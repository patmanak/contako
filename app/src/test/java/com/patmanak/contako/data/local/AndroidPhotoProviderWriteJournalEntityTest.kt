package com.patmanak.contako.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

internal class AndroidPhotoProviderWriteJournalEntityTest {
    @Test
    fun preparedJournalIsBoundedAndDiagnosticsAreRedacted() {
        val journal = entity(contentSize = 10L * 1_024 * 1_024)

        assertEquals("AndroidPhotoProviderWriteJournalEntity(REDACTED, state=PREPARED, contentSize=10485760)", journal.toString())
        assertFalse(journal.toString().contains("account-secret"))
        assertFalse(journal.toString().contains("binary-secret"))
        assertThrows(IllegalArgumentException::class.java) {
            entity(contentSize = 10L * 1_024 * 1_024 + 1)
        }
    }

    @Test
    fun committedStateRequiresAProviderVersion() {
        assertThrows(IllegalArgumentException::class.java) {
            entity(state = "COMMITTED", resultVersion = null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            entity(state = "PREPARED", resultVersion = 8)
        }
        entity(state = "COMMITTED", resultVersion = 8)
    }

    private fun entity(
        contentSize: Long = 1,
        state: String = "PREPARED",
        resultVersion: Long? = null,
    ) = AndroidPhotoProviderWriteJournalEntity(
        accountId = "account-secret",
        canonicalContactId = "contact-secret",
        androidAccountName = "android-secret",
        providerEpoch = 3,
        rawContactLocator = 4,
        expectedRawContactVersion = 7,
        expectedSourceIdentity = "source-secret",
        expectedCanonicalRevision = 5,
        expectedLedgerRevision = 6,
        canonicalValueId = "photo-secret",
        binaryReference = "binary-secret",
        contentSize = contentSize,
        contentSha256 = "a".repeat(64),
        state = state,
        resultRawContactVersion = resultVersion,
    )
}
