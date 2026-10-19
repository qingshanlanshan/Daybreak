package com.qshlab.daybreak.update

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/** Android: download the APK (checked) and open the system installer, which asks once to allow it. */
class AndroidUpdater(private val ctx: Context) : Updater {
    override val feed = "$SITE/android.json"
    override fun isNewer(r: Release) = r.versionCode > ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode

    override suspend fun install(r: Release, progress: (Float) -> Unit) {
        val apk = download(r, File(File(ctx.cacheDir, "updates").apply { deleteRecursively() }, "Daybreak.apk"), progress)
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", apk)
        ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
