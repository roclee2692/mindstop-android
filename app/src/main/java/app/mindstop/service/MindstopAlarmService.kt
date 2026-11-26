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
 * gabeg805/NFC-Alarm-Clock (GPL-3.0): acquires a bounded WakeLock, plays looping alarm audio with
 * smooth fade-in, optionally vibrates, and posts a high-priority full-screen intent notification
 * so [MindstopAlarmRingActivity] opens reliably over the lock screen.
 */
class MindstopAlarmService : Service() {

    companion object {
        private const val TAG = "MindstopAlarmService"
        const val ACTION_STOP = "app.mindstop.action.ALARM_STOP"
        const val CHANNEL_ID = "mindstop_morning_alarm_ring"
        const val NOTIFICATION_ID = 42_101
        private const val MAX_RING_MS = 10 * 60_000L

        fun stop(context: Context) {
            context.startService(Intent(context, MindstopAlarmService::class.java).apply { action = ACTION_STOP })
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
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
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val alarmId = intent?.getLongExtra(MindstopAlarmReceiver.EXTRA_ALARM_ID, -1L) ?: -1L
        val sessionId = intent?.getLongExtra(MindstopAlarmReceiver.EXTRA_SESSION_ID, -1L) ?: -1L
        if (alarmId <= 0L) {
            stopSelf()
            return START_NOT_STICKY
        }

        val initialNotif = buildNotification(alarmId, sessionId, getString(R.string.mindstop_morning_ring_default_label))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, initialNotif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, initialNotif)
        }
        acquireWakeLock()
        mainHandler.removeCallbacks(safetyTimeout)
        mainHandler.postDelayed(safetyTimeout, MAX_RING_MS)

        scope.launch {
            val alarm = try {
                MorningRepository(applicationContext).getAlarm(alarmId)
            } catch (_: Exception) {
                null
            } ?: MorningAlarm(id = alarmId)
            mainHandler.post {
                val label = alarm.label.ifBlank { getString(R.string.mindstop_morning_ring_default_label) }
                getSystemService(NotificationManager::class.java)
                    .notify(NOTIFICATION_ID, buildNotification(alarmId, sessionId, label))
                startAudio(alarm)
                if (alarm.vibrate) startVibration()
                launchRingScreen(alarmId, sessionId)
            }
        }
        return START_NOT_STICKY
    }

    private fun buildNotification(alarmId: Long, sessionId: Long, label: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.mindstop_morning_ring_channel),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            setSound(null, null) // Audio is managed directly by MediaPlayer for gradual volume ramp
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setBypassDnd(true)
        }
        nm.createNotificationChannel(channel)

        val ringIntent = Intent(this, MindstopAlarmRingActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
            putExtra(MindstopAlarmReceiver.EXTRA_ALARM_ID, alarmId)
            putExtra(MindstopAlarmReceiver.EXTRA_SESSION_ID, sessionId)
        }
        val fullScreenPi = PendingIntent.getActivity(
            this,
            (alarmId.toInt() and 0x7FFF) + 60_000,
            ringIntent,
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
            .build()
    }

    private fun launchRingScreen(alarmId: Long, sessionId: Long) {
        val ringIntent = Intent(this, MindstopAlarmRingActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(MindstopAlarmReceiver.EXTRA_ALARM_ID, alarmId)
            putExtra(MindstopAlarmReceiver.EXTRA_SESSION_ID, sessionId)
        }
        try {
            startActivity(ringIntent)
        } catch (e: Exception) {
            Log.w(TAG, "Direct ring startActivity blocked; fullScreenIntent notification remains active", e)
        }
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
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "mindstop:morning_alarm")
        wl.setReferenceCounted(false)
        wl.acquire(MAX_RING_MS)
        wakeLock = wl
    }

    override fun onDestroy() {
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
