package com.qshlab.daybreak.notify

import com.qshlab.daybreak.cal.Cal
import com.qshlab.daybreak.cal.parseDateTime
import com.qshlab.daybreak.ui.timeText
import java.time.LocalDateTime

/** Something to notify about at [at] (device time). */
data class Alert(val id: String, val at: LocalDateTime, val title: String, val body: String)

/** Event alerts and task reminders, from the calendar data. Each device notifies on its own. */
object Reminders {
    fun between(from: LocalDateTime, to: LocalDateTime): List<Alert> {
        val out = mutableListOf<Alert>()
        for (o in Cal.occurrences(from.toLocalDate(), to.toLocalDate().plusDays(3))) {
            for (m in o.event.alarms) {
                val at = (if (o.allDay) o.start.withHour(9) else o.start).minusMinutes(m.toLong())
                if (at.isBefore(from) || !at.isBefore(to)) continue
                val whenText = if (o.allDay) "All day" else timeText(o.start.toLocalTime()) + (if (m > 0) " (in ${if (m < 60) "$m min" else "${m / 60} h"})" else "")
                out += Alert("e/${o.calendarId}/${o.uid}/${o.key}/$m", at, o.event.title, listOf(whenText, o.event.location).filter { it.isNotBlank() }.joinToString(" · "))
            }
        }
        for (t in Cal.tasks()) {
            if (t.task.done) continue
            val at = t.task.remind?.let { runCatching { parseDateTime(it) }.getOrNull() } ?: continue
            if (at.isBefore(from) || !at.isBefore(to)) continue
            out += Alert("t/${t.list}/${t.uid}/${t.task.remind}", at, t.task.title, t.task.notes.lines().firstOrNull().orEmpty().ifBlank { "Reminder" })
        }
        return out.sortedBy { it.at }
    }
}
