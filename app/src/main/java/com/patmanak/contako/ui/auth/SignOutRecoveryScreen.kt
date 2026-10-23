package com.patmanak.contako.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.patmanak.contako.R
import com.patmanak.contako.android.account.SignOutChoice
import com.patmanak.contako.android.account.SignOutResult

/** Cleanup recovery is not an authenticated READY state and never schedules a synchronization. */
@Composable
internal fun SignOutRecoveryScreen(state: AuthenticationUiState, onRetry: (SignOutChoice) -> Unit) {
    var confirmDiscard by remember { mutableStateOf(false) }
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.dialog_signout_failed_title), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(if (state.signOutResult == SignOutResult.PendingChanges)
                R.string.dialog_signout_pending_unverified else R.string.dialog_signout_cleanup_failed))
            if (state.isSigningOut) CircularProgressIndicator()
            Button(enabled = !state.isSigningOut, onClick = { onRetry(SignOutChoice.CONFIRM) }) {
                Text(stringResource(R.string.account_sign_out))
            }
            if (state.signOutResult == SignOutResult.PendingChanges) TextButton(
                enabled = !state.isSigningOut, onClick = { confirmDiscard = true },
            ) { Text(stringResource(R.string.dialog_discard_signout)) }
        }
    }
    if (confirmDiscard) AlertDialog(
        onDismissRequest = { confirmDiscard = false },
        title = { Text(stringResource(R.string.dialog_discard_signout_title)) },
        text = { Text(stringResource(R.string.dialog_discard_signout_body)) },
        confirmButton = { TextButton(enabled = !state.isSigningOut, onClick = {
            confirmDiscard = false
            onRetry(SignOutChoice.DISCARD)
        }) { Text(stringResource(R.string.dialog_discard_signout_confirm)) } },
        dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text(stringResource(R.string.action_cancel)) } },
    )
}
