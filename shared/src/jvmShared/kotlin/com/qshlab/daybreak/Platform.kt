package com.qshlab.daybreak

import com.qshlab.daybreak.sync.Sync
import com.qshlab.daybreak.update.Updater
import java.io.File

/** What each app (Android, Mac) provides to the shared code. */
interface Platform {
    /** Where Daybreak keeps its data. Synced files live here; .sync/ and local/ stay on this device. */
    val home: File
    /** "android" or "mac". */
    val kind: String
    val deviceName: String
    val appVersion: String
    val updater: Updater
    fun openUrl(url: String)
    fun copyText(text: String)
    /** Hands text to another app (Android share sheet); on the Mac it copies. */
    fun shareText(text: String, title: String) = copyText(text)
    /** Scans a QR code with the camera, or null when this device can't (the Mac). */
    val scanQr: ((onResult: (String?) -> Unit) -> Unit)? get() = null
    /** Lets the user pick a file; calls back with its name and contents, or null if cancelled. */
    fun pickFile(onResult: (Pair<String, ByteArray>?) -> Unit)
    /** Lets the user save [bytes] as a file (Mac: save dialog; Android: choose where). */
    fun saveFile(name: String, mime: String, bytes: ByteArray, onDone: (Boolean) -> Unit = {})
    /** Shows a system notification. */
    fun notify(id: String, title: String, body: String)
    /** Where this device is: (latitude, longitude), asking for permission if needed. */
    suspend fun locate(): Result<Pair<Double, Double>>
    /** Mac: whether Daybreak opens (in the background) when you log in; null where it doesn't apply. */
    fun openAtLogin(): Boolean? = null
    fun setOpenAtLogin(on: Boolean) {}
    /** Mail this device reads without an account: Apple Mail (Mac), mail-app notifications (Android). */
    val deviceMail: com.qshlab.daybreak.sources.DeviceMail? get() = null
    /** Android: asks the launcher to put the month widget on the home screen; null where there's none. */
    val addWidget: (() -> Unit)? get() = null
    /** Android: battery optimisation can stop Daybreak's background updates; true while it applies. */
    val backgroundLimited: Boolean get() = false
    /** Asks the system to let Daybreak run in the background (Android's battery optimisation dialog). */
    fun allowBackground() {}
    /** Whether Daybreak is on screen now (then a new feed needs no notification). */
    val onScreen: Boolean get() = false
    /** Clock style for times. */
    val use24Hour: Boolean get() = !(java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT) as java.text.SimpleDateFormat).toPattern().contains('a')
}

/** The running app: set up once by the Android Application / the Mac main(). */
object Daybreak {
    lateinit var platform: Platform
        private set
    lateinit var sync: Sync
        private set

    fun start(p: Platform) {
        platform = p
        p.home.mkdirs()
        sync = Sync(p).also { it.start() }
        com.qshlab.daybreak.cal.Cal.ensureDefaults()
    }
}
