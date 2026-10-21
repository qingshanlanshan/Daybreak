package com.qshlab.daybreak.desktop

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.qshlab.daybreak.Daybreak
import com.qshlab.daybreak.Platform
import com.qshlab.daybreak.ui.DaybreakApp
import com.qshlab.daybreak.notify.MacNotifier
import com.qshlab.daybreak.update.MacUpdater
import com.qshlab.daybreak.update.Updates
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File

/** The Mac app. DAYBREAK_HOME overrides the data folder (for testing). */
class MacPlatform : Platform {
    override val home = File(System.getenv("DAYBREAK_HOME") ?: (System.getProperty("user.home") + "/Library/Application Support/Daybreak"))
    override val kind = "mac"
    override val deviceName: String = runCatching {
        ProcessBuilder("scutil", "--get", "ComputerName").start().inputStream.bufferedReader().readText().trim()
    }.getOrNull()?.ifBlank { null } ?: "Mac"
    /** Set by the app's launcher (jpackage); absent when running from source. */
    override val appVersion: String = System.getProperty("jpackage.app-version") ?: "dev"
    private val bundle: File? = System.getProperty("jpackage.app-path")?.let { File(it).parentFile?.parentFile?.parentFile }
        ?.takeIf { it.name.endsWith(".app") }
    override val updater = MacUpdater(bundle, appVersion, File(home, "local"))
    val notifier = MacNotifier(bundle?.let { File(it, "Contents/MacOS/daybreak-helper") }, File(home, "local"))

    override fun openUrl(url: String) { ProcessBuilder("open", url).start() }
    override val deviceMail = com.qshlab.daybreak.sources.AppleMail(File(home, "local"))
    override fun copyText(text: String) { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null) }

    override fun notify(id: String, title: String, body: String) { notifier.show(id, title, body) }

    override suspend fun locate(): Result<Pair<Double, Double>> = notifier.location()

    private val agent = File(System.getProperty("user.home"), "Library/LaunchAgents/com.qshlab.daybreak.plist")
    override fun openAtLogin(): Boolean? = if (bundle == null) null else agent.exists()
    override fun setOpenAtLogin(on: Boolean) {
        val app = bundle ?: return
        if (!on) { agent.delete(); return }
        agent.parentFile.mkdirs()
        agent.writeText(listOf(
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>",
            "<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" \"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">",
            "<plist version=\"1.0\"><dict>",
            "  <key>Label</key><string>com.qshlab.daybreak</string>",
            "  <key>ProgramArguments</key><array><string>/usr/bin/open</string><string>-g</string><string>-a</string><string>${app.path}</string>" +
                "<string>--args</string><string>--background</string></array>",
            "  <key>RunAtLoad</key><true/>",
            "</dict></plist>",
        ).joinToString("\n", postfix = "\n"))
    }

    override fun pickFile(onResult: (Pair<String, ByteArray>?) -> Unit) {
        java.awt.EventQueue.invokeLater {
            val d = java.awt.FileDialog(null as java.awt.Frame?, "Open", java.awt.FileDialog.LOAD).apply { isVisible = true }
            onResult(d.file?.let { f -> File(d.directory, f).let { it.name to it.readBytes() } })
        }
    }

    override fun saveFile(name: String, mime: String, bytes: ByteArray, onDone: (Boolean) -> Unit) {
        java.awt.EventQueue.invokeLater {
            val d = java.awt.FileDialog(null as java.awt.Frame?, "Save", java.awt.FileDialog.SAVE).apply { file = name; isVisible = true }
            onDone(d.file?.let { f -> runCatching { File(d.directory, f).writeBytes(bytes) }.isSuccess } ?: false)
        }
    }
}

fun main(args: Array<String>) {
    val platform = MacPlatform()
    Daybreak.start(platform)
    if ("--selfcheck" in args) {   // diagnostics: write the feed once, report, quit
        val r = runBlocking { com.qshlab.daybreak.feed.FeedEngine.refresh() }
        val c = com.qshlab.daybreak.feed.FeedEngine.collected()
        File(platform.home, "local/selfcheck.txt").apply { parentFile.mkdirs() }.writeText(r.fold({ "ok: ${it.brief} (${it.cards.size} cards)" }, { "error: " + it.stackTraceToString().take(3000) }) +
            "\nread: weather=${c?.weather?.size} news=${c?.news?.size} mail=${c?.mail?.size} errors=${c?.errors}\n")
        kotlin.system.exitProcess(0)
    }
    if ("--mailcheck" in args) {   // diagnostics: read Apple Mail once and report counts only
        runBlocking { runCatching { platform.deviceMail.refresh(java.time.Instant.now().minusSeconds(3 * 86400), wake = true) } }
        val b = com.qshlab.daybreak.sources.SharedMail.mine("applemail")
        File(platform.home, "local/mailcheck.txt").apply { parentFile.mkdirs() }.writeText(
            "emails=${b?.items?.size} accounts=${b?.items?.map { it.account.length }?.distinct()} withText=${b?.items?.count { it.snippet.isNotBlank() }} " +
            "bulk=${b?.items?.count { it.bulk }} links=${b?.items?.count { it.url.startsWith("message://") }} error=${b?.error} ms=${com.qshlab.daybreak.sources.AppleMail.lastTiming}\n")
        kotlin.system.exitProcess(0)
    }
    platform.notifier.start()
    // The scheduled feed: checked every minute while Daybreak runs (window open or not).
    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
        var mailAt = 0L
        while (true) {
            runCatching { com.qshlab.daybreak.feed.FeedEngine.tick() }
            // Every 15 minutes, while Apple Mail is open anyway: keep the synced copy fresh for the phone's feed.
            if (System.currentTimeMillis() - mailAt > 15 * 60_000L && com.qshlab.daybreak.sources.SharedMail.enabled()) {
                mailAt = System.currentTimeMillis()
                runCatching { platform.deviceMail.refresh(java.time.Instant.now().minusSeconds(3 * 86400), wake = false) }
            }
            kotlinx.coroutines.delay(60_000)
        }
    }
    Thread { runBlocking { Updates.check() } }.start()
    application {
        // Closing the window keeps Daybreak running (reminders, the scheduled feed); the Dock icon brings it back.
        var visible by remember { mutableStateOf("--background" !in args) }
        LaunchedEffect(Unit) {
            runCatching {
                java.awt.Desktop.getDesktop().addAppEventListener(java.awt.desktop.AppReopenedListener { visible = true })
                java.awt.Desktop.getDesktop().setQuitHandler { _, r -> r.performQuit() }
            }
        }
        val state = rememberWindowState(width = 1180.dp, height = 780.dp, position = WindowPosition.PlatformDefault)
        Window(onCloseRequest = { visible = false }, visible = visible, state = state, title = "Daybreak") {
            window.minimumSize = java.awt.Dimension(720, 480)
            LaunchedEffect(visible) { if (visible) { window.toFront(); Daybreak.sync.kick() } }
            DaybreakApp()
        }
    }
}
