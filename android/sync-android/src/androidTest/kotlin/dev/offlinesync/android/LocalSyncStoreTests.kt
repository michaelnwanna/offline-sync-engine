package dev.offlinesync.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.offlinesync.core.DocumentDelta
import dev.offlinesync.core.RegisterValue
import dev.offlinesync.core.WireJson
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class LocalSyncStoreTests {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun editsAndImmutableOutboxSurviveReopenAndClockRollback() = runTest {
        val account = UUID.randomUUID().toString()
        val entityId = UUID.randomUUID().toString()
        val database = SyncDatabase.open(context, account)
        val store = LocalSyncStore(database) { 100 }
        val first = store.edit(entityId) { title("first", it) }
        val second = store.edit(entityId) { title("second", it) }
        assertEquals(2, store.pendingCount.first())
        assertEquals(listOf(first, second), store.pendingMutations())
        assertEquals(2L, second.localSequence)
        database.close()
        val reopened = SyncDatabase.open(context, account)
        try {
            val recovered = LocalSyncStore(reopened) { 50 }
            assertEquals(listOf(first, second), recovered.pendingMutations())
            assertEquals(JsonPrimitive("second"), recovered.observeDocument(entityId).first()?.registers?.get("title")?.value)
            val third = recovered.edit(entityId) { title("third", it) }
            assertEquals(first.replicaId, third.replicaId)
            assertEquals(3L, third.localSequence)
            assertTrue(third.delta.registers.getValue("title").stamp > second.delta.registers.getValue("title").stamp)
            assertEquals(WireJson.hash(first), WireJson.hash(recovered.pendingMutations().first()))
        } finally {
            reopened.close()
        }
    }

    @Test
    fun outboxInsertFailureRollsBackProjectionSequenceAndClock() = runTest {
        val database = SyncDatabase.open(context, UUID.randomUUID().toString())
        try {
            val store = LocalSyncStore(database) { 100 }
            val entityId = UUID.randomUUID().toString()
            database.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER reject_outbox BEFORE INSERT ON sync_outbox BEGIN SELECT RAISE(ABORT, 'injected failure'); END",
            )
            val result = runCatching { store.edit(entityId) { title("rolled back", it) } }
            assertTrue(result.isFailure)
            assertNull(store.observeDocument(entityId).first())
            assertTrue(store.pendingMutations().isEmpty())
            assertNull(database.metadata().get("clock"))
            assertNull(database.metadata().get("replicaId"))
            assertNull(database.metadata().get("localSequence"))
            database.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_outbox")
            assertEquals(1L, store.edit(entityId) { title("committed", it) }.localSequence)
        } finally {
            database.close()
        }
    }

    @Test
    fun accountsHaveIndependentQueuesAndReplicaIdentities() = runTest {
        val first = SyncDatabase.open(context, UUID.randomUUID().toString())
        val second = SyncDatabase.open(context, UUID.randomUUID().toString())
        try {
            val entityId = UUID.randomUUID().toString()
            LocalSyncStore(first).edit(entityId) { title("private", it) }
            assertTrue(LocalSyncStore(second).pendingMutations().isEmpty())
            assertNull(LocalSyncStore(second).observeDocument(entityId).first())
        } finally {
            first.close()
            second.close()
        }
    }

    private fun title(value: String, edit: LocalEditContext) = DocumentDelta(
        registers = mapOf("title" to RegisterValue(JsonPrimitive(value), edit.stamp)),
    )
}