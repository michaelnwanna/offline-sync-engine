package dev.offlinesync.android

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.withTransaction
import dev.offlinesync.core.DocumentDelta
import dev.offlinesync.core.HybridTimestamp
import dev.offlinesync.core.Mutation
import dev.offlinesync.core.MutationValidation
import dev.offlinesync.core.WireJson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import java.util.UUID

@Entity(tableName = "sync_documents")
internal data class StoredDocument(
    @PrimaryKey val entityId: String,
    val confirmed: String,
    val projected: String,
)

@Entity(tableName = "sync_outbox", indices = [Index(value = ["localSequence"], unique = true)])
internal data class OutboxEntry(
    @PrimaryKey val mutationId: String,
    val entityId: String,
    val localSequence: Long,
    val payload: String,
    val state: String = "PENDING",
    @androidx.room.ColumnInfo(defaultValue = "0") val attempts: Long = 0,
    @androidx.room.ColumnInfo(defaultValue = "0") val nextAttemptMs: Long = 0,
    val leaseToken: String? = null,
    val leaseUntilMs: Long? = null,
    val errorCode: String? = null,
)

@Dao
internal interface DocumentDao {
    @Query("SELECT * FROM sync_documents WHERE entityId = :entityId")
    suspend fun get(entityId: String): StoredDocument?

    @Query("SELECT projected FROM sync_documents WHERE entityId = :entityId")
    fun observe(entityId: String): Flow<String?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(document: StoredDocument)
}

@Dao
internal interface OutboxDao {
    @Insert
    suspend fun insert(entry: OutboxEntry)

    @Query("SELECT * FROM sync_outbox WHERE state = 'PENDING' ORDER BY localSequence LIMIT :limit")
    suspend fun pending(limit: Int): List<OutboxEntry>

    @Query("SELECT COUNT(*) FROM sync_outbox WHERE state = 'PENDING'")
    fun observePendingCount(): Flow<Int>

    @Query("SELECT * FROM sync_outbox ORDER BY localSequence")
    fun observeAll(): Flow<List<OutboxEntry>>

    @Query("UPDATE sync_outbox SET state = 'PENDING', leaseToken = NULL, leaseUntilMs = NULL WHERE state = 'IN_FLIGHT' AND leaseUntilMs <= :now")
    suspend fun recoverExpired(now: Long)

    @Query("""SELECT * FROM sync_outbox AS candidate
        WHERE candidate.state = 'PENDING' AND candidate.nextAttemptMs <= :now
        AND NOT EXISTS (SELECT 1 FROM sync_outbox AS earlier
            WHERE earlier.entityId = candidate.entityId AND earlier.localSequence < candidate.localSequence
            AND earlier.state != 'SYNCED')
        ORDER BY candidate.localSequence LIMIT :limit""")
    suspend fun eligible(now: Long, limit: Int): List<OutboxEntry>

    @Query("""UPDATE sync_outbox SET state = 'IN_FLIGHT', attempts = attempts + 1,
        leaseToken = :token, leaseUntilMs = :until WHERE mutationId = :mutationId AND state = 'PENDING'""")
    suspend fun claim(mutationId: String, token: String, until: Long): Int

    @Query("""SELECT * FROM sync_outbox WHERE mutationId = :mutationId AND state = 'IN_FLIGHT'
        AND leaseToken = :token AND leaseUntilMs > :now""")
    suspend fun owned(mutationId: String, token: String, now: Long): OutboxEntry?

    @Query("""UPDATE sync_outbox SET state = :state, nextAttemptMs = :nextAttempt,
        leaseToken = NULL, leaseUntilMs = NULL, errorCode = :error WHERE mutationId = :mutationId""")
    suspend fun release(mutationId: String, state: String, nextAttempt: Long, error: String?)

    @Query("SELECT * FROM sync_outbox WHERE entityId = :entityId AND state IN ('PENDING', 'IN_FLIGHT') ORDER BY localSequence")
    suspend fun overlay(entityId: String): List<OutboxEntry>
}

data class LocalEditContext(
    val current: DocumentDelta,
    val stamp: HybridTimestamp,
    val mutationId: String,
)

class LocalSyncStore(private val database: SyncDatabase, private val nowMs: () -> Long = System::currentTimeMillis) {
    val pendingCount: Flow<Int> = database.outbox().observePendingCount()

    fun observeDocument(entityId: String): Flow<DocumentDelta?> = database.documents().observe(entityId).map { json ->
        json?.let { WireJson.format.decodeFromString<DocumentDelta>(it) }
    }

    suspend fun pendingMutations(limit: Int = 100): List<Mutation> {
        require(limit in 1..100)
        return database.outbox().pending(limit).map { WireJson.format.decodeFromString<Mutation>(it.payload) }
    }

    suspend fun edit(entityId: String, delta: (LocalEditContext) -> DocumentDelta): Mutation = database.withTransaction {
        require(MutationValidation.isId(entityId))
        val metadata = database.metadata()
        val replicaId = metadata.get("replicaId") ?: UUID.randomUUID().toString()
        val sequence = Math.addExact(metadata.get("localSequence")?.toLong() ?: 0, 1)
        val now = nowMs()
        require(now >= 0)
        val clock = metadata.get("clock")?.let { WireJson.format.decodeFromString<HybridTimestamp>(it) }
            ?: HybridTimestamp(0, 0, replicaId)
        val stamp = clock.tick(now)
        val stored = database.documents().get(entityId)
        val current = stored?.let { WireJson.format.decodeFromString<DocumentDelta>(it.projected) } ?: DocumentDelta()
        check(!current.deleted) { "A deleted document requires a new entity ID." }
        val mutationId = UUID.randomUUID().toString()
        val change = delta(LocalEditContext(current, stamp, mutationId))
        require(change.registers.values.all { it.stamp == stamp }) { "Local registers must use the allocated timestamp." }
        val mutation = Mutation(1, replicaId, mutationId, sequence, entityId, change)
        require(MutationValidation.error(mutation, now) == null) { "Invalid local delta." }
        val projection = current.merge(change)
        metadata.put(SyncMetadata("replicaId", replicaId))
        metadata.put(SyncMetadata("localSequence", sequence.toString()))
        metadata.put(SyncMetadata("clock", WireJson.format.encodeToString(stamp)))
        database.documents().put(StoredDocument(entityId,
            stored?.confirmed ?: WireJson.format.encodeToString(DocumentDelta()),
            WireJson.format.encodeToString(projection)))
        database.outbox().insert(OutboxEntry(mutationId, entityId, sequence, WireJson.format.encodeToString(mutation)))
        mutation
    }
}