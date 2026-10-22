package dev.offlinesync.sample

import dev.offlinesync.android.LocalEditContext
import dev.offlinesync.android.LocalSyncStore
import dev.offlinesync.core.DocumentDelta
import dev.offlinesync.core.ObservedRemoveSet
import dev.offlinesync.core.PnCounter
import dev.offlinesync.core.RegisterValue
import kotlinx.serialization.json.JsonPrimitive
import java.util.UUID

internal class ChecklistRepository(val store: LocalSyncStore, private val schedule: suspend () -> Unit) {
    suspend fun create(title: String) {
        val cleaned = title.trim()
        require(cleaned.isNotEmpty() && cleaned.length <= 500)
        change(UUID.randomUUID().toString()) { edit ->
            DocumentDelta(registers = mapOf(
                "title" to RegisterValue(JsonPrimitive(cleaned), edit.stamp),
                "completed" to RegisterValue(JsonPrimitive(false), edit.stamp),
            ), counters = mapOf("quantity" to PnCounter().increment(edit.stamp.replicaId, 1)))
        }
    }

    suspend fun rename(entityId: String, title: String) {
        val cleaned = title.trim()
        require(cleaned.isNotEmpty() && cleaned.length <= 500)
        change(entityId) { DocumentDelta(registers = mapOf("title" to RegisterValue(JsonPrimitive(cleaned), it.stamp))) }
    }

    suspend fun complete(entityId: String, completed: Boolean) = change(entityId) {
        DocumentDelta(registers = mapOf("completed" to RegisterValue(JsonPrimitive(completed), it.stamp)))
    }

    suspend fun quantity(entityId: String, amount: Long) = change(entityId) { edit ->
        require(amount == 1L || amount == -1L)
        val replica = edit.stamp.replicaId
        val current = (edit.current.counters["quantity"] ?: PnCounter()).increment(replica, amount)
        DocumentDelta(counters = mapOf("quantity" to PnCounter(
            current.positive.filterKeys { it == replica }, current.negative.filterKeys { it == replica })))
    }

    suspend fun addTag(entityId: String, tag: String) {
        val cleaned = tag.trim()
        require(cleaned.isNotEmpty() && cleaned.length <= 100)
        change(entityId) { edit ->
            require(cleaned !in (edit.current.sets["tags"]?.values ?: emptySet()))
            DocumentDelta(sets = mapOf("tags" to ObservedRemoveSet(adds = mapOf(edit.mutationId to cleaned))))
        }
    }

    suspend fun removeTag(entityId: String, tag: String) = change(entityId) { edit ->
        val observed = edit.current.sets["tags"]?.adds?.filterValues { it == tag }?.keys ?: emptySet()
        DocumentDelta(sets = mapOf("tags" to ObservedRemoveSet(removes = observed)))
    }

    suspend fun delete(entityId: String) = change(entityId) { DocumentDelta(deleted = true) }

    private suspend fun change(entityId: String, delta: (LocalEditContext) -> DocumentDelta) {
        store.edit(entityId, delta)
        schedule()
    }
}