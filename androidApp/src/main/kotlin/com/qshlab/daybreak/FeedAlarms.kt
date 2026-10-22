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
 * The scheduled feed on Android: alarms around each update time (see schedule()). An alarm starts a short job
 * with network access, which does FeedEngine.tick().
 */
object FeedAlarms {
    /** One alarm, at the next moment tick() has something to do. */
    fun schedule(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        for (code in listOf(2000, 2001)) PendingIntent.getBroadcast(ctx, code, Intent(ctx, FeedAlarmReceiver::class.java), PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)?.let { am.cancel(it) }
        val now = java.time.LocalDateTime.now()
        val s = runCatching { FeedEngine.config().schedule }.getOrNull() ?: return
        // For each update time: 15 min before (write it), at it (notify), 10 min after (the Mac's turn to write is
        // over: write it here if it hasn't), 30 min after (notify late if the feed came late).
        val next = (0..2).flatMap { FeedEngine.slots(now.toLocalDate().plusDays(it.toLong()), s) }
            .flatMap { listOf(it.minusMinutes(15), it, it.plusMinutes(10), it.plusMinutes(30)) }
            .filter { it.isAfter(now.plusSeconds(20)) }.minOrNull() ?: return
        val ms = next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val pi = PendingIntent.getBroadcast(ctx, 2000, Intent(ctx, FeedAlarmReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, pi)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, pi)
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

/**
 * About every 30 minutes (when Android allows, with a network): sync, so a feed your Mac wrote arrives with a
 * notification, and the widget stays current, even when Daybreak isn't open.
 */
object SyncJob {
    private const val ID = 43
    fun schedule(ctx: Context) {
        val js = ctx.getSystemService(JobScheduler::class.java)
        if (js.getPendingJob(ID) != null) return
        js.schedule(JobInfo.Builder(ID, ComponentName(ctx, SyncJobService::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPeriodic(30 * 60_000L).setPersisted(true).build())
    }
}

class SyncJobService : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { if (com.qshlab.daybreak.Daybreak.sync.config.value != null) com.qshlab.daybreak.Daybreak.sync.runOnce() }
            runCatching { FeedEngine.announce() }
            MonthWidget.refresh(applicationContext)
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters) = true
}
