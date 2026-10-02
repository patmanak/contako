package com.patmanak.contako.data.proton

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebStorage
import com.patmanak.contako.data.proton.OriginBoundVerificationBridge.Companion.isVerificationOrigin
import java.net.URLEncoder
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.proton.core.network.domain.client.ClientId
import me.proton.core.network.domain.humanverification.HumanVerificationAvailableMethods
import me.proton.core.network.domain.humanverification.HumanVerificationDetails
import me.proton.core.network.domain.humanverification.HumanVerificationListener
import me.proton.core.network.domain.humanverification.HumanVerificationProvider
import me.proton.core.network.domain.humanverification.HumanVerificationState
import org.json.JSONObject
import org.json.JSONException

internal data class GateCHumanVerificationUiState(
    val isRequired: Boolean = false,
    val generation: Long = 0,
)

/**
 * Interactive, memory-only implementation of Proton Core's human-verification contract.
 *
 * Proton Core suspends the rejected request in [onHumanVerificationNeeded]. The phone UI solves
 * the hosted Proton challenge, [getHumanVerificationDetails] supplies the resulting one-time
 * headers, and Core retries the original request. Challenge and solution values never enter the
 * Compose state, a database, saved state, diagnostics, or logs.
 */
internal class GateCInteractiveHumanVerification : HumanVerificationProvider, HumanVerificationListener {
    private val guard = Any()
    private val nextGeneration = AtomicLong(0)
    private val mutableUiState = MutableStateFlow(GateCHumanVerificationUiState())
    private var pending: Pending? = null
    private var solved: HumanVerificationDetails? = null
    private val loadedGenerations = WeakHashMap<WebView, Long>()
    private val bridges = WeakHashMap<WebView, OriginBoundVerificationBridge>()

    val uiState: StateFlow<GateCHumanVerificationUiState> = mutableUiState.asStateFlow()

    val hooks = GateCHumanVerificationHooks(
        provider = this,
        listener = this,
        clear = ::clear,
    )

    override suspend fun getHumanVerificationDetails(clientId: ClientId): HumanVerificationDetails? =
        synchronized(guard) { solved?.takeIf { it.clientId.id == clientId.id } }

    override suspend fun onHumanVerificationNeeded(
        clientId: ClientId,
        methods: HumanVerificationAvailableMethods,
    ): HumanVerificationListener.HumanVerificationResult {
        val challenge = requireChallenge(clientId, methods)
        val current = synchronized(guard) {
            pending?.takeIf { it.matches(challenge) } ?: Pending(
                generation = nextGeneration.incrementAndGet(),
                challenge = challenge,
            ).also {
                pending?.completion?.complete(HumanVerificationListener.HumanVerificationResult.Failure)
                pending = it
                solved = null
                mutableUiState.value = GateCHumanVerificationUiState(true, it.generation)
            }
        }
        return try {
            current.completion.await()
        } finally {
            synchronized(guard) {
                if (pending === current) {
                    pending = null
                    mutableUiState.value = GateCHumanVerificationUiState()
                }
            }
        }
    }

    override suspend fun onHumanVerificationInvalid(clientId: ClientId) {
        synchronized(guard) {
            if (solved?.clientId?.id == clientId.id) solved = null
        }
    }

    fun cancel() {
        synchronized(guard) {
            pending?.completion?.complete(HumanVerificationListener.HumanVerificationResult.Failure)
            pending = null
            solved = null
            mutableUiState.value = GateCHumanVerificationUiState()
        }
    }

    suspend fun clear() = cancel()

    @SuppressLint("SetJavaScriptEnabled")
    fun load(webView: WebView, generation: Long, darkTheme: Boolean): Boolean {
        if (releasedViews.containsKey(webView)) return false
        val current = synchronized(guard) { pending?.takeIf { it.generation == generation } } ?: return false
        if (loadedGenerations[webView] == generation) return true
        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            safeBrowsingEnabled = true
        }
        WebView.setWebContentsDebuggingEnabled(false)
        webView.isLongClickable = false
        webView.setOnLongClickListener { true }
        webView.webChromeClient = null
        webView.webViewClient = RestrictedVerificationWebViewClient(generation)
        bridges.remove(webView)?.close()
        val bridge = OriginBoundVerificationBridge.install(webView) { response ->
            acceptResponse(generation, response)
        } ?: return false
        bridges[webView] = bridge
        webView.loadUrl(buildHumanVerificationUrl(current.challenge, darkTheme))
        loadedGenerations[webView] = generation
        return true
    }

    private val releasedViews = java.util.WeakHashMap<WebView, Boolean>()

    fun release(webView: WebView) = release(webView, rendererGone = false)

    private fun release(webView: WebView, rendererGone: Boolean) {
        if (releasedViews.put(webView, true) != null) return
        loadedGenerations.remove(webView)
        val bridge = bridges.remove(webView)
        try {
            if (rendererGone) bridge?.abandon() else {
                bridge?.close()
                webView.stopLoading()
                webView.webChromeClient = null
                webView.webViewClient = WebViewClient()
                webView.clearFormData()
                webView.clearHistory()
                webView.clearCache(true)
            }
            WebStorage.getInstance().deleteOrigin(HUMAN_VERIFICATION_ORIGIN)
            CookieManager.getInstance().apply { removeAllCookies { flush() } }
        } catch (_: RuntimeException) {
            // Cleanup failure must not turn renderer recovery into a host crash.
            bridge?.abandon()
        } finally {
            try { (webView.parent as? android.view.ViewGroup)?.removeView(webView) }
            finally { webView.destroy() }
        }
    }

    internal fun acceptSolution(generation: Long, tokenType: String, tokenCode: String): Boolean {
        if (tokenType !in SUPPORTED_TOKEN_TYPES || tokenCode.isBlank() || tokenCode.length > MAX_TOKEN_LENGTH) {
            return false
        }
        return synchronized(guard) {
            val current = pending?.takeIf { it.generation == generation } ?: return@synchronized false
            if (current.completion.isCompleted) return@synchronized false
            if (tokenType !in current.challenge.verificationMethods) return@synchronized false
            solved = HumanVerificationDetails(
                clientId = current.challenge.clientId,
                verificationMethods = current.challenge.verificationMethods,
                verificationToken = current.challenge.verificationToken,
                state = HumanVerificationState.HumanVerificationSuccess,
                tokenType = tokenType,
                tokenCode = tokenCode,
            )
            current.completion.complete(HumanVerificationListener.HumanVerificationResult.Success)
        }
    }

    private fun requireChallenge(
        clientId: ClientId,
        methods: HumanVerificationAvailableMethods,
    ): Challenge {
        val token = methods.verificationToken
        val verificationMethods = methods.verificationMethods.distinct()
        // Proton Core HV3 deliberately treats the server method list as opaque and delegates its
        // validation to verify.proton.me. Keep only URL-size circuit breakers here; rejecting an
        // empty, future, or otherwise unfamiliar method locally can deadlock a legitimate login.
        if (token.length > MAX_TOKEN_LENGTH ||
            verificationMethods.joinToString(",").length > MAX_METHOD_PAYLOAD_LENGTH
        ) throw GateCHumanVerificationRequired()
        return Challenge(clientId, verificationMethods, token)
    }

    private fun acceptResponse(generation: Long, response: String) {
        try {
            val root = JSONObject(response)
            if (root.optString("type") != SUCCESS_MESSAGE_TYPE) return
            val payload = root.optJSONObject("payload") ?: return
            acceptSolution(
                generation = generation,
                tokenType = payload.optString("type"),
                tokenCode = payload.optString("token"),
            )
        } catch (_: JSONException) {
            // Malformed page messages are ignored without logging their contents.
        }
    }

    private inner class RestrictedVerificationWebViewClient(
        private val generation: Long,
    ) : WebViewClient() {
        override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
            failGeneration(generation)
            release(view, rendererGone = true)
            return true
        }
        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest): Boolean {
            if (!request.isForMainFrame) return false
            return !request.url.isVerificationOrigin()
        }

        override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler, error: SslError?) {
            handler.cancel()
            failGeneration(generation)
        }

        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            val parsed = runCatching { Uri.parse(url) }.getOrNull()
            if (parsed == null || !parsed.isVerificationOrigin()) {
                view?.stopLoading()
                failGeneration(generation)
            }
        }
    }

    internal fun failGeneration(generation: Long) {
        synchronized(guard) {
            val current = pending?.takeIf { it.generation == generation } ?: return
            if (!current.completion.complete(HumanVerificationListener.HumanVerificationResult.Failure)) return
            pending = null
            solved = null
            mutableUiState.value = GateCHumanVerificationUiState()
        }
    }

    private data class Challenge(
        val clientId: ClientId,
        val verificationMethods: List<String>,
        val verificationToken: String,
    )

    private data class Pending(
        val generation: Long,
        val challenge: Challenge,
        val completion: CompletableDeferred<HumanVerificationListener.HumanVerificationResult> = CompletableDeferred(),
    ) {
        fun matches(other: Challenge): Boolean =
            challenge.clientId.id == other.clientId.id &&
                challenge.verificationMethods == other.verificationMethods &&
                challenge.verificationToken == other.verificationToken
    }

    private companion object {
        const val HUMAN_VERIFICATION_ORIGIN = OriginBoundVerificationBridge.ORIGIN
        const val HUMAN_VERIFICATION_URL = "$HUMAN_VERIFICATION_ORIGIN/"
        const val SUCCESS_MESSAGE_TYPE = "HUMAN_VERIFICATION_SUCCESS"
        const val MAX_METHOD_PAYLOAD_LENGTH = 16_384
        const val MAX_TOKEN_LENGTH = 16_384
        val SUPPORTED_TOKEN_TYPES = setOf("captcha", "email", "sms", "payment")

        fun buildHumanVerificationUrl(challenge: Challenge, darkTheme: Boolean): String {
            val parameters = listOf(
                "embed" to "true",
                "token" to challenge.verificationToken,
                "methods" to challenge.verificationMethods.joinToString(","),
                "theme" to if (darkTheme) "1" else "2",
            ).joinToString("&") { (key, value) ->
                "$key=${URLEncoder.encode(value, Charsets.UTF_8.name())}"
            }
            return "$HUMAN_VERIFICATION_URL?$parameters"
        }

    }
}
