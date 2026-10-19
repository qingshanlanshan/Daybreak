package com.qshlab.daybreak.desktop

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import com.qshlab.daybreak.Daybreak
import com.qshlab.daybreak.ui.DaybreakApp
import com.qshlab.daybreak.ui.Tab
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.system.exitProcess

/** Draws screens to PNG files (development check of layouts; no window). */
fun main(args: Array<String>) {
    val out = File(args[0]).apply { mkdirs() }
    Daybreak.start(MacPlatform())
    fun shot(name: String, w: Int, h: Int, content: @androidx.compose.runtime.Composable () -> Unit) {
        ImageComposeScene(w, h, Density(2f), content = content).use { scene ->
            scene.render(0); val img = scene.render(500_000_000)
            File(out, "$name.png").writeBytes(img.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        }
    }
    shot("mac-feed", 2360, 1560) { DaybreakApp() }
    shot("mac-settings", 2360, 1560) { DaybreakApp(startInSettings = true) }
    shot("phone-calendar", 820, 1700) { DaybreakApp(startTab = Tab.Calendar) }
    shot("phone-settings", 820, 1700) { DaybreakApp(startInSettings = true) }
    println("snapshots in $out")
    exitProcess(0)
}
