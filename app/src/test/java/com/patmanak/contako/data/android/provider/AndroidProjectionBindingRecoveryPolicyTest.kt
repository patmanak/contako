package com.patmanak.contako.data.android.provider

import com.patmanak.contako.data.android.mapping.AndroidRowKind
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidProjectionBindingRecoveryPolicyTest {
    @Test fun pendingBirthdayAndOtherBatchFieldsCanReachFullReceiptVerification() {
        listOf(AndroidRowKind.STRUCTURED_NAME, AndroidRowKind.EMAIL, AndroidRowKind.BIRTHDAY,
            AndroidRowKind.ANNIVERSARY, AndroidRowKind.CUSTOM_DATE, AndroidRowKind.POSTAL_ADDRESS,
            AndroidRowKind.NOTE, AndroidRowKind.ORGANIZATION).forEach {
            assertTrue(permitsProjectionBindingRelocation(it, pending = true))
        }
    }

    @Test fun photoNeverBypassesItsStreamJournalAndCompletedRecoveryStaysNameOnly() {
        assertFalse(permitsProjectionBindingRelocation(AndroidRowKind.PHOTO, pending = true))
        assertTrue(permitsProjectionBindingRelocation(AndroidRowKind.STRUCTURED_NAME, pending = false))
        AndroidRowKind.entries.filterNot { it == AndroidRowKind.STRUCTURED_NAME }.forEach {
            assertFalse(permitsProjectionBindingRelocation(it, pending = false))
        }
    }
}
