package dev.offlinesync.android

import dev.offlinesync.core.PullResponse
import dev.offlinesync.core.PushRequest
import dev.offlinesync.core.PushResponse
import dev.offlinesync.core.WireJson
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.GzipSink
import okio.buffer
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Query
import retrofit2.http.Streaming
import java.io.IOException
import java.util.concurrent.TimeUnit

fun interface SyncTokenProvider {
    suspend fun accessToken(): String?
}

interface SyncTransport {
    suspend fun push(request: PushRequest): PushResponse
    suspend fun pull(cursor: String?, limit: Int = 100): PullResponse
}

class SyncAuthenticationException : IOException("Sync credentials are missing or were rejected.")
class SyncProtocolException : IOException("Invalid or oversized sync message.")
class SyncHttpException(val statusCode: Int) : IOException("Sync HTTP status $statusCode") {
    val retryable: Boolean get() = statusCode == 408 || statusCode == 429 || statusCode in 500..599
}

internal interface SyncHttpApi {
    @POST("v1/sync/push")
    @Streaming
    suspend fun push(@Header("Authorization") authorization: String, @Body body: RequestBody): Response<ResponseBody>

    @GET("v1/sync/pull")
    @Streaming
    suspend fun pull(@Header("Authorization") authorization: String, @Query("cursor") cursor: String?,
        @Query("limit") limit: Int): Response<ResponseBody>
}

class HttpSyncTransport(
    baseUrl: String,
    private val tokens: SyncTokenProvider,
    allowLocalHttp: Boolean = false,
) : SyncTransport {
    private val api: SyncHttpApi

    init {
        val url = baseUrl.toHttpUrl()
        require(url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null)
        require(url.encodedPath == "/") { "The sync base URL must be an origin URL." }
        require(url.isHttps || (allowLocalHttp && url.host in setOf("localhost", "127.0.0.1", "::1", "10.0.2.2")))
        val client = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .callTimeout(30, TimeUnit.SECONDS)
            .connectTimeout(10, TimeUnit.SECONDS)
            .addInterceptor(GzipRequestInterceptor())
            .addInterceptor { chain -> boundResponse(chain.proceed(chain.request())) }
            .addNetworkInterceptor { chain -> boundResponse(chain.proceed(chain.request())) }
            .build()
        api = Retrofit.Builder().baseUrl(url).client(client).build().create(SyncHttpApi::class.java)
    }

    override suspend fun push(request: PushRequest): PushResponse {
        require(request.protocolVersion == 1 && request.mutations.size in 1..100)
        require(request.mutations.all { it.replicaId == request.replicaId && it.protocolVersion == 1 })
        val bytes = WireJson.format.encodeToString(request).toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_BYTES) throw SyncProtocolException()
        val response = decode<PushResponse>(api.push(authorization(), bytes.toRequestBody(JSON)))
        if (response.acknowledgments.size > request.mutations.size ||
            response.acknowledgments.map { it.mutationId } != request.mutations
                .take(response.acknowledgments.size).map { it.mutationId }) throw SyncProtocolException()
        return response
    }

    override suspend fun pull(cursor: String?, limit: Int): PullResponse {
        require(limit in 1..100 && (cursor == null || cursor.length in 1..4096))
        val response = decode<PullResponse>(api.pull(authorization(), cursor, limit))
        if (response.changes.size > limit) throw SyncProtocolException()
        return response
    }

    private suspend fun authorization(): String {
        val token = tokens.accessToken()
        if (token.isNullOrBlank() || token.any { it.code !in 0x21..0x7e }) throw SyncAuthenticationException()
        return "Bearer $token"
    }

    private inline fun <reified Message> decode(response: Response<ResponseBody>): Message {
        if (!response.isSuccessful) {
            response.errorBody()?.close()
            if (response.code() == 401 || response.code() == 403) throw SyncAuthenticationException()
            throw SyncHttpException(response.code())
        }
        val body = response.body() ?: throw SyncProtocolException()
        return body.use {
            val mediaType = it.contentType()
            if (mediaType?.subtype != "json" && mediaType?.subtype?.endsWith("+json") != true)
                throw SyncProtocolException()
            try {
                WireJson.format.decodeFromString<Message>(it.string())
            } catch (_: SerializationException) {
                throw SyncProtocolException()
            } catch (_: IllegalArgumentException) {
                throw SyncProtocolException()
            }
        }
    }

    private companion object {
        const val MAX_BYTES = 1_048_576L
        val JSON = "application/json; charset=utf-8".toMediaType()

        fun boundResponse(response: okhttp3.Response): okhttp3.Response {
            val body = response.body ?: return response
            if (body.contentLength() > MAX_BYTES) {
                response.close()
                throw SyncProtocolException()
            }
            val source = object : ForwardingSource(body.source()) {
                var total = 0L
                override fun read(sink: Buffer, byteCount: Long): Long {
                    val count = super.read(sink, minOf(byteCount, MAX_BYTES - total + 1))
                    if (count > 0) total += count
                    if (total > MAX_BYTES) throw SyncProtocolException()
                    return count
                }
            }.buffer()
            return response.newBuilder().body(source.asResponseBody(body)).build()
        }

        fun okio.BufferedSource.asResponseBody(original: ResponseBody): ResponseBody = object : ResponseBody() {
            override fun contentType() = original.contentType()
            override fun contentLength() = original.contentLength()
            override fun source() = this@asResponseBody
        }
    }
}

internal class GzipRequestInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
        val request = chain.request()
        val body = request.body ?: return chain.proceed(request)
        val compressed = Buffer()
        GzipSink(compressed).buffer().use { body.writeTo(it) }
        val bytes = compressed.readByteArray()
        if (bytes.size > 1_048_576) throw SyncProtocolException()
        return chain.proceed(request.newBuilder().header("Content-Encoding", "gzip")
            .method(request.method, bytes.toRequestBody(body.contentType())).build())
    }
}