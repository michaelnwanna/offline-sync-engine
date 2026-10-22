package dev.offlinesync.sample

import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dev.offlinesync.android.HttpSyncTransport
import dev.offlinesync.android.LocalSyncStore
import dev.offlinesync.android.MutationQueue
import dev.offlinesync.android.QueueState
import dev.offlinesync.core.WireJson
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@RunWith(AndroidJUnit4::class)
class WorkManagerInteropTests {
    @Test
    fun offlineEditDeliveredAfterNetworkReconnect() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Enable only against the dedicated interop host", arguments.getString("serverInterop") == "true")
        val phase = arguments.getString("networkPhase")
        require(phase in setOf("offline", "checkpoint", "reconnect"))
        val transition = arguments.getString("osTransition")
        require(transition == null || transition in setOf("doze", "reboot"))
        val app = ApplicationProvider.getApplicationContext<ChecklistApplication>()
        val preferences = app.getSharedPreferences("network-interop", android.content.Context.MODE_PRIVATE)
        val store = LocalSyncStore(app.database())
        val queue = MutationQueue(app.database())
        val network = app.getSystemService(android.net.ConnectivityManager::class.java)
        if (phase == "offline") {
            assertEquals("Disable Wi-Fi and mobile data before preparation", null, network.activeNetwork)
            check(app.connection() == null) { "Use a dedicated installation with no saved connection." }
            check(queue.entries.first().isEmpty()) { "Use a dedicated installation with an empty queue." }
            val workspace = UUID.randomUUID().toString()
            ConnectionVault(app, app.scopeId).write(Connection(ORIGIN, "network-interop-$workspace", token(workspace, store.replicaId())))
            ChecklistRepository(store) { app.schedule() }.create("Offline reconnect oats")
            val before = queue.entries.first().single()
            check(preferences.edit().putString("workspace", workspace)
                .putString("mutationId", before.mutation.mutationId)
                .putString("hash", WireJson.hash(before.mutation)).commit())
            val delivered = withTimeoutOrNull(10_000) {
                queue.entries.first { entries -> entries.any { it.state != QueueState.PENDING || it.attempts != 0L } }
            }
            assertEquals("Connected constraint must prevent claiming offline", null, delivered)
            assertEquals(null, network.activeNetwork)
            val work = WorkManager.getInstance(app)
                .getWorkInfosForUniqueWork("offline-sync:${app.scopeId}:run").get()
            assertTrue(work.isNotEmpty())
            assertTrue(work.all { it.state == WorkInfo.State.ENQUEUED })
            check(preferences.edit().putString("workId", work.first().id.toString())
                .putInt("bootCount", android.provider.Settings.Global.getInt(app.contentResolver,
                    android.provider.Settings.Global.BOOT_COUNT)).commit())
            return@runBlocking
        }
        val originalWorkId = requireNotNull(preferences.getString("workId", null))
        if (transition != null) {
            val bootCount = android.provider.Settings.Global.getInt(app.contentResolver,
                android.provider.Settings.Global.BOOT_COUNT)
            val previousBoot = preferences.getInt("bootCount", -1)
            if (transition == "reboot") assertTrue("An actual Android reboot is required", bootCount > previousBoot)
            else assertEquals(previousBoot, bootCount)
        }
        if (phase == "checkpoint") {
            require(transition != null)
            assertEquals(null, network.activeNetwork)
            val before = queue.entries.first().single()
            assertEquals(QueueState.PENDING, before.state)
            assertEquals(0L, before.attempts)
            assertEquals(preferences.getString("mutationId", null), before.mutation.mutationId)
            assertEquals(preferences.getString("hash", null), WireJson.hash(before.mutation))
            val work = WorkManager.getInstance(app)
                .getWorkInfosForUniqueWork("offline-sync:${app.scopeId}:run").get()
            assertTrue(work.any { it.id.toString() == originalWorkId && it.state == WorkInfo.State.ENQUEUED })
            return@runBlocking
        }
        try {
            val after = withTimeout(240_000) {
                queue.entries.first { entries -> entries.size == 1 && entries.single().state == QueueState.SYNCED }.single()
            }
            assertEquals(preferences.getString("mutationId", null), after.mutation.mutationId)
            assertEquals(preferences.getString("hash", null), WireJson.hash(after.mutation))
            assertEquals(1L, after.attempts)
            withTimeout(60_000) {
                WorkManager.getInstance(app).getWorkInfosForUniqueWorkFlow("offline-sync:${app.scopeId}:run")
                    .first { work -> work.any { it.id.toString() == originalWorkId } &&
                        work.all { it.state == WorkInfo.State.SUCCEEDED } }
            }
            val workspace = requireNotNull(preferences.getString("workspace", null))
            val page = HttpSyncTransport(ORIGIN, { token(workspace, store.replicaId()) }, allowLocalHttp = true).pull(null)
            assertEquals(1, page.changes.size)
            assertEquals(1L, page.changes.single().revision)
            assertEquals(after.mutation.entityId, page.changes.single().entityId)
            assertEquals(store.documents.first().single().state, page.changes.single().delta)
        } finally {
            app.disconnect()
        }
    }

    @Test
    fun persistedEditDeliveredByRealWorkerAfterProcessRestart() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Enable only against the dedicated interop host",
            arguments.getString("serverInterop") == "true")
        val phase = arguments.getString("recoveryPhase")
        require(phase == "prepare" || phase == "verify") { "Set recoveryPhase=prepare or verify." }
        val app = ApplicationProvider.getApplicationContext<ChecklistApplication>()
        val preferences = app.getSharedPreferences("worker-interop", android.content.Context.MODE_PRIVATE)
        val store = LocalSyncStore(app.database())
        val queue = MutationQueue(app.database())
        if (phase == "prepare") {
            check(app.connection() == null) { "Use a fresh dedicated test installation; existing credentials must not be replaced." }
            check(queue.entries.first().isEmpty()) { "Use a fresh dedicated test installation." }
            val workspace = UUID.randomUUID().toString()
            val replica = store.replicaId()
            ChecklistRepository(store) {}.create("Restarted worker oats")
            val mutation = store.pendingMutations().single()
            val lease = queue.claim(limit = 1, leaseDurationMs = 60_000).single()
            ConnectionVault(app, app.scopeId).write(Connection(ORIGIN, "worker-interop-$workspace", token(workspace, replica)))
            check(preferences.edit().putString("workspace", workspace)
                .putString("mutationId", mutation.mutationId).putString("hash", WireJson.hash(mutation))
                .putLong("pid", android.os.Process.myPid().toLong())
                .putLong("leaseUntil", lease.expiresAtMs).commit())
            assertEquals(QueueState.IN_FLIGHT, queue.entries.first().single().state)
            assertTrue(lease.expiresAtMs > System.currentTimeMillis())
            return@runBlocking
        }

        assertNotEquals("Verification must run in a new app process", preferences.getLong("pid", -1),
            android.os.Process.myPid().toLong())
        val workspace = requireNotNull(preferences.getString("workspace", null))
        val before = queue.entries.first().single()
        assertEquals(preferences.getString("mutationId", null), before.mutation.mutationId)
        assertEquals(preferences.getString("hash", null), WireJson.hash(before.mutation))
        assertEquals(QueueState.IN_FLIGHT, before.state)
        try {
            app.schedule()
            val after = withTimeout(240_000) {
                queue.entries.first { entries -> entries.size == 1 && entries.single().state == QueueState.SYNCED }.single()
            }
            assertEquals(before.mutation, after.mutation)
            assertEquals(2L, after.attempts)
            withTimeout(60_000) {
                WorkManager.getInstance(app).getWorkInfosForUniqueWorkFlow("offline-sync:${app.scopeId}:run")
                    .first { work -> work.isNotEmpty() && work.all { it.state == WorkInfo.State.SUCCEEDED } }
            }
            val transport = HttpSyncTransport(ORIGIN, { token(workspace, store.replicaId()) }, allowLocalHttp = true)
            val page = transport.pull(null)
            assertEquals(1, page.changes.size)
            assertEquals(before.mutation.entityId, page.changes.single().entityId)
            assertEquals(1L, page.changes.single().revision)
            assertEquals(store.documents.first().single().state, page.changes.single().delta)
        } finally {
            app.disconnect()
        }
    }

    private fun token(workspace: String, replica: String): String {
        fun encode(bytes: ByteArray) = Base64.encodeToString(bytes, FLAGS)
        val header = encode("""{"alg":"HS256","typ":"JWT"}""".toByteArray(Charsets.UTF_8))
        val claims = encode(JSONObject().put("iss", "offline-sync-interop").put("aud", "offline-sync-interop")
            .put("sub", "interop-user").put("workspace", workspace).put("replica", replica)
            .put("exp", System.currentTimeMillis() / 1000 + 3600).toString().toByteArray(Charsets.UTF_8))
        val unsigned = "$header.$claims"
        val mac = Mac.getInstance("HmacSHA256").apply {
            init(SecretKeySpec("interop-jwt-key-not-for-production-00000001".toByteArray(Charsets.UTF_8), "HmacSHA256"))
        }
        return "$unsigned.${encode(mac.doFinal(unsigned.toByteArray(Charsets.UTF_8)))}"
    }

    private companion object {
        const val ORIGIN = "http://10.0.2.2:5087/"
        const val FLAGS = Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
    }
}