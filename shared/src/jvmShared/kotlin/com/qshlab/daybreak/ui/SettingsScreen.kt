package com.qshlab.daybreak.ui

import androidx.compose.foundation.Canvas
import com.qshlab.daybreak.data.Changes
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.qshlab.daybreak.Daybreak
import com.qshlab.daybreak.update.Updates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date

/** The sections of Settings: each is a row on the main page and opens its own page. */
enum class SettingsPage(val title: String) {
    Claude("Claude"), AboutMe("About me"), Schedule("Updates & notifications"), Places("Places"), Email("Email"),
    Sources("What the feed reads"), Sync("Sync"), App("App updates")
}

@Composable
fun SettingsScreen(onBack: () -> Unit, start: SettingsPage? = null) {
    var page by rememberSaveable { mutableStateOf(start) }
    BackHandler(page != null) { page = null }
    val p = page
    if (p != null) {
        TabScreen(p.title, onBack = { page = null }) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 32.dp)) {
                Column(Modifier.widthIn(max = 720.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    when (p) {
                        SettingsPage.Claude -> ClaudeSection()
                        SettingsPage.AboutMe -> AboutMeSection()
                        SettingsPage.Schedule -> ScheduleSection()
                        SettingsPage.Places -> PlacesSection()
                        SettingsPage.Email -> EmailSection()
                        SettingsPage.Sources -> SourcesSection()
                        SettingsPage.Sync -> SyncSection()
                        SettingsPage.App -> UpdatesSection()
                    }
                }
            }
        }
        return
    }
    val version by Changes.version.collectAsState()
    val sync by Daybreak.sync.config.collectAsState()
    val update by Updates.available.collectAsState()
    TabScreen("Settings", onBack = onBack) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp).padding(bottom = 32.dp)) {
            Column(Modifier.widthIn(max = 720.dp)) {
                for (pg in SettingsPage.entries) {
                    val summary = remember(version, sync, update, pg) { summary(pg) }
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { page = pg }.padding(horizontal = 8.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(pg.title, fontWeight = FontWeight.SemiBold)
                            Hint(summary.first, color = if (summary.second) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text("›", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 22.sp)
                    }
                    HorizontalDivider(Modifier.padding(horizontal = 8.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                }
                Daybreak.platform.addWidget?.let { add ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { add() }.padding(horizontal = 8.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Home screen widget", fontWeight = FontWeight.SemiBold)
                            Hint("Add the month to your home screen; tap a day there to add a task")
                        }
                        Text("+", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 22.sp)
                    }
                }
                Hint("Daybreak ${Daybreak.platform.appVersion} · qshlab", Modifier.padding(start = 8.dp, top = 24.dp))
            }
        }
    }
}

/** One line under each section on the main Settings page, and whether it needs attention. */
private fun summary(p: SettingsPage): Pair<String, Boolean> = runCatching {
    val cfg = com.qshlab.daybreak.feed.FeedEngine.config()
    when (p) {
        SettingsPage.Claude -> if (com.qshlab.daybreak.feed.Claude.keyHint == null) "Add your API key to get your feed" to true
            else com.qshlab.daybreak.feed.Claude.model(cfg.model).name to false
        SettingsPage.AboutMe -> com.qshlab.daybreak.feed.FeedEngine.profile().replace('\n', ' ').take(70).ifBlank { "Tell Daybreak what matters to you" } to false
        SettingsPage.Schedule -> cfg.schedule.let { s ->
            when (s.mode) { "manual" -> "Only when you ask"; "interval" -> "Every ${s.everyHours} hours"; else -> "At ${s.times.joinToString(", ")}" } +
                if (s.notify) " · notifies" else ""
        } to false
        SettingsPage.Places -> com.qshlab.daybreak.sources.Places.list().joinToString(", ") { if (it.current) "Current location" else it.name }.ifBlank { "None" } to false
        SettingsPage.Email -> {
            val dm = Daybreak.platform.deviceMail
            val parts = buildList {
                if (dm != null && com.qshlab.daybreak.sources.SharedMail.enabled()) add(if (dm.allowed()) dm.label else "${dm.label}: needs access")
                val n = com.qshlab.daybreak.sources.MailAccounts.list().size
                if (n > 0) add("$n account${if (n == 1) "" else "s"}")
            }
            parts.joinToString(" · ").ifEmpty { "Off" } to (dm != null && com.qshlab.daybreak.sources.SharedMail.enabled() && !dm.allowed())
        }
        SettingsPage.Sources -> listOfNotNull("Weather".takeIf { cfg.weather }, "local news".takeIf { cfg.localNews }, "email".takeIf { cfg.email },
            "calendar".takeIf { cfg.calendar }, "${cfg.topics.size} topics".takeIf { cfg.topics.isNotEmpty() }).joinToString(", ") to false
        SettingsPage.Sync -> (Daybreak.sync.config.value?.let { "On · ${Daybreak.sync.devices().size} devices" } ?: "Off") to false
        SettingsPage.App -> (Updates.available.value?.let { "Daybreak ${it.version} is available" } ?: "Version ${Daybreak.platform.appVersion}") to (Updates.available.value != null)
    }
}.getOrElse { "" to false }

fun ago(t: Double): String {
    val s = System.currentTimeMillis() / 1000.0 - t
    return when {
        s < 60 -> "just now"; s < 3600 -> "${(s / 60).toInt()} min ago"; s < 86400 -> "${(s / 3600).toInt()} h ago"
        else -> SimpleDateFormat("d MMM").format(Date((t * 1000).toLong()))
    }
}

/** Sync through the user's own Cloudflare R2 bucket. Off by default. */
@Composable
fun SyncSection() {
    val sync = Daybreak.sync
    val platform = Daybreak.platform
    val scope = rememberCoroutineScope()
    val cfg by sync.config.collectAsState()
    val st by sync.status.collectAsState()
    var mode by remember { mutableStateOf<String?>(null) }      // "paste" | "setup"
    var account by remember { mutableStateOf("") }
    var bucket by remember { mutableStateOf("daybreak-sync") }
    var keyId by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    var showCode by remember { mutableStateOf(false) }
    var confirmOff by remember { mutableStateOf(false) }

    fun act(block: () -> Unit) {
        busy = true; err = null
        scope.launch {
            err = withContext(Dispatchers.IO) { runCatching { block(); null }.getOrElse { it.message ?: "Something went wrong." } }
            busy = false
            if (err == null) { mode = null; secret = ""; code = "" }
        }
    }

    val c = cfg
    if (c == null) {
        Hint("Keep your calendar, tasks and feed the same on your phone and your Mac, through your own Cloudflare R2 storage (free up to 10 GB). Everything is encrypted on the device before upload, so Cloudflare can't read it.")
        when (mode) {
            null -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val scan = platform.scanQr
                    if (scan != null) Button({ scan { v -> if (v != null) act { sync.join(v) } } }, enabled = !busy) { Text(if (busy) "Joining…" else "Scan code") }
                    OutlinedButton({ mode = "paste"; err = null }) { Text("Paste code") }
                    if (scan == null) Button({ mode = "setup"; err = null }) { Text("Set up sync") }
                }
                if (platform.scanQr != null) TextButton({ mode = "setup"; err = null }) { Text("No other device yet? Set up sync here") }
                Hint("Already syncing on another device? There: Settings → Sync → “Code for another device”.")
            }
            "paste" -> {
                OutlinedTextField(code, { code = it.trim() }, Modifier.fillMaxWidth(), placeholder = { Text("daybreak1:…") }, minLines = 2,
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Uri))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button({ act { sync.join(code) } }, enabled = !busy && code.isNotBlank()) { Text(if (busy) "Joining…" else "Join") }
                    TextButton({ mode = null; err = null }) { Text("Cancel") }
                }
            }
            "setup" -> {
                Hint("1. In the Cloudflare dashboard → R2, create a bucket, for example “daybreak-sync”.\n2. In R2 → Manage API tokens → Create API token: Object Read & Write, limited to that bucket. Copy the access key ID and secret.\n3. Fill these in. The account ID is on the R2 overview page.")
                TextButton({ platform.openUrl("https://dash.cloudflare.com/?to=/:account/r2/overview") }) { Text("Open Cloudflare R2 →") }
                OutlinedTextField(account, { account = it }, Modifier.fillMaxWidth(), label = { Text("Account ID") }, singleLine = true)
                OutlinedTextField(bucket, { bucket = it }, Modifier.fillMaxWidth(), label = { Text("Bucket") }, singleLine = true)
                OutlinedTextField(keyId, { keyId = it }, Modifier.fillMaxWidth(), label = { Text("Access key ID") }, singleLine = true)
                OutlinedTextField(secret, { secret = it }, Modifier.fillMaxWidth(), label = { Text("Secret access key") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button({ act { sync.connect(account, bucket, keyId, secret) } },
                        enabled = !busy && listOf(account, bucket, keyId, secret).all { it.isNotBlank() }) { Text(if (busy) "Connecting…" else "Connect") }
                    TextButton({ mode = null; err = null }) { Text("Cancel") }
                }
            }
        }
        err?.let { Hint(it, color = MaterialTheme.colorScheme.error) }
        return
    }

    val devices = remember(st) { sync.devices() }
    Text("On · bucket “${c.bucket}”", fontWeight = FontWeight.SemiBold)
    Hint(when { st.running -> "Syncing…"; st.error != null -> st.error!!; st.last != null -> "Synced ${ago(st.last!!)}"; else -> "Waiting…" },
        color = if (st.error != null && !st.running) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
    for (d in devices) Row(Modifier.fillMaxWidth()) {
        Text((if (d.platform == "android") "📱 " else "💻 ") + d.name + (if (d.me) " (this device)" else ""), Modifier.weight(1f))
        if (!d.me) Hint("seen ${ago(d.seen)}")
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton({ sync.kick() }, enabled = !st.running) { Text("Sync now") }
        OutlinedButton({ showCode = !showCode }) { Text(if (showCode) "Hide code" else "Code for another device") }
    }
    if (showCode) {
        val text = remember { sync.code() }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Qr(text, Modifier.size(180.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Hint("On your phone: Settings → Sync → Scan code. On another Mac: Paste code.")
                Hint("Anyone with this code can read and change your Daybreak data. Don't share it.", color = MaterialTheme.colorScheme.error)
                OutlinedButton({ platform.shareText(text, "Daybreak sync code") }) { Text(if (platform.kind == "android") "Share code" else "Copy code") }
            }
        }
    }
    TextButton({ confirmOff = true }) { Text("Turn off sync", color = MaterialTheme.colorScheme.error) }
    if (confirmOff) AlertDialog(onDismissRequest = { confirmOff = false },
        title = { Text("Turn off sync?") },
        text = { Text("Nothing is deleted, here or in your bucket. You can join again with a code.") },
        confirmButton = { TextButton({ confirmOff = false; showCode = false; sync.disconnect() }) { Text("Turn off", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton({ confirmOff = false }) { Text("Cancel") } })
}

/** A QR code, drawn square by square. */
@Composable
fun Qr(text: String, modifier: Modifier = Modifier) {
    val m = remember(text) {
        QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 2))
    }
    Canvas(modifier.clip(RoundedCornerShape(8.dp)).background(Color.White)) {
        val cell = size.minDimension / m.width
        for (y in 0 until m.height) for (x in 0 until m.width) if (m.get(x, y))
            drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
    }
}

@Composable
fun UpdatesSection() {
    val scope = rememberCoroutineScope()
    val update by Updates.available.collectAsState()
    var checking by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<Float?>(null) }
    var msg by remember { mutableStateOf<String?>(null) }
    var auto by remember { mutableStateOf(Updates.autoCheck()) }

    Hint("Installed: ${Daybreak.platform.appVersion}. New versions come from daybreak.qshlab.com.")
    Row(verticalAlignment = Alignment.CenterVertically) { Text("Check automatically", Modifier.weight(1f)); Switch(auto, { auto = it; Updates.setAutoCheck(it) }) }
    update?.let { u ->
        Text("Daybreak ${u.version} is available.", fontWeight = FontWeight.SemiBold)
        if (u.notes.isNotBlank()) Hint(u.notes)
        progress?.let { LinearProgressIndicator({ it }, Modifier.fillMaxWidth()) }
        Button({
            progress = 0f; msg = null
            scope.launch { runCatching { Daybreak.platform.updater.install(u) { progress = it } }.onFailure { msg = it.message }; progress = null }
        }, enabled = progress == null) { Text(if (Daybreak.platform.kind == "android") "Download and install" else "Update and restart") }
    } ?: OutlinedButton({
        checking = true; msg = null
        scope.launch { val r = Updates.check(force = true); checking = false; if (r == null) msg = "Daybreak is up to date." }
    }, enabled = !checking) { Text(if (checking) "Checking…" else "Check for updates") }
    msg?.let { Hint(it) }
}
