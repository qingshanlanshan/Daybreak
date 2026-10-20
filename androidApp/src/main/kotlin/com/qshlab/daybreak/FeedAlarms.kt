package com.qshlab.daybreak

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import com.qshlab.daybreak.feed.FeedEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.ZoneId

/**
 * The scheduled feed on Android: alarms 15 minutes before each update time (write it) and at the time
 * (notify). An alarm starts a short job with network access, which does FeedEngine.tick().
 */
object FeedAlarms {
    fun schedule(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        for (code in listOf(2000, 2001)) PendingIntent.getBroadcast(ctx, code, Intent(ctx, FeedAlarmReceiver::class.java), PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)?.let { am.cancel(it) }
        val next = runCatching { FeedEngine.nextSlot(java.time.LocalDateTime.now().plusMinutes(1).minusMinutes(15)) }.getOrNull() ?: return
        fun at(t: java.time.LocalDateTime, code: Int) {
            val ms = t.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli().coerceAtLeast(System.currentTimeMillis() + 5_000)
            val pi = PendingIntent.getBroadcast(ctx, code, Intent(ctx, FeedAlarmReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, pi)
        }
        at(next.minusMinutes(15), 2000)
        at(next, 2001)
    }

    fun runJob(ctx: Context) {
        val js = ctx.getSystemService(JobScheduler::class.java)
        js.schedule(JobInfo.Builder(42, ComponentName(ctx, FeedJob::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setOverrideDeadline(60_000).build())
    }
}

class FeedAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) = FeedAlarms.runJob(ctx)
}

class FeedJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { FeedEngine.tick() }
            FeedAlarms.schedule(applicationContext)
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters) = true
}
