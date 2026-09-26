package com.patmanak.contako.ui

import java.time.DateTimeException
import java.time.LocalDate
import java.time.MonthDay
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** Presentation only: partial or unrecognized dates retain their exact source text. */
internal fun formatContactDate(value: String, locale: Locale, monthDayPattern: String): String = try {
    when {
        value.matches(Regex("[0-9]{8}")) -> LocalDate.parse(value, DateTimeFormatter.BASIC_ISO_DATE)
            .format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))
        value.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")) -> LocalDate.parse(value)
            .format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))
        value.matches(Regex("--[0-9]{4}")) -> MonthDay.parse("${value.take(4)}-${value.takeLast(2)}")
            .format(DateTimeFormatter.ofPattern(monthDayPattern, locale))
        value.matches(Regex("--[0-9]{2}-[0-9]{2}")) -> MonthDay.parse(value)
            .format(DateTimeFormatter.ofPattern(monthDayPattern, locale))
        else -> value
    }
} catch (_: DateTimeException) {
    value
}
