package app.mindstop.ui

import android.app.KeyguardManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import app.mindstop.core.Difficulty
import app.mindstop.core.MathGenerator
import app.mindstop.core.MathQuestion
import app.mindstop.core.MorningAlarm
import app.mindstop.core.WakeupConfirmMethod
import app.mindstop.service.MindstopAlarmReceiver
import app.mindstop.service.MindstopAlarmScheduler
import app.mindstop.service.MindstopAlarmService
import app.mindstop.service.MorningRepository
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import neth.iecal.curbox.R
import neth.iecal.curbox.databinding.ActivityMindstopAlarmRingBinding
import neth.iecal.curbox.ui.activity.PortraitCaptureActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Lock-screen morning alarm ringing activity (v0.3 SPEC §10–§11).
 * Shows directly over the lock screen, supports optional Math (reusing existing [MathGenerator]),
 * Step sensor, QR scan, or NFC verification, and immediately transitions into the 15-minute
 * [MindstopMorningActivationActivity] instead of falsely claiming wake-up is already complete.
 */
class MindstopAlarmRingActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var binding: ActivityMindstopAlarmRingBinding
    private lateinit var repo: MorningRepository
    private val clockHandler = Handler(Looper.getMainLooper())
    private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())

    private var alarmId: Long = -1L
    private var sessionId: Long = -1L
    private var alarm: MorningAlarm = MorningAlarm()
    private var busy = false

    // Challenge state
    private var challengePassed = false
    private var mathSolved = 0
    private var mathIndex = 0
    private var mathSeed = 0L
    private var currentMath: MathQuestion? = null
    private var stepsCount = 0
    private var sensorManager: SensorManager? = null
    private var nfcAdapter: NfcAdapter? = null

    private val qrLauncher = registerForActivityResult(ScanContract()) { result ->
        val contents = result.contents?.trim().orEmpty()
        if (contents.isBlank()) return@registerForActivityResult
        if (alarm.targetTagOrQr.isBlank() || contents.equals(alarm.targetTagOrQr.trim(), ignoreCase = true)) {
            challengePassed = true
            completeDismiss()
        } else {
            Toast.makeText(this, R.string.mindstop_morning_qr_mismatch, Toast.LENGTH_SHORT).show()
        }
    }

    private val clockTicker = object : Runnable {
        override fun run() {
            binding.ringClock.text = timeFmt.format(Date())
            clockHandler.postDelayed(this, 1_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableLockscreenDisplay()
        binding = ActivityMindstopAlarmRingBinding.inflate(layoutInflater)
        setContentView(binding.root)
        MindstopUi.applySystemBarInsets(this, binding.ringRoot)

        repo = MorningRepository(this)
        alarmId = intent.getLongExtra(MindstopAlarmReceiver.EXTRA_ALARM_ID, -1L)
        sessionId = intent.getLongExtra(MindstopAlarmReceiver.EXTRA_SESSION_ID, -1L)
        if (alarmId <= 0L && MindstopAlarmService.isRinging()) {
            alarmId = MindstopAlarmService.activeAlarmId
            sessionId = MindstopAlarmService.activeSessionId
        }
        mathSeed = System.currentTimeMillis()
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)

        onBackPressedDispatcher.addCallback(this) { /* Keep ring screen focused while ringing */ }
        binding.ringDismissBtn.setOnClickListener {
            if (challengePassed || alarm.dismissMethod == WakeupConfirmMethod.MANUAL) {
                completeDismiss()
            } else {
                Toast.makeText(this, R.string.mindstop_morning_finish_verify_first, Toast.LENGTH_SHORT).show()
            }
        }
        binding.ringSnoozeBtn.setOnClickListener { snooze() }

        lifecycleScope.launch {
            loadAlarmAndSetup()
        }
    }

    private suspend fun loadAlarmAndSetup() {
        val loaded = withContext(Dispatchers.IO) {
            if (alarmId > 0L) repo.getAlarm(alarmId) else null
        } ?: MorningAlarm(id = alarmId)
        alarm = loaded
        binding.ringLabel.text = alarm.label.ifBlank { getString(R.string.mindstop_morning_ring_default_label) }
        binding.ringSnoozeBtn.text = getString(R.string.mindstop_morning_ring_snooze_min, alarm.snoozeMinutes)
        setupChallenge()
    }

    override fun onResume() {
        super.onResume()
        clockHandler.post(clockTicker)
        notifyRingUiOpened()
        if (alarm.dismissMethod == WakeupConfirmMethod.NFC && !challengePassed) {
            enableNfcDispatch()
        }
    }

    private fun notifyRingUiOpened() {
        try {
            val bc = Intent(MindstopAlarmService.ACTION_ALARM_UI_OPENED).apply {
                setPackage(packageName)
                putExtra(MindstopAlarmReceiver.EXTRA_ALARM_ID, alarmId)
                putExtra(MindstopAlarmReceiver.EXTRA_SESSION_ID, sessionId)
            }
            sendBroadcast(bc)
        } catch (_: Exception) {
        }
    }

    override fun onPause() {
        clockHandler.removeCallbacks(clockTicker)
        disableNfcDispatch()
        super.onPause()
    }

    override fun onDestroy() {
        sensorManager?.unregisterListener(this)
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val incomingAlarmId = intent.getLongExtra(MindstopAlarmReceiver.EXTRA_ALARM_ID, -1L)
        val incomingSessionId = intent.getLongExtra(MindstopAlarmReceiver.EXTRA_SESSION_ID, -1L)
        if (incomingAlarmId > 0L && (incomingAlarmId != alarmId || incomingSessionId != sessionId || busy)) {
            setIntent(intent)
            sensorManager?.unregisterListener(this)
            disableNfcDispatch()
            alarmId = incomingAlarmId
            sessionId = incomingSessionId
            busy = false
            challengePassed = false
            mathSolved = 0
            mathIndex = 0
            mathSeed = System.currentTimeMillis()
            stepsCount = 0
            lifecycleScope.launch { loadAlarmAndSetup() }
            return
        }
        if (alarm.dismissMethod == WakeupConfirmMethod.NFC && !challengePassed) {
            @Suppress("DEPRECATION")
            val tag: Tag? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
            } else {
                intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
            }
            val tagId = tag?.id?.joinToString("") { "%02X".format(it) }.orEmpty()
            if (tagId.isNotBlank() && (alarm.targetTagOrQr.isBlank() || tagId.equals(alarm.targetTagOrQr.trim(), ignoreCase = true))) {
                challengePassed = true
                completeDismiss()
            } else if (tagId.isNotBlank()) {
                Toast.makeText(this, R.string.mindstop_morning_nfc_mismatch, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun enableLockscreenDisplay() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            (getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager)?.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun setupChallenge() {
        // Reset visibility cleanly in case onNewIntent reloads a second alarm with a different method.
        binding.ringChallengeAction.visibility = View.VISIBLE
        binding.ringChallengeAction.setOnClickListener(null)
        when (alarm.dismissMethod) {
            WakeupConfirmMethod.MANUAL -> {
                challengePassed = true
                binding.ringChallengeCard.visibility = View.GONE
                binding.ringDismissBtn.visibility = View.VISIBLE
            }
            WakeupConfirmMethod.MATH -> {
                binding.ringChallengeCard.visibility = View.VISIBLE
                binding.ringMathInputLayout.visibility = View.VISIBLE
                binding.ringDismissBtn.visibility = View.GONE
                showNextMath()
                binding.ringChallengeAction.setText(R.string.mindstop_challenge_submit)
                binding.ringChallengeAction.setOnClickListener { checkMathAnswer() }
            }
            WakeupConfirmMethod.STEPS -> {
                binding.ringChallengeCard.visibility = View.VISIBLE
                binding.ringMathInputLayout.visibility = View.GONE
                val sm = getSystemService(Context.SENSOR_SERVICE) as SensorManager
                sensorManager = sm
                val stepSensor = sm.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
                val targetSteps = alarm.challengeCount.coerceIn(5, 60)
                if (stepSensor != null) {
                    sm.registerListener(this, stepSensor, SensorManager.SENSOR_DELAY_UI)
                    binding.ringChallengePrompt.text = getString(R.string.mindstop_morning_steps_prompt, stepsCount, targetSteps)
                    binding.ringChallengeAction.setText(R.string.mindstop_morning_steps_fallback)
                    binding.ringChallengeAction.setOnClickListener {
                        challengePassed = true
                        completeDismiss()
                    }
                    binding.ringDismissBtn.visibility = View.GONE
                } else {
                    challengePassed = true
                    binding.ringChallengePrompt.setText(R.string.mindstop_morning_steps_unavailable)
                    binding.ringChallengeAction.visibility = View.GONE
                    binding.ringDismissBtn.visibility = View.VISIBLE
                }
            }
            WakeupConfirmMethod.QR -> {
                binding.ringChallengeCard.visibility = View.VISIBLE
                binding.ringMathInputLayout.visibility = View.GONE
                binding.ringChallengePrompt.setText(R.string.mindstop_morning_qr_prompt)
                binding.ringChallengeAction.setText(R.string.mindstop_morning_qr_scan_btn)
                binding.ringChallengeAction.setOnClickListener {
                    val opts = ScanOptions().apply {
                        setCaptureActivity(PortraitCaptureActivity::class.java)
                        setOrientationLocked(true)
                        setBeepEnabled(false)
                    }
                    qrLauncher.launch(opts)
                }
                binding.ringDismissBtn.visibility = View.VISIBLE
            }
            WakeupConfirmMethod.NFC -> {
                binding.ringChallengeCard.visibility = View.VISIBLE
                binding.ringMathInputLayout.visibility = View.GONE
                binding.ringChallengePrompt.setText(
                    if (nfcAdapter != null) R.string.mindstop_morning_nfc_prompt else R.string.mindstop_morning_nfc_unavailable
                )
                binding.ringChallengeAction.visibility = View.GONE
                binding.ringDismissBtn.visibility = View.VISIBLE
                enableNfcDispatch()
            }
        }
    }

    private fun showNextMath() {
        val needed = alarm.challengeCount.coerceIn(1, 5)
        val q = MathGenerator.generate(mathSeed, mathIndex++, Difficulty.EASY)
        currentMath = q
        binding.ringChallengePrompt.text = getString(R.string.mindstop_morning_math_prompt, mathSolved, needed, q.prompt)
        binding.ringMathInput.setText("")
    }

    private fun checkMathAnswer() {
        val q = currentMath ?: return
        val typed = MathGenerator.parseAnswer(binding.ringMathInput.text?.toString().orEmpty())
        if (typed == q.answer) {
            mathSolved++
            val needed = alarm.challengeCount.coerceIn(1, 5)
            if (mathSolved >= needed) {
                challengePassed = true
                completeDismiss()
            } else {
                showNextMath()
            }
        } else {
            Toast.makeText(this, getString(R.string.mindstop_challenge_wrong, q.answer.toString()), Toast.LENGTH_SHORT).show()
            showNextMath()
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type != Sensor.TYPE_STEP_DETECTOR || challengePassed) return
        stepsCount++
        val targetSteps = alarm.challengeCount.coerceIn(5, 60)
        binding.ringChallengePrompt.text = getString(R.string.mindstop_morning_steps_prompt, stepsCount, targetSteps)
        if (stepsCount >= targetSteps) {
            challengePassed = true
            sensorManager?.unregisterListener(this)
            completeDismiss()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun enableNfcDispatch() {
        val adapter = nfcAdapter ?: return
        val pi = PendingIntent.getActivity(
            this,
            0,
            Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
        try {
            adapter.enableForegroundDispatch(this, pi, null, null)
        } catch (_: Exception) {
        }
    }

    private fun disableNfcDispatch() {
        try {
            nfcAdapter?.disableForegroundDispatch(this)
        } catch (_: Exception) {
        }
    }

    private fun completeDismiss() {
        if (busy) return
        busy = true
        MindstopAlarmService.stop(this)
        lifecycleScope.launch {
            val sid = withContext(Dispatchers.IO) {
                val now = System.currentTimeMillis()
                val actualSid = if (sessionId > 0L) {
                    sessionId
                } else {
                    repo.startRinging(alarm, now, now).id
                }
                repo.acknowledgeAlarm(actualSid, now)
                actualSid
            }
            MindstopAlarmScheduler.scheduleActivationCheckins(
                context = this@MindstopAlarmRingActivity,
                sessionId = sid,
                alarmId = alarm.id,
                firstCheckMin = 5,
                finalCheckMin = 15,
                backupReminderEnabled = alarm.backupReminderEnabled
            )
            startActivity(
                Intent(this@MindstopAlarmRingActivity, MindstopMorningActivationActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(MindstopAlarmReceiver.EXTRA_SESSION_ID, sid)
                    .putExtra(MindstopAlarmReceiver.EXTRA_ALARM_ID, alarm.id)
            )
            finishAndRemoveTask()
        }
    }

    private fun snooze() {
        if (busy) return
        busy = true
        MindstopAlarmService.stop(this)
        if (alarm.id > 0L) {
            MindstopAlarmScheduler.scheduleSnooze(this, alarm.id, alarm.snoozeMinutes)
        }
        Toast.makeText(this, getString(R.string.mindstop_morning_snoozed_toast, alarm.snoozeMinutes), Toast.LENGTH_SHORT).show()
        finishAndRemoveTask()
    }
}
