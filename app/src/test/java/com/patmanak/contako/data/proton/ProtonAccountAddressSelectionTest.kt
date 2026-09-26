package com.patmanak.contako.data.proton

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProtonAccountAddressSelectionTest {
    @Test
    fun primaryEnabledAddressWinsOverLoginAddressAndDisabledAliases() {
        assertEquals(
            "primary@example.test",
            selectCurrentAccountAddress(
                addresses = listOf(
                    GateCDisplayAddress("disabled@example.test", enabled = false, order = 0),
                    GateCDisplayAddress("secondary@example.test", enabled = true, order = 2),
                    GateCDisplayAddress("primary@example.test", enabled = true, order = 1),
                ),
                accountEmail = "login@example.test",
                username = "username",
            ),
        )
    }

    @Test
    fun invalidAddressRowsFallBackToAccountEmailThenEmailShapedUsername() {
        assertEquals(
            "account@example.test",
            selectCurrentAccountAddress(
                addresses = listOf(GateCDisplayAddress("invalid", enabled = true, order = 0)),
                accountEmail = "account@example.test",
                username = "username@example.test",
            ),
        )
        assertEquals(
            "username@example.test",
            selectCurrentAccountAddress(emptyList(), "invalid", "username@example.test"),
        )
        assertNull(selectCurrentAccountAddress(emptyList(), "invalid", "username"))
    }
}
