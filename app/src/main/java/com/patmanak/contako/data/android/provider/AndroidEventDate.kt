package com.patmanak.contako.data.android.provider

import java.time.DateTimeException
import java.time.LocalDate
import java.time.MonthDay

/**
 * Event.START_DATE is provider text, not an ISO-only column. Imported date-time,
 * partial and textual dates MUST survive an unchanged projection round trip.
 * This read policy does not relax validation of newly authored Proton dates.
 */
internal fun isReadableAndroidEventDate(value: String): Boolean {
    if (value.isBlank() || value.any(Char::isISOControl)) return false
    return try {
        when {
            FULL_EVENT_DATE.matches(value) -> {
                val digits = value.replace("-", "")
                LocalDate.of(digits.substring(0, 4).toInt(), digits.substring(4, 6).toInt(),
                    digits.substring(6, 8).toInt())
            }
            YEARLESS_EVENT_DATE.matches(value) -> {
                val digits = value.removePrefix("--").replace("-", "")
                MonthDay.of(digits.substring(0, 2).toInt(), digits.substring(2, 4).toInt())
            }
            // Other provider text is opaque: do not invent a year, timezone or calendar
            // interpretation. The canonical value and preserved vCard remain unchanged.
        }
        true
    } catch (_: DateTimeException) {
        false
    }
}

private val FULL_EVENT_DATE = Regex("(?:[0-9]{4}-[0-9]{2}-[0-9]{2}|[0-9]{8})")
private val YEARLESS_EVENT_DATE = Regex("--(?:[0-9]{2}-[0-9]{2}|[0-9]{4})")
