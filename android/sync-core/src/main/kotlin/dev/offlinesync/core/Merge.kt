package dev.offlinesync.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class HybridTimestamp(val physicalMs: Long, val logical: Int, val replicaId: String) :
    Comparable<HybridTimestamp> {
    override fun compareTo(other: HybridTimestamp): Int =
        compareValuesBy(this, other, { it.physicalMs }, { it.logical }, { it.replicaId })

    fun tick(nowMs: Long, received: HybridTimestamp? = null): HybridTimestamp {
        val physical = maxOf(nowMs, physicalMs, received?.physicalMs ?: 0)
        val nextLogical = when {
            physical == physicalMs && physical == received?.physicalMs ->
                Math.addExact(maxOf(logical, received.logical), 1)
            physical == physicalMs -> Math.addExact(logical, 1)
            physical == received?.physicalMs -> Math.addExact(received.logical, 1)
            else -> 0
        }
        return HybridTimestamp(physical, nextLogical, replicaId)
    }
}

@Serializable
data class RegisterValue(val value: JsonElement, val stamp: HybridTimestamp) {
    fun merge(other: RegisterValue): RegisterValue {
        val order = stamp.compareTo(other.stamp)
        require(order != 0 || value == other.value) {
            "One field timestamp cannot identify different values."
        }
        return if (order >= 0) this else other
    }
}

@Serializable
data class PnCounter(
    val positive: Map<String, Long> = emptyMap(),
    val negative: Map<String, Long> = emptyMap(),
) {
    val value: Long
        get() = Math.subtractExact(
            positive.values.fold(0L, Math::addExact),
            negative.values.fold(0L, Math::addExact),
        )

    fun increment(replicaId: String, amount: Long): PnCounter {
        require(amount != Long.MIN_VALUE)
        val components = if (amount >= 0) positive else negative
        val next = Math.addExact(components[replicaId] ?: 0, kotlin.math.abs(amount))
        return if (amount >= 0) copy(positive = positive + (replicaId to next))
        else copy(negative = negative + (replicaId to next))
    }

    fun merge(other: PnCounter): PnCounter = PnCounter(
        mergeComponents(positive, other.positive),
        mergeComponents(negative, other.negative),
    )

    private fun mergeComponents(left: Map<String, Long>, right: Map<String, Long>): Map<String, Long> {
        require(left.values.all { it >= 0 } && right.values.all { it >= 0 }) {
            "PN components must be nonnegative."
        }
        return (left.keys + right.keys).sorted().associateWith {
            maxOf(left[it] ?: 0, right[it] ?: 0)
        }
    }
}

@Serializable
data class ObservedRemoveSet(
    val adds: Map<String, String> = emptyMap(),
    val removes: Set<String> = emptySet(),
) {
    val values: Set<String>
        get() = adds.filterKeys { it !in removes }.values.toSortedSet()

    fun add(value: String, dot: String): ObservedRemoveSet {
        require(dot !in adds || adds[dot] == value) { "An add dot cannot identify two values." }
        return copy(adds = adds + (dot to value))
    }

    fun remove(value: String): ObservedRemoveSet =
        copy(removes = removes + adds.filterValues { it == value }.keys)

    fun merge(other: ObservedRemoveSet): ObservedRemoveSet {
        val merged = other.adds.entries.fold(this) { state, entry -> state.add(entry.value, entry.key) }
        return merged.copy(adds = merged.adds.toSortedMap(), removes = (removes + other.removes).toSortedSet())
    }
}

@Serializable
data class DocumentDelta(
    val registers: Map<String, RegisterValue> = emptyMap(),
    val counters: Map<String, PnCounter> = emptyMap(),
    val sets: Map<String, ObservedRemoveSet> = emptyMap(),
    val deleted: Boolean = false,
) {
    fun merge(other: DocumentDelta): DocumentDelta = DocumentDelta(
        registers = (registers.keys + other.registers.keys).sorted().associateWith { field ->
            val current = registers[field]
            val incoming = other.registers[field]
            if (current == null) requireNotNull(incoming)
            else if (incoming == null) current else current.merge(incoming)
        },
        counters = (counters.keys + other.counters.keys).sorted().associateWith { field ->
            (counters[field] ?: PnCounter()).merge(other.counters[field] ?: PnCounter())
        },
        sets = (sets.keys + other.sets.keys).sorted().associateWith { field ->
            (sets[field] ?: ObservedRemoveSet()).merge(other.sets[field] ?: ObservedRemoveSet())
        },
        deleted = deleted || other.deleted,
    )
}