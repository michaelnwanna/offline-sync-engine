package dev.offlinesync.android

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import dev.offlinesync.core.DocumentDelta
import dev.offlinesync.core.PullResponse
import dev.offlinesync.core.PushRequest
import dev.offlinesync.core.PushResponse
import dev.offlinesync.core.RegisterValue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SyncSchedulerTests {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun initialize() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build())
    }

    @Test
    fun requestsAreConnectedSerialAccountScopedAndCancellable() {
        val scheduler = SyncScheduler(context)
        val manager = WorkManager.getInstance(context)
        scheduler.start("first").result.get()
        scheduler.start("first").result.get()
        scheduler.requestSync("first").result.get()
        scheduler.requestSync("first").result.get()
        scheduler.requestSync("second").result.get()
        val first = manager.getWorkInfosForUniqueWork("offline-sync:first:run").get()
        assertEquals(2, first.size)
        assertEquals(setOf(WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED), first.map { it.state }.toSet())
        assertTrue(first.all { it.constraints.requiredNetworkType == NetworkType.CONNECTED })
        assertEquals(1, manager.getWorkInfosForUniqueWork("offline-sync:first:periodic").get().size)
        scheduler.cancel("first").result.get()
        assertTrue(manager.getWorkInfosByTag("offline-sync:first").get().all { it.state == WorkInfo.State.CANCELLED })
        assertEquals(WorkInfo.State.ENQUEUED,
            manager.getWorkInfosForUniqueWork("offline-sync:second:run").get().single().state)
    }

    @Test
    fun delayedWakeDoesNotBlockImmediatePassAndReplacesOldDeadline() {
        val scheduler = SyncScheduler(context)
        val manager = WorkManager.getInstance(context)
        val now = System.currentTimeMillis()
        scheduler.wakeAt("account", now + 120_000, now).result.get()
        val old = manager.getWorkInfosForUniqueWork("offline-sync:account:deadline").get().single()
        scheduler.wakeAt("account", now + 60_000, now).result.get()
        scheduler.requestSync("account").result.get()
        val deadlines = manager.getWorkInfosForUniqueWork("offline-sync:account:deadline").get()
        val replaced = manager.getWorkInfoById(old.id).get()
        assertTrue(replaced == null || replaced.state == WorkInfo.State.CANCELLED)
        assertEquals(1, deadlines.count { !it.state.isFinished })
        assertEquals(WorkInfo.State.ENQUEUED,
            manager.getWorkInfosForUniqueWork("offline-sync:account:run").get().single().state)
    }

    @Test
    fun deadlineIgnoresBlockedDescendantsAndSurvivesReopen() = runTest {
        val account = UUID.randomUUID().toString()
        val database = SyncDatabase.open(context, account)
        val queue = MutationQueue(database, { 100 })
        val store = LocalSyncStore(database) { 100 }
        val entity = UUID.randomUUID().toString()
        repeat(2) { sequence ->
            store.edit(entity) { edit ->
                DocumentDelta(registers = mapOf("title" to RegisterValue(JsonPrimitive("edit-$sequence"), edit.stamp)))
            }
        }
        val lease = queue.claim(leaseDurationMs = 500).single()
        assertEquals(600L, queue.nextWakeAtMs())
        queue.fail(lease, QueueFailure.TRANSIENT, "NETWORK_ERROR")
        val deadline = queue.entries.first().first().nextAttemptMs
        assertEquals(deadline, queue.nextWakeAtMs())
        queue.pauseBackgroundSync()
        database.close()
        val reopened = SyncDatabase.open(context, account)
        try {
            val recovered = MutationQueue(reopened)
            assertNull(recovered.nextWakeAtMs())
            assertTrue(recovered.isBackgroundSyncPaused())
            recovered.resumeBackgroundSync()
            assertEquals(deadline, recovered.nextWakeAtMs())
            val head = recovered.claim().single()
            recovered.fail(head, QueueFailure.REJECTED, "INVALID_DELTA")
            assertNull(recovered.nextWakeAtMs())
        } finally {
            reopened.close()
        }
    }

    @Test
    fun workerPersistsActionPauseAndClosesSession() = runTest {
        val database = SyncDatabase.open(context, UUID.randomUUID().toString())
        try {
            val queue = MutationQueue(database)
            var calls = 0
            val transport = transport { calls++; throw SyncProtocolException() }
            val host = TestHost(context, queue, SyncRunner(queue, SyncResponseStore(database), transport))
            assertTrue(worker(host).doWork() is ListenableWorker.Result.Failure)
            assertTrue(queue.isBackgroundSyncPaused())
            assertEquals(1, host.closed)
            assertTrue(worker(host).doWork() is ListenableWorker.Result.Success)
            assertEquals(1, calls)
            assertEquals(2, host.closed)
            queue.resumeBackgroundSync()
            assertFalse(queue.isBackgroundSyncPaused())
        } finally {
            database.close()
        }
    }

    @Test
    fun workerRetriesTransientFailureAndStopsOnAuthenticationPause() = runTest {
        val database = SyncDatabase.open(context, UUID.randomUUID().toString())
        try {
            val queue = MutationQueue(database)
            var authFailure = false
            var calls = 0
            val transport = transport {
                calls++
                if (authFailure) throw SyncAuthenticationException() else throw IOException()
            }
            val host = TestHost(context, queue, SyncRunner(queue, SyncResponseStore(database), transport))
            assertTrue(worker(host).doWork() is ListenableWorker.Result.Retry)
            authFailure = true
            assertTrue(worker(host).doWork() is ListenableWorker.Result.Success)
            assertTrue(queue.isAuthenticationPaused())
            assertTrue(worker(host).doWork() is ListenableWorker.Result.Success)
            assertEquals(2, calls)
            assertEquals(3, host.closed)
        } finally {
            database.close()
        }
    }

    @Test
    fun completedPassSchedulesPersistedLeaseExpiry() = runTest {
        val database = SyncDatabase.open(context, UUID.randomUUID().toString())
        try {
            val queue = MutationQueue(database)
            LocalSyncStore(database).edit(UUID.randomUUID().toString()) { DocumentDelta(deleted = true) }
            queue.claim()
            val host = TestHost(context, queue, SyncRunner(queue, SyncResponseStore(database),
                transport { PullResponse(emptyList(), "current", false) }))
            assertTrue(worker(host).doWork() is ListenableWorker.Result.Success)
            val deadline = WorkManager.getInstance(context)
                .getWorkInfosForUniqueWork("offline-sync:account:deadline").get().single()
            assertTrue(deadline.nextScheduleTimeMillis > System.currentTimeMillis())
            assertEquals(1, host.closed)
        } finally {
            database.close()
        }
    }

    @Test
    fun removedAccountDoesNotOpenSessionOrSendRequests() = runTest {
        val host = object : ContextWrapper(context), SyncWorkerHost {
            override fun getApplicationContext(): Context = this
            override suspend fun openSyncSession(scopeId: String): SyncWorkerSession? = null
        }
        assertTrue(worker(host).doWork() is ListenableWorker.Result.Success)
    }

    private fun worker(host: Context) = TestListenableWorkerBuilder<SyncWorker>(host)
        .setInputData(workDataOf(SCOPE_ID to "account")).build()

    private fun transport(pull: suspend () -> PullResponse) = object : SyncTransport {
        override suspend fun push(request: PushRequest): PushResponse = error("Unexpected push")
        override suspend fun pull(cursor: String?, limit: Int): PullResponse = pull()
    }

    private class TestHost(context: Context, private val queue: MutationQueue, private val runner: SyncRunner) :
        ContextWrapper(context), SyncWorkerHost {
        var closed = 0
        override fun getApplicationContext(): Context = this
        override suspend fun openSyncSession(scopeId: String): SyncWorkerSession {
            assertEquals("account", scopeId)
            return object : SyncWorkerSession {
                override val queue = this@TestHost.queue
                override val runner = this@TestHost.runner
                override fun close() { closed++ }
            }
        }
    }
}