package com.patmanak.contako.data.android.provider

import android.accounts.Account
import android.accounts.AccountManager
import android.content.ContentResolver
import android.content.Context
import android.os.Bundle
import com.patmanak.contako.android.account.AndroidAccountPlatform
import com.patmanak.contako.android.account.ContakoAndroidAccountContract

/**
 * Framework side of Android account provisioning.
 *
 * It lives in the replaceable provider boundary because it is the only layer allowed to touch
 * `ContentResolver`; the coordinator itself stays framework-free and unit-testable.
 */
internal class FrameworkAndroidAccountPlatform(context: Context) : AndroidAccountPlatform {
    private val applicationContext = context.applicationContext

    override fun existingAccountNames(): List<String> = try {
        AccountManager.get(applicationContext)
            .getAccountsByType(ContakoAndroidAccountContract.ACCOUNT_TYPE)
            .map(Account::name)
    } catch (_: SecurityException) {
        emptyList()
    }

    override fun addAccount(account: Account): Boolean = try {
        // No password and no userdata: credentials stay in Proton Core protected storage (D-060).
        AccountManager.get(applicationContext).addAccountExplicitly(account, null, null)
    } catch (_: SecurityException) {
        false
    }

    override fun enableContactsSync(account: Account) {
        val authority = ContakoAndroidAccountContract.CONTACTS_AUTHORITY
        try {
            ContentResolver.setIsSyncable(account, authority, 1)
            ContentResolver.setSyncAutomatically(account, authority, true)
            // D-033 asks for roughly hourly periodic sync; the platform still applies its own batching.
            ContentResolver.addPeriodicSync(account, authority, Bundle.EMPTY, PERIODIC_SYNC_SECONDS)
        } catch (_: SecurityException) {
            // Scheduling is best-effort: manual and in-app passes remain available.
        }
    }

    override fun writeProviderEpochProof(androidAccountName: String, providerEpoch: Long): Boolean =
        FrameworkAndroidProviderEpochProofWriter(applicationContext)
            .write(androidAccountName, providerEpoch) == AndroidProviderEpochProofWriteResult.Written

    private companion object {
        const val PERIODIC_SYNC_SECONDS = 3_600L
    }
}
