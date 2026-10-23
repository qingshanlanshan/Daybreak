package com.qshlab.daybreak.desktop

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import com.qshlab.daybreak.Daybreak
import com.qshlab.daybreak.cal.Cal
import com.qshlab.daybreak.cal.Event
import com.qshlab.daybreak.cal.Task
import com.qshlab.daybreak.cal.newId
import com.qshlab.daybreak.cal.str
import java.time.LocalDate
import com.qshlab.daybreak.ui.DaybreakApp
import com.qshlab.daybreak.ui.Tab
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.system.exitProcess

/** Draws screens to PNG files (development check of layouts; no window). */
fun main(args: Array<String>) {
    val out = File(args[0]).apply { mkdirs() }
    Daybreak.start(MacPlatform())
    sample()
    fun shot(name: String, w: Int, h: Int, content: @androidx.compose.runtime.Composable () -> Unit) {
        ImageComposeScene(w, h, Density(2f), content = content).use { scene ->
            scene.render(0); val img = scene.render(500_000_000)
            File(out, "$name.png").writeBytes(img.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        }
    }
    shot("mac-feed", 2360, 1560) { DaybreakApp() }
    shot("phone-feed", 820, 1700) { DaybreakApp() }
    shot("phone-feed-tall", 820, 4600) { DaybreakApp() }
    shot("mac-month", 2360, 1560) { DaybreakApp(startTab = Tab.Calendar) }
    shot("mac-tasks", 2360, 1560) { DaybreakApp(startTab = Tab.Tasks) }
    shot("mac-week", 2360, 1560) { DaybreakApp(startTab = Tab.Calendar, calendarView = com.qshlab.daybreak.ui.CalView.Week) }
    shot("phone-week", 820, 1700) { DaybreakApp(startTab = Tab.Calendar, calendarView = com.qshlab.daybreak.ui.CalView.Week) }
    shot("phone-month", 820, 1700) { DaybreakApp(startTab = Tab.Calendar) }
    shot("phone-tasks", 820, 1700) { DaybreakApp(startTab = Tab.Tasks) }
    shot("mac-settings", 2360, 1560) { DaybreakApp(startInSettings = true) }
    shot("phone-calendar", 820, 1700) { DaybreakApp(startTab = Tab.Calendar) }
    shot("phone-settings", 820, 1700) { DaybreakApp(startInSettings = true) }
    run {   // mail a phone read, as it would arrive through sync
        val now = System.currentTimeMillis()
        java.io.File(Daybreak.platform.home, "mail/notifications-pixel.json").apply { parentFile.mkdirs() }.writeText(com.qshlab.daybreak.data.json.encodeToString(
            com.qshlab.daybreak.sources.MailBox.serializer(), com.qshlab.daybreak.sources.MailBox("notifications", "pixel", "Pixel 9", now - 600_000, false,
                listOf(com.qshlab.daybreak.sources.Mail("Outlook", "IT Helpdesk", "Password expiry", now - 3600_000, "Your password expires")))))
    }
    shot("phone-ai", 820, 2200) { com.qshlab.daybreak.ui.DaybreakTheme { com.qshlab.daybreak.ui.SettingsScreen({}, com.qshlab.daybreak.ui.SettingsPage.Claude) } }
    shot("mac-email", 2360, 1560) { com.qshlab.daybreak.ui.DaybreakTheme { com.qshlab.daybreak.ui.SettingsScreen({}, com.qshlab.daybreak.ui.SettingsPage.Email) } }
    println("snapshots in $out")
    exitProcess(0)
}

/** Sample calendar data for the snapshots (written into the snapshot home). */
private fun sample() {
    if (Cal.events(Cal.DEFAULT_CALENDAR).isNotEmpty()) return
    val today = LocalDate.now()
    val mon = today.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
    fun ev(title: String, d: LocalDate, h: Int, m: Int, mins: Long, rrule: String? = null, cal: String = Cal.DEFAULT_CALENDAR) =
        Cal.saveEvent(cal, newId(), Event(title, d.atTime(h, m).str(), d.atTime(h, m).plusMinutes(mins).str(), tz = java.time.ZoneId.systemDefault().id, rrule = rrule))
    val work = Cal.createCalendar("Lab", Cal.COLORS[2])
    ev("Group meeting", mon, 10, 0, 60, "FREQ=WEEKLY;BYDAY=MO", work)
    ev("Seminar", mon.plusDays(2), 14, 0, 90, cal = work)
    ev("Dentist", today, 16, 30, 45)
    ev("Gym", mon, 18, 0, 60, "FREQ=WEEKLY;BYDAY=MO,WE,FR")
    ev("Call with advisor", today, 11, 0, 30, cal = work)
    ev("Paper reading", today, 11, 15, 60, cal = work)
    Cal.saveEvent(Cal.DEFAULT_CALENDAR, newId(), Event("Mum's birthday", today.plusDays(3).str(), today.plusDays(4).str(), allDay = true, rrule = "FREQ=YEARLY"))
    Cal.saveTask(Cal.DEFAULT_LIST, newId(), Task("Buy milk"))
    Cal.saveTask(Cal.DEFAULT_LIST, newId(), Task("Call the landlord about the aircon", notes = "Unit 12-34"))
    Cal.saveTask(Cal.DEFAULT_LIST, newId(), Task("Submit conference paper", due = today.plusDays(2).atTime(23, 59).str()))
    Cal.saveTask(Cal.DEFAULT_LIST, newId(), Task("Renew library books", due = today.minusDays(1).str()))
    Cal.saveTask(Cal.DEFAULT_LIST, newId(), Task("Reply to Prof. Tan", due = today.str(), remind = today.atTime(15, 0).str()))
}
