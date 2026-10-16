package dev.offlinesync.android

import androidx.room.withTransaction
import dev.offlinesync.core.Mutation
import dev.offlinesync.core.WireJson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID
import kotlin.random.Random

enum class QueueState { PENDING, IN_FLIGHT, SYNCED, CONFLICT, REJECTED }

data class QueuedMutation(
    val mutation: Mutation,
    val state: QueueState,
    val attempts: Long,
    val nextAttemptMs: Long,
    val leaseUntilMs: Long?,
    val errorCode: String?,
)

data class MutationLease(val mutation: Mutation, val token: String, val expiresAtMs: Long)

enum class QueueFailure { TRANSIENT, AUTHENTICATION, CONFLICT, REJECTED }

class MutationQueue(
    private val database: SyncDatabase,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val random: Random = Random.Default,
) {
    val entries: Flow<List<QueuedMutation>> = database.outbox().observeAll().map { entries ->
        entries.map { entry ->
            QueuedMutation(WireJson.format.decodeFromString<Mutation>(entry.payload), QueueState.valueOf(entry.state),
                entry.attempts, entry.nextAttemptMs, entry.leaseUntilMs, entry.errorCode)
        }
    }

    suspend fun claim(limit: Int = 100, leaseDurationMs: Long = 60_000): List<MutationLease> {
        require(limit in 1..100)
        require(leaseDurationMs in 1..300_000)
        return database.withTransaction {
            val now = nowMs().also { require(it >= 0) }
            val until = Math.addExact(now, leaseDurationMs)
            val outbox = database.outbox()
            outbox.recoverExpired(now)
            if (database.metadata().get("authenticationPaused") == "true") return@withTransaction emptyList()
            outbox.eligible(now, limit).map { entry ->
                val token = UUID.randomUUID().toString()
                check(outbox.claim(entry.mutationId, token, until) == 1)
                MutationLease(WireJson.format.decodeFromString<Mutation>(entry.payload), token, until)
            }
        }
    }

    suspend fun fail(lease: MutationLease, failure: QueueFailure, errorCode: String): Boolean {
        require(errorCode.matches(Regex("[A-Z0-9_]{1,64}")))
        return database.withTransaction {
            val now = nowMs().also { require(it >= 0) }
            val entry = database.outbox().owned(lease.mutation.mutationId, lease.token, now)
                ?: return@withTransaction false
            val state = when (failure) {
                QueueFailure.CONFLICT -> QueueState.CONFLICT
                QueueFailure.REJECTED -> QueueState.REJECTED
                else -> QueueState.PENDING
            }
            val nextAttempt = if (failure == QueueFailure.TRANSIENT) {
                val ceiling = minOf(3_600_000L, 1_000L shl (entry.attempts - 1).coerceIn(0, 12).toInt())
                val delay = random.nextLong(ceiling / 2, ceiling + 1)
                if (now > Long.MAX_VALUE - delay) Long.MAX_VALUE else now + delay
            } else now
            if (failure == QueueFailure.AUTHENTICATION)
                database.metadata().put(SyncMetadata("authenticationPaused", "true"))
            database.outbox().release(entry.mutationId, state.name, nextAttempt, errorCode)
            if (state == QueueState.CONFLICT || state == QueueState.REJECTED)
                rebuildProjection(database, entry.entityId)
            true
        }
    }

    suspend fun resumeAfterAuthentication() {
        database.withTransaction { database.metadata().put(SyncMetadata("authenticationPaused", "false")) }
    }
}