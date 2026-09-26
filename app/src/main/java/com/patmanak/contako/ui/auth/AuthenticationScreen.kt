package com.patmanak.contako.ui.auth

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.password
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.patmanak.contako.R
import com.patmanak.contako.ui.ContakoBrand
import com.patmanak.contako.domain.auth.PasswordPurpose

@Composable
internal fun AuthenticationScreen(
    state: AuthenticationUiState,
    onSignIn: (CharArray, CharArray) -> Unit,
    onCode: (CharArray) -> Unit,
    onPassword: (CharArray) -> Unit,
    onCancel: () -> Unit,
) {
    BackHandler(
        enabled = state.requiresAuthenticationBackCleanup(),
        onBack = onCancel,
    )
    AuthenticationWindowProtection(state.destination)
    Surface(Modifier.fillMaxSize()) {
        when (state.destination) {
            AuthenticationDestination.RESTORING -> CenteredProgress(R.string.auth_restoring)
            AuthenticationDestination.SIGN_IN -> SignInContent(state, onSignIn)
            AuthenticationDestination.CODE -> CodeContent(state, onCode, onCancel)
            AuthenticationDestination.PASSWORD -> PasswordContent(state, onPassword, onCancel)
            AuthenticationDestination.SECURITY_KEY_UNSUPPORTED -> LimitationContent(
                title = stringResource(R.string.auth_fido_unsupported_title),
                body = stringResource(R.string.auth_fido_unsupported_body),
                onBack = onCancel,
            )
            AuthenticationDestination.HUMAN_VERIFICATION_UNAVAILABLE -> LimitationContent(
                title = stringResource(R.string.auth_human_title),
                body = stringResource(R.string.auth_human_unavailable),
                onBack = onCancel,
            )
            AuthenticationDestination.READY -> Unit
        }
    }
}

@Composable
private fun SignInContent(
    state: AuthenticationUiState,
    onSignIn: (CharArray, CharArray) -> Unit,
) {
    var username by remember { mutableStateOf("") }
    var passwordValue by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current

    DisposableEffect(Unit) {
        onDispose {
            username = ""
            passwordValue = ""
            passwordVisible = false
        }
    }

    fun submit() {
        val usernameSecret = username.toCharArray()
        val passwordSecret = passwordValue.toCharArray()
        username = ""
        passwordValue = ""
        passwordVisible = false
        keyboard?.hide()
        onSignIn(usernameSecret, passwordSecret)
    }

    AuthenticationColumn {
        AuthenticationBrand()
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = username,
            onValueChange = { if (it.length <= MAX_SECRET_INPUT_LENGTH) username = it },
            enabled = !state.isSubmitting && !state.isRetryBlocked,
            label = { Text(stringResource(R.string.auth_username)) },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Email,
                imeAction = ImeAction.Next,
            ),
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag(AuthenticationTestTags.USERNAME),
        )
        SecretField(
            value = passwordValue,
            onValueChange = { passwordValue = it },
            label = stringResource(R.string.auth_password),
            visible = passwordVisible,
            onVisibilityChange = { passwordVisible = it },
            enabled = !state.isSubmitting && !state.isRetryBlocked,
            onSubmit = ::submit,
            testTag = AuthenticationTestTags.SIGN_IN_PASSWORD,
        )
        AuthenticationMessage(state.message)
        SubmitButton(
            label = stringResource(R.string.auth_sign_in),
            progressLabel = stringResource(R.string.auth_signing_in),
            submitting = state.isSubmitting,
            enabled = !state.isRetryBlocked,
            onClick = ::submit,
            testTag = AuthenticationTestTags.SIGN_IN_SUBMIT,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(R.string.auth_unofficial),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CodeContent(
    state: AuthenticationUiState,
    onCode: (CharArray) -> Unit,
    onCancel: () -> Unit,
) {
    var code by remember { mutableStateOf("") }
    val keyboard = LocalSoftwareKeyboardController.current
    DisposableEffect(Unit) { onDispose { code = "" } }

    fun submit() {
        val secret = code.toCharArray()
        code = ""
        keyboard?.hide()
        onCode(secret)
    }

    AuthenticationColumn {
        BackHeader(stringResource(R.string.auth_code_title), onCancel)
        Text(
            stringResource(R.string.auth_code_help),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SecretField(
            value = code,
            onValueChange = { if (it.length <= MAX_CODE_INPUT_LENGTH) code = it },
            label = stringResource(R.string.auth_code_label),
            visible = false,
            onVisibilityChange = null,
            enabled = !state.isSubmitting && !state.isRetryBlocked,
            keyboardType = KeyboardType.Password,
            onSubmit = ::submit,
            testTag = AuthenticationTestTags.SECOND_FACTOR_CODE,
        )
        AuthenticationMessage(state.message)
        SubmitButton(
            label = stringResource(R.string.auth_verify),
            progressLabel = stringResource(R.string.auth_verifying),
            submitting = state.isSubmitting,
            enabled = !state.isRetryBlocked,
            onClick = ::submit,
            testTag = AuthenticationTestTags.SECOND_FACTOR_SUBMIT,
        )
        Text(
            stringResource(R.string.auth_fido_compact_limitation),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PasswordContent(
    state: AuthenticationUiState,
    onPassword: (CharArray) -> Unit,
    onCancel: () -> Unit,
) {
    var passwordValue by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    DisposableEffect(Unit) {
        onDispose {
            passwordValue = ""
            passwordVisible = false
        }
    }

    fun submit() {
        val secret = passwordValue.toCharArray()
        passwordValue = ""
        passwordVisible = false
        keyboard?.hide()
        onPassword(secret)
    }

    AuthenticationColumn {
        BackHeader(
            title = stringResource(
                if (state.passwordPurpose == PasswordPurpose.MAILBOX) {
                    R.string.auth_mailbox_password_title
                } else {
                    R.string.auth_key_unlock_title
                },
            ),
            onBack = onCancel,
        )
        Text(
            stringResource(
                if (state.passwordPurpose == PasswordPurpose.MAILBOX) {
                    R.string.auth_mailbox_password_help
                } else {
                    R.string.auth_key_unlock_help
                },
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SecretField(
            value = passwordValue,
            onValueChange = { passwordValue = it },
            label = stringResource(R.string.auth_password),
            visible = passwordVisible,
            onVisibilityChange = { passwordVisible = it },
            enabled = !state.isSubmitting && !state.isRetryBlocked,
            onSubmit = ::submit,
            testTag = AuthenticationTestTags.MAILBOX_PASSWORD,
        )
        AuthenticationMessage(state.message)
        SubmitButton(
            label = stringResource(R.string.auth_continue),
            progressLabel = stringResource(R.string.auth_unlocking),
            submitting = state.isSubmitting,
            enabled = !state.isRetryBlocked,
            onClick = ::submit,
            testTag = AuthenticationTestTags.PASSWORD_SUBMIT,
        )
    }
}

@Composable
private fun SecretField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    visible: Boolean,
    onVisibilityChange: ((Boolean) -> Unit)?,
    enabled: Boolean,
    keyboardType: KeyboardType = KeyboardType.Password,
    onSubmit: () -> Unit,
    testTag: String,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { candidate ->
            if (candidate.length <= MAX_SECRET_INPUT_LENGTH) onValueChange(candidate)
        },
        enabled = enabled,
        label = { Text(label) },
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = onVisibilityChange?.let { toggle ->
            {
                TextButton(onClick = { toggle(!visible) }) {
                    Text(
                        stringResource(
                            if (visible) R.string.auth_hide_password else R.string.auth_show_password,
                        ),
                    )
                }
            }
        },
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { if (enabled) onSubmit() }),
        singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag(testTag).semantics { password() },
    )
}

@Composable
private fun AuthenticationColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().widthIn(max = 560.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            content = content,
        )
    }
}

@Composable
private fun BackHeader(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back))
        }
        Text(title, style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun SubmitButton(
    label: String,
    progressLabel: String,
    submitting: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    testTag: String,
) {
    Button(
        onClick = onClick,
        enabled = enabled && !submitting,
        modifier = Modifier.fillMaxWidth().height(48.dp).testTag(testTag),
    ) {
        if (submitting) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
            )
            Text(progressLabel, modifier = Modifier.padding(start = 12.dp))
        } else {
            Text(label)
        }
    }
}

@Composable
private fun AuthenticationBrand() {
    ContakoBrand(
        modifier = Modifier.fillMaxWidth().height(152.dp).testTag(AuthenticationTestTags.BRAND),
    )
}

@Composable
private fun AuthenticationMessage(message: AuthenticationMessage?) {
    if (message == null) return
    val resource = when (message) {
        AuthenticationMessage.INPUT_REQUIRED -> R.string.auth_input_required
        AuthenticationMessage.REJECTED -> R.string.auth_invalid_retry
        AuthenticationMessage.ACCOUNT_ALREADY_CONNECTED -> R.string.auth_account_already_connected
        AuthenticationMessage.OFFLINE -> R.string.auth_offline
        AuthenticationMessage.TIMED_OUT -> R.string.auth_timed_out
        AuthenticationMessage.RATE_LIMITED -> R.string.auth_rate_limited
        AuthenticationMessage.CRYPTOGRAPHIC_FAILURE -> R.string.auth_cryptographic_failure
        AuthenticationMessage.CLIENT_UNSUPPORTED -> R.string.auth_client_unsupported
        AuthenticationMessage.TRY_AGAIN -> R.string.auth_try_again
    }
    Text(
        text = stringResource(resource),
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
    )
}

@Composable
private fun LimitationContent(title: String, body: String, onBack: () -> Unit) {
    AuthenticationColumn {
        Icon(Icons.Default.Lock, contentDescription = null)
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = onBack) { Text(stringResource(R.string.auth_back_to_sign_in)) }
    }
}

@Composable
private fun CenteredProgress(label: Int) {
    AuthenticationColumn {
        CircularProgressIndicator()
        Text(stringResource(label))
    }
}

private const val MAX_SECRET_INPUT_LENGTH = 16_384
private const val MAX_CODE_INPUT_LENGTH = 128

internal object AuthenticationTestTags {
    const val BRAND = "auth_brand"
    const val USERNAME = "auth_username"
    const val SIGN_IN_PASSWORD = "auth_sign_in_password"
    const val SIGN_IN_SUBMIT = "auth_sign_in_submit"
    const val SECOND_FACTOR_CODE = "auth_second_factor_code"
    const val SECOND_FACTOR_SUBMIT = "auth_second_factor_submit"
    const val MAILBOX_PASSWORD = "auth_mailbox_password"
    const val PASSWORD_SUBMIT = "auth_password_submit"
}

internal fun AuthenticationDestination.requiresAuthenticationBackCleanup(): Boolean = when (this) {
    AuthenticationDestination.CODE,
    AuthenticationDestination.PASSWORD,
    AuthenticationDestination.SECURITY_KEY_UNSUPPORTED,
    AuthenticationDestination.HUMAN_VERIFICATION_UNAVAILABLE,
    -> true
    AuthenticationDestination.RESTORING,
    AuthenticationDestination.SIGN_IN,
    AuthenticationDestination.READY,
    -> false
}

internal fun AuthenticationUiState.requiresAuthenticationBackCleanup(): Boolean =
    !isCleanupInProgress && (
        destination.requiresAuthenticationBackCleanup() ||
            (destination == AuthenticationDestination.SIGN_IN && isSubmitting)
        )
