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

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        scanLauncher = registerForActivityResult(ScanContract()) { r -> onScan?.invoke(r.contents); onScan = null }
        (Daybreak.platform as AndroidPlatform).scanner = { done ->
            onScan = done
            scanLauncher.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setBeepEnabled(false)
                .setOrientationLocked(false).setPrompt("Point at the code: on your Mac, Settings → Sync → Code for another device"))
        }
        lifecycleScope.launch { Updates.check() }
        // Debug builds: join sync with a code passed by adb (typing long codes on an emulator is unreliable).
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0)
            intent.getStringExtra("syncCode")?.let { code -> Thread { android.util.Log.i("DaybreakSync", runCatching { Daybreak.sync.join(code); "joined" }.getOrElse { it.message ?: "failed" }) }.start() }
        setContent { DaybreakApp() }
    }

    override fun onStart() { super.onStart(); Daybreak.sync.kick() }

    override fun onDestroy() {
        (Daybreak.platform as AndroidPlatform).scanner = null
        super.onDestroy()
    }
}
