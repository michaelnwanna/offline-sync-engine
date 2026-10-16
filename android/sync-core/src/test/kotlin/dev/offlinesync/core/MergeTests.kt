package dev.offlinesync.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MergeTests {
    @Test
    fun sharedFixturesMatchKotlin() {
        val text = requireNotNull(javaClass.getResource("/merge-v1.json")).readText()
        val fixtures = Json.parseToJsonElement(text).jsonObject
        for (element in fixtures.getValue("clocks").jsonArray) {
            val fixture = element.jsonObject
            val current = Json.decodeFromJsonElement<HybridTimestamp>(fixture.getValue("current"))
            val received = fixture["received"]?.let { Json.decodeFromJsonElement<HybridTimestamp>(it) }
            val expected = Json.decodeFromJsonElement<HybridTimestamp>(fixture.getValue("expected"))
            assertEquals(expected, current.tick(fixture.getValue("nowMs").jsonPrimitive.long, received))
        }
        for (element in fixtures.getValue("merges").jsonArray) {
            val fixture = element.jsonObject
            val left = Json.decodeFromJsonElement<DocumentDelta>(fixture.getValue("left"))
            val right = Json.decodeFromJsonElement<DocumentDelta>(fixture.getValue("right"))
            val expected = Json.decodeFromJsonElement<DocumentDelta>(fixture.getValue("expected"))
            assertEquals(expected, left.merge(right), fixture.getValue("name").toString())
            assertEquals(expected, right.merge(left))
            assertEquals(expected, left.merge(right).merge(right))
        }
    }

    @Test
    fun generatedStatesConverge() {
        val random = Random(421)
        repeat(100) { iteration ->
            val first = PnCounter().increment("a", random.nextLong(-100, 100))
            val second = PnCounter().increment("b", random.nextLong(-100, 100))
            val third = PnCounter().increment("c", random.nextLong(-100, 100))
            assertEquals(first.merge(second), second.merge(first))
            assertEquals(first, first.merge(first))
            assertEquals(first.merge(second).merge(third), first.merge(second.merge(third)))
            val firstSet = ObservedRemoveSet().add("x", "a:$iteration")
            val secondSet = firstSet.remove("x")
            val thirdSet = ObservedRemoveSet().add("x", "b:$iteration")
            assertEquals(firstSet.merge(secondSet), secondSet.merge(firstSet))
            assertEquals(secondSet, secondSet.merge(secondSet))
            assertEquals(firstSet.merge(secondSet).merge(thirdSet), firstSet.merge(secondSet.merge(thirdSet)))
            assertEquals(setOf("x"), secondSet.merge(thirdSet).values)
        }
    }

    @Test
    fun counterRetainsConcurrentChangesAndChecksOverflow() {
        val left = PnCounter().increment("a", 5).increment("a", -2)
        val right = PnCounter().increment("b", 4)
        assertEquals(7L, left.merge(right).merge(right).value)
        assertEquals(3L, left.value)
        assertFailsWith<ArithmeticException> { PnCounter(mapOf("a" to Long.MAX_VALUE)).increment("a", 1) }
        assertFailsWith<IllegalArgumentException> { PnCounter().increment("a", Long.MIN_VALUE) }
        assertFailsWith<IllegalArgumentException> { PnCounter(mapOf("a" to -1)).merge(PnCounter()) }
        assertFailsWith<ArithmeticException> { PnCounter(mapOf("a" to Long.MAX_VALUE, "b" to 1)).value }
    }

    @Test
    fun ambiguousMetadataAndClockOverflowFail() {
        val stamp = HybridTimestamp(100, 0, "a")
        val register = RegisterValue(JsonPrimitive("first"), stamp)
        assertFailsWith<IllegalArgumentException> { register.merge(RegisterValue(JsonPrimitive("second"), stamp)) }
        assertFailsWith<IllegalArgumentException> { ObservedRemoveSet().add("first", "dot").add("second", "dot") }
        assertFailsWith<ArithmeticException> { HybridTimestamp(100, Int.MAX_VALUE, "a").tick(50) }
        assertTrue(DocumentDelta(deleted = true).merge(DocumentDelta()).deleted)
    }
}