package dev.offlinesync.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.offlinesync.core.DocumentDelta
import dev.offlinesync.core.HybridTimestamp
import dev.offlinesync.core.MutationAck
import dev.offlinesync.core.PnCounter
import dev.offlinesync.core.PullResponse
import dev.offlinesync.core.RegisterValue
import dev.offlinesync.core.SyncChange
import dev.offlinesync.core.WireJson
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

@RunWith(AndroidJUnit4::class)
class SyncResponseStoreTests {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val remote = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"

    @Test
    fun acknowledgmentPreservesNewerEditsAndReleasesTheNextEntityHead() = runTest {
        val database = SyncDatabase.open(context, UUID.randomUUID().toString())
        try {
            val store = LocalSyncStore(database) { 100 }
            val queue = MutationQueue(database, { 100 })
            val responses = SyncResponseStore(database) { 100 }
            val entity = UUID.randomUUID().toString()
            val first = store.edit(entity) { title("first", it.stamp) }
            val lease = queue.claim().single()
            val second = store.edit(entity) { title("second", it.stamp) }
            assertTrue(responses.acknowledge(lease, MutationAck(first.mutationId, "SYNCED", 8, first.delta)))
            assertEquals(JsonPrimitive("second"), store.observeDocument(entity).first()?.registers?.get("title")?.value)
            assertEquals(listOf(QueueState.SYNCED, QueueState.PENDING), queue.entries.first().map { it.state })
            assertNull(responses.pullCursor())
            val next = queue.claim().single()
            assertEquals(second, next.mutation)
            assertTrue(responses.acknowledge(next, MutationAck(second.mutationId, "REJECTED", errorCode = "INVALID_TITLE")))
            assertEquals(JsonPrimitive("first"), store.observeDocument(entity).first()?.registers?.get("title")?.value)
            assertEquals(QueueState.REJECTED, queue.entries.first().last().state)
        } finally {
            database.close()
        }
    }

    @Test
    fun losingLwwAcknowledgmentIsSyncedAndReceivedClockSurvivesReopen() = runTest {
        val account = UUID.randomUUID().toString()
        val database = SyncDatabase.open(context, account)
        val entity = UUID.randomUUID().toString()
        val mutation = LocalSyncStore(database) { 100 }.edit(entity) { title("losing", it.stamp) }
        val lease = MutationQueue(database, { 100 }).claim().single()
        val winner = HybridTimestamp(2000, 7, remote)
        assertTrue(SyncResponseStore(database) { 100 }.acknowledge(lease,
            MutationAck(mutation.mutationId, "SYNCED", 9, title("winner", winner))))
        assertEquals(QueueState.SYNCED, MutationQueue(database).entries.first().single().state)
        assertEquals(JsonPrimitive("winner"), LocalSyncStore(database).observeDocument(entity).first()?.registers?.get("title")?.value)
        database.close()
        val reopened = SyncDatabase.open(context, account)
        try {
            val next = LocalSyncStore(reopened) { 50 }.edit(entity) { title("later", it.stamp) }
            assertTrue(next.delta.registers.getValue("title").stamp > winner)
        } finally {
            reopened.close()
        }
    }

    @Test
    fun staleAndDuplicateAcknowledgmentsCannotChangeQueueOrCursor() = runTest {
        val database = SyncDatabase.open(context, UUID.randomUUID().toString())
        try {
            val now = AtomicLong(100)
            val mutation = LocalSyncStore(database) { 100 }.edit(UUID.randomUUID().toString()) { title("one", it.stamp) }
            val queue = MutationQueue(database, now::get)
            val responses = SyncResponseStore(database, now::get)
            val old = queue.claim(leaseDurationMs = 10).single()
            val ack = MutationAck(mutation.mutationId, "SYNCED", 1, mutation.delta)
            now.set(110)
            assertFalse(responses.acknowledge(old, ack))
            val current = queue.claim().single()
            assertFalse(responses.acknowledge(old, ack))
            assertTrue(responses.acknowledge(current, ack))
            assertFalse(responses.acknowledge(current, ack))
            assertNull(responses.pullCursor())
            assertEquals(1, queue.entries.first().size)
        } finally {
            database.close()
        }
    }

    @Test
    fun acknowledgmentFailureRollsBackQueueAndConfirmedState() = runTest {
        val database = SyncDatabase.open(context, UUID.randomUUID().toString())
        try {
            val store = LocalSyncStore(database) { 100 }
            val entity = UUID.randomUUID().toString()
            val mutation = store.edit(entity) { title("local", it.stamp) }
            val lease = MutationQueue(database, { 100 }).claim().single()
            val before = database.documents().get(entity)
            val clock = database.metadata().get("clock")
            database.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER reject_projection BEFORE INSERT ON sync_documents BEGIN SELECT RAISE(ABORT, 'injected failure'); END",
            )
            val ack = MutationAck(mutation.mutationId, "SYNCED", 1, mutation.delta)
            assertTrue(runCatching { SyncResponseStore(database) { 100 }.acknowledge(lease, ack) }.isFailure)
            assertEquals(QueueState.IN_FLIGHT, MutationQueue(database).entries.first().single().state)
            assertEquals(before, database.documents().get(entity))
            assertEquals(clock, database.metadata().get("clock"))
            database.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_projection")
            assertTrue(SyncResponseStore(database) { 100 }.acknowledge(lease, ack))
        } finally {
            database.close()
        }
    }

    @Test
    fun pullPreservesPendingOverlayAndRejectsStaleOrUnorderedPages() = runTest {
        val database = SyncDatabase.open(context, UUID.randomUUID().toString())
        try {
            val store = LocalSyncStore(database) { 100 }
            val responses = SyncResponseStore(database) { 100 }
            val entity = UUID.randomUUID().toString()
            store.edit(entity) { title("newer local", it.stamp) }
            val page = PullResponse(listOf(SyncChange(1, entity, title("older remote", HybridTimestamp(50, 0, remote)))), "cursor-1", true)
            assertTrue(responses.applyPull(null, page))
            assertEquals(JsonPrimitive("newer local"), store.observeDocument(entity).first()?.registers?.get("title")?.value)
            assertEquals("cursor-1", responses.pullCursor())
            assertFalse(responses.applyPull(null, page))
            val before = database.documents().get(entity)
            val invalid = PullResponse(listOf(
                SyncChange(2, entity, DocumentDelta(deleted = true)),
                SyncChange(1, entity, DocumentDelta(deleted = true)),
            ), "bad-cursor", false)
            assertTrue(runCatching { responses.applyPull("cursor-1", invalid) }.isFailure)
            assertEquals(before, database.documents().get(entity))
            assertEquals("cursor-1", responses.pullCursor())
            assertTrue(responses.applyPull("cursor-1", PullResponse(emptyList(), "cursor-1", false)))
        } finally {
            database.close()
        }
    }

    @Test
    fun cursorWriteFailureRollsBackEntirePageAndClockThenReopens() = runTest {
        val account = UUID.randomUUID().toString()
        val database = SyncDatabase.open(context, account)
        val entity = UUID.randomUUID().toString()
        val page = PullResponse(listOf(SyncChange(1, entity, title("remote", HybridTimestamp(1000, 5, remote)))), "cursor-1", false)
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_cursor BEFORE INSERT ON sync_metadata WHEN NEW.name = 'pullCursor' BEGIN SELECT RAISE(ABORT, 'injected failure'); END",
        )
        val responses = SyncResponseStore(database) { 100 }
        assertTrue(runCatching { responses.applyPull(null, page) }.isFailure)
        assertNull(database.documents().get(entity))
        assertNull(database.metadata().get("clock"))
        assertNull(database.metadata().get("replicaId"))
        assertNull(database.metadata().get("pullRevision"))
        assertNull(responses.pullCursor())
        database.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_cursor")
        assertTrue(responses.applyPull(null, page))
        database.close()
        val reopened = SyncDatabase.open(context, account)
        try {
            assertEquals("cursor-1", SyncResponseStore(reopened).pullCursor())
            assertEquals(JsonPrimitive("remote"), LocalSyncStore(reopened).observeDocument(entity).first()?.registers?.get("title")?.value)
            val next = LocalSyncStore(reopened) { 50 }.edit(entity) { title("later", it.stamp) }
            assertTrue(next.delta.registers.getValue("title").stamp > HybridTimestamp(1000, 5, remote))
        } finally {
            reopened.close()
        }
    }

    @Test
    fun counterPullDoesNotDoubleApplyPendingComponentsAndDeletionNeverResurrects() = runTest {
        val database = SyncDatabase.open(context, UUID.randomUUID().toString())
        try {
            val store = LocalSyncStore(database) { 100 }
            val responses = SyncResponseStore(database) { 100 }
            val entity = UUID.randomUUID().toString()
            val mutation = store.edit(entity) {
                DocumentDelta(counters = mapOf("quantity" to PnCounter(mapOf(it.stamp.replicaId to 3))))
            }
            val remoteDelta = DocumentDelta(counters = mapOf("quantity" to PnCounter(mapOf(remote to 4))))
            assertTrue(responses.applyPull(null, PullResponse(listOf(SyncChange(1, entity, remoteDelta)), "cursor-1", false)))
            assertEquals(7L, store.observeDocument(entity).first()?.counters?.get("quantity")?.value)
            val lease = MutationQueue(database, { 100 }).claim().single()
            assertTrue(responses.acknowledge(lease, MutationAck(mutation.mutationId, "SYNCED", 2, remoteDelta.merge(mutation.delta))))
            assertEquals(7L, store.observeDocument(entity).first()?.counters?.get("quantity")?.value)
            store.edit(entity) { title("pending", it.stamp) }
            assertTrue(responses.applyPull("cursor-1", PullResponse(listOf(
                SyncChange(2, entity, mutation.delta), SyncChange(3, entity, DocumentDelta(deleted = true)),
            ), "cursor-3", false)))
            assertTrue(requireNotNull(store.observeDocument(entity).first()).deleted)
            assertTrue(runCatching { store.edit(entity) { title("resurrect", it.stamp) } }.isFailure)
            val base = WireJson.format.decodeFromString<DocumentDelta>(requireNotNull(database.documents().get(entity)).confirmed)
            assertTrue(base.deleted)
            assertEquals("cursor-3", responses.pullCursor())
        } finally {
            database.close()
        }
    }

    private fun title(value: String, stamp: HybridTimestamp) = DocumentDelta(
        registers = mapOf("title" to RegisterValue(JsonPrimitive(value), stamp)),
    )
}