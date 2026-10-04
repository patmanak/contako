package com.patmanak.contako.data.android.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class AdaptiveAndroidContactPageReaderTest {
    @Test fun oversizedAggregateShrinksWithoutChangingReturnedObservationOrCursor() {
        val limits = mutableListOf<Int>()
        val observation = AndroidStableRawContactObservation(
            AndroidOwnedRawContact(37, "canonical", "source", false, false, 4), emptyList(),
        )
        val expected = AndroidStableRawContactPageResult.Stable(
            AndroidStableRawContactObservationPage(listOf(observation), 37),
        )
        val actual = readAdaptiveAndroidContactPage { limit ->
            limits += limit
            if (limit > 25) throw AndroidProviderBoundaryException(AndroidProviderFailureCategory.BOUND_EXCEEDED)
            expected
        }
        assertEquals(listOf(100, 50, 25), limits)
        assertSame(expected, actual)
    }

    @Test fun individuallyOversizedContactStillFailsAfterBoundedAttempts() {
        val limits = mutableListOf<Int>()
        val failure = assertThrows(AndroidProviderBoundaryException::class.java) {
            readAdaptiveAndroidContactPage { limit ->
                limits += limit
                throw AndroidProviderBoundaryException(AndroidProviderFailureCategory.BOUND_EXCEEDED)
            }
        }
        assertEquals(listOf(100, 50, 25, 12, 6, 3, 1), limits)
        assertEquals(AndroidProviderFailureCategory.BOUND_EXCEEDED, failure.category)
    }

    @Test fun permissionOwnershipAndMalformedFailuresNeverBecomeSmallerPages() {
        listOf(AndroidProviderFailureCategory.PERMISSION_DENIED,
            AndroidProviderFailureCategory.ACCOUNT_SCOPE_MISMATCH,
            AndroidProviderFailureCategory.MALFORMED_PROVIDER_DATA,
            AndroidProviderFailureCategory.PROVIDER_UNAVAILABLE).forEach { category ->
            var calls = 0
            val failure = assertThrows(AndroidProviderBoundaryException::class.java) {
                readAdaptiveAndroidContactPage {
                    calls++
                    throw AndroidProviderBoundaryException(category)
                }
            }
            assertEquals(1, calls)
            assertEquals(category, failure.category)
        }
    }

    @Test fun stalePageRemainsStaleWithoutRetryingInsideTheByteBudgetBoundary() {
        var calls = 0
        assertSame(AndroidStableRawContactPageResult.ReplanRequired,
            readAdaptiveAndroidContactPage {
                calls++
                AndroidStableRawContactPageResult.ReplanRequired
            })
        assertEquals(1, calls)
    }
}
