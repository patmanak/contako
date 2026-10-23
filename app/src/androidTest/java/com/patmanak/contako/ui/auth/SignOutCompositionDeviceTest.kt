package com.patmanak.contako.ui.auth

import androidx.activity.ComponentActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import com.patmanak.contako.R
import com.patmanak.contako.android.account.SignOutResult
import com.patmanak.contako.data.local.ContakoDatabase
import com.patmanak.contako.data.local.RoomContactRepository
import com.patmanak.contako.data.sync.AuthenticatedSyncLifecycle
import com.patmanak.contako.domain.auth.AuthenticationFlowPort
import com.patmanak.contako.domain.auth.AuthenticationSecret
import com.patmanak.contako.domain.auth.AuthenticationStep
import com.patmanak.contako.ui.ContactsViewModel
import com.patmanak.contako.ui.ContakoApp
import com.patmanak.contako.ui.theme.ContakoTheme
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Real Compose disposal boundary; all account work is synthetic and no provider is accessed. */
class SignOutCompositionDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var database: ContakoDatabase
    private lateinit var model: AuthenticationViewModel
    private lateinit var contacts: ContactsViewModel
    private val port = ReadyPort()
    private var showApp by mutableStateOf(true)
    private var checkpointPresent = false

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.deleteDatabase(DATABASE)
        database = ContakoDatabase.create(context, DATABASE)
        compose.activityRule.scenario.onActivity {
            model = ViewModelProvider(it, AuthenticationViewModel.factory(port) { checkpointPresent })[AuthenticationViewModel::class.java]
            contacts = ViewModelProvider(it, ContactsViewModel.factory(RoomContactRepository(database)))[ContactsViewModel::class.java]
        }
    }

    @After fun tearDown() {
        compose.runOnIdle {
            showApp = false
            compose.activity.viewModelStore.clear()
        }
        compose.waitForIdle()
        database.close()
        ApplicationProvider.getApplicationContext<android.content.Context>().deleteDatabase(DATABASE)
    }

    @Test fun disposingAppAfterSessionDeletionDoesNotCancelCleanup() {
        val release = CompletableDeferred<SignOutResult>()
        var finalized = false
        show {
            port.ready = false
            model.onSessionChanged()
            release.await().also { finalized = true }
        }
        confirmSignOut()
        compose.runOnIdle {
            assertTrue(model.state.value.isSigningOut)
            assertEquals(AuthenticationDestination.READY, model.state.value.destination)
            showApp = false
        }
        compose.waitForIdle()
        release.complete(SignOutResult.SignedOut)
        compose.waitUntil { model.state.value.destination == AuthenticationDestination.SIGN_IN }
        assertTrue(finalized)
    }

    @Test fun cleanupFailureAfterDisposalIsDisplayedOnReattachmentAndCanBeRetried() {
        val release = CompletableDeferred<SignOutResult>()
        var attempts = 0
        show {
            attempts++
            checkpointPresent = true
            port.ready = false
            model.onSessionChanged()
            if (attempts == 1) release.await() else SignOutResult.SignedOut
        }
        confirmSignOut()
        compose.runOnIdle { showApp = false }
        compose.waitForIdle()
        release.complete(SignOutResult.CleanupFailed)
        compose.waitUntil { model.state.value.signOutResult == SignOutResult.CleanupFailed }
        compose.runOnIdle {
            model.onSessionChanged()
            showApp = true
        }
        compose.onNodeWithText(text(R.string.dialog_signout_cleanup_failed)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.account_sign_out)).performClick()
        compose.waitUntil { model.state.value.destination == AuthenticationDestination.SIGN_IN }
        assertEquals(2, attempts)
    }

    @Test fun preDestructiveRefusalKeepsTheAppAvailableAndErrorDismissible() {
        show { SignOutResult.CleanupFailed }
        confirmSignOut()
        compose.waitUntil { model.state.value.signOutResult == SignOutResult.CleanupFailed }
        compose.runOnIdle {
            assertEquals(AuthenticationDestination.READY, model.state.value.destination)
            assertTrue(!model.state.value.isSignOutRecoveryRequired)
        }
        compose.onNodeWithText(text(R.string.dialog_signout_cleanup_failed)).assertIsDisplayed()
        compose.runOnIdle { model.dismissSignOutResult() }
        compose.onNodeWithContentDescription(text(R.string.account_menu)).assertIsDisplayed().performClick()
        compose.onNodeWithText(text(R.string.account_sign_out)).assertIsDisplayed()
    }

    @Test fun cleanupCancelsProvisioningAndPermissionOrCompositionChangesCannotReopenScheduling() {
        var state by mutableStateOf(AuthenticationUiState(AuthenticationDestination.READY))
        var permission by mutableStateOf(false)
        val readyCalls = AtomicInteger()
        val cancelledEffects = AtomicInteger()
        val startupCalls = AtomicInteger()
        val lifecycle = AuthenticatedSyncLifecycle({}, { startupCalls.incrementAndGet() })
        val neverComplete = CompletableDeferred<Unit>()
        compose.setContent {
            if (showApp) AuthenticatedAccountEffect(state, permission,
                onAuthenticated = {
                    readyCalls.incrementAndGet()
                    lifecycle.onAuthenticationReady()
                    try { neverComplete.await() } finally { cancelledEffects.incrementAndGet() }
                }, onUnavailable = lifecycle::onAuthenticationUnavailable)
        }
        compose.waitUntil { readyCalls.get() == 1 }
        compose.runOnIdle { state = state.copy(isSigningOut = true) }
        compose.waitUntil { cancelledEffects.get() == 1 }
        compose.runOnIdle {
            permission = true
            lifecycle.onActivityStarted()
        }
        compose.waitForIdle()
        compose.runOnIdle { showApp = false }
        compose.waitForIdle()
        compose.runOnIdle { showApp = true; permission = false }
        compose.waitForIdle()
        compose.runOnIdle {
            state = state.copy(destination = AuthenticationDestination.SIGN_OUT_RECOVERY,
                isSigningOut = false, isSignOutRecoveryRequired = true)
        }
        compose.waitForIdle()
        compose.runOnIdle { lifecycle.onActivityStarted() }
        assertEquals(1, readyCalls.get())
        assertEquals(0, startupCalls.get())
    }

    private fun show(cleanup: suspend () -> SignOutResult) {
        compose.setContent {
            val state by model.state.collectAsState()
            if (showApp && state.destination == AuthenticationDestination.READY) ContakoTheme {
                ContakoApp(contacts,
                    signOutInProgress = state.isSigningOut,
                    signOutResult = state.signOutResult,
                    onDismissSignOutResult = model::dismissSignOutResult,
                    onSignOut = { choice -> model.signOut(choice) { cleanup() } },
                )
            } else if (showApp && state.destination == AuthenticationDestination.SIGN_OUT_RECOVERY) ContakoTheme {
                SignOutRecoveryScreen(state) { choice -> model.signOut(choice) { cleanup() } }
            }
        }
    }

    private fun confirmSignOut() {
        compose.onNodeWithContentDescription(text(R.string.account_menu)).performClick()
        compose.onNodeWithText(text(R.string.account_sign_out)).performClick()
        compose.onNodeWithText(text(R.string.account_sign_out)).performClick()
    }

    private fun text(id: Int) = compose.activity.getString(id)

    private class ReadyPort : AuthenticationFlowPort {
        var ready = true
        override suspend fun restore() = if (ready) AuthenticationStep.Ready else AuthenticationStep.SignIn
        override suspend fun signIn(username: AuthenticationSecret, password: AuthenticationSecret): AuthenticationStep = error("No login")
        override suspend fun submitCode(code: AuthenticationSecret): AuthenticationStep = error("No code")
        override suspend fun unlock(password: AuthenticationSecret): AuthenticationStep = error("No unlock")
        override suspend fun cancel() = error("Sign-out cleanup MUST NOT be started twice")
    }

    private companion object { const val DATABASE = "signout-composition-fixture.db" }
}
