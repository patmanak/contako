package com.patmanak.contako.data.proton

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import me.proton.core.network.domain.client.ClientId
import me.proton.core.network.domain.client.ClientIdProvider
import me.proton.core.network.domain.session.SessionId
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtonHostPolicyContractTest {
    @Test
    fun `pre-auth client id fallback is stable in memory and authenticated session wins`() = runBlocking {
        val provider = GateCPreAuthClientIdProvider(
            delegate = object : ClientIdProvider {
                override suspend fun getClientId(sessionId: SessionId?): ClientId? =
                    sessionId?.let(ClientId::AccountSession)
            },
            fallbackIdFactory = { "synthetic-memory-only-correlation" },
        )

        val first = provider.getClientId(null)
        val second = provider.getClientId(null)
        val authenticated = provider.getClientId(SessionId("synthetic-account-session"))

        assertTrue(first is ClientId.CookieSession)
        assertEquals(first, second)
        assertEquals(
            ClientId.AccountSession(SessionId("synthetic-account-session")),
            authenticated,
        )
    }

    @Test
    fun `301 302 307 and 308 never follow same-origin redirects or replay credentials and bodies`() {
        listOf(301, 302, 307, 308).forEach { status ->
            RedirectProbe(status).use { probe ->
                val audit = CountingAudit()
                val response = buildGateCOkHttpClient(audit).newCall(
                    Request.Builder()
                        .url(probe.entryUrl)
                        .header("Authorization", "Bearer synthetic-secret")
                        .post("synthetic-body-canary".toRequestBody("application/json".toMediaType()))
                        .build(),
                ).execute()

                response.use { assertEquals(status, it.code) }
                probe.await()
                assertEquals(1, probe.requestCount.get())
                assertTrue(probe.firstRequestLine.startsWith("POST /contacts/v4/contacts "))
                assertFalse(probe.observedRequest.contains("/redirect-target"))
                assertEquals(1, audit.requests)
                assertEquals(1, audit.mutations)
            }
        }
    }

    @Test
    fun `foreign-origin redirect target receives no authorization cookie or request body`() {
        PassiveTarget().use { target ->
            ForeignRedirectProbe(target.url).use { source ->
                val response = buildGateCOkHttpClient(GateCRequestAudit.Disabled).newCall(
                    Request.Builder()
                        .url(source.entryUrl)
                        .header("Authorization", "Bearer synthetic-secret")
                        .header("Cookie", "synthetic-cookie=secret")
                        .put("synthetic-body-canary".toRequestBody("application/json".toMediaType()))
                        .build(),
                ).execute()

                response.use { assertEquals(307, it.code) }
                target.awaitNoRequest()
                assertEquals(0, target.requestCount.get())
            }
        }
    }
}

private class CountingAudit : GateCRequestAudit {
    var requests = 0
    var mutations = 0

    override fun onRequest(requestClass: GateCRequestClass) {
        requests++
    }

    override fun onDataMutationAttempt(mutationClass: GateCDataMutationClass) {
        mutations++
    }
}

private class RedirectProbe(private val status: Int) : AutoCloseable {
    private val server = ServerSocket(0, 8, InetAddress.getLoopbackAddress()).apply { soTimeout = 1_500 }
    val requestCount = AtomicInteger()
    var observedRequest = ""
        private set
    var firstRequestLine = ""
        private set
    val entryUrl = "http://${server.inetAddress.hostAddress}:${server.localPort}/contacts/v4/contacts"
    private val worker = thread(name = "contako-redirect-$status") {
        try {
            server.accept().use { socket ->
                requestCount.incrementAndGet()
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.US_ASCII))
                val lines = mutableListOf<String>()
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    lines += line
                }
                firstRequestLine = lines.firstOrNull().orEmpty()
                observedRequest = lines.joinToString("\n")
                val response = "HTTP/1.1 $status Redirect\r\n" +
                    "Location: http://${server.inetAddress.hostAddress}:${server.localPort}/redirect-target\r\n" +
                    "Content-Length: 0\r\nConnection: close\r\n\r\n"
                socket.getOutputStream().write(response.toByteArray(Charsets.US_ASCII))
            }
            try {
                server.accept().use { requestCount.incrementAndGet() }
            } catch (_: SocketTimeoutException) {
                Unit
            }
        } finally {
            server.close()
        }
    }

    fun await() = worker.join(3_000)
    override fun close() {
        server.close()
        worker.join(3_000)
    }
}

private class ForeignRedirectProbe(private val target: String) : AutoCloseable {
    private val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).apply { soTimeout = 1_500 }
    val entryUrl = "http://${server.inetAddress.hostAddress}:${server.localPort}/entry"
    private val worker = thread(name = "contako-foreign-redirect") {
        server.accept().use { socket ->
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.US_ASCII))
            while (!reader.readLine().isNullOrEmpty()) Unit
            val response = "HTTP/1.1 307 Redirect\r\nLocation: $target\r\n" +
                "Content-Length: 0\r\nConnection: close\r\n\r\n"
            socket.getOutputStream().write(response.toByteArray(Charsets.US_ASCII))
        }
        server.close()
    }

    override fun close() {
        server.close()
        worker.join(3_000)
    }
}

private class PassiveTarget : AutoCloseable {
    private val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).apply { soTimeout = 1_500 }
    val url = "http://${server.inetAddress.hostAddress}:${server.localPort}/redirect-target"
    val requestCount = AtomicInteger()
    private val worker = thread(name = "contako-passive-target") {
        try {
            server.accept().use { requestCount.incrementAndGet() }
        } catch (_: SocketTimeoutException) {
            Unit
        } finally {
            server.close()
        }
    }

    fun awaitNoRequest() = worker.join(3_000)
    override fun close() {
        server.close()
        worker.join(3_000)
    }
}
