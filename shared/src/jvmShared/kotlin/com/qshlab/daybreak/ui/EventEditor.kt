package com.qshlab.daybreak.ui

import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qshlab.daybreak.Daybreak
import com.qshlab.daybreak.cal.Cal
import com.qshlab.daybreak.cal.CalendarMeta
import com.qshlab.daybreak.cal.Event
import com.qshlab.daybreak.cal.Occurrence
import com.qshlab.daybreak.cal.Rule
import com.qshlab.daybreak.cal.newId
import com.qshlab.daybreak.cal.parseDate
import com.qshlab.daybreak.cal.parseDateTime
import com.qshlab.daybreak.cal.str
import com.qshlab.daybreak.data.Changes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/** A panel over the screen: full screen on phones, a centred card on wide windows. */
@Composable
fun Sheet(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    BackHandler(true, onDismiss)
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f))
        .clickable(remember { MutableInteractionSource() }, null) { onDismiss() }, contentAlignment = Alignment.Center) {
        val wide = maxWidth > 700.dp
        Surface(Modifier.then(if (wide) Modifier.widthIn(max = 560.dp).heightIn(max = maxHeight - 48.dp).clip(RoundedCornerShape(16.dp)) else Modifier.fillMaxSize())
            .clickable(remember { MutableInteractionSource() }, null) {}, color = MaterialTheme.colorScheme.surface) {
            // Full screen on a phone: keep clear of the status bar, the navigation bar and the keyboard.
            Column(Modifier.then(if (wide) Modifier else Modifier.windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.safeDrawing))
                .verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
        }
    }
}

/** What the editor works on: a new event, or an existing one (the whole series for repeating events). */
data class EventTarget(val calendarId: String?, val uid: String?, val event: Event) {
    companion object {
        fun new(start: LocalDateTime) = EventTarget(null, null,
            Event("", start.str(), start.plusHours(1).str(), tz = ZoneId.systemDefault().id))
        fun of(o: Occurrence) = EventTarget(o.calendarId, o.uid, o.event)
    }
}

fun repeatText(rrule: String?, start: LocalDate): String {
    val r = rrule?.let { Rule.parse(it) } ?: return "Does not repeat"
    val every = if (r.interval > 1) "Every ${r.interval} " else "Every "
    val base = when (r.freq) {
        "DAILY" -> if (r.byDay.size == 5 && r.byDay.none { it.second.value > 5 }) "Every weekday" else every + if (r.interval > 1) "days" else "day"
        "WEEKLY" -> every + (if (r.interval > 1) "weeks" else "week") + " on " + r.byDay.ifEmpty { listOf(0 to start.dayOfWeek) }.joinToString(", ") { dayName(it.second) }
        "MONTHLY" -> every + (if (r.interval > 1) "months" else "month") + when {
            r.byDay.isNotEmpty() -> " on the " + ordinal(r.byDay[0].first) + " " + dayName(r.byDay[0].second, short = false)
            r.byMonthDay.isNotEmpty() -> " on day " + r.byMonthDay.joinToString(", ")
            else -> " on day ${start.dayOfMonth}"
        }
        else -> every + (if (r.interval > 1) "years" else "year") + " on " + start.format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()))
    }
    return base + (r.count?.let { ", $it times" } ?: "") + (r.until?.let { ", until " + dateText(it.toLocalDate(), withYear = true) } ?: "")
}

private fun ordinal(n: Int) = when (n) { 1 -> "first"; 2 -> "second"; 3 -> "third"; 4 -> "fourth"; -1 -> "last"; else -> "$n." }

private fun alertText(m: Int) = when { m == 0 -> "At the start"; m < 60 -> "$m minutes before"; m < 1440 -> "${m / 60} hour${if (m >= 120) "s" else ""} before"; else -> "${m / 1440} day${if (m >= 2880) "s" else ""} before" }

@Composable
fun EventDetails(o: Occurrence, onClose: () -> Unit, onEdit: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    val cal = Cal.calendar(o.calendarId)
    Sheet(onClose) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ColorDot(hexColor(o.color), 12); Spacer(Modifier.width(10.dp))
            Text(o.event.title.ifBlank { "(no title)" }, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        }
        Text(if (o.allDay) dateText(o.start.toLocalDate(), withYear = true) + (if (ChronoUnit.DAYS.between(o.start, o.end) > 1) " – " + dateText(o.end.toLocalDate().minusDays(1), withYear = true) else "") + " · all day"
             else dateText(o.start.toLocalDate(), withYear = true) + " · " + timeText(o.start.toLocalTime()) + " – " + timeText(o.end.toLocalTime()))
        if (o.event.rrule != null) Hint(repeatText(o.event.rrule, parseDate(o.event.start)))
        if (o.event.location.isNotBlank()) Text("📍 " + o.event.location)
        if (o.event.notes.isNotBlank()) Text(o.event.notes, color = MaterialTheme.colorScheme.onSurfaceVariant)
        o.event.alarms.firstOrNull()?.let { Hint("🔔 " + alertText(it)) }
        Hint(cal?.meta?.name ?: "")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!o.readOnly) {
                Button(onEdit) { Text("Edit") }
                OutlinedButton({ confirm = true }) { Text("Delete") }
            } else Hint("From a subscribed calendar (read-only).")
            Spacer(Modifier.weight(1f))
            TextButton(onClose) { Text("Close") }
        }
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("Delete “${o.event.title}”?") },
        text = { if (o.event.rrule != null) Text("It repeats. Delete only this one, or all of them?") },
        confirmButton = {
            Row {
                if (o.event.rrule != null) TextButton({ Cal.skipOccurrence(o.calendarId, o.uid, if (o.allDay) parseDate(o.key).atStartOfDay() else parseDateTime(o.key)); confirm = false; onClose() }) { Text("Only this one") }
                TextButton({ Cal.deleteEvent(o.calendarId, o.uid); confirm = false; onClose() }) { Text(if (o.event.rrule != null) "All" else "Delete", color = MaterialTheme.colorScheme.error) }
            }
        },
        dismissButton = { TextButton({ confirm = false }) { Text("Cancel") } })
}

private data class RepeatOption(val label: String, val rule: String?)

private fun repeatOptions(start: LocalDate): List<RepeatOption> {
    val dow = Rule.DAYS.entries.first { it.value == start.dayOfWeek }.key
    val nth = (start.dayOfMonth - 1) / 7 + 1
    val last = start.plusWeeks(1).month != start.month
    return listOf(
        RepeatOption("Does not repeat", null),
        RepeatOption("Every day", "FREQ=DAILY"),
        RepeatOption("Every weekday (Mon–Fri)", "FREQ=DAILY;BYDAY=MO,TU,WE,TH,FR"),
        RepeatOption("Every week on ${dayName(start.dayOfWeek, false)}", "FREQ=WEEKLY;BYDAY=$dow"),
        RepeatOption("Every 2 weeks on ${dayName(start.dayOfWeek, false)}", "FREQ=WEEKLY;INTERVAL=2;BYDAY=$dow"),
        RepeatOption("Every month on day ${start.dayOfMonth}", "FREQ=MONTHLY;BYMONTHDAY=${start.dayOfMonth}"),
        RepeatOption("Every month on the ${ordinal(if (last) -1 else nth)} ${dayName(start.dayOfWeek, false)}", "FREQ=MONTHLY;BYDAY=${if (last) -1 else nth}$dow"),
        RepeatOption("Every year on ${start.format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()))}", "FREQ=YEARLY"),
    )
}

@Composable
fun EventEditor(target: EventTarget, onDone: () -> Unit) {
    val e0 = target.event
    val locals = remember { Cal.calendars().filter { it.meta.kind == "local" } }
    var title by remember { mutableStateOf(e0.title) }
    var allDay by remember { mutableStateOf(e0.allDay) }
    var start by remember { mutableStateOf(parseDateTime(e0.start)) }
    var end by remember { mutableStateOf(parseDateTime(e0.end).let { if (e0.allDay) it.minusDays(1) else it }) }   // all-day end shown inclusive
    var rule by remember { mutableStateOf(e0.rrule?.let { r -> Rule.parse(r)?.let { Rule(it.freq, it.interval, null, null, it.byDay, it.byMonthDay, it.byMonth).format() } }) }
    val r0 = e0.rrule?.let { Rule.parse(it) }
    var endMode by remember { mutableStateOf(when { r0?.count != null -> "count"; r0?.until != null -> "until"; else -> "never" }) }
    var count by remember { mutableStateOf((r0?.count ?: 10).toString()) }
    var until by remember { mutableStateOf(r0?.until?.toLocalDate() ?: start.toLocalDate().plusMonths(3)) }
    var calId by remember { mutableStateOf(target.calendarId ?: Daybreak.platform.home.let { lastCalendar() }.takeIf { id -> locals.any { it.id == id } } ?: locals.firstOrNull()?.id ?: Cal.DEFAULT_CALENDAR) }
    var alert by remember { mutableStateOf(e0.alarms.firstOrNull() ?: (if (target.uid == null && !e0.allDay) 10 else null)) }
    var location by remember { mutableStateOf(e0.location) }
    var notes by remember { mutableStateOf(e0.notes) }

    fun save() {
        val r = rule?.let { base -> Rule.parse(base)?.let { b ->
            Rule(b.freq, b.interval, if (endMode == "count") count.toIntOrNull()?.coerceAtLeast(1) else null,
                if (endMode == "until") until.atTime(23, 59) else null, b.byDay, b.byMonthDay, b.byMonth).format()
        } }
        val ev = e0.copy(title = title.trim().ifEmpty { "(no title)" }, allDay = allDay,
            start = if (allDay) start.toLocalDate().str() else start.str(),
            end = if (allDay) end.toLocalDate().plusDays(1).str() else (if (end.isAfter(start)) end else start.plusHours(1)).str(),
            tz = if (allDay) null else (e0.tz ?: ZoneId.systemDefault().id), rrule = r,
            exdates = if (r == e0.rrule) e0.exdates else emptyList(), alarms = listOfNotNull(alert), location = location.trim(), notes = notes.trim())
        val uid = target.uid ?: newId()
        if (target.calendarId != null && target.calendarId != calId) Cal.moveEvent(target.calendarId, calId, uid)
        Cal.saveEvent(calId, uid, ev)
        rememberCalendar(calId)
        onDone()
    }

    Sheet(onDone) {
        Text(if (target.uid == null) "New event" else "Edit event", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), label = { Text("Title") }, singleLine = true)
        Row(verticalAlignment = Alignment.CenterVertically) { Text("All day", Modifier.weight(1f)); Switch(allDay, { allDay = it }) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Starts", Modifier.width(56.dp))
            DateField(start.toLocalDate(), { d -> val len = ChronoUnit.MINUTES.between(start, end); start = d.atTime(start.toLocalTime()); end = start.plusMinutes(len) })
            if (!allDay) TimeField(start.toLocalTime(), { t -> val len = ChronoUnit.MINUTES.between(start, end); start = start.toLocalDate().atTime(t); end = start.plusMinutes(len) })
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Ends", Modifier.width(56.dp))
            DateField(end.toLocalDate(), { d -> end = d.atTime(end.toLocalTime()) })
            if (!allDay) TimeField(end.toLocalTime(), { t -> end = end.toLocalDate().atTime(t) })
        }
        if (!allDay && !end.isAfter(start)) Hint("The end is before the start; it will be set to an hour later.", color = MaterialTheme.colorScheme.error)
        Menu("Repeat", repeatOptions(start.toLocalDate()).firstOrNull { it.rule == rule }?.label ?: repeatText(rule, start.toLocalDate()),
            repeatOptions(start.toLocalDate()).map { it.label to { rule = it.rule } })
        if (rule != null) {
            Menu("Ends", mapOf("never" to "Never", "until" to "On a date", "count" to "After a number of times")[endMode]!!,
                listOf("Never" to { endMode = "never" }, "On a date" to { endMode = "until" }, "After a number of times" to { endMode = "count" }))
            if (endMode == "until") Row(verticalAlignment = Alignment.CenterVertically) { Text("Last day", Modifier.width(80.dp)); DateField(until, { until = it }) }
            if (endMode == "count") OutlinedTextField(count, { count = it.filter(Char::isDigit).take(4) }, Modifier.width(160.dp), label = { Text("Times") },
                singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        }
        Menu("Calendar", locals.firstOrNull { it.id == calId }?.meta?.name ?: "", locals.map { c -> c.meta.name to { calId = c.id } },
            dot = locals.firstOrNull { it.id == calId }?.meta?.color)
        Menu("Alert", alert?.let(::alertText) ?: "None",
            listOf("None" to { alert = null }) + listOf(0, 5, 10, 15, 30, 60, 120, 1440, 2880).map { m -> alertText(m) to { alert = m } })
        OutlinedTextField(location, { location = it }, Modifier.fillMaxWidth(), label = { Text("Location") }, singleLine = true)
        OutlinedTextField(notes, { notes = it }, Modifier.fillMaxWidth(), label = { Text("Notes") }, minLines = 2)
        if (e0.rrule != null && target.uid != null) Hint("Changes apply to every time this event repeats.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(::save) { Text("Save") }
            TextButton(onDone) { Text("Cancel") }
        }
    }
}

/** A label with the current choice; tap for the options. */
@Composable
fun Menu(label: String, current: String, options: List<Pair<String, () -> Unit>>, dot: String? = null) {
    var open by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(80.dp))
        Box {
            Row(Modifier.clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).clickable { open = true }
                .padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                dot?.let { ColorDot(hexColor(it)); Spacer(Modifier.width(8.dp)) }
                Text("$current  ▾")
            }
            DropdownMenu(open, { open = false }) { for ((t, f) in options) DropdownMenuItem({ Text(t) }, { open = false; f() }) }
        }
    }
}

private fun lastCalendar(): String? = java.io.File(Daybreak.platform.home, "local/last-calendar").takeIf { it.exists() }?.readText()?.trim()
private fun rememberCalendar(id: String) { java.io.File(Daybreak.platform.home, "local/last-calendar").apply { parentFile.mkdirs() }.writeText(id) }

// --- calendars: show/hide, colours, add, subscribe, import, export, delete

@Composable
fun CalendarsSheet(onDismiss: () -> Unit) {
    val version by Changes.version.collectAsState()
    val cals = remember(version) { Cal.calendars() }
    val scope = rememberCoroutineScope()
    var adding by remember { mutableStateOf<String?>(null) }     // "local" | "subscription"
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<String?>(null) }
    val platform = Daybreak.platform
    Sheet(onDismiss) {
        Text("Calendars", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        for (c in cals) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                var colors by remember { mutableStateOf(false) }
                Box {
                    ColorDot(hexColor(c.meta.color), 16, Modifier.clickable { colors = true })
                    DropdownMenu(colors, { colors = false }) {
                        Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (col in Cal.COLORS) ColorDot(hexColor(col), 22, Modifier.clickable { Cal.saveCalendar(c.id, c.meta.copy(color = col)); colors = false })
                        }
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(c.meta.name, fontWeight = FontWeight.SemiBold)
                    Hint(if (c.meta.kind == "subscription") "Subscribed · read-only" else "${Cal.events(c.id).size} events")
                }
                Switch(!c.meta.hidden, { Cal.saveCalendar(c.id, c.meta.copy(hidden = !it)) })
                var more by remember { mutableStateOf(false) }
                Box {
                    TextButton({ more = true }) { Text("⋯") }
                    DropdownMenu(more, { more = false }) {
                        if (c.meta.kind == "local") {
                            DropdownMenuItem({ Text("Import .ics file…") }, { more = false
                                platform.pickFile { f -> f?.let { (_, bytes) -> msg = runCatching { "Added ${Cal.importIcs(String(bytes), c.id)} items to ${c.meta.name}." }.getOrElse { "That file couldn't be read." } } } })
                            DropdownMenuItem({ Text("Export .ics file…") }, { more = false
                                platform.saveFile("${c.meta.name}.ics", "text/calendar", Cal.exportIcs(c.id).toByteArray()) { ok -> if (ok) msg = "Saved ${c.meta.name}.ics." } })
                        } else DropdownMenuItem({ Text("Refresh now") }, { more = false
                            scope.launch { val err = withContext(Dispatchers.IO) { Cal.refreshSubscriptions(0) }; msg = err[c.id]?.let { "Couldn't refresh: $it" } ?: "Refreshed." } })
                        if (c.id != Cal.DEFAULT_CALENDAR) DropdownMenuItem({ Text("Delete…", color = MaterialTheme.colorScheme.error) }, { more = false; deleting = c.id })
                    }
                }
            }
        }
        msg?.let { Hint(it) }
        HorizontalDivider()
        when (adding) {
            null -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({ adding = "local"; name = "" }) { Text("New calendar") }
                OutlinedButton({ adding = "subscription"; name = ""; url = "" }) { Text("Subscribe to a link") }
            }
            else -> {
                OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Name") }, singleLine = true)
                if (adding == "subscription") {
                    OutlinedTextField(url, { url = it.trim() }, Modifier.fillMaxWidth(), label = { Text("Calendar link (.ics or webcal://)") }, singleLine = true)
                    Hint("Google: calendar settings → “Secret address in iCal format”. iCloud: share the calendar → Public calendar. Outlook: Settings → Shared calendars → Publish → ICS link. Read-only; refreshed every few hours.")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button({
                        val id = Cal.createCalendar(name.ifBlank { if (adding == "subscription") "Subscribed" else "Calendar" }, Cal.COLORS[cals.size % Cal.COLORS.size], url.takeIf { adding == "subscription" })
                        if (adding == "subscription") scope.launch { val err = withContext(Dispatchers.IO) { Cal.refreshSubscriptions(0) }; msg = err[id]?.let { "Added, but the link couldn't be read yet: $it" } }
                        adding = null
                    }, enabled = name.isNotBlank() && (adding == "local" || url.startsWith("http") || url.startsWith("webcal"))) { Text("Add") }
                    TextButton({ adding = null }) { Text("Cancel") }
                }
            }
        }
        TextButton(onDismiss, Modifier.align(Alignment.End)) { Text("Done") }
    }
    deleting?.let { id ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Delete “${cals.firstOrNull { it.id == id }?.meta?.name}”?") },
            text = { Text("Its events are removed from all your devices.") },
            confirmButton = { TextButton({ Cal.deleteCalendar(id); deleting = null }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton({ deleting = null }) { Text("Cancel") } })
    }
}
