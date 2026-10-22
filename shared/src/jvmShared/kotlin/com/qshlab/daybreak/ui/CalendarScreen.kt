package com.qshlab.daybreak.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qshlab.daybreak.cal.Cal
import com.qshlab.daybreak.cal.Occurrence
import com.qshlab.daybreak.cal.TaskRef
import com.qshlab.daybreak.cal.dueAt
import com.qshlab.daybreak.cal.dueDate
import com.qshlab.daybreak.data.Changes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.Locale

enum class CalView { Day, Week, Month }

@Composable
fun CalendarScreen(onSettings: (() -> Unit)?, startView: CalView = CalView.Month) {
    val version by Changes.version.collectAsState()
    var view by rememberSaveable { mutableStateOf(startView) }
    var anchor by remember { mutableStateOf(LocalDate.now()) }
    var editing by remember { mutableStateOf<EventTarget?>(null) }
    var showing by remember { mutableStateOf<Occurrence?>(null) }
    var task by remember { mutableStateOf<TaskRef?>(null) }
    var newTask by remember { mutableStateOf<TaskRef?>(null) }
    var managing by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { Cal.refreshSubscriptions() } }

    val (from, to) = when (view) {
        CalView.Month -> anchor.withDayOfMonth(1).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).let { it to it.plusWeeks(6) }
        CalView.Week -> anchor.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).let { it to it.plusDays(7) }
        CalView.Day -> anchor to anchor.plusDays(1)
    }
    val occ = remember(version, from, to) { Cal.occurrences(from, to) }
    val tasks = remember(version, from, to) { Cal.tasks().filter { t -> t.task.dueDate()?.let { !it.isBefore(from) && it.isBefore(to) } == true } }
    val title = when (view) {
        CalView.Month -> anchor.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault()))
        CalView.Week -> "${from.format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()))} – ${to.minusDays(1).format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault()))}"
        CalView.Day -> dateText(anchor, withYear = true)
    }
    fun step(n: Long) { anchor = when (view) { CalView.Month -> anchor.plusMonths(n); CalView.Week -> anchor.plusWeeks(n); CalView.Day -> anchor.plusDays(n) } }
    fun newAt(t: LocalDateTime) { editing = EventTarget.new(t) }

    TabScreen(title, onSettings = onSettings, actions = {
        IconButton({ managing = true }) { Icon(Icons.Default.Layers, "Calendars") }
        IconButton({ newAt((if (anchor == LocalDate.now()) LocalDateTime.now() else anchor.atTime(9, 0)).roundUpToHour()) }) { Icon(Icons.Default.Add, "New event") }
    }) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(3.dp)) {
                    for (v in CalView.entries) Text(v.name, Modifier.clip(RoundedCornerShape(8.dp))
                        .background(if (view == v) MaterialTheme.colorScheme.surfaceContainer else Color.Transparent)
                        .clickable { view = v }.padding(horizontal = 12.dp, vertical = 6.dp), fontSize = 13.sp,
                        fontWeight = if (view == v) FontWeight.SemiBold else FontWeight.Normal)
                }
                Spacer(Modifier.weight(1f))
                IconButton({ step(-1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous") }
                TextButton({ anchor = LocalDate.now() }) { Text("Today") }
                IconButton({ step(1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next") }
            }
            when (view) {
                // Tapping a day in the month adds a task due then (the Day view is one tap away at the top).
                CalView.Month -> MonthGrid(anchor, from, occ, tasks, onDay = { d ->
                    newTask = TaskRef(com.qshlab.daybreak.cal.Cal.DEFAULT_LIST, com.qshlab.daybreak.cal.newId(), com.qshlab.daybreak.cal.Task("", due = d.toString()))
                }, onOpen = { showing = it }, onTask = { task = it })
                CalView.Week -> TimeGrid((0 until 7).map { from.plusDays(it.toLong()) }, occ, tasks, ::newAt, { showing = it }, { task = it }, onDay = { anchor = it; view = CalView.Day })
                CalView.Day -> TimeGrid(listOf(anchor), occ, tasks, ::newAt, { showing = it }, { task = it })
            }
        }
    }
    showing?.let { o -> EventDetails(o, onClose = { showing = null }, onEdit = { showing = null; editing = EventTarget.of(o) }) }
    editing?.let { EventEditor(it, onDone = { editing = null }) }
    task?.let { TaskEditor(it, onDone = { task = null }) }
    newTask?.let { TaskEditor(it, onDone = { newTask = null }, isNew = true) }
    if (managing) CalendarsSheet(onDismiss = { managing = false })
}

// --- month

@Composable
private fun MonthGrid(anchor: LocalDate, first: LocalDate, occ: List<Occurrence>, tasks: List<TaskRef>,
                      onDay: (LocalDate) -> Unit, onOpen: (Occurrence) -> Unit, onTask: (TaskRef) -> Unit) {
    val line = MaterialTheme.colorScheme.outlineVariant
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth > 600.dp
        val rows = 6
        val perCell = ((maxHeight.value - 28) / rows / 19).toInt().coerceIn(1, 6) - 1
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().height(28.dp)) {
                for (i in 0 until 7) Text(dayName(first.plusDays(i.toLong()).dayOfWeek), Modifier.weight(1f).padding(top = 6.dp),
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
            for (w in 0 until rows) Row(Modifier.fillMaxWidth().weight(1f)) {
                for (i in 0 until 7) {
                    val d = first.plusDays((w * 7 + i).toLong())
                    val items = occ.filter { o -> !o.start.toLocalDate().isAfter(d) && (o.end.toLocalDate().isAfter(d) || (o.start.toLocalDate() == d)) && !(o.allDay && !o.end.toLocalDate().isAfter(d)) }
                    val due = tasks.filter { it.task.dueDate() == d }
                    Column(Modifier.weight(1f).fillMaxHeight().border(0.5.dp, line).clickable { onDay(d) }.padding(2.dp)) {
                        val today = d == LocalDate.now()
                        Box(Modifier.align(Alignment.CenterHorizontally).size(22.dp).clip(CircleShape)
                            .background(if (today) MaterialTheme.colorScheme.primary else Color.Transparent), contentAlignment = Alignment.Center) {
                            Text("${d.dayOfMonth}", fontSize = 12.sp, fontWeight = if (today) FontWeight.Bold else FontWeight.Normal,
                                color = when { today -> MaterialTheme.colorScheme.onPrimary; d.month != anchor.month -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f); else -> MaterialTheme.colorScheme.onSurface })
                        }
                        val all = items.map { it as Any } + due
                        for (x in all.take(perCell)) when (x) {
                            is Occurrence -> MonthChip(if (wide && !x.allDay) timeText(x.start.toLocalTime()) + " " + x.event.title else x.event.title, hexColor(x.color), x.allDay) { onOpen(x) }
                            is TaskRef -> MonthChip("☐ " + x.task.title, MaterialTheme.colorScheme.onSurfaceVariant, false, done = x.task.done) { onTask(x) }
                        }
                        if (all.size > perCell) Text("+${all.size - perCell}", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 3.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun MonthChip(text: String, color: Color, filled: Boolean, done: Boolean = false, onClick: () -> Unit) {
    Text(text, Modifier.fillMaxWidth().padding(vertical = 1.dp).clip(RoundedCornerShape(4.dp))
        .background(if (filled) color.copy(alpha = 0.85f) else color.copy(alpha = 0.12f)).clickable(onClick = onClick).padding(horizontal = 3.dp),
        fontSize = 10.sp, lineHeight = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
        color = if (filled) Color.White else MaterialTheme.colorScheme.onSurface, textDecoration = if (done) TextDecoration.LineThrough else null)
}

// --- week / day

private val HOUR = 52.dp

@Composable
private fun TimeGrid(days: List<LocalDate>, occ: List<Occurrence>, tasks: List<TaskRef>, onSlot: (LocalDateTime) -> Unit,
                     onOpen: (Occurrence) -> Unit, onTask: (TaskRef) -> Unit, onDay: ((LocalDate) -> Unit)? = null) {
    val line = MaterialTheme.colorScheme.outlineVariant
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    // Open at 7:30, or earlier when something starts earlier (or it's earlier now, today).
    val firstHour = (occ.filter { !it.allDay && it.start.toLocalDate() in days }.minOfOrNull { it.start.hour + it.start.minute / 60f } ?: 7.5f)
        .let { if (LocalDate.now() in days) minOf(it, LocalTime.now().hour.toFloat()) else it }.let { minOf(7.5f, it - 0.5f).coerceAtLeast(0f) }
    LaunchedEffect(days.first()) { scroll.scrollTo(with(density) { (HOUR * firstHour).roundToPx() }) }
    Column(Modifier.fillMaxSize()) {
        // day headers
        Row(Modifier.fillMaxWidth()) {
            Spacer(Modifier.width(48.dp))
            for (d in days) {
                val today = d == LocalDate.now()
                Column(Modifier.weight(1f).clickable(enabled = onDay != null) { onDay?.invoke(d) }.padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(dayName(d.dayOfWeek), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Box(Modifier.size(28.dp).clip(CircleShape).background(if (today) MaterialTheme.colorScheme.primary else Color.Transparent), contentAlignment = Alignment.Center) {
                        Text("${d.dayOfMonth}", fontWeight = FontWeight.SemiBold, color = if (today) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        }
        // all-day events and tasks due
        val allDayRows = days.map { d ->
            occ.filter { it.allDay && !it.start.toLocalDate().isAfter(d) && it.end.toLocalDate().isAfter(d) }.map { it as Any } +
                tasks.filter { it.task.dueDate() == d }
        }
        if (allDayRows.any { it.isNotEmpty() }) Row(Modifier.fillMaxWidth().border(0.5.dp, line)) {
            Text("all-day", Modifier.width(48.dp).padding(4.dp), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            for (items in allDayRows) Column(Modifier.weight(1f).padding(1.dp)) {
                for (x in items.take(3)) when (x) {
                    is Occurrence -> MonthChip(x.event.title, hexColor(x.color), true) { onOpen(x) }
                    is TaskRef -> MonthChip((if (x.task.done) "☑ " else "☐ ") + x.task.title + (x.task.dueAt()?.takeIf { (x.task.due?.length ?: 0) > 10 }?.let { " " + timeText(it.toLocalTime()) } ?: ""),
                        MaterialTheme.colorScheme.onSurfaceVariant, false, done = x.task.done) { onTask(x) }
                }
                if (items.size > 3) Text("+${items.size - 3}", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        // the hours
        Row(Modifier.fillMaxSize().verticalScroll(scroll)) {
            Column(Modifier.width(48.dp)) {
                for (h in 0 until 24) Box(Modifier.height(HOUR).fillMaxWidth()) {
                    if (h > 0) Text(timeText(LocalTime.of(h, 0)).replace(":00", ""), Modifier.align(Alignment.TopEnd).offset(y = (-7).dp).padding(end = 6.dp),
                        fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            for (d in days) {
                val dayStart = d.atStartOfDay(); val dayEnd = dayStart.plusDays(1)
                val timed = occ.filter { !it.allDay && it.start.isBefore(dayEnd) && (it.end.isAfter(dayStart) || it.start == dayStart) }
                Box(Modifier.weight(1f).height(HOUR * 24).border(0.25.dp, line).pointerInput(d) {
                    detectTapGestures { p -> onSlot(dayStart.plusMinutes(((p.y / HOUR.toPx()) * 60).toLong() / 30 * 30)) }
                }) {
                    for (h in 1 until 24) Box(Modifier.fillMaxWidth().offset(y = HOUR * h).height(0.5.dp).background(line))
                    for ((o, col, cols) in layoutDay(timed, dayStart, dayEnd)) {
                        val s = maxOf(o.start, dayStart); val e = minOf(maxOf(o.end, o.start.plusMinutes(20)), dayEnd)
                        val top = HOUR * (ChronoUnit.MINUTES.between(dayStart, s) / 60f)
                        val h = HOUR * (ChronoUnit.MINUTES.between(s, e) / 60f)
                        BoxWithConstraints(Modifier.fillMaxSize()) {
                            val w = maxWidth / cols
                            val c = hexColor(o.color)
                            Column(Modifier.offset(x = w * col, y = top).width(w - 1.dp).height(h - 1.dp).clip(RoundedCornerShape(4.dp))
                                .background(c.copy(alpha = 0.18f)).border(width = 0.dp, color = Color.Transparent).clickable { onOpen(o) }) {
                                Row(Modifier.fillMaxSize()) {
                                    Box(Modifier.width(3.dp).fillMaxHeight().background(c))
                                    Column(Modifier.padding(horizontal = 3.dp, vertical = 1.dp)) {
                                        Text(o.event.title, fontSize = 11.sp, lineHeight = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        if (h > 34.dp) Text(timeText(o.start.toLocalTime()), fontSize = 10.sp, lineHeight = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                    if (d == LocalDate.now()) {
                        val nowY = HOUR * (ChronoUnit.MINUTES.between(dayStart, LocalDateTime.now()) / 60f)
                        Box(Modifier.fillMaxWidth().offset(y = nowY).height(2.dp).background(Danger))
                    }
                }
            }
        }
    }
}

/** Side by side when events overlap: (event, column, columns in its group). */
private fun layoutDay(items: List<Occurrence>, dayStart: LocalDateTime, dayEnd: LocalDateTime): List<Triple<Occurrence, Int, Int>> {
    val sorted = items.sortedWith(compareBy({ maxOf(it.start, dayStart) }, { -ChronoUnit.MINUTES.between(it.start, it.end) }))
    val out = mutableListOf<Triple<Occurrence, Int, Int>>()
    var group = mutableListOf<Pair<Occurrence, Int>>(); val ends = mutableListOf<LocalDateTime>(); var groupEnd = LocalDateTime.MIN
    fun flush() { val n = ends.size.coerceAtLeast(1); group.forEach { (o, c) -> out += Triple(o, c, n) }; group = mutableListOf(); ends.clear() }
    for (o in sorted) {
        val s = maxOf(o.start, dayStart); val e = minOf(maxOf(o.end, o.start.plusMinutes(20)), dayEnd)
        if (group.isNotEmpty() && !s.isBefore(groupEnd)) flush()
        val col = ends.indexOfFirst { !it.isAfter(s) }.takeIf { it >= 0 } ?: ends.size.also { ends += e }
        ends[col] = e
        group += o to col
        groupEnd = if (group.size == 1) e else maxOf(groupEnd, e)
    }
    flush()
    return out
}
