package com.qshlab.daybreak.ui

import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.outlined.ThumbDown
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.foundation.border
import com.qshlab.daybreak.feed.Source
import com.qshlab.daybreak.feed.NewTask
import com.qshlab.daybreak.feed.Feedback
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Source
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qshlab.daybreak.Daybreak
import com.qshlab.daybreak.data.Changes
import com.qshlab.daybreak.feed.Card
import com.qshlab.daybreak.feed.Claude
import com.qshlab.daybreak.feed.FeedEngine
import com.qshlab.daybreak.sources.Weather
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

private fun kindIcon(k: String) = when (k) { "email" -> "✉️"; "weather" -> "⛅"; "local" -> "📍"; "news" -> "📰"; "calendar" -> "📅"; "task" -> "✅"; else -> "✨" }

private fun greeting(): String = when (LocalTime.now().hour) { in 5..11 -> "Good morning"; in 12..17 -> "Good afternoon"; in 18..23 -> "Good evening"; else -> "Hello" }

@Composable
fun FeedScreen(onSettings: (() -> Unit)?, openSettings: () -> Unit) {
    val version by Changes.version.collectAsState()
    val st by FeedEngine.state.collectAsState()
    val feed = remember(version) { FeedEngine.current() }
    val collected = remember(version) { FeedEngine.collected() }
    val chat = remember(version) { FeedEngine.chat() }
    val reactions = remember(version) { FeedEngine.feedback().associateBy { it.title } }
    val placeOrder = remember(version) { com.qshlab.daybreak.sources.Places.resolved().map { it.name } }
    val hasKey = remember(version) { Claude.key != null }
    val scope = rememberCoroutineScope()
    var sources by remember { mutableStateOf(false) }
    var reply by remember { mutableStateOf<String?>(null) }

    TabScreen("Feed", onSettings = onSettings, actions = {
        IconButton({ sources = true }) { Icon(Icons.Default.Source, "What it read") }
        IconButton({ scope.launch { FeedEngine.update() } }, enabled = !st.running && hasKey) { Icon(Icons.Default.Refresh, "Update now") }
    }) {
        Column(Modifier.fillMaxSize()) {
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                item {
                    Column(Modifier.widthIn(max = 760.dp).fillMaxWidth()) {
                        Text(greeting(), fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
                        Hint(dateText(java.time.LocalDate.now(), withYear = false) + (collected?.weather?.firstOrNull()?.let { "  ·  ${Weather.icon(it.code)} ${it.place} %.0f°C".format(it.temp) } ?: ""))
                    }
                }
                if (!hasKey) item {
                    Notice("Add your Claude API key to get your feed", "Daybreak reads your email, calendar, weather and news, and Claude writes what matters to you. The key is stored on your devices only.") {
                        Button(openSettings) { Text("Open Settings") }
                    }
                }
                if (st.running) item { Column(Modifier.widthIn(max = 760.dp).fillMaxWidth()) { Hint(st.step); LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp)) } }
                st.error?.let { e -> item { Notice("Couldn't update", e) {} } }
                if (feed == null && hasKey && !st.running) item {
                    Notice("No feed yet", "Tap update to write your first one. Tell Daybreak about yourself below or in Settings → About me, so it knows what matters to you.") {
                        Button({ scope.launch { FeedEngine.update() } }) { Text("Write my feed") }
                    }
                }
                feed?.let { f ->
                    item {
                        Column(Modifier.widthIn(max = 760.dp).fillMaxWidth()) {
                            Text(f.brief, fontSize = 17.sp, lineHeight = 25.sp)
                            val by = remember(f.device, version) { if (f.device == Daybreak.sync.deviceId) null else Daybreak.sync.devices().firstOrNull { d -> d.id == f.device }?.name }
                            Hint("Updated ${ago(f.generatedAt / 1000.0)}" + (by?.let { " on $it" } ?: "") + (f.focus?.let { " · focus: $it" } ?: "") + " · about $%.2f".format(f.costUsd), Modifier.padding(top = 6.dp))
                        }
                    }
                    // Cards about one of your places come after the others, under the place's name, in your order of places.
                    val (local, general) = f.cards.withIndex().partition { it.value.place.isNotBlank() }
                    items(general, key = { "c${it.index}" }) { (_, c) -> FeedCard(c, reactions[c.title], version) }
                    val groups = local.groupBy { it.value.place }.toList()
                        .sortedBy { (p, _) -> placeOrder.indexOfFirst { it.equals(p, true) }.let { i -> if (i < 0) Int.MAX_VALUE else i } }
                    for ((place, cs) in groups) {
                        item(key = "p-$place") {
                            Text("📍 $place", Modifier.widthIn(max = 760.dp).fillMaxWidth().padding(top = 8.dp), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        }
                        items(cs, key = { "c${it.index}" }) { (_, c) -> FeedCard(c, reactions[c.title], version) }
                    }
                }
                item { Spacer(Modifier.size(8.dp)) }
            }
            ChatBar(chat.lastOrNull()?.takeIf { it.role == "assistant" && System.currentTimeMillis() - it.at < 6 * 3600_000L }?.text ?: reply,
                enabled = hasKey && !st.running, busy = st.running && st.step == "Thinking…") { msg ->
                scope.launch {
                    FeedEngine.say(msg).onSuccess { out -> reply = out.reply; if (out.rewrite) FeedEngine.refresh(out.focus.ifBlank { null }, reuse = true) }
                        .onFailure { reply = it.message }
                }
            }
        }
    }
    if (sources) SourcesSheet { sources = false }
}

@Composable
private fun Notice(title: String, text: String, actions: @Composable () -> Unit) {
    Surface(Modifier.widthIn(max = 760.dp).fillMaxWidth(), shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.primaryContainer) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold); Hint(text); actions()
        }
    }
}

private val REASONS = listOf("Not relevant to me", "I already knew", "Wrong or outdated", "Repeats another card", "Too long")

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FeedCard(c: Card, reaction: Feedback?, version: Int) {
    var askWhy by remember { mutableStateOf(false) }
    var other by remember { mutableStateOf("") }
    Surface(Modifier.widthIn(max = 760.dp).fillMaxWidth(), shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(kindIcon(c.kind), fontSize = 18.sp); Spacer(Modifier.width(10.dp))
                Text(c.title, Modifier.weight(1f), fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                if (c.priority == 1) Box(Modifier.size(8.dp).clip(CircleShape).background(Danger))
            }
            Text(c.body, lineHeight = 21.sp)
            if (c.why.isNotBlank()) Hint(c.why)
            // What it's based on, as a footnote: "Sources: CNA ↗ · Email from Prof Tan ↗". Older feeds only kept addresses.
            val links = c.sources.ifEmpty { c.links.map { Source(siteName(it), it) } }
            if (links.isNotEmpty()) FlowRow(verticalArrangement = Arrangement.Center) {
                val gray = MaterialTheme.colorScheme.onSurfaceVariant
                Text(if (links.size == 1) "Source: " else "Sources: ", fontSize = 12.sp, color = gray, modifier = Modifier.padding(vertical = 4.dp))
                links.take(4).forEachIndexed { i, l ->
                    if (i > 0) Text(" · ", fontSize = 12.sp, color = gray, modifier = Modifier.padding(vertical = 4.dp))
                    Text(l.label + " ↗", Modifier.clip(RoundedCornerShape(4.dp)).clickable { Daybreak.platform.openUrl(l.url) }.padding(vertical = 4.dp),
                        fontSize = 12.sp, color = gray, maxLines = 1)
                }
            }
            if (c.task_title.isNotBlank()) TaskSuggestion(c, version)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (reaction?.verdict == "down") Hint(if (reaction.note.isNotBlank()) "👎 ${reaction.note}. Daybreak will show less like this." else "Daybreak will show less like this.", Modifier.weight(1f))
                else Spacer(Modifier.weight(1f))
                Vote(Icons.Outlined.ThumbUp, Icons.Filled.ThumbUp, "Good card", reaction?.verdict == "up") { askWhy = false; FeedEngine.react(c, "up") }
                Spacer(Modifier.width(8.dp))
                Vote(Icons.Outlined.ThumbDown, Icons.Filled.ThumbDown, "Bad card", reaction?.verdict == "down") { FeedEngine.react(c, "down"); askWhy = true }
            }
            if (askWhy) {
                Hint("What was wrong with it? (optional)")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    REASONS.forEach { r -> FilterChip(false, { FeedEngine.react(c, "down", r); askWhy = false }, { Text(r) }) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(other, { other = it }, Modifier.weight(1f), singleLine = true, placeholder = { Text("Something else…") })
                    TextButton({ FeedEngine.react(c, "down", other.trim()); askWhy = false; other = "" }, enabled = other.isNotBlank()) { Text("Send") }
                    TextButton({ askWhy = false }) { Text("Skip") }
                }
            }
        }
    }
}

/** 👍 / 👎: an outlined icon in a thin gray circle; filled in the accent colour once chosen. */
@Composable
private fun Vote(icon: androidx.compose.ui.graphics.vector.ImageVector, chosenIcon: androidx.compose.ui.graphics.vector.ImageVector,
                 label: String, chosen: Boolean, onClick: () -> Unit) {
    val color = if (chosen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Box(Modifier.size(34.dp).clip(CircleShape).border(1.dp, if (chosen) color else MaterialTheme.colorScheme.outlineVariant, CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center) {
        Icon(if (chosen) chosenIcon else icon, label, Modifier.size(17.dp), tint = color)
    }
}

/** A readable name for a web address: "straitstimes.com"; Google News links say "Google News". */
private fun siteName(url: String): String = runCatching { java.net.URI(url).host.removePrefix("www.") }.getOrNull()
    ?.let { if (it == "news.google.com") "Google News" else it } ?: "Link"

/** "+ Add task: Submit the ethics form · due Fri 24 Oct" — or a note that it's already in your tasks. */
@Composable
private fun TaskSuggestion(c: Card, version: Int) {
    val added = remember(version, c.task_title) { com.qshlab.daybreak.cal.Cal.tasks().any { it.task.title.equals(c.task_title.trim(), true) } }
    val due = remember(c.task_due) {
        runCatching {
            if (c.task_due.length > 10) java.time.LocalDateTime.parse(c.task_due).let { relativeDay(it.toLocalDate()) + " " + timeText(it.toLocalTime()) }
            else if (c.task_due.isNotBlank()) relativeDay(java.time.LocalDate.parse(c.task_due)) else null
        }.getOrNull()
    }
    val label = c.task_title + (due?.let { " · due $it" } ?: "")
    if (added) Hint("✓ In your tasks: $label")
    else OutlinedButton({ FeedEngine.addTask(NewTask(c.task_title, c.task_due, "From your feed: ${c.title}")) }) { Text("+ Add task: $label") }
}

@Composable
private fun ChatBar(lastReply: String?, enabled: Boolean, busy: Boolean, send: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Column(Modifier.widthIn(max = 760.dp).fillMaxWidth()) {
                if (busy) Hint("Thinking…", Modifier.padding(bottom = 6.dp))
                else lastReply?.let { Text(it, Modifier.fillMaxWidth().heightIn(max = 160.dp).padding(bottom = 6.dp), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    fun go() { if (text.isNotBlank()) { send(text.trim()); text = "" } }
                    OutlinedTextField(text, { text = it }, Modifier.weight(1f), enabled = enabled,
                        placeholder = { Text("Ask, or tell Daybreak what to focus on…") }, maxLines = 4,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { go() }))
                    IconButton({ go() }, enabled = enabled && text.isNotBlank()) { Icon(Icons.AutoMirrored.Filled.Send, "Send") }
                }
            }
        }
    }
}

/** Everything the last update read (raw), and what failed. */
@Composable
private fun SourcesSheet(onClose: () -> Unit) {
    val c = remember { FeedEngine.collected() }
    Sheet(onClose) {
        Text("What Daybreak read", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        if (c == null) { Hint("Nothing yet: it reads your sources when it writes the feed."); TextButton(onClose) { Text("Close") }; return@Sheet }
        Hint("Read ${ago(c.at / 1000.0)}. This stays on this device; only the feed itself syncs.")
        if (c.errors.isNotEmpty()) { Text("Problems", fontWeight = FontWeight.SemiBold); c.errors.forEach { Hint("• $it", color = MaterialTheme.colorScheme.error) } }
        if (c.weather.isNotEmpty()) { Text("Weather", fontWeight = FontWeight.SemiBold); c.weather.forEach { w ->
            Text("${Weather.icon(w.code)} ${w.place}: ${w.now}"); Hint("Today ${w.today}" + (w.air?.let { " · $it" } ?: "") + (if (w.nextHours.isNotEmpty()) "\n" + w.nextHours.joinToString(", ") else "")) } }
        if (c.events.isNotEmpty() || c.tasks.isNotEmpty()) { Text("Calendar & tasks", fontWeight = FontWeight.SemiBold); (c.events + c.tasks).forEach { Hint("• $it") } }
        if (c.mail.isNotEmpty()) {
            Text("Email (${c.mail.size}, last two days)", fontWeight = FontWeight.SemiBold)
            Hint("By mailbox: " + c.mail.groupingBy { it.account.ifBlank { "?" } }.eachCount().entries.sortedByDescending { it.value }.joinToString(" · ") { "${it.key} ${it.value}" })
            c.mail.forEach { m ->
                Hint((if (c.newSince > 0 && m.received >= c.newSince) "• new · " else "") + (if (m.bulk) "[newsletter] " else "") + m.from.substringBefore(" <") + " — " + m.subject)
            }
        }
        if (c.news.isNotEmpty()) { Text("News (${c.news.size})", fontWeight = FontWeight.SemiBold); c.news.forEach { n -> Hint("• [${n.via}] ${n.title} — ${n.source}") } }
        if (c.web.isNotBlank()) { Text("Web searches", fontWeight = FontWeight.SemiBold); Hint(c.web) }
        HorizontalDivider()
        TextButton(onClose) { Text("Close") }
    }
}
