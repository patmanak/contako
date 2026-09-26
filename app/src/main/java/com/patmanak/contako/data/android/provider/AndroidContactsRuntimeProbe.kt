package com.patmanak.contako.data.android.provider

import android.Manifest
import android.accounts.AccountManager
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import java.nio.ByteBuffer
import com.patmanak.contako.android.account.ContakoAndroidAccountContract
import com.patmanak.contako.data.android.AndroidRuntimeProbe
import com.patmanak.contako.data.android.AndroidRuntimeProbeResult

internal class AndroidContactsRuntimeProbe(
    private val context: Context,
    private val authority: String = ContakoAndroidAccountContract.CONTACTS_AUTHORITY,
) : AndroidRuntimeProbe {
    override fun inspect(
        androidAccountName: String,
        expectedProviderEpoch: Long,
    ): AndroidRuntimeProbeResult {
        if (authority != ContakoAndroidAccountContract.CONTACTS_AUTHORITY) {
            return AndroidRuntimeProbeResult.AuthorityMismatch
        }
        if (
            context.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.WRITE_CONTACTS) != PackageManager.PERMISSION_GRANTED
        ) {
            return AndroidRuntimeProbeResult.PermissionDenied
        }
        val exactAccount = AccountManager.get(context)
            .getAccountsByType(ContakoAndroidAccountContract.ACCOUNT_TYPE)
            .singleOrNull { it.name == androidAccountName }
            ?: return AndroidRuntimeProbeResult.AndroidAccountMissing
        if (context.packageManager.resolveContentProvider(authority, 0) == null) {
            return AndroidRuntimeProbeResult.ProviderUnavailable
        }
        return try {
            val cursor = context.contentResolver.query(
                ContactsContract.RawContacts.CONTENT_URI,
                arrayOf(ContactsContract.RawContacts._ID),
                "${ContactsContract.RawContacts.ACCOUNT_NAME} = ? AND " +
                    "${ContactsContract.RawContacts.ACCOUNT_TYPE} = ?",
                arrayOf(androidAccountName, ContakoAndroidAccountContract.ACCOUNT_TYPE),
                "${ContactsContract.RawContacts._ID} ASC LIMIT 1",
            ) ?: return AndroidRuntimeProbeResult.ProviderUnavailable
            cursor.close()
            val client = context.contentResolver.acquireContentProviderClient(authority)
                ?: return AndroidRuntimeProbeResult.ProviderUnavailable
            client.use {
                val state = ContactsContract.SyncState.get(it, exactAccount)
                if (state.contentEquals(encodeProviderEpoch(expectedProviderEpoch))) {
                    AndroidRuntimeProbeResult.Ready
                } else {
                    AndroidRuntimeProbeResult.ProviderStateDiverged
                }
            }
        } catch (_: SecurityException) {
            AndroidRuntimeProbeResult.PermissionDenied
        } catch (_: RuntimeException) {
            AndroidRuntimeProbeResult.ProviderUnavailable
        }
    }

    internal companion object {
        private const val PROOF_VERSION: Byte = 1

        fun encodeProviderEpoch(providerEpoch: Long): ByteArray =
            ByteBuffer.allocate(9).put(PROOF_VERSION).putLong(providerEpoch).array()
    }
}
