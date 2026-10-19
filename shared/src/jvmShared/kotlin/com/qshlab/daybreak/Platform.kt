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
    }
}
