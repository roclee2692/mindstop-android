package app.mindstop.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import app.mindstop.core.MorningAlarm
import app.mindstop.ui.MindstopAlarmRingActivity
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import neth.iecal.curbox.R

/**
 * Foreground ringing service for Mindstop morning alarms (v0.3 SPEC §10).
 *
 * Adapted from Purgator/Upupup (`AlarmService.kt`, GPL-3.0) with gradual volume ramp inspired by
 * gabeg805/NFC-Alarm-Clock (GPL-3.0). Handles back-to-back multi-alarm transitions cleanly, posts
 * both a full-screen intent and a direct notification dismiss action, and broadcasts package-scoped
 * ring state so foreground screens and the AccessibilityService can guarantee the dismiss UI opens.
 */
class MindstopAlarmService : Service() {

    companion object {
        private const val TAG = "MindstopAlarmService"
        const val ACTION_STOP = "app.mindstop.action.ALARM_STOP"
        const val ACTION_DISMISS_FROM_NOTIFICATION = "app.mindstop.action.ALARM_DISMISS_NOTIF"
        const val ACTION_ALARM_RINGING = "app.mindstop.action.ALARM_RINGING_BROADCAST"
        const val ACTION_ALARM_STOPPED = "app.mindstop.action.ALARM_STOPPED_BROADCAST"
        const val ACTION_ALARM_UI_OPENED = "app.mindstop.action.ALARM_UI_OPENED_BROADCAST"
        const val CHANNEL_ID = "mindstop_morning_alarm_ring"
        const val NOTIFICATION_ID = 42_101
        private const val MAX_RING_MS = 10 * 60_000L

        @Volatile var activeAlarmId: Long = -1L
            private set
        @Volatile var activeSessionId: Long = -1L
            private set

        fun isRinging(): Boolean = activeAlarmId > 0L

        fun stop(context: Context) {
            activeAlarmId = -1L
            activeSessionId = -1L
            try {
                context.applicationContext.stopService(Intent(context.applicationContext, MindstopAlarmService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "stopService failed", e)
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val generation = AtomicInteger(0)
    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var rampStep = 0
    private var rampTotalSteps = 1

    private val safetyTimeout = Runnable {
        Log.w(TAG, "Alarm reached safety timeout; stopping sound")
        stopSelf()
    }

    private val volumeRamper = object : Runnable {
        override fun run() {
            val mp = mediaPlayer ?: return
            rampStep++
            val fraction = (rampStep.toFloat() / rampTotalSteps.toFloat()).coerceIn(0.15f, 1.0f)
            try {
                mp.setVolume(fraction, fraction)
            } catch (_: Exception) {
                return
            }
            if (rampStep < rampTotalSteps) {
                mainHandler.postDelayed(this, 1_000L)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_DISMISS_FROM_NOTIFICATION -> {
                val sid = intent.getLongExtra(MindstopAlarmReceiver.EXTRA_SESSION_ID, activeSessionId)
                activeAlarmId = -1L
                activeSessionId = -1L
                stopAudio()
                stopVibration()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                }
                if (sid > 0L) {
                    scope.launch {
                        try {
                            MorningRepository(applicationContext).acknowledgeAlarm(sid, System.currentTimeMillis())
                        } catch (_: Exception) {
                        }
                        mainHandler.post { stopSelf() }
                    }
                } else {
                    stopSelf()
                }
                return START_NOT_STICKY
            }
        }

        val alarmId = intent?.getLongExtra(MindstopAlarmReceiver.EXTRA_ALARM_ID, -1L) ?: -1L
        val sessionId = intent?.getLongExtra(MindstopAlarmReceiver.EXTRA_SESSION_ID, -1L) ?: -1L
        if (alarmId <= 0L) {
            stopSelf()
            return START_NOT_STICKY
        }

        val myGen = generation.incrementAndGet()
        activeAlarmId = alarmId
        activeSessionId = sessionId

        // Reset any previous ringing state cleanly when a 2nd alarm fires back-to-back.
        stopAudio()
        stopVibration()
        mainHandler.removeCallbacks(safetyTimeout)
        mainHandler.postDelayed(safetyTimeout, MAX_RING_MS)

        val initialNotif = buildNotification(alarmId, sessionId, getString(R.string.mindstop_morning_ring_default_label))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, initialNotif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, initialNotif)
        }
        acquireWakeLock()

        // Notify foreground activities & AccessibilityService immediately so dismiss UI is guaranteed.
        broadcastRingEvent(ACTION_ALARM_RINGING, alarmId, sessionId)

        scope.launch {
            val alarm = try {
                MorningRepository(applicationContext).getAlarm(alarmId)
            } catch (_: Exception) {
                null
            } ?: MorningAlarm(id = alarmId)
            mainHandler.post {
                if (generation.get() != myGen) return@post
                val label = alarm.label.ifBlank { getString(R.string.mindstop_morning_ring_default_label) }
                val nm = getSystemService(NotificationManager::class.java)
                nm.notify(NOTIFICATION_ID, buildNotification(alarmId, sessionId, label))
                startAudio(alarm)
                if (alarm.vibrate) startVibration()
                launchRingScreen(alarmId, sessionId)
            }
        }
        return START_NOT_STICKY
    }

    private fun broadcastRingEvent(action: String, alarmId: Long, sessionId: Long) {
        try {
            val bc = Intent(action).apply {
                setPackage(packageName)
                putExtra(MindstopAlarmReceiver.EXTRA_ALARM_ID, alarmId)
                putExtra(MindstopAlarmReceiver.EXTRA_SESSION_ID, sessionId)
            }
            sendBroadcast(bc)
        } catch (_: Exception) {
        }
    }

    private fun buildNotification(alarmId: Long, sessionId: Long, label: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.mindstop_morning_ring_channel),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            setSound(null, null)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setBypassDnd(true)
        }
        nm.createNotificationChannel(channel)

        val ringIntent = Intent(this, MindstopAlarmRingActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_NO_USER_ACTION
            )
            putExtra(MindstopAlarmReceiver.EXTRA_ALARM_ID, alarmId)
            putExtra(MindstopAlarmReceiver.EXTRA_SESSION_ID, sessionId)
        }
        val reqCode = ((alarmId xor sessionId).toInt() and 0x7FFF) + 60_000
        val fullScreenPi = PendingIntent.getActivity(
            this,
            reqCode,
            ringIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val dismissIntent = Intent(this, MindstopAlarmService::class.java).apply {
            action = ACTION_DISMISS_FROM_NOTIFICATION
            putExtra(MindstopAlarmReceiver.EXTRA_ALARM_ID, alarmId)
            putExtra(MindstopAlarmReceiver.EXTRA_SESSION_ID, sessionId)
        }
        val dismissPi = PendingIntent.getService(
            this,
            reqCode + 1,
            dismissIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mindstop_hourglass)
            .setContentTitle(label)
            .setContentText(getString(R.string.mindstop_morning_ring_notif_body))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setFullScreenIntent(fullScreenPi, true)
            .setContentIntent(fullScreenPi)
            .addAction(0, getString(R.string.mindstop_morning_ring_open_btn), fullScreenPi)
            .addAction(0, getString(R.string.mindstop_morning_ring_stop_quick_btn), dismissPi)
            .build()
    }

    private fun launchRingScreen(alarmId: Long, sessionId: Long) {
        val ringIntent = Intent(this, MindstopAlarmRingActivity::class.java).apply {
            putExtra(MindstopAlarmReceiver.EXTRA_ALARM_ID, alarmId)
            putExtra(MindstopAlarmReceiver.EXTRA_SESSION_ID, sessionId)
        }
        val reqCode = ((alarmId xor sessionId).toInt() and 0x7FFF) + 62_000
        BackgroundActivityLauncher.launch(this, ringIntent, reqCode)
    }

    private fun startAudio(alarm: MorningAlarm) {
        stopAudio()
        val uri: Uri = alarm.ringtoneUri.takeIf { it.isNotBlank() }?.let(Uri::parse)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            ?: return
        try {
            val mp = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(this@MindstopAlarmService, uri)
                isLooping = true
                prepare()
                val rampSec = alarm.gradualVolumeSeconds.coerceIn(0, 120)
                if (rampSec > 0) {
                    setVolume(0.15f, 0.15f)
                    rampStep = 0
                    rampTotalSteps = rampSec
                    mainHandler.postDelayed(volumeRamper, 1_000L)
                } else {
                    setVolume(1.0f, 1.0f)
                }
                start()
            }
            mediaPlayer = mp
        } catch (e: Exception) {
            Log.e(TAG, "Could not start alarm audio", e)
        }
    }

    private fun stopAudio() {
        mainHandler.removeCallbacks(volumeRamper)
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        } catch (_: Exception) {
        }
        mediaPlayer = null
    }

    private fun startVibration() {
        stopVibration()
        val vib = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        vibrator = vib
        try {
            vib.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 500, 700), 0))
        } catch (_: Exception) {
        }
    }

    private fun stopVibration() {
        try {
            vibrator?.cancel()
        } catch (_: Exception) {
        }
        vibrator = null
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "mindstop:morning_alarm")
        wl.setReferenceCounted(false)
        wl.acquire(MAX_RING_MS)
        wakeLock = wl
    }

    override fun onDestroy() {
        val prevAlarm = activeAlarmId
        val prevSession = activeSessionId
        activeAlarmId = -1L
        activeSessionId = -1L
        broadcastRingEvent(ACTION_ALARM_STOPPED, prevAlarm, prevSession)
        mainHandler.removeCallbacksAndMessages(null)
        stopAudio()
        stopVibration()
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {
        }
        scope.cancel()
        super.onDestroy()
    }
}
