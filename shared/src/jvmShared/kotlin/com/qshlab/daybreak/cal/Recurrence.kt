package com.qshlab.daybreak.cal

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/**
 * Repeat rules (RFC 5545 RRULE), the parts calendars use in practice: FREQ (DAILY/WEEKLY/MONTHLY/YEARLY),
 * INTERVAL, COUNT, UNTIL, BYDAY (with ordinals like 2MO, -1FR), BYMONTHDAY (negative = from the end),
 * BYMONTH. BYSETPOS and sub-daily frequencies are not supported (such events show their first occurrence).
 */
class Rule(
    val freq: String, val interval: Int = 1, val count: Int? = null, val until: LocalDateTime? = null,
    val byDay: List<Pair<Int, DayOfWeek>> = emptyList(),     // (ordinal or 0, day)
    val byMonthDay: List<Int> = emptyList(), val byMonth: List<Int> = emptyList(),
) {
    fun format(): String = buildList {
        add("FREQ=$freq")
        if (interval > 1) add("INTERVAL=$interval")
        count?.let { add("COUNT=$it") }
        until?.let { add("UNTIL=" + it.format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss"))) }
        if (byDay.isNotEmpty()) add("BYDAY=" + byDay.joinToString(",") { (n, d) -> (if (n != 0) "$n" else "") + DAYS.entries.first { it.value == d }.key })
        if (byMonthDay.isNotEmpty()) add("BYMONTHDAY=" + byMonthDay.joinToString(","))
        if (byMonth.isNotEmpty()) add("BYMONTH=" + byMonth.joinToString(","))
    }.joinToString(";")

    companion object {
        val DAYS = mapOf("MO" to DayOfWeek.MONDAY, "TU" to DayOfWeek.TUESDAY, "WE" to DayOfWeek.WEDNESDAY, "TH" to DayOfWeek.THURSDAY,
            "FR" to DayOfWeek.FRIDAY, "SA" to DayOfWeek.SATURDAY, "SU" to DayOfWeek.SUNDAY)

        /** [tz]: the event's zone, for a UTC UNTIL. Null when the rule can't be read. */
        fun parse(s: String, tz: String? = null): Rule? = runCatching {
            val p = s.removePrefix("RRULE:").split(';').filter { '=' in it }.associate { it.substringBefore('=').uppercase() to it.substringAfter('=') }
            val freq = p["FREQ"]?.uppercase()?.takeIf { it in setOf("DAILY", "WEEKLY", "MONTHLY", "YEARLY") } ?: return null
            Rule(
                freq, p["INTERVAL"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1, p["COUNT"]?.toIntOrNull(),
                p["UNTIL"]?.let { u -> parseUntil(u, tz) },
                p["BYDAY"]?.split(',')?.mapNotNull { d ->
                    val m = Regex("([+-]?\\d*)([A-Z]{2})").matchEntire(d.trim().uppercase()) ?: return@mapNotNull null
                    (m.groupValues[1].toIntOrNull() ?: 0) to (DAYS[m.groupValues[2]] ?: return@mapNotNull null)
                } ?: emptyList(),
                p["BYMONTHDAY"]?.split(',')?.mapNotNull { it.trim().toIntOrNull() } ?: emptyList(),
                p["BYMONTH"]?.split(',')?.mapNotNull { it.trim().toIntOrNull() } ?: emptyList(),
            )
        }.getOrNull()

        private fun parseUntil(u: String, tz: String?): LocalDateTime {
            val t = u.trim()
            if (t.length == 8) return LocalDate.parse(t, java.time.format.DateTimeFormatter.BASIC_ISO_DATE).atTime(23, 59, 59)
            val local = LocalDateTime.parse(t.take(15), java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss"))
            return if (t.endsWith("Z")) local.atOffset(ZoneOffset.UTC).atZoneSameInstant(zoneOf(tz)).toLocalDateTime() else local
        }
    }
}

/**
 * The starts of a repeating event that fall in [from, to), in the event's own local time. Counting (COUNT)
 * always starts from the first occurrence, so this walks from [start].
 */
fun occurrences(start: LocalDateTime, rule: Rule, from: LocalDateTime, to: LocalDateTime): List<LocalDateTime> {
    val out = mutableListOf<LocalDateTime>()
    var made = 0
    val time = start.toLocalTime()
    fun accept(t: LocalDateTime): Boolean {           // false: stop
        if (t.isBefore(start)) return true
        if (rule.until != null && t.isAfter(rule.until)) return false
        if (rule.count != null && made >= rule.count) return false
        made++
        if (!t.isBefore(to)) return false
        if (!t.isBefore(from)) out += t
        return true
    }
    fun monthDays(month: LocalDate): List<LocalDate> {   // the days chosen within one month
        val len = month.lengthOfMonth()
        val days = when {
            rule.byMonthDay.isNotEmpty() -> rule.byMonthDay.mapNotNull { d -> (if (d > 0) d else len + d + 1).takeIf { it in 1..len }?.let { month.withDayOfMonth(it) } }
            rule.byDay.isNotEmpty() -> rule.byDay.flatMap { (n, dow) ->
                val all = generateSequence(month.withDayOfMonth(1).with(TemporalAdjusters.nextOrSame(dow))) { it.plusWeeks(1) }.takeWhile { it.month == month.month }.toList()
                when { n > 0 -> listOfNotNull(all.getOrNull(n - 1)); n < 0 -> listOfNotNull(all.getOrNull(all.size + n)); else -> all }
            }
            else -> listOfNotNull(start.dayOfMonth.takeIf { it <= len }?.let { month.withDayOfMonth(it) })
        }
        return days.distinct().sorted()
    }
    var periods = 0
    when (rule.freq) {
        "DAILY" -> {
            var d = start.toLocalDate()
            while (periods++ < 20000) {
                val ok = (rule.byMonth.isEmpty() || d.monthValue in rule.byMonth) && (rule.byDay.isEmpty() || rule.byDay.any { it.second == d.dayOfWeek }) &&
                    (rule.byMonthDay.isEmpty() || d.dayOfMonth in rule.byMonthDay)
                if (ok && !accept(d.atTime(time))) break
                d = d.plusDays(rule.interval.toLong())
            }
        }
        "WEEKLY" -> {
            val days = rule.byDay.map { it.second }.ifEmpty { listOf(start.dayOfWeek) }.sorted()
            var week = start.toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            loop@ while (periods++ < 5000) {
                for (dow in days) if (!accept(week.with(TemporalAdjusters.nextOrSame(dow)).atTime(time))) break@loop
                week = week.plusWeeks(rule.interval.toLong())
            }
        }
        "MONTHLY" -> {
            var m = start.toLocalDate().withDayOfMonth(1)
            loop@ while (periods++ < 2400) {
                if (rule.byMonth.isEmpty() || m.monthValue in rule.byMonth) for (d in monthDays(m)) if (!accept(d.atTime(time))) break@loop
                m = m.plusMonths(rule.interval.toLong())
            }
        }
        "YEARLY" -> {
            var y = start.toLocalDate().withDayOfYear(1)
            val months = rule.byMonth.ifEmpty { listOf(start.monthValue) }.sorted()
            loop@ while (periods++ < 400) {
                for (mo in months) {
                    val m = y.withMonth(mo)
                    val days = if (rule.byMonthDay.isEmpty() && rule.byDay.isEmpty())
                        listOfNotNull(start.dayOfMonth.takeIf { it <= m.lengthOfMonth() }?.let { m.withDayOfMonth(it) }) else monthDays(m)
                    for (d in days) if (!accept(d.atTime(time))) break@loop
                }
                y = y.plusYears(rule.interval.toLong())
            }
        }
    }
    return out
}

/** One appearance: its start in the event's own time (the key for skipping it), and start/end on this device. */
data class Instance(val original: LocalDateTime, val start: LocalDateTime, val end: LocalDateTime)

/** Every appearance of this event overlapping the device-local window [from, to). */
fun Event.occurrencesIn(from: LocalDateTime, to: LocalDateTime): List<Instance> {
    val s = parseDateTime(start)
    val len = ChronoUnit.MINUTES.between(s, parseDateTime(end)).coerceAtLeast(if (allDay) 24 * 60L else 0L)
    val tzz = if (allDay) null else tz
    // Search a little wider in the event's own time (zones can shift the day).
    val pad = 2L
    val starts = rrule?.let { Rule.parse(it, tz) }?.let { r ->
        occurrences(s, r, fromDevice(from, tzz).minusDays(pad).minusMinutes(len), fromDevice(to, tzz).plusDays(pad))
    } ?: listOf(s)
    val skip = exdates.map { parseDateTime(it) }.toSet()
    return starts.filter { it !in skip }.map { st -> Instance(st, toDevice(st, tzz), toDevice(st.plusMinutes(len), tzz)) }
        .filter { i -> i.start.isBefore(to) && (i.end.isAfter(from) || (len == 0L && !i.start.isBefore(from))) }
}

private fun fromDevice(t: LocalDateTime, tz: String?): LocalDateTime =
    if (tz == null) t else t.atZone(ZoneId.systemDefault()).withZoneSameInstant(zoneOf(tz)).toLocalDateTime()
