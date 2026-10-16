package dev.offlinesync.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.offlinesync.core.DocumentDelta
import dev.offlinesync.core.RegisterValue
import dev.offlinesync.core.WireJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

@RunWith(AndroidJUnit4::class)
class MutationQueueTests {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun competingDatabaseHandlesCannotClaimTheSameMutationOrOvertakeAnEntity() = runTest {
        val account = UUID.randomUUID().toString()
        val first = SyncDatabase.open(context, account)
        val second = SyncDatabase.open(context, account)
        try {
            val entity = UUID.randomUUID().toString()
            val store = LocalSyncStore(first) { 100 }
            val head = store.edit(entity) { title(it) }
            store.edit(entity) { title(it) }
            val unrelated = store.edit(UUID.randomUUID().toString()) { title(it) }
            val claims = listOf(first, second).map { database ->
                async(Dispatchers.IO) { MutationQueue(database, { 100 }).claim() }
            }.awaitAll().flatten()
            assertEquals(setOf(head.mutationId, unrelated.mutationId), claims.map { it.mutation.mutationId }.toSet())
            assertEquals(2, claims.size)
            assertTrue(MutationQueue(first, { 100 }).claim().isEmpty())
        } finally {
            second.close()
            first.close()
        }
    }

    @Test
    fun expiredLeaseRecoversAfterReopenAndFencesTheOldWorker() = runTest {
        val account = UUID.randomUUID().toString()
        val first = SyncDatabase.open(context, account)
        val mutation = LocalSyncStore(first) { 100 }.edit(UUID.randomUUID().toString()) { title(it) }
        val oldLease = MutationQueue(first, { 100 }).claim(leaseDurationMs = 10).single()
        first.close()
        val reopened = SyncDatabase.open(context, account)
        try {
            val queue = MutationQueue(reopened, { 110 })
            val recovered = queue.claim().single()
            assertEquals(mutation, recovered.mutation)
            assertEquals(WireJson.hash(mutation), WireJson.hash(recovered.mutation))
            assertFalse(oldLease.token == recovered.token)
            assertFalse(queue.fail(oldLease, QueueFailure.REJECTED, "STALE_WORKER"))
            assertEquals(QueueState.IN_FLIGHT, queue.entries.first().single().state)
            assertEquals(2L, queue.entries.first().single().attempts)
        } finally {
            reopened.close()
        }
    }

    @Test
    fun retryDeadlineAndAttemptsPersistWithoutBlockingOtherEntities() = runTest {
        val account = UUID.randomUUID().toString()
        val database = SyncDatabase.open(context, account)
        val now = AtomicLong(100)
        val store = LocalSyncStore(database) { 100 }
        val entity = UUID.randomUUID().toString()
        val mutation = store.edit(entity) { title(it) }
        store.edit(entity) { title(it) }
        val queue = MutationQueue(database, now::get, Random(421))
        val lease = queue.claim().single()
        assertTrue(queue.fail(lease, QueueFailure.TRANSIENT, "NETWORK_ERROR"))
        val retry = queue.entries.first().first()
        assertTrue(retry.nextAttemptMs in 600..1100)
        assertEquals(1L, retry.attempts)
        val unrelated = store.edit(UUID.randomUUID().toString()) { title(it) }
        assertEquals(unrelated, queue.claim().single().mutation)
        database.close()
        val reopened = SyncDatabase.open(context, account)
        try {
            val recovered = MutationQueue(reopened, now::get, Random(421))
            assertEquals(retry.nextAttemptMs, recovered.entries.first().first().nextAttemptMs)
            now.set(retry.nextAttemptMs - 1)
            assertTrue(recovered.claim().isEmpty())
            now.incrementAndGet()
            val next = recovered.claim().single()
            assertEquals(mutation, next.mutation)
            assertEquals(2L, recovered.entries.first().first().attempts)
            assertTrue(recovered.fail(next, QueueFailure.TRANSIENT, "NETWORK_ERROR"))
            val nextDeadline = recovered.entries.first().first().nextAttemptMs
            assertTrue(nextDeadline - now.get() in 1000..2000)
        } finally {
            reopened.close()
        }
    }

    @Test
    fun repeatedTransientFailuresCapBackoffWithoutDroppingWork() = runTest {
        val database = SyncDatabase.open(context, UUID.randomUUID().toString())
        try {
            val now = AtomicLong(100)
            val mutation = LocalSyncStore(database) { 100 }.edit(UUID.randomUUID().toString()) { title(it) }
            val queue = MutationQueue(database, now::get, Random(42))
            repeat(32) { attempt ->
                val lease = queue.claim().single()
                assertEquals(mutation, lease.mutation)
                assertTrue(queue.fail(lease, QueueFailure.TRANSIENT, "NETWORK_ERROR"))
                val entry = queue.entries.first().single()
                val ceiling = minOf(3_600_000L, 1_000L shl minOf(attempt, 12))
                assertEquals(attempt.toLong() + 1, entry.attempts)
                assertEquals(QueueState.PENDING, entry.state)
                assertTrue(entry.nextAttemptMs - now.get() in ceiling / 2..ceiling)
                now.set(entry.nextAttemptMs)
            }
            assertEquals(mutation, queue.claim().single().mutation)
        } finally {
            database.close()
        }
    }

    @Test
    fun authenticationPausePersistsAndTerminalFailureBlocksOnlyItsEntity() = runTest {
        val account = UUID.randomUUID().toString()
        val database = SyncDatabase.open(context, account)
        val store = LocalSyncStore(database) { 100 }
        val entity = UUID.randomUUID().toString()
        store.edit(entity) { title(it) }
        store.edit(entity) { title(it) }
        val queue = MutationQueue(database, { 100 })
        val lease = queue.claim().single()
        assertTrue(queue.fail(lease, QueueFailure.AUTHENTICATION, "TOKEN_EXPIRED"))
        database.close()
        val reopened = SyncDatabase.open(context, account)
        try {
            val recovered = MutationQueue(reopened, { 100 })
            assertTrue(recovered.claim().isEmpty())
            recovered.resumeAfterAuthentication()
            val retried = recovered.claim().single()
            assertTrue(recovered.fail(retried, QueueFailure.REJECTED, "INVALID_DELTA"))
            assertFalse(recovered.fail(retried, QueueFailure.TRANSIENT, "STALE_WORKER"))
            assertEquals("INVALID_DELTA", recovered.entries.first().first().errorCode)
            assertEquals(QueueState.REJECTED, recovered.entries.first().first().state)
            val unrelated = LocalSyncStore(reopened) { 100 }.edit(UUID.randomUUID().toString()) { title(it) }
            val unrelatedLease = recovered.claim().single()
            assertEquals(unrelated, unrelatedLease.mutation)
            assertTrue(recovered.fail(unrelatedLease, QueueFailure.CONFLICT, "USER_ACTION_REQUIRED"))
            assertEquals(QueueState.CONFLICT, recovered.entries.first().last().state)
            assertTrue(recovered.claim().isEmpty())
        } finally {
            reopened.close()
        }
    }

    private fun title(edit: LocalEditContext) = DocumentDelta(
        registers = mapOf("title" to RegisterValue(JsonPrimitive("queued"), edit.stamp)),
    )
}