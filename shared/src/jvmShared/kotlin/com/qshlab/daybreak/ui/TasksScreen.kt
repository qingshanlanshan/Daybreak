package com.qshlab.daybreak.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Event
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qshlab.daybreak.cal.Cal
import com.qshlab.daybreak.cal.Task
import com.qshlab.daybreak.cal.TaskRef
import com.qshlab.daybreak.cal.dueAt
import com.qshlab.daybreak.cal.dueDate
import com.qshlab.daybreak.cal.newId
import com.qshlab.daybreak.cal.overdue
import com.qshlab.daybreak.cal.parseDateTime
import com.qshlab.daybreak.cal.str
import com.qshlab.daybreak.data.Changes
import java.time.LocalDate
import java.time.LocalTime

/** The views on the left (Mac) / chips on top (phone): three smart views, then the lists. */
private const val TODAY = "@today"
private const val UPCOMING = "@upcoming"
private const val ALL = "@all"

@Composable
fun TasksScreen(onSettings: (() -> Unit)?) {
    val version by Changes.version.collectAsState()
    val lists = remember(version) { Cal.lists() }
    val all = remember(version) { Cal.tasks() }
    var view by rememberSaveable { mutableStateOf(TODAY) }
    var editing by remember { mutableStateOf<TaskRef?>(null) }
    var newList by remember { mutableStateOf(false) }
    var showDone by remember { mutableStateOf(false) }
    val today = LocalDate.now()
    val listOf = lists.associateBy { it.id }
    val shown = when (view) {
        TODAY -> all.filter { t -> t.task.dueDate()?.let { !it.isAfter(today) } == true }
        UPCOMING -> all.filter { it.task.due != null }
        ALL -> all
        else -> all.filter { it.list == view }
    }.sortedWith(compareBy({ it.task.dueAt() == null }, { it.task.dueAt() }, { -it.task.created }))
    val open = shown.filter { !it.task.done }
    val done = shown.filter { it.task.done }.sortedByDescending { it.task.doneAt ?: 0.0 }
    val title = when (view) { TODAY -> "Today"; UPCOMING -> "Upcoming"; ALL -> "All tasks"; else -> listOf[view]?.meta?.name ?: "Tasks" }
    val views = listOf(TODAY to "Today", UPCOMING to "Upcoming", ALL to "All") + lists.map { it.id to it.meta.name }

    BoxWithConstraints {
        val wide = maxWidth > 720.dp
        TabScreen(if (wide) "Tasks" else title, onSettings = onSettings) {
            Row(Modifier.fillMaxSize()) {
                if (wide) Column(Modifier.width(220.dp).fillMaxHeight().padding(8.dp)) {
                    for ((id, name) in views) {
                        val count = when (id) { TODAY -> all.count { t -> !t.task.done && t.task.dueDate()?.let { !it.isAfter(today) } == true }; UPCOMING, ALL -> null; else -> all.count { it.list == id && !it.task.done } }
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(if (view == id) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                            .clickable { view = id }.padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                            listOf[id]?.let { ColorDot(hexColor(it.meta.color)); Spacer(Modifier.width(8.dp)) }
                            Text(name, Modifier.weight(1f), fontWeight = if (view == id) FontWeight.SemiBold else FontWeight.Normal)
                            count?.takeIf { it > 0 }?.let { Hint("$it") }
                        }
                        if (id == ALL) Spacer(Modifier.padding(4.dp))
                    }
                    TextButton({ newList = true }) { Text("+ New list") }
                }
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    if (!wide) Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for ((id, name) in views) Text(name, Modifier.clip(RoundedCornerShape(16.dp))
                            .background(if (view == id) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh)
                            .clickable { view = id }.padding(horizontal = 12.dp, vertical = 6.dp), fontSize = 13.sp)
                        Text("+ List", Modifier.clip(RoundedCornerShape(16.dp)).clickable { newList = true }.padding(horizontal = 12.dp, vertical = 6.dp),
                            fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                    }
                    QuickAdd(view)
                    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                        if (open.isEmpty()) item {
                            Hint(when (view) { TODAY -> "Nothing due today."; UPCOMING -> "No tasks with a date."; else -> "No tasks here yet." }, Modifier.padding(12.dp))
                        }
                        items(open, key = { it.list + "/" + it.uid }) { t -> TaskRow(t, if (view.startsWith("@")) listOf[t.list]?.meta?.color else null) { editing = t } }
                        if (done.isNotEmpty()) item {
                            TextButton({ showDone = !showDone }) { Text((if (showDone) "Hide" else "Show") + " completed (${done.size})") }
                        }
                        if (showDone) items(done, key = { "d" + it.list + "/" + it.uid }) { t -> TaskRow(t, null) { editing = t } }
                    }
                }
            }
        }
    }
    editing?.let { TaskEditor(it, onDone = { editing = null }) }
    if (newList) {
        var name by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { newList = false }, title = { Text("New list") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Name") }) },
            confirmButton = { TextButton({ view = Cal.createList(name, Cal.COLORS[(lists.size + 1) % Cal.COLORS.size]); newList = false }, enabled = name.isNotBlank()) { Text("Create") } },
            dismissButton = { TextButton({ newList = false }) { Text("Cancel") } })
    }
}

/** Type and press Enter. Without a date it goes to the default list (Reminders), unless you're in a list. */
@Composable
private fun QuickAdd(view: String) {
    var text by remember { mutableStateOf("") }
    var due by remember { mutableStateOf<LocalDate?>(if (view == TODAY) LocalDate.now() else null) }
    var picking by remember { mutableStateOf(false) }
    fun add() {
        if (text.isBlank()) return
        val list = if (!view.startsWith("@")) view else Cal.DEFAULT_LIST
        Cal.saveTask(list, newId(), Task(text.trim(), due = due?.str()))
        text = ""
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(text, { text = it }, Modifier.weight(1f), placeholder = { Text(if (view.startsWith("@") && due == null) "Add a reminder or note…" else "Add a task…") },
            singleLine = true, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { add() }))
        Spacer(Modifier.width(6.dp))
        if (due != null) Chip(relativeDay(due!!)) { picking = true } else IconButton({ picking = true }) { Icon(Icons.Default.Event, "Due date") }
        Button(::add, enabled = text.isNotBlank(), modifier = Modifier.padding(start = 6.dp)) { Text("Add") }
    }
    if (picking) DueDialog(due, onDone = { d -> due = d; picking = false })
}

@Composable
private fun DueDialog(current: LocalDate?, onDone: (LocalDate?) -> Unit) {
    AlertDialog(onDismissRequest = { onDone(current) }, title = { Text("Due") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((label, d) in listOf("Today" to LocalDate.now(), "Tomorrow" to LocalDate.now().plusDays(1), "Next week" to LocalDate.now().plusWeeks(1)))
                    Text(label, Modifier.fillMaxWidth().clickable { onDone(d) }.padding(vertical = 8.dp))
                DateField(current ?: LocalDate.now(), { onDone(it) })
                Text("No date (a reminder or note)", Modifier.fillMaxWidth().clickable { onDone(null) }.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.primary)
            }
        }, confirmButton = {})
}

@Composable
fun TaskRow(t: TaskRef, listColor: String?, onOpen: () -> Unit) {
    val overdue = t.task.overdue()
    Row(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(22.dp).clip(CircleShape).border(1.5.dp, if (t.task.done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, CircleShape)
            .background(if (t.task.done) MaterialTheme.colorScheme.primary else Color.Transparent).clickable { Cal.setDone(t, !t.task.done) }, contentAlignment = Alignment.Center) {
            if (t.task.done) Icon(Icons.Default.Check, "Done", Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onPrimary)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(t.task.title, textDecoration = if (t.task.done) TextDecoration.LineThrough else null,
                color = if (t.task.done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val bits = listOfNotNull(
                t.task.dueAt()?.let { relativeDay(it.toLocalDate()) + if ((t.task.due?.length ?: 0) > 10) " " + timeText(it.toLocalTime()) else "" },
                t.task.remind?.let { "🔔 " + timeText(parseDateTime(it).toLocalTime()) },
                t.task.notes.takeIf { it.isNotBlank() }?.lines()?.first(),
            )
            if (bits.isNotEmpty()) Text(bits.joinToString(" · "), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        listColor?.let { ColorDot(hexColor(it), 8) }
    }
}

@Composable
fun TaskEditor(ref: TaskRef, onDone: () -> Unit) {
    val lists = remember { Cal.lists() }
    val t0 = ref.task
    var title by remember { mutableStateOf(t0.title) }
    var notes by remember { mutableStateOf(t0.notes) }
    var hasDue by remember { mutableStateOf(t0.due != null) }
    var dueDate by remember { mutableStateOf(t0.dueDate() ?: LocalDate.now()) }
    var dueTime by remember { mutableStateOf(t0.due?.takeIf { it.length > 10 }?.let { parseDateTime(it).toLocalTime() }) }
    var remind by remember { mutableStateOf(t0.remind?.let { parseDateTime(it) }) }
    var list by remember { mutableStateOf(ref.list) }
    var confirm by remember { mutableStateOf(false) }
    fun save() {
        val due = if (hasDue) dueTime?.let { dueDate.atTime(it).str() } ?: dueDate.str() else null
        Cal.saveTask(ref.list, ref.uid, t0.copy(title = title.trim().ifEmpty { "(untitled)" }, notes = notes.trim(), due = due, remind = remind?.str()))
        Cal.moveTask(ref.list, list, ref.uid)
        onDone()
    }
    Sheet(onDone) {
        Text("Task", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), label = { Text("Title") })
        OutlinedTextField(notes, { notes = it }, Modifier.fillMaxWidth(), label = { Text("Notes") }, minLines = 2)
        Row(verticalAlignment = Alignment.CenterVertically) { Text("Due date", Modifier.weight(1f)); Switch(hasDue, { hasDue = it }) }
        if (hasDue) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DateField(dueDate, { dueDate = it })
            if (dueTime != null) { TimeField(dueTime!!, { dueTime = it }); TextButton({ dueTime = null }) { Text("No time") } }
            else TextButton({ dueTime = LocalTime.of(17, 0) }) { Text("Add a time") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) { Text("Remind me", Modifier.weight(1f)); Switch(remind != null, { on ->
            remind = if (on) (if (hasDue) dueDate.atTime(dueTime?.minusHours(1) ?: LocalTime.of(9, 0)) else java.time.LocalDateTime.now().plusHours(1).roundUpToHour()) else null }) }
        remind?.let { r -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { DateField(r.toLocalDate(), { remind = it.atTime(r.toLocalTime()) }); TimeField(r.toLocalTime(), { remind = r.toLocalDate().atTime(it) }) } }
        Menu("List", lists.firstOrNull { it.id == list }?.meta?.name ?: "", lists.map { l -> l.meta.name to { list = l.id } }, dot = lists.firstOrNull { it.id == list }?.meta?.color)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(::save) { Text("Save") }
            TextButton({ Cal.setDone(ref, !t0.done); onDone() }) { Text(if (t0.done) "Mark not done" else "Mark done") }
            Spacer(Modifier.weight(1f))
            TextButton({ confirm = true }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
        }
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("Delete “${t0.title}”?") },
        confirmButton = { TextButton({ Cal.deleteTask(ref.list, ref.uid); confirm = false; onDone() }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton({ confirm = false }) { Text("Cancel") } })
}
