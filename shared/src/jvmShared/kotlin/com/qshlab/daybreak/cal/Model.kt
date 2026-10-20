package com.qshlab.daybreak.cal

import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/*
 * Calendar data, as plain JSON files in the data folder (they sync file by file):
 *   calendars/<id>/calendar.json          a calendar: name, color, local or subscribed (.ics link)
 *   calendars/<id>/events/<uid>.json      an event
 *   tasks/<list>/list.json                a task list ("reminders" is the default list)
 *   tasks/<list>/<uid>.json               a task
 * Times: all-day events use dates ("2026-10-21", end exclusive); timed events use local date-times
 * ("2026-10-21T10:00") in the event's time zone `tz` (null: whatever zone the device is in).
 */

@Serializable
data class CalendarMeta(
    val name: String,
    val color: String = "#3F51D1",
    val kind: String = "local",          // "local" | "subscription"
    val url: String? = null,             // subscription: the .ics link
    val hidden: Boolean = false,
    val created: Double = 0.0,
)

@Serializable
data class Event(
    val title: String,
    val start: String,
    val end: String,
    val allDay: Boolean = false,
    val tz: String? = null,
    val location: String = "",
    val notes: String = "",
    val rrule: String? = null,           // RFC 5545 RRULE value, e.g. "FREQ=WEEKLY;BYDAY=MO,WE"
    val exdates: List<String> = emptyList(),   // skipped occurrences (their start, same format as start)
    val alarms: List<Int> = emptyList(),       // minutes before start
    val created: Double = 0.0,
    val updated: Double = 0.0,
)

@Serializable
data class TaskList(val name: String, val color: String = "#3F51D1", val isDefault: Boolean = false, val created: Double = 0.0)

@Serializable
data class Task(
    val title: String,
    val notes: String = "",
    val due: String? = null,             // "2026-10-25" or "2026-10-25T17:00"; null: a note / reminder without a deadline
    val remind: String? = null,          // "2026-10-25T09:00": notify then
    val done: Boolean = false,
    val doneAt: Double? = null,
    val created: Double = 0.0,
    val updated: Double = 0.0,
)

/** One appearance of an event on the calendar, in the device's time zone. */
data class Occurrence(
    val calendarId: String, val uid: String, val event: Event, val color: String, val readOnly: Boolean,
    val start: LocalDateTime, val end: LocalDateTime, val allDay: Boolean,
    /** The occurrence's original start, in the event's own format (for "skip this one"). */
    val key: String,
)

data class TaskRef(val list: String, val uid: String, val task: Task)

// --- time helpers

val DATE: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE
val DATE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")

fun zoneOf(tz: String?): ZoneId = tz?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.systemDefault()

fun parseDate(s: String): LocalDate = LocalDate.parse(s.take(10))
fun parseDateTime(s: String): LocalDateTime = if (s.length <= 10) parseDate(s).atStartOfDay() else LocalDateTime.parse(s.take(16), DATE_TIME)
fun LocalDate.str(): String = format(DATE)
fun LocalDateTime.str(): String = format(DATE_TIME)

/** The event's local time in its own zone → the device's zone. */
fun toDevice(t: LocalDateTime, tz: String?): LocalDateTime =
    if (tz == null) t else t.atZone(zoneOf(tz)).withZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime()

/** A task's due moment: a date-only due counts as the end of that day. */
fun Task.dueAt(): LocalDateTime? = due?.let { if (it.length <= 10) parseDate(it).atTime(LocalTime.of(23, 59)) else parseDateTime(it) }
fun Task.dueDate(): LocalDate? = due?.let { parseDate(it) }
fun Task.overdue(now: LocalDateTime = LocalDateTime.now()) = !done && dueAt()?.isBefore(now) == true

fun newId(): String = java.util.UUID.randomUUID().toString().replace("-", "").take(16)
