package com.qshlab.daybreak.notify

import com.qshlab.daybreak.data.readJson
import com.qshlab.daybreak.data.writeJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.io.File
import java.time.LocalDateTime

/**
 * Notifications on the Mac, through daybreak-helper inside the app (so they show as Daybreak); when running
 * from source there's no helper and they go through osascript instead.
 */
class MacNotifier(private val helper: File?, private val local: File) {
    fun show(id: String, title: String, body: String): String? = runCatching {
        val cmd = if (helper?.exists() == true) listOf(helper.path, "notify", id, title, body)
            else listOf("osascript", "-e", "display notification \"${body.replace("\"", "'")}\" with title \"${title.replace("\"", "'")}\"")
        val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText().trim()
        if (p.waitFor() == 0) null else out.ifBlank { "couldn't notify" }
    }.getOrElse { it.message }

    /** Current location from the helper: (lat, lon), or an error message. */
    fun location(): Result<Pair<Double, Double>> = runCatching {
        val h = helper?.takeIf { it.exists() } ?: error("Location needs the installed app.")
        val p = ProcessBuilder(h.path, "location").redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText().trim()
        p.waitFor()
        if (out.startsWith("error:")) error(out.removePrefix("error:").trim())
        out.split(',').let { it[0].toDouble() to it[1].toDouble() }
    }

    @Serializable private data class Seen(val until: String = "")

    /** Checks every 30 s for alerts that came due and shows them (each once). */
    fun start() {
        val f = File(local, "alerts-seen.json")
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            var since = f.readJson<Seen>()?.until?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }
                ?.coerceAtLeast(LocalDateTime.now().minusMinutes(10)) ?: LocalDateTime.now()
            while (true) {
                val now = LocalDateTime.now()
                runCatching { Reminders.between(since, now) }.getOrDefault(emptyList()).forEach { a -> show(a.id, a.title, a.body) }
                since = now
                f.writeJson(Seen(now.toString()))
                delay(30_000)
            }
        }
    }
}
