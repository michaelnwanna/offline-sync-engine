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
import dev.offlinesync.core.MutationValidation
import dev.offlinesync.core.WireJson
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ActiveRequestInteropTests {
    @Test
    fun committedRequestSurvivesProcessTermination() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("serverInterop") == "true")
        val workspace = requireNotNull(arguments.getString("workspace"))
        require(MutationValidation.isId(workspace))
        val phase = arguments.getString("requestPhase")
        require(phase == "push" || phase == "verify")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = context.getSharedPreferences("active-request-$workspace", Context.MODE_PRIVATE)
        val database = SyncDatabase.open(context, "active-request-$workspace")
        try {
            val store = LocalSyncStore(database)
            val replica = store.replicaId()
            if (phase == "push") {
                check(store.documents.first().isEmpty())
                ChecklistRepository(store) {}.create("Killed request oats")
                val mutation = store.pendingMutations().single()
                check(preferences.edit().putString("mutationId", mutation.mutationId)
                    .putString("hash", WireJson.hash(mutation)).putInt("pid", android.os.Process.myPid()).commit())
                val transport = InteropTransport.http(workspace, replica, "http://10.0.2.2:5088/")
                SyncRunner(MutationQueue(database), SyncResponseStore(database), transport).run()
                error("Coordinator must terminate the process while the committed response is held")
            }
            assertNotEquals(preferences.getInt("pid", -1), android.os.Process.myPid())
            val before = MutationQueue(database).entries.first().single()
            assertEquals(QueueState.IN_FLIGHT, before.state)
            assertEquals(1L, before.attempts)
            assertEquals(preferences.getString("mutationId", null), before.mutation.mutationId)
            assertEquals(preferences.getString("hash", null), WireJson.hash(before.mutation))
            val transport = InteropTransport.http(workspace, replica)
            val initial = transport.pull(null)
            assertEquals(1, initial.changes.size)
            assertEquals(1L, initial.changes.single().revision)
            val now = { maxOf(System.currentTimeMillis(), before.leaseUntilMs ?: error("Missing lease")) }
            val result = SyncRunner(MutationQueue(database, now), SyncResponseStore(database, now), transport).run()
            assertEquals(SyncRunOutcome.COMPLETE, result.outcome)
            val after = MutationQueue(database).entries.first().single()
            assertEquals(QueueState.SYNCED, after.state)
            assertEquals(2L, after.attempts)
            assertEquals(before.mutation, after.mutation)
            val history = transport.pull(null)
            assertEquals(initial, history)
            assertEquals(before.mutation.entityId, history.changes.single().entityId)
            assertEquals(store.documents.first().single().state, history.changes.single().delta)
        } finally {
            database.close()
        }
    }
}