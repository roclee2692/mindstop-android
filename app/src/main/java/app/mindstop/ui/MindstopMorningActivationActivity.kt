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
import android.text.format.DateFormat
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import app.mindstop.core.BodyWakeActivity
import app.mindstop.core.MorningActivationEngine
import app.mindstop.core.MorningAlarm
import app.mindstop.core.MorningJoyPreset
import app.mindstop.core.MorningRewardChoice
import app.mindstop.core.MorningRuntimeMeta
import app.mindstop.core.MorningSessionRecord
import app.mindstop.core.MorningState
import app.mindstop.core.MorningV2Settings
import app.mindstop.core.RewardEngine
import app.mindstop.core.WakeCheckSubState
import app.mindstop.service.MindstopAlarmReceiver
import app.mindstop.service.MindstopAlarmScheduler
import app.mindstop.service.MindstopAlarmService
import app.mindstop.service.MorningRepository
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import neth.iecal.curbox.R
import neth.iecal.curbox.databinding.ActivityMindstopMorningActivationBinding

/**
 * Strict sequential 3-stage Morning Activation V2 screen.
 *
 * - Stage 1 (STAGE1_WAKE): 15m planned wake window (counting from alarm fire), 5m anti-cheat gate,
 *   overtime counter (+mm:ss), low-weight Extended Sleep entry unlocked ONLY from 2nd ring.
 * - Extended Sleep (EXTENDED_SLEEP): paused rest card with expected next ring time.
 * - Stage 2 (STAGE2_PREP): 30m prep countdown + overtime counter + internal Silent Wake Check
 *   sub-state (WAITING -> PROMPTED -> CONFIRMED, optional 30-step hardware counter assist) +
 *   prep checklist (default + custom items).
 * - Stage 3 (STAGE3_REWARD): strictly mutually exclusive 3-way choice (A Instant / B Delayed / C Skip)
 *   + optional 1..5 wakefulness self-rating.
 * - Always keeps an honest Exit / Emergency Exit dialog (never marks as COMPLETED).
 */
class MindstopMorningActivationActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var binding: ActivityMindstopMorningActivationBinding
    private lateinit var repo: MorningRepository
    private val handler = Handler(Looper.getMainLooper())

    private var sessionId: Long = -1L
    private var alarmId: Long = -1L
    private var session: MorningSessionRecord? = null
    private var meta: MorningRuntimeMeta = MorningRuntimeMeta()
    private var settings: MorningV2Settings = MorningV2Settings()
    private var alarm: MorningAlarm = MorningAlarm()
    private var sensorManager: SensorManager? = null
    private var motionRecorded = false
    private var busy = false
    private var checklistBuiltForSession = -1L
    private var joyBuiltForSession = -1L
    private var ratingBuiltForSession = -1L
    private var tickStepCounter = 0

    private val ticker = object : Runnable {
        override fun run() {
            updateCountdown()
            tickStepCounter++
            if (tickStepCounter % 5 == 0) {
                refreshSessionSilently()
            }
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
        binding.activationExtendSleepBtn.setOnClickListener { openExtendedSleepDialog() }
        binding.activationResumeFromExtendedBtn.setOnClickListener { resumeFromExtendedNow() }
        binding.activationWakecheckConfirmBtn.setOnClickListener { confirmWakeCheckNow() }
        binding.activationFinishStage2Btn.setOnClickListener { finishStage2() }
        binding.activationChooseABtn.setOnClickListener { chooseInstantReward() }
        binding.activationChooseBBtn.setOnClickListener { chooseDelayedReward() }
        binding.activationChooseCBtn.setOnClickListener { chooseSkipReward() }
        binding.activationCompleteBtn.setOnClickListener { completeInstantReward() }
        binding.activationBackToBedBtn.setOnClickListener { showExitOptionsDialog() }

        loadSessionFromIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        busy = false
        motionRecorded = false
        loadSessionFromIntent(intent)
    }

    private var loadJob: Job? = null

    private fun loadSessionFromIntent(source: Intent) {
        loadJob?.cancel()
        refreshJob?.cancel()
        nextStepReadElapsed = 0L
        sessionId = source.getLongExtra(MindstopAlarmReceiver.EXTRA_SESSION_ID, -1L)
        alarmId = source.getLongExtra(MindstopAlarmReceiver.EXTRA_ALARM_ID, -1L)
        loadJob = lifecycleScope.launch {
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
            settings = withContext(Dispatchers.IO) { repo.loadSettings() }
            meta = withContext(Dispatchers.IO) { repo.loadRuntimeMeta(loadedSession.id) }
            render()
            if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) registerAuxiliaryMotionHint()
        }
    }

    private var refreshJob: Job? = null
    private var nextStepReadElapsed = 0L

    private fun refreshSessionSilently() {
        val sid = sessionId.takeIf { it > 0L } ?: return
        if (busy || refreshJob?.isActive == true) return
        refreshJob = lifecycleScope.launch {
            val pair = withContext(Dispatchers.IO) {
                val s = repo.getSession(sid)
                val m = repo.loadRuntimeMeta(sid)
                s to m
            }
            if (busy || sid != sessionId) return@launch
            val latest = pair.first
            if (latest == null || latest.status.isTerminal) {
                finish()
                return@launch
            }
            if (latest.status == MorningState.STAGE2_PREP &&
                settings.stepProofEnabled && pair.second.stepBaseline >= 0f &&
                pair.second.wakeCheckState != WakeCheckSubState.CONFIRMED &&
                android.os.SystemClock.elapsedRealtime() >= nextStepReadElapsed
            ) {
                nextStepReadElapsed = android.os.SystemClock.elapsedRealtime() + 30_000L
                val auto = withContext(Dispatchers.IO) { repo.tryAutoConfirmWakeCheckBySteps(sid, pair.second.wakeCheckToken) }
                if (auto != null && auto.changed) {
                    session = auto.session
                    meta = auto.meta
                    render()
                    return@launch
                }
            }
            val changed = latest.status != session?.status ||
                pair.second.wakeCheckState != meta.wakeCheckState ||
                pair.second.ringCount != meta.ringCount ||
                pair.second.rewardChoice != meta.rewardChoice
            session = latest
            meta = pair.second
            if (changed) render()
        }
    }

    override fun onResume() {
        super.onResume()
        if (session != null) registerAuxiliaryMotionHint()
        handler.post(ticker)
        ContextCompat.registerReceiver(
            this,
            alarmRingReceiver,
            IntentFilter(MindstopAlarmService.ACTION_ALARM_RINGING),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        if (MindstopAlarmService.isRinging()) {
            openActiveRingScreen(MindstopAlarmService.activeAlarmId, MindstopAlarmService.activeSessionId)
        } else {
            refreshSessionSilently()
        }
    }

    override fun onPause() {
        try {
            unregisterReceiver(alarmRingReceiver)
        } catch (_: Exception) {
        }
        refreshJob?.cancel()
        sensorManager?.unregisterListener(this)
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
            s.status == MorningState.EXTENDED_SLEEP -> 0
            s.status == MorningState.STAGE2_PREP -> 2
            s.status == MorningState.STAGE3_REWARD -> 3
            else -> 1
        }

        styleStepPill(binding.activationStep1Pill, binding.activationStep1Text, R.string.mindstop_morning_step1_short, step = 1, activeStage = activeStage)
        styleStepPill(binding.activationStep2Pill, binding.activationStep2Text, R.string.mindstop_morning_step2_short, step = 2, activeStage = activeStage)
        styleStepPill(binding.activationStep3Pill, binding.activationStep3Text, R.string.mindstop_morning_step3_short, step = 3, activeStage = activeStage)

        binding.activationExtendedCard.visibility = if (activeStage == 0) View.VISIBLE else View.GONE
        binding.activationStage1Card.visibility = if (activeStage == 1) View.VISIBLE else View.GONE
        binding.activationStage2Card.visibility = if (activeStage == 2) View.VISIBLE else View.GONE
        binding.activationStage3Card.visibility = if (activeStage == 3) View.VISIBLE else View.GONE
        binding.activationBackToBedBtn.visibility = View.VISIBLE

        if (activeStage == 1) {
            binding.activationS1Badge.text = getString(
                R.string.mindstop_morning_s1_badge_ring_count,
                settings.stage1WindowMin,
                meta.ringCount
            )
            binding.activationExtendSleepBtn.visibility = if (meta.canOfferExtendedSleep) View.VISIBLE else View.GONE
        }

        if (activeStage == 2) {
            if (checklistBuiltForSession != s.id) {
                checklistBuiltForSession = s.id
                buildStage2Checklist()
            }
            renderWakeCheckCard()
        }

        if (activeStage == 3) {
            if (ratingBuiltForSession != s.id) {
                ratingBuiltForSession = s.id
                buildWakefulnessRatingRow()
            }
            if (joyBuiltForSession != s.id) {
                joyBuiltForSession = s.id
                buildJoyPicker(meta.selectedJoy.ifBlank { alarm.joyActivity })
            }
            val instantRunning = meta.rewardChoice == MorningRewardChoice.INSTANT
            binding.activationS3PickerContainer.visibility = if (instantRunning) View.GONE else View.VISIBLE
            binding.activationS3InstantRunning.visibility = if (instantRunning) View.VISIBLE else View.GONE

            binding.activationChoiceATitle.text = getString(R.string.mindstop_morning_choice_a_title, settings.stage3InstantMin)
            binding.activationChooseABtn.text = getString(R.string.mindstop_morning_choice_a_btn, settings.stage3InstantMin)

            val unlockTime = "%02d:%02d".format(settings.morningRestrictEndMinute / 60, settings.morningRestrictEndMinute % 60)
            binding.activationChoiceBTitle.text = getString(R.string.mindstop_morning_choice_b_title, settings.delayedBonusMinutes)
            binding.activationChoiceBDesc.text = getString(R.string.mindstop_morning_choice_b_desc, settings.delayedBonusMinutes, unlockTime)
            binding.activationChooseBBtn.text = getString(R.string.mindstop_morning_choice_b_btn, settings.delayedBonusMinutes)

            if (instantRunning) {
                binding.activationS3ActiveJoyLabel.text = getString(
                    R.string.mindstop_morning_s3_running_joy_desc,
                    joyLabel(meta.selectedJoy.ifBlank { alarm.joyActivity })
                )
            }
        }

        updateCountdown()
    }

    private fun buildStage2Checklist() {
        val checklist = binding.activationBodyChecklist
        checklist.removeAllViews()
        val items = alarm.bodyActivities.ifEmpty { BodyWakeActivity.DEFAULT_CODES }
        for (code in items) {
            checklist.addView(CheckBox(this).apply { text = bodyActivityLabel(code) })
        }
        val customItems = settings.customPrepItems.split(',', '，', '\n').map { it.trim() }.filter { it.isNotBlank() }
        for (item in customItems) {
            checklist.addView(CheckBox(this).apply { text = item })
        }
    }

    private fun renderWakeCheckCard() {
        val now = System.currentTimeMillis()
        when (meta.wakeCheckState) {
            WakeCheckSubState.CONFIRMED -> {
                binding.activationWakecheckStatus.setText(R.string.mindstop_morning_wakecheck_done_title)
                binding.activationWakecheckDetail.setText(R.string.mindstop_morning_wakecheck_done_desc)
                binding.activationWakecheckConfirmBtn.visibility = View.GONE
            }
            WakeCheckSubState.PROMPTED -> {
                val leftMs = (meta.wakeCheckDeadlineWall - now).coerceAtLeast(0L)
                binding.activationWakecheckStatus.text = getString(
                    R.string.mindstop_morning_wakecheck_prompt_title,
                    formatMmSs(leftMs)
                )
                binding.activationWakecheckDetail.text = if (settings.stepProofEnabled) {
                    getString(R.string.mindstop_morning_wakecheck_prompt_steps_desc, settings.stepProofThreshold)
                } else {
                    getString(R.string.mindstop_morning_wakecheck_prompt_desc)
                }
                binding.activationWakecheckConfirmBtn.visibility = View.VISIBLE
            }
            else -> {
                val waitLeft = (meta.wakeCheckPromptWall - now).coerceAtLeast(0L)
                binding.activationWakecheckStatus.text = getString(
                    R.string.mindstop_morning_wakecheck_wait_title,
                    formatMmSs(waitLeft)
                )
                binding.activationWakecheckDetail.text = if (settings.stepProofEnabled) {
                    getString(R.string.mindstop_morning_wakecheck_wait_steps_desc, settings.stepProofThreshold)
                } else {
                    getString(R.string.mindstop_morning_wakecheck_wait_desc)
                }
                binding.activationWakecheckConfirmBtn.visibility = View.VISIBLE
            }
        }
    }

    private fun buildWakefulnessRatingRow() {
        val row = binding.activationRatingRow
        row.removeAllViews()
        val dp4 = (4 * resources.displayMetrics.density).toInt()
        for (score in 1..5) {
            val btn = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = score.toString()
                val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                lp.marginEnd = dp4
                layoutParams = lp
                isSelected = meta.wakefulnessRating == score
                alpha = if (meta.wakefulnessRating == score) 1f else 0.72f
                setOnClickListener {
                    val sid = sessionId.takeIf { it > 0L } ?: return@setOnClickListener
                    meta = meta.copy(wakefulnessRating = score)
                    lifecycleScope.launch(Dispatchers.IO) {
                        repo.saveWakefulnessRating(sid, score, System.currentTimeMillis())
                    }
                    buildWakefulnessRatingRow()
                }
            }
            row.addView(btn)
        }
    }

    private fun styleStepPill(
        card: MaterialCardView,
        label: android.widget.TextView,
        baseTextRes: Int,
        step: Int,
        activeStage: Int
    ) {
        val bgAttr: Int
        val fgAttr: Int
        val statusSuffix: String
        when {
            activeStage > 0 && step == activeStage -> {
                bgAttr = com.google.android.material.R.attr.colorPrimary
                fgAttr = com.google.android.material.R.attr.colorOnPrimary
                statusSuffix = getString(R.string.mindstop_morning_step_status_active)
            }
            activeStage > 0 && step < activeStage -> {
                bgAttr = com.google.android.material.R.attr.colorPrimaryContainer
                fgAttr = com.google.android.material.R.attr.colorOnPrimaryContainer
                statusSuffix = getString(R.string.mindstop_morning_step_status_done)
            }
            else -> {
                bgAttr = com.google.android.material.R.attr.colorSurfaceContainerHighest
                fgAttr = com.google.android.material.R.attr.colorOnSurfaceVariant
                statusSuffix = getString(R.string.mindstop_morning_step_status_pending)
            }
        }
        card.setCardBackgroundColor(MaterialColors.getColor(card, bgAttr))
        label.setTextColor(MaterialColors.getColor(label, fgAttr))
        label.text = "${getString(baseTextRes)}\n$statusSuffix"
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
            meta = meta.copy(selectedJoy = picked)
            lifecycleScope.launch(Dispatchers.IO) { repo.updateSelectedJoy(sid, picked) }
        }
    }

    private fun updateCountdown() {
        val s = session ?: return
        val now = System.currentTimeMillis()
        when {
            s.status == MorningState.EXTENDED_SLEEP -> {
                val leftMs = (meta.extendedSleepResumeWall - now).coerceAtLeast(0L)
                val targetTime = DateFormat.getTimeFormat(this).format(Date(meta.extendedSleepResumeWall))
                binding.activationExtendedCountdown.text = getString(
                    R.string.mindstop_morning_extended_countdown,
                    targetTime,
                    formatMmSs(leftMs)
                )
            }
            s.status.isStage1 -> {
                val windowLeftMs = MorningActivationEngine.stage1WindowRemainingMs(s, now, settings.stage1WindowMs, meta)
                val overtimeMs = MorningActivationEngine.stage1OvertimeMs(s, now, settings.stage1WindowMs, meta)
                binding.activationS1Countdown.text = if (overtimeMs > 0L) {
                    getString(R.string.mindstop_morning_s1_overtime, formatMmSs(overtimeMs))
                } else {
                    getString(R.string.mindstop_morning_s1_countdown, formatMmSs(windowLeftMs))
                }
                val unlockLeftMs = MorningActivationEngine.stage1UnlockRemainingMs(s, now, settings.stage1UnlockMs, meta)
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
                val leftMs = MorningActivationEngine.stage2RemainingMs(s, meta, settings, now)
                val overMs = MorningActivationEngine.stage2OvertimeMs(s, meta, settings, now)
                binding.activationS2Countdown.text = if (overMs > 0L) {
                    getString(R.string.mindstop_morning_s2_overtime, formatMmSs(overMs))
                } else {
                    getString(R.string.mindstop_morning_s2_countdown, formatMmSs(leftMs))
                }
                renderWakeCheckCard()

                val minPrepLeft = MorningActivationEngine.stage2MinPrepRemainingMs(s, meta, settings, now)
                when {
                    meta.wakeCheckState != WakeCheckSubState.CONFIRMED -> {
                        binding.activationFinishStage2Btn.isEnabled = false
                        binding.activationFinishStage2Btn.setText(R.string.mindstop_morning_s2_need_wakecheck_btn)
                    }
                    minPrepLeft > 0L -> {
                        binding.activationFinishStage2Btn.isEnabled = false
                        binding.activationFinishStage2Btn.text = getString(
                            R.string.mindstop_morning_s2_min_wait_btn,
                            formatMmSs(minPrepLeft)
                        )
                    }
                    else -> {
                        binding.activationFinishStage2Btn.isEnabled = !busy
                        binding.activationFinishStage2Btn.setText(
                            if (settings.stage3Enabled) R.string.mindstop_morning_s2_finish_btn
                            else R.string.mindstop_morning_s3_complete_btn
                        )
                    }
                }
            }
            s.status == MorningState.STAGE3_REWARD && meta.rewardChoice == MorningRewardChoice.INSTANT -> {
                val leftMs = (meta.instantRewardEndWall - now).coerceAtLeast(0L)
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
        if (busy || !MorningActivationEngine.canEnterStage2(s, now, settings.stage1UnlockMs, meta)) return
        busy = true
        binding.activationStartStage2Btn.isEnabled = false
        lifecycleScope.launch {
            val snap = withContext(Dispatchers.IO) { repo.enterStage2(sid, now) }
            if (snap != null && snap.changed) {
                session = snap.session
                meta = snap.meta
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

    private fun openExtendedSleepDialog() {
        val s = session ?: return
        if (busy || !meta.canOfferExtendedSleep) return
        MorningExtendedSleepDialog.show(this, s, meta, settings) { minutes ->
            if (busy) return@show
            busy = true
            lifecycleScope.launch {
                val now = System.currentTimeMillis()
                val snap = withContext(Dispatchers.IO) { repo.enterExtendedSleep(s.id, minutes, now) }
                if (snap != null && snap.changed) {
                    session = snap.session
                    meta = snap.meta
                    val timeStr = DateFormat.getTimeFormat(this@MindstopMorningActivationActivity)
                        .format(Date(snap.meta.extendedSleepResumeWall))
                    Toast.makeText(
                        this@MindstopMorningActivationActivity,
                        getString(R.string.mindstop_morning_extend_confirmed_toast, timeStr),
                        Toast.LENGTH_LONG
                    ).show()
                }
                busy = false
                render()
            }
        }
    }

    private fun resumeFromExtendedNow() {
        val sid = sessionId.takeIf { it > 0L } ?: return
        if (busy) return
        busy = true
        lifecycleScope.launch {
            val now = System.currentTimeMillis()
            val snap = withContext(Dispatchers.IO) { repo.resumeFromExtendedSleep(sid, meta.stageToken, now) }
            if (snap != null && snap.changed) {
                session = snap.session
                meta = snap.meta
            }
            busy = false
            render()
        }
    }

    private fun confirmWakeCheckNow() {
        val sid = sessionId.takeIf { it > 0L } ?: return
        if (busy) return
        busy = true
        lifecycleScope.launch {
            val snap = withContext(Dispatchers.IO) {
                repo.confirmSilentWakeCheck(sid, meta.wakeCheckToken, System.currentTimeMillis())
            }
            if (snap != null && snap.changed) {
                session = snap.session
                meta = snap.meta
                Toast.makeText(
                    this@MindstopMorningActivationActivity,
                    R.string.mindstop_morning_wakecheck_confirmed_toast,
                    Toast.LENGTH_SHORT
                ).show()
            }
            busy = false
            render()
        }
    }

    private fun finishStage2() {
        val sid = sessionId.takeIf { it > 0L } ?: return
        if (busy) return
        busy = true
        binding.activationFinishStage2Btn.isEnabled = false
        lifecycleScope.launch {
            val now = System.currentTimeMillis()
            val (snap, confirmedReward) = withContext(Dispatchers.IO) { repo.enterStage3(sid, now) }
            if (snap != null && snap.changed) {
                session = snap.session
                meta = snap.meta
                if (snap.session.status == MorningState.COMPLETED) {
                    MindstopAlarmScheduler.cancelAllStageTimers(this@MindstopMorningActivationActivity, sid)
                    showCompletedToastAndFinish(confirmedReward)
                    return@launch
                }
            }
            busy = false
            render()
        }
    }

    private fun chooseInstantReward() {
        val sid = sessionId.takeIf { it > 0L } ?: return
        if (busy) return
        busy = true
        lifecycleScope.launch {
            val now = System.currentTimeMillis()
            val snap = withContext(Dispatchers.IO) { repo.chooseInstantReward(sid, now) }
            if (snap != null && snap.changed) {
                session = snap.session
                meta = snap.meta
            }
            busy = false
            render()
        }
    }

    private fun chooseDelayedReward() {
        val sid = sessionId.takeIf { it > 0L } ?: return
        if (busy) return
        busy = true
        lifecycleScope.launch {
            val now = System.currentTimeMillis()
            val (snap, confirmed, bonusResult) = withContext(Dispatchers.IO) {
                repo.chooseDelayedRewardAndComplete(sid, now)
            }
            if (snap != null && snap.changed) {
                MindstopAlarmScheduler.cancelAllStageTimers(this@MindstopMorningActivationActivity, sid)
                val bonusMsgRes = when (bonusResult) {
                    MorningRepository.BonusClaimResult.SCHEDULED_AFTERNOON -> R.string.mindstop_morning_s3_bonus_claimed_afternoon
                    MorningRepository.BonusClaimResult.CREDITED_NOW -> R.string.mindstop_morning_s3_bonus_claimed_now
                    MorningRepository.BonusClaimResult.NO_DAILY_RULES -> R.string.mindstop_morning_s3_bonus_no_rules
                    MorningRepository.BonusClaimResult.ALREADY_CLAIMED -> R.string.mindstop_morning_bonus_already_today
                }
                val baseMsg = if (confirmed) {
                    getString(R.string.mindstop_morning_act_completed_reward, RewardEngine.MORNING_ENERGY)
                } else {
                    getString(R.string.mindstop_morning_act_completed_plain)
                }
                Toast.makeText(
                    this@MindstopMorningActivationActivity,
                    "$baseMsg\n${getString(bonusMsgRes)}",
                    Toast.LENGTH_LONG
                ).show()
                finish()
                return@launch
            }
            busy = false
            render()
        }
    }

    private fun chooseSkipReward() {
        val sid = sessionId.takeIf { it > 0L } ?: return
        if (busy) return
        busy = true
        lifecycleScope.launch {
            val (snap, confirmed) = withContext(Dispatchers.IO) {
                repo.chooseSkipRewardAndComplete(sid, System.currentTimeMillis())
            }
            if (snap != null && snap.changed) {
                MindstopAlarmScheduler.cancelAllStageTimers(this@MindstopMorningActivationActivity, sid)
                showCompletedToastAndFinish(confirmed)
                return@launch
            }
            busy = false
            render()
        }
    }

    private fun completeInstantReward() {
        val sid = sessionId.takeIf { it > 0L } ?: return
        if (busy) return
        busy = true
        lifecycleScope.launch {
            val (completed, confirmed) = withContext(Dispatchers.IO) {
                repo.completeActivation(sid, System.currentTimeMillis())
            }
            if (completed == null) {
                busy = false
                refreshSessionSilently()
                return@launch
            }
            MindstopAlarmScheduler.cancelAllStageTimers(this@MindstopMorningActivationActivity, sid)
            showCompletedToastAndFinish(confirmed)
        }
    }

    private fun showCompletedToastAndFinish(confirmedReward: Boolean) {
        val msg = if (confirmedReward) {
            getString(R.string.mindstop_morning_act_completed_reward, RewardEngine.MORNING_ENERGY)
        } else {
            getString(R.string.mindstop_morning_act_completed_plain)
        }
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        finish()
    }

    private fun showExitOptionsDialog() {
        val sid = sessionId.takeIf { it > 0L } ?: return
        if (busy) return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.mindstop_morning_exit_dialog_title)
            .setMessage(R.string.mindstop_morning_exit_dialog_msg)
            .setPositiveButton(R.string.mindstop_morning_exit_emergency_btn) { _, _ ->
                executeExit(sid, returnedToBed = false)
            }
            .setNeutralButton(R.string.mindstop_morning_exit_back_bed_btn) { _, _ ->
                executeExit(sid, returnedToBed = true)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun executeExit(sid: Long, returnedToBed: Boolean) {
        busy = true
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                repo.reportExitNotCompleted(sid, returnedToBed = returnedToBed, nowWall = System.currentTimeMillis())
            }
            MindstopAlarmScheduler.cancelAllStageTimers(this@MindstopMorningActivationActivity, sid)
            Toast.makeText(
                this@MindstopMorningActivationActivity,
                if (returnedToBed) R.string.mindstop_morning_act_returned_toast else R.string.mindstop_morning_emergency_exit_toast,
                Toast.LENGTH_LONG
            ).show()
            finish()
        }
    }

    /** Auxiliary motion hint only: recorded passively, never auto-completes wake-up. */
    private fun registerAuxiliaryMotionHint() {
        if (motionRecorded) return
        val sm = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        sensorManager = sm
        val sensor = sm.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR) ?: return
        try {
            sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        } catch (_: SecurityException) {
            // Motion hints are optional when activity recognition permission is unavailable.
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (motionRecorded || sessionId <= 0L) return
        val sid = sessionId
        motionRecorded = true
        sensorManager?.unregisterListener(this)
        lifecycleScope.launch(Dispatchers.IO) { repo.markMotionHint(sid, System.currentTimeMillis()) }
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
