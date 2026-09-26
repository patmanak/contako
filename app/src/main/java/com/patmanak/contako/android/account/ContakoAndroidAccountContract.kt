package com.patmanak.contako.android.account

import com.patmanak.contako.BuildConfig

/** Permanent release identity selected by D-048, isolated for explicitly suffixed QA packages. */
internal object ContakoAndroidAccountContract {
    val ACCOUNT_TYPE: String = BuildConfig.ANDROID_ACCOUNT_TYPE
    const val CONTACTS_AUTHORITY = "com.android.contacts"
    const val AUTHENTICATOR_ACTION = "android.accounts.AccountAuthenticator"
    const val SYNC_ADAPTER_ACTION = "android.content.SyncAdapter"
}
