package app.mindstop.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import app.mindstop.core.MorningAlarm
import app.mindstop.core.MorningRewardChoice
import app.mindstop.core.MorningState
import app.mindstop.ui.MindstopMorningActivationActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import neth.iecal.curbox.R

/**
 * Broadcast entry point for all Morning Activation V2 alarms, stage transitions, silent wake
 * checks, extended sleep resumption, and reboot recovery.
 *
 * Every stage event validates its monotonic [EXTRA_STAGE_TOKEN] / [EXTRA_WAKE_CHECK_TOKEN]
 * against persisted state so stale or late-delivered OS broadcasts can never ring, roll back,
 * or grant duplicate rewards.
 */
class MindstopAlarmReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "MindstopAlarmReceiver"
        const val ACTION_FIRE_ALARM = "app.mindstop.action.FIRE_ALARM"
        const val ACTION_CHECK_5M = "app.mindstop.action.MORNING_CHECK_5M"
        const val ACTION_CHECK_15M = "app.mindstop.action.MORNING_CHECK_15M"
        const val ACTION_BACKUP_REMIND = "app.mindstop.action.MORNING_BACKUP_REMIND"
        const val ACTION_STAGE1_SECOND_RING = "app.mindstop.action.MORNING_STAGE1_SECOND_RING"
        const val ACTION_STAGE1_TIMEOUT_RING = "app.mindstop.action.MORNING_STAGE1_TIMEOUT_RING"
        const val ACTION_EXTENDED_SLEEP_RESUME = "app.mindstop.action.MORNING_EXTENDED_SLEEP_RESUME"
        const val ACTION_WAKE_CHECK_PROMPT = "app.mindstop.action.MORNING_WAKE_CHECK_PROMPT"
        const val ACTION_WAKE_CHECK_CONFIRM = "app.mindstop.action.MORNING_WAKE_CHECK_CONFIRM"
        const val ACTION_WAKE_CHECK_DEADLINE = "app.mindstop.action.MORNING_WAKE_CHECK_DEADLINE"
        const val ACTION_STAGE2_TIMEOUT = "app.mindstop.action.MORNING_STAGE2_TIMEOUT"
        const val ACTION_STAGE3_END = "app.mindstop.action.MORNING_STAGE3_END"

        const val EXTRA_ALARM_ID = "app.mindstop.extra.ALARM_ID"
        const val EXTRA_SESSION_ID = "app.mindstop.extra.MORNING_SESSION_ID"
        const val EXTRA_IS_SNOOZE = "app.mindstop.extra.IS_SNOOZE"
        const val EXTRA_SCHEDULED_WALL = "app.mindstop.extra.SCHEDULED_WALL"
        const val EXTRA_STAGE_TOKEN = "app.mindstop.extra.STAGE_TOKEN"
        const val EXTRA_WAKE_CHECK_TOKEN = "app.mindstop.extra.WAKE_CHECK_TOKEN"

        private const val CHECKIN_CHANNEL_ID = "mindstop_morning_checkin"
        private const val SILENT_CHECK_CHANNEL_ID = "mindstop_morning_silent_check"
        private const val CHECKIN_NOTIFY_ID = 42_102
        private const val SKIPPED_NOTIFY_ID = 42_103
        const val WAKE_CHECK_NOTIFY_ID = 42_104
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        when (action) {
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                MindstopAlarmScheduler.rescheduleAllAsync(context, goAsync())
                return
            }
            ACTION_FIRE_ALARM -> handleFireAlarm(context, intent)
            ACTION_STAGE1_SECOND_RING -> handleStage1SecondRing(context, intent)
            ACTION_STAGE1_TIMEOUT_RING -> handleStage1TimeoutRing(context, intent)
            ACTION_EXTENDED_SLEEP_RESUME -> handleExtendedSleepResume(context, intent)
            ACTION_WAKE_CHECK_PROMPT -> handleWakeCheckPrompt(context, intent)
            ACTION_WAKE_CHECK_CONFIRM -> handleWakeCheckConfirm(context, intent)
            ACTION_WAKE_CHECK_DEADLINE -> handleWakeCheckDeadline(context, intent)
            ACTION_STAGE2_TIMEOUT -> handleStage2Timeout(context, intent)
            ACTION_STAGE3_END -> handleStage3End(context, intent)
            ACTION_CHECK_5M,
            ACTION_CHECK_15M,
            ACTION_BACKUP_REMIND -> handleLegacyCheckin(context, intent, action)
        }
    }

    private fun handleFireAlarm(context: Context, intent: Intent) {
        val alarmId = intent.getLongExtra(EXTRA_ALARM_ID, -1L)
        if (alarmId <= 0L) return
        val isSnooze = intent.getBooleanExtra(EXTRA_IS_SNOOZE, false)
        val scheduledWall = intent.getLongExtra(EXTRA_SCHEDULED_WALL, System.currentTimeMillis())
        val pending = goAsync()
        val appCtx = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val repo = MorningRepository(appCtx)
                val alarm = repo.getAlarm(alarmId) ?: return@launch
                if (!alarm.enabled && !isSnooze) return@launch
                val now = System.currentTimeMillis()
                if (!isSnooze) {
                    if (alarm.repeatDaysMask != MorningAlarm.ONCE_MASK) {
                        MindstopAlarmScheduler.schedule(appCtx, alarm, now)
                    } else {
                        repo.saveAlarm(alarm.copy(enabled = false), now)
                    }
                }
                if (!isSnooze && repo.shouldSkipAlarmBecauseAlreadyAwake(now)) {
                    repo.logSkippedAlarm(alarm.id, now)
                    postSkippedNotification(appCtx, alarm)
                    return@launch
                }
                val started = repo.startRinging(alarm, scheduledWall, now)
                val settings = repo.loadSettings()
                MindstopAlarmScheduler.scheduleStage1Timers(
                    context = appCtx,
                    sessionId = started.session.id,
                    alarmId = alarm.id,
                    stageToken = started.meta.stageToken,
                    stage1StartWall = started.meta.stage1StartWall,
                    settings = settings,
                    nowWall = now
                )
                launchAlarmService(appCtx, alarm.id, started.session.id)
            } catch (e: Exception) {
                Log.e(TAG, "Failed firing morning alarm $alarmId", e)
            } finally {
                pending.finish()
            }
        }
    }

    /**
     * At +5m after first ring: unlocks "I am awake" button AND fires 2nd ring if still unconfirmed,
     * which also unlocks the low-weight "仍需休息一会儿" intentional sleep extension option.
     */
    private fun handleStage1SecondRing(context: Context, intent: Intent) {
        val sessionId = intent.getLongExtra(EXTRA_SESSION_ID, -1L)
        val alarmId = intent.getLongExtra(EXTRA_ALARM_ID, -1L)
        val expectedToken = intent.getLongExtra(EXTRA_STAGE_TOKEN, -1L)
        if (sessionId <= 0L || expectedToken <= 0L) return
        val pending = goAsync()
        val appCtx = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val repo = MorningRepository(appCtx)
                val now = System.currentTimeMillis()
                val snap = repo.onStage1RepeatRing(sessionId, expectedToken, now) ?: return@launch
                if (!snap.changed) return@launch // Stale token or already moved past Stage 1
                val targetAlarmId = if (alarmId > 0L) alarmId else snap.session.alarmId
                val alarm = repo.getAlarm(targetAlarmId)
                postInfoNotification(
                    appCtx,
                    sessionId,
                    appCtx.getString(R.string.mindstop_morning_check5_title),
                    appCtx.getString(R.string.mindstop_morning_check5_body)
                )
                if (targetAlarmId > 0L && (alarm?.backupReminderEnabled ?: true)) {
                    launchAlarmService(appCtx, targetAlarmId, sessionId)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed handling Stage 1 2nd ring for session $sessionId", e)
            } finally {
                pending.finish()
            }
        }
    }

    /** At +15m (and subsequent intervals): enters overtime reminder state and re-rings until confirmed. */
    private fun handleStage1TimeoutRing(context: Context, intent: Intent) {
        val sessionId = intent.getLongExtra(EXTRA_SESSION_ID, -1L)
        val alarmId = intent.getLongExtra(EXTRA_ALARM_ID, -1L)
        val expectedToken = intent.getLongExtra(EXTRA_STAGE_TOKEN, -1L)
        if (sessionId <= 0L || expectedToken <= 0L) return
        val pending = goAsync()
        val appCtx = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val repo = MorningRepository(appCtx)
                val now = System.currentTimeMillis()
                val snap = repo.onStage1RepeatRing(sessionId, expectedToken, now) ?: return@launch
                if (!snap.changed) return@launch
                val settings = repo.loadSettings()
                val targetAlarmId = if (alarmId > 0L) alarmId else snap.session.alarmId
                // Schedule next periodic overtime reminder (never auto-advances to Stage 2!).
                MindstopAlarmScheduler.scheduleStage1NextOvertimeRing(
                    context = appCtx,
                    sessionId = sessionId,
                    alarmId = targetAlarmId,
                    stageToken = snap.meta.stageToken,
                    delayMs = settings.stage1RepeatMs,
                    nowWall = now
                )
                if (targetAlarmId > 0L) {
                    launchAlarmService(appCtx, targetAlarmId, sessionId)
                } else {
                    postInfoNotification(
                        appCtx,
                        sessionId,
                        appCtx.getString(R.string.mindstop_morning_backup_title),
                        appCtx.getString(R.string.mindstop_morning_backup_body)
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed handling Stage 1 timeout ring for session $sessionId", e)
            } finally {
                pending.finish()
            }
        }
    }

    private fun handleExtendedSleepResume(context: Context, intent: Intent) {
        val sessionId = intent.getLongExtra(EXTRA_SESSION_ID, -1L)
        val alarmId = intent.getLongExtra(EXTRA_ALARM_ID, -1L)
        val expectedToken = intent.getLongExtra(EXTRA_STAGE_TOKEN, -1L)
        if (sessionId <= 0L || expectedToken <= 0L) return
        val pending = goAsync()
        val appCtx = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val repo = MorningRepository(appCtx)
                val now = System.currentTimeMillis()
                val snap = repo.resumeFromExtendedSleep(sessionId, expectedToken, now) ?: return@launch
                if (!snap.changed) return@launch
                val settings = repo.loadSettings()
                val targetAlarmId = if (alarmId > 0L) alarmId else snap.session.alarmId
                MindstopAlarmScheduler.scheduleStage1Timers(
                    context = appCtx,
                    sessionId = sessionId,
                    alarmId = targetAlarmId,
                    stageToken = snap.meta.stageToken,
                    stage1StartWall = snap.meta.stage1StartWall,
                    settings = settings,
                    nowWall = now
                )
                if (targetAlarmId > 0L) {
                    launchAlarmService(appCtx, targetAlarmId, sessionId)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed resuming extended sleep for session $sessionId", e)
            } finally {
                pending.finish()
            }
        }
    }

    /** Stage 2 + 5m: opens silent wake-check window (or passes automatically if 30 steps detected). */
    private fun handleWakeCheckPrompt(context: Context, intent: Intent) {
        val sessionId = intent.getLongExtra(EXTRA_SESSION_ID, -1L)
        val alarmId = intent.getLongExtra(EXTRA_ALARM_ID, -1L)
        val checkToken = intent.getLongExtra(EXTRA_WAKE_CHECK_TOKEN, -1L)
        if (sessionId <= 0L || checkToken <= 0L) return
        val pending = goAsync()
        val appCtx = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val repo = MorningRepository(appCtx)
                val now = System.currentTimeMillis()
                val outcome = repo.triggerSilentWakeCheckPrompt(sessionId, checkToken, now)
                val sess = outcome.session ?: return@launch
                if (outcome.autoPassedBySteps) {
                    MindstopAlarmScheduler.cancelWakeCheckTimers(appCtx, sessionId)
                    return@launch
                }
                if (!outcome.openedPrompt) return@launch
                val targetAlarmId = if (alarmId > 0L) alarmId else sess.alarmId
                MindstopAlarmScheduler.scheduleWakeCheckDeadline(
                    context = appCtx,
                    sessionId = sessionId,
                    alarmId = targetAlarmId,
                    stageToken = outcome.meta.stageToken,
                    wakeCheckToken = outcome.meta.wakeCheckToken,
                    deadlineWall = outcome.meta.wakeCheckDeadlineWall
                )
                val settings = repo.loadSettings()
                postSilentWakeCheckNotification(appCtx, sessionId, outcome.meta.wakeCheckToken, settings.wakeCheckWindowMin)
            } catch (e: Exception) {
                Log.e(TAG, "Failed prompting silent wake check for session $sessionId", e)
            } finally {
                pending.finish()
            }
        }
    }

    /** Direct notification button tap: confirms silent wake check cleanly without opening app. */
    private fun handleWakeCheckConfirm(context: Context, intent: Intent) {
        val sessionId = intent.getLongExtra(EXTRA_SESSION_ID, -1L)
        val checkToken = intent.getLongExtra(EXTRA_WAKE_CHECK_TOKEN, -1L)
        if (sessionId <= 0L) return
        val pending = goAsync()
        val appCtx = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val repo = MorningRepository(appCtx)
                val snap = repo.confirmSilentWakeCheck(sessionId, checkToken.takeIf { it > 0L }, System.currentTimeMillis())
                if (snap != null && snap.changed) {
                    MindstopAlarmScheduler.cancelWakeCheckTimers(appCtx, sessionId)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed confirming silent wake check for session $sessionId", e)
            } finally {
                pending.finish()
            }
        }
    }

    /** Silent wake-check window timed out unanswered: rolls back Stage 2 -> Stage 1 and rings again. */
    private fun handleWakeCheckDeadline(context: Context, intent: Intent) {
        val sessionId = intent.getLongExtra(EXTRA_SESSION_ID, -1L)
        val alarmId = intent.getLongExtra(EXTRA_ALARM_ID, -1L)
        val checkToken = intent.getLongExtra(EXTRA_WAKE_CHECK_TOKEN, -1L)
        if (sessionId <= 0L || checkToken <= 0L) return
        val pending = goAsync()
        val appCtx = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val repo = MorningRepository(appCtx)
                val now = System.currentTimeMillis()
                val snap = repo.onSilentWakeCheckTimeout(sessionId, checkToken, now) ?: return@launch
                if (!snap.changed) return@launch // Already confirmed or exited
                MindstopAlarmScheduler.cancelAllStageTimers(appCtx, sessionId)
                val settings = repo.loadSettings()
                val targetAlarmId = if (alarmId > 0L) alarmId else snap.session.alarmId
                MindstopAlarmScheduler.scheduleStage1Timers(
                    context = appCtx,
                    sessionId = sessionId,
                    alarmId = targetAlarmId,
                    stageToken = snap.meta.stageToken,
                    stage1StartWall = snap.meta.stage1StartWall,
                    settings = settings,
                    nowWall = now
                )
                if (targetAlarmId > 0L) {
                    launchAlarmService(appCtx, targetAlarmId, sessionId)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed rolling back wake check deadline for session $sessionId", e)
            } finally {
                pending.finish()
            }
        }
    }

    private fun handleStage2Timeout(context: Context, intent: Intent) {
        val sessionId = intent.getLongExtra(EXTRA_SESSION_ID, -1L)
        val expectedToken = intent.getLongExtra(EXTRA_STAGE_TOKEN, -1L)
        if (sessionId <= 0L || expectedToken <= 0L) return
        val pending = goAsync()
        val appCtx = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val repo = MorningRepository(appCtx)
                val session = repo.getSession(sessionId) ?: return@launch
                val meta = repo.loadRuntimeMeta(sessionId)
                if (session.status != MorningState.STAGE2_PREP || meta.stageToken != expectedToken) return@launch
                postInfoNotification(
                    appCtx,
                    sessionId,
                    appCtx.getString(R.string.mindstop_morning_s2_timeout_title),
                    appCtx.getString(R.string.mindstop_morning_s2_timeout_body)
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed handling Stage 2 timeout for session $sessionId", e)
            } finally {
                pending.finish()
            }
        }
    }

    private fun handleStage3End(context: Context, intent: Intent) {
        val sessionId = intent.getLongExtra(EXTRA_SESSION_ID, -1L)
        val expectedToken = intent.getLongExtra(EXTRA_STAGE_TOKEN, -1L)
        if (sessionId <= 0L || expectedToken <= 0L) return
        val pending = goAsync()
        val appCtx = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val repo = MorningRepository(appCtx)
                val session = repo.getSession(sessionId) ?: return@launch
                val meta = repo.loadRuntimeMeta(sessionId)
                if (session.status != MorningState.STAGE3_REWARD ||
                    meta.stageToken != expectedToken ||
                    meta.rewardChoice != MorningRewardChoice.INSTANT
                ) {
                    return@launch
                }
                repo.completeActivation(sessionId, System.currentTimeMillis())
                MindstopAlarmScheduler.cancelAllStageTimers(appCtx, sessionId)
                postInfoNotification(
                    appCtx,
                    sessionId,
                    appCtx.getString(R.string.mindstop_morning_s3_end_title),
                    appCtx.getString(R.string.mindstop_morning_s3_end_body)
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed handling Stage 3 instant reward end for session $sessionId", e)
            } finally {
                pending.finish()
            }
        }
    }

    private fun handleLegacyCheckin(context: Context, intent: Intent, action: String) {
        val sessionId = intent.getLongExtra(EXTRA_SESSION_ID, -1L)
        if (sessionId <= 0L) return
        val pending = goAsync()
        val appCtx = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val repo = MorningRepository(appCtx)
                val session = repo.getSession(sessionId) ?: return@launch
                if (!session.status.isStage1) return@launch
                val (titleRes, bodyRes) = when (action) {
                    ACTION_CHECK_5M -> R.string.mindstop_morning_check5_title to R.string.mindstop_morning_check5_body
                    ACTION_BACKUP_REMIND -> R.string.mindstop_morning_backup_title to R.string.mindstop_morning_backup_body
                    else -> R.string.mindstop_morning_check15_title to R.string.mindstop_morning_check15_body
                }
                postInfoNotification(appCtx, sessionId, appCtx.getString(titleRes), appCtx.getString(bodyRes))
            } catch (e: Exception) {
                Log.e(TAG, "Failed posting legacy checkin $action", e)
            } finally {
                pending.finish()
            }
        }
    }

    private fun launchAlarmService(appCtx: Context, alarmId: Long, sessionId: Long) {
        val serviceIntent = Intent(appCtx, MindstopAlarmService::class.java).apply {
            putExtra(EXTRA_ALARM_ID, alarmId)
            putExtra(EXTRA_SESSION_ID, sessionId)
        }
        try {
            ContextCompat.startForegroundService(appCtx, serviceIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Foreground alarm service start blocked; posting fallback ring screen", e)
            val ringIntent = Intent(appCtx, app.mindstop.ui.MindstopAlarmRingActivity::class.java).apply {
                putExtra(EXTRA_ALARM_ID, alarmId)
                putExtra(EXTRA_SESSION_ID, sessionId)
            }
            BackgroundActivityLauncher.launch(appCtx, ringIntent, ((alarmId.toInt() and 0x7FFF) + 64_000))
        }
    }

    private fun postSilentWakeCheckNotification(context: Context, sessionId: Long, checkToken: Long, windowMin: Int) {
        val nm = context.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            SILENT_CHECK_CHANNEL_ID,
            context.getString(R.string.mindstop_morning_silent_check_channel),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            setSound(null, null)
            enableVibration(false)
        }
        nm.createNotificationChannel(channel)

        val openIntent = Intent(context, MindstopMorningActivationActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(EXTRA_SESSION_ID, sessionId)
        }
        val openPi = PendingIntent.getActivity(
            context,
            (sessionId.toInt() and 0x3FFF) + 52_000,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val confirmIntent = Intent(context, MindstopAlarmReceiver::class.java).apply {
            action = ACTION_WAKE_CHECK_CONFIRM
            data = Uri.parse("mindstop://morning/wakecheck_confirm/$sessionId/$checkToken")
            putExtra(EXTRA_SESSION_ID, sessionId)
            putExtra(EXTRA_WAKE_CHECK_TOKEN, checkToken)
        }
        val confirmPi = PendingIntent.getBroadcast(
            context,
            0,
            confirmIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, SILENT_CHECK_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mindstop_hourglass)
            .setContentTitle(context.getString(R.string.mindstop_morning_wakecheck_notif_title))
            .setContentText(context.getString(R.string.mindstop_morning_wakecheck_notif_body, windowMin))
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(openPi)
            .addAction(0, context.getString(R.string.mindstop_morning_wakecheck_confirm_btn), confirmPi)
            .build()
        nm.notify(WAKE_CHECK_NOTIFY_ID, notification)
    }

    private fun postSkippedNotification(context: Context, alarm: MorningAlarm) {
        val nm = context.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHECKIN_CHANNEL_ID,
            context.getString(R.string.mindstop_morning_checkin_channel),
            NotificationManager.IMPORTANCE_DEFAULT
        )
        nm.createNotificationChannel(channel)
        val timeText = "%02d:%02d".format(alarm.hour, alarm.minute)
        val notification = NotificationCompat.Builder(context, CHECKIN_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mindstop_hourglass)
            .setContentTitle(context.getString(R.string.mindstop_morning_skipped_title, timeText))
            .setContentText(context.getString(R.string.mindstop_morning_skipped_body))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        nm.notify(SKIPPED_NOTIFY_ID, notification)
    }

    private fun postInfoNotification(context: Context, sessionId: Long, title: String, body: String) {
        val nm = context.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHECKIN_CHANNEL_ID,
            context.getString(R.string.mindstop_morning_checkin_channel),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            enableVibration(true)
        }
        nm.createNotificationChannel(channel)

        val openIntent = Intent(context, MindstopMorningActivationActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(EXTRA_SESSION_ID, sessionId)
        }
        val pi = PendingIntent.getActivity(
            context,
            (sessionId.toInt() and 0x3FFF) + 50_000,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHECKIN_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mindstop_hourglass)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pi)
            .build()
        nm.notify(CHECKIN_NOTIFY_ID, notification)
    }
}
