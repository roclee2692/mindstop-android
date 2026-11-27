package app.mindstop.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.text.InputType
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.mindstop.core.MorningAlarm
import app.mindstop.core.MorningJoyPreset
import app.mindstop.core.MorningState
import app.mindstop.core.MorningStatsSummary
import app.mindstop.core.MorningV2Settings
import app.mindstop.core.WakeupConfirmMethod
import app.mindstop.service.MindstopAlarmReceiver
import app.mindstop.service.MindstopAlarmScheduler
import app.mindstop.service.MindstopAlarmService
import app.mindstop.service.MorningRepository
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import neth.iecal.curbox.R
import neth.iecal.curbox.databinding.ActivityMindstopMorningBinding
import java.util.Date

/**
 * Morning Activation & Alarm Manager hub (Morning Activation V2).
 * Includes permission diagnostics, configurable V2 stage/wake-check/reward parameters,
 * local 7/14/30-day behavioral trend stats, alarm scheduling, and bedtime-morning records.
 */
class MindstopMorningActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMindstopMorningBinding
    private lateinit var repo: MorningRepository
    private var activeSessionId: Long? = null
    private var statsDaysWindow = 7

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMindstopMorningBinding.inflate(layoutInflater)
        setContentView(binding.root)
        MindstopUi.applySystemBarInsets(this, binding.morningRoot)
        MindstopUi.setupToolbar(this, binding.morningToolbar, R.string.mindstop_morning_title)

        repo = MorningRepository(this)

        binding.morningExactFixBtn.setOnClickListener {
            if (!MindstopAlarmScheduler.canScheduleExact(this)) {
                OemPermissionRouter.openExactAlarm(this)
            } else {
                startActivity(Intent(this, MindstopDiagnosticsActivity::class.java))
            }
        }
        binding.morningActiveCard.setOnClickListener {
            val sid = activeSessionId ?: return@setOnClickListener
            startActivity(
                Intent(this, MindstopMorningActivationActivity::class.java)
                    .putExtra(MindstopAlarmReceiver.EXTRA_SESSION_ID, sid)
            )
        }
        binding.morningAddAlarm.setOnClickListener { openAlarmEditor(null) }
        binding.morningSyncSleepPlan.setOnClickListener { syncFromSleepPlan() }
        binding.morningV2SettingsBtn.setOnClickListener { openV2SettingsDialog() }

        binding.morningStats7d.setOnClickListener { selectStatsWindow(7) }
        binding.morningStats14d.setOnClickListener { selectStatsWindow(14) }
        binding.morningStats30d.setOnClickListener { selectStatsWindow(30) }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                repo.observeAlarms().collectLatest { alarms ->
                    renderAlarms(alarms)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (MindstopAlarmService.isRinging()) {
            startActivity(
                Intent(this, MindstopAlarmRingActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(MindstopAlarmReceiver.EXTRA_ALARM_ID, MindstopAlarmService.activeAlarmId)
                    .putExtra(MindstopAlarmReceiver.EXTRA_SESSION_ID, MindstopAlarmService.activeSessionId)
            )
            return
        }
        updatePermissionHealthBanner()
        refreshActiveStatsAndLinks()
    }

    private fun updatePermissionHealthBanner() {
        val issues = ArrayList<String>(3)
        if (!MindstopAlarmScheduler.canScheduleExact(this)) {
            issues.add(getString(R.string.mindstop_morning_health_exact))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            issues.add(getString(R.string.mindstop_morning_health_notif))
        }
        val pm = getSystemService(PowerManager::class.java)
        if (pm != null && !pm.isIgnoringBatteryOptimizations(packageName)) {
            issues.add(getString(R.string.mindstop_morning_health_battery))
        }
        if (issues.isEmpty()) {
            binding.morningExactWarnCard.visibility = View.GONE
        } else {
            binding.morningExactWarnCard.visibility = View.VISIBLE
            binding.morningHealthWarnText.text = getString(
                R.string.mindstop_morning_health_summary,
                issues.joinToString(" · ")
            )
        }
    }

    private fun selectStatsWindow(days: Int) {
        statsDaysWindow = days
        refreshStatsOnly()
    }

    private fun refreshStatsOnly() {
        binding.morningStats7d.alpha = if (statsDaysWindow == 7) 1f else 0.65f
        binding.morningStats14d.alpha = if (statsDaysWindow == 14) 1f else 0.65f
        binding.morningStats30d.alpha = if (statsDaysWindow == 30) 1f else 0.65f
        lifecycleScope.launch {
            val summary = withContext(Dispatchers.IO) {
                repo.loadStatsSummary(statsDaysWindow, System.currentTimeMillis())
            }
            renderStats(summary)
        }
    }

    private fun refreshActiveStatsAndLinks() {
        binding.morningStats7d.alpha = if (statsDaysWindow == 7) 1f else 0.65f
        binding.morningStats14d.alpha = if (statsDaysWindow == 14) 1f else 0.65f
        binding.morningStats30d.alpha = if (statsDaysWindow == 30) 1f else 0.65f
        lifecycleScope.launch {
            val now = System.currentTimeMillis()
            val active = withContext(Dispatchers.IO) { repo.activeSession(now) }
            activeSessionId = active?.id
            val showResume = active != null && active.status.isInActivation
            binding.morningActiveCard.visibility = if (showResume) View.VISIBLE else View.GONE
            if (showResume && active != null) {
                binding.morningActiveBannerText.text = getString(
                    R.string.mindstop_morning_resume_stage,
                    stateLabel(active.status)
                )
            }
            val summary = withContext(Dispatchers.IO) { repo.loadStatsSummary(statsDaysWindow, now) }
            renderStats(summary)
            val links = withContext(Dispatchers.IO) { repo.loadRecentLinks(7) }
            renderLinks(links)
        }
    }

    private fun renderStats(s: MorningStatsSummary) {
        if (s.totalSessions == 0) {
            binding.morningStatsBody.setText(R.string.mindstop_morning_stats_empty)
            return
        }
        val ratingStr = if (s.avgWakefulnessRating > 0f) "%.1f / 5".format(s.avgWakefulnessRating) else getString(R.string.mindstop_morning_stats_unrated)
        binding.morningStatsBody.text = getString(
            R.string.mindstop_morning_stats_detail,
            s.daysWindow,
            s.totalSessions,
            s.completionRatePercent,
            s.avgWakeConfirmMin,
            s.totalExtendedCount,
            s.totalExtendedMinutes,
            s.totalWakeCheckTimeouts,
            s.avgPrepMin,
            s.avgPostWakeDistractionMin,
            ratingStr
        )
    }

    private fun openV2SettingsDialog() {
        lifecycleScope.launch {
            val current = withContext(Dispatchers.IO) { repo.loadSettings() }
            val scroll = ScrollView(this@MindstopMorningActivity)
            val form = LinearLayout(this@MindstopMorningActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(12), dp(20), dp(8))
            }

            fun numField(hintRes: Int, value: Int): EditText = EditText(this@MindstopMorningActivity).apply {
                setHint(hintRes)
                inputType = InputType.TYPE_CLASS_NUMBER
                setText(value.toString())
            }

            val s1Window = numField(R.string.mindstop_morning_cfg_s1_window, current.stage1WindowMin)
            val s1Unlock = numField(R.string.mindstop_morning_cfg_s1_unlock, current.stage1UnlockMin)
            val s1Repeat = numField(R.string.mindstop_morning_cfg_s1_repeat, current.stage1RepeatIntervalMin)
            val latestWake = EditText(this@MindstopMorningActivity).apply {
                setHint(R.string.mindstop_morning_cfg_latest_wake)
                setText(
                    if (current.latestWakeMinuteOfDay in 0..1439) {
                        "%02d:%02d".format(current.latestWakeMinuteOfDay / 60, current.latestWakeMinuteOfDay % 60)
                    } else ""
                )
            }
            val s2Duration = numField(R.string.mindstop_morning_cfg_s2_duration, current.stage2DurationMin)
            val s2MinPrep = numField(R.string.mindstop_morning_cfg_s2_min, current.stage2MinPrepMin)
            val wcDelay = numField(R.string.mindstop_morning_cfg_wc_delay, current.wakeCheckDelayMin)
            val wcWindow = numField(R.string.mindstop_morning_cfg_wc_window, current.wakeCheckWindowMin)
            val stepSwitch = MaterialSwitch(this@MindstopMorningActivity).apply {
                setText(R.string.mindstop_morning_cfg_step_switch)
                isChecked = current.stepProofEnabled
            }
            val stepThreshold = numField(R.string.mindstop_morning_cfg_step_count, current.stepProofThreshold)
            val s3Switch = MaterialSwitch(this@MindstopMorningActivity).apply {
                setText(R.string.mindstop_morning_cfg_s3_switch)
                isChecked = current.stage3Enabled
            }
            val s3Instant = numField(R.string.mindstop_morning_cfg_s3_instant, current.stage3InstantMin)
            val delayedUnlock = EditText(this@MindstopMorningActivity).apply {
                setHint(R.string.mindstop_morning_cfg_delayed_unlock)
                setText("%02d:%02d".format(current.morningRestrictEndMinute / 60, current.morningRestrictEndMinute % 60))
            }
            val customPrep = EditText(this@MindstopMorningActivity).apply {
                setHint(R.string.mindstop_morning_cfg_custom_prep)
                setText(current.customPrepItems)
            }

            listOf(
                s1Window, s1Unlock, s1Repeat, latestWake,
                s2Duration, s2MinPrep, wcDelay, wcWindow,
                stepSwitch, stepThreshold, s3Switch, s3Instant,
                delayedUnlock, customPrep
            ).forEach { form.addView(it) }
            scroll.addView(form)

            MaterialAlertDialogBuilder(this@MindstopMorningActivity)
                .setTitle(R.string.mindstop_morning_v2_settings_btn)
                .setView(scroll)
                .setPositiveButton(R.string.mindstop_rule_save) { _, _ ->
                    val latestMin = parseHhMm(latestWake.text?.toString()) ?: -1
                    val unlockMin = parseHhMm(delayedUnlock.text?.toString()) ?: current.morningRestrictEndMinute
                    val updated = MorningV2Settings(
                        stage1WindowMin = s1Window.text?.toString()?.toIntOrNull()?.coerceIn(5, 60) ?: current.stage1WindowMin,
                        stage1UnlockMin = s1Unlock.text?.toString()?.toIntOrNull()?.coerceIn(1, 30) ?: current.stage1UnlockMin,
                        stage1RepeatIntervalMin = s1Repeat.text?.toString()?.toIntOrNull()?.coerceIn(2, 30) ?: current.stage1RepeatIntervalMin,
                        stage2DurationMin = s2Duration.text?.toString()?.toIntOrNull()?.coerceIn(5, 90) ?: current.stage2DurationMin,
                        stage2MinPrepMin = s2MinPrep.text?.toString()?.toIntOrNull()?.coerceIn(0, 60) ?: current.stage2MinPrepMin,
                        wakeCheckDelayMin = wcDelay.text?.toString()?.toIntOrNull()?.coerceIn(1, 30) ?: current.wakeCheckDelayMin,
                        wakeCheckWindowMin = wcWindow.text?.toString()?.toIntOrNull()?.coerceIn(1, 20) ?: current.wakeCheckWindowMin,
                        stepProofEnabled = stepSwitch.isChecked,
                        stepProofThreshold = stepThreshold.text?.toString()?.toIntOrNull()?.coerceIn(10, 200) ?: current.stepProofThreshold,
                        stage3Enabled = s3Switch.isChecked,
                        stage3InstantMin = s3Instant.text?.toString()?.toIntOrNull()?.coerceIn(1, 30) ?: current.stage3InstantMin,
                        delayedBonusMinutes = current.delayedBonusMinutes,
                        morningRestrictEndMinute = unlockMin,
                        latestWakeMinuteOfDay = latestMin,
                        customPrepItems = customPrep.text?.toString()?.trim().orEmpty()
                    )
                    lifecycleScope.launch {
                        withContext(Dispatchers.IO) { repo.saveSettings(updated, System.currentTimeMillis()) }
                        Toast.makeText(this@MindstopMorningActivity, R.string.mindstop_cs_saved, Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun parseHhMm(raw: String?): Int? {
        val cleaned = raw?.trim().orEmpty().replace('：', ':')
        if (cleaned.isBlank()) return null
        val parts = cleaned.split(':')
        val h = parts.getOrNull(0)?.toIntOrNull() ?: return null
        val m = parts.getOrNull(1)?.toIntOrNull() ?: return null
        if (parts.size != 2 || h !in 0..23 || m !in 0..59) return null
        return h * 60 + m
    }

    private fun syncFromSleepPlan() {
        lifecycleScope.launch {
            val synced = withContext(Dispatchers.IO) {
                val list = repo.syncAlarmsFromSleepPlan(
                    workdayLabel = getString(R.string.mindstop_morning_workday_label),
                    weekendLabel = getString(R.string.mindstop_morning_weekend_label),
                    nowWall = System.currentTimeMillis()
                )
                for (a in list) MindstopAlarmScheduler.schedule(this@MindstopMorningActivity, a)
                list
            }
            Toast.makeText(
                this@MindstopMorningActivity,
                getString(R.string.mindstop_morning_synced_toast, synced.size),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun renderAlarms(alarms: List<MorningAlarm>) {
        val container = binding.morningAlarmsContainer
        container.removeAllViews()
        val onSurface = MaterialColors.getColor(container, com.google.android.material.R.attr.colorOnSurface)
        val onVariant = MaterialColors.getColor(container, com.google.android.material.R.attr.colorOnSurfaceVariant)
        if (alarms.isEmpty()) {
            container.addView(TextView(this).apply {
                setText(R.string.mindstop_morning_alarms_empty)
                setTextColor(onVariant)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            })
            return
        }
        for (alarm in alarms) {
            val card = buildCard()
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(14), dp(16), dp(12))
            }
            val top = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val timeView = TextView(this).apply {
                text = "%02d:%02d".format(alarm.hour, alarm.minute)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(onSurface)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val toggle = MaterialSwitch(this).apply {
                isChecked = alarm.enabled
                setOnCheckedChangeListener { _, checked ->
                    lifecycleScope.launch(Dispatchers.IO) {
                        val updated = repo.saveAlarm(alarm.copy(enabled = checked), System.currentTimeMillis())
                        MindstopAlarmScheduler.schedule(this@MindstopMorningActivity, updated)
                    }
                }
            }
            top.addView(timeView)
            top.addView(toggle)
            col.addView(top)

            val meta = TextView(this).apply {
                val labelPart = alarm.label.ifBlank { getString(R.string.mindstop_morning_ring_default_label) }
                text = getString(
                    R.string.mindstop_morning_alarm_meta,
                    labelPart,
                    repeatLabel(alarm.repeatDaysMask),
                    methodLabel(alarm.dismissMethod),
                    alarm.totalActivationMinutes
                )
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(onVariant)
                setPadding(0, dp(4), 0, dp(8))
            }
            col.addView(meta)

            val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val editBtn = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                setText(R.string.mindstop_morning_edit_btn)
                setOnClickListener { openAlarmEditor(alarm) }
            }
            val testBtn = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                setText(R.string.mindstop_morning_test_10s_btn)
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.marginStart = dp(8)
                layoutParams = lp
                setOnClickListener {
                    MindstopAlarmScheduler.scheduleTestRingInSeconds(this@MindstopMorningActivity, alarm.id, 10)
                    Toast.makeText(this@MindstopMorningActivity, R.string.mindstop_morning_test_10s_toast, Toast.LENGTH_LONG).show()
                }
            }
            actions.addView(editBtn)
            actions.addView(testBtn)
            col.addView(actions)
            card.addView(col)
            container.addView(card)
        }
    }

    private fun openAlarmEditor(existing: MorningAlarm?) {
        val base = existing ?: MorningAlarm()
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(4))
        }
        val timeInput = EditText(this).apply {
            hint = getString(R.string.mindstop_morning_editor_time_hint)
            setText("%02d:%02d".format(base.hour, base.minute))
        }
        val labelInput = EditText(this).apply {
            hint = getString(R.string.mindstop_morning_editor_label_hint)
            setText(base.label)
        }
        val repeatOptions = listOf(
            MorningAlarm.WORKDAYS_MASK to getString(R.string.mindstop_morning_repeat_workdays),
            MorningAlarm.WEEKEND_MASK to getString(R.string.mindstop_morning_repeat_weekend),
            MorningAlarm.EVERYDAY_MASK to getString(R.string.mindstop_morning_repeat_daily),
            MorningAlarm.ONCE_MASK to getString(R.string.mindstop_morning_repeat_once)
        )
        val repeatSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MindstopMorningActivity, android.R.layout.simple_spinner_dropdown_item, repeatOptions.map { it.second })
            setSelection(repeatOptions.indexOfFirst { it.first == base.repeatDaysMask }.coerceAtLeast(0))
        }
        val methodOptions = listOf(
            WakeupConfirmMethod.MANUAL to methodLabel(WakeupConfirmMethod.MANUAL),
            WakeupConfirmMethod.MATH to methodLabel(WakeupConfirmMethod.MATH),
            WakeupConfirmMethod.STEPS to methodLabel(WakeupConfirmMethod.STEPS),
            WakeupConfirmMethod.QR to methodLabel(WakeupConfirmMethod.QR),
            WakeupConfirmMethod.NFC to methodLabel(WakeupConfirmMethod.NFC)
        )
        val methodSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MindstopMorningActivity, android.R.layout.simple_spinner_dropdown_item, methodOptions.map { it.second })
            setSelection(methodOptions.indexOfFirst { it.first == base.dismissMethod }.coerceAtLeast(0))
        }
        val joyOptions = listOf(
            MorningJoyPreset.MUSIC.code to getString(R.string.mindstop_morning_joy_music),
            MorningJoyPreset.AUDIOBOOK.code to getString(R.string.mindstop_morning_joy_audiobook),
            MorningJoyPreset.BREAKFAST.code to getString(R.string.mindstop_morning_joy_breakfast),
            MorningJoyPreset.SHORT_GAME.code to getString(R.string.mindstop_morning_joy_game),
            MorningJoyPreset.MARKET.code to getString(R.string.mindstop_morning_joy_market),
            MorningJoyPreset.GROWTH_GARDEN.code to getString(R.string.mindstop_morning_joy_garden)
        )
        val joySpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MindstopMorningActivity, android.R.layout.simple_spinner_dropdown_item, joyOptions.map { it.second })
            setSelection(joyOptions.indexOfFirst { it.first == base.joyActivity }.coerceAtLeast(0))
        }
        val gradualInput = EditText(this).apply {
            hint = getString(R.string.mindstop_morning_editor_gradual_hint)
            setText(base.gradualVolumeSeconds.toString())
        }
        val backupSwitch = MaterialSwitch(this).apply {
            setText(R.string.mindstop_morning_editor_backup_switch)
            isChecked = base.backupReminderEnabled
        }

        form.addView(timeInput)
        form.addView(labelInput)
        form.addView(repeatSpinner)
        form.addView(methodSpinner)
        form.addView(joySpinner)
        form.addView(gradualInput)
        form.addView(backupSwitch)

        val builder = MaterialAlertDialogBuilder(this)
            .setTitle(if (existing == null) R.string.mindstop_morning_add_alarm else R.string.mindstop_morning_edit_btn)
            .setView(form)
            .setPositiveButton(R.string.mindstop_rule_save) { _, _ ->
                val parts = timeInput.text?.toString()?.trim().orEmpty().replace('：', ':').split(':')
                val h = parts.getOrNull(0)?.toIntOrNull()
                val m = parts.getOrNull(1)?.toIntOrNull()
                if (parts.size != 2 || h == null || m == null || h !in 0..23 || m !in 0..59) {
                    Toast.makeText(this, R.string.mindstop_bedtime_invalid, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val gradual = gradualInput.text?.toString()?.trim()?.toIntOrNull()?.coerceIn(0, 120) ?: 30
                val updated = base.copy(
                    hour = h,
                    minute = m,
                    label = labelInput.text?.toString()?.trim().orEmpty(),
                    repeatDaysMask = repeatOptions[repeatSpinner.selectedItemPosition].first,
                    dismissMethod = methodOptions[methodSpinner.selectedItemPosition].first,
                    joyActivity = joyOptions[joySpinner.selectedItemPosition].first,
                    gradualVolumeSeconds = gradual,
                    backupReminderEnabled = backupSwitch.isChecked,
                    enabled = true
                )
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        val saved = repo.saveAlarm(updated, System.currentTimeMillis())
                        MindstopAlarmScheduler.schedule(this@MindstopMorningActivity, saved)
                    }
                    Toast.makeText(this@MindstopMorningActivity, R.string.mindstop_morning_alarm_saved, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.cancel, null)

        if (existing != null) {
            builder.setNeutralButton(R.string.mindstop_rule_delete) { _, _ ->
                if (MindstopAlarmService.activeAlarmId == existing.id) {
                    MindstopAlarmService.stop(this@MindstopMorningActivity)
                }
                lifecycleScope.launch(Dispatchers.IO) {
                    MindstopAlarmScheduler.cancel(this@MindstopMorningActivity, existing.id)
                    repo.deleteAlarm(existing.id, System.currentTimeMillis())
                }
            }
        }
        builder.show()
    }

    private fun renderLinks(links: List<MorningRepository.NightMorningLink>) {
        val container = binding.morningLinksContainer
        container.removeAllViews()
        val onSurface = MaterialColors.getColor(container, com.google.android.material.R.attr.colorOnSurface)
        val onVariant = MaterialColors.getColor(container, com.google.android.material.R.attr.colorOnSurfaceVariant)
        if (links.isEmpty()) {
            container.addView(TextView(this).apply {
                setText(R.string.mindstop_morning_links_empty)
                setTextColor(onVariant)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            })
            return
        }
        val timeFmt = DateFormat.getTimeFormat(this)
        for (item in links) {
            val s = item.session
            val card = buildCard()
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
            }
            val wakeTime = s.completedWall?.let { timeFmt.format(Date(it)) } ?: timeFmt.format(Date(s.ringStartedWall))
            box.addView(TextView(this).apply {
                text = getString(R.string.mindstop_morning_link_header, s.dayKey, stateLabel(s.status), wakeTime)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(onSurface)
            })
            val nightOver = item.previousNight?.overtimeMinutes ?: 0
            box.addView(TextView(this).apply {
                text = getString(R.string.mindstop_morning_link_detail, nightOver, s.responseDelaySec)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                setTextColor(onVariant)
                setPadding(0, dp(4), 0, 0)
            })
            card.addView(box)
            container.addView(card)
        }
    }

    private fun buildCard(): MaterialCardView = MaterialCardView(this).apply {
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.bottomMargin = dp(10)
        layoutParams = lp
        radius = dp(16).toFloat()
        cardElevation = 0f
        strokeWidth = dp(1)
        strokeColor = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOutlineVariant)
        setCardBackgroundColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurfaceContainerLow))
    }

    private fun repeatLabel(mask: Int): String = when (mask) {
        MorningAlarm.ONCE_MASK -> getString(R.string.mindstop_morning_repeat_once)
        MorningAlarm.WORKDAYS_MASK -> getString(R.string.mindstop_morning_repeat_workdays)
        MorningAlarm.WEEKEND_MASK -> getString(R.string.mindstop_morning_repeat_weekend)
        MorningAlarm.EVERYDAY_MASK -> getString(R.string.mindstop_morning_repeat_daily)
        else -> getString(R.string.mindstop_morning_repeat_custom)
    }

    private fun methodLabel(method: WakeupConfirmMethod): String = when (method) {
        WakeupConfirmMethod.MANUAL -> getString(R.string.mindstop_morning_method_manual)
        WakeupConfirmMethod.MATH -> getString(R.string.mindstop_morning_method_math)
        WakeupConfirmMethod.STEPS -> getString(R.string.mindstop_morning_method_steps)
        WakeupConfirmMethod.QR -> getString(R.string.mindstop_morning_method_qr)
        WakeupConfirmMethod.NFC -> getString(R.string.mindstop_morning_method_nfc)
    }

    private fun stateLabel(state: MorningState): String = when (state) {
        MorningState.COMPLETED -> getString(R.string.mindstop_morning_state_completed)
        MorningState.RETURNED_TO_BED -> getString(R.string.mindstop_morning_state_returned)
        MorningState.STAGE1_WAKE -> getString(R.string.mindstop_morning_state_stage1)
        MorningState.EXTENDED_SLEEP -> getString(R.string.mindstop_morning_state_extended)
        MorningState.STAGE2_PREP -> getString(R.string.mindstop_morning_state_stage2)
        MorningState.STAGE3_REWARD -> getString(R.string.mindstop_morning_state_stage3)
        MorningState.ACTIVATION -> getString(R.string.mindstop_morning_state_activation)
        MorningState.UNVERIFIED -> getString(R.string.mindstop_morning_state_unverified)
        else -> getString(R.string.mindstop_morning_state_ringing)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
