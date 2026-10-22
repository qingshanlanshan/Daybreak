package com.qshlab.daybreak

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.qshlab.daybreak.notify.Reminders
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Reminders on Android: the next 36 hours of event alerts and task reminders become system alarms, so they
 * fire with the app closed. Rescheduled when the app starts, when data changes, after a reboot, and after
 * each alarm.
 */
object Alarms {
    const val CHANNEL = "reminders"
    private const val SLOTS = 40

    fun channels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Reminders", NotificationManager.IMPORTANCE_HIGH).apply { description = "Event alerts and task reminders" })
        nm.createNotificationChannel(NotificationChannel("feed", "Your feed", NotificationManager.IMPORTANCE_DEFAULT).apply { description = "When your feed is updated" })
    }

    fun reschedule(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val now = LocalDateTime.now()
        val alerts = runCatching { Reminders.between(now, now.plusHours(36)) }.getOrDefault(emptyList()).take(SLOTS)
        for (i in 0 until SLOTS) {
            val pi = PendingIntent.getBroadcast(ctx, 1000 + i, Intent(ctx, AlarmReceiver::class.java),
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
            if (pi != null) { am.cancel(pi); pi.cancel() }
        }
        alerts.forEachIndexed { i, a ->
            val intent = Intent(ctx, AlarmReceiver::class.java).putExtra("id", a.id).putExtra("title", a.title).putExtra("body", a.body)
            val pi = PendingIntent.getBroadcast(ctx, 1000 + i, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val at = a.at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    fun show(ctx: Context, channel: String, id: Int, title: String, body: String) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(ctx, channel).setSmallIcon(R.drawable.ic_notification).setContentTitle(title).setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body)).setContentIntent(open).setAutoCancel(true).build()
        ctx.getSystemService(NotificationManager::class.java).notify(id, n)
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getStringExtra("id") ?: return
        Alarms.show(ctx, Alarms.CHANNEL, id.hashCode(), intent.getStringExtra("title") ?: "Daybreak", intent.getStringExtra("body") ?: "")
        Alarms.reschedule(ctx)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) { Alarms.reschedule(ctx); FeedAlarms.schedule(ctx) }
}
