package com.patmanak.contako.data.proton

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.proton.core.crypto.android.context.AndroidCryptoContext
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Local-only proof against the maintained Android Keystore primitive; no Proton API is built. */
@RunWith(AndroidJUnit4::class)
class GateCProtectedStorageGuardDeviceTest {
    @Test
    fun maintainedKeystoreAdmissionPrecedesGateCDatabaseOpen() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.deleteDatabase(DATABASE_NAME)
        assertFalse(context.getDatabasePath(DATABASE_NAME).exists())

        val crypto = AndroidCryptoContext().keyStoreCrypto
        val proof = GateCProtectedStorageGuard.requireAvailable(crypto)
        assertFalse(context.getDatabasePath(DATABASE_NAME).exists())

        val database = ProtonGateCDatabase.build(context, proof, DATABASE_NAME)
        try {
            database.openHelper.writableDatabase
            assertTrue(database.isOpen)
        } finally {
            database.close()
            context.deleteDatabase(DATABASE_NAME)
        }
        assertFalse(context.getDatabasePath(DATABASE_NAME).exists())
    }

    private companion object {
        const val DATABASE_NAME = "gate-c-protected-storage-guard-test.db"
    }
}
