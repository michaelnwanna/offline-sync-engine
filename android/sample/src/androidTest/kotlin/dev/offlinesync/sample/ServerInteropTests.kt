package dev.offlinesync.sample

import android.content.Context
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.offlinesync.android.HttpSyncTransport
import dev.offlinesync.android.LocalSyncStore
import dev.offlinesync.android.MutationQueue
import dev.offlinesync.android.QueueState
import dev.offlinesync.android.SyncDatabase
import dev.offlinesync.android.SyncHttpException
import dev.offlinesync.android.SyncResponseStore
import dev.offlinesync.android.SyncRunOutcome
import dev.offlinesync.android.SyncRunner
import dev.offlinesync.android.SyncTokenProvider
import dev.offlinesync.android.SyncTransport
import dev.offlinesync.core.PullResponse
import dev.offlinesync.core.PushRequest
import dev.offlinesync.core.PushResponse
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@RunWith(AndroidJUnit4::class)
class ServerInteropTests {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun requireExplicitOptIn() {
        assumeTrue("Start the dedicated interop host and pass -Pandroid.testInstrumentationRunnerArguments.serverInterop=true",
            InstrumentationRegistry.getArguments().getString("serverInterop") == "true")
    }

    @Test
    fun committedLostAcknowledgmentReplaysAfterReopenAndIndependentReplicasConverge() = runBlocking {
        val workspace = UUID.randomUUID().toString()
        val account = UUID.randomUUID().toString()
        var first = SyncDatabase.open(context, account)
        val second = SyncDatabase.open(context, UUID.randomUUID().toString())
        try {
            var firstStore = LocalSyncStore(first)
            val firstReplica = firstStore.replicaId()
            val secondStore = LocalSyncStore(second)
            val secondReplica = secondStore.replicaId()
            val firstHttp = http(workspace, firstReplica)
            val secondHttp = http(workspace, secondReplica)
            val originalRequests = mutableListOf<PushRequest>()
            val lostAck = object : SyncTransport {
                override suspend fun push(request: PushRequest): PushResponse {
                    originalRequests.add(request)
                    firstHttp.push(request)
                    throw IOException("Simulated loss after the real API committed its response")
                }
                override suspend fun pull(cursor: String?, limit: Int): PullResponse = firstHttp.pull(cursor, limit)
            }
            ChecklistRepository(firstStore) {}.create("Shared oats")
            val entity = firstStore.documents.first().single().entityId
            val original = firstStore.pendingMutations().single()
            assertEquals(SyncRunOutcome.RETRY, runner(first, lostAck).run().outcome)
            first.close()
            first = SyncDatabase.open(context, account)
            firstStore = LocalSyncStore(first)
            assertEquals(original, firstStore.pendingMutations().single())
            val deadline = MutationQueue(first).entries.first().single().nextAttemptMs
            val replayRequests = mutableListOf<PushRequest>()
            val replay = object : SyncTransport {
                override suspend fun push(request: PushRequest): PushResponse {
                    replayRequests.add(request)
                    return firstHttp.push(request)
                }
                override suspend fun pull(cursor: String?, limit: Int): PullResponse = firstHttp.pull(cursor, limit)
            }
            assertEquals(SyncRunOutcome.COMPLETE, runner(first, replay) { maxOf(System.currentTimeMillis(), deadline) }.run().outcome)
            assertEquals(originalRequests, replayRequests)
            val history = firstHttp.pull(null)
            assertEquals(1, history.changes.size)
            assertEquals(1L, history.changes.single().revision)
            assertEquals(SyncRunOutcome.COMPLETE, runner(second, secondHttp).run().outcome)
            assertEquals(firstStore.documents.first(), secondStore.documents.first())

            val firstEdits = ChecklistRepository(firstStore) {}
            val secondEdits = ChecklistRepository(secondStore) {}
            firstEdits.quantity(entity, 1)
            firstEdits.addTag(entity, "pantry")
            secondEdits.quantity(entity, 1)
            secondEdits.addTag(entity, "breakfast")
            secondEdits.complete(entity, true)
            assertEquals(SyncRunOutcome.COMPLETE, runner(first, firstHttp).run().outcome)
            assertEquals(SyncRunOutcome.COMPLETE, runner(second, secondHttp).run().outcome)
            assertEquals(SyncRunOutcome.COMPLETE, runner(first, firstHttp).run().outcome)
            val merged = firstStore.documents.first().single().state
            assertEquals(3L, merged.counters.getValue("quantity").value)
            assertEquals(setOf("pantry", "breakfast"), merged.sets.getValue("tags").values)
            assertEquals(firstStore.documents.first(), secondStore.documents.first())
            assertTrue(MutationQueue(first).entries.first().all { it.state == QueueState.SYNCED })
            assertTrue(MutationQueue(second).entries.first().all { it.state == QueueState.SYNCED })

            firstEdits.removeTag(entity, "pantry")
            secondEdits.delete(entity)
            assertEquals(SyncRunOutcome.COMPLETE, runner(first, firstHttp).run().outcome)
            assertEquals(SyncRunOutcome.COMPLETE, runner(second, secondHttp).run().outcome)
            assertEquals(SyncRunOutcome.COMPLETE, runner(first, firstHttp).run().outcome)
            assertTrue(firstStore.documents.first().single().state.deleted)
            assertEquals(firstStore.documents.first(), secondStore.documents.first())
        } finally {
            first.close()
            second.close()
        }
    }

    @Test
    fun realApiRejectsReplicaMismatchAndCrossWorkspaceCursor() = runBlocking {
        val database = SyncDatabase.open(context, UUID.randomUUID().toString())
        try {
            val store = LocalSyncStore(database)
            ChecklistRepository(store) {}.create("Private item")
            val original = store.pendingMutations().single()
            val workspace = UUID.randomUUID().toString()
            val wrongReplica = http(workspace, UUID.randomUUID().toString())
            val failure = runCatching { wrongReplica.push(PushRequest(1, original.replicaId, listOf(original))) }.exceptionOrNull()
            assertTrue(failure is dev.offlinesync.android.SyncAuthenticationException)
            val authorized = http(workspace, original.replicaId)
            assertEquals(SyncRunOutcome.COMPLETE, runner(database, authorized).run().outcome)
            val page = authorized.pull(null)
            val other = http(UUID.randomUUID().toString(), original.replicaId)
            assertTrue(other.pull(null).changes.isEmpty())
            val cursorFailure = runCatching { other.pull(page.nextCursor) }.exceptionOrNull()
            assertTrue(cursorFailure is SyncHttpException && cursorFailure.statusCode == 400)
            assertFalse(page.changes.isEmpty())
        } finally { database.close() }
    }

    private fun runner(database: SyncDatabase, transport: SyncTransport,
        now: () -> Long = System::currentTimeMillis) = SyncRunner(
        MutationQueue(database, now), SyncResponseStore(database, now), transport)

    private fun http(workspace: String, replica: String): HttpSyncTransport = InteropTransport.http(workspace, replica)
}

internal object InteropTransport {
    fun http(workspace: String, replica: String, origin: String = "http://10.0.2.2:5087/"): HttpSyncTransport {
        val header = encode("""{"alg":"HS256","typ":"JWT"}""")
        val claims = encode(JSONObject().put("iss", "offline-sync-interop").put("aud", "offline-sync-interop")
            .put("sub", "interop-user").put("workspace", workspace).put("replica", replica)
            .put("exp", System.currentTimeMillis() / 1000 + 3600).toString())
        val unsigned = "$header.$claims"
        val mac = Mac.getInstance("HmacSHA256").apply {
            init(SecretKeySpec("interop-jwt-key-not-for-production-00000001".toByteArray(Charsets.UTF_8), "HmacSHA256"))
        }
        val signature = Base64.encodeToString(mac.doFinal(unsigned.toByteArray(Charsets.UTF_8)), FLAGS)
        return HttpSyncTransport(origin, SyncTokenProvider { "$unsigned.$signature" }, allowLocalHttp = true)
    }

    private fun encode(value: String) = Base64.encodeToString(value.toByteArray(Charsets.UTF_8), FLAGS)

    private const val FLAGS = Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
}