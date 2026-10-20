package com.qshlab.daybreak.cal

import com.qshlab.daybreak.Daybreak
import com.qshlab.daybreak.data.Changes
import com.qshlab.daybreak.data.now
import com.qshlab.daybreak.data.readJson
import com.qshlab.daybreak.data.writeAtomic
import com.qshlab.daybreak.data.writeJson
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

data class CalendarInfo(val id: String, val meta: CalendarMeta)
data class ListInfo(val id: String, val meta: TaskList)

/** Calendars, events, task lists and tasks on disk (see Model.kt), with an in-memory copy. */
object Cal {
    const val DEFAULT_CALENDAR = "personal"
    const val DEFAULT_LIST = "reminders"
    val COLORS = listOf("#3F51D1", "#E0533D", "#2E9E6B", "#E59A1F", "#8E4CC9", "#1F8FB8", "#C2477F", "#6B7D3A")

    private val home get() = Daybreak.platform.home
    private val calDir get() = File(home, "calendars")
    private val taskDir get() = File(home, "tasks")
    private val subDir get() = File(home, "local/subscriptions")

    @Volatile private var loadedAt = -1
    private var cals = listOf<CalendarInfo>()
    private var events = mapOf<String, List<Pair<String, Event>>>()

    private fun changed() { Changes.bump(); Daybreak.sync.kickSoon() }

    fun ensureDefaults() {
        if (!File(calDir, "$DEFAULT_CALENDAR/calendar.json").exists() && calendars().none { it.meta.kind == "local" })
            File(calDir, "$DEFAULT_CALENDAR/calendar.json").writeJson(CalendarMeta("Personal", COLORS[0], created = now()))
        if (!File(taskDir, "$DEFAULT_LIST/list.json").exists())
            File(taskDir, "$DEFAULT_LIST/list.json").writeJson(TaskList("Reminders", COLORS[3], isDefault = true, created = now()))
    }

    @Synchronized
    private fun load() {
        val v = Changes.version.value
        if (v == loadedAt) return
        cals = calDir.listFiles().orEmpty().mapNotNull { d -> File(d, "calendar.json").readJson<CalendarMeta>()?.let { CalendarInfo(d.name, it) } }
            .sortedWith(compareBy({ it.meta.kind != "local" }, { it.id != DEFAULT_CALENDAR }, { it.meta.name.lowercase() }))
        events = cals.associate { c ->
            c.id to if (c.meta.kind == "subscription") subscribed(c.id)
            else File(calDir, "${c.id}/events").listFiles().orEmpty().filter { it.name.endsWith(".json") }
                .mapNotNull { f -> f.readJson<Event>()?.let { f.name.removeSuffix(".json") to it } }
        }
        loadedAt = v
    }

    // --- calendars

    fun calendars(): List<CalendarInfo> { load(); return cals }
    fun calendar(id: String) = calendars().firstOrNull { it.id == id }

    fun createCalendar(name: String, color: String, url: String? = null): String {
        val id = newId()
        File(calDir, "$id/calendar.json").writeJson(CalendarMeta(name.trim(), color, if (url != null) "subscription" else "local", url?.trim(), created = now()))
        changed()
        return id
    }

    fun saveCalendar(id: String, meta: CalendarMeta) { File(calDir, "$id/calendar.json").writeJson(meta); changed() }

    /** Moves the calendar to this device's trash (its deletion syncs to the others). */
    fun deleteCalendar(id: String) {
        val trash = File(home, "local/trash").apply { mkdirs() }
        File(calDir, id).renameTo(File(trash, "calendar-$id-${System.currentTimeMillis()}"))
        File(subDir, "$id.ics").delete()
        changed()
    }

    // --- events

    fun events(calendarId: String): List<Pair<String, Event>> { load(); return events[calendarId].orEmpty() }

    fun saveEvent(calendarId: String, uid: String, e: Event) {
        File(calDir, "$calendarId/events/$uid.json").writeJson(e.copy(updated = now(), created = e.created.takeIf { it > 0 } ?: now()))
        changed()
    }

    fun deleteEvent(calendarId: String, uid: String) { File(calDir, "$calendarId/events/$uid.json").delete(); changed() }

    /** Removes one appearance of a repeating event (the rest stay). */
    fun skipOccurrence(calendarId: String, uid: String, original: LocalDateTime) {
        val e = events(calendarId).firstOrNull { it.first == uid }?.second ?: return
        saveEvent(calendarId, uid, e.copy(exdates = e.exdates + (if (e.allDay) original.toLocalDate().str() else original.str())))
    }

    /** Ends a repeating event before [original] (for "this and following" changes). */
    fun endSeriesBefore(calendarId: String, uid: String, original: LocalDateTime) {
        val e = events(calendarId).firstOrNull { it.first == uid }?.second ?: return
        val r = e.rrule?.let { Rule.parse(it, e.tz) } ?: return
        val until = Rule(r.freq, r.interval, null, original.minusSeconds(1), r.byDay, r.byMonthDay, r.byMonth)
        saveEvent(calendarId, uid, e.copy(rrule = until.format()))
    }

    fun moveEvent(from: String, to: String, uid: String) {
        if (from == to) return
        val f = File(calDir, "$from/events/$uid.json")
        File(calDir, "$to/events").mkdirs()
        f.renameTo(File(calDir, "$to/events/$uid.json")); changed()
    }

    /** Everything on the calendar overlapping [from, to) (device time), visible calendars only. */
    fun occurrences(from: LocalDate, to: LocalDate): List<Occurrence> {
        load()
        val a = from.atStartOfDay(); val b = to.atStartOfDay()
        return cals.filter { !it.meta.hidden }.flatMap { c ->
            events[c.id].orEmpty().flatMap { (uid, e) ->
                runCatching { e.occurrencesIn(a, b) }.getOrDefault(emptyList()).map { i ->
                    Occurrence(c.id, uid, e, c.meta.color, c.meta.kind != "local", i.start, i.end, e.allDay, if (e.allDay) i.original.toLocalDate().str() else i.original.str())
                }
            }
        }.sortedWith(compareBy({ !it.allDay }, { it.start }, { it.end }))
    }

    // --- .ics

    /** Adds the events (and to-dos, into the default list) of an .ics file to a calendar. Returns how many. */
    fun importIcs(text: String, calendarId: String): Int {
        val p = Ics.parse(text)
        for ((uid, e) in p.events) File(calDir, "$calendarId/events/$uid.json").writeJson(e)
        for ((uid, t) in p.tasks) File(taskDir, "$DEFAULT_LIST/$uid.json").writeJson(t)
        changed()
        return p.events.size + p.tasks.size
    }

    fun exportIcs(calendarId: String): String = Ics.write(calendar(calendarId)?.meta?.name ?: "Calendar", events(calendarId))

    // --- subscriptions (a link to an .ics; each device fetches it itself, every few hours)

    private val http by lazy { OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build() }
    private val parsedSubs = mutableMapOf<String, Pair<Long, List<Pair<String, Event>>>>()

    private fun subscribed(id: String): List<Pair<String, Event>> {
        val f = File(subDir, "$id.ics")
        if (!f.exists()) return emptyList()
        parsedSubs[id]?.let { (m, ev) -> if (m == f.lastModified()) return ev }
        val ev = runCatching { Ics.parse(f.readText()).events }.getOrDefault(emptyList())
        parsedSubs[id] = f.lastModified() to ev
        return ev
    }

    /** Fetches subscribed calendars older than [maxAgeMs]. Returns an error message per failed calendar. Call off the main thread. */
    fun refreshSubscriptions(maxAgeMs: Long = 3 * 3600_000L): Map<String, String> {
        val errors = mutableMapOf<String, String>()
        for (c in calendars().filter { it.meta.kind == "subscription" && it.meta.url != null }) {
            val f = File(subDir, "${c.id}.ics")
            if (f.exists() && System.currentTimeMillis() - f.lastModified() < maxAgeMs) continue
            runCatching {
                val url = c.meta.url!!.replaceFirst(Regex("^webcals?://"), "https://")
                http.newCall(Request.Builder().url(url).header("User-Agent", "Daybreak").build()).execute().use { r ->
                    check(r.isSuccessful) { "HTTP ${r.code}" }
                    val body = r.body.string()
                    check(body.contains("BEGIN:VCALENDAR")) { "not a calendar file" }
                    f.writeAtomic(body)
                }
            }.onFailure { errors[c.id] = it.message ?: "couldn't fetch" }
        }
        if (errors.size < calendars().count { it.meta.kind == "subscription" }) { loadedAt = -1; Changes.bump() }
        return errors
    }

    // --- tasks

    fun lists(): List<ListInfo> = taskDir.listFiles().orEmpty().mapNotNull { d -> File(d, "list.json").readJson<TaskList>()?.let { ListInfo(d.name, it) } }
        .sortedWith(compareBy({ !it.meta.isDefault }, { it.meta.name.lowercase() }))

    fun createList(name: String, color: String): String {
        val id = newId(); File(taskDir, "$id/list.json").writeJson(TaskList(name.trim(), color, created = now())); changed(); return id
    }

    fun saveList(id: String, meta: TaskList) { File(taskDir, "$id/list.json").writeJson(meta); changed() }

    fun deleteList(id: String) {
        if (id == DEFAULT_LIST) return
        val trash = File(home, "local/trash").apply { mkdirs() }
        File(taskDir, id).renameTo(File(trash, "list-$id-${System.currentTimeMillis()}")); changed()
    }

    fun tasks(listId: String? = null): List<TaskRef> =
        (if (listId != null) listOf(listId) else lists().map { it.id }).flatMap { l ->
            File(taskDir, l).listFiles().orEmpty().filter { it.name.endsWith(".json") && it.name != "list.json" }
                .mapNotNull { f -> f.readJson<Task>()?.let { TaskRef(l, f.name.removeSuffix(".json"), it) } }
        }

    fun saveTask(listId: String, uid: String, t: Task) {
        File(taskDir, "$listId/$uid.json").writeJson(t.copy(updated = now(), created = t.created.takeIf { it > 0 } ?: now())); changed()
    }

    fun deleteTask(listId: String, uid: String) { File(taskDir, "$listId/$uid.json").delete(); changed() }

    fun moveTask(from: String, to: String, uid: String) {
        if (from == to) return
        File(taskDir, "$to").mkdirs(); File(taskDir, "$from/$uid.json").renameTo(File(taskDir, "$to/$uid.json")); changed()
    }

    fun setDone(r: TaskRef, done: Boolean) = saveTask(r.list, r.uid, r.task.copy(done = done, doneAt = if (done) now() else null))
}
