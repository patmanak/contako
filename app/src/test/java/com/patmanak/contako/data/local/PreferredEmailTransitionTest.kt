package com.patmanak.contako.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PreferredEmailTransitionTest {
    @Test
    fun switchingPreferredEmailDoesNotReplaceItsExistingSecondaryGroups() {
        assertEquals(setOf("secondary-only"), preferredEmailTransitionGroupIds(
            setOf("secondary-only"), setOf("old-primary-only"), setOf("old-primary-only"), "next-email",
        ))
    }

    @Test
    fun onlyNativeMembershipDifferenceIsAppliedToTheNewPreferredEmail() {
        assertEquals(setOf("secondary-only", "new-native-group"), preferredEmailTransitionGroupIds(
            setOf("secondary-only", "removed-native-group"),
            setOf("old-primary-only", "removed-native-group"),
            setOf("old-primary-only", "new-native-group"), "next-email",
        ))
    }

    @Test
    fun firstAndLastEmailTransitionsDoNotInventImpossibleAssignments() {
        assertEquals(setOf("new-native-group"), preferredEmailTransitionGroupIds(
            emptySet(), emptySet(), setOf("new-native-group"), "first-email",
        ))
        assertTrue(preferredEmailTransitionGroupIds(
            setOf("old-group"), setOf("old-group"), setOf("old-group"), null,
        ).isEmpty())
        assertTrue(runCatching { RoomAndroidPreferredEmailTransition("same", "same", 1) }.isFailure)
        assertTrue(runCatching { RoomAndroidPreferredEmailTransition(null, "next", Long.MAX_VALUE) }.isFailure)
    }
}
