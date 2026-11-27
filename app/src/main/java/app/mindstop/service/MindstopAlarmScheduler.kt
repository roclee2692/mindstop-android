package app.mindstop.service

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import app.mindstop.core.MorningAlarm
import app.mindstop.core.MorningAlarmPlanner
import app.mindstop.core.MorningRewardChoice
import app.mindstop.core.MorningRuntimeMeta
import app.mindstop.core.MorningSessionRecord
import app.mindstop.core.MorningState
import app.mindstop.core.MorningV2Settings
import app.mindstop.core.WakeCheckSubState
import app.mindstop.ui.MindstopMorningActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Exact alarm & stage-timer scheduler for Module 2: Morning Activation V2.
 *
 * Open-source compatibility patterns:
 * - WakeNudge (`PendingAlarms.kt` & `restoreWakeChecks`, Apache-2.0,
 *   https://github.com/riyadmondol2006/WakeNudge): identifies every scheduled trigger by a unique
 *   data URI (`mindstop://morning/<action>/<id>`) to eliminate request-code collisions, applies
 *   a 3-tier fallback (`setAlarmClock` -> `setExactAndAllowWhileIdle` -> `setAndAllowWhileIdle`),
 *   and restores active stage/wake-check triggers after reboot or package updates.
 * - Purgator/Upupup (`AlarmScheduler.kt`, GPL-3.0) & FossifyOrg/Clock (GPL-3.0).
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
        scheduleAlarmTrigger(context, alarm.id, triggerAt, isSnooze = false)
        return triggerAt
    }

    /** Arms a one-shot test ring in [seconds] for real-device alarm & lock-screen verification. */
    fun scheduleTestRingInSeconds(context: Context, alarmId: Long, seconds: Int = 10): Long {
        val triggerAt = System.currentTimeMillis() + seconds.coerceIn(3, 120) * 1_000L
        scheduleAlarmTrigger(context, alarmId, triggerAt, isSnooze = true)
        return triggerAt
    }

    fun scheduleSnooze(context: Context, alarmId: Long, minutes: Int): Long {
        val triggerAt = System.currentTimeMillis() + minutes.coerceIn(1, 30) * 60_000L
        scheduleAlarmTrigger(context, alarmId, triggerAt, isSnooze = true)
        return triggerAt
    }

    fun cancel(context: Context, alarmId: Long) {
        if (alarmId <= 0L) return
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(firePendingIntent(context, alarmId, isSnooze = false, scheduledWall = 0L))
        am.cancel(firePendingIntent(context, alarmId, isSnooze = true, scheduledWall = 0L))
    }

    // Stage-scoped exact timers (all tagged with stageToken / wakeCheckToken for stale-event safety)

    fun scheduleStage1Timers(
        context: Context,
        sessionId: Long,
        alarmId: Long,
        stageToken: Long,
        stage1StartWall: Long,
        settings: MorningV2Settings,
        nowWall: Long = System.currentTimeMillis()
    ) {
        cancelAllStageTimers(context, sessionId)
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        // At +5m (stage1UnlockMs): unlocks "I am awake" button & triggers 2nd ring (unlocking extended sleep).
        val tUnlock = stage1StartWall + settings.stage1UnlockMs
        if (tUnlock > nowWall + 1_000L) {
            setAlarmClockWithFallback(
                context,
                am,
                tUnlock,
                stagePendingIntent(context, MindstopAlarmReceiver.ACTION_STAGE1_SECOND_RING, sessionId, alarmId, stageToken, 0L)
            )
        }

        // At +15m (stage1WindowMs) or next overtime interval: triggers overtime reminder ring.
        val tWindow = stage1StartWall + settings.stage1WindowMs
        val nextTimeoutAt = if (tWindow > nowWall + 1_000L) {
            tWindow
        } else {
            nowWall + settings.stage1RepeatMs
        }
        setAlarmClockWithFallback(
            context,
            am,
            nextTimeoutAt,
            stagePendingIntent(context, MindstopAlarmReceiver.ACTION_STAGE1_TIMEOUT_RING, sessionId, alarmId, stageToken, 0L)
        )
    }

    fun scheduleStage1NextOvertimeRing(
        context: Context,
        sessionId: Long,
        alarmId: Long,
        stageToken: Long,
        delayMs: Long,
        nowWall: Long = System.currentTimeMillis()
    ) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val triggerAt = nowWall + delayMs.coerceIn(60_000L, 30 * 60_000L)
        setAlarmClockWithFallback(
            context,
            am,
            triggerAt,
            stagePendingIntent(context, MindstopAlarmReceiver.ACTION_STAGE1_TIMEOUT_RING, sessionId, alarmId, stageToken, 0L)
        )
    }

    fun scheduleExtendedSleepResume(
        context: Context,
        sessionId: Long,
        alarmId: Long,
        stageToken: Long,
        resumeAtWall: Long
    ) {
        cancelAllStageTimers(context, sessionId)
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        setAlarmClockWithFallback(
            context,
            am,
            resumeAtWall,
            stagePendingIntent(context, MindstopAlarmReceiver.ACTION_EXTENDED_SLEEP_RESUME, sessionId, alarmId, stageToken, 0L)
        )
    }

    fun scheduleStage2Timers(
        context: Context,
        sessionId: Long,
        alarmId: Long,
        stageToken: Long,
        wakeCheckToken: Long,
        stage2StartWall: Long,
        wakeCheckState: WakeCheckSubState,
        wakeCheckPromptWall: Long,
        wakeCheckDeadlineWall: Long,
        settings: MorningV2Settings,
        nowWall: Long = System.currentTimeMillis()
    ) {
        cancelAllStageTimers(context, sessionId)
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        // Silent wake check sub-state timers
        when (wakeCheckState) {
            WakeCheckSubState.WAITING -> {
                val promptAt = wakeCheckPromptWall.takeIf { it > 0L } ?: (stage2StartWall + settings.wakeCheckDelayMs)
                setExactWithFallback(
                    am,
                    promptAt.coerceAtLeast(nowWall + 1_000L),
                    stagePendingIntent(context, MindstopAlarmReceiver.ACTION_WAKE_CHECK_PROMPT, sessionId, alarmId, stageToken, wakeCheckToken)
                )
            }
            WakeCheckSubState.PROMPTED -> {
                val deadlineAt = wakeCheckDeadlineWall.takeIf { it > 0L } ?: (nowWall + settings.wakeCheckWindowMs)
                scheduleWakeCheckDeadline(context, sessionId, alarmId, stageToken, wakeCheckToken, deadlineAt)
            }
            else -> Unit
        }

        // Moderate Stage 2 30-min overtime reminder (never auto-completes Stage 2)
        val tPrepEnd = stage2StartWall + settings.stage2DurationMs
        if (tPrepEnd > nowWall + 1_000L) {
            setExactWithFallback(
                am,
                tPrepEnd,
                stagePendingIntent(context, MindstopAlarmReceiver.ACTION_STAGE2_TIMEOUT, sessionId, alarmId, stageToken, wakeCheckToken)
            )
        }
    }

    fun scheduleWakeCheckDeadline(
        context: Context,
        sessionId: Long,
        alarmId: Long,
        stageToken: Long,
        wakeCheckToken: Long,
        deadlineWall: Long
    ) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        setAlarmClockWithFallback(
            context,
            am,
            deadlineWall,
            stagePendingIntent(context, MindstopAlarmReceiver.ACTION_WAKE_CHECK_DEADLINE, sessionId, alarmId, stageToken, wakeCheckToken)
        )
    }

    fun cancelWakeCheckTimers(context: Context, sessionId: Long) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(stagePendingIntent(context, MindstopAlarmReceiver.ACTION_WAKE_CHECK_PROMPT, sessionId, 0L, 0L, 0L))
        am.cancel(stagePendingIntent(context, MindstopAlarmReceiver.ACTION_WAKE_CHECK_DEADLINE, sessionId, 0L, 0L, 0L))
        cancelSilentWakeCheckNotification(context)
    }

    fun scheduleStage3End(
        context: Context,
        sessionId: Long,
        alarmId: Long,
        stageToken: Long,
        endWall: Long
    ) {
        cancelAllStageTimers(context, sessionId)
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        setExactWithFallback(
            am,
            endWall,
            stagePendingIntent(context, MindstopAlarmReceiver.ACTION_STAGE3_END, sessionId, alarmId, stageToken, 0L)
        )
    }

    /** Cancels EVERY pending timer & silent notification belonging to [sessionId]. */
    fun cancelAllStageTimers(context: Context, sessionId: Long) {
        if (sessionId <= 0L) return
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val actions = listOf(
            MindstopAlarmReceiver.ACTION_CHECK_5M,
            MindstopAlarmReceiver.ACTION_CHECK_15M,
            MindstopAlarmReceiver.ACTION_BACKUP_REMIND,
            MindstopAlarmReceiver.ACTION_STAGE1_SECOND_RING,
            MindstopAlarmReceiver.ACTION_STAGE1_TIMEOUT_RING,
            MindstopAlarmReceiver.ACTION_EXTENDED_SLEEP_RESUME,
            MindstopAlarmReceiver.ACTION_WAKE_CHECK_PROMPT,
            MindstopAlarmReceiver.ACTION_WAKE_CHECK_DEADLINE,
            MindstopAlarmReceiver.ACTION_STAGE2_TIMEOUT,
            MindstopAlarmReceiver.ACTION_STAGE3_END
        )
        for (action in actions) {
            am.cancel(stagePendingIntent(context, action, sessionId, 0L, 0L, 0L))
        }
        cancelSilentWakeCheckNotification(context)
    }

    fun cancelActivationCheckins(context: Context, sessionId: Long) = cancelAllStageTimers(context, sessionId)

    fun cancelSilentWakeCheckNotification(context: Context) {
        try {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.cancel(MindstopAlarmReceiver.WAKE_CHECK_NOTIFY_ID)
        } catch (_: Exception) {
        }
    }

    /**
     * Restores ALL scheduled morning alarms AND any active Morning Activation session's pending
     * stage/wake-check timers after device reboot, package replacement, or clock/timezone changes.
     */
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
                val active = repo.activeSession(now)
                if (active != null && active.status.isInActivation) {
                    val meta = repo.loadRuntimeMeta(active.id)
                    val settings = repo.loadSettings()
                    restoreActiveSessionTimers(appCtx, active, meta, settings, now)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to reschedule Mindstop alarms & active morning stage", e)
            } finally {
                try {
                    pendingResult?.finish()
                } catch (_: Exception) {
                }
            }
        }
    }

    fun restoreActiveSessionTimers(
        context: Context,
        session: MorningSessionRecord,
        meta: MorningRuntimeMeta,
        settings: MorningV2Settings,
        nowWall: Long = System.currentTimeMillis()
    ) {
        when (session.status) {
            MorningState.STAGE1_WAKE, MorningState.ACTIVATION -> {
                val start = meta.stage1StartWall.takeIf { it > 0L } ?: session.ringStartedWall
                scheduleStage1Timers(context, session.id, session.alarmId, meta.stageToken, start, settings, nowWall)
            }
            MorningState.EXTENDED_SLEEP -> {
                val resumeAt = meta.extendedSleepResumeWall.coerceAtLeast(nowWall + 2_000L)
                scheduleExtendedSleepResume(context, session.id, session.alarmId, meta.stageToken, resumeAt)
            }
            MorningState.STAGE2_PREP -> {
                val start = meta.stage2StartWall.takeIf { it > 0L } ?: session.updatedWall
                scheduleStage2Timers(
                    context = context,
                    sessionId = session.id,
                    alarmId = session.alarmId,
                    stageToken = meta.stageToken,
                    wakeCheckToken = meta.wakeCheckToken,
                    stage2StartWall = start,
                    wakeCheckState = meta.wakeCheckState,
                    wakeCheckPromptWall = meta.wakeCheckPromptWall,
                    wakeCheckDeadlineWall = meta.wakeCheckDeadlineWall,
                    settings = settings,
                    nowWall = nowWall
                )
            }
            MorningState.STAGE3_REWARD -> {
                if (meta.rewardChoice == MorningRewardChoice.INSTANT && meta.instantRewardEndWall > nowWall + 1_000L) {
                    scheduleStage3End(context, session.id, session.alarmId, meta.stageToken, meta.instantRewardEndWall)
                }
            }
            else -> Unit
        }
    }

    private fun scheduleAlarmTrigger(context: Context, alarmId: Long, triggerAt: Long, isSnooze: Boolean) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val firePi = firePendingIntent(context, alarmId, isSnooze, triggerAt)
        setAlarmClockWithFallback(context, am, triggerAt, firePi)
    }

    /** WakeNudge-inspired 3-tier exact scheduling fallback: setAlarmClock -> setExactAndAllowWhileIdle -> setAndAllowWhileIdle. */
    private fun setAlarmClockWithFallback(
        context: Context,
        am: AlarmManager,
        triggerAt: Long,
        operation: PendingIntent
    ) {
        try {
            if (canScheduleExact(context)) {
                val showIntent = PendingIntent.getActivity(
                    context,
                    0,
                    Intent(context, MindstopMorningActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                am.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAt, showIntent), operation)
                return
            }
        } catch (se: SecurityException) {
            Log.w(TAG, "setAlarmClock denied; trying exact/inexact fallback", se)
        }
        setExactWithFallback(am, triggerAt, operation)
    }

    private fun setExactWithFallback(am: AlarmManager, triggerAt: Long, pi: PendingIntent) {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
                return
            }
        } catch (_: SecurityException) {
        }
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
    }

    private fun firePendingIntent(context: Context, alarmId: Long, isSnooze: Boolean, scheduledWall: Long): PendingIntent {
        val kind = if (isSnooze) "snooze" else "alarm"
        val intent = Intent(context, MindstopAlarmReceiver::class.java).apply {
            action = MindstopAlarmReceiver.ACTION_FIRE_ALARM
            data = Uri.parse("mindstop://morning/$kind/$alarmId")
            putExtra(MindstopAlarmReceiver.EXTRA_ALARM_ID, alarmId)
            putExtra(MindstopAlarmReceiver.EXTRA_IS_SNOOZE, isSnooze)
            putExtra(MindstopAlarmReceiver.EXTRA_SCHEDULED_WALL, scheduledWall)
        }
        return PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun stagePendingIntent(
        context: Context,
        actionName: String,
        sessionId: Long,
        alarmId: Long,
        stageToken: Long,
        wakeCheckToken: Long
    ): PendingIntent {
        val intent = Intent(context, MindstopAlarmReceiver::class.java).apply {
            action = actionName
            data = Uri.parse("mindstop://morning/stage/$actionName/$sessionId")
            putExtra(MindstopAlarmReceiver.EXTRA_SESSION_ID, sessionId)
            putExtra(MindstopAlarmReceiver.EXTRA_ALARM_ID, alarmId)
            putExtra(MindstopAlarmReceiver.EXTRA_STAGE_TOKEN, stageToken)
            putExtra(MindstopAlarmReceiver.EXTRA_WAKE_CHECK_TOKEN, wakeCheckToken)
        }
        return PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
