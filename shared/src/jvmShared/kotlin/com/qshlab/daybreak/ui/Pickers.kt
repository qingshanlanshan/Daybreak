package com.qshlab.daybreak.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.qshlab.daybreak.Daybreak
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale

fun hexColor(s: String): Color = runCatching { Color(("FF" + s.removePrefix("#")).toLong(16)) }.getOrDefault(Color(0xFF3F51D1))

fun timeText(t: LocalTime): String =
    t.format(DateTimeFormatter.ofPattern(if (Daybreak.platform.use24Hour) "HH:mm" else "h:mm a", Locale.getDefault()))

fun dateText(d: LocalDate, withYear: Boolean = d.year != LocalDate.now().year): String =
    d.format(DateTimeFormatter.ofPattern(if (withYear) "EEE d MMM yyyy" else "EEE d MMM", Locale.getDefault()))

/** "Today", "Tomorrow", "Yesterday", or the date. */
fun relativeDay(d: LocalDate): String = when (d) {
    LocalDate.now() -> "Today"; LocalDate.now().plusDays(1) -> "Tomorrow"; LocalDate.now().minusDays(1) -> "Yesterday"; else -> dateText(d)
}

fun dayName(d: java.time.DayOfWeek, short: Boolean = true): String = d.getDisplayName(if (short) TextStyle.SHORT else TextStyle.FULL, Locale.getDefault())

@Composable
fun ColorDot(color: Color, size: Int = 10, modifier: Modifier = Modifier) = Box(modifier.size(size.dp).clip(CircleShape).background(color))

/** A tappable chip showing a date; opens a date picker. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateField(date: LocalDate, onChange: (LocalDate) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Chip(dateText(date, withYear = true), modifier) { open = true }
    if (open) {
        val state = rememberDatePickerState(initialSelectedDateMillis = date.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli())
        DatePickerDialog(onDismissRequest = { open = false },
            confirmButton = { TextButton({ state.selectedDateMillis?.let { onChange(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }; open = false }) { Text("OK") } },
            dismissButton = { TextButton({ open = false }) { Text("Cancel") } }) { DatePicker(state) }
    }
}

/** A tappable chip showing a time; opens a time picker. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeField(time: LocalTime, onChange: (LocalTime) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Chip(timeText(time), modifier) { open = true }
    if (open) {
        val state = rememberTimePickerState(time.hour, time.minute, Daybreak.platform.use24Hour)
        AlertDialog(onDismissRequest = { open = false },
            confirmButton = { TextButton({ onChange(LocalTime.of(state.hour, state.minute)); open = false }) { Text("OK") } },
            dismissButton = { TextButton({ open = false }) { Text("Cancel") } },
            text = { TimePicker(state) })
    }
}

@Composable
fun Chip(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(modifier.clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).clickable(onClick = onClick)
        .padding(horizontal = 12.dp, vertical = 8.dp)) { Text(text) }
}

fun LocalDateTime.roundUpToHour(): LocalDateTime = if (minute == 0 && second == 0) this else withMinute(0).withSecond(0).withNano(0).plusHours(1)
