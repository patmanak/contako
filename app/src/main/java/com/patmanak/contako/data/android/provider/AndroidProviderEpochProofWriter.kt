package com.patmanak.contako.data.android.provider

import android.accounts.AccountManager
import android.content.Context
import android.provider.ContactsContract
import com.patmanak.contako.android.account.ContakoAndroidAccountContract

internal sealed interface AndroidProviderEpochProofWriteResult {
    data object Written : AndroidProviderEpochProofWriteResult
    data object PermissionDenied : AndroidProviderEpochProofWriteResult
    data object ProviderUnavailable : AndroidProviderEpochProofWriteResult
    data object AccountMissing : AndroidProviderEpochProofWriteResult
}

internal fun interface AndroidProviderEpochProofWriter {
    fun write(androidAccountName: String, providerEpoch: Long): AndroidProviderEpochProofWriteResult
}

internal class FrameworkAndroidProviderEpochProofWriter(
    private val context: Context,
) : AndroidProviderEpochProofWriter {
    override fun write(androidAccountName: String, providerEpoch: Long): AndroidProviderEpochProofWriteResult {
        val account = AccountManager.get(context)
            .getAccountsByType(ContakoAndroidAccountContract.ACCOUNT_TYPE)
            .singleOrNull { it.name == androidAccountName }
            ?: return AndroidProviderEpochProofWriteResult.AccountMissing
        return try {
            val client = context.contentResolver.acquireContentProviderClient(ContactsContract.AUTHORITY)
                ?: return AndroidProviderEpochProofWriteResult.ProviderUnavailable
            client.use {
                ContactsContract.SyncState.set(
                    it,
                    account,
                    AndroidContactsRuntimeProbe.encodeProviderEpoch(providerEpoch),
                )
            }
            AndroidProviderEpochProofWriteResult.Written
        } catch (_: SecurityException) {
            AndroidProviderEpochProofWriteResult.PermissionDenied
        } catch (_: RuntimeException) {
            AndroidProviderEpochProofWriteResult.ProviderUnavailable
        }
    }
}
