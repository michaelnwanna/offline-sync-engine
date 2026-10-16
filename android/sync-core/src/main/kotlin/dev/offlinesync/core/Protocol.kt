package dev.offlinesync.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.encodeToJsonElement
import java.security.MessageDigest
import java.util.UUID

object WireJson {
    val format = Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = false
        isLenient = false
    }

    fun canonical(value: JsonElement): String = when (value) {
        is JsonObject -> value.entries.sortedBy { it.key }.joinToString(",", "{", "}") {
            "${quote(it.key)}:${canonical(it.value)}"
        }
        is JsonArray -> value.joinToString(",", "[", "]") { canonical(it) }
        is JsonPrimitive -> if (value.isString) quote(value.content) else value.toString()
    }

    fun hash(mutation: Mutation): String {
        val normalized = mutation.copy(delta = mutation.delta.copy(
            sets = mutation.delta.sets.mapValues { (_, set) -> set.copy(removes = set.removes.toSortedSet()) },
        ))
        val bytes = canonical(format.encodeToJsonElement(normalized)).toByteArray(Charsets.UTF_8)
        return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }

    private fun quote(value: String): String = buildString {
        append('"')
        for (character in value) {
            when (character) {
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\t' -> append("\\t")
                '\n' -> append("\\n")
                '\u000C' -> append("\\f")
                '\r' -> append("\\r")
                else -> if (character.code !in 0x20..0x7e || character in "\"&'+<>`") {
                    append("\\u")
                    append(character.code.toString(16).uppercase().padStart(4, '0'))
                } else append(character)
            }
        }
        append('"')
    }
}

@Serializable
data class Mutation(
    val protocolVersion: Int,
    val replicaId: String,
    val mutationId: String,
    val localSequence: Long,
    val entityId: String,
    val delta: DocumentDelta,
)

@Serializable
data class PushRequest(val protocolVersion: Int, val replicaId: String, val mutations: List<Mutation>)

@Serializable
data class MutationAck(
    val mutationId: String,
    val status: String,
    val revision: Long? = null,
    val state: DocumentDelta? = null,
    val errorCode: String? = null,
)

@Serializable
data class PushResponse(val acknowledgments: List<MutationAck>)

@Serializable
data class SyncChange(val revision: Long, val entityId: String, val delta: DocumentDelta)

@Serializable
data class PullResponse(val changes: List<SyncChange>, val nextCursor: String, val hasMore: Boolean)

object MutationValidation {
    const val MAXIMUM_COMPONENT: Long = 1_000_000_000_000

    fun isId(value: String): Boolean = runCatching {
        val identifier = UUID.fromString(value)
        identifier != UUID(0, 0) && identifier.toString() == value
    }.getOrDefault(false)

    fun error(mutation: Mutation, nowMs: Long): String? {
        if (mutation.protocolVersion != 1) return "UNSUPPORTED_VERSION"
        if (!isId(mutation.replicaId) || !isId(mutation.mutationId) || !isId(mutation.entityId) ||
            mutation.localSequence !in 1..MAXIMUM_COMPONENT
        ) return "INVALID_MUTATION"
        val delta = mutation.delta
        if (delta.registers.isEmpty() && delta.counters.isEmpty() && delta.sets.isEmpty() && !delta.deleted)
            return "EMPTY_DELTA"
        for ((field, register) in delta.registers) {
            val stamp = register.stamp
            if (stamp.replicaId != mutation.replicaId || stamp.physicalMs < 0 || stamp.logical < 0)
                return "INVALID_STAMP"
            if (stamp.physicalMs > nowMs + 300_000) return "CLOCK_SKEW"
            val value = register.value as? JsonPrimitive
            if (field == "title") {
                if (value == null || !value.isString || value.content.length > 500) return "INVALID_TITLE"
            } else if (field != "completed" || value == null || value.isString || value.booleanOrNull == null)
                return "UNKNOWN_FIELD_OR_TYPE"
        }
        for ((field, counter) in delta.counters) {
            if (field != "quantity") return "INVALID_COUNTER"
            if ((counter.positive.entries + counter.negative.entries).any {
                    it.key != mutation.replicaId || it.value !in 0..MAXIMUM_COMPONENT
                }
            ) return "INVALID_COUNTER_COMPONENT"
        }
        for ((field, set) in delta.sets) {
            if (field != "tags" || set.adds.size > 100 || set.removes.size > 1000) return "INVALID_SET"
            if (set.adds.any { it.key != mutation.mutationId || it.value.length > 100 } ||
                set.removes.any { !isId(it) }
            ) return "INVALID_DOT"
        }
        return null
    }
}