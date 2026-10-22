package dev.offlinesync.sample

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.offlinesync.android.LocalSyncStore
import dev.offlinesync.android.MutationQueue
import dev.offlinesync.android.QueueState
import dev.offlinesync.android.SyncDatabase
import dev.offlinesync.android.SyncResponseStore
import dev.offlinesync.android.SyncRunOutcome
import dev.offlinesync.android.SyncRunner
import dev.offlinesync.core.DocumentDelta
import dev.offlinesync.core.MutationValidation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeviceConvergenceTests {
    @Test
    fun independentInstallationsConverge() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Enable only against the dedicated interop host", arguments.getString("serverInterop") == "true")
        val workspace = requireNotNull(arguments.getString("workspace"))
        require(MutationValidation.isId(workspace))
        val role = requireNotNull(arguments.getString("role"))
        require(role in setOf("first", "second"))
        val phase = requireNotNull(arguments.getString("devicePhase"))
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = SyncDatabase.open(context, "device-interop-$workspace-$role")
        try {
            val store = LocalSyncStore(database)
            val queue = MutationQueue(database)
            val replica = store.replicaId()
            val http = InteropTransport.http(workspace, replica)
            val runner = SyncRunner(queue, SyncResponseStore(database), http)
            val repository = ChecklistRepository(store) {}
            suspend fun sync() {
                val result = runner.run()
                assertEquals(result.toString(), SyncRunOutcome.COMPLETE, result.outcome)
                assertTrue(queue.entries.first().all { it.state == QueueState.SYNCED })
            }
            when (phase) {
                "seed" -> {
                    require(role == "first")
                    check(store.documents.first().isEmpty())
                    repository.create("Two-device oats")
                    sync()
                }
                "bootstrap" -> {
                    require(role == "second")
                    check(store.documents.first().isEmpty())
                    sync()
                    assertEquals(1L, store.documents.first().single().state.counters.getValue("quantity").value)
                }
                "edit" -> {
                    check(queue.entries.first().all { it.state == QueueState.SYNCED })
                    val document = store.documents.first().single()
                    assertEquals(1L, document.state.counters.getValue("quantity").value)
                    repository.quantity(document.entityId, 1)
                    repository.addTag(document.entityId, role)
                    repository.rename(document.entityId, "Oats from $role")
                    if (role == "second") repository.complete(document.entityId, true)
                    assertTrue(queue.entries.first().any { it.state == QueueState.PENDING })
                }
                "sync" -> sync()
                "verify" -> {
                    sync()
                    val state = store.documents.first().single().state
                    assertEquals(3L, state.counters.getValue("quantity").value)
                    assertEquals(setOf("first", "second"), state.sets.getValue("tags").values)
                    assertEquals(JsonPrimitive(true), state.registers.getValue("completed").value)
                    assertEquals(2, state.counters.getValue("quantity").positive.size)
                    assertTrue(replica in state.counters.getValue("quantity").positive)
                    assertFalse(state.deleted)
                    assertServerState(state, http.pull(null).changes.map { it.delta })
                }
                "delete" -> {
                    require(role == "first")
                    repository.delete(store.documents.first().single().entityId)
                }
                "late-edit" -> {
                    require(role == "second")
                    val document = store.documents.first().single()
                    assertFalse(document.state.deleted)
                    repository.rename(document.entityId, "Late offline edit")
                }
                "verify-deleted" -> {
                    sync()
                    val state = store.documents.first().single().state
                    assertTrue(state.deleted)
                    assertServerState(state, http.pull(null).changes.map { it.delta })
                }
                else -> error("Unknown devicePhase")
            }
            InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply {
                putString("replicaId", replica)
                putString("role", role)
                putString("phase", phase)
            })
        } finally { database.close() }
    }

    private fun assertServerState(local: DocumentDelta, changes: List<DocumentDelta>) {
        assertEquals(local, changes.fold(DocumentDelta()) { state, change -> state.merge(change) })
    }
}