package dev.offlinesync.sample

import android.app.Application
import android.util.Base64
import dev.offlinesync.android.HttpSyncTransport
import dev.offlinesync.android.LocalSyncStore
import dev.offlinesync.android.MutationQueue
import dev.offlinesync.android.SyncDatabase
import dev.offlinesync.android.SyncResponseStore
import dev.offlinesync.android.SyncRunner
import dev.offlinesync.android.SyncScheduler
import dev.offlinesync.android.SyncTokenProvider
import dev.offlinesync.android.SyncWorkerHost
import dev.offlinesync.android.SyncWorkerSession
import dev.offlinesync.core.MutationValidation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

class ChecklistApplication : Application(), SyncWorkerHost {
    private val lock = Mutex()
    private var database: SyncDatabase? = null
    internal val scopeId: String by lazy {
        val preferences = getSharedPreferences("sample-scope", MODE_PRIVATE)
        preferences.getString("scope", null) ?: UUID.randomUUID().toString().also {
            check(preferences.edit().putString("scope", it).commit())
        }
    }
    private val vault by lazy { ConnectionVault(this, scopeId) }

    internal suspend fun database(): SyncDatabase = withContext(Dispatchers.IO) {
        lock.withLock { database ?: SyncDatabase.open(this@ChecklistApplication, scopeId).also { database = it } }
    }

    internal suspend fun connection(): Connection? = withContext(Dispatchers.IO) { vault.read() }

    internal suspend fun connect(origin: String, token: String) = withContext(Dispatchers.IO) {
        require(token.length in 1..16_384) { "Enter a valid JWT." }
        val parts = token.split('.')
        require(parts.size == 3) { "Enter a valid JWT." }
        val claims = JSONObject(String(Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_WRAP), Charsets.UTF_8))
        val replica = LocalSyncStore(database()).replicaId()
        require(claims.optString("replica") == replica) { "The JWT replica does not match this device." }
        require(MutationValidation.isId(claims.optString("workspace"))) { "The JWT needs a workspace UUID." }
        require(claims.optString("sub").isNotBlank() && claims.optString("iss").isNotBlank()) {
            "The JWT needs subject and issuer claims."
        }
        val canonicalOrigin = java.net.URI(origin.trim()).let { uri ->
            require(uri.path.isNullOrEmpty() || uri.path == "/") { "Use an origin URL without a path." }
            origin.trim().trimEnd('/') + "/"
        }
        HttpSyncTransport(canonicalOrigin, SyncTokenProvider { token }, allowLocalHttp = BuildConfig.DEBUG)
        val binding = MessageDigest.getInstance("SHA-256").digest(JSONArray(listOf(canonicalOrigin,
            claims.getString("iss"), claims.getString("sub"), claims.getString("workspace"), replica))
            .toString().toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        lock.withLock {
            val previous = vault.read()
            require(previous == null || previous.binding == binding) {
                "This installation is bound to another identity or server. Use a separate app installation."
            }
            vault.write(Connection(canonicalOrigin, binding, token))
        }
        val queue = MutationQueue(database())
        queue.resumeAfterAuthentication()
        queue.resumeBackgroundSync()
        SyncScheduler(this@ChecklistApplication).start(scopeId)
        SyncScheduler(this@ChecklistApplication).requestSync(scopeId)
    }

    internal suspend fun disconnect() = withContext(Dispatchers.IO) {
        lock.withLock { vault.read()?.let { vault.write(it.copy(token = "")) } }
        SyncScheduler(this@ChecklistApplication).cancel(scopeId).result.get()
    }

    internal suspend fun schedule() = withContext(Dispatchers.IO) {
        if (!connection()?.token.isNullOrEmpty()) {
            SyncScheduler(this@ChecklistApplication).start(scopeId)
            SyncScheduler(this@ChecklistApplication).requestSync(scopeId)
        }
    }

    override suspend fun openSyncSession(scopeId: String): SyncWorkerSession? = withContext(Dispatchers.IO) {
        if (scopeId != this@ChecklistApplication.scopeId) return@withContext null
        val connection = connection()?.takeIf { it.token.isNotEmpty() } ?: return@withContext null
        val database = database()
        val queue = MutationQueue(database)
        val transport = HttpSyncTransport(connection.origin, SyncTokenProvider {
            this@ChecklistApplication.connection()?.takeIf { it.binding == connection.binding }?.token
        }, allowLocalHttp = BuildConfig.DEBUG)
        object : SyncWorkerSession {
            override val queue = queue
            override val runner = SyncRunner(queue, SyncResponseStore(database), transport)
            override fun close() = Unit
        }
    }
}