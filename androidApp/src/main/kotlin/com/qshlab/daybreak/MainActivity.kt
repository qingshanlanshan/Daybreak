package com.qshlab.daybreak

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.lifecycle.lifecycleScope
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.qshlab.daybreak.ui.DaybreakApp
import com.qshlab.daybreak.update.Updates
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var onScan: ((String?) -> Unit)? = null
    private lateinit var scanLauncher: ActivityResultLauncher<ScanOptions>
    private var onPick: ((Pair<String, ByteArray>?) -> Unit)? = null
    private var pending: Triple<ByteArray, (Boolean) -> Unit, String>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        scanLauncher = registerForActivityResult(ScanContract()) { r -> onScan?.invoke(r.contents); onScan = null }
        val p = Daybreak.platform as AndroidPlatform
        val pick = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
            val r = uri?.let { u -> runCatching {
                val name = contentResolver.query(u, null, null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(c.getColumnIndexOrThrow(android.provider.OpenableColumns.DISPLAY_NAME)) else null } ?: "file"
                name to contentResolver.openInputStream(u)!!.use { it.readBytes() }
            }.getOrNull() }
            onPick?.invoke(r); onPick = null
        }
        p.picker = { done -> onPick = done; pick.launch(arrayOf("*/*")) }
        val create = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("text/calendar")) { uri ->
            val (bytes, done, _) = pending ?: return@registerForActivityResult
            done(uri != null && runCatching { contentResolver.openOutputStream(uri)!!.use { it.write(bytes) } }.isSuccess); pending = null
        }
        p.saver = { name, mime, bytes, done -> pending = Triple(bytes, done, mime); create.launch(name) }
        var onPerm: ((Boolean) -> Unit)? = null
        val perm = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { ok -> onPerm?.invoke(ok); onPerm = null }
        p.askPermission = { name, done -> onPerm = done; perm.launch(name) }
        (Daybreak.platform as AndroidPlatform).scanner = { done ->
            onScan = done
            scanLauncher.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setBeepEnabled(false)
                .setOrientationLocked(false).setPrompt("Point at the code: on your Mac, Settings → Sync → Code for another device"))
        }
        lifecycleScope.launch { Updates.check() }
        val notifPerm = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) {}
        if (android.os.Build.VERSION.SDK_INT >= 33) notifPerm.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        // Debug builds: join sync with a code passed by adb (typing long codes on an emulator is unreliable).
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0)
            intent.getStringExtra("syncCode")?.let { code -> Thread { android.util.Log.i("DaybreakSync", runCatching { Daybreak.sync.join(code); "joined" }.getOrElse { it.message ?: "failed" }) }.start() }
        setContent { DaybreakApp() }
        fromWidget(intent)
    }

    override fun onNewIntent(intent: android.content.Intent) { super.onNewIntent(intent); fromWidget(intent) }

    /** The home-screen widget: a tapped day starts a new task due then; the month name opens the calendar. */
    private fun fromWidget(i: android.content.Intent?) {
        i?.getStringExtra(EXTRA_NEW_TASK)?.let { d -> runCatching { java.time.LocalDate.parse(d) }.getOrNull()?.let { com.qshlab.daybreak.ui.Launch.newTaskOn.value = it } }
        if (i?.getStringExtra(EXTRA_OPEN) == "calendar") com.qshlab.daybreak.ui.Launch.openCalendar.value = true
        i?.removeExtra(EXTRA_NEW_TASK); i?.removeExtra(EXTRA_OPEN)
    }

    override fun onStart() { super.onStart(); (Daybreak.platform as AndroidPlatform).shown++; Daybreak.sync.kick() }
    override fun onStop() { (Daybreak.platform as AndroidPlatform).shown--; super.onStop() }

    override fun onDestroy() {
        (Daybreak.platform as AndroidPlatform).scanner = null
        (Daybreak.platform as AndroidPlatform).askPermission = null
        super.onDestroy()
    }
}
