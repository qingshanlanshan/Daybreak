package com.qshlab.daybreak.update

import com.qshlab.daybreak.Daybreak
import com.qshlab.daybreak.data.json
import com.qshlab.daybreak.data.readJson
import com.qshlab.daybreak.data.writeJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

const val SITE = "https://daybreak.qshlab.com"

/** A release, as published on the download site (android.json / mac.json). */
@Serializable
data class Release(val version: String, val versionCode: Long = 0, val url: String, val size: Long = 0,
                   val sha256: String = "", val notes: String = "")

/** How this platform finds and installs its updates. */
interface Updater {
    /** The release feed for this platform. */
    val feed: String
    fun isNewer(r: Release): Boolean
    /** Downloads and installs (Android: opens the installer; Mac: swaps the app and restarts). */
    suspend fun install(r: Release, progress: (Float) -> Unit)
}

@Serializable
private data class UpdatePrefs(val auto: Boolean = true, val last: Long = 0)

/** Checks the site for a newer release. Only the release feed is read; nothing about you is sent. */
object Updates {
    private const val DAY = 24 * 60 * 60 * 1000L
    private val _available = MutableStateFlow<Release?>(null)
    val available: StateFlow<Release?> = _available

    private val prefsFile get() = File(Daybreak.platform.home, "local/updates.json")
    private fun prefs() = prefsFile.readJson<UpdatePrefs>() ?: UpdatePrefs()
    fun autoCheck() = prefs().auto
    fun setAutoCheck(on: Boolean) = prefsFile.writeJson(prefs().copy(auto = on))

    /** Always when [force]; otherwise at most once a day and only if automatic checks are on. */
    suspend fun check(force: Boolean = false): Release? = withContext(Dispatchers.IO) {
        val p = prefs()
        if (!force && (!p.auto || System.currentTimeMillis() - p.last < DAY)) return@withContext _available.value
        val u = Daybreak.platform.updater
        val latest = runCatching {
            val conn = URL(u.feed).openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000; conn.readTimeout = 10_000
            conn.setRequestProperty("Cache-Control", "no-cache")
            try { json.decodeFromString<Release>(conn.inputStream.bufferedReader().use { it.readText() }) } finally { conn.disconnect() }
        }.getOrNull() ?: return@withContext _available.value
        prefsFile.writeJson(p.copy(last = System.currentTimeMillis()))
        latest.takeIf(u::isNewer).also { _available.value = it }
    }
}

/** Downloads [r] into [dir], checking its SHA-256. */
suspend fun download(r: Release, out: File, progress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
    out.parentFile?.mkdirs()
    val conn = URL(r.url).openConnection() as HttpURLConnection
    conn.connectTimeout = 15_000; conn.readTimeout = 60_000
    val digest = MessageDigest.getInstance("SHA-256")
    try {
        check(conn.responseCode in 200..299) { "HTTP ${conn.responseCode}" }
        val total = conn.contentLengthLong.takeIf { it > 0 } ?: r.size
        var done = 0L
        conn.inputStream.use { input ->
            out.outputStream().use { output ->
                val buf = ByteArray(128 * 1024)
                while (true) {
                    ensureActive()
                    val n = input.read(buf)
                    if (n < 0) break
                    output.write(buf, 0, n); digest.update(buf, 0, n); done += n
                    if (total > 0) progress(done.toFloat() / total)
                }
            }
        }
    } finally { conn.disconnect() }
    val sha = digest.digest().joinToString("") { "%02x".format(it) }
    if (r.sha256.isNotBlank() && !sha.equals(r.sha256, ignoreCase = true)) { out.delete(); throw IllegalStateException("The download was damaged. Try again later.") }
    out
}

/** "0.10.2" > "0.9.9". */
fun versionNewer(a: String, b: String): Boolean {
    val x = a.split('.').map { it.toIntOrNull() ?: 0 }; val y = b.split('.').map { it.toIntOrNull() ?: 0 }
    for (i in 0 until maxOf(x.size, y.size)) { val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }; if (d != 0) return d > 0 }
    return false
}
