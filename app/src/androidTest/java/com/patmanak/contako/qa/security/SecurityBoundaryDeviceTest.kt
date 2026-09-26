package com.patmanak.contako.qa.security

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLHandshakeException
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SecurityBoundaryDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun seedSyntheticBackupData() {
        context.getSharedPreferences("v08_backup_fixture", Context.MODE_PRIVATE)
            .edit().putString("canary", "synthetic-backup-value").commit()
        context.filesDir.resolve("v08-backup-canary").writeText("synthetic-backup-file")
    }

    @Test fun verifySyntheticBackupDataAbsent() {
        assertFalse(context.getSharedPreferences("v08_backup_fixture", Context.MODE_PRIVATE).contains("canary"))
        assertFalse(context.filesDir.resolve("v08-backup-canary").exists())
    }

    @Test fun cleartextAndUntrustedTlsAreRejected() {
        val arguments = InstrumentationRegistry.getArguments()
        val clearPort = arguments.getString("clearPort")?.toIntOrNull() ?: fail("clearPort required")
        val tlsPort = arguments.getString("tlsPort")?.toIntOrNull() ?: fail("tlsPort required")
        expectFailure("cleartext") {
            (URL("http://127.0.0.1:$clearPort/FX08_AUTH_CANARY_NOT_PERSONAL").openConnection() as HttpURLConnection)
                .apply { connectTimeout = 3_000; readTimeout = 3_000; setRequestProperty("Authorization", "Bearer FX08_TOKEN_CANARY_NOT_PERSONAL") }
                .inputStream.use { it.read() }
        }
        expectFailure("untrusted TLS") {
            (URL("https://127.0.0.1:$tlsPort/FX08_CONTACT_CANARY_NOT_PERSONAL").openConnection() as HttpsURLConnection)
                .apply { connectTimeout = 3_000; readTimeout = 3_000 }
                .inputStream.use { it.read() }
        }
    }

    private fun expectFailure(name: String, operation: () -> Unit) {
        try {
            operation()
            fail("$name unexpectedly succeeded")
        } catch (expected: Exception) {
            if (name == "untrusted TLS" && expected !is SSLHandshakeException && expected.cause !is SSLHandshakeException) {
                throw expected
            }
        }
    }
}
