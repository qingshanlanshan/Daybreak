package com.qshlab.daybreak.sources

import com.qshlab.daybreak.Daybreak
import com.qshlab.daybreak.data.readJson
import com.qshlab.daybreak.data.writeJson
import kotlinx.serialization.Serializable
import java.io.File
import java.time.Instant

/**
 * Mail a device reads without signing in anywhere: Apple Mail on the Mac (whole emails), the Outlook and Gmail
 * apps' new-mail notifications on Android (sender, subject and a preview). Read-only either way.
 */
interface DeviceMail {
    /** "applemail" | "notifications" */
    val kind: String
    /** Whole emails (true), or previews only. */
    val full: Boolean
    /** What it reads, for Settings: "Apple Mail", "Outlook and Gmail notifications". */
    val label: String
    /** Whether Daybreak has the permission it needs (Android: notification access). */
    fun allowed(): Boolean = true
    /** Opens the system setting that grants it. */
    fun requestAccess() {}
    /** When it last read successfully (ms; 0 = not yet in this run of the app). */
    val readAt: Long get() = 0
    /** Opens this app's system settings (Android: where "Allow restricted settings" is). */
    fun openAppSettings() {}
    /**
     * Reads mail received since [since] and saves it for the feed ([SharedMail]). [wake]: Mac — open Apple Mail
     * in the background if it isn't running, so it fetches new mail first. Android has nothing to read on demand:
     * notifications are saved as they arrive.
     */
    suspend fun refresh(since: Instant, wake: Boolean) {}
}

/** One device's reading, as saved in mail/<kind>-<device>.json. */
@Serializable
data class MailBox(val kind: String, val device: String, val deviceName: String, val at: Long, val full: Boolean,
                   val items: List<Mail> = emptyList(), val error: String? = null)

/**
 * What each device read, kept under mail/ — which syncs (encrypted), so a feed written on one device also has the
 * mail the others read: the phone's feed gets Apple Mail from the Mac, as of the Mac's last reading.
 */
object SharedMail {
    private const val KEEP_DAYS = 7
    private const val KEEP_MAX = 300
    private val dir get() = File(Daybreak.platform.home, "mail")
    private val prefsFile get() = File(Daybreak.platform.home, "local/device-mail.json")

    @Serializable private data class Prefs(val on: Boolean = true)
    /** Whether this device reads its own mail (on unless turned off). */
    fun enabled() = prefsFile.readJson<Prefs>()?.on ?: true
    fun setEnabled(on: Boolean) { prefsFile.writeJson(Prefs(on)); com.qshlab.daybreak.data.Changes.bump() }

    private fun file(kind: String, device: String = Daybreak.sync.deviceId) = File(dir, "$kind-$device.json")

    fun boxes(): List<MailBox> = dir.listFiles { f -> f.name.endsWith(".json") }.orEmpty().mapNotNull { it.readJson<MailBox>() }.sortedByDescending { it.at }
    fun mine(kind: String): MailBox? = file(kind).readJson()

    /** Replaces this device's reading of [kind] (Apple Mail: what's in the inbox now). */
    fun save(kind: String, full: Boolean, items: List<Mail>, error: String? = null) {
        val old = mine(kind)
        val keep = if (error != null && old != null) old.items else trim(items)
        if (old != null && old.items == keep && old.error == error && System.currentTimeMillis() - old.at < 3600_000L) return
        file(kind).writeJson(MailBox(kind, Daybreak.sync.deviceId, Daybreak.platform.deviceName, System.currentTimeMillis(), full, keep, error))
        Daybreak.sync.kickSoon()
    }

    /** Adds one email (Android: a notification just arrived). The same sender + subject replaces the earlier one. */
    @Synchronized fun add(kind: String, m: Mail) {
        val old = mine(kind)?.items.orEmpty().filterNot { it.from == m.from && norm(it.subject) == norm(m.subject) }
        file(kind).writeJson(MailBox(kind, Daybreak.sync.deviceId, Daybreak.platform.deviceName, System.currentTimeMillis(), false, trim(old + m)))
        Daybreak.sync.kickSoon()
    }

    private fun trim(items: List<Mail>): List<Mail> {
        val cutoff = System.currentTimeMillis() - KEEP_DAYS * 86400_000L
        return items.filter { it.received > cutoff }.sortedByDescending { it.received }.take(KEEP_MAX)
    }

    /**
     * All mail for the feed since [since]: whole emails (accounts, Apple Mail) first, then previews from notifications
     * that none of those already has. The same email read twice (an account and Apple Mail) appears once.
     */
    fun merge(fromAccounts: List<Mail>, since: Instant): List<Mail> {
        val t0 = since.toEpochMilli()
        val boxes = boxes()
        val whole = mutableListOf<Mail>()
        for (m in (fromAccounts + boxes.filter { it.full }.flatMap { it.items }).sortedByDescending { it.received })
            if (m.received >= t0 && whole.none { same(it, m, 15 * 60_000L) }) whole += m
        val previews = boxes.filter { !it.full }.flatMap { it.items }.filter { it.received >= t0 }
            .filter { p -> whole.none { same(it, p, 12 * 3600_000L) } }.distinctBy { it.from to norm(it.subject) }
        return (whole + previews).sortedByDescending { it.received }
    }

    private fun same(a: Mail, b: Mail, window: Long) = norm(a.subject) == norm(b.subject) && kotlin.math.abs(a.received - b.received) <= window

    /** "Re: Fwd:  Hello " → "hello" */
    fun norm(s: String) = s.lowercase().replace(Regex("^\\s*((re|fw|fwd|aw|回复|转发)\\s*[:：]\\s*)+"), "").replace(Regex("\\s+"), " ").trim()
}
