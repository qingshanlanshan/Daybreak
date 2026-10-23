package com.qshlab.daybreak

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.qshlab.daybreak.update.AndroidUpdater
import java.io.File
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

class DaybreakApplication : Application() {
    @OptIn(kotlinx.coroutines.FlowPreview::class)
    override fun onCreate() {
        super.onCreate()
        Daybreak.start(AndroidPlatform(this))
        Alarms.channels(this)
        Alarms.reschedule(this)
        FeedAlarms.schedule(this)
        SyncJob.schedule(this)
        // Re-plan reminders a moment after data changes (edits here or from sync).
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
            com.qshlab.daybreak.data.Changes.version.drop(1).debounce(2000).collect {
                Alarms.reschedule(this@DaybreakApplication); FeedAlarms.schedule(this@DaybreakApplication)
                MonthWidget.refresh(this@DaybreakApplication)   // the home-screen month shows events and due tasks
                com.qshlab.daybreak.feed.FeedEngine.announce()    // a new feed (e.g. from the Mac): a notification
            }
        }
    }
}

class AndroidPlatform(private val app: Application) : Platform {
    override val home = File(app.filesDir, "Daybreak")
    override val kind = "android"
    override val deviceName: String = if (Build.MODEL.startsWith(Build.MANUFACTURER, ignoreCase = true)) Build.MODEL
        else "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}"
    override val appVersion: String = app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: "?"
    override val updater = AndroidUpdater(app)
    override val deviceMail = NotificationMail(app)
    override val backgroundLimited get() = !app.getSystemService(android.os.PowerManager::class.java).isIgnoringBatteryOptimizations(app.packageName)
    @android.annotation.SuppressLint("BatteryLife")   // a calendar and scheduled feed: updates must run on time
    override fun allowBackground() {
        runCatching { app.startActivity(Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${app.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { app.startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
    /** Activities showing now (MainActivity counts itself in onStart/onStop). */
    @Volatile var shown = 0
    override val onScreen get() = shown > 0
    override val addWidget: (() -> Unit) = {
        val awm = android.appwidget.AppWidgetManager.getInstance(app)
        if (awm.isRequestPinAppWidgetSupported) awm.requestPinAppWidget(android.content.ComponentName(app, MonthWidgetReceiver::class.java), null, null)
    }

    /** Set by the activity while it's open (the camera scanner and file pickers need it). */
    var scanner: (((String?) -> Unit) -> Unit)? = null
    override val scanQr get() = scanner
    var picker: (((Pair<String, ByteArray>?) -> Unit) -> Unit)? = null
    var saver: ((String, String, ByteArray, (Boolean) -> Unit) -> Unit)? = null
    override fun pickFile(onResult: (Pair<String, ByteArray>?) -> Unit) { picker?.invoke(onResult) ?: onResult(null) }
    override fun saveFile(name: String, mime: String, bytes: ByteArray, onDone: (Boolean) -> Unit) { saver?.invoke(name, mime, bytes, onDone) ?: onDone(false) }
    override val use24Hour get() = android.text.format.DateFormat.is24HourFormat(app)
    var askPermission: ((String, (Boolean) -> Unit) -> Unit)? = null

    override fun notify(id: String, title: String, body: String) = Alarms.show(app, "feed", id.hashCode(), title, body)

    @android.annotation.SuppressLint("MissingPermission")
    override suspend fun locate(): Result<Pair<Double, Double>> = runCatching {
        val perm = android.Manifest.permission.ACCESS_COARSE_LOCATION
        if (androidx.core.content.ContextCompat.checkSelfPermission(app, perm) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            // Only ask while Daybreak is on screen (Android blocks it from the background); meanwhile the city comes
            // from the internet connection.
            val ask = askPermission?.takeIf { onScreen } ?: error("Open Daybreak to allow location.")
            val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                kotlinx.coroutines.suspendCancellableCoroutine<Boolean> { c -> ask(perm) { c.resumeWith(Result.success(it)) } }
            }
            if (!ok) error("Location is off for Daybreak. Allow it in Android settings, or add your city in Settings → Places.")
        }
        val lm = app.getSystemService(android.location.LocationManager::class.java)
        // "fused" (Android 12+) is what most phones answer fastest with; then Wi-Fi/cell, GPS, and other apps' fixes.
        val all = lm.allProviders
        val providers = ((if (Build.VERSION.SDK_INT >= 31) listOf(android.location.LocationManager.FUSED_PROVIDER) else emptyList()) +
            listOf(android.location.LocationManager.NETWORK_PROVIDER, android.location.LocationManager.GPS_PROVIDER, android.location.LocationManager.PASSIVE_PROVIDER))
            .filter { it in all && runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
        if (providers.isEmpty()) error("Location services are off.")
        var loc: android.location.Location? = null
        if (Build.VERSION.SDK_INT >= 30) for (p in providers) {
            loc = kotlinx.coroutines.withTimeoutOrNull(10_000) {
                kotlinx.coroutines.suspendCancellableCoroutine<android.location.Location?> { c -> lm.getCurrentLocation(p, null, app.mainExecutor) { c.resumeWith(Result.success(it)) } }
            }
            if (loc != null) break
        }
        val l = loc ?: providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
            ?: error("Couldn't find where you are right now. Try again outdoors, or add your city in Settings → Places.")
        l.latitude to l.longitude
    }

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
