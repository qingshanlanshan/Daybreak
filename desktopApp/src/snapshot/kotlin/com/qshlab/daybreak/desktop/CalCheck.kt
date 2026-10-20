package com.qshlab.daybreak.desktop

import com.qshlab.daybreak.cal.Event
import com.qshlab.daybreak.cal.Ics
import com.qshlab.daybreak.cal.Rule
import com.qshlab.daybreak.cal.occurrences
import com.qshlab.daybreak.cal.occurrencesIn
import java.time.LocalDateTime
import kotlin.system.exitProcess

/** Development: checks repeat rules and .ics reading against known answers. */
fun main() {
    var bad = 0
    fun check(name: String, got: Any?, want: Any?) { if (got != want) { bad++; println("FAIL $name\n  got  $got\n  want $want") } else println("ok   $name") }
    val s = LocalDateTime.of(2026, 1, 5, 9, 0)   // a Monday
    val far = LocalDateTime.of(2030, 1, 1, 0, 0)
    fun occ(rule: String, n: Int = 6) = occurrences(s, Rule.parse(rule)!!, s, far).take(n).map { it.toString() }
    check("weekly MO,WE x4", occ("FREQ=WEEKLY;BYDAY=MO,WE;COUNT=4"), listOf("2026-01-05T09:00", "2026-01-07T09:00", "2026-01-12T09:00", "2026-01-14T09:00"))
    check("every 2 weeks", occ("FREQ=WEEKLY;INTERVAL=2", 3), listOf("2026-01-05T09:00", "2026-01-19T09:00", "2026-02-02T09:00"))
    check("monthly 2nd TU", occ("FREQ=MONTHLY;BYDAY=2TU", 3), listOf("2026-01-13T09:00", "2026-02-10T09:00", "2026-03-10T09:00"))
    check("monthly last FR", occ("FREQ=MONTHLY;BYDAY=-1FR", 2), listOf("2026-01-30T09:00", "2026-02-27T09:00"))
    check("monthly on the 31st skips short months", occurrences(LocalDateTime.of(2026, 1, 31, 8, 0), Rule.parse("FREQ=MONTHLY;COUNT=3")!!, s, far).map { it.toString() },
        listOf("2026-01-31T08:00", "2026-03-31T08:00", "2026-05-31T08:00"))
    check("yearly", occ("FREQ=YEARLY;UNTIL=20280105T090000", 5), listOf("2026-01-05T09:00", "2027-01-05T09:00", "2028-01-05T09:00"))
    check("daily weekdays", occ("FREQ=DAILY;BYDAY=MO,TU,WE,TH,FR", 6), listOf("2026-01-05T09:00", "2026-01-06T09:00", "2026-01-07T09:00", "2026-01-08T09:00", "2026-01-09T09:00", "2026-01-12T09:00"))
    val e = Event("Standup", "2026-01-05T09:00", "2026-01-05T09:15", rrule = "FREQ=DAILY;COUNT=5", exdates = listOf("2026-01-07T09:00"))
    check("exdate skips one", e.occurrencesIn(s, far).map { it.start.toString() }, listOf("2026-01-05T09:00", "2026-01-06T09:00", "2026-01-08T09:00", "2026-01-09T09:00"))
    val ics = """
        BEGIN:VCALENDAR
        X-WR-CALNAME:School
        BEGIN:VEVENT
        UID:abc@school
        DTSTART;TZID=Singapore Standard Time:20261021T100000
        DTEND;TZID=Singapore Standard Time:20261021T113000
        SUMMARY:Seminar\, room 5
        RRULE:FREQ=WEEKLY;COUNT=10
        BEGIN:VALARM
        TRIGGER:-PT15M
        END:VALARM
        END:VEVENT
        BEGIN:VEVENT
        UID:abc@school
        RECURRENCE-ID;TZID=Singapore Standard Time:20261028T100000
        DTSTART;TZID=Singapore Standard Time:20261028T140000
        DTEND;TZID=Singapore Standard Time:20261028T153000
        SUMMARY:Seminar (moved)
        END:VEVENT
        BEGIN:VEVENT
        UID:holiday
        DTSTART;VALUE=DATE:20261225
        SUMMARY:Christmas
        END:VEVENT
        BEGIN:VTODO
        UID:t1
        SUMMARY:Submit report
        DUE;VALUE=DATE:20261030
        END:VTODO
        END:VCALENDAR
    """.trimIndent()
    val p = Ics.parse(ics)
    val m = p.events.toMap()
    check("ics name", p.name, "School")
    check("ics tz mapped", m["abc_school"]?.tz, "Asia/Singapore")
    check("ics text unescaped", m["abc_school"]?.title, "Seminar, room 5")
    check("ics alarm", m["abc_school"]?.alarms, listOf(15))
    check("ics override excluded from series", m["abc_school"]?.exdates, listOf("2026-10-28T10:00"))
    check("ics override kept", m.values.count { it.title == "Seminar (moved)" }, 1)
    check("ics all-day end", m["holiday"]?.end, "2026-12-26")
    check("ics todo", p.tasks.firstOrNull()?.second?.due, "2026-10-30")
    val back = Ics.parse(Ics.write("Mine", listOf("x" to m["abc_school"]!!))).events.first().second
    check("write → read round trip", listOf(back.title, back.start, back.tz, back.rrule, back.exdates, back.alarms),
        listOf("Seminar, room 5", "2026-10-21T10:00", "Asia/Singapore", "FREQ=WEEKLY;COUNT=10", listOf("2026-10-28T10:00"), listOf(15)))
    println(if (bad == 0) "ALL OK" else "$bad FAILED")
    exitProcess(if (bad == 0) 0 else 1)
}
