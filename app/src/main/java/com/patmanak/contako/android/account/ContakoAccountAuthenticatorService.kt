package com.patmanak.contako.android.account

import android.accounts.AbstractAccountAuthenticator
import android.accounts.Account
import android.accounts.AccountAuthenticatorResponse
import android.accounts.AccountManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import kotlinx.coroutines.runBlocking

/** Framework authenticator only; Proton credentials and tokens never cross this boundary. */
internal class ContakoAccountAuthenticator(private val context: Context) :
    AbstractAccountAuthenticator(context) {

    override fun editProperties(
        response: AccountAuthenticatorResponse?,
        accountType: String?,
    ): Bundle = unsupported()

    override fun addAccount(
        response: AccountAuthenticatorResponse?,
        accountType: String?,
        authTokenType: String?,
        requiredFeatures: Array<out String>?,
        options: Bundle?,
    ): Bundle = unsupported()

    override fun confirmCredentials(
        response: AccountAuthenticatorResponse?,
        account: Account?,
        options: Bundle?,
    ): Bundle = unsupported()

    override fun getAuthToken(
        response: AccountAuthenticatorResponse?,
        account: Account?,
        authTokenType: String?,
        options: Bundle?,
    ): Bundle = unsupported()

    override fun getAuthTokenLabel(authTokenType: String?): String? = null

    override fun updateCredentials(
        response: AccountAuthenticatorResponse?,
        account: Account?,
        authTokenType: String?,
        options: Bundle?,
    ): Bundle = unsupported()

    override fun hasFeatures(
        response: AccountAuthenticatorResponse?,
        account: Account?,
        features: Array<out String>?,
    ): Bundle = Bundle().apply {
        putBoolean(AccountManager.KEY_BOOLEAN_RESULT, false)
    }

    override fun getAccountRemovalAllowed(
        response: AccountAuthenticatorResponse?,
        account: Account?,
    ): Bundle = Bundle().apply {
        val coordinator = (context.applicationContext as? ContakoAccountRemovalRuntime)
            ?.accountRemovalCoordinator
        val allowed = account != null && coordinator != null && runBlocking {
            coordinator.remove(account)
        }
        putBoolean(AccountManager.KEY_BOOLEAN_RESULT, allowed)
    }

    private fun unsupported(): Bundle = Bundle().apply {
        putInt(AccountManager.KEY_ERROR_CODE, AccountManager.ERROR_CODE_UNSUPPORTED_OPERATION)
    }
}

class ContakoAccountAuthenticatorService : Service() {
    private lateinit var authenticator: ContakoAccountAuthenticator

    override fun onCreate() {
        super.onCreate()
        authenticator = ContakoAccountAuthenticator(applicationContext)
    }

    override fun onBind(intent: Intent?): IBinder? =
        authenticator.iBinder.takeIf {
            intent?.action == ContakoAndroidAccountContract.AUTHENTICATOR_ACTION
        }
}
