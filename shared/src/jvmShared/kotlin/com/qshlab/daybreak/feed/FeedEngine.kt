package com.qshlab.daybreak.feed

import com.qshlab.daybreak.Daybreak
import com.qshlab.daybreak.data.Changes
import com.qshlab.daybreak.data.json
import com.qshlab.daybreak.data.readJson
import com.qshlab.daybreak.data.writeAtomic
import com.qshlab.daybreak.data.writeJson
import com.qshlab.daybreak.sources.Places
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

data class EngineState(val running: Boolean = false, val step: String = "", val error: String? = null)

@Serializable
data class ChatOut(
    val reply: String,
    val profile: String = "",
    val add_topics: List<String> = emptyList(), val remove_topics: List<String> = emptyList(),
    val add_feeds: List<RssSource> = emptyList(), val remove_feeds: List<String> = emptyList(),
    val add_searches: List<String> = emptyList(), val remove_searches: List<String> = emptyList(),
    val rewrite: Boolean = false, val focus: String = "",
    val add_tasks: List<NewTask> = emptyList(),
)

@Serializable
private data class FeedOut(val brief: String, val cards: List<Card>)

/** Writes the feed, talks with you about it, and keeps it on schedule. */
object FeedEngine {
    private val home get() = Daybreak.platform.home
    private val mutex = Mutex()
    private val _state = MutableStateFlow(EngineState())
    val state: StateFlow<EngineState> = _state

    // --- files

    fun config(): FeedConfig = File(home, "feed/config.json").readJson() ?: FeedConfig()
    fun saveConfig(c: FeedConfig) { File(home, "feed/config.json").writeJson(c); changed() }
    fun profile(): String = File(home, "feed/profile.md").takeIf { it.exists() }?.readText() ?: ""
    fun saveProfile(text: String) { File(home, "feed/profile.md").writeAtomic(text.trim() + "\n"); changed() }
    fun current(): FeedResult? = File(home, "feed/current.json").readJson()
    fun collected(): Collected? = File(home, "local/collected.json").readJson()
    fun chat(): List<ChatMsg> = File(home, "feed/chat.json").readJson<List<ChatMsg>>() ?: emptyList()
    fun feedback(): List<Feedback> = File(home, "feed/feedback.json").readJson<List<Feedback>>() ?: emptyList()
    private fun changed() { Changes.bump(); Daybreak.sync.kickSoon() }

    fun react(card: Card, verdict: String, note: String = "") {
        val f = (feedback().filterNot { it.title == card.title } + Feedback(card.title, card.kind, verdict, System.currentTimeMillis(), note)).takeLast(60)
        File(home, "feed/feedback.json").writeJson(f); changed()
    }

    // --- the instructions (stable, cached between updates)

    private fun instructions(profile: String) = """
        You write the user's personal feed in Daybreak, a personal assistant app: a short brief and a few cards about
        what matters to them right now, from their email, calendar, tasks, weather, local news and chosen topics.

        What makes a good feed:
        - Personal and practical first: emails from real people or about the user's own affairs (deliveries, bills,
          bookings, school, work), today's events and deadlines, weather that changes plans (rain at commute time,
          haze/air quality, heat, storms), city updates that affect daily life (transport disruptions, road works,
          outbreaks/health advisories, utilities, public holidays). Then news that affects individuals.
        - Leave out what the profile says the user doesn't want. Without other instructions: skip ads, promotions,
          newsletters and marketing emails, social-media notifications, politics and celebrity news, and anything
          that doesn't affect the user.
        - Use only the material given. Never invent facts, times or numbers. If unsure, say so briefly.
        - Each card: a short title; 1–3 sentences of body with the concrete facts (who, what, when, what to do);
          "why" = a few words on why it matters to this user; refs = the ids of the items it's based on
          (like M3, N12, W1, E2, T4, S1); priority 1 = needs attention today, 2 = good to know, 3 = minor.
        - Up to 12 cards, most important first. One card per subject: never two cards about the same thing.
          Everything about one subject goes in one card, even from different sources (for example all of the
          haze: the air-quality reading, the forecast, the advisory and the news about it). A weather card only
          when the weather is notable or affects plans; otherwise mention it in a few words in the brief.
        - place: for a card about one of the user's places (local news, weather, city updates), that place's name
          exactly as listed; otherwise "".
        - task_title / task_due: when a card is about something the user has to do by a time (a deadline, a
          payment, a form, an RSVP, preparing for an appointment) and it isn't already among their tasks, suggest
          one task: a short imperative title ("Submit the ethics form") and the due date as "YYYY-MM-DD", or
          "YYYY-MM-DDTHH:MM" when a time is known, or "" when there's no date. Otherwise both "".
        - Take the user's reactions to earlier cards seriously: avoid what they marked 👎 (and why), keep doing what
          they liked.
        - The brief: 2–4 friendly sentences, the day at a glance (what's coming, what needs doing, anything to
          watch out for). Address the user as "you". No greeting formulas.
        - Write in the language the profile asks for; English otherwise.
        - Follow a one-off focus if one is given for this version.

        The user's profile (their own words, plus what they've told you):
        ${profile.ifBlank { "(empty: the user hasn't said anything about themselves yet)" }}
    """.trimIndent()

    private val feedSchema: Map<String, Any> = mapOf(
        "type" to "object", "additionalProperties" to false, "required" to listOf("brief", "cards"),
        "properties" to mapOf(
            "brief" to mapOf("type" to "string"),
            "cards" to mapOf("type" to "array", "items" to mapOf(
                "type" to "object", "additionalProperties" to false,
                "required" to listOf("kind", "title", "body", "why", "refs", "priority", "place", "task_title", "task_due"),
                "properties" to mapOf(
                    "kind" to mapOf("type" to "string", "enum" to listOf("email", "weather", "local", "news", "calendar", "task", "other")),
                    "title" to mapOf("type" to "string"), "body" to mapOf("type" to "string"), "why" to mapOf("type" to "string"),
                    "refs" to mapOf("type" to "array", "items" to mapOf("type" to "string")),
                    "priority" to mapOf("type" to "integer", "enum" to listOf(1, 2, 3)),
                    "place" to mapOf("type" to "string"), "task_title" to mapOf("type" to "string"), "task_due" to mapOf("type" to "string"),
                ))),
        ))

    /** The material for one update, as text with ids the cards can cite. */
    private fun material(c: Collected, cfg: FeedConfig, focus: String?): String = buildString {
        val now = LocalDateTime.now()
        appendLine("Now: ${now.format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy, HH:mm", Locale.ENGLISH))} (${ZoneId.systemDefault().id}).")
        val places = Places.resolved()
        if (places.isNotEmpty()) appendLine("The user's places: " + places.joinToString { it.name + if (it.current) " (where they are now)" else "" })
        focus?.takeIf { it.isNotBlank() }?.let { appendLine("\nOne-off focus for this version: $it") }
        val fb = feedback().takeLast(25)
        if (fb.isNotEmpty()) { appendLine("\nThe user's reactions to earlier cards:"); fb.forEach { appendLine("- ${if (it.verdict == "up") "👍 liked" else "👎 didn't want"} [${it.kind}] ${it.title}${if (it.note.isNotBlank()) " (${it.note})" else ""}") } }
        current()?.let { prev -> if (System.currentTimeMillis() - prev.generatedAt < 20 * 3600_000L) { appendLine("\nThe previous version (avoid repeating unless still relevant): ${prev.cards.joinToString("; ") { it.title }}") } }
        if (c.weather.isNotEmpty()) { appendLine("\n## Weather"); c.weather.forEachIndexed { i, w ->
            appendLine("W${i + 1} ${w.place}: now ${w.now}; today ${w.today}" + (if (w.nextHours.isNotEmpty()) "; notable hours: ${w.nextHours.joinToString(", ")}" else "") +
                "; next days: ${w.nextDays.joinToString("; ")}" + (w.air?.let { "; $it" } ?: "")) } }
        if (c.events.isNotEmpty()) { appendLine("\n## Calendar"); c.events.forEachIndexed { i, e -> appendLine("E${i + 1} $e") } }
        if (c.tasks.isNotEmpty()) { appendLine("\n## Tasks"); c.tasks.forEachIndexed { i, t -> appendLine("T${i + 1} $t") } }
        if (c.mail.isNotEmpty()) { appendLine("\n## Email (newest first, read-only)"); c.mail.forEachIndexed { i, m ->
            val age = ago(m.received)
            appendLine(if (m.bulk) "M${i + 1} [newsletter/promotion] from ${m.from}: ${m.subject} ($age)" else "M${i + 1} from ${m.from}: ${m.subject} ($age) — ${m.snippet.take(600)}") } }
        if (c.news.isNotEmpty()) { appendLine("\n## News"); c.news.forEachIndexed { i, n ->
            appendLine("N${i + 1} [${n.via}] ${n.title} — ${n.source}${if (n.published > 0) " (${ago(n.published)})" else ""}${if (n.summary.isNotBlank()) ": ${n.summary.take(300)}" else ""}") } }
        if (c.web.isNotBlank()) { appendLine("\n## Web search notes"); appendLine(c.web) }
        if (c.errors.isNotEmpty()) { appendLine("\n(Couldn't read: ${c.errors.joinToString("; ")})") }
    }

    private fun ago(t: Long): String { val m = (System.currentTimeMillis() - t) / 60000; return when { m < 60 -> "${m}m ago"; m < 1440 -> "${m / 60}h ago"; else -> "${m / 1440}d ago" } }

    /** The articles / emails a card cites, named: "Email from Prof Tan", "CNA". */
    private fun sources(card: Card, c: Collected): List<Source> = card.refs.mapNotNull { r ->
        val n = r.drop(1).toIntOrNull()?.minus(1) ?: return@mapNotNull null
        when (r.first()) {
            'N' -> c.news.getOrNull(n)?.let { Source(it.source.ifBlank { it.via }.take(40), it.url) }
            'M' -> c.mail.getOrNull(n)?.takeIf { it.url.isNotBlank() }?.let { m ->
                Source("Email from " + m.from.substringBefore('<').trim().trim('"').ifEmpty { m.from }.take(30), m.url) }
            else -> null
        }
    }.filter { it.url.isNotBlank() }.distinctBy { it.url }

    // --- updating the feed

    /**
     * Reads the sources (or reuses the last reading with [reuse]) and writes a new feed. [focus]: a one-off
     * emphasis for this version (from the assistant).
     */
    suspend fun refresh(focus: String? = null, reuse: Boolean = false): Result<FeedResult> = mutex.withLock {
        withContext(Dispatchers.IO) {
            runCatching {
                val cfg = config()
                if (Claude.key == null) throw ClaudeError("Add your Claude API key in Settings to get your feed.")
                if (Places.list().any { it.current } && (Places.here()?.let { System.currentTimeMillis() - it.at > 3 * 3600_000L } != false))
                    runCatching { _state.value = EngineState(true, "Finding where you are…"); Places.locate().getOrThrow() }
                        .onFailure { Places.lastError = it.message }.onSuccess { Places.lastError = null }
                var c = if (reuse) collected() ?: Collector.collect(cfg, since()) else {
                    _state.value = EngineState(true, "Reading your email, calendar, weather and news…")
                    Collector.collect(cfg, since())
                }
                if (!reuse && cfg.searches.isNotEmpty()) {
                    _state.value = EngineState(true, "Searching the web…")
                    val web = runCatching {
                        Claude.ask(cfg.model, "You research things for the user's personal feed. Search the web and report briefly, with dates and sources, what's new in the last day or two on each question. Plain text, a few lines per question. Today is ${LocalDate.now()}.",
                            cfg.searches.joinToString("\n") { "- $it" } + (Places.resolved().firstOrNull()?.let { "\n(The user is in ${it.name}.)" } ?: ""),
                            effort = "low", maxTokens = 4000, webSearches = (cfg.searches.size * 2).coerceAtMost(8)).text
                    }.getOrElse { "(web search failed: ${it.message})" }
                    c = c.copy(web = web)
                }
                File(home, "local/collected.json").writeJson(c)
                _state.value = EngineState(true, "Writing your feed…")
                val r = Claude.ask(cfg.model, instructions(profile()), material(c, cfg, focus), feedSchema, effort = "low")
                val out = json.decodeFromString<FeedOut>(r.text)
                val result = FeedResult(out.brief, out.cards.sortedBy { it.priority }.map { it.copy(sources = sources(it, c)) }, System.currentTimeMillis(),
                    Daybreak.sync.deviceId, cfg.model, r.costUsd, focus, c.at)
                current()?.let { prev -> File(home, "feed/history/${prev.generatedAt}.json").writeJson(prev) }
                File(home, "feed/history").listFiles().orEmpty().sortedBy { it.name }.dropLast(14).forEach { it.delete() }
                File(home, "feed/current.json").writeJson(result)
                changed()
                Daybreak.sync.kick()
                result
            }.also { r -> _state.value = EngineState(false, "", r.exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName }) }
        }
    }

    /** Mail since the last feed (a little overlap), at most three days back. */
    private fun since(): Instant {
        val last = current()?.sourcesAt?.takeIf { it > 0 } ?: 0
        return Instant.ofEpochMilli(maxOf(last - 2 * 3600_000L, System.currentTimeMillis() - 3 * 86400_000L))
    }

    // --- talking to the assistant

    private val chatSchema: Map<String, Any> = run {
        val str = mapOf("type" to "string"); val strs = mapOf("type" to "array", "items" to str)
        val props = mapOf("reply" to str, "profile" to str, "add_topics" to strs, "remove_topics" to strs,
            "add_feeds" to mapOf("type" to "array", "items" to mapOf("type" to "object", "additionalProperties" to false, "required" to listOf("name", "url"),
                "properties" to mapOf("name" to str, "url" to str))),
            "remove_feeds" to strs, "add_searches" to strs, "remove_searches" to strs, "rewrite" to mapOf("type" to "boolean"), "focus" to str,
            "add_tasks" to mapOf("type" to "array", "items" to mapOf("type" to "object", "additionalProperties" to false, "required" to listOf("title", "due", "notes"),
                "properties" to mapOf("title" to str, "due" to str, "notes" to str))))
        mapOf("type" to "object", "additionalProperties" to false, "required" to props.keys.toList(), "properties" to props)
    }

    /** One message to the assistant: answers it, and changes the profile / sources / current feed if asked. */
    suspend fun say(message: String): Result<ChatOut> = withContext(Dispatchers.IO) {
        runCatching {
            if (Claude.key == null) throw ClaudeError("Add your Claude API key in Settings first.")
            _state.value = EngineState(true, "Thinking…")
            val cfg = config()
            val history = chat().takeLast(12)
            val stable = """
                You are the assistant inside Daybreak, the user's personal feed and calendar app. The user is talking to you
                about their feed. You can:
                - answer questions about the feed and the material behind it (only from what's given; say when you don't know);
                - change what the feed pays attention to, by rewriting the profile (return the full new profile text in
                  "profile", in the user's words where possible, short bullet points; "" = leave unchanged). Use this for
                  lasting preferences ("never show promotions", "I care about my PhD and my family", "write in Chinese");
                - change what it reads: news topics (Google News searches), RSS feeds (name + url, only real feed urls you
                  are sure of), web searches (questions Claude researches each update; these cost extra, so suggest them
                  only when topics/feeds can't cover it). Use the add_/remove_ fields; remove by exact existing name/text;
                - rewrite the current feed now (rewrite = true), with "focus" = a one-off emphasis for this version
                  (e.g. "only personal emails and weather"). Rewrite whenever the user asks to change the current feed,
                  or after changing preferences when they'd expect to see the effect.
                - add tasks and reminders when the user asks ("remind me to call mum tomorrow at 6", "add a task to pay the
                  rent by Friday", "add the form deadline from that email"): add_tasks with a short title, due as
                  "YYYY-MM-DD" or "YYYY-MM-DDTHH:MM" (work out dates from now; "" for a note without a deadline), and notes
                  (where it came from, or ""). Only when asked; otherwise add_tasks = [].
                Keep "reply" short and friendly: what you did or the answer. Write in the user's language.
            """.trimIndent()
            val user = buildString {
                appendLine("Now: ${LocalDateTime.now().format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy, HH:mm", Locale.ENGLISH))}.\n")
                appendLine("Current profile:\n${profile().ifBlank { "(empty)" }}\n")
                appendLine("Current sources: topics=${cfg.topics}, feeds=${cfg.feeds.map { it.name }}, web searches=${cfg.searches}, " +
                    "places=${Places.list().map { if (it.current) "current location" else it.name }}, email from=${com.qshlab.daybreak.sources.MailAccounts.list().map { it.email } + com.qshlab.daybreak.sources.SharedMail.boxes().map { (if (it.kind == "applemail") "Apple Mail" else "mail notifications") + " on " + it.deviceName }}\n")
                current()?.let { f -> appendLine("Current feed (written ${ago(f.generatedAt)}):\n${f.brief}\n" + f.cards.joinToString("\n") { "- [${it.kind}] ${it.title}: ${it.body}" } + "\n") }
                collected()?.let { appendLine("The material behind it:\n" + material(it, cfg, null).take(30000) + "\n") }
                if (history.isNotEmpty()) { appendLine("Conversation so far:"); history.forEach { appendLine("${it.role}: ${it.text}") } }
                appendLine("user: $message")
            }
            val r = Claude.ask(cfg.model, stable, user, chatSchema, effort = "medium", maxTokens = 8000)
            val out = json.decodeFromString<ChatOut>(r.text)
            val now = System.currentTimeMillis()
            File(home, "feed/chat.json").writeJson((chat() + ChatMsg("user", message, now) + ChatMsg("assistant", out.reply, now)).takeLast(30))
            if (out.profile.isNotBlank() && out.profile.trim() != profile().trim()) saveProfile(out.profile)
            val c2 = cfg.copy(
                topics = (cfg.topics - out.remove_topics.toSet() + out.add_topics).distinct(),
                feeds = (cfg.feeds.filterNot { f -> out.remove_feeds.any { it.equals(f.name, true) || it == f.url } } + out.add_feeds).distinctBy { it.url },
                searches = (cfg.searches - out.remove_searches.toSet() + out.add_searches).distinct())
            if (c2 != cfg) saveConfig(c2)
            out.add_tasks.filter { it.title.isNotBlank() }.forEach { addTask(it) }
            changed()
            out
        }.also { r -> _state.value = EngineState(false, "", r.exceptionOrNull()?.message) }
    }

    /** Adds a task to the default list (Reminders). Returns its id. */
    fun addTask(t: NewTask): String {
        val due = t.due.trim().takeIf { Regex("\\d{4}-\\d{2}-\\d{2}(T\\d{2}:\\d{2})?").matches(it) }
        val now = System.currentTimeMillis() / 1000.0
        val id = com.qshlab.daybreak.cal.newId()
        com.qshlab.daybreak.cal.Cal.saveTask(com.qshlab.daybreak.cal.Cal.DEFAULT_LIST, id,
            com.qshlab.daybreak.cal.Task(t.title.trim(), t.notes.trim(), due, created = now, updated = now))
        return id
    }

    fun clearChat() { File(home, "feed/chat.json").writeJson(emptyList<ChatMsg>()); changed() }

    // --- schedule

    private fun hm(s: String): LocalTime = runCatching { LocalTime.parse(s) }.getOrDefault(LocalTime.of(6, 0))

    /** When feeds should be ready on [day]. */
    fun slots(day: LocalDate, s: Schedule): List<LocalDateTime> = when (s.mode) {
        "daily" -> s.times.map { day.atTime(hm(it)) }.sorted()
        "interval" -> {
            val start = hm(s.quietEnd); val end = hm(s.quietStart)
            generateSequence(day.atTime(start)) { it.plusHours(s.everyHours.coerceIn(1, 24).toLong()) }
                .takeWhile { it.toLocalDate() == day && (end <= start || !it.toLocalTime().isAfter(end)) }.toList()
        }
        else -> emptyList()
    }

    fun nextSlot(after: LocalDateTime = LocalDateTime.now()): LocalDateTime? {
        val s = config().schedule
        return (0..2).asSequence().flatMap { slots(after.toLocalDate().plusDays(it.toLong()), s) }.firstOrNull { it.isAfter(after) }
    }

    /** Updates per day under the current schedule (for the cost estimate). */
    fun perDay(s: Schedule = config().schedule): Int = slots(LocalDate.now(), s).size

    /** Rough cost of one update with [model] (reading ~15k tokens of material, writing ~1.5k). */
    fun estimate(modelId: String): Double = Claude.model(modelId).let { 15_000 / 1e6 * it.inPrice + 2_500 / 1e6 * it.outPrice } + config().searches.size * 0.03

    @Serializable private data class Done(val generated: String = "", val notified: String = "")
    private val doneFile get() = File(home, "local/schedule.json")

    /**
     * Called every minute or so (Mac) or by alarms (Android). Writes the feed for a slot from 15 minutes before
     * it (one device only; others pick it up through sync), and notifies at the slot on the chosen devices.
     */
    suspend fun tick() {
        val s = config().schedule
        if (s.mode == "manual") return
        val now = LocalDateTime.now()
        val slot = (0..1).flatMap { slots(now.toLocalDate().plusDays(it.toLong()), s) }.filter { !it.minusMinutes(15).isAfter(now) }.maxOrNull() ?: return
        if (now.isAfter(slot.plusHours(3))) return      // missed (device was off); the next slot will do
        val done = doneFile.readJson<Done>() ?: Done()
        val slotMs = slot.minusMinutes(15).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        if (done.generated != slot.toString()) {
            withContext(Dispatchers.IO) { Daybreak.sync.runOnce() }
            val fresh = (current()?.generatedAt ?: 0) >= slotMs
            // While a Mac is awake it writes the scheduled feed (it has Apple Mail); the phone steps in only if it
            // hasn't by 10 minutes after the time.
            val leaveToMac = !fresh && Daybreak.platform.kind != "mac" && now.isBefore(slot.plusMinutes(10)) &&
                withContext(Dispatchers.IO) { Daybreak.sync.macAwake() }
            if (!leaveToMac) {
                if (!fresh && Claude.key != null && withContext(Dispatchers.IO) { Daybreak.sync.tryLease("feed", 600) }) refresh()
                doneFile.writeJson(done.copy(generated = slot.toString()))
            }
        }
        if (!now.isBefore(slot) && (doneFile.readJson<Done>() ?: Done()).notified != slot.toString()) {
            withContext(Dispatchers.IO) { Daybreak.sync.runOnce() }
            val f = current()?.takeIf { it.generatedAt >= slotMs }
            // No feed yet (another device is still writing it): try again later, up to half an hour after the time.
            if (f != null || now.isAfter(slot.plusMinutes(30))) {
                if (f != null && s.notify && Daybreak.sync.deviceId !in s.quietDevices) Daybreak.platform.notify("feed-$slot", "Your Daybreak", f.brief.take(240))
                doneFile.writeJson((doneFile.readJson<Done>() ?: Done()).copy(notified = slot.toString()))
            }
        }
    }
}
