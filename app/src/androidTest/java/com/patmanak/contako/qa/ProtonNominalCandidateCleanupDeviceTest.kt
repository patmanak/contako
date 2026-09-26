package com.patmanak.contako.qa

import android.accounts.AccountManager
import android.Manifest
import android.provider.ContactsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.patmanak.contako.BuildConfig
import com.patmanak.contako.android.account.ContakoAndroidAccountContract
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Removes only isolated candidate account/provider residue before a fresh nominal login. */
@RunWith(AndroidJUnit4::class)
class ProtonNominalCandidateCleanupDeviceTest {
    @Test
    fun removeOnlyCandidateAccountAndProjectionResidue() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString(ARG_MODE) == MODE_RUN)
        require(BuildConfig.APPLICATION_ID == ProtonNominalCandidateResidueCleaner.CANDIDATE_APPLICATION_ID) {
            "PN_CLEANUP_CANDIDATE_ISOLATION"
        }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(
            BuildConfig.APPLICATION_ID,
            Manifest.permission.READ_CONTACTS,
        )
        instrumentation.uiAutomation.grantRuntimePermission(
            BuildConfig.APPLICATION_ID,
            Manifest.permission.WRITE_CONTACTS,
        )
        ProtonNominalCandidateResidueCleaner.removeAndVerify(context)
    }

    private companion object {
        const val ARG_MODE = "pnCandidateCleanupMode"
        const val MODE_RUN = "candidate_only"
    }
}

internal object ProtonNominalCandidateResidueCleaner {
    const val CANDIDATE_APPLICATION_ID = "com.patmanak.contako.candidate"

    fun removeAndVerify(context: android.content.Context) {
        require(BuildConfig.APPLICATION_ID == CANDIDATE_APPLICATION_ID) {
            "PN_CANDIDATE_ISOLATION"
        }
        require(ContakoAndroidAccountContract.ACCOUNT_TYPE == CANDIDATE_APPLICATION_ID) {
            "PN_CANDIDATE_ACCOUNT_TYPE"
        }
        val accountManager = AccountManager.get(context)
        val accounts = accountManager.getAccountsByType(ContakoAndroidAccountContract.ACCOUNT_TYPE)
        // ContactsProvider otherwise converts the delete into a tombstone for later upload. This
        // is the sync-adapter-owned candidate projection, so cleanup must remove the rows rather
        // than leave hundreds of deleted records that make the next nominal run ambiguous.
        val syncAdapterRawContacts = ContactsContract.RawContacts.CONTENT_URI.buildUpon()
            .appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true")
            .build()
        val syncAdapterGroups = ContactsContract.Groups.CONTENT_URI.buildUpon()
            .appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true")
            .build()
        context.contentResolver.delete(
            syncAdapterRawContacts,
            "${ContactsContract.RawContacts.ACCOUNT_TYPE} = ?",
            arrayOf(ContakoAndroidAccountContract.ACCOUNT_TYPE),
        )
        context.contentResolver.delete(
            syncAdapterGroups,
            "${ContactsContract.Groups.ACCOUNT_TYPE} = ?",
            arrayOf(ContakoAndroidAccountContract.ACCOUNT_TYPE),
        )
        accounts.forEach { account ->
            check(accountManager.removeAccountExplicitly(account)) { "PN_CANDIDATE_ACCOUNT_REMOVE" }
        }
        assertEquals(
            0,
            accountManager.getAccountsByType(ContakoAndroidAccountContract.ACCOUNT_TYPE).size,
        )
        assertEquals(0, countRows(context, ContactsContract.RawContacts.CONTENT_URI))
        assertEquals(0, countRows(context, ContactsContract.Groups.CONTENT_URI))
    }

    private fun countRows(context: android.content.Context, uri: android.net.Uri): Int =
        context.contentResolver.query(
            uri,
            arrayOf(ContactsContract.RawContacts._ID),
            "account_type = ?",
            arrayOf(ContakoAndroidAccountContract.ACCOUNT_TYPE),
            null,
        )?.use { it.count } ?: error("PN_CANDIDATE_PROVIDER_QUERY")
}
