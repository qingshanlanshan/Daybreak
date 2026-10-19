package com.qshlab.daybreak.desktop

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.qshlab.daybreak.Daybreak
import com.qshlab.daybreak.Platform
import com.qshlab.daybreak.ui.DaybreakApp
import com.qshlab.daybreak.update.MacUpdater
import com.qshlab.daybreak.update.Updates
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

    override fun openUrl(url: String) { ProcessBuilder("open", url).start() }
    override fun copyText(text: String) { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null) }
}

fun main() {
    Daybreak.start(MacPlatform())
    Thread { runBlocking { Updates.check() } }.start()
    application {
        val state = rememberWindowState(width = 1180.dp, height = 780.dp, position = WindowPosition.PlatformDefault)
        Window(onCloseRequest = ::exitApplication, state = state, title = "Daybreak") {
            window.minimumSize = java.awt.Dimension(720, 480)
            DaybreakApp()
        }
    }
}
