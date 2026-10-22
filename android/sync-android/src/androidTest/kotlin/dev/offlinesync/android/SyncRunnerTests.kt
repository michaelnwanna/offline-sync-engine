package dev.offlinesync.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.offlinesync.core.DocumentDelta
import dev.offlinesync.core.MutationAck
import dev.offlinesync.core.PullResponse
import dev.offlinesync.core.PushRequest
import dev.offlinesync.core.PushResponse
import dev.offlinesync.core.RegisterValue
import dev.offlinesync.core.SyncChange
import dev.offlinesync.core.WireJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

@RunWith(AndroidJUnit4::class)
class SyncRunnerTests {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun runnerDrainsOrderedEditsAndAppliesPaginatedPulls() = runTest {
        val database = SyncDatabase.open(context, UUID.randomUUID().toString())
        try {
            val store = LocalSyncStore(database) { 100 }
            val entity = UUID.randomUUID().toString()
            val first = edit(store, entity, "first")
            val second = edit(store, entity, "second")
            val transport = FakeTransport()
            val cursors = mutableListOf<String?>()
            transport.onPull = { cursor ->
                cursors.add(cursor)
                if (cursor == null) PullResponse(listOf(SyncChange(1, entity, first.delta)), "page-1", true)
                else PullResponse(listOf(SyncChange(2, entity, second.delta)), "page-2", false)
            }
            val result = runner(database, transport).run()
            assertEquals(SyncRunOutcome.COMPLETE, result.outcome)
            assertEquals(2, result.acknowledged)
            assertEquals(2, result.pullPages)
            assertEquals(listOf(first, second), transport.pushes.flatMap { it.mutations })
            assertEquals(listOf(null, "page-1"), cursors)
            assertTrue(MutationQueue(database).entries.first().all { it.state == QueueState.SYNCED })
            assertEquals("page-2", SyncResponseStore(database).pullCursor())
            assertEquals(JsonPrimitive("second"), store.observeDocument(entity).first()?.registers?.get("title")?.value)
        } finally {
            database.close()
        }
    }

    @Test
    fun lostAcknowledgmentRetriesTheExactMutationAfterReopen() = runTest {
        val account = UUID.randomUUID().toString()
        val database = SyncDatabase.open(context, account)
        val now = AtomicLong(100)
        val original = edit(LocalSyncStore(database) { 100 }, UUID.randomUUID().toString(), "offline")
        val transport = FakeTransport()
        transport.onPush = { throw IOException("ack lost after server commit") }
        assertEquals(SyncRunOutcome.RETRY, runner(database, transport, now::get).run().outcome)
        val entry = MutationQueue(database).entries.first().single()
        assertEquals(QueueState.PENDING, entry.state)
        database.close()
        val reopened = SyncDatabase.open(context, account)
        try {
            transport.onPush = transport::success
            now.set(entry.nextAttemptMs)
            assertEquals(SyncRunOutcome.COMPLETE, runner(reopened, transport, now::get).run().outcome)
            assertEquals(listOf(original, original), transport.pushes.flatMap { it.mutations })
            assertEquals(WireJson.hash(original), WireJson.hash(transport.pushes.last().mutations.single()))
            assertEquals(QueueState.SYNCED, MutationQueue(reopened).entries.first().single().state)
        } finally {
            reopened.close()
        }
    }

    @Test
    fun emptyAcknowledgmentRetainsPayloadAndPersistsRetry() = runTest {
        val database = SyncDatabase.open(context, UUID.randomUUID().toString())
        try {
            val original = edit(LocalSyncStore(database) { 100 }, UUID.randomUUID().toString(), "pending")
            val transport = FakeTransport().apply { onPush = { PushResponse(emptyList()) } }
            val result = runner(database, transport).run()
            assertEquals(SyncRunOutcome.RETRY, result.outcome)
            assertEquals(0, result.acknowledged)
            val entry = MutationQueue(database).entries.first().single()
            assertEquals(original, entry.mutation)
            assertEquals(QueueState.PENDING, entry.state)
            assertTrue(entry.nextAttemptMs > 100)
            assertNull(SyncResponseStore(database).pullCursor())
        } finally {
            database.close()
        }
    }

    @Test
    fun pullOnlyAuthenticationFailurePersistsPauseUntilCredentialsResume() = runTest {
        val account = UUID.randomUUID().toString()
        val database = SyncDatabase.open(context, account)
        val transport = FakeTransport().apply { onPull = { throw SyncAuthenticationException() } }
        assertEquals(SyncRunOutcome.AUTHENTICATION_REQUIRED, runner(database, transport).run().outcome)
        database.close()
        val reopened = SyncDatabase.open(context, account)
        try {
            assertEquals(SyncRunOutcome.AUTHENTICATION_REQUIRED, runner(reopened, transport).run().outcome)
            assertEquals(1, transport.pulls)
            MutationQueue(reopened).resumeAfterAuthentication()
            transport.onPull = { PullResponse(emptyList(), "current", false) }
            assertEquals(SyncRunOutcome.COMPLETE, runner(reopened, transport).run().outcome)
            assertEquals(2, transport.pulls)
        } finally {
            reopened.close()
        }
    }

    @Test
    fun pushAuthenticationAndPermanentHttpErrorsDoNotDiscardMutation() = runTest {
        val database = SyncDatabase.open(context, UUID.randomUUID().toString())
        try {
            val now = AtomicLong(100)
            val mutation = edit(LocalSyncStore(database) { 100 }, UUID.randomUUID().toString(), "retained")
            val transport = FakeTransport().apply { onPush = { throw SyncAuthenticationException() } }
            assertEquals(SyncRunOutcome.AUTHENTICATION_REQUIRED, runner(database, transport, now::get).run().outcome)
            assertTrue(MutationQueue(database).isAuthenticationPaused())
            MutationQueue(database).resumeAfterAuthentication()
            transport.onPush = { throw SyncHttpException(400) }
            assertEquals(SyncRunOutcome.ACTION_REQUIRED, runner(database, transport, now::get).run().outcome)
            var entry = MutationQueue(database).entries.first().single()
            assertEquals(QueueState.PENDING, entry.state)
            assertEquals(mutation, entry.mutation)
            now.set(entry.nextAttemptMs)
            transport.onPush = { throw SyncProtocolException() }
            assertEquals(SyncRunOutcome.ACTION_REQUIRED, runner(database, transport, now::get).run().outcome)
            entry = MutationQueue(database).entries.first().single()
            assertEquals(mutation, entry.mutation)
            assertEquals(QueueState.PENDING, entry.state)
        } finally {
            database.close()
        }
    }

    @Test
    fun cancelledCallKeepsLeaseAndRecoversAfterExpiry() = runTest {
        val database = SyncDatabase.open(context, UUID.randomUUID().toString())
        try {
            val now = AtomicLong(100)
            edit(LocalSyncStore(database) { 100 }, UUID.randomUUID().toString(), "retained")
            val transport = FakeTransport().apply { onPush = { throw CancellationException("worker stopped") } }
            val failure = runCatching { runner(database, transport, now::get).run() }.exceptionOrNull()
            assertTrue(failure is CancellationException)
            val entry = MutationQueue(database).entries.first().single()
            assertEquals(QueueState.IN_FLIGHT, entry.state)
            now.set(requireNotNull(entry.leaseUntilMs))
            transport.onPush = transport::success
            assertEquals(SyncRunOutcome.COMPLETE, runner(database, transport, now::get).run().outcome)
        } finally {
            database.close()
        }
    }

    @Test
    fun editDuringNetworkCallSurvivesAcknowledgmentAndRunBounds() = runTest {
        val database = SyncDatabase.open(context, UUID.randomUUID().toString())
        try {
            val store = LocalSyncStore(database) { 100 }
            val entity = UUID.randomUUID().toString()
            edit(store, entity, "first")
            val transport = FakeTransport()
            transport.onPush = { request ->
                edit(store, entity, "new while sending")
                transport.success(request)
            }
            assertEquals(SyncRunOutcome.MORE_WORK, runner(database, transport).run(maxPushes = 1).outcome)
            assertEquals(JsonPrimitive("new while sending"), store.observeDocument(entity).first()?.registers?.get("title")?.value)
            transport.onPush = transport::success
            var revision = 0L
            transport.onPull = {
                revision++
                PullResponse(listOf(SyncChange(revision, entity, DocumentDelta())), "cursor-$revision", true)
            }
            val bounded = runner(database, transport).run(maxPullPages = 2)
            assertEquals(SyncRunOutcome.MORE_WORK, bounded.outcome)
            assertEquals(2, bounded.pullPages)
            assertEquals("cursor-2", SyncResponseStore(database).pullCursor())
        } finally {
            database.close()
        }
    }

    @Test
    fun malformedAcknowledgmentAndStalePullNeverAdvanceState() = runTest {
        val database = SyncDatabase.open(context, UUID.randomUUID().toString())
        try {
            val now = AtomicLong(100)
            edit(LocalSyncStore(database) { 100 }, UUID.randomUUID().toString(), "retained")
            val transport = FakeTransport().apply {
                onPush = { request -> PushResponse(listOf(MutationAck(request.mutations.single().mutationId, "SYNCED"))) }
            }
            assertEquals(SyncRunOutcome.ACTION_REQUIRED, runner(database, transport, now::get).run().outcome)
            val entry = MutationQueue(database).entries.first().single()
            assertEquals(QueueState.PENDING, entry.state)
            now.set(entry.nextAttemptMs)
            transport.onPush = transport::success
            transport.onPull = {
                SyncResponseStore(database) { now.get() }.applyPull(null, PullResponse(emptyList(), "other-worker", false))
                PullResponse(emptyList(), "stale-page", false)
            }
            assertEquals(SyncRunOutcome.STALE_RESPONSE, runner(database, transport, now::get).run().outcome)
            assertEquals("other-worker", SyncResponseStore(database).pullCursor())
            assertFalse(MutationQueue(database).isAuthenticationPaused())
        } finally {
            database.close()
        }
    }

    private fun runner(database: SyncDatabase, transport: SyncTransport, now: () -> Long = { 100 }) =
        SyncRunner(MutationQueue(database, now), SyncResponseStore(database, now), transport)

    private suspend fun edit(store: LocalSyncStore, entity: String, value: String) = store.edit(entity) {
        DocumentDelta(registers = mapOf("title" to RegisterValue(JsonPrimitive(value), it.stamp)))
    }

    private class FakeTransport : SyncTransport {
        val pushes = mutableListOf<PushRequest>()
        var pulls = 0
        var onPush: suspend (PushRequest) -> PushResponse = ::success
        var onPull: suspend (String?) -> PullResponse = { PullResponse(emptyList(), "current", false) }

        fun success(request: PushRequest) = PushResponse(request.mutations.map {
            MutationAck(it.mutationId, "SYNCED", it.localSequence, it.delta)
        })

        override suspend fun push(request: PushRequest): PushResponse {
            pushes.add(request)
            return onPush(request)
        }

        override suspend fun pull(cursor: String?, limit: Int): PullResponse {
            pulls++
            return onPull(cursor)
        }
    }
}