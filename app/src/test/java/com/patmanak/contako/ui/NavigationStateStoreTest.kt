package com.patmanak.contako.ui

import androidx.lifecycle.SavedStateHandle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationStateStoreTest {
    @Test
    fun searchVisibilitySurvivesNavigationButCloseClearsItsFilter() {
        val handle = SavedStateHandle()
        val store = NavigationStateStore(handle)
        assertFalse(store.snapshot().current.searchExpanded)
        store.openSearch()
        assertTrue(store.snapshot().current.searchExpanded)
        assertTrue(store.back())
        assertFalse(store.snapshot().current.searchExpanded)
        store.updateQuery("Ada")
        store.selectItem("contact")
        store.back()
        assertTrue(store.snapshot().current.searchExpanded)
        assertEquals("Ada", store.snapshot().current.query)
        store.select(RootDestination.GROUPS)
        assertFalse(store.snapshot().current.searchExpanded)
        val restored = NavigationStateStore(handle)
        restored.select(RootDestination.CONTACTS)
        assertTrue(restored.snapshot().current.searchExpanded)
        assertEquals("Ada", restored.snapshot().current.query)
        restored.closeSearch()
        assertFalse(restored.snapshot().current.searchExpanded)
        assertEquals("", restored.snapshot().current.query)
        assertFalse(NavigationStateStore(handle).snapshot().current.searchExpanded)
        val legacy = NavigationStateStore(SavedStateHandle(mapOf("navigation.CONTACTS.query" to "Ada")))
        assertTrue(legacy.snapshot().current.searchExpanded)
    }

    @Test
    fun actionBadgeUsesAnHonestBoundedVisualCount() {
        assertEquals("0", actionRequiredBadgeText(0))
        assertEquals("99", actionRequiredBadgeText(99))
        assertEquals("99+", actionRequiredBadgeText(100))
    }

    @Test
    fun responsiveBreakpointUsesCompactBottomBarBelowSixHundredDp() {
        assertEquals(NavigationMode.BOTTOM_BAR, navigationMode(599))
        assertEquals(NavigationMode.RAIL, navigationMode(600))
        assertEquals(NavigationMode.RAIL, navigationMode(840))
        assertEquals(NavigationMode.BOTTOM_BAR, navigationMode(840, fontScale = 2f))
        assertEquals(NavigationMode.RAIL, navigationMode(1_200, fontScale = 2f))
    }

    @Test
    fun selectionClearingIsDestinationScopedAndIgnoresAnotherSelectedItem() {
        val store = NavigationStateStore()
        store.selectItem("contact-kept")
        store.select(RootDestination.GROUPS)
        store.selectItem("group-kept")

        store.clearSelection(RootDestination.CONTACTS, "contact-deleted")
        store.clearSelection(RootDestination.GROUPS, "group-kept")

        assertEquals(
            "contact-kept",
            store.snapshot().destinationStates.getValue(RootDestination.CONTACTS).selectedId,
        )
        assertNull(store.snapshot().destinationStates.getValue(RootDestination.GROUPS).selectedId)
    }

    @Test
    fun destinationQuerySelectionAndScrollSurviveSwitchAndRecreation() {
        val handle = SavedStateHandle()
        val store = NavigationStateStore(handle)
        store.updateQuery("contact query")
        store.selectItem("synthetic-contact")
        store.updateScroll(18, 7)
        store.select(RootDestination.GROUPS)
        store.updateQuery("group query")
        store.selectItem("synthetic-group")
        store.updateScroll(4, 3)

        val recreated = NavigationStateStore(handle)
        assertEquals(RootDestination.GROUPS, recreated.snapshot().destination)
        assertEquals("group query", recreated.snapshot().current.query)
        assertEquals("synthetic-group", recreated.snapshot().current.selectedId)
        recreated.select(RootDestination.CONTACTS)
        assertEquals("contact query", recreated.snapshot().current.query)
        assertEquals("synthetic-contact", recreated.snapshot().current.selectedId)
        assertEquals(18, recreated.snapshot().current.scrollIndex)
        assertEquals(7, recreated.snapshot().current.scrollOffset)
    }

    @Test
    fun backUnwindsSecondarySelectionQueryDestinationThenLeavesRoot() {
        val store = NavigationStateStore()
        store.select(RootDestination.SYNC)
        store.updateQuery("query")
        store.selectItem("selection")
        store.open(SecondaryDestination.ABOUT)

        assertTrue(store.back())
        assertNull(store.snapshot().secondary)
        assertTrue(store.back())
        assertNull(store.snapshot().current.selectedId)
        assertTrue(store.back())
        assertEquals("", store.snapshot().current.query)
        assertTrue(store.back())
        assertEquals(RootDestination.CONTACTS, store.snapshot().destination)
        assertFalse(store.back())
    }

    @Test
    fun settingsSurvivesRecreationAndBackReturnsToThePrimaryScreen() {
        val handle = SavedStateHandle()
        val store = NavigationStateStore(handle)
        store.open(SecondaryDestination.SETTINGS)

        val recreated = NavigationStateStore(handle)
        assertEquals(SecondaryDestination.SETTINGS, recreated.snapshot().secondary)
        assertTrue(recreated.back())
        assertNull(recreated.snapshot().secondary)
    }

    @Test
    fun removedAccountHelpAndNestedSettingsStatesAreSanitizedOnRestore() {
        listOf("ACCOUNT", "HELP").forEach { removedDestination ->
            val handle = SavedStateHandle(
                mapOf(
                    "navigation.secondary" to removedDestination,
                    "navigation.settingsSection" to "ACCOUNT",
                ),
            )

            assertNull(NavigationStateStore(handle).snapshot().secondary)
        }
    }

    @Test
    fun reselectionScrollsToTopWithoutClearingQuery() {
        val store = NavigationStateStore()
        store.updateQuery("kept")
        store.updateScroll(20, 5)
        store.select(RootDestination.CONTACTS)

        assertEquals("kept", store.snapshot().current.query)
        assertEquals(0, store.snapshot().current.scrollIndex)
        assertEquals(1, store.snapshot().current.reselectionRevision)
    }
}
