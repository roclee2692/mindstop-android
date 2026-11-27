package app.mindstop.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import app.mindstop.core.MorningAlarm
import app.mindstop.core.MorningAlarmPlanner
import app.mindstop.ui.MindstopMorningActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Exact alarm scheduler for Module 2: Morning Activation (v0.3 SPEC §10–§12).
 *
 * Adapted from Purgator/Upupup (`AlarmScheduler.kt`, GPL-3.0) and FossifyOrg/Clock (GPL-3.0)
 * compatibility patterns: prefers [AlarmManager.setAlarmClock] (exempt from Doze & battery
 * optimizations, displays system alarm icon) and falls back cleanly when exact alarm permission
 * is revoked on Android 12+.
 */
object MindstopAlarmScheduler {

    private const val TAG = "MindstopAlarmScheduler"

    fun canScheduleExact(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return am.canScheduleExactAlarms()
    }

    fun schedule(context: Context, alarm: MorningAlarm, nowWall: Long = System.currentTimeMillis()): Long? {
        if (!alarm.enabled || alarm.id <= 0L) {
            cancel(context, alarm.id)
            return null
        }
        val triggerAt = MorningAlarmPlanner.nextTriggerWall(alarm, nowWall)
        scheduleExactAt(context, alarm.id, triggerAt, isSnooze = false)
        return triggerAt
    }

    /** Arms a one-shot test ring in [seconds] for real-device alarm & lock-screen verification. */
    fun scheduleTestRingInSeconds(context: Context, alarmId: Long, seconds: Int = 10): Long {
        val triggerAt = System.currentTimeMillis() + seconds.coerceIn(3, 120) * 1_000L
        scheduleExactAt(context, alarmId, triggerAt, isSnooze = true)
        return triggerAt
    }

    fun scheduleSnooze(context: Context, alarmId: Long, minutes: Int): Long {
        val triggerAt = System.currentTimeMillis() + minutes.coerceIn(1, 30) * 60_000L
        scheduleExactAt(context, alarmId, triggerAt, isSnooze = true)
        return triggerAt
    }

    fun cancel(context: Context, alarmId: Long) {
        if (alarmId <= 0L) return
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(firePendingIntent(context, alarmId, isSnooze = false, scheduledWall = 0L))
        am.cancel(firePendingIntent(context, alarmId, isSnooze = true, scheduledWall = 0L))
    }

    /**
     * Schedules Stage 1 guards after alarm dismiss:
     * - At [firstCheckMin] (default 5m): gentle notification that Stage 2 ("Wash up & Prep") is unlocked.
     * - At [finalCheckMin] (default 15m): if user STILL hasn't entered Stage 2, fires
     *   [MindstopAlarmReceiver.ACTION_STAGE1_TIMEOUT_RING] (rings alarm again when [backupReminderEnabled]
     *   is true, or posts a high-priority reminder otherwise).
     */
    fun scheduleActivationCheckins(
        context: Context,
        sessionId: Long,
        alarmId: Long,
        firstCheckMin: Int = 5,
        finalCheckMin: Int = 15,
        backupReminderEnabled: Boolean = true
    ) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val now = System.currentTimeMillis()
        val t5 = now + firstCheckMin.coerceIn(1, 30) * 60_000L
        val t15 = now + finalCheckMin.coerceIn(firstCheckMin + 1, 60) * 60_000L

        setReminderTrigger(am, t5, checkinPendingIntent(context, sessionId, alarmId, MindstopAlarmReceiver.ACTION_CHECK_5M))
        val finalAction = if (backupReminderEnabled) {
            MindstopAlarmReceiver.ACTION_STAGE1_TIMEOUT_RING
        } else {
            MindstopAlarmReceiver.ACTION_BACKUP_REMIND
        }
        if (backupReminderEnabled && canScheduleExact(context)) {
            try {
                val showIntent = PendingIntent.getActivity(
                    context,
                    (sessionId.toInt() and 0x3FFF) + 45_000,
                    Intent(context, MindstopMorningActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                am.setAlarmClock(
                    AlarmManager.AlarmClockInfo(t15, showIntent),
                    checkinPendingIntent(context, sessionId, alarmId, finalAction)
                )
                return
            } catch (_: SecurityException) {
            }
        }
        setReminderTrigger(am, t15, checkinPendingIntent(context, sessionId, alarmId, finalAction))
    }

    fun cancelActivationCheckins(context: Context, sessionId: Long) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(checkinPendingIntent(context, sessionId, 0L, MindstopAlarmReceiver.ACTION_CHECK_5M))
        am.cancel(checkinPendingIntent(context, sessionId, 0L, MindstopAlarmReceiver.ACTION_CHECK_15M))
        am.cancel(checkinPendingIntent(context, sessionId, 0L, MindstopAlarmReceiver.ACTION_BACKUP_REMIND))
        am.cancel(checkinPendingIntent(context, sessionId, 0L, MindstopAlarmReceiver.ACTION_STAGE1_TIMEOUT_RING))
    }

    fun rescheduleAllAsync(
        context: Context,
        pendingResult: android.content.BroadcastReceiver.PendingResult? = null
    ) {
        val appCtx = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val repo = MorningRepository(appCtx)
                val now = System.currentTimeMillis()
                for (alarm in repo.loadAlarms()) {
                    schedule(appCtx, alarm, now)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to reschedule Mindstop alarms", e)
            } finally {
                try {
                    pendingResult?.finish()
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun scheduleExactAt(context: Context, alarmId: Long, triggerAt: Long, isSnooze: Boolean) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val firePi = firePendingIntent(context, alarmId, isSnooze, triggerAt)
        try {
            if (canScheduleExact(context)) {
                val showIntent = PendingIntent.getActivity(
                    context,
                    (alarmId.toInt() and 0x7FFF) + 40_000,
                    Intent(context, MindstopMorningActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                am.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAt, showIntent), firePi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, firePi)
            }
        } catch (se: SecurityException) {
            Log.w(TAG, "Exact alarm permission denied; falling back to inexact wakeup", se)
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, firePi)
        }
    }

    private fun setReminderTrigger(am: AlarmManager, triggerAt: Long, pi: PendingIntent) {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            }
        } catch (_: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        }
    }

    private fun firePendingIntent(context: Context, alarmId: Long, isSnooze: Boolean, scheduledWall: Long): PendingIntent {
        val reqCode = ((alarmId.toInt() and 0x7FFF) * 2) + if (isSnooze) 1 else 0
        val intent = Intent(context, MindstopAlarmReceiver::class.java).apply {
            action = MindstopAlarmReceiver.ACTION_FIRE_ALARM
            putExtra(MindstopAlarmReceiver.EXTRA_ALARM_ID, alarmId)
            putExtra(MindstopAlarmReceiver.EXTRA_IS_SNOOZE, isSnooze)
            putExtra(MindstopAlarmReceiver.EXTRA_SCHEDULED_WALL, scheduledWall)
        }
        return PendingIntent.getBroadcast(
            context,
            reqCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun checkinPendingIntent(context: Context, sessionId: Long, alarmId: Long, actionName: String): PendingIntent {
        val salt = when (actionName) {
            MindstopAlarmReceiver.ACTION_CHECK_5M -> 100_001
            MindstopAlarmReceiver.ACTION_CHECK_15M -> 100_002
            MindstopAlarmReceiver.ACTION_STAGE1_TIMEOUT_RING -> 100_004
            else -> 100_003
        }
        val reqCode = ((sessionId.toInt() and 0x3FFF) * 8) + salt
        val intent = Intent(context, MindstopAlarmReceiver::class.java).apply {
            action = actionName
            putExtra(MindstopAlarmReceiver.EXTRA_SESSION_ID, sessionId)
            putExtra(MindstopAlarmReceiver.EXTRA_ALARM_ID, alarmId)
        }
        return PendingIntent.getBroadcast(
            context,
            reqCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
