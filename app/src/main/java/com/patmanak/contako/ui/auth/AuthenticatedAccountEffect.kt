package com.patmanak.contako.ui.auth

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect

/** A permission change or recreated composition MUST NOT reopen scheduling during cleanup. */
@Composable
internal fun AuthenticatedAccountEffect(
    state: AuthenticationUiState,
    hasContactAccess: Boolean,
    onAuthenticated: suspend () -> Unit,
    onUnavailable: () -> Unit,
) {
    LaunchedEffect(state.destination, state.isSigningOut, state.isSignOutRecoveryRequired, hasContactAccess) {
        if (state.destination == AuthenticationDestination.READY && !state.isSigningOut && !state.isSignOutRecoveryRequired) {
            onAuthenticated()
        } else onUnavailable()
    }
}
