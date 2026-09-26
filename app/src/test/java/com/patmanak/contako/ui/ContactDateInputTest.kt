package com.patmanak.contako.ui

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContactDateInputTest {
    @Test fun fullAndYearlessImportsHaveUnambiguousDayFirstPresentation() {
        assertEquals("30/09/2020", contactDateForInput("20200930"))
        assertEquals("30/09/2020", contactDateForInput("2020-09-30"))
        assertEquals("29/02", contactDateForInput("--0229"))
        assertEquals("29/02", contactDateForInput("--02-29"))
        assertEquals("2020-09-30", contactDateFromInput("30/09/2020", false))
        assertEquals("--02-29", contactDateFromInput("29/02", true))
        assertEquals("2000-02-29", contactDateFromInput("29/02/2000", false))
    }

    @Test fun impossibleAndAmbiguousInputCannotBeCommitted() {
        listOf("29/02/2023", "31/04/2020", "01/01/0000", "1/2/2020", "2020-09-30", "30/09", "").forEach {
            assertNull(it, contactDateFromInput(it, false))
        }
        listOf("30/02", "31/04", "00/01", "12/13", "29/02/2000", "").forEach {
            assertNull(it, contactDateFromInput(it, true))
        }
    }

    @Test fun opaqueImportedValuesStayVerbatimAndDoNotSeedTheCalendar() {
        listOf("spring", "2020", "--02", "20200230", "2020-09-30T12:00:00Z", "").forEach {
            assertEquals(it, contactDateForInput(it))
            assertNull(contactDateCalendarValue(it))
        }
    }

    @Test fun calendarAnchorPreservesLeapDayWithoutAddingAStoredYear() {
        assertEquals(LocalDate.of(2000, 2, 29), contactDateCalendarValue("--0229"))
        assertEquals(LocalDate.of(1982, 9, 30), contactDateCalendarValue("19820930"))
        assertEquals("--02-29", contactDateFromInput(contactDateForInput("--0229"), true))
    }
}
