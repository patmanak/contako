package com.patmanak.contako.data.proton

import java.io.IOException
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.zip.GZIPOutputStream
import kotlin.concurrent.thread
import com.patmanak.contako.data.gateway.GatewayFailureCategory
import me.proton.core.network.domain.ApiException
import me.proton.core.network.domain.ApiResult
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Retrofit

/** Exercises the real Retrofit converter and production limiter, without any remote request. */
class ProtonRawResponseBoundaryTest {
    @Test
    fun realHttpChunkedGzipIsLimitedAfterDecompressionForSuccessAndError() = runBlocking {
        for ((method, path) in listOf("POST" to "contacts/v4/contacts", "GET" to "contacts/v4/contacts/fixture",
            "GET" to "contacts/v4/contacts", "GET" to "core/v4/labels")) for (status in listOf(200, 400)) {
            val limit = requireNotNull(GateDRawResponseLimits.forRequest(method, path.split('/')))
            val compressed = ByteArrayOutputStream().also { output ->
                GZIPOutputStream(output).use { it.write(ByteArray(limit + 1) { 'x'.code.toByte() }) }
            }.toByteArray()
            ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
                server.soTimeout = 5_000
                val responder = thread(isDaemon = true) {
                    server.accept().use { socket ->
                        socket.soTimeout = 5_000
                        val input = socket.getInputStream().bufferedReader()
                        var length = 0
                        while (true) {
                            val line = input.readLine() ?: break
                            if (line.isEmpty()) break
                            if (line.startsWith("Content-Length:", ignoreCase = true)) {
                                length = line.substringAfter(':').trim().toInt()
                            }
                        }
                        repeat(length) { input.read() }
                        socket.getOutputStream().use { output ->
                            output.write(("HTTP/1.1 $status synthetic\r\nContent-Encoding: gzip\r\n" +
                                "Transfer-Encoding: chunked\r\nConnection: close\r\n\r\n" +
                                compressed.size.toString(16) + "\r\n").toByteArray())
                            output.write(compressed)
                            output.write("\r\n0\r\n\r\n".toByteArray())
                        }
                    }
                }
                val client = OkHttpClient.Builder().addInterceptor(GateDBoundedResponseInterceptor).build()
                val host = requireNotNull(server.inetAddress.hostAddress).let { if (':' in it) "[$it]" else it }
                val request = okhttp3.Request.Builder().url("http://$host:${server.localPort}/$path")
                    .method(method, if (method == "POST") "{}".toRequestBody() else null).build()
                try {
                    client.newCall(request).execute().use { it.body!!.string() }
                    error("DECOMPRESSED_BODY_LIMIT_NOT_ENFORCED")
                } catch (_: ProtonResponseSizeExceeded) {
                    // The compressed wire body is small, but the decoded body exceeds the cap.
                    assertTrue(compressed.size < limit)
                } finally {
                    responder.join(5_000)
                    assertTrue("LOCAL_HTTP_RESPONDER_STILL_RUNNING", !responder.isAlive)
                    client.connectionPool.evictAll()
                    client.dispatcher.executorService.shutdown()
                }
            }
        }
    }

    @Test
    fun responseLimitIsMalformedEvenWhenCoreWrapsItAsAConnectionError() {
        val failure = ProtonResponseSizeExceeded()
        assertEquals(GatewayFailureCategory.MALFORMED_RESPONSE, failure.toContactGatewayFailure())
        assertEquals(GatewayFailureCategory.MALFORMED_RESPONSE,
            ApiException(ApiResult.Error.Connection(cause = failure)).toContactGatewayFailure())
        assertEquals(GatewayFailureCategory.NETWORK_UNAVAILABLE, IOException().toContactGatewayFailure())
    }

    @Test
    fun allRawRoutesReturnAnUnreadStreamAndCloseOnBoundedRejection() = runBlocking {
        val request = "{}".toRequestBody("application/json".toMediaType())
        val calls: List<suspend (ProtonGateDWireApi) -> ResponseBody> = listOf(
            { it.createContacts(request) }, { it.getRichContacts(0, 1) },
            { it.getContactEmails(0, 1) }, { it.labelContactEmails(request) },
            { it.unlabelContactEmails(request) },
        )
        for (call in calls) {
            val body = CountingBody(8_192, declared = false)
            val returned = call(api(body))
            assertEquals("Retrofit must not buffer before the application cap", 0L, body.readBytes)
            try {
                returned.use { it.readBoundedUtf8(1_024) }
                error("OVERSIZED_BODY_ACCEPTED")
            } catch (_: ProtonMalformedContactResponse) {
                assertTrue(body.closed)
                assertTrue(body.readBytes <= 8_192)
            }
        }
    }

    @Test
    fun oversizedSuccessAndErrorBodiesAreCappedBeforeRetrofitBuffering() = runBlocking {
        for (status in listOf(200, 400, 500)) {
            for (declared in listOf(true, false)) {
                val body = CountingBody(1_048_576, declared)
                try {
                    api(body, status).createContacts("{}".toRequestBody()).use { it.string() }
                    error("OVERSIZED_BODY_ACCEPTED")
                } catch (_: IOException) {
                    assertTrue(body.closed)
                    assertTrue(body.readBytes <= GateDRawResponseLimits.CREATE_BYTES + 8_192L)
                    if (declared) assertEquals(0L, body.readBytes)
                }
            }
        }
    }

    @Test
    fun exactLimitSuccessAndSmallErrorKeepTheirExistingSemantics() = runBlocking {
        val body = CountingBody(GateDRawResponseLimits.CREATE_BYTES, declared = false)
        api(body).createContacts("{}".toRequestBody()).use {
            assertEquals(GateDRawResponseLimits.CREATE_BYTES, it.readBoundedUtf8(GateDRawResponseLimits.CREATE_BYTES).length)
        }
        assertTrue(body.closed)
        val errorBody = CountingBody(128, declared = false)
        try {
            api(errorBody, 400).createContacts("{}".toRequestBody())
            error("ERROR_STATUS_ACCEPTED")
        } catch (error: HttpException) {
            assertEquals(400, error.code())
            error.response()?.errorBody()?.use { assertEquals(128, it.string().length) }
            assertTrue(errorBody.closed)
        }
    }

    private fun api(body: ResponseBody, status: Int = 200): ProtonGateDWireApi {
        val client = OkHttpClient.Builder()
            .addInterceptor(GateDBoundedResponseInterceptor)
            .addInterceptor { chain ->
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(status).message("synthetic").body(body).build()
            }.build()
        return Retrofit.Builder().baseUrl("https://example.invalid/").client(client)
            .build().create(ProtonGateDWireApi::class.java)
    }

    private class CountingBody(private val size: Int, private val declared: Boolean) : ResponseBody() {
        var readBytes = 0L
        var closed = false
        private val input = object : Source {
            override fun read(sink: Buffer, byteCount: Long): Long {
                if (readBytes == size.toLong()) return -1
                val count = minOf(byteCount, size - readBytes, 8_192L).toInt()
                sink.write(ByteArray(count) { 'x'.code.toByte() })
                readBytes += count
                return count.toLong()
            }
            override fun timeout() = Timeout.NONE
            override fun close() { closed = true }
        }.buffer()
        override fun contentType() = "application/json".toMediaType()
        override fun contentLength() = if (declared) size.toLong() else -1L
        override fun source() = input
    }
}
