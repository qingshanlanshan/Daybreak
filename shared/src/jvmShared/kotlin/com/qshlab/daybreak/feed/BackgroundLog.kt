package com.qshlab.daybreak.feed

import com.qshlab.daybreak.Daybreak
import com.qshlab.daybreak.data.readJson
import com.qshlab.daybreak.data.writeJson
import kotlinx.serialization.Serializable
import java.io.File

/** What this device did in the background lately (shown in Settings → Updates & notifications). */
object BackgroundLog {
    @Serializable data class Entry(val at: Long, val what: String)
    private val file get() = File(Daybreak.platform.home, "local/background-log.json")
    fun list(): List<Entry> = file.readJson<List<Entry>>() ?: emptyList()
    @Synchronized fun add(what: String) = runCatching {
        file.writeJson((list() + Entry(System.currentTimeMillis(), what)).takeLast(30))
    }
}
