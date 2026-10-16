package dev.offlinesync.android

import androidx.room.withTransaction
import dev.offlinesync.core.DocumentDelta
import dev.offlinesync.core.HybridTimestamp
import dev.offlinesync.core.Mutation
import dev.offlinesync.core.MutationAck
import dev.offlinesync.core.MutationValidation
import dev.offlinesync.core.PullResponse
import dev.offlinesync.core.WireJson
import kotlinx.serialization.encodeToString
import java.util.UUID

class SyncResponseStore(
    private val database: SyncDatabase,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    suspend fun pullCursor(): String? = database.metadata().get("pullCursor")

    suspend fun acknowledge(lease: MutationLease, acknowledgment: MutationAck): Boolean {
        require(acknowledgment.mutationId == lease.mutation.mutationId)
        require(acknowledgment.status in setOf("SYNCED", "REJECTED", "CONFLICT"))
        if (acknowledgment.status == "SYNCED") {
            require(acknowledgment.state != null && (acknowledgment.revision ?: 0) > 0 && acknowledgment.errorCode == null)
        } else {
            require(acknowledgment.state == null && acknowledgment.revision == null)
            require(acknowledgment.errorCode?.matches(Regex("[A-Z0-9_]{1,64}")) == true)
        }
        return database.withTransaction {
            val now = nowMs().also { require(it >= 0) }
            val entry = database.outbox().owned(acknowledgment.mutationId, lease.token, now)
                ?: return@withTransaction false
            val mutation = WireJson.format.decodeFromString<Mutation>(entry.payload)
            require(mutation == lease.mutation)
            database.outbox().release(entry.mutationId, acknowledgment.status, 0, acknowledgment.errorCode)
            if (acknowledgment.status == "SYNCED") {
                mergeConfirmed(mutation.entityId, requireNotNull(acknowledgment.state), now)
            } else rebuildProjection(database, mutation.entityId)
            true
        }
    }

    suspend fun applyPull(expectedCursor: String?, page: PullResponse): Boolean {
        require(page.changes.size <= 100 && page.nextCursor.isNotBlank() && page.nextCursor.length <= 4096)
        require(!page.hasMore || page.changes.isNotEmpty())
        require(page.changes.isEmpty() || page.nextCursor != expectedCursor)
        return database.withTransaction {
            val metadata = database.metadata()
            if (metadata.get("pullCursor") != expectedCursor) return@withTransaction false
            val now = nowMs().also { require(it >= 0) }
            var revision = metadata.get("pullRevision")?.toLong() ?: 0
            for (change in page.changes) {
                require(change.revision > revision && MutationValidation.isId(change.entityId))
                mergeConfirmed(change.entityId, change.delta, now)
                revision = change.revision
            }
            metadata.put(SyncMetadata("pullRevision", revision.toString()))
            metadata.put(SyncMetadata("pullCursor", page.nextCursor))
            true
        }
    }

    private suspend fun mergeConfirmed(entityId: String, delta: DocumentDelta, now: Long) {
        val stored = database.documents().get(entityId)
        val current = stored?.let { WireJson.format.decodeFromString<DocumentDelta>(it.confirmed) } ?: DocumentDelta()
        val confirmed = current.merge(delta)
        database.documents().put(StoredDocument(entityId, WireJson.format.encodeToString(confirmed),
            stored?.projected ?: WireJson.format.encodeToString(DocumentDelta())))
        val received = delta.registers.values.map { it.stamp }
        require(received.all { it.physicalMs >= 0 && it.logical >= 0 && MutationValidation.isId(it.replicaId) })
        if (received.isNotEmpty()) {
            val metadata = database.metadata()
            val replica = metadata.get("replicaId") ?: UUID.randomUUID().toString()
            val clock = metadata.get("clock")?.let { WireJson.format.decodeFromString<HybridTimestamp>(it) }
                ?: HybridTimestamp(0, 0, replica)
            metadata.put(SyncMetadata("replicaId", replica))
            metadata.put(SyncMetadata("clock", WireJson.format.encodeToString(clock.tick(now, received.maxOrNull()))))
        }
        rebuildProjection(database, entityId)
    }
}

internal suspend fun rebuildProjection(database: SyncDatabase, entityId: String) {
    val stored = database.documents().get(entityId) ?: return
    val base = WireJson.format.decodeFromString<DocumentDelta>(stored.confirmed)
    val projected = database.outbox().overlay(entityId).fold(base) { state, entry ->
        state.merge(WireJson.format.decodeFromString<Mutation>(entry.payload).delta)
    }
    database.documents().put(stored.copy(projected = WireJson.format.encodeToString(projected)))
}