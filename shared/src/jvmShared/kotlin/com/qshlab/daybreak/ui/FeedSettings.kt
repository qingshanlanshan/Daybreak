package com.qshlab.daybreak.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.qshlab.daybreak.Daybreak
import com.qshlab.daybreak.data.Changes
import com.qshlab.daybreak.feed.CLAUDE_MODELS
import com.qshlab.daybreak.feed.Claude
import com.qshlab.daybreak.feed.FeedEngine
import com.qshlab.daybreak.feed.RssSource
import com.qshlab.daybreak.sources.MailAccount
import com.qshlab.daybreak.sources.MailAccounts
import com.qshlab.daybreak.sources.MailReader
import com.qshlab.daybreak.sources.Outlook
import com.qshlab.daybreak.sources.SharedMail
import com.qshlab.daybreak.sources.Place
import com.qshlab.daybreak.sources.Places
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalTime

fun money(usd: Double): String = if (usd < 0.01) "$%.3f".format(usd) else "$%.2f".format(usd)

/** Built into release builds once registered with Microsoft; until then it can be entered in Settings. */
const val OUTLOOK_CLIENT_ID = ""

@Composable
fun ClaudeSection() {
    val version by Changes.version.collectAsState()
    val cfg = remember(version) { FeedEngine.config() }
    val hint = remember(version) { Claude.keyHint }
    val month = remember(version) { Claude.thisMonth() }
    val scope = rememberCoroutineScope()
    var key by remember { mutableStateOf("") }
    var test by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    Hint("Claude writes your feed and answers you. Only text is sent to Anthropic (email snippets, headlines, your events and tasks); it's not used for training. Your key is kept on your devices and syncs to them encrypted.")
    Text("API key", fontWeight = FontWeight.SemiBold)
    Hint(hint?.let { "Saved ($it)" } ?: "Not set — get one at console.anthropic.com → API keys.")
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(key, { key = it }, Modifier.weight(1f), placeholder = { Text(if (hint != null) "Replace with a new key" else "sk-ant-…") },
            singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
        Spacer(Modifier.width(8.dp))
        Button({ if (key.trim().startsWith("sk-ant-")) { Claude.key = key.trim(); key = ""; test = null } else test = "That doesn't look like an Anthropic API key (they start with sk-ant-)." },
            enabled = key.isNotBlank()) { Text("Save") }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton({ testing = true; test = null; scope.launch {
            test = withContext(Dispatchers.IO) { runCatching { "✓ ${Claude.model(cfg.model).name} answered: ${Claude.test(cfg.model)}" }.getOrElse { it.message } }; testing = false
        } }, enabled = hint != null && !testing) { Text(if (testing) "Testing…" else "Test connection") }
        if (hint != null) TextButton({ Claude.key = null }) { Text("Remove key", color = MaterialTheme.colorScheme.error) }
    }
    test?.let { Hint(it) }
    Text("Model", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
    for (m in CLAUDE_MODELS) Row(Modifier.fillMaxWidth().clickable { FeedEngine.saveConfig(cfg.copy(model = m.id)) }, verticalAlignment = Alignment.CenterVertically) {
        RadioButton(cfg.model == m.id, { FeedEngine.saveConfig(cfg.copy(model = m.id)) })
        Column { Text(m.name); Hint("${m.note} · about " + money(FeedEngine.estimate(m.id)) + " per update") }
    }
    Hint("This month (all your devices): ${month.requests} request${if (month.requests == 1) "" else "s"} · about $%.2f".format(month.usd))
}

@Composable
fun AboutMeSection() {
    val version by Changes.version.collectAsState()
    val saved = remember(version) { FeedEngine.profile() }
    var text by remember(saved) { mutableStateOf(saved) }
    Hint("What matters to you and what to skip. The feed follows this; when you tell Daybreak something lasting in the Feed tab, it updates this too.")
    OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), minLines = 4,
        placeholder = { Text("e.g. PhD student in Singapore. Care about: my research and supervisor's emails, family, MRT disruptions, haze and rain. Skip: politics, promotions, sports.") })
    if (text != saved) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button({ FeedEngine.saveProfile(text) }) { Text("Save") }
        TextButton({ text = saved }) { Text("Undo") }
    }
}

@Composable
fun ScheduleSection() {
    val version by Changes.version.collectAsState()
    val cfg = remember(version) { FeedEngine.config() }
    val s = cfg.schedule
    fun save(n: com.qshlab.daybreak.feed.Schedule) = FeedEngine.saveConfig(cfg.copy(schedule = n))
    Hint("Each update costs a little (Claude). Only one of your devices writes each update; the others get it through sync.")
    Menu("Update", when (s.mode) { "manual" -> "Only when I ask"; "interval" -> "Every ${s.everyHours} hours"; else -> "At set times" },
        listOf("At set times" to { save(s.copy(mode = "daily")) }, "Every few hours" to { save(s.copy(mode = "interval")) }, "Only when I ask" to { save(s.copy(mode = "manual")) }))
    when (s.mode) {
        "daily" -> {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                for ((i, t) in s.times.withIndex()) {
                    TimeField(LocalTime.parse(t), { nt -> save(s.copy(times = s.times.toMutableList().also { it[i] = nt.toString() }.sorted())) })
                    if (s.times.size > 1) TextButton({ save(s.copy(times = s.times - t)) }) { Text("✕") }
                }
                if (s.times.size < 6) TextButton({ save(s.copy(times = (s.times + "12:00").distinct().sorted())) }) { Text("+ Add a time") }
            }
            Hint("The feed is ready at these times (written a few minutes before).")
        }
        "interval" -> {
            Menu("Every", "${s.everyHours} hours", listOf(1, 2, 3, 4, 6, 8, 12).map { h -> "$h hours" to { save(s.copy(everyHours = h)) } })
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Quiet from"); TimeField(LocalTime.parse(s.quietStart), { save(s.copy(quietStart = it.toString())) })
                Text("to"); TimeField(LocalTime.parse(s.quietEnd), { save(s.copy(quietEnd = it.toString())) })
            }
        }
    }
    Daybreak.platform.openAtLogin()?.let { on ->
        var login by remember { mutableStateOf(on) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text("Open at login"); Hint("On the Mac, Daybreak updates and notifies while it's running (in the background is fine).") }
            Switch(login, { login = it; Daybreak.platform.setOpenAtLogin(it) })
        }
    }
    if (s.mode != "manual") Hint("≈ ${FeedEngine.perDay(s)} update${if (FeedEngine.perDay(s) == 1) "" else "s"} a day · about $%.2f a month with ${Claude.model(cfg.model).name}".format(FeedEngine.perDay(s) * 30 * FeedEngine.estimate(cfg.model)))
    if (s.mode != "manual") {
        Row(verticalAlignment = Alignment.CenterVertically) { Text("Notify me when it's ready", Modifier.weight(1f)); Switch(s.notify, { save(s.copy(notify = it)) }) }
        if (s.notify) {
            val me = Daybreak.sync.deviceId
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("…on this device", Modifier.weight(1f))
                Switch(me !in s.quietDevices, { on -> save(s.copy(quietDevices = if (on) s.quietDevices - me else (s.quietDevices + me).distinct())) })
            }
            if (Daybreak.sync.config.value != null) Hint("Each of your devices has this switch.")
        }
        var limited by remember { mutableStateOf(Daybreak.platform.backgroundLimited) }
        LaunchedEffect(limited) { while (limited) { kotlinx.coroutines.delay(2000); limited = Daybreak.platform.backgroundLimited } }
        if (limited) {
            Hint("Android's battery saving may stop updates and notifications while the phone sleeps.", color = MaterialTheme.colorScheme.error)
            OutlinedButton({ Daybreak.platform.allowBackground() }) { Text("Let Daybreak run in the background") }
        }
    }
    // What happened in the background lately: tells "not updated" apart from "updated but not notified".
    val log = remember(version) { com.qshlab.daybreak.feed.BackgroundLog.list().takeLast(10).reversed() }
    if (log.isNotEmpty()) {
        Text("Recent background activity", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
        for (e in log) Hint(java.text.SimpleDateFormat("d MMM HH:mm").format(java.util.Date(e.at)) + "  " + e.what)
    }
}

@Composable
fun SourcesSection() {
    val version by Changes.version.collectAsState()
    val cfg = remember(version) { FeedEngine.config() }
    var topic by remember { mutableStateOf("") }
    var feedName by remember { mutableStateOf("") }
    var feedUrl by remember { mutableStateOf("") }
    var search by remember { mutableStateOf("") }
    Hint("You can also just tell Daybreak in the Feed tab (\"follow news about AI research\", \"stop showing sports\").")
    for ((label, on, set) in listOf(
        Triple("Weather (for your places)", cfg.weather) { v: Boolean -> FeedEngine.saveConfig(cfg.copy(weather = v)) },
        Triple("Local news (for your places)", cfg.localNews) { v: Boolean -> FeedEngine.saveConfig(cfg.copy(localNews = v)) },
        Triple("Email", cfg.email) { v: Boolean -> FeedEngine.saveConfig(cfg.copy(email = v)) },
        Triple("Calendar and tasks", cfg.calendar) { v: Boolean -> FeedEngine.saveConfig(cfg.copy(calendar = v)) },
    )) Row(verticalAlignment = Alignment.CenterVertically) { Text(label, Modifier.weight(1f)); Switch(on, set) }
    Text("News topics", fontWeight = FontWeight.SemiBold)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        cfg.topics.forEach { t -> FilterChip(true, { FeedEngine.saveConfig(cfg.copy(topics = cfg.topics - t)) }, { Text("$t  ✕") }) }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(topic, { topic = it }, Modifier.weight(1f), singleLine = true, placeholder = { Text("A topic, e.g. housing grants") })
        TextButton({ FeedEngine.saveConfig(cfg.copy(topics = (cfg.topics + topic.trim()).distinct())); topic = "" }, enabled = topic.isNotBlank()) { Text("Add") }
    }
    Text("RSS feeds", fontWeight = FontWeight.SemiBold)
    cfg.feeds.forEach { f -> Row(verticalAlignment = Alignment.CenterVertically) { Text(f.name, Modifier.weight(1f)); TextButton({ FeedEngine.saveConfig(cfg.copy(feeds = cfg.feeds - f)) }) { Text("Remove") } } }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(feedName, { feedName = it }, Modifier.weight(0.4f), singleLine = true, placeholder = { Text("Name") })
        OutlinedTextField(feedUrl, { feedUrl = it.trim() }, Modifier.weight(0.6f), singleLine = true, placeholder = { Text("https://…/rss") })
        TextButton({ FeedEngine.saveConfig(cfg.copy(feeds = cfg.feeds + RssSource(feedName.ifBlank { feedUrl }, feedUrl))); feedName = ""; feedUrl = "" },
            enabled = feedUrl.startsWith("http")) { Text("Add") }
    }
    Text("Web searches", fontWeight = FontWeight.SemiBold)
    Hint("Questions Claude researches on the web each update — for what news and feeds don't cover. About 3¢ each per update.")
    cfg.searches.forEach { q -> Row(verticalAlignment = Alignment.CenterVertically) { Text(q, Modifier.weight(1f)); TextButton({ FeedEngine.saveConfig(cfg.copy(searches = cfg.searches - q)) }) { Text("Remove") } } }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(search, { search = it }, Modifier.weight(1f), singleLine = true, placeholder = { Text("e.g. road closures near Clementi this week") })
        TextButton({ FeedEngine.saveConfig(cfg.copy(searches = (cfg.searches + search.trim()).distinct())); search = "" }, enabled = search.isNotBlank()) { Text("Add") }
    }
}

@Composable
fun PlacesSection() {
    val version by Changes.version.collectAsState()
    val places = remember(version) { Places.list() }
    val here = remember(version) { Places.here() }
    val scope = rememberCoroutineScope()
    var q by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Place>>(emptyList()) }
    var msg by remember { mutableStateOf<String?>(null) }
    Hint("Weather and local news cover each of these.")
    for (p in places) Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(if (p.current) "📍 Current location" else p.name)
            Hint(if (p.current) here?.let { "Now: ${it.name}" + (it.country.takeIf { c -> c.isNotBlank() }?.let { c -> ", $c" } ?: "") +
                (if (it.approx) " (approximate, from your internet connection)" else "") } ?: "Not known yet" else p.country)
        }
        if (p.current) TextButton({ scope.launch { msg = withContext(Dispatchers.IO) { Places.locate() }.fold({ "You're in ${it.name}." }, { it.message }) } }) { Text("Update") }
        TextButton({ Places.save(places - p) }) { Text("Remove") }
    }
    if (places.none { it.current }) TextButton({ Places.save(listOf(Place("here", "Current location", current = true)) + places) }) { Text("+ Use my current location") }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(q, { q = it }, Modifier.weight(1f), singleLine = true, placeholder = { Text("Add a city") })
        TextButton({ scope.launch { results = withContext(Dispatchers.IO) { runCatching { Places.search(q) }.getOrElse { msg = it.message; emptyList() } } } }, enabled = q.isNotBlank()) { Text("Search") }
    }
    for (r in results) Text("${r.name}, ${r.country}", Modifier.fillMaxWidth().clickable { Places.save(places + r); results = emptyList(); q = "" }.padding(vertical = 8.dp),
        color = MaterialTheme.colorScheme.primary)
    msg?.let { Hint(it) }
}

@Composable
fun EmailSection() {
    val version by Changes.version.collectAsState()
    val accounts = remember(version) { MailAccounts.list() }
    val cfg = remember(version) { FeedEngine.config() }
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf<String?>(null) }    // "imap" | "outlook"
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var host by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    var code by remember { mutableStateOf<Outlook.DeviceCode?>(null) }
    var clientId by remember(cfg.outlookClientId) { mutableStateOf(cfg.outlookClientId.ifBlank { OUTLOOK_CLIENT_ID }) }
    val platform = Daybreak.platform
    val mac = platform.kind == "mac"
    // Permissions are granted in system settings: look again every few seconds while this page is open.
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(2000); tick++ } }
    val dm = platform.deviceMail
    val on = remember(version) { SharedMail.enabled() }
    val allowed = remember(tick, version) { dm?.allowed() ?: false }
    val boxes = remember(version, tick) { SharedMail.boxes() }
    val mine = boxes.firstOrNull { it.device == Daybreak.sync.deviceId && it.kind == dm?.kind }

    Hint("Read-only: Daybreak never sends, deletes or marks anything. Newsletters and promotions are labelled so the feed can skip them.")
    if (dm != null) {
        Text(if (mac) "On this Mac" else "On this phone", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(dm.label)
                Hint(when {
                    !on -> "Off"
                    !allowed -> "Needs notification access"
                    mine?.error != null -> mine.error
                    mine != null -> "${mine.items.size} email${if (mine.items.size == 1) "" else "s"} · ${if (mac) "read" else "updated"} ${ago(mine.at / 1000.0)}"
                    mac -> "Not read yet: Daybreak reads it before each feed update."
                    else -> "On: waiting for new mail."
                }, color = if (on && (mine?.error != null || !allowed)) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(on, { SharedMail.setEnabled(it) })
        }
        if (on && mine != null && mine.items.isNotEmpty()) Hint("From: " + mine.items.groupingBy { it.account.ifBlank { "?" } }.eachCount().entries
            .sortedByDescending { it.value }.joinToString(" · ") { "${it.key} (${it.value})" })
        if (mac) {
            Hint("Reads the inbox of every account in Apple Mail, school or work Outlook included. macOS asks once to let Daybreak control Mail. If Mail isn't open, Daybreak opens it in the background before a feed update so it fetches new mail, then quits it again.")
            if (on) OutlinedButton({
                busy = true; msg = null
                scope.launch { runCatching { dm.refresh(java.time.Instant.now().minusSeconds(3 * 86400), wake = true) }.onFailure { msg = it.message }; busy = false }
            }, enabled = !busy) { Text(if (busy) "Reading…" else "Read now") }
        } else {
            Hint("Daybreak reads the new-mail notifications of the Outlook and Gmail apps: sender, subject and a preview. It sees mail that arrives after you allow this, and only what the notification shows. When the whole email is available another way (an account below, or Apple Mail on your Mac through Sync), that's used instead.")
            if (on && !allowed) {
                Button({ dm.requestAccess() }) { Text("Allow notification access") }
                Hint("If Android says it's a restricted setting: open Daybreak's App info, tap ⋮ → Allow restricted settings, then allow notification access again.")
                TextButton({ dm.openAppSettings() }) { Text("Open App info →") }
            }
        }
    }

    val others = boxes.filter { it.device != Daybreak.sync.deviceId }
    if (others.isNotEmpty()) {
        Text("From your other devices", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
        for (b in others) Column {
            Text((if (b.kind == "applemail") "Apple Mail" else "Mail notifications") + " on " + b.deviceName)
            Hint("${b.items.size} email${if (b.items.size == 1) "" else "s"} · ${ago(b.at / 1000.0)}" + (b.error?.let { " · $it" } ?: "") +
                b.items.groupingBy { it.account.ifBlank { "?" } }.eachCount().entries.sortedByDescending { it.value }.joinToString(" · ", prefix = "\nFrom: ") { "${it.key} (${it.value})" })
        }
    } else if (!mac && Daybreak.sync.config.value == null) Hint("With Sync on, your phone's feed also gets the whole emails your Mac reads in Apple Mail.")

    Text("Accounts (optional)", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp))
    Hint("Sign in to a mailbox directly; it's read on every device. Gmail and others with an app password, or Outlook if your organisation allows it.")
    for (a in accounts) Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(a.email); Hint(if (a.kind == "outlook") "Outlook" else a.host) }
        TextButton({ MailAccounts.delete(a.id) }) { Text("Remove") }
    }
    when (mode) {
        null -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ mode = "imap"; msg = null }) { Text("Add with app password") }
            OutlinedButton({ mode = "outlook"; msg = null }) { Text("Add Outlook") }
        }
        "imap" -> {
            Hint("Gmail: turn on 2-Step Verification, then create an app password at myaccount.google.com/apppasswords and paste it here (not your normal password). iCloud, Yahoo, QQ and 163 mail have app passwords too.")
            TextButton({ platform.openUrl("https://myaccount.google.com/apppasswords") }) { Text("Open Google app passwords →") }
            OutlinedTextField(email, { email = it.trim(); host = MailAccounts.PRESETS[it.substringAfter('@', "").lowercase()] ?: host }, Modifier.fillMaxWidth(),
                label = { Text("Email address") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
            OutlinedTextField(password, { password = it.replace(" ", "") }, Modifier.fillMaxWidth(), label = { Text("App password") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation())
            OutlinedTextField(host, { host = it.trim() }, Modifier.fillMaxWidth(), label = { Text("IMAP server") }, singleLine = true)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({
                    busy = true; msg = null
                    val a = MailAccount(com.qshlab.daybreak.cal.newId(), "imap", email, host = host, password = password)
                    scope.launch {
                        msg = withContext(Dispatchers.IO) { runCatching { MailReader.fetch(a, Instant.now().minusSeconds(86400), 5) } }
                            .fold({ MailAccounts.save(a); mode = null; email = ""; password = ""; "Added ${a.email} (${it.size} emails in the last day)." }, { it.message })
                        busy = false
                    }
                }, enabled = !busy && email.contains('@') && password.isNotBlank() && host.isNotBlank()) { Text(if (busy) "Checking…" else "Add") }
                TextButton({ mode = null }) { Text("Cancel") }
            }
        }
        "outlook" -> {
            if (clientId.isBlank() || code == null) {
                Hint("Outlook and Microsoft 365 sign in with Microsoft. Daybreak asks only to read mail.")
                OutlinedTextField(clientId, { clientId = it.trim() }, Modifier.fillMaxWidth(), label = { Text("Outlook app ID (one-time setup)") }, singleLine = true)
                Hint("Register Daybreak once at entra.microsoft.com → App registrations → New registration (any accounts incl. personal; Authentication → allow public client flows; API permissions: Mail.Read). Paste its Application (client) ID here.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button({
                        busy = true; msg = null
                        if (clientId != cfg.outlookClientId) FeedEngine.saveConfig(cfg.copy(outlookClientId = clientId))
                        scope.launch {
                            withContext(Dispatchers.IO) { runCatching { Outlook.start(clientId) } }.onSuccess { c ->
                                code = c; platform.copyText(c.user_code); platform.openUrl(c.verification_uri)
                                withContext(Dispatchers.IO) { runCatching { Outlook.finish(clientId, c) } }
                                    .onSuccess { a -> MailAccounts.save(a); msg = "Added ${a.email}."; mode = null }.onFailure { msg = it.message }
                                code = null
                            }.onFailure { msg = it.message }
                            busy = false
                        }
                    }, enabled = !busy && clientId.isNotBlank()) { Text(if (busy) "Waiting…" else "Sign in with Microsoft") }
                    TextButton({ mode = null; code = null }) { Text("Cancel") }
                }
            }
            code?.let { c ->
                Text("Enter this code at ${c.verification_uri}:", fontWeight = FontWeight.SemiBold)
                Text(c.user_code, style = MaterialTheme.typography.headlineMedium)
                Hint("It's copied, and the page is open in your browser. Sign in, approve, then come back — this finishes by itself.")
            }
        }
    }
    msg?.let { Hint(it) }
}
