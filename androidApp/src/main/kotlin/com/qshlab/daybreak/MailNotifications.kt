package com.qshlab.daybreak

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import com.qshlab.daybreak.sources.DeviceMail
import com.qshlab.daybreak.sources.Mail
import com.qshlab.daybreak.sources.SharedMail
import java.util.concurrent.Executors

/** The mail apps whose notifications Daybreak reads. */
private val MAIL_APPS = mapOf("com.microsoft.office.outlook" to "Outlook", "com.google.android.gm" to "Gmail")
private const val KIND = "notifications"

/**
 * Android: the Outlook and Gmail apps' new-mail notifications (sender, subject, preview), once you allow
 * notification access. Nothing signs in anywhere; other apps' notifications are ignored.
 */
class NotificationMail(private val ctx: Context) : DeviceMail {
    override val kind = KIND
    override val full = false
    override val label = "Outlook and Gmail notifications"
    override fun allowed() = ctx.packageName in NotificationManagerCompat.getEnabledListenerPackages(ctx)
    override fun requestAccess() {
        val i = if (Build.VERSION.SDK_INT >= 30) Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
            .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, ComponentName(ctx, MailListener::class.java).flattenToString())
        else Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
    /** App info, where Android 13+ hides "Allow restricted settings" for apps not from an app store. */
    override fun openAppSettings() {
        ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

class MailListener : NotificationListenerService() {
    private val io = Executors.newSingleThreadExecutor()

    override fun onListenerConnected() {
        // Mail already waiting in the notification shade.
        runCatching { activeNotifications }.getOrNull()?.forEach(::onNotificationPosted)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        // Debug builds also take test notifications posted with `adb shell cmd notification post`.
        val debug = applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
        val app = MAIL_APPS[sbn.packageName] ?: (if (debug && sbn.packageName == "com.android.shell") "Test" else return)
        val n = sbn.notification
        if (n.flags and (Notification.FLAG_GROUP_SUMMARY or Notification.FLAG_ONGOING_EVENT) != 0) return
        if (n.category != null && n.category != Notification.CATEGORY_EMAIL) return   // reminders, "sending…", sync status
        val e = n.extras
        val from = e.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = e.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        val big = e.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim().orEmpty()
        val account = (e.getCharSequence(Notification.EXTRA_SUB_TEXT) ?: e.getCharSequence(Notification.EXTRA_SUMMARY_TEXT))?.toString()?.trim().orEmpty()
        if (from.isEmpty() || (text.isEmpty() && big.isEmpty())) return
        val subject = text.lineSequence().firstOrNull()?.trim().orEmpty().ifEmpty { big.lineSequence().first().trim() }
        val preview = big.removePrefix(subject).trim().ifEmpty { text.removePrefix(subject).trim() }
        val m = Mail(account.ifEmpty { app }, from, subject.ifEmpty { "(no subject)" }, sbn.postTime, preview.replace(Regex("\\s+"), " ").take(600))
        io.execute { runCatching { if (SharedMail.enabled()) SharedMail.add(KIND, m) } }
    }
}
