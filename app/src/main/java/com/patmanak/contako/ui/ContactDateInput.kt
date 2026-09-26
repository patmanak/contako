package com.patmanak.contako.ui

import java.time.DateTimeException
import java.time.LocalDate
import java.time.MonthDay
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.Locale

private val fullInputDate = DateTimeFormatter.ofPattern("dd/MM/uuuu", Locale.ROOT)
    .withResolverStyle(ResolverStyle.STRICT)
private val yearlessInputDate = DateTimeFormatter.ofPattern("dd/MM", Locale.ROOT)
    .withResolverStyle(ResolverStyle.STRICT)

/** Unknown imported forms remain verbatim until the user explicitly replaces them. */
internal fun contactDateForInput(value: String): String = try {
    when {
        value.matches(Regex("[0-9]{8}")) -> LocalDate.parse(value, DateTimeFormatter.BASIC_ISO_DATE).format(fullInputDate)
        value.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")) -> LocalDate.parse(value).format(fullInputDate)
        value.matches(Regex("--[0-9]{4}")) -> MonthDay.parse("${value.take(4)}-${value.takeLast(2)}").format(yearlessInputDate)
        value.matches(Regex("--[0-9]{2}-[0-9]{2}")) -> MonthDay.parse(value).format(yearlessInputDate)
        else -> value
    }
} catch (_: DateTimeException) {
    value
}

internal fun contactDateFromInput(value: String, withoutYear: Boolean): String? = try {
    when {
        withoutYear && value.matches(Regex("[0-9]{2}/[0-9]{2}")) ->
            MonthDay.parse(value, yearlessInputDate).toString()
        !withoutYear && value.matches(Regex("[0-9]{2}/[0-9]{2}/[0-9]{4}")) ->
            LocalDate.parse(value, fullInputDate).takeIf { it.year in 1..9999 }?.toString()
        else -> null
    }
} catch (_: DateTimeException) {
    null
}

/** A leap-year anchor is only for calendar selection; it MUST NOT become stored data. */
internal fun contactDateCalendarValue(value: String): LocalDate? {
    val displayed = contactDateForInput(value)
    val canonical = contactDateFromInput(displayed, withoutYear = displayed.length == 5) ?: return null
    return if (canonical.startsWith("--")) MonthDay.parse(canonical).atYear(2000) else LocalDate.parse(canonical)
}
