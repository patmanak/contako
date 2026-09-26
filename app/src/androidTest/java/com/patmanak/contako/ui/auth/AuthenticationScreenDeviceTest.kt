package com.patmanak.contako.ui.auth

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import com.patmanak.contako.R
import com.patmanak.contako.domain.auth.PasswordPurpose
import com.patmanak.contako.ui.theme.ContakoTheme
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AuthenticationScreenDeviceTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val capturedSecrets = mutableListOf<CharArray>()

    @Test
    fun signInDisplaysTheContakoOctopusBrand() {
        composeRule.setContent {
            ContakoTheme {
                AuthenticationScreen(
                    AuthenticationUiState(AuthenticationDestination.SIGN_IN),
                    ::capturePair,
                    ::captureOne,
                    ::captureOne,
                ) {}
            }
        }

        val brand = composeRule.onNodeWithTag(AuthenticationTestTags.BRAND).assertIsDisplayed()
            .fetchSemanticsNode()
        val expectedHeight = 152f * composeRule.activity.resources.displayMetrics.density
        assertEquals(expectedHeight, brand.boundsInRoot.height, 1f)
        composeRule.onAllNodesWithText(composeRule.activity.getString(R.string.auth_welcome_title)).assertCountEquals(0)
        composeRule.onAllNodesWithText(composeRule.activity.getString(R.string.auth_welcome_subtitle)).assertCountEquals(0)
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.auth_unofficial)).assertIsDisplayed()
    }

    @After
    fun clearCapturedSyntheticSecrets() {
        capturedSecrets.forEach { it.fill('\u0000') }
        capturedSecrets.clear()
    }

    @Test
    fun captureProtectionTracksOnlySecretBearingDestinations() {
        var state by mutableStateOf(AuthenticationUiState(AuthenticationDestination.SIGN_IN))
        composeRule.setContent {
            ContakoTheme {
                AuthenticationScreen(state, ::capturePair, ::captureOne, ::captureOne) {}
            }
        }

        composeRule.waitForIdle()
        assertTrue(composeRule.activity.window.hasSecureFlag())

        composeRule.runOnUiThread {
            state = AuthenticationUiState(AuthenticationDestination.CODE)
        }
        composeRule.waitForIdle()
        assertTrue(composeRule.activity.window.hasSecureFlag())

        composeRule.runOnUiThread {
            state = AuthenticationUiState(
                destination = AuthenticationDestination.PASSWORD,
                passwordPurpose = PasswordPurpose.MAILBOX,
            )
        }
        composeRule.waitForIdle()
        assertTrue(composeRule.activity.window.hasSecureFlag())

        composeRule.runOnUiThread {
            state = AuthenticationUiState(AuthenticationDestination.SECURITY_KEY_UNSUPPORTED)
        }
        composeRule.waitForIdle()
        assertFalse(composeRule.activity.window.hasSecureFlag())

        composeRule.runOnUiThread {
            state = AuthenticationUiState(AuthenticationDestination.READY)
        }
        composeRule.waitForIdle()
        assertFalse(composeRule.activity.window.hasSecureFlag())
    }

    @Test
    fun signInSubmissionDropsUiValuesAndSubmittingStateDisablesAction() {
        var state by mutableStateOf(AuthenticationUiState(AuthenticationDestination.SIGN_IN))
        composeRule.setContent {
            ContakoTheme {
                AuthenticationScreen(state, ::capturePair, ::captureOne, ::captureOne) {}
            }
        }

        composeRule.onNodeWithTag(AuthenticationTestTags.USERNAME).performTextInput("synthetic-user")
        composeRule.onNodeWithTag(AuthenticationTestTags.SIGN_IN_PASSWORD).performTextInput("synthetic-pass")
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(AuthenticationTestTags.SIGN_IN_SUBMIT).assertIsEnabled()
        composeRule.onNodeWithTag(AuthenticationTestTags.SIGN_IN_PASSWORD).performImeAction()
        composeRule.waitForIdle()

        assertEquals(2, capturedSecrets.size)
        assertEquals("synthetic-user", String(capturedSecrets[0]))
        assertEquals("synthetic-pass", String(capturedSecrets[1]))
        composeRule.onNodeWithTag(AuthenticationTestTags.USERNAME)
            .assertTextEquals("", composeRule.activity.getString(R.string.auth_username))
        composeRule.onNodeWithTag(AuthenticationTestTags.SIGN_IN_PASSWORD)
            .assertTextEquals("", composeRule.activity.getString(R.string.auth_password))

        composeRule.runOnUiThread {
            state = state.copy(isSubmitting = true)
        }
        composeRule.waitForIdle()
        val callCount = capturedSecrets.size
        composeRule.onNodeWithTag(AuthenticationTestTags.SIGN_IN_SUBMIT)
            .assertIsNotEnabled()
        assertEquals(callCount, capturedSecrets.size)
    }

    @Test
    fun submittedSecretsDisappearFromMergedAndUnmergedAccessibilitySemantics() {
        var state by mutableStateOf(AuthenticationUiState(AuthenticationDestination.SIGN_IN))
        composeRule.setContent {
            ContakoTheme {
                AuthenticationScreen(state, ::capturePair, ::captureOne, ::captureOne) {}
            }
        }

        val username = "synthetic-accessibility-user"
        val password = "synthetic-accessibility-password"
        composeRule.onNodeWithTag(AuthenticationTestTags.USERNAME).performTextInput(username)
        composeRule.onNodeWithTag(AuthenticationTestTags.SIGN_IN_PASSWORD).performTextInput(password)
        composeRule.onNodeWithTag(AuthenticationTestTags.SIGN_IN_SUBMIT).performClick()
        composeRule.runOnUiThread {
            state = AuthenticationUiState(AuthenticationDestination.CODE)
        }
        composeRule.waitForIdle()

        assertAbsentFromSemantics(username)
        assertAbsentFromSemantics(password)

        val code = "synthetic-accessibility-code"
        composeRule.onNodeWithTag(AuthenticationTestTags.SECOND_FACTOR_CODE).performTextInput(code)
        composeRule.onNodeWithTag(AuthenticationTestTags.SECOND_FACTOR_SUBMIT).performClick()
        composeRule.runOnUiThread {
            state = AuthenticationUiState(AuthenticationDestination.SIGN_IN)
        }
        composeRule.waitForIdle()

        assertAbsentFromSemantics(code)
    }

    @Test
    fun secondAccountLimitationIsVisibleAndActionableOnTheSignInScreen() {
        composeRule.setContent {
            ContakoTheme {
                AuthenticationScreen(
                    state = AuthenticationUiState(
                        destination = AuthenticationDestination.SIGN_IN,
                        message = AuthenticationMessage.ACCOUNT_ALREADY_CONNECTED,
                    ),
                    onSignIn = ::capturePair,
                    onCode = ::captureOne,
                    onPassword = ::captureOne,
                    onCancel = {},
                )
            }
        }

        composeRule.onNodeWithText(
            composeRule.activity.getString(R.string.auth_account_already_connected),
        ).assertExists()
        composeRule.onNodeWithTag(AuthenticationTestTags.SIGN_IN_SUBMIT).assertIsEnabled()
        assertTrue(composeRule.activity.window.hasSecureFlag())
    }

    @Test
    fun codeValueIsNotRestoredFromSavedComposeState() {
        val restoration = StateRestorationTester(composeRule)
        restoration.setContent {
            ContakoTheme {
                AuthenticationScreen(
                    state = AuthenticationUiState(AuthenticationDestination.CODE),
                    onSignIn = ::capturePair,
                    onCode = ::captureOne,
                    onPassword = ::captureOne,
                    onCancel = {},
                )
            }
        }

        composeRule.onNodeWithTag(AuthenticationTestTags.SECOND_FACTOR_CODE)
            .performTextInput("synthetic-code")
        restoration.emulateSavedInstanceStateRestore()
        composeRule.onNodeWithTag(AuthenticationTestTags.SECOND_FACTOR_CODE)
            .assertTextEquals("", composeRule.activity.getString(R.string.auth_code_label))
        assertTrue(composeRule.activity.window.hasSecureFlag())
    }

    @Test
    fun signInAndMailboxPasswordValuesAreNotRestoredFromSavedComposeState() {
        val restoration = StateRestorationTester(composeRule)
        var state by mutableStateOf(AuthenticationUiState(AuthenticationDestination.SIGN_IN))
        restoration.setContent {
            ContakoTheme {
                AuthenticationScreen(state, ::capturePair, ::captureOne, ::captureOne) {}
            }
        }

        composeRule.onNodeWithTag(AuthenticationTestTags.USERNAME).performTextInput("restore-user-canary")
        composeRule.onNodeWithTag(AuthenticationTestTags.SIGN_IN_PASSWORD)
            .performTextInput("restore-password-canary")
        restoration.emulateSavedInstanceStateRestore()
        composeRule.onNodeWithTag(AuthenticationTestTags.USERNAME)
            .assertTextEquals("", composeRule.activity.getString(R.string.auth_username))
        composeRule.onNodeWithTag(AuthenticationTestTags.SIGN_IN_PASSWORD)
            .assertTextEquals("", composeRule.activity.getString(R.string.auth_password))

        composeRule.runOnUiThread {
            state = AuthenticationUiState(
                destination = AuthenticationDestination.PASSWORD,
                passwordPurpose = PasswordPurpose.MAILBOX,
            )
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(AuthenticationTestTags.MAILBOX_PASSWORD)
            .performTextInput("restore-mailbox-canary")
        restoration.emulateSavedInstanceStateRestore()
        composeRule.onNodeWithTag(AuthenticationTestTags.MAILBOX_PASSWORD)
            .assertTextEquals("", composeRule.activity.getString(R.string.auth_password))
        assertTrue(composeRule.activity.window.hasSecureFlag())
    }

    @Test
    fun backFromCodeRequestsCleanupAndFidoLimitationHasNoSecretField() {
        val cancels = AtomicInteger(0)
        var state by mutableStateOf(AuthenticationUiState(AuthenticationDestination.CODE))
        composeRule.setContent {
            ContakoTheme {
                AuthenticationScreen(
                    state = state,
                    onSignIn = ::capturePair,
                    onCode = ::captureOne,
                    onPassword = ::captureOne,
                    onCancel = {
                        cancels.incrementAndGet()
                        state = AuthenticationUiState(AuthenticationDestination.SIGN_IN)
                    },
                )
            }
        }

        composeRule.runOnUiThread {
            composeRule.activity.onBackPressedDispatcher.onBackPressed()
        }
        composeRule.waitForIdle()
        assertEquals(1, cancels.get())
        composeRule.onNodeWithTag(AuthenticationTestTags.USERNAME).assertIsEnabled()

        composeRule.runOnUiThread {
            state = AuthenticationUiState(AuthenticationDestination.SECURITY_KEY_UNSUPPORTED)
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.auth_fido_unsupported_title))
            .assertExists()
        composeRule.onNodeWithTag(AuthenticationTestTags.SECOND_FACTOR_CODE).assertDoesNotExist()
        composeRule.onNodeWithTag(AuthenticationTestTags.MAILBOX_PASSWORD).assertDoesNotExist()
        assertFalse(composeRule.activity.window.hasSecureFlag())
    }

    @Test
    fun humanVerificationLimitationIsActionableHasNoSecretAndReturnsThroughCleanup() {
        val cancels = AtomicInteger(0)
        var state by mutableStateOf(
            AuthenticationUiState(AuthenticationDestination.HUMAN_VERIFICATION_UNAVAILABLE),
        )
        composeRule.setContent {
            ContakoTheme {
                AuthenticationScreen(
                    state = state,
                    onSignIn = ::capturePair,
                    onCode = ::captureOne,
                    onPassword = ::captureOne,
                    onCancel = {
                        cancels.incrementAndGet()
                        state = AuthenticationUiState(AuthenticationDestination.SIGN_IN)
                    },
                )
            }
        }

        composeRule.onNodeWithText(composeRule.activity.getString(R.string.auth_human_title))
            .assertExists()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.auth_human_unavailable))
            .assertExists()
        composeRule.onNodeWithTag(AuthenticationTestTags.SECOND_FACTOR_CODE).assertDoesNotExist()
        composeRule.onNodeWithTag(AuthenticationTestTags.MAILBOX_PASSWORD).assertDoesNotExist()
        assertFalse(composeRule.activity.window.hasSecureFlag())

        composeRule.runOnUiThread {
            composeRule.activity.onBackPressedDispatcher.onBackPressed()
        }
        composeRule.waitForIdle()
        assertEquals(1, cancels.get())
        composeRule.onNodeWithTag(AuthenticationTestTags.USERNAME).assertIsEnabled()
    }

    private fun capturePair(first: CharArray, second: CharArray) {
        capturedSecrets += first
        capturedSecrets += second
    }

    private fun captureOne(value: CharArray) {
        capturedSecrets += value
    }

    private fun assertAbsentFromSemantics(value: String) {
        composeRule.onAllNodesWithText(value, substring = true).assertCountEquals(0)
        composeRule.onAllNodesWithText(value, substring = true, useUnmergedTree = true)
            .assertCountEquals(0)
    }
}

private fun android.view.Window.hasSecureFlag(): Boolean =
    attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
