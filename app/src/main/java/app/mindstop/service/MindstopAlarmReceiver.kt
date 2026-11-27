package app.mindstop.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import app.mindstop.core.MorningAlarm
import app.mindstop.core.MorningState
import app.mindstop.ui.MindstopMorningActivationActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import neth.iecal.curbox.R

/**
 * Broadcast entry point for scheduled Mindstop morning alarms, 5m/15m gentle activation check-ins,
 * and system clock/timezone changes. Adapted from Purgator/Upupup (`AlarmReceiver.kt`, GPL-3.0).
 */
class MindstopAlarmReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "MindstopAlarmReceiver"
        const val ACTION_FIRE_ALARM = "app.mindstop.action.FIRE_ALARM"
        const val ACTION_CHECK_5M = "app.mindstop.action.MORNING_CHECK_5M"
        const val ACTION_CHECK_15M = "app.mindstop.action.MORNING_CHECK_15M"
        const val ACTION_BACKUP_REMIND = "app.mindstop.action.MORNING_BACKUP_REMIND"
        const val ACTION_STAGE1_TIMEOUT_RING = "app.mindstop.action.MORNING_STAGE1_TIMEOUT_RING"

        const val EXTRA_ALARM_ID = "app.mindstop.extra.ALARM_ID"
        const val EXTRA_SESSION_ID = "app.mindstop.extra.MORNING_SESSION_ID"
        const val EXTRA_IS_SNOOZE = "app.mindstop.extra.IS_SNOOZE"
        const val EXTRA_SCHEDULED_WALL = "app.mindstop.extra.SCHEDULED_WALL"

        private const val CHECKIN_CHANNEL_ID = "mindstop_morning_checkin"
        private const val CHECKIN_NOTIFY_ID = 42_102
        private const val SKIPPED_NOTIFY_ID = 42_103
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
            ACTION_STAGE1_TIMEOUT_RING -> handleStage1TimeoutRing(context, intent)
            ACTION_CHECK_5M,
            ACTION_CHECK_15M,
            ACTION_BACKUP_REMIND -> handleCheckin(context, intent, action)
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
                // Upupup reliability pattern: re-arm next occurrence immediately for repeating alarms.
                if (!isSnooze) {
                    if (alarm.repeatDaysMask != MorningAlarm.ONCE_MASK) {
                        MindstopAlarmScheduler.schedule(appCtx, alarm, now)
                    } else {
                        repo.saveAlarm(alarm.copy(enabled = false), now)
                    }
                }
                // Auto-skip subsequent morning alarms if user already completed Morning Activation
                // within the same morning window or has already progressed into Stage 2 / Stage 3.
                if (!isSnooze && repo.shouldSkipAlarmBecauseAlreadyAwake(now)) {
                    repo.logSkippedAlarm(alarm.id, now)
                    postSkippedNotification(appCtx, alarm)
                    return@launch
                }
                val session = repo.startRinging(alarm, scheduledWall, now)
                launchAlarmService(appCtx, alarm.id, session.id)
            } catch (e: Exception) {
                Log.e(TAG, "Failed firing morning alarm $alarmId", e)
            } finally {
                pending.finish()
            }
        }
    }

    private fun handleStage1TimeoutRing(context: Context, intent: Intent) {
        val sessionId = intent.getLongExtra(EXTRA_SESSION_ID, -1L)
        val alarmId = intent.getLongExtra(EXTRA_ALARM_ID, -1L)
        if (sessionId <= 0L) return
        val pending = goAsync()
        val appCtx = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val repo = MorningRepository(appCtx)
                val session = repo.getSession(sessionId) ?: return@launch
                // Only re-ring if user is STILL in Stage 1 after 15 minutes (never started Stage 2).
                if (!session.status.isStage1) return@launch
                val targetAlarmId = if (alarmId > 0L) alarmId else session.alarmId
                if (targetAlarmId <= 0L) {
                    postCheckinNotification(appCtx, sessionId, ACTION_BACKUP_REMIND)
                    return@launch
                }
                launchAlarmService(appCtx, targetAlarmId, sessionId)
            } catch (e: Exception) {
                Log.e(TAG, "Failed firing Stage 1 timeout ring for session $sessionId", e)
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
            Log.e(TAG, "Foreground alarm service start blocked; posting fallback ring notification", e)
            val ringIntent = Intent(appCtx, app.mindstop.ui.MindstopAlarmRingActivity::class.java).apply {
                putExtra(EXTRA_ALARM_ID, alarmId)
                putExtra(EXTRA_SESSION_ID, sessionId)
            }
            BackgroundActivityLauncher.launch(appCtx, ringIntent, ((alarmId.toInt() and 0x7FFF) + 64_000))
        }
    }

    private fun handleCheckin(context: Context, intent: Intent, action: String) {
        val sessionId = intent.getLongExtra(EXTRA_SESSION_ID, -1L)
        if (sessionId <= 0L) return
        val pending = goAsync()
        val appCtx = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val repo = MorningRepository(appCtx)
                val session = repo.getSession(sessionId) ?: return@launch
                if (!session.status.isStage1) return@launch
                postCheckinNotification(appCtx, sessionId, action)
            } catch (e: Exception) {
                Log.e(TAG, "Failed posting morning checkin $action", e)
            } finally {
                pending.finish()
            }
        }
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

    private fun postCheckinNotification(context: Context, sessionId: Long, action: String) {
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
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(EXTRA_SESSION_ID, sessionId)
        }
        val pi = PendingIntent.getActivity(
            context,
            (sessionId.toInt() and 0x3FFF) + 50_000,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val (titleRes, bodyRes) = when (action) {
            ACTION_CHECK_5M -> R.string.mindstop_morning_check5_title to R.string.mindstop_morning_check5_body
            ACTION_BACKUP_REMIND -> R.string.mindstop_morning_backup_title to R.string.mindstop_morning_backup_body
            else -> R.string.mindstop_morning_check15_title to R.string.mindstop_morning_check15_body
        }
        val notification = NotificationCompat.Builder(context, CHECKIN_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mindstop_hourglass)
            .setContentTitle(context.getString(titleRes))
            .setContentText(context.getString(bodyRes))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pi)
            .build()
        nm.notify(CHECKIN_NOTIFY_ID, notification)
    }
}
