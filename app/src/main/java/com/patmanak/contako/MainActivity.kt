package com.patmanak.contako

import android.Manifest
import android.accounts.AccountManager
import android.graphics.Color
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.patmanak.contako.data.proton.ProtonAuthenticationFlowAdapter
import com.patmanak.contako.ui.ContakoApp
import com.patmanak.contako.ui.ContactsViewModel
import com.patmanak.contako.ui.ContactsPermissionBoundary
import com.patmanak.contako.ui.ContactsPermissionAction
import kotlinx.coroutines.flow.MutableStateFlow
import com.patmanak.contako.ui.auth.AuthenticationDestination
import com.patmanak.contako.ui.auth.AuthenticationScreen
import com.patmanak.contako.ui.auth.AuthenticationViewModel
import com.patmanak.contako.ui.auth.HumanVerificationScreen
import com.patmanak.contako.ui.locale.AndroidLanguagePreferenceStore
import com.patmanak.contako.ui.theme.ContakoTheme
import com.patmanak.contako.ui.theme.resolveDarkTheme
import com.patmanak.contako.android.account.SignOutResult
import com.patmanak.contako.android.account.AccountProvisioningResult
import com.patmanak.contako.android.account.ContakoAndroidAccountContract
import com.patmanak.contako.data.sync.AuthenticatedSyncLifecycle

class MainActivity : ComponentActivity() {
    /** Survives recomposition so the launch prompt is shown at most once per activity. */
    private var contactsPermissionRequested = false
    private val contactsPermissionGranted = MutableStateFlow(false)
    private val contactsPermissionAction = MutableStateFlow(ContactsPermissionAction.REQUEST)
    private lateinit var authenticatedSyncLifecycle: AuthenticatedSyncLifecycle

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AndroidLanguagePreferenceStore.localizedContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        contactsPermissionRequested = savedInstanceState?.getBoolean(CONTACTS_PERMISSION_REQUESTED_KEY) == true
        refreshContactsPermissionState()
        val contakoApplication = application as ContakoApplication
        authenticatedSyncLifecycle = AuthenticatedSyncLifecycle(
            onAccountReady = contakoApplication.syncSchedulingPolicy::onAccountReady,
            onAuthenticatedStartup = contakoApplication.syncSchedulingPolicy::onAppStartup,
        )
        enableEdgeToEdge()
        setContent {
            val application = application as ContakoApplication
            val themeMode by application.themePreferences.mode.collectAsState()
            val language by application.languagePreferences.language.collectAsState()
            val systemDarkTheme = isSystemInDarkTheme()
            val darkTheme = resolveDarkTheme(themeMode, systemDarkTheme)
            SideEffect {
                val barStyle = if (darkTheme) {
                    SystemBarStyle.dark(Color.TRANSPARENT)
                } else {
                    SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                }
                enableEdgeToEdge(statusBarStyle = barStyle, navigationBarStyle = barStyle)
            }
            ContakoTheme(themeMode = themeMode, systemDarkTheme = systemDarkTheme) {
                val permissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions(),
                ) {
                    contactsPermissionRequested = true
                    refreshContactsPermissionState()
                }
                val permissionBoundary = remember(permissionLauncher) {
                    object : ContactsPermissionBoundary {
                        override val granted = contactsPermissionGranted
                        override val action = contactsPermissionAction
                        override fun requestPermission() {
                            if (contactsPermissionAction.value == ContactsPermissionAction.OPEN_SETTINGS) {
                                startActivity(Intent(
                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.fromParts("package", packageName, null),
                                ))
                            } else {
                                contactsPermissionRequested = true
                                permissionLauncher.launch(CONTACTS_PERMISSIONS)
                            }
                        }
                    }
                }
                // Ask at application entry, including before sign-in. A denial never loops.
                LaunchedEffect(Unit) {
                    if (!hasContactsPermission() && !contactsPermissionRequested) {
                        permissionBoundary.requestPermission()
                    }
                }
                val hasContactAccess by contactsPermissionGranted.collectAsState()
                val contactsViewModel: ContactsViewModel = viewModel(
                    factory = ContactsViewModel.factory(
                        application.contactRepository,
                        application.syncRecoveryDataSource,
                        permissionBoundary,
                        application.syncSchedulingPolicy::onMutationCommitted,
                        // The screen and the synchronization engine MUST observe the same account
                        // scope. Previously the UI was pinned to LOCAL_ACCOUNT_ID while the engine
                        // ran on ProtonGateCRuntime's scope, so saved contacts and pending
                        // mutations landed in a partition no sync pass ever drained.
                        accountId = application.protonGateCRuntime.accountScope.value,
                    ),
                )
                val authenticationPort = remember(application) {
                    val runtime = application.protonGateCRuntime
                    ProtonAuthenticationFlowAdapter(
                        account = runtime.accountScope,
                        authentication = runtime.authentication,
                        session = runtime.session,
                    )
                }
                val authenticationViewModel: AuthenticationViewModel = viewModel(
                    factory = AuthenticationViewModel.factory(authenticationPort),
                )
                val authenticationState by authenticationViewModel.state.collectAsState()
                val humanVerificationState by application.humanVerification.uiState.collectAsState()
                var signedInAddress by remember { mutableStateOf<String?>(null) }
                // The Android account only exists once a Proton account is connected, so it is
                // provisioned here rather than at startup. Idempotent, and non-fatal on failure:
                // D-062 keeps local and Proton work usable when Android interoperability degrades.
                LaunchedEffect(authenticationState.destination, hasContactAccess) {
                    if (authenticationState.destination == AuthenticationDestination.READY) {
                        val address = application.protonGateCRuntime.currentAccountAddress()
                            ?: AccountManager.get(this@MainActivity)
                                .getAccountsByType(ContakoAndroidAccountContract.ACCOUNT_TYPE)
                                .singleOrNull()
                                ?.name
                        signedInAddress = address
                        if (address != null &&
                            application.provisionAndroidAccount(address) == AccountProvisioningResult.READY
                        ) {
                            authenticatedSyncLifecycle.onAccountProvisioned()
                        } else {
                            authenticatedSyncLifecycle.onAuthenticationUnavailable()
                        }
                    } else {
                        signedInAddress = null
                        authenticatedSyncLifecycle.onAuthenticationUnavailable()
                    }
                }
                if (humanVerificationState.isRequired) {
                    HumanVerificationScreen(
                        coordinator = application.humanVerification,
                        generation = humanVerificationState.generation,
                    )
                } else if (authenticationState.destination == AuthenticationDestination.READY) {
                    ContakoApp(
                        contactsViewModel,
                        accountAddress = signedInAddress,
                        themeMode = themeMode,
                        onThemeModeChange = application.themePreferences::setMode,
                        language = language,
                        onLanguageChange = {
                            application.languagePreferences.setLanguage(it)
                            if (Build.VERSION.SDK_INT < 33) recreate()
                        },
                        onSignOut = { choice ->
                            application.accountSignOutCoordinator.signOut(choice).also {
                                if (it == SignOutResult.SignedOut) authenticationViewModel.signOut()
                            }
                        },
                    )
                } else {
                    AuthenticationScreen(
                        state = authenticationState,
                        onSignIn = authenticationViewModel::submitSignIn,
                        onCode = authenticationViewModel::submitCode,
                        onPassword = authenticationViewModel::submitPassword,
                        onCancel = authenticationViewModel::cancelAuthentication,
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        (application as ContakoApplication).syncSchedulingPolicy.onForegroundChanged(true)
        refreshContactsPermissionState()
        authenticatedSyncLifecycle.onActivityStarted()
    }

    override fun onStop() {
        (application as ContakoApplication).syncSchedulingPolicy.onForegroundChanged(false)
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(CONTACTS_PERMISSION_REQUESTED_KEY, contactsPermissionRequested)
        super.onSaveInstanceState(outState)
    }

    private fun hasContactsPermission(): Boolean = CONTACTS_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun refreshContactsPermissionState() {
        val granted = hasContactsPermission()
        contactsPermissionGranted.value = granted
        contactsPermissionAction.value = if (!granted && contactsPermissionRequested &&
            CONTACTS_PERMISSIONS.filter { permission ->
                ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED
            }.any { permission -> !shouldShowRequestPermissionRationale(permission) }
        ) {
            ContactsPermissionAction.OPEN_SETTINGS
        } else {
            ContactsPermissionAction.REQUEST
        }
    }

    private companion object {
        val CONTACTS_PERMISSIONS = arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)
        const val CONTACTS_PERMISSION_REQUESTED_KEY = "contacts-permission-requested"
    }
}
