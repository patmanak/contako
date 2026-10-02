package com.patmanak.contako.data.proton

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.WebView
import androidx.webkit.ScriptHandler
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/** UI-thread confined. Only the hosted verifier's main frame may submit a native message. */
internal class OriginBoundVerificationBridge private constructor(
    private val webView: WebView,
) {
    private var active = true
    private var script: ScriptHandler? = null

    /** Invalidate queued messages without invoking a renderer that has already terminated. */
    fun abandon() {
        active = false
        script = null
    }

    // The private constructor is reached only after install checks both provider features.
    @SuppressLint("RequiresFeature")
    fun close() {
        if (!active) return
        // Removing an injection affects future documents; already queued callbacks need this guard.
        active = false
        try {
            script?.remove()
        } finally {
            WebViewCompat.removeWebMessageListener(webView, MESSAGE_OBJECT)
        }
    }

    companion object {
        const val ORIGIN = "https://verify.proton.me"
        private const val MESSAGE_OBJECT = "ContakoVerificationMessage"
        private const val MAX_RESPONSE_LENGTH = 32_768
        private val origins = setOf(ORIGIN)

        fun install(webView: WebView, onMessage: (String) -> Unit): OriginBoundVerificationBridge? {
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return null
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return null
            val bridge = OriginBoundVerificationBridge(webView)
            WebViewCompat.addWebMessageListener(webView, MESSAGE_OBJECT, origins) {
                    _, message, sourceOrigin, isMainFrame, _ ->
                if (bridge.active && isMainFrame && sourceOrigin.isVerificationOrigin() &&
                    message.type == WebMessageCompat.TYPE_STRING
                ) {
                    val response = message.data
                    if (response != null && response.length <= MAX_RESPONSE_LENGTH) onMessage(response)
                }
            }
            try {
                // Proton detects AndroidInterface while its modules initialize. The compatibility
                // shim must exist before page JavaScript, never via a racing onPageFinished hook.
                bridge.script = WebViewCompat.addDocumentStartJavaScript(
                    webView,
                    """
                    (() => {
                      if (window !== window.top) return;
                      const channel = window.$MESSAGE_OBJECT;
                      Object.defineProperty(window, 'AndroidInterface', {
                        value: Object.freeze({ dispatch: function(response) {
                          if (typeof response === 'string' && response.length <= $MAX_RESPONSE_LENGTH) {
                            channel.postMessage(response);
                          }
                        }}), writable: false, configurable: false
                      });
                    })();
                    """.trimIndent(),
                    origins,
                )
            } catch (failure: RuntimeException) {
                bridge.close()
                throw failure
            }
            return bridge
        }

        internal fun Uri.isVerificationOrigin(): Boolean =
            scheme.equals("https", ignoreCase = true) &&
                host.equals("verify.proton.me", ignoreCase = true) &&
                userInfo == null && (port == -1 || port == 443)
    }
}
