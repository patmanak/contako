package com.patmanak.contako.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.patmanak.contako.R
import java.time.Instant
import java.time.LocalDate
import java.time.MonthDay
import java.time.ZoneOffset

@Composable
internal fun ContactDateField(
    value: String,
    label: String,
    error: String?,
    onValueChange: (String) -> Unit,
) {
    var opened by rememberSaveable { mutableStateOf(false) }
    val display = contactDateForInput(value)
    Box(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = display,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            placeholder = { Text(stringResource(R.string.date_input_full_hint)) },
            trailingIcon = { Icon(Icons.Default.DateRange, contentDescription = null) },
            singleLine = true,
            isError = error != null,
            supportingText = error?.let { { Text(it) } },
            modifier = Modifier.fillMaxWidth().focusProperties { canFocus = false }
                .clearAndSetSemantics {},
        )
        // One accessible button covers the entire field, including its calendar icon.
        Spacer(Modifier.matchParentSize().testTag("contact_date_field")
            .clickable(role = Role.Button) { opened = true }
            .semantics { contentDescription = listOfNotNull(label, display, error).joinToString(", ") })
    }
    if (opened) {
        ContactDateDialog(value, label, onDismiss = { opened = false }) { selected ->
            // Opening/confirming an unchanged basic-format date preserves its source spelling.
            if (contactDateForInput(selected) != display) onValueChange(selected)
            opened = false
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContactDateDialog(value: String, label: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    val initialDate = remember(value) { contactDateCalendarValue(value) }
    var withoutYear by rememberSaveable { mutableStateOf(initialDate != null && contactDateForInput(value).length == 5) }
    var manual by rememberSaveable { mutableStateOf(false) }
    var input by rememberSaveable { mutableStateOf(contactDateForInput(value)) }
    val calendar = rememberDatePickerState(
        initialSelectedDateMillis = initialDate?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli(),
        yearRange = 1..9999,
    )
    val selectedDate = calendar.selectedDateMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
    val selectedValue = if (manual) contactDateFromInput(input, withoutYear) else selectedDate?.let {
        if (withoutYear) MonthDay.from(it).toString() else it.toString()
    }
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { selectedValue?.let(onConfirm) }, enabled = selectedValue != null,
                modifier = Modifier.testTag("contact_date_confirm")) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    ) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(label, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(24.dp, 16.dp))
            if (manual) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text(stringResource(if (withoutYear) R.string.date_input_yearless_hint else R.string.date_input_full_hint)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                    isError = input.isNotEmpty() && selectedValue == null,
                    supportingText = if (input.isNotEmpty() && selectedValue == null) {
                        { Text(stringResource(R.string.error_date)) }
                    } else null,
                    modifier = Modifier.padding(horizontal = 24.dp).fillMaxWidth().testTag("contact_date_input"),
                )
            } else {
                DatePicker(state = calendar, title = null, showModeToggle = false,
                    headline = {
                        Text(selectedValue?.let(::contactDateForInput).orEmpty(),
                            style = MaterialTheme.typography.headlineMedium,
                            modifier = Modifier.padding(horizontal = 24.dp))
                    })
            }
            Row(Modifier.fillMaxWidth().toggleable(value = withoutYear, role = Role.Checkbox, onValueChange = { next ->
                if (!next) {
                    // Restoring a year requires an explicit choice, never the leap-year anchor.
                    if (manual) input = input.takeIf { it.matches(Regex("[0-9]{2}/[0-9]{2}")) }?.plus("/").orEmpty()
                    calendar.selectedDateMillis = null
                    calendar.displayedMonthMillis = LocalDate.now().withDayOfMonth(1)
                        .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
                } else if (manual) {
                    contactDateFromInput(input, withoutYear)?.let { previous ->
                        val date = contactDateCalendarValue(previous)!!
                        input = contactDateForInput(MonthDay.from(date).toString())
                    }
                } else {
                    // February 29 remains selectable for a date with no year.
                    calendar.selectedDateMillis = selectedDate?.withYear(2000)?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli()
                    calendar.displayedMonthMillis = (selectedDate ?: LocalDate.now()).withYear(2000)
                        .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
                }
                withoutYear = next
            }).padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = withoutYear, onCheckedChange = null)
                Text(stringResource(R.string.date_without_year), modifier = Modifier.padding(start = 8.dp))
            }
            TextButton(onClick = {
                if (!manual) input = selectedValue?.let(::contactDateForInput) ?: input
                else {
                    // Invalid/cleared text MUST NOT resurrect an earlier calendar selection.
                    calendar.selectedDateMillis = contactDateFromInput(input, withoutYear)
                        ?.let(::contactDateCalendarValue)?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli()
                    calendar.selectedDateMillis?.let { calendar.displayedMonthMillis = it }
                }
                manual = !manual
            }, modifier = Modifier.padding(horizontal = 16.dp)) {
                Text(stringResource(if (manual) R.string.date_use_calendar else R.string.date_enter_manually))
            }
        }
    }
}
