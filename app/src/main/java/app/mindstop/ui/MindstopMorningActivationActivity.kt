package app.mindstop.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.CheckBox
import android.widget.RadioButton
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import app.mindstop.core.BodyWakeActivity
import app.mindstop.core.MorningActivationEngine
import app.mindstop.core.MorningAlarm
import app.mindstop.core.MorningJoyPreset
import app.mindstop.core.MorningSessionRecord
import app.mindstop.core.MorningState
import app.mindstop.core.RewardEngine
import app.mindstop.service.MindstopAlarmReceiver
import app.mindstop.service.MindstopAlarmScheduler
import app.mindstop.service.MindstopAlarmService
import app.mindstop.service.MorningRepository
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import neth.iecal.curbox.R
import neth.iecal.curbox.databinding.ActivityMindstopMorningActivationBinding

/**
 * Strict sequential 3-stage Morning Activation screen (v0.3 SPEC §11–§13).
 *
 * - Stage 1 (STAGE1_WAKE): 15-minute out-of-bed window with a 5-minute anti-cheat lock before
 *   the "Start Stage 2" button unlocks; failing to start Stage 2 within 15 minutes rings a backup alarm.
 * - Stage 2 (STAGE2_PREP): 30-minute wash-up & morning prep countdown with checklist.
 * - Stage 3 (STAGE3_REWARD): Morning joy countdown (music, audiobook, breakfast, short game,
 *   market/news, growth garden) + optional +15 min daily entertainment quota bonus (defaults to
 *   afternoon-only after 12:00 so mornings stay calm).
 */
class MindstopMorningActivationActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var binding: ActivityMindstopMorningActivationBinding
    private lateinit var repo: MorningRepository
    private val handler = Handler(Looper.getMainLooper())

    private var sessionId: Long = -1L
    private var alarmId: Long = -1L
    private var session: MorningSessionRecord? = null
    private var stageMeta: MorningRepository.StageProgressMeta = MorningRepository.StageProgressMeta()
    private var alarm: MorningAlarm = MorningAlarm()
    private var sensorManager: SensorManager? = null
    private var motionRecorded = false
    private var busy = false
    private var checklistBuiltForSession = -1L
    private var joyBuiltForSession = -1L

    private val ticker = object : Runnable {
        override fun run() {
            updateCountdown()
            handler.postDelayed(this, 1_000L)
        }
    }

    private val alarmRingReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != MindstopAlarmService.ACTION_ALARM_RINGING) return
            val ringAlarmId = intent.getLongExtra(MindstopAlarmReceiver.EXTRA_ALARM_ID, -1L)
            val ringSessionId = intent.getLongExtra(MindstopAlarmReceiver.EXTRA_SESSION_ID, -1L)
            if (ringAlarmId > 0L) openActiveRingScreen(ringAlarmId, ringSessionId)
        }
    }

    private fun openActiveRingScreen(ringAlarmId: Long, ringSessionId: Long) {
        val ring = Intent(this, MindstopAlarmRingActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(MindstopAlarmReceiver.EXTRA_ALARM_ID, ringAlarmId)
            putExtra(MindstopAlarmReceiver.EXTRA_SESSION_ID, ringSessionId)
        }
        try {
            startActivity(ring)
        } catch (_: Exception) {
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMindstopMorningActivationBinding.inflate(layoutInflater)
        setContentView(binding.root)
        MindstopUi.applySystemBarInsets(this, binding.activationRoot)
        MindstopUi.setupToolbar(this, binding.activationToolbar, R.string.mindstop_morning_act_title)

        repo = MorningRepository(this)
        sessionId = intent.getLongExtra(MindstopAlarmReceiver.EXTRA_SESSION_ID, -1L)
        alarmId = intent.getLongExtra(MindstopAlarmReceiver.EXTRA_ALARM_ID, -1L)

        binding.activationStartStage2Btn.setOnClickListener { startStage2() }
        binding.activationFinishStage2Btn.setOnClickListener { startStage3() }
        binding.activationClaimBonusBtn.setOnClickListener { claimOptionalBonus() }
        binding.activationCompleteBtn.setOnClickListener { completeActivation() }
        binding.activationBackToBedBtn.setOnClickListener { reportReturnedToBed() }

        loadSessionFromIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        busy = false
        motionRecorded = false
        loadSessionFromIntent(intent)
    }

    private fun loadSessionFromIntent(source: Intent) {
        sessionId = source.getLongExtra(MindstopAlarmReceiver.EXTRA_SESSION_ID, -1L)
        alarmId = source.getLongExtra(MindstopAlarmReceiver.EXTRA_ALARM_ID, -1L)
        lifecycleScope.launch {
            val now = System.currentTimeMillis()
            val loadedSession = withContext(Dispatchers.IO) {
                if (sessionId > 0L) repo.getSession(sessionId) else repo.activeSession(now)
            }
            if (loadedSession == null || loadedSession.status.isTerminal) {
                finish()
                return@launch
            }
            session = loadedSession
            sessionId = loadedSession.id
            alarm = withContext(Dispatchers.IO) { repo.getAlarm(loadedSession.alarmId) } ?: MorningAlarm(id = alarmId)
            stageMeta = withContext(Dispatchers.IO) { repo.loadStageMeta(loadedSession.id) }
            render()
            registerAuxiliaryMotionHint()
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(ticker)
        ContextCompat.registerReceiver(
            this,
            alarmRingReceiver,
            IntentFilter(MindstopAlarmService.ACTION_ALARM_RINGING),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        if (MindstopAlarmService.isRinging()) {
            openActiveRingScreen(MindstopAlarmService.activeAlarmId, MindstopAlarmService.activeSessionId)
        }
    }

    override fun onPause() {
        try {
            unregisterReceiver(alarmRingReceiver)
        } catch (_: Exception) {
        }
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    override fun onDestroy() {
        sensorManager?.unregisterListener(this)
        super.onDestroy()
    }

    private fun render() {
        val s = session ?: return
        val activeStage = when {
            s.status.isStage1 -> 1
            s.status == MorningState.STAGE2_PREP -> 2
            s.status == MorningState.STAGE3_REWARD -> 3
            else -> 1
        }

        styleStepPill(binding.activationStep1Pill, binding.activationStep1Text, step = 1, activeStage = activeStage)
        styleStepPill(binding.activationStep2Pill, binding.activationStep2Text, step = 2, activeStage = activeStage)
        styleStepPill(binding.activationStep3Pill, binding.activationStep3Text, step = 3, activeStage = activeStage)

        binding.activationStage1Card.visibility = if (activeStage == 1) View.VISIBLE else View.GONE
        binding.activationStage2Card.visibility = if (activeStage == 2) View.VISIBLE else View.GONE
        binding.activationStage3Card.visibility = if (activeStage == 3) View.VISIBLE else View.GONE
        binding.activationBackToBedBtn.visibility = if (activeStage <= 2) View.VISIBLE else View.GONE

        if (activeStage == 2 && checklistBuiltForSession != s.id) {
            checklistBuiltForSession = s.id
            val checklist = binding.activationBodyChecklist
            checklist.removeAllViews()
            val items = alarm.bodyActivities.ifEmpty { BodyWakeActivity.DEFAULT_CODES }
            for (code in items) {
                checklist.addView(CheckBox(this).apply {
                    text = bodyActivityLabel(code)
                })
            }
        }

        if (activeStage == 3) {
            if (joyBuiltForSession != s.id) {
                joyBuiltForSession = s.id
                buildJoyPicker(stageMeta.selectedJoy.ifBlank { alarm.joyActivity })
            }
            binding.activationBonusTitle.text = getString(
                R.string.mindstop_morning_s3_bonus_title,
                MorningActivationEngine.BONUS_QUOTA_MINUTES
            )
            binding.activationBonusAfternoonSwitch.isChecked = stageMeta.bonusAfterNoon
            renderBonusButtonState()
        }

        updateCountdown()
    }

    private fun styleStepPill(card: MaterialCardView, label: android.widget.TextView, step: Int, activeStage: Int) {
        val bgAttr: Int
        val fgAttr: Int
        when {
            step == activeStage -> {
                bgAttr = com.google.android.material.R.attr.colorPrimary
                fgAttr = com.google.android.material.R.attr.colorOnPrimary
            }
            step < activeStage -> {
                bgAttr = com.google.android.material.R.attr.colorPrimaryContainer
                fgAttr = com.google.android.material.R.attr.colorOnPrimaryContainer
            }
            else -> {
                bgAttr = com.google.android.material.R.attr.colorSurfaceContainerHighest
                fgAttr = com.google.android.material.R.attr.colorOnSurfaceVariant
            }
        }
        card.setCardBackgroundColor(MaterialColors.getColor(card, bgAttr))
        label.setTextColor(MaterialColors.getColor(label, fgAttr))
    }

    private fun buildJoyPicker(selectedCode: String) {
        val group = binding.activationJoyGroup
        group.setOnCheckedChangeListener(null)
        group.removeAllViews()
        val presets = listOf(
            MorningJoyPreset.MUSIC.code,
            MorningJoyPreset.AUDIOBOOK.code,
            MorningJoyPreset.BREAKFAST.code,
            MorningJoyPreset.SHORT_GAME.code,
            MorningJoyPreset.MARKET.code,
            MorningJoyPreset.GROWTH_GARDEN.code
        )
        var selectedViewId = View.NO_ID
        for (code in presets) {
            val rb = RadioButton(this).apply {
                id = View.generateViewId()
                tag = code
                text = joyLabel(code)
            }
            group.addView(rb)
            if (code == selectedCode) selectedViewId = rb.id
        }
        if (selectedViewId != View.NO_ID) {
            group.check(selectedViewId)
        } else if (group.childCount > 0) {
            group.check(group.getChildAt(0).id)
        }
        group.setOnCheckedChangeListener { rg, checkedId ->
            val picked = rg.findViewById<RadioButton>(checkedId)?.tag as? String ?: return@setOnCheckedChangeListener
            val sid = sessionId.takeIf { it > 0L } ?: return@setOnCheckedChangeListener
            stageMeta = stageMeta.copy(selectedJoy = picked)
            lifecycleScope.launch(Dispatchers.IO) { repo.updateSelectedJoy(sid, picked) }
        }
    }

    private fun renderBonusButtonState() {
        val btn = binding.activationClaimBonusBtn
        val sw = binding.activationBonusAfternoonSwitch
        if (stageMeta.bonusClaimed) {
            btn.isEnabled = false
            sw.isEnabled = false
            btn.setText(
                if (stageMeta.bonusAfterNoon) {
                    R.string.mindstop_morning_s3_bonus_claimed_afternoon
                } else {
                    R.string.mindstop_morning_s3_bonus_claimed_now
                }
            )
        } else {
            btn.isEnabled = true
            sw.isEnabled = true
            btn.text = getString(
                R.string.mindstop_morning_s3_bonus_claim_btn,
                MorningActivationEngine.BONUS_QUOTA_MINUTES
            )
        }
    }

    private fun updateCountdown() {
        val s = session ?: return
        val now = System.currentTimeMillis()
        when {
            s.status.isStage1 -> {
                val windowLeftMs = MorningActivationEngine.stage1WindowRemainingMs(s, now)
                binding.activationS1Countdown.text = if (windowLeftMs > 0L) {
                    getString(R.string.mindstop_morning_s1_countdown, formatMmSs(windowLeftMs))
                } else {
                    getString(R.string.mindstop_morning_s1_timeout_warn)
                }
                val unlockLeftMs = MorningActivationEngine.stage1UnlockRemainingMs(s, now)
                if (unlockLeftMs > 0L) {
                    binding.activationStartStage2Btn.isEnabled = false
                    binding.activationStartStage2Btn.text = getString(
                        R.string.mindstop_morning_s1_locked_btn,
                        formatMmSs(unlockLeftMs)
                    )
                } else {
                    binding.activationStartStage2Btn.isEnabled = !busy
                    binding.activationStartStage2Btn.setText(R.string.mindstop_morning_s1_unlock_btn)
                }
            }
            s.status == MorningState.STAGE2_PREP -> {
                val startWall = stageMeta.stage2StartWall.takeIf { it > 0L } ?: s.updatedWall
                val leftMs = (startWall + MorningActivationEngine.STAGE2_DURATION_MS - now).coerceAtLeast(0L)
                binding.activationS2Countdown.text = if (leftMs > 0L) {
                    getString(R.string.mindstop_morning_s2_countdown, formatMmSs(leftMs))
                } else {
                    getString(R.string.mindstop_morning_s2_ready)
                }
            }
            s.status == MorningState.STAGE3_REWARD -> {
                val startWall = stageMeta.stage3StartWall.takeIf { it > 0L } ?: s.updatedWall
                val stage3Ms = alarm.morningJoyMinutes.coerceIn(5, 30) * 60_000L
                val leftMs = (startWall + stage3Ms - now).coerceAtLeast(0L)
                binding.activationS3Countdown.text = if (leftMs > 0L) {
                    getString(R.string.mindstop_morning_s3_countdown, formatMmSs(leftMs))
                } else {
                    getString(R.string.mindstop_morning_s3_ready)
                }
            }
        }
    }

    private fun startStage2() {
        val sid = sessionId.takeIf { it > 0L } ?: return
        val s = session ?: return
        val now = System.currentTimeMillis()
        if (busy || !MorningActivationEngine.canEnterStage2(s, now)) return
        busy = true
        binding.activationStartStage2Btn.isEnabled = false
        lifecycleScope.launch {
            val updated = withContext(Dispatchers.IO) { repo.enterStage2(sid, now) }
            val meta = withContext(Dispatchers.IO) { repo.loadStageMeta(sid) }
            if (updated != null && updated.status == MorningState.STAGE2_PREP) {
                MindstopAlarmScheduler.cancelActivationCheckins(this@MindstopMorningActivationActivity, sid)
                session = updated
                stageMeta = meta
                Toast.makeText(
                    this@MindstopMorningActivationActivity,
                    R.string.mindstop_morning_s1_entered_s2_toast,
                    Toast.LENGTH_SHORT
                ).show()
            }
            busy = false
            render()
        }
    }

    private fun startStage3() {
        val sid = sessionId.takeIf { it > 0L } ?: return
        if (busy) return
        busy = true
        binding.activationFinishStage2Btn.isEnabled = false
        lifecycleScope.launch {
            val now = System.currentTimeMillis()
            val updated = withContext(Dispatchers.IO) { repo.enterStage3(sid, now) }
            val meta = withContext(Dispatchers.IO) { repo.loadStageMeta(sid) }
            if (updated != null && updated.status == MorningState.STAGE3_REWARD) {
                session = updated
                stageMeta = meta
            }
            busy = false
            binding.activationFinishStage2Btn.isEnabled = true
            render()
        }
    }

    private fun claimOptionalBonus() {
        val sid = sessionId.takeIf { it > 0L } ?: return
        if (busy || stageMeta.bonusClaimed) return
        val afternoonOnly = binding.activationBonusAfternoonSwitch.isChecked
        busy = true
        binding.activationClaimBonusBtn.isEnabled = false
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                repo.claimMorningBonusQuota(
                    sessionId = sid,
                    afternoonOnly = afternoonOnly,
                    bonusMinutes = MorningActivationEngine.BONUS_QUOTA_MINUTES,
                    nowWall = System.currentTimeMillis()
                )
            }
            stageMeta = withContext(Dispatchers.IO) { repo.loadStageMeta(sid) }
            val msgRes = when (result) {
                MorningRepository.BonusClaimResult.SCHEDULED_AFTERNOON -> R.string.mindstop_morning_s3_bonus_claimed_afternoon
                MorningRepository.BonusClaimResult.CREDITED_NOW -> R.string.mindstop_morning_s3_bonus_claimed_now
                MorningRepository.BonusClaimResult.NO_DAILY_RULES -> R.string.mindstop_morning_s3_bonus_no_rules
                MorningRepository.BonusClaimResult.ALREADY_CLAIMED -> R.string.mindstop_morning_s3_bonus_claimed_now
            }
            Toast.makeText(this@MindstopMorningActivationActivity, msgRes, Toast.LENGTH_LONG).show()
            busy = false
            renderBonusButtonState()
        }
    }

    private fun completeActivation() {
        val sid = sessionId.takeIf { it > 0L } ?: return
        if (busy) return
        busy = true
        lifecycleScope.launch {
            val (_, confirmed) = withContext(Dispatchers.IO) {
                repo.completeActivation(sid, System.currentTimeMillis())
            }
            MindstopAlarmScheduler.cancelActivationCheckins(this@MindstopMorningActivationActivity, sid)
            val msg = if (confirmed) {
                getString(R.string.mindstop_morning_act_completed_reward, RewardEngine.MORNING_ENERGY)
            } else {
                getString(R.string.mindstop_morning_act_completed_plain)
            }
            Toast.makeText(this@MindstopMorningActivationActivity, msg, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun reportReturnedToBed() {
        val sid = sessionId.takeIf { it > 0L } ?: return
        if (busy) return
        busy = true
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { repo.reportReturnedToBed(sid, System.currentTimeMillis()) }
            MindstopAlarmScheduler.cancelActivationCheckins(this@MindstopMorningActivationActivity, sid)
            Toast.makeText(this@MindstopMorningActivationActivity, R.string.mindstop_morning_act_returned_toast, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    /** Auxiliary motion hint only (v0.3 §12): recorded passively, never auto-completes wake-up. */
    private fun registerAuxiliaryMotionHint() {
        val sm = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        sensorManager = sm
        val sensor = sm.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR) ?: return
        sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (motionRecorded || sessionId <= 0L) return
        motionRecorded = true
        sensorManager?.unregisterListener(this)
        lifecycleScope.launch(Dispatchers.IO) { repo.markMotionHint(sessionId, System.currentTimeMillis()) }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun formatMmSs(ms: Long): String {
        val totalSec = ((ms + 999L) / 1_000L).coerceAtLeast(0L)
        val mins = (totalSec / 60L).toInt()
        val secs = (totalSec % 60L).toInt()
        return "%02d:%02d".format(mins, secs)
    }

    private fun bodyActivityLabel(code: String): String = when (code) {
        BodyWakeActivity.WARM_CLOTHES.code -> getString(R.string.mindstop_morning_body_clothes)
        BodyWakeActivity.WASH_FACE.code -> getString(R.string.mindstop_morning_body_wash)
        BodyWakeActivity.DRINK_WATER.code -> getString(R.string.mindstop_morning_body_water)
        BodyWakeActivity.TURN_ON_LIGHT.code -> getString(R.string.mindstop_morning_body_light)
        BodyWakeActivity.LIGHT_STRETCH.code -> getString(R.string.mindstop_morning_body_stretch)
        else -> code
    }

    private fun joyLabel(code: String): String = when (code) {
        MorningJoyPreset.MUSIC.code -> getString(R.string.mindstop_morning_joy_music)
        MorningJoyPreset.AUDIOBOOK.code -> getString(R.string.mindstop_morning_joy_audiobook)
        MorningJoyPreset.BREAKFAST.code -> getString(R.string.mindstop_morning_joy_breakfast)
        MorningJoyPreset.SHORT_GAME.code -> getString(R.string.mindstop_morning_joy_game)
        MorningJoyPreset.MARKET.code -> getString(R.string.mindstop_morning_joy_market)
        MorningJoyPreset.GROWTH_GARDEN.code -> getString(R.string.mindstop_morning_joy_garden)
        else -> code.ifBlank { getString(R.string.mindstop_morning_joy_music) }
    }
}
