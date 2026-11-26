package app.mindstop.ui

import android.os.Bundle
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import app.mindstop.core.DayKeys
import app.mindstop.core.MindstopStatsSummary
import app.mindstop.core.ReflectionReason
import app.mindstop.core.StatsRange
import app.mindstop.service.QuotaRepository
import app.mindstop.service.StatsRepository
import com.google.android.material.color.MaterialColors
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import neth.iecal.curbox.R
import neth.iecal.curbox.databinding.ActivityMindstopStatsBinding
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import kotlin.math.roundToInt

/**
 * Local behavioral statistics and CSV data export screen (SPEC §17).
 * Shows Today / Last 7 Days / All History metrics without judgmental scoring.
 */
class MindstopStatsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMindstopStatsBinding
    private lateinit var quotaRepo: QuotaRepository
    private lateinit var statsRepo: StatsRepository
    private var currentRange = StatsRange.TODAY

    private val csvSaver = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch {
            val count = withContext(Dispatchers.IO) {
                try {
                    val export = statsRepo.exportCsv()
                    contentResolver.openOutputStream(uri)?.use { stream ->
                        OutputStreamWriter(stream, StandardCharsets.UTF_8).use { writer ->
                            writer.write(export.csv)
                        }
                    } ?: return@withContext -1
                    export.rowCount
                } catch (_: Exception) {
                    -1
                }
            }
            if (count >= 0) {
                Toast.makeText(
                    this@MindstopStatsActivity,
                    getString(R.string.mindstop_stats_export_ok, count),
                    Toast.LENGTH_LONG
                ).show()
            } else {
                Toast.makeText(this@MindstopStatsActivity, R.string.mindstop_stats_export_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMindstopStatsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        MindstopUi.applySystemBarInsets(this, binding.statsRoot)
        MindstopUi.setupToolbar(this, binding.statsToolbar, R.string.mindstop_stats_title)

        quotaRepo = QuotaRepository(this)
        statsRepo = StatsRepository(this)

        binding.statsRangeGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            currentRange = when (checkedIds.firstOrNull()) {
                R.id.stats_range_7d -> StatsRange.LAST_7_DAYS
                R.id.stats_range_all -> StatsRange.ALL
                else -> StatsRange.TODAY
            }
            refresh()
        }
        binding.statsExportCsv.setOnClickListener {
            val day = DayKeys.dayKey(System.currentTimeMillis())
            csvSaver.launch("mindstop_data_$day.csv")
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        lifecycleScope.launch {
            val summary = withContext(Dispatchers.IO) {
                val now = quotaRepo.approxTrustedNow()
                statsRepo.loadSummary(currentRange, now)
            }
            render(summary)
        }
    }

    private fun render(s: MindstopStatsSummary) {
        val funStr = MindstopUi.formatDuration(this, s.funTotalMs)
        val studyStr = MindstopUi.formatDuration(this, s.studyTotalMs)
        val focusStr = MindstopUi.formatDuration(this, s.focusCompletedMs)
        val intStr = MindstopUi.formatDuration(this, s.focusInterruptMs)
        binding.statsOverviewBody.text = getString(
            R.string.mindstop_stats_overview_lines,
            funStr,
            studyStr,
            focusStr,
            s.focusCompletedCount,
            intStr
        )

        val na = getString(R.string.mindstop_stats_na)
        val volRate = s.voluntaryExitRate?.let { formatPct(it) } ?: na
        val overtime = s.avgOvertimeMs?.let { MindstopUi.formatDuration(this, it) } ?: na
        val reentry = s.reentryRate?.let { formatPct(it) } ?: na
        val studyDone = s.studyCompletionRate?.let {
            "${formatPct(it)} (${s.studyCompletedCount}/${s.studyReflectionCount})"
        } ?: na
        val reliance = s.interventionRelianceRate?.let { formatPct(it) } ?: na

        binding.statsMetricsBody.text = getString(
            R.string.mindstop_stats_metrics_lines,
            volRate,
            overtime,
            reentry,
            studyDone,
            reliance
        )
        binding.statsEventsBody.text = getString(
            R.string.mindstop_stats_events_lines,
            s.activeExitCount,
            s.forcedBlockCount,
            s.challengePassedCount,
            s.cooldownCount,
            s.bedtimeUnplannedCount
        )

        renderTrend(s)
        renderTopApps(s)
        renderReasons(s)
    }

    private fun renderTrend(s: MindstopStatsSummary) {
        val container = binding.statsTrendContainer
        container.removeAllViews()
        val maxMs = s.dailyTrend.maxOfOrNull { it.funMs + it.studyMs + it.focusMs }?.coerceAtLeast(60_000L) ?: 60_000L
        val primary = MaterialColors.getColor(container, androidx.appcompat.R.attr.colorPrimary)
        val track = MaterialColors.getColor(container, com.google.android.material.R.attr.colorPrimaryContainer)
        val onVariant = MaterialColors.getColor(container, com.google.android.material.R.attr.colorOnSurfaceVariant)

        for (pt in s.dailyTrend) {
            val label = TextView(this).apply {
                val shortDay = pt.dayKey.takeLast(5)
                text = getString(
                    R.string.mindstop_stats_trend_row,
                    shortDay,
                    MindstopUi.formatDuration(this@MindstopStatsActivity, pt.funMs),
                    MindstopUi.formatDuration(this@MindstopStatsActivity, pt.studyMs),
                    MindstopUi.formatDuration(this@MindstopStatsActivity, pt.focusMs)
                )
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(onVariant)
                setPadding(0, dp(6), 0, dp(2))
            }
            val bar = LinearProgressIndicator(this).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                trackThickness = dp(6)
                trackCornerRadius = dp(4)
                setIndicatorColor(primary)
                trackColor = track
                max = (maxMs / 1000L).toInt().coerceAtLeast(1)
                setProgressCompat((pt.funMs / 1000L).toInt(), false)
            }
            container.addView(label)
            container.addView(bar)
        }
    }

    private fun renderTopApps(s: MindstopStatsSummary) {
        val container = binding.statsAppsContainer
        container.removeAllViews()
        val onSurface = MaterialColors.getColor(container, com.google.android.material.R.attr.colorOnSurface)
        val onVariant = MaterialColors.getColor(container, com.google.android.material.R.attr.colorOnSurfaceVariant)
        if (s.topFunApps.isEmpty()) {
            container.addView(TextView(this).apply {
                setText(R.string.mindstop_stats_apps_empty)
                setTextColor(onVariant)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            })
            return
        }
        for (app in s.topFunApps) {
            val label = try {
                packageManager.getApplicationLabel(packageManager.getApplicationInfo(app.packageName, 0)).toString()
            } catch (_: Exception) {
                app.packageName
            }
            container.addView(TextView(this).apply {
                text = getString(
                    R.string.mindstop_stats_app_row,
                    label,
                    MindstopUi.formatDuration(this@MindstopStatsActivity, app.durationMs)
                )
                setTextColor(onSurface)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setPadding(0, dp(4), 0, dp(4))
            })
        }
    }

    private fun renderReasons(s: MindstopStatsSummary) {
        if (s.topReasons.isEmpty()) {
            binding.statsReasonsBody.setText(R.string.mindstop_stats_reasons_empty)
            return
        }
        binding.statsReasonsBody.text = s.topReasons.joinToString("\n") { (reason, count) ->
            getString(R.string.mindstop_stats_reason_row, getString(reasonLabelRes(reason)), count)
        }
    }

    private fun reasonLabelRes(reason: ReflectionReason): Int = when (reason) {
        ReflectionReason.RECOMMENDATIONS -> R.string.mindstop_reflection_reason_recs
        ReflectionReason.COMMENTS -> R.string.mindstop_reflection_reason_comments
        ReflectionReason.FORGOT_GOAL -> R.string.mindstop_reflection_reason_forgot
        ReflectionReason.FATIGUE -> R.string.mindstop_reflection_reason_fatigue
        ReflectionReason.WANT_REST -> R.string.mindstop_reflection_reason_rest
        ReflectionReason.OTHER -> R.string.mindstop_reflection_reason_other
    }

    private fun formatPct(rate: Float): String = "${(rate * 100f).roundToInt()}%"

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
