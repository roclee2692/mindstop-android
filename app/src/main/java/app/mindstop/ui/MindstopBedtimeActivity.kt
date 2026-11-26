package app.mindstop.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import app.mindstop.core.BedtimeEngine
import app.mindstop.core.BedtimeSettings
import app.mindstop.core.SleepDaySchedule
import app.mindstop.data.BedtimeLogEntity
import app.mindstop.service.QuotaRepository
import app.mindstop.service.Stage5Repository
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import neth.iecal.curbox.R
import neth.iecal.curbox.databinding.ActivityMindstopBedtimeBinding
import neth.iecal.curbox.ui.activity.SelectAppsActivity
import java.util.Calendar
import java.util.Date

/**
 * Bedtime Wind-down & Unified Sleep Plan screen (v0.3 SPEC §3–§8).
 * Configures workday & weekend schedules, live planned sleep opportunity, progressive wind-down
 * nodes, night restricted vs allowed apps, low-stimulation policy, and nightly behavioral logs.
 */
class MindstopBedtimeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMindstopBedtimeBinding
    private lateinit var quotaRepo: QuotaRepository
    private lateinit var stage5: Stage5Repository

    private var restrictedPackages: Set<String> = emptySet()
    private var allowedPackages: Set<String> = emptySet()
    private var loadedSettings: BedtimeSettings = BedtimeSettings()

    private val pickRestricted = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val apps = result.data?.getStringArrayListExtra("SELECTED_APPS") ?: return@registerForActivityResult
        restrictedPackages = apps.toSet()
        renderAppSummaries()
    }

    private val pickAllowed = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val apps = result.data?.getStringArrayListExtra("SELECTED_APPS") ?: return@registerForActivityResult
        allowedPackages = apps.toSet()
        renderAppSummaries()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMindstopBedtimeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        MindstopUi.applySystemBarInsets(this, binding.bedtimeRoot)
        MindstopUi.setupToolbar(this, binding.bedtimeToolbar, R.string.mindstop_bedtime_title)

        quotaRepo = QuotaRepository(this)
        stage5 = Stage5Repository(this)

        binding.bedtimeWeekendSwitch.setOnCheckedChangeListener { _, checked ->
            binding.bedtimeWeekendPanel.visibility = if (checked) View.VISIBLE else View.GONE
            updatePreview()
        }
        binding.bedtimePickRestricted.setOnClickListener {
            pickRestricted.launch(
                Intent(this, SelectAppsActivity::class.java)
                    .putStringArrayListExtra("PRE_SELECTED_APPS", ArrayList(restrictedPackages))
            )
        }
        binding.bedtimePickAllowed.setOnClickListener {
            pickAllowed.launch(
                Intent(this, SelectAppsActivity::class.java)
                    .putStringArrayListExtra("PRE_SELECTED_APPS", ArrayList(allowedPackages))
            )
        }
        binding.bedtimeSave.setOnClickListener { save() }
        binding.bedtimeReportSleepBtn.setOnClickListener { openSelfReportSleepDialog() }

        val previewInputs = listOf(
            binding.bedtimeFunEndInput,
            binding.bedtimeStartInput,
            binding.bedtimeEndInput,
            binding.bedtimeFreeRestInput,
            binding.bedtimeWindLeadInput,
            binding.bedtimeCountdownLeadInput
        )
        for (input in previewInputs) {
            input.doAfterTextChanged { updatePreview() }
        }

        lifecycleScope.launch {
            val current = withContext(Dispatchers.IO) { stage5.loadBedtime() }
            loadedSettings = current
            restrictedPackages = current.nightRestrictedPackages
            allowedPackages = current.nightAllowedPackages

            binding.bedtimeEnableSwitch.isChecked = current.enabled
            binding.bedtimeFunEndInput.setText(BedtimeEngine.formatMinuteOfDay(current.funEndMinutesOfDay))
            binding.bedtimeStartInput.setText(BedtimeEngine.formatMinuteOfDay(current.startMinutesOfDay))
            binding.bedtimeEndInput.setText(BedtimeEngine.formatMinuteOfDay(current.endMinutesOfDay))
            binding.bedtimeFreeRestInput.setText(current.freeRestMinutes.toString())
            binding.bedtimeWindLeadInput.setText(current.windDownLeadMinutes.toString())
            binding.bedtimeCountdownLeadInput.setText(current.countdownLeadMinutes.toString())

            binding.bedtimeWeekendSwitch.isChecked = current.weekendSeparate
            binding.bedtimeWeekendPanel.visibility = if (current.weekendSeparate) View.VISIBLE else View.GONE
            binding.bedtimeWeekendFunEndInput.setText(BedtimeEngine.formatMinuteOfDay(current.weekendFunEndMinutesOfDay))
            binding.bedtimeWeekendSleepInput.setText(BedtimeEngine.formatMinuteOfDay(current.weekendSleepMinutesOfDay))
            binding.bedtimeWeekendWakeInput.setText(BedtimeEngine.formatMinuteOfDay(current.weekendWakeMinutesOfDay))

            binding.bedtimePauseSwitch.isChecked = current.pauseEntertainment
            binding.bedtimeUntilMorningSwitch.isChecked = current.restrictUntilMorning
            binding.bedtimeReduceStimSwitch.isChecked = current.reduceStimulation
            binding.bedtimeRenewalsInput.setText(current.maxBedtimeRenewals.toString())
            binding.bedtimeCooldownInput.setText(current.bedtimeCooldownMinutes.toString())

            renderAppSummaries()
            updatePreview()
        }

        lifecycleScope.launch {
            stage5.observeRecentBedtimeLogs(7).collectLatest { logs ->
                renderLogs(logs)
            }
        }
    }

    private fun renderAppSummaries() {
        binding.bedtimeRestrictedSummary.text = if (restrictedPackages.isEmpty()) {
            getString(R.string.mindstop_bedtime_restricted_default)
        } else {
            getString(R.string.mindstop_bedtime_restricted_count, restrictedPackages.size)
        }
        binding.bedtimeAllowedSummary.text = if (allowedPackages.isEmpty()) {
            getString(R.string.mindstop_bedtime_allowed_default)
        } else {
            getString(R.string.mindstop_bedtime_allowed_count, allowedPackages.size)
        }
    }

    private fun updatePreview() {
        val sleep = parseHhMm(binding.bedtimeStartInput.text?.toString()) ?: loadedSettings.startMinutesOfDay
        val wake = parseHhMm(binding.bedtimeEndInput.text?.toString()) ?: loadedSettings.endMinutesOfDay
        val funEnd = parseHhMm(binding.bedtimeFunEndInput.text?.toString()) ?: loadedSettings.funEndMinutesOfDay
        val freeMin = binding.bedtimeFreeRestInput.text?.toString()?.trim()?.toIntOrNull()?.coerceIn(0, 180) ?: 30
        val windLead = binding.bedtimeWindLeadInput.text?.toString()?.trim()?.toIntOrNull()?.coerceIn(5, 120) ?: 20
        val countLead = binding.bedtimeCountdownLeadInput.text?.toString()?.trim()?.toIntOrNull()?.coerceIn(1, 60) ?: 5

        val schedule = SleepDaySchedule(
            plannedSleepMinutesOfDay = sleep,
            plannedWakeMinutesOfDay = wake,
            funEndMinutesOfDay = funEnd,
            freeRestMinutes = freeMin,
            windDownLeadMinutes = windLead,
            countdownLeadMinutes = countLead
        )
        val (oppH, oppM) = BedtimeEngine.formatHoursMinutes(schedule.plannedSleepOpportunityMinutes)
        binding.bedtimeOpportunitySummary.text = getString(
            R.string.mindstop_bedtime_opportunity_summary,
            BedtimeEngine.formatMinuteOfDay(sleep),
            BedtimeEngine.formatMinuteOfDay(wake),
            oppH,
            oppM
        )
        val freeStart = (funEnd - windLead - freeMin + 1440) % 1440
        val prepStart = (funEnd - windLead + 1440) % 1440
        val countStart = (funEnd - countLead + 1440) % 1440
        binding.bedtimeTimelinePreview.text = getString(
            R.string.mindstop_bedtime_timeline_preview,
            BedtimeEngine.formatMinuteOfDay(freeStart),
            BedtimeEngine.formatMinuteOfDay(prepStart),
            BedtimeEngine.formatMinuteOfDay(countStart),
            BedtimeEngine.formatMinuteOfDay(funEnd),
            BedtimeEngine.formatMinuteOfDay(sleep)
        )
    }

    private fun save() {
        val startMin = parseHhMm(binding.bedtimeStartInput.text?.toString())
        val endMin = parseHhMm(binding.bedtimeEndInput.text?.toString())
        val funEndMin = parseHhMm(binding.bedtimeFunEndInput.text?.toString())
        val freeMin = binding.bedtimeFreeRestInput.text?.toString()?.trim()?.toIntOrNull()
        val windLead = binding.bedtimeWindLeadInput.text?.toString()?.trim()?.toIntOrNull()
        val countLead = binding.bedtimeCountdownLeadInput.text?.toString()?.trim()?.toIntOrNull()
        val renewals = binding.bedtimeRenewalsInput.text?.toString()?.trim()?.toIntOrNull()
        val cooldown = binding.bedtimeCooldownInput.text?.toString()?.trim()?.toIntOrNull()

        val wkndSeparate = binding.bedtimeWeekendSwitch.isChecked
        val wkndSleep = parseHhMm(binding.bedtimeWeekendSleepInput.text?.toString()) ?: loadedSettings.weekendSleepMinutesOfDay
        val wkndWake = parseHhMm(binding.bedtimeWeekendWakeInput.text?.toString()) ?: loadedSettings.weekendWakeMinutesOfDay
        val wkndFunEnd = parseHhMm(binding.bedtimeWeekendFunEndInput.text?.toString()) ?: loadedSettings.weekendFunEndMinutesOfDay

        if (startMin == null || endMin == null || funEndMin == null ||
            freeMin == null || freeMin !in 0..180 ||
            windLead == null || windLead !in 5..120 ||
            countLead == null || countLead !in 1..60 ||
            renewals == null || renewals !in 0..5 ||
            cooldown == null || cooldown !in 1..720
        ) {
            Toast.makeText(this, R.string.mindstop_bedtime_invalid, Toast.LENGTH_SHORT).show()
            return
        }

        val bufferMin = ((startMin - funEndMin + 1440) % 1440).coerceIn(0, 120)
        val wkndBuffer = ((wkndSleep - wkndFunEnd + 1440) % 1440).coerceIn(0, 120)
        val updated = loadedSettings.copy(
            enabled = binding.bedtimeEnableSwitch.isChecked,
            startMinutesOfDay = startMin,
            endMinutesOfDay = endMin,
            plannedWakeMinutesOfDay = endMin,
            funEndMinutesOfDay = funEndMin,
            bufferMinutes = bufferMin,
            freeRestMinutes = freeMin,
            windDownLeadMinutes = windLead,
            countdownLeadMinutes = countLead,
            weekendSeparate = wkndSeparate,
            weekendSleepMinutesOfDay = wkndSleep,
            weekendWakeMinutesOfDay = wkndWake,
            weekendFunEndMinutesOfDay = wkndFunEnd,
            weekendBufferMinutes = wkndBuffer,
            pauseEntertainment = binding.bedtimePauseSwitch.isChecked,
            restrictUntilMorning = binding.bedtimeUntilMorningSwitch.isChecked,
            reduceStimulation = binding.bedtimeReduceStimSwitch.isChecked,
            maxBedtimeRenewals = renewals,
            bedtimeCooldownMinutes = cooldown,
            nightRestrictedPackages = restrictedPackages,
            nightAllowedPackages = allowedPackages
        )
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                val now = quotaRepo.approxTrustedNow()
                stage5.saveBedtime(updated, now)
            }
            Toast.makeText(this@MindstopBedtimeActivity, R.string.mindstop_bedtime_saved, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun openSelfReportSleepDialog() {
        val input = EditText(this).apply {
            hint = "23:15"
            setText(BedtimeEngine.formatMinuteOfDay(loadedSettings.startMinutesOfDay))
            setPadding(dp(20), dp(14), dp(20), dp(14))
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.mindstop_bedtime_report_sleep_dialog_title)
            .setMessage(R.string.mindstop_bedtime_report_sleep_dialog_msg)
            .setView(input)
            .setPositiveButton(R.string.mindstop_rule_save) { _, _ ->
                val min = parseHhMm(input.text?.toString())
                if (min == null) {
                    Toast.makeText(this, R.string.mindstop_bedtime_invalid, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        val now = quotaRepo.approxTrustedNow()
                        val eval = BedtimeEngine.evaluateAtWall(loadedSettings, now)
                        val cal = Calendar.getInstance().apply {
                            timeInMillis = now
                            // Map self-reported time to the target night (evening >= 12:00 or early morning < 12:00)
                            if (get(Calendar.HOUR_OF_DAY) < 12 && min >= 12 * 60) {
                                add(Calendar.DAY_OF_MONTH, -1)
                            }
                            set(Calendar.HOUR_OF_DAY, min / 60)
                            set(Calendar.MINUTE, min % 60)
                            set(Calendar.SECOND, 0)
                            set(Calendar.MILLISECOND, 0)
                        }
                        stage5.saveSelfReportedSleep(eval.nightKey, cal.timeInMillis, eval, now)
                    }
                    Toast.makeText(this@MindstopBedtimeActivity, R.string.mindstop_bedtime_report_sleep_saved, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun renderLogs(logs: List<BedtimeLogEntity>) {
        val container = binding.bedtimeLogsContainer
        container.removeAllViews()
        val onSurface = MaterialColors.getColor(container, com.google.android.material.R.attr.colorOnSurface)
        val onVariant = MaterialColors.getColor(container, com.google.android.material.R.attr.colorOnSurfaceVariant)
        if (logs.isEmpty()) {
            container.addView(TextView(this).apply {
                setText(R.string.mindstop_bedtime_logs_empty)
                setTextColor(onVariant)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            })
            return
        }
        val timeFmt = DateFormat.getTimeFormat(this)
        for (log in logs) {
            val card = MaterialCardView(this).apply {
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.bottomMargin = dp(10)
                layoutParams = lp
                radius = dp(16).toFloat()
                cardElevation = 0f
                strokeWidth = dp(1)
                strokeColor = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOutlineVariant)
                setCardBackgroundColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurfaceContainerLow))
            }
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
            }
            val lastStr = log.lastSupervisedWall?.let { timeFmt.format(Date(it)) } ?: getString(R.string.mindstop_stats_na)
            val sleepStr = log.selfReportedSleepWall?.let { timeFmt.format(Date(it)) } ?: getString(R.string.mindstop_stats_na)
            box.addView(TextView(this).apply {
                text = getString(
                    R.string.mindstop_bedtime_log_header,
                    log.nightKey,
                    BedtimeEngine.formatMinuteOfDay(log.plannedFunEndMin),
                    log.overtimeMinutes
                )
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(onSurface)
            })
            box.addView(TextView(this).apply {
                text = getString(
                    R.string.mindstop_bedtime_log_detail,
                    lastStr,
                    log.reopenCount,
                    log.voluntaryStopCount,
                    log.forcedBlockCount,
                    sleepStr
                )
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                setTextColor(onVariant)
                setPadding(0, dp(4), 0, 0)
            })
            card.addView(box)
            container.addView(card)
        }
    }

    private fun parseHhMm(raw: String?): Int? {
        val text = raw?.trim().orEmpty()
        val parts = text.split(':')
        if (parts.size != 2) return null
        val h = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null
        if (h !in 0..23 || m !in 0..59) return null
        return h * 60 + m
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
