package com.qshlab.daybreak.feed

import com.qshlab.daybreak.cal.Cal
import com.qshlab.daybreak.cal.dueAt
import com.qshlab.daybreak.cal.dueDate
import com.qshlab.daybreak.cal.overdue
import com.qshlab.daybreak.sources.MailAccounts
import com.qshlab.daybreak.sources.MailReader
import com.qshlab.daybreak.sources.News
import com.qshlab.daybreak.sources.Places
import com.qshlab.daybreak.sources.Weather
import com.qshlab.daybreak.ui.relativeDay
import com.qshlab.daybreak.ui.timeText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime

/** Reads everything the feed covers, in parallel. Failures are noted, not fatal. */
object Collector {
    suspend fun collect(cfg: FeedConfig, since: Instant): Collected = withContext(Dispatchers.IO) {
        val errors = java.util.Collections.synchronizedList(mutableListOf<String>())
        fun <T> safe(what: String, f: () -> T): T? = runCatching(f).onFailure { errors += "$what: ${it.message ?: it.javaClass.simpleName}" }.getOrNull()
        val places = Places.resolved()
        if (Places.list().any { it.current } && Places.here() == null) errors += "Current location unknown" + (Places.lastError?.let { ": $it" } ?: " (allow location, or add your city in Settings → Places)")
        coroutineScope {
            val weather = if (!cfg.weather) emptyList() else places.map { p -> async { safe("Weather for ${p.name}") { Weather.fetch(p) } } }
            val primaryCc = places.firstOrNull()?.countryCode.orEmpty()
            val newsJobs = buildList {
                if (cfg.localNews) places.distinctBy { it.name }.forEach { p -> add(async { safe("News for ${p.name}") { News.feed(News.googleSearch(p.name, p.countryCode), p.name, 25) } }) }
                cfg.topics.forEach { t -> add(async { safe("News on “$t”") { News.feed(News.googleSearch(t, primaryCc), t, 12) } }) }
                cfg.feeds.forEach { f -> add(async { safe(f.name) { News.feed(f.url, f.name, 12) } }) }
            }
            val mailJobs = if (!cfg.email) emptyList() else MailAccounts.list().map { a -> async { safe("Mail (${a.email})") { MailReader.fetch(a, since) } } }
            val cutoff = System.currentTimeMillis() - 36 * 3600_000L
            val news = newsJobs.awaitAll().filterNotNull().flatten()
                .filter { it.published == 0L || it.published > cutoff }
                .distinctBy { it.title.lowercase().take(60) }
            val mail = mailJobs.awaitAll().filterNotNull().flatten().sortedByDescending { it.received }
            val (events, tasks) = if (cfg.calendar) calendarLines() else emptyList<String>() to emptyList()
            Collected(System.currentTimeMillis(), weather.awaitAll().filterNotNull(), news, mail, events, tasks, errors = errors.toList())
        }
    }

    /** Today's and tomorrow's events; overdue, due-soon and undated tasks. */
    private fun calendarLines(): Pair<List<String>, List<String>> {
        val today = LocalDate.now()
        val events = Cal.occurrences(today, today.plusDays(2)).map { o ->
            relativeDay(o.start.toLocalDate()) + " " + (if (o.allDay) "all day" else timeText(o.start.toLocalTime()) + "–" + timeText(o.end.toLocalTime())) +
                " " + o.event.title + (if (o.event.location.isNotBlank()) " @ ${o.event.location}" else "") + " [" + (Cal.calendar(o.calendarId)?.meta?.name ?: "") + "]"
        }
        val now = LocalDateTime.now()
        val open = Cal.tasks().filter { !it.task.done }
        val tasks = open.filter { it.task.overdue(now) }.map { "OVERDUE (due ${relativeDay(it.task.dueDate()!!)}): ${it.task.title}" } +
            open.filter { !it.task.overdue(now) && it.task.dueDate()?.let { d -> !d.isAfter(today.plusDays(7)) } == true }.sortedBy { it.task.dueAt() }
                .map { "Due ${relativeDay(it.task.dueDate()!!)}${if ((it.task.due?.length ?: 0) > 10) " " + timeText(it.task.dueAt()!!.toLocalTime()) else ""}: ${it.task.title}" } +
            open.filter { it.task.due == null }.sortedByDescending { it.task.created }.take(8).map { "Reminder/note: ${it.task.title}" + it.task.notes.takeIf { n -> n.isNotBlank() }?.let { n -> " — ${n.take(120)}" }.orEmpty() }
        return events to tasks
    }
}
