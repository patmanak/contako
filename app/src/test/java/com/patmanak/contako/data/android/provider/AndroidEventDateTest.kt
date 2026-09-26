package com.patmanak.contako.data.android.provider

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidEventDateTest {
    @Test
    fun `provider text forms are readable without a calendar conversion`() {
        listOf("2000-02-29", "20000229", "--02-29", "--0229", "2000", "2000-02",
            "--02", "---29", "2000-02-29T12:30:00Z", "20000229T123000Z",
            "circa spring 2000", "29/02/2000").forEach { assertTrue(isReadableAndroidEventDate(it)) }
    }

    @Test
    fun `impossible calendar dates and empty or control-bearing values remain rejected`() {
        listOf("2001-02-29", "20010229", "--02-30", "--0230", "2000-13-01", "", "  ",
            "2000\n", "spring\u0000").forEach { assertFalse(isReadableAndroidEventDate(it)) }
    }
}
