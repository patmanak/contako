package com.patmanak.contako.ui

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class ContactDatePresentationTest {
    @Test fun importedBasicDatesDisplayWithoutInventingAYear() {
        assertEquals("29 février", formatContactDate("--0229", Locale.FRANCE, "d MMMM"))
        assertEquals("29 février", formatContactDate("--02-29", Locale.FRANCE, "d MMMM"))
        assertEquals("30 sept. 2020", formatContactDate("20200930", Locale.FRANCE, "d MMMM"))
        assertEquals("Sep 30, 2020", formatContactDate("2020-09-30", Locale.US, "MMMM d"))
    }

    @Test fun unsupportedAndInvalidDatesRemainVisibleVerbatim() {
        listOf("--0230", "20200230", "2020", "--02", "spring", "").forEach {
            assertEquals(it, formatContactDate(it, Locale.FRANCE, "d MMMM"))
        }
    }
}
