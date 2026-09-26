package com.patmanak.contako.data.proton

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.platform.app.InstrumentationRegistry
import com.patmanak.contako.data.proton.OriginBoundVerificationBridge.Companion.isVerificationOrigin
import java.io.ByteArrayInputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** Real device/WebView security boundary; all HTTPS responses are local, synthetic and intercepted. */
class OriginBoundVerificationBridgeDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var webView: WebView
    private var bridge: OriginBoundVerificationBridge? = null
    private val messages = CopyOnWriteArrayList<String>()
    private val received = CountDownLatch(1)
    private val finished = CountDownLatch(1)

    @After
    fun cleanup() = instrumentation.runOnMainSync {
        bridge?.close()
        if (::webView.isInitialized) {
            webView.stopLoading()
            webView.destroy()
        }
    }

    @Test
    fun exactHttpsOriginRejectsLookalikesUserInfoPortsAndOpaqueUris() {
        listOf("https://verify.proton.me", "https://VERIFY.PROTON.ME:443/path").forEach {
            assertTrue(Uri.parse(it).isVerificationOrigin())
        }
        listOf("http://verify.proton.me", "https://verify.proton.me.attacker.invalid",
            "https://verify.proton.me:444", "https://user@verify.proton.me",
            "https://other.proton.me", "data:text/html,fixture", "file:///fixture", "null",
        ).forEach { assertFalse(Uri.parse(it).isVerificationOrigin()) }
    }

    @Test
    fun protonDispatchExistsBeforePageCodeAndDeliversStringOnly() {
        start("""
            <script>
            AndroidInterface.dispatch({type: 'not-a-string'});
            AndroidInterface.dispatch('x'.repeat(32769));
            ContakoVerificationMessage.postMessage('x'.repeat(32769));
            ContakoVerificationMessage.postMessage(new Uint8Array([1,2]).buffer);
            AndroidInterface.dispatch(JSON.stringify({type:'HUMAN_VERIFICATION_SUCCESS',
                payload:{type:'captcha',token:'synthetic-proof'}}));
            </script>
        """.trimIndent())
        assertTrue("Trusted dispatch did not reach the native listener", received.await(15, TimeUnit.SECONDS))
        assertEquals(listOf("""{"type":"HUMAN_VERIFICATION_SUCCESS","payload":{"type":"captcha","token":"synthetic-proof"}}"""), messages.toList())
    }

    @Test
    fun crossOriginSameOriginAndOpaqueFramesCannotDispatchDirectly() {
        val child = """
            <script>
            if (typeof ContakoVerificationMessage !== 'undefined')
                ContakoVerificationMessage.postMessage('forged-child');
            if (typeof AndroidInterface !== 'undefined') AndroidInterface.dispatch('forged-shim');
            parent.postMessage('child-finished', 'https://verify.proton.me');
            </script>
        """.trimIndent()
        val opaque = android.util.Base64.encodeToString(child.toByteArray(), android.util.Base64.NO_WRAP)
        start("""
            <script>
            let completed = 0;
            window.addEventListener('message', event => {
                if(event.data === 'child-finished' && ++completed === 3)
                    AndroidInterface.dispatch('all-children-finished');
            });
            </script>
            <iframe src="https://attacker.invalid/child"></iframe>
            <iframe src="https://verify.proton.me/child"></iframe>
            <iframe src="data:text/html;base64,$opaque"></iframe>
        """.trimIndent(), child)
        assertTrue("All adversarial frames must execute", received.await(15, TimeUnit.SECONDS))
        assertEquals(listOf("all-children-finished"), messages.toList())
    }

    @Test
    fun untrustedMainFrameHasNeitherCompatibilityShimNorNativeChannel() {
        start("<html><body>Isolated fixture</body></html>", origin = "https://attacker.invalid/")
        assertTrue(finished.await(15, TimeUnit.SECONDS))
        assertEquals("\"undefined/undefined\"", evaluate("typeof AndroidInterface + '/' + typeof ContakoVerificationMessage"))
        assertTrue(messages.isEmpty())
    }

    @Test
    fun closedBridgeRejectsExistingDocumentAndDoesNotInjectIntoNextDocument() {
        start("<html><body>Isolated fixture</body></html>")
        assertTrue(finished.await(15, TimeUnit.SECONDS))
        assertEquals("\"object\"", evaluate("typeof AndroidInterface"))
        instrumentation.runOnMainSync { bridge!!.close() }
        evaluate("try { AndroidInterface.dispatch('after-close'); } catch (_) {}")
        instrumentation.waitForIdleSync()
        assertTrue(messages.isEmpty())
        val next = CountDownLatch(1)
        instrumentation.runOnMainSync {
            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) { next.countDown() }
            }
            webView.loadDataWithBaseURL("https://verify.proton.me/", "<html></html>", "text/html", "UTF-8", null)
        }
        assertTrue(next.await(15, TimeUnit.SECONDS))
        assertEquals("\"undefined/undefined\"", evaluate("typeof AndroidInterface + '/' + typeof ContakoVerificationMessage"))
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun start(main: String, child: String = "", origin: String = "https://verify.proton.me/") {
        instrumentation.runOnMainSync {
            webView = WebView(instrumentation.targetContext)
            webView.settings.javaScriptEnabled = true
            webView.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                    val html = if (request.isForMainFrame) main else child
                    return WebResourceResponse("text/html", "UTF-8", ByteArrayInputStream(html.toByteArray()))
                }
                override fun onPageFinished(view: WebView, url: String) { finished.countDown() }
            }
            bridge = OriginBoundVerificationBridge.install(webView) {
                messages.add(it)
                received.countDown()
            }
            assertNotNull("Physical WebView must support the origin-bound bridge", bridge)
            webView.loadUrl(origin)
        }
    }

    private fun evaluate(script: String): String {
        val done = CountDownLatch(1)
        var result = ""
        instrumentation.runOnMainSync {
            webView.evaluateJavascript(script) { result = it; done.countDown() }
        }
        assertTrue(done.await(10, TimeUnit.SECONDS))
        return result
    }
}
