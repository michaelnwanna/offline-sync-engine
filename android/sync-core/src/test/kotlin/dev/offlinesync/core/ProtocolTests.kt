package dev.offlinesync.core

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class ProtocolTests {
    private val fixtures = WireJson.format.parseToJsonElement(
        requireNotNull(javaClass.getResource("/wire-v1.json")).readText(),
    ).jsonObject

    @Test
    fun canonicalEncodingMatchesSharedBackendCases() {
        val cases = WireJson.format.parseToJsonElement(
            requireNotNull(javaClass.getResource("/canonical-v1.json")).readText(),
        ).jsonArray
        for (element in cases) {
            val fixture = element.jsonObject
            assertEquals(fixture.getValue("canonical").jsonPrimitive.content,
                WireJson.canonical(fixture.getValue("value")))
        }
    }

    @Test
    fun mutationHashIgnoresMapAndRemovalInsertionOrder() {
        val mutation = WireJson.format.decodeFromJsonElement<PushRequest>(fixtures.getValue("pushRequest")).mutations.single()
        assertEquals(fixtures.getValue("mutationHash").jsonPrimitive.content, WireJson.hash(mutation))
        val first = mutation.copy(delta = mutation.delta.copy(sets = mapOf(
            "tags" to ObservedRemoveSet(removes = linkedSetOf("b", "a")),
        )))
        val reordered = first.copy(delta = first.delta.copy(
            registers = first.delta.registers.entries.reversed().associate { it.toPair() },
            sets = mapOf("tags" to ObservedRemoveSet(removes = linkedSetOf("a", "b"))),
        ))
        assertEquals(WireJson.hash(first), WireJson.hash(reordered))
        assertEquals(64, WireJson.hash(first).length)
    }

    @Test
    fun typedMessagesRoundTripSharedWireFixture() {
        roundTrip<PushRequest>(fixtures.getValue("pushRequest"))
        roundTrip<PushResponse>(fixtures.getValue("pushResponse"))
        roundTrip<PullResponse>(fixtures.getValue("pullResponse"))
        val response = WireJson.format.decodeFromJsonElement<PushResponse>(fixtures.getValue("pushResponse"))
        assertEquals(9_007_199_254_740_993L, response.acknowledgments.first().revision)
        assertNull(response.acknowledgments.last().state)
    }

    @Test
    fun validatesOwnershipTypesBoundsAndClockSkew() {
        val mutation = WireJson.format.decodeFromJsonElement<PushRequest>(fixtures.getValue("pushRequest")).mutations.single()
        assertNull(MutationValidation.error(mutation, 1_700_000_000_000))
        assertEquals("CLOCK_SKEW", MutationValidation.error(mutation, 1_699_999_699_999))
        assertNull(MutationValidation.error(mutation, 1_699_999_700_000))
        assertEquals("UNSUPPORTED_VERSION", MutationValidation.error(mutation.copy(protocolVersion = 2), Long.MAX_VALUE))
        assertEquals("INVALID_MUTATION", MutationValidation.error(mutation.copy(localSequence = 0), 1_700_000_000_000))
        assertEquals("EMPTY_DELTA", MutationValidation.error(mutation.copy(delta = DocumentDelta()), 1_700_000_000_000))
        val stamp = HybridTimestamp(100, 0, mutation.replicaId)
        val invalidBoolean = DocumentDelta(registers = mapOf("completed" to RegisterValue(JsonPrimitive("true"), stamp)))
        assertEquals("UNKNOWN_FIELD_OR_TYPE", MutationValidation.error(mutation.copy(delta = invalidBoolean), 100))
        val invalidCounter = DocumentDelta(counters = mapOf("quantity" to PnCounter(mapOf("other" to 1))))
        assertEquals("INVALID_COUNTER_COMPONENT", MutationValidation.error(mutation.copy(delta = invalidCounter), 100))
        val invalidSet = DocumentDelta(sets = mapOf("tags" to ObservedRemoveSet(mapOf("other" to "tag"))))
        assertEquals("INVALID_DOT", MutationValidation.error(mutation.copy(delta = invalidSet), 100))
        assertFalse(MutationValidation.isId("00000000-0000-0000-0000-000000000000"))
        assertFalse(MutationValidation.isId("AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA"))
    }

    @Test
    fun rejectsUnknownFieldsAndInvalidRequiredTypes() {
        assertFailsWith<SerializationException> { WireJson.format.decodeFromString<DocumentDelta>("{\"unknown\":true}") }
        assertFailsWith<SerializationException> { WireJson.format.decodeFromString<HybridTimestamp>("{\"physicalMs\":0}") }
        assertFailsWith<SerializationException> {
            WireJson.format.decodeFromString<HybridTimestamp>("{\"physicalMs\":1.5,\"logical\":0,\"replicaId\":\"a\"}")
        }
    }

    private inline fun <reified Message> roundTrip(value: JsonElement) {
        val decoded = WireJson.format.decodeFromJsonElement<Message>(value)
        assertEquals(value, WireJson.format.encodeToJsonElement(decoded))
    }
}