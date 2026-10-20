package com.qshlab.daybreak.cal

import com.qshlab.daybreak.data.now
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** iCalendar (.ics, RFC 5545): read events and to-dos from files and subscription links; write events. */
object Ics {
    data class Parsed(val events: List<Pair<String, Event>>, val tasks: List<Pair<String, Task>>, val name: String?)

    private val BASIC_DT = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")
    private val BASIC_D = DateTimeFormatter.BASIC_ISO_DATE

    /** Outlook and Exchange use Windows zone names. The common ones; others fall back to the device's zone. */
    private val WINDOWS = mapOf(
        "China Standard Time" to "Asia/Shanghai", "Singapore Standard Time" to "Asia/Singapore", "Taipei Standard Time" to "Asia/Taipei",
        "Tokyo Standard Time" to "Asia/Tokyo", "Korea Standard Time" to "Asia/Seoul", "India Standard Time" to "Asia/Kolkata",
        "SE Asia Standard Time" to "Asia/Bangkok", "AUS Eastern Standard Time" to "Australia/Sydney", "New Zealand Standard Time" to "Pacific/Auckland",
        "GMT Standard Time" to "Europe/London", "W. Europe Standard Time" to "Europe/Berlin", "Romance Standard Time" to "Europe/Paris",
        "Central Europe Standard Time" to "Europe/Budapest", "Russian Standard Time" to "Europe/Moscow", "Eastern Standard Time" to "America/New_York",
        "Central Standard Time" to "America/Chicago", "Mountain Standard Time" to "America/Denver", "Pacific Standard Time" to "America/Los_Angeles",
        "Hawaiian Standard Time" to "Pacific/Honolulu", "UTC" to "UTC", "Coordinated Universal Time" to "UTC",
    )

    fun zoneId(tzid: String?): String? = tzid?.trim('"')?.let { t ->
        runCatching { ZoneId.of(t).id }.getOrNull() ?: WINDOWS[t] ?: WINDOWS.entries.firstOrNull { t.contains(it.key) }?.value
    }

    private data class Prop(val name: String, val params: Map<String, String>, val value: String)

    private fun unfold(text: String): List<String> {
        val out = mutableListOf<String>()
        for (raw in text.replace("\r\n", "\n").replace('\r', '\n').split('\n')) {
            if ((raw.startsWith(" ") || raw.startsWith("\t")) && out.isNotEmpty()) out[out.size - 1] = out.last() + raw.substring(1)
            else if (raw.isNotEmpty()) out += raw
        }
        return out
    }

    private fun prop(line: String): Prop? {
        var inQuote = false; var colon = -1
        for ((i, ch) in line.withIndex()) { if (ch == '"') inQuote = !inQuote; if (ch == ':' && !inQuote) { colon = i; break } }
        if (colon < 0) return null
        val head = line.substring(0, colon).split(';')
        val params = head.drop(1).mapNotNull { p -> p.indexOf('=').takeIf { it > 0 }?.let { p.substring(0, it).uppercase() to p.substring(it + 1).trim('"') } }.toMap()
        return Prop(head[0].uppercase(), params, line.substring(colon + 1))
    }

    private fun unescape(s: String) = s.replace("\\n", "\n").replace("\\N", "\n").replace("\\,", ",").replace("\\;", ";").replace("\\\\", "\\")
    private fun escape(s: String) = s.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace("\n", "\\n")

    /** A DTSTART-like value → (string in our format, all-day?, zone id or null). */
    private fun time(p: Prop): Triple<String, Boolean, String?>? = runCatching {
        val v = p.value.trim()
        if (p.params["VALUE"] == "DATE" || v.length == 8) return Triple(LocalDate.parse(v.take(8), BASIC_D).str(), true, null)
        val local = LocalDateTime.parse(v.take(15), BASIC_DT)
        if (v.endsWith("Z")) {   // UTC: keep it in the device's zone (floating stays correct for this device)
            val z = ZoneId.systemDefault()
            return Triple(local.atOffset(ZoneOffset.UTC).atZoneSameInstant(z).toLocalDateTime().str(), false, z.id)
        }
        Triple(local.str(), false, zoneId(p.params["TZID"]) ?: ZoneId.systemDefault().id)
    }.getOrNull()

    /** "PT15M", "-PT15M", "P1D", "P2W" … */
    private fun duration(s: String): Duration? = runCatching {
        val t = s.trim().removePrefix("+")
        Regex("^(-?)P(\\d+)W$").matchEntire(t)?.let { m -> Duration.ofDays(m.groupValues[2].toLong() * 7).let { if (m.groupValues[1] == "-") it.negated() else it } }
            ?: Duration.parse(t)
    }.getOrNull()

    fun parse(text: String): Parsed {
        val events = mutableListOf<Pair<String, Event>>()
        val tasks = mutableListOf<Pair<String, Task>>()
        var calName: String? = null
        val overrides = mutableListOf<Triple<String, String, Event>>()  // uid, recurrence-id, event
        var cur: MutableList<Prop>? = null
        var kind = ""
        var alarm: MutableList<Prop>? = null
        val alarms = mutableListOf<Int>()
        val ts = now()
        for (line in unfold(text)) {
            val p = prop(line) ?: continue
            when {
                p.name == "X-WR-CALNAME" && cur == null -> calName = unescape(p.value)
                p.name == "BEGIN" && (p.value == "VEVENT" || p.value == "VTODO") -> { cur = mutableListOf(); kind = p.value; alarms.clear() }
                p.name == "BEGIN" && p.value == "VALARM" && cur != null -> alarm = mutableListOf()
                p.name == "END" && p.value == "VALARM" -> {
                    alarm?.firstOrNull { it.name == "TRIGGER" }?.let { t -> duration(t.value)?.let { d -> if (d.isNegative || d.isZero) alarms += (-d.toMinutes()).toInt() } }
                    alarm = null
                }
                p.name == "END" && (p.value == "VEVENT" || p.value == "VTODO") && cur != null -> {
                    val props = cur.groupBy { it.name }
                    fun one(n: String) = props[n]?.firstOrNull()
                    val uid = one("UID")?.value?.trim()?.ifBlank { null } ?: newId()
                    if (kind == "VEVENT") {
                        val st = one("DTSTART")?.let(::time)
                        if (st != null) {
                            val (start, allDay, tz) = st
                            val end = one("DTEND")?.let(::time)?.first ?: one("DURATION")?.let { duration(it.value) }?.let { d ->
                                if (allDay) parseDate(start).plusDays(d.toDays().coerceAtLeast(1)).str() else parseDateTime(start).plus(d).str()
                            } ?: if (allDay) parseDate(start).plusDays(1).str() else start
                            val ex = props["EXDATE"].orEmpty().flatMap { e -> e.value.split(',').mapNotNull { v -> time(e.copy(value = v))?.first } }
                            val ev = Event(title = unescape(one("SUMMARY")?.value ?: "(no title)"), start = start, end = end, allDay = allDay,
                                tz = if (allDay) null else tz, location = unescape(one("LOCATION")?.value ?: ""), notes = unescape(one("DESCRIPTION")?.value ?: ""),
                                rrule = one("RRULE")?.value, exdates = ex, alarms = alarms.toList(), created = ts, updated = ts)
                            val rid = one("RECURRENCE-ID")?.let(::time)?.first
                            if (rid != null) overrides += Triple(uid, rid, ev.copy(rrule = null)) else events += uid to ev
                        }
                    } else {
                        val due = one("DUE")?.let(::time)?.first
                        val done = one("STATUS")?.value == "COMPLETED" || one("COMPLETED") != null
                        tasks += uid to Task(unescape(one("SUMMARY")?.value ?: "(no title)"), unescape(one("DESCRIPTION")?.value ?: ""), due = due,
                            done = done, doneAt = if (done) ts else null, created = ts, updated = ts)
                    }
                    cur = null
                }
                alarm != null -> alarm!! += p
                cur != null -> cur!! += p
            }
        }
        // A changed occurrence of a repeating event: skip it in the series, show the changed one on its own.
        val byUid = events.toMap().toMutableMap()
        for ((uid, rid, ev) in overrides) {
            byUid[uid]?.let { m -> byUid[uid] = m.copy(exdates = m.exdates + rid) }
            byUid["$uid-${rid.filter(Char::isDigit)}"] = ev
        }
        return Parsed(byUid.map { it.key.safeId() to it.value }, tasks.map { it.first.safeId() to it.second }, calName)
    }

    private fun String.safeId(): String = replace(Regex("[^A-Za-z0-9._-]"), "_").take(80).ifEmpty { newId() }

    private fun dt(s: String, allDay: Boolean): String =
        if (allDay) parseDate(s).format(BASIC_D) else parseDateTime(s).format(BASIC_DT)

    private fun fold(line: String): String {
        val out = StringBuilder(); var n = 0
        for (ch in line) { val w = ch.toString().toByteArray().size; if (n + w > 74) { out.append("\r\n "); n = 1 }; out.append(ch); n += w }
        return out.toString()
    }

    /** A .ics file with [events] (uid → event). */
    fun write(name: String, events: List<Pair<String, Event>>): String {
        val stamp = LocalDateTime.now(ZoneOffset.UTC).format(BASIC_DT) + "Z"
        val lines = mutableListOf("BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//qshlab//Daybreak//EN", "CALSCALE:GREGORIAN", "X-WR-CALNAME:${escape(name)}")
        for ((uid, e) in events) {
            lines += "BEGIN:VEVENT"
            lines += "UID:$uid"
            lines += "DTSTAMP:$stamp"
            val tzp = if (!e.allDay && e.tz != null) ";TZID=${e.tz}" else ""
            lines += if (e.allDay) "DTSTART;VALUE=DATE:${dt(e.start, true)}" else "DTSTART$tzp:${dt(e.start, false)}"
            lines += if (e.allDay) "DTEND;VALUE=DATE:${dt(e.end, true)}" else "DTEND$tzp:${dt(e.end, false)}"
            lines += "SUMMARY:${escape(e.title)}"
            if (e.location.isNotBlank()) lines += "LOCATION:${escape(e.location)}"
            if (e.notes.isNotBlank()) lines += "DESCRIPTION:${escape(e.notes)}"
            e.rrule?.let { lines += "RRULE:$it" }
            for (x in e.exdates) lines += if (e.allDay) "EXDATE;VALUE=DATE:${dt(x, true)}" else "EXDATE$tzp:${dt(x, false)}"
            for (m in e.alarms) { lines += "BEGIN:VALARM"; lines += "ACTION:DISPLAY"; lines += "DESCRIPTION:${escape(e.title)}"; lines += "TRIGGER:-PT${m}M"; lines += "END:VALARM" }
            lines += "END:VEVENT"
        }
        lines += "END:VCALENDAR"
        return lines.joinToString("\r\n") { fold(it) } + "\r\n"
    }
}
