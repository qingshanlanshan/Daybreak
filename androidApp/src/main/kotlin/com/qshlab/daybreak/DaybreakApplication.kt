package com.qshlab.daybreak

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.qshlab.daybreak.update.AndroidUpdater
import java.io.File

class DaybreakApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Daybreak.start(AndroidPlatform(this))
    }
}

class AndroidPlatform(private val app: Application) : Platform {
    override val home = File(app.filesDir, "Daybreak")
    override val kind = "android"
    override val deviceName: String = if (Build.MODEL.startsWith(Build.MANUFACTURER, ignoreCase = true)) Build.MODEL
        else "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}"
    override val appVersion: String = app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: "?"
    override val updater = AndroidUpdater(app)

    /** Set by the activity while it's open (the camera scanner needs it). */
    var scanner: (((String?) -> Unit) -> Unit)? = null
    override val scanQr get() = scanner

    override fun openUrl(url: String) {
        app.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    override fun copyText(text: String) {
        (app.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Daybreak", text))
    }

    override fun shareText(text: String, title: String) {
        app.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), title)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
