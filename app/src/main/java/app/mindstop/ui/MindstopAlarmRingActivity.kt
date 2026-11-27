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
import android.text.format.DateFormat
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import app.mindstop.core.Difficulty
import app.mindstop.core.MathGenerator
import app.mindstop.core.MathQuestion
import app.mindstop.core.MorningAlarm
import app.mindstop.core.MorningRuntimeMeta
import app.mindstop.core.MorningState
import app.mindstop.core.MorningSessionRecord
import app.mindstop.core.MorningV2Settings
import app.mindstop.core.WakeupConfirmMethod
import app.mindstop.service.MindstopAlarmReceiver
import app.mindstop.service.MindstopAlarmScheduler
import app.mindstop.service.MindstopAlarmService
import app.mindstop.service.MorningRepository
import com.google.android.material.dialog.MaterialAlertDialogBuilder
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
 * Lock-screen morning alarm ringing activity (Morning Activation V2).
 *
 * - 1st ring: allows temporary mute into Stage 1 (does NOT mark wake-up done, does NOT offer
 *   ordinary sleep extension yet).
 * - 2nd ring onward (`meta.ringCount >= 2`): reveals the low-visual-weight "仍需休息一会儿"
 *   text button (3-second press-and-hold confirmation, no high-cognitive math punishment).
 * - Always keeps an honest Emergency Exit option (records as UNVERIFIED, never COMPLETED).
 */
class MindstopAlarmRingActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var binding: ActivityMindstopAlarmRingBinding
    private lateinit var repo: MorningRepository
    private val clockHandler = Handler(Looper.getMainLooper())
    private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())

    private var alarmId: Long = -1L
    private var sessionId: Long = -1L
    private var alarm: MorningAlarm = MorningAlarm()
    private var session: MorningSessionRecord? = null
    private var meta: MorningRuntimeMeta = MorningRuntimeMeta()
    private var settings: MorningV2Settings = MorningV2Settings()
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
            completeMuteToStage1()
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
                completeMuteToStage1()
            } else {
                Toast.makeText(this, R.string.mindstop_morning_finish_verify_first, Toast.LENGTH_SHORT).show()
            }
        }
        binding.ringExtendSleepBtn.setOnClickListener { openExtendedSleepPicker() }
        binding.ringEmergencyExitBtn.setOnClickListener { confirmEmergencyExit() }

        lifecycleScope.launch {
            loadAlarmAndSetup()
        }
    }

    private suspend fun loadAlarmAndSetup() {
        val requestedAlarm = alarmId
        val requestedSession = sessionId
        val loaded = withContext(Dispatchers.IO) {
            val a = if (requestedAlarm > 0L) repo.getAlarm(requestedAlarm) else null
            val s = if (requestedSession > 0L) repo.getSession(requestedSession) else repo.activeSession(System.currentTimeMillis())
            val m = if (s != null) repo.loadRuntimeMeta(s.id) else MorningRuntimeMeta()
            val cfg = repo.loadSettings()
            Quad(a ?: MorningAlarm(id = alarmId), s, m, cfg)
        }
        if (requestedAlarm != alarmId || requestedSession != sessionId) return
        if (requestedSession > 0 && loaded.session?.status?.let { it.isStage1 || it == MorningState.RINGING || it == MorningState.ACKNOWLEDGED } != true) {
            finish()
            return
        }
        alarm = loaded.alarm
        session = loaded.session
        if (loaded.session != null) sessionId = loaded.session.id
        meta = loaded.meta
        settings = loaded.settings

        binding.ringLabel.text = alarm.label.ifBlank { getString(R.string.mindstop_morning_ring_default_label) }
        // Only reveal intentional sleep extension starting from the 2nd ring (never on 1st ring!)
        binding.ringExtendSleepBtn.visibility = if (meta.canOfferExtendedSleep) View.VISIBLE else View.GONE
        setupChallenge()
    }

    private data class Quad(
        val alarm: MorningAlarm,
        val session: MorningSessionRecord?,
        val meta: MorningRuntimeMeta,
        val settings: MorningV2Settings
    )

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
        if (incomingAlarmId > 0L) {
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
                completeMuteToStage1()
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
                        completeMuteToStage1()
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
                completeMuteToStage1()
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
            completeMuteToStage1()
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

    /** Temporarily mutes alarm audio and opens Stage 1 without marking wake-up complete. */
    private fun completeMuteToStage1() {
        if (busy) return
        busy = true
        MindstopAlarmService.stop(this, sessionId)
        lifecycleScope.launch {
            val sid = withContext(Dispatchers.IO) {
                val now = System.currentTimeMillis()
                val actualSid = sessionId.takeIf { it > 0L } ?: repo.activeSession(now)?.id
                actualSid?.let { repo.acknowledgeAlarm(it, now)?.session?.id }
            }
            if (sid == null) {
                finish()
                return@launch
            }
            startActivity(
                Intent(this@MindstopAlarmRingActivity, MindstopMorningActivationActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(MindstopAlarmReceiver.EXTRA_SESSION_ID, sid)
                    .putExtra(MindstopAlarmReceiver.EXTRA_ALARM_ID, alarm.id)
            )
            finishAndRemoveTask()
        }
    }

    private fun openExtendedSleepPicker() {
        val s = session ?: return
        if (!meta.canOfferExtendedSleep || busy) return
        MorningExtendedSleepDialog.show(this, s, meta, settings) { minutes ->
            if (busy) return@show
            busy = true
            MindstopAlarmService.stop(this, sessionId)
            lifecycleScope.launch {
                val now = System.currentTimeMillis()
                val snap = withContext(Dispatchers.IO) { repo.enterExtendedSleep(s.id, minutes, now) }
                if (snap != null && snap.changed) {
                    val timeStr = DateFormat.getTimeFormat(this@MindstopAlarmRingActivity)
                        .format(Date(snap.meta.extendedSleepResumeWall))
                    Toast.makeText(
                        this@MindstopAlarmRingActivity,
                        getString(R.string.mindstop_morning_extend_confirmed_toast, timeStr),
                        Toast.LENGTH_LONG
                    ).show()
                    finishAndRemoveTask()
                } else {
                    busy = false
                }
            }
        }
    }

    private fun confirmEmergencyExit() {
        if (busy) return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.mindstop_morning_exit_dialog_title)
            .setMessage(R.string.mindstop_morning_exit_dialog_msg)
            .setPositiveButton(R.string.mindstop_morning_exit_emergency_btn) { _, _ ->
                executeExit(returnedToBed = false)
            }
            .setNeutralButton(R.string.mindstop_morning_exit_back_bed_btn) { _, _ ->
                executeExit(returnedToBed = true)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun executeExit(returnedToBed: Boolean) {
        busy = true
        MindstopAlarmService.stop(this, sessionId)
        lifecycleScope.launch {
            val sid = sessionId
            if (sid > 0L) {
                withContext(Dispatchers.IO) {
                    repo.reportExitNotCompleted(sid, returnedToBed = returnedToBed, nowWall = System.currentTimeMillis())
                }
                MindstopAlarmScheduler.cancelAllStageTimers(this@MindstopAlarmRingActivity, sid)
            }
            Toast.makeText(
                this@MindstopAlarmRingActivity,
                if (returnedToBed) R.string.mindstop_morning_act_returned_toast else R.string.mindstop_morning_emergency_exit_toast,
                Toast.LENGTH_LONG
            ).show()
            finishAndRemoveTask()
        }
    }
}
