package dev.offlinesync.android

import dev.offlinesync.core.DocumentDelta
import dev.offlinesync.core.Mutation
import dev.offlinesync.core.MutationAck
import dev.offlinesync.core.PullResponse
import dev.offlinesync.core.PushRequest
import dev.offlinesync.core.PushResponse
import dev.offlinesync.core.RegisterValue
import dev.offlinesync.core.HybridTimestamp
import dev.offlinesync.core.WireJson
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import okio.GzipSink
import okio.GzipSource
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class HttpSyncTransportTests {
    private val replica = "11111111-1111-4111-8111-111111111111"
    private val mutation = Mutation(1, replica, "22222222-2222-4222-8222-222222222222", 1,
        "33333333-3333-4333-8333-333333333333", DocumentDelta(deleted = true))
    private val request = PushRequest(1, replica, listOf(mutation))

    @Test
    fun pushUsesGzipWireBytesAndDecodesGzipResponse() = runBlocking {
        MockWebServer().use { server ->
            val expected = PushResponse(listOf(MutationAck(mutation.mutationId, "SYNCED", 1, mutation.delta)))
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
                .setHeader("Content-Encoding", "gzip").setBody(gzip(WireJson.format.encodeToString(expected))))
            val transport = HttpSyncTransport(server.url("/").toString(), { "test-token" }, true)
            assertEquals(expected, transport.push(request))
            val sent = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            assertEquals("POST", sent.method)
            assertEquals("/v1/sync/push", sent.path)
            assertEquals("Bearer test-token", sent.getHeader("Authorization"))
            assertEquals("gzip", sent.getHeader("Content-Encoding"))
            assertEquals("gzip", sent.getHeader("Accept-Encoding"))
            assertEquals(0x1f, sent.body[0].toInt() and 255)
            assertEquals(0x8b, sent.body[1].toInt() and 255)
            val decoded = GzipSource(sent.body).buffer().use { it.readUtf8() }
            assertEquals(request, WireJson.format.decodeFromString<PushRequest>(decoded))
        }
    }

    @Test
    fun pullEncodesCursorAndObtainsFreshTokenOnEveryRequest() = runBlocking {
        MockWebServer().use { server ->
            val page = PullResponse(emptyList(), "next", false)
            repeat(2) { server.enqueue(json(WireJson.format.encodeToString(page))) }
            var token = "first"
            val transport = HttpSyncTransport(server.url("/").toString(), { token }, true)
            assertEquals(page, transport.pull("signed+/=&cursor", 25))
            val first = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            assertEquals("signed+/=&cursor", first.requestUrl?.queryParameter("cursor"))
            assertEquals("25", first.requestUrl?.queryParameter("limit"))
            assertEquals("Bearer first", first.getHeader("Authorization"))
            token = "refreshed"
            transport.pull(null)
            val second = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            assertEquals("Bearer refreshed", second.getHeader("Authorization"))
            assertEquals(null, second.requestUrl?.queryParameter("cursor"))
        }
    }

    @Test
    fun authenticationAndHttpFailuresExposeNoResponsePayload() = runBlocking {
        MockWebServer().use { server ->
            val transport = HttpSyncTransport(server.url("/").toString(), { "test" }, true)
            for (status in listOf(401, 403)) {
                server.enqueue(json("{\"detail\":\"private server text\"}").setResponseCode(status))
                val failure = assertThrows(SyncAuthenticationException::class.java) { runBlocking { transport.pull(null) } }
                assertFalse(requireNotNull(failure.message).contains("private"))
            }
            for (status in listOf(400, 408, 429, 503)) {
                server.enqueue(json("{\"detail\":\"private server text\"}").setResponseCode(status))
                val failure = assertThrows(SyncHttpException::class.java) { runBlocking { transport.push(request) } }
                assertEquals(status, failure.statusCode)
                assertEquals(status != 400, failure.retryable)
                assertFalse(requireNotNull(failure.message).contains("private"))
            }
        }
    }

    @Test
    fun redirectsAreNotFollowed() = runBlocking {
        MockWebServer().use { server ->
            MockWebServer().use { other ->
                other.start()
                server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", other.url("/stolen")))
                val transport = HttpSyncTransport(server.url("/").toString(), { "test" }, true)
                assertThrows(SyncHttpException::class.java) { runBlocking { transport.push(request) } }
                assertEquals(0, other.requestCount)
                assertEquals(1, server.requestCount)
            }
        }
    }

    @Test
    fun oversizedExpandedSuccessAndErrorResponsesAreRejected() = runBlocking {
        MockWebServer().use { server ->
            val transport = HttpSyncTransport(server.url("/").toString(), { "test" }, true)
            for (status in listOf(200, 503)) {
                server.enqueue(MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json")
                    .setHeader("Content-Encoding", "gzip").setBody(gzip(" ".repeat(1_048_577))))
                assertThrows(SyncProtocolException::class.java) { runBlocking { transport.pull(null) } }
            }
        }
    }

    @Test
    fun oversizedDeclaredAndChunkedResponsesAreRejected(): Unit = runBlocking {
        MockWebServer().use { server ->
            val transport = HttpSyncTransport(server.url("/").toString(), { "test" }, true)
            server.enqueue(json(" ".repeat(1_048_577)))
            assertThrows(SyncProtocolException::class.java) { runBlocking { transport.pull(null) } }
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
                .setChunkedBody(" ".repeat(1_048_577), 8192))
            assertThrows(SyncProtocolException::class.java) { runBlocking { transport.pull(null) } }
        }
    }

    @Test
    fun malformedAndMismatchedResponsesFailButOrderedPartialAcknowledgmentsAreAllowed() = runBlocking {
        MockWebServer().use { server ->
            val transport = HttpSyncTransport(server.url("/").toString(), { "test" }, true)
            server.enqueue(json("{\"unexpected\":true}"))
            assertThrows(SyncProtocolException::class.java) { runBlocking { transport.pull(null) } }
            server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody("private"))
            assertThrows(SyncProtocolException::class.java) { runBlocking { transport.pull(null) } }
            server.enqueue(json(WireJson.format.encodeToString(PushResponse(listOf(
                MutationAck("wrong-id", "REJECTED", errorCode = "INVALID_MUTATION"),
            )))))
            assertThrows(SyncProtocolException::class.java) { runBlocking { transport.push(request) } }
            server.enqueue(json(WireJson.format.encodeToString(PushResponse(emptyList()))))
            assertTrue(transport.push(request).acknowledgments.isEmpty())
        }
    }

    @Test
    fun oversizedPushAndMissingCredentialsNeverReachNetwork() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val transport = HttpSyncTransport(server.url("/").toString(), { "test" }, true)
            val large = mutation.copy(delta = DocumentDelta(registers = mapOf("title" to
                RegisterValue(JsonPrimitive("x".repeat(1_048_576)), HybridTimestamp(1, 0, replica)))))
            assertThrows(SyncProtocolException::class.java) {
                runBlocking { transport.push(request.copy(mutations = listOf(large))) }
            }
            val missing = HttpSyncTransport(server.url("/").toString(), { null }, true)
            assertThrows(SyncAuthenticationException::class.java) { runBlocking { missing.pull(null) } }
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun insecureOrCredentialBearingBaseUrlsAreRejected() {
        for (url in listOf("http://example.com/", "https://user:password@example.com/", "https://example.com/base/")) {
            assertThrows(IllegalArgumentException::class.java) { HttpSyncTransport(url, { "test" }, true) }
        }
        assertThrows(IllegalArgumentException::class.java) { HttpSyncTransport("http://localhost/", { "test" }) }
    }

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    private fun gzip(text: String): Buffer {
        val result = Buffer()
        GzipSink(result).buffer().use { it.writeUtf8(text) }
        return result
    }
}