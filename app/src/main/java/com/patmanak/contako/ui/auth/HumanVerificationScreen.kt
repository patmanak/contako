package com.patmanak.contako.ui.auth

import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import com.patmanak.contako.R
import com.patmanak.contako.data.proton.GateCInteractiveHumanVerification

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HumanVerificationScreen(
    coordinator: GateCInteractiveHumanVerification,
    generation: Long,
) {
    val darkTheme = isSystemInDarkTheme()
    var unavailable by remember(generation) { mutableStateOf(false) }
    BackHandler(onBack = coordinator::cancel)
    AuthenticationWindowProtection(AuthenticationDestination.SIGN_IN)
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.auth_human_title)) },
                navigationIcon = {
                    IconButton(onClick = coordinator::cancel) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.auth_back_to_sign_in),
                        )
                    }
                },
            )
            Box(Modifier.fillMaxSize()) {
                if (unavailable) {
                    Text(
                        stringResource(R.string.auth_human_webview_update),
                        modifier = Modifier.padding(24.dp),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                } else {
                    AndroidView(
                        factory = { context -> WebView(context) },
                        update = { webView ->
                            unavailable = !coordinator.load(webView, generation, darkTheme)
                        },
                        onRelease = coordinator::release,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}
