package com.patmanak.contako.ui.auth

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView

internal object AuthenticationCapturePolicy {
    fun blocksCapture(destination: AuthenticationDestination): Boolean = when (destination) {
        AuthenticationDestination.SIGN_IN,
        AuthenticationDestination.CODE,
        AuthenticationDestination.PASSWORD,
        -> true
        AuthenticationDestination.RESTORING,
        AuthenticationDestination.SECURITY_KEY_UNSUPPORTED,
        AuthenticationDestination.HUMAN_VERIFICATION_UNAVAILABLE,
        AuthenticationDestination.READY,
        -> false
    }
}

/** Applies FLAG_SECURE only while a secret-bearing authentication surface is visible. */
@Composable
internal fun AuthenticationWindowProtection(destination: AuthenticationDestination) {
    val view = LocalView.current
    val blocksCapture = AuthenticationCapturePolicy.blocksCapture(destination)
    DisposableEffect(view, blocksCapture) {
        val window = view.context.findActivity()?.window
        if (blocksCapture) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        onDispose {
            if (blocksCapture) window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
