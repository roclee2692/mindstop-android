package app.mindstop.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import app.mindstop.core.CandidatePriceInput
import app.mindstop.core.DecisionTier
import app.mindstop.core.MoneyFormat
import app.mindstop.core.PriceAnalysisEngine
import app.mindstop.core.PriceDistribution
import app.mindstop.core.PricePositionBand
import app.mindstop.core.ShoppingBudgetSettings
import app.mindstop.core.StopReason
import app.mindstop.data.ProductCandidateEntity
import app.mindstop.data.ShoppingTaskEntity
import app.mindstop.service.ShoppingRepository
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import neth.iecal.curbox.R
import neth.iecal.curbox.databinding.ActivityMindstopTaskDetailBinding
import java.util.Date

/**
 * Staged research, unit-price standardization, P25/P50/P75 percentile analysis, 3-2-1 stopping
 * rules, and search-cost estimation for a single shopping task (v0.3 SPEC §20–§23).
 */
class MindstopTaskDetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_TASK_ID = "app.mindstop.extra.SHOPPING_TASK_ID"
    }

    private lateinit var binding: ActivityMindstopTaskDetailBinding
    private lateinit var repo: ShoppingRepository
    private val handler = Handler(Looper.getMainLooper())

    private var taskId: Long = -1L
    private var task: ShoppingTaskEntity? = null
    private var candidates: List<ProductCandidateEntity> = emptyList()
    private var budgetSettings = ShoppingBudgetSettings()
    private var researchStartWall: Long? = null

    private val ticker = object : Runnable {
        override fun run() {
            if (researchStartWall != null) {
                renderTaskHeader()
                handler.postDelayed(this, 1_000L)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMindstopTaskDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        MindstopUi.applySystemBarInsets(this, binding.taskDetailRoot)
        MindstopUi.setupToolbar(this, binding.taskDetailToolbar, R.string.mindstop_shop_analysis_screen_title)

        repo = ShoppingRepository(this)
        taskId = intent.getLongExtra(EXTRA_TASK_ID, -1L)
        if (taskId <= 0L) {
            finish()
            return
        }

        binding.taskResearchToggleBtn.setOnClickListener { toggleResearchSession() }
        binding.taskSearchCostBtn.setOnClickListener { openSearchCostDialog() }
        binding.taskSaveNotesBtn.setOnClickListener { saveResearchNotes() }

        lifecycleScope.launch {
            budgetSettings = withContext(Dispatchers.IO) { repo.loadBudgetSettings() }
            val loaded = withContext(Dispatchers.IO) { repo.getTask(taskId) }
            if (loaded == null) {
                finish()
                return@launch
            }
            task = loaded
            binding.taskUnresolvedInput.setText(loaded.unresolvedQuestions)
            binding.taskDecisionDateInput.setText(loaded.decisionDateKey)
            binding.taskCooldownHoursInput.setText(loaded.cooldownHours.toString())
            renderTaskHeader()
            renderAnalysis()
        }

        lifecycleScope.launch {
            repo.observeCandidates(taskId).collectLatest { rows ->
                candidates = rows
                renderAnalysis()
            }
        }
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (researchStartWall != null) handler.post(ticker)
    }

    private fun toggleResearchSession() {
        val current = task ?: return
        val started = researchStartWall
        if (started == null) {
            researchStartWall = System.currentTimeMillis()
            binding.taskResearchToggleBtn.setText(R.string.mindstop_shop_research_pause)
            handler.post(ticker)
        } else {
            handler.removeCallbacks(ticker)
            val elapsedMs = (System.currentTimeMillis() - started).coerceAtLeast(0L)
            researchStartWall = null
            binding.taskResearchToggleBtn.setText(R.string.mindstop_shop_research_start)
            val updated = current.copy(
                researchMsTotal = current.researchMsTotal + elapsedMs,
                updatedWall = System.currentTimeMillis()
            )
            task = updated
            lifecycleScope.launch(Dispatchers.IO) { repo.saveTask(updated) }
            renderTaskHeader()
            renderAnalysis()
            Toast.makeText(this, R.string.mindstop_shop_research_saved_toast, Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveResearchNotes() {
        val current = task ?: return
        val updated = current.copy(
            unresolvedQuestions = binding.taskUnresolvedInput.text?.toString()?.trim().orEmpty(),
            decisionDateKey = binding.taskDecisionDateInput.text?.toString()?.trim().orEmpty(),
            cooldownHours = binding.taskCooldownHoursInput.text?.toString()?.trim()?.toIntOrNull()?.coerceIn(0, 720) ?: current.cooldownHours,
            updatedWall = System.currentTimeMillis()
        )
        task = updated
        lifecycleScope.launch(Dispatchers.IO) { repo.saveTask(updated) }
        Toast.makeText(this, R.string.mindstop_shop_progress_saved, Toast.LENGTH_SHORT).show()
    }

    private fun renderTaskHeader() {
        val t = task ?: return
        val tier = DecisionTier.fromCode(t.decisionTier)
        val liveExtra = researchStartWall?.let { (System.currentTimeMillis() - it).coerceAtLeast(0L) } ?: 0L
        val totalMs = t.researchMsTotal + liveExtra
        binding.taskDetailHeader.text = getString(
            R.string.mindstop_shop_detail_header,
            t.itemName,
            MoneyFormat.formatYuan(t.budgetCents)
        )
        binding.taskDetailMeta.text = getString(
            R.string.mindstop_shop_detail_meta,
            tier.minMinutes,
            tier.maxMinutes,
            MindstopUi.formatDuration(this, totalMs),
            t.mustHaves.ifBlank { getString(R.string.mindstop_stats_na) }
        )
    }

    private fun renderAnalysis() {
        val t = task ?: return
        val inputs = candidates.map {
            CandidatePriceInput(
                id = it.id,
                name = it.name,
                comparisonGroupKey = it.comparisonGroupKey.ifBlank { t.itemName },
                quantityText = it.quantityText,
                unitCode = it.unitCode,
                priceCents = it.priceCents,
                discountCents = it.discountCents,
                shippingCents = it.shippingCents,
                meetsMustHaves = it.meetsMustHaves,
                source = it.source,
                collectedWall = it.collectedWall
            )
        }
        val tier = DecisionTier.fromCode(t.decisionTier)
        val elapsedMin = ((t.researchMsTotal + (researchStartWall?.let { System.currentTimeMillis() - it } ?: 0L)) / 60_000L).toInt()
        val stopping = PriceAnalysisEngine.evaluateStopping(inputs, t.budgetCents, tier, elapsedMin)
        val stopLines = buildString {
            append(getString(R.string.mindstop_shop_stopping_counts, stopping.qualifiedCount, stopping.withinBudgetCount))
            for (r in stopping.reasons) {
                append("\n• ").append(stopReasonText(r))
            }
            if (stopping.reasons.isEmpty()) {
                append("\n").append(getString(R.string.mindstop_shop_stopping_keep_going))
            }
        }
        binding.taskStoppingBody.text = stopLines

        val container = binding.taskAnalysisContainer
        container.removeAllViews()
        val groups = PriceAnalysisEngine.analyzeAllGroups(inputs)
        val onSurface = MaterialColors.getColor(container, com.google.android.material.R.attr.colorOnSurface)
        val onVariant = MaterialColors.getColor(container, com.google.android.material.R.attr.colorOnSurfaceVariant)
        if (groups.isEmpty()) {
            container.addView(TextView(this).apply {
                setText(R.string.mindstop_shop_analysis_empty)
                setTextColor(onVariant)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            })
            return
        }
        val dateFmt = DateFormat.getDateFormat(this)
        for (dist in groups) {
            container.addView(buildGroupCard(dist, dateFmt, onSurface, onVariant))
        }
    }

    private fun buildGroupCard(
        dist: PriceDistribution,
        dateFmt: java.text.DateFormat,
        onSurface: Int,
        onVariant: Int
    ): MaterialCardView {
        val card = MaterialCardView(this).apply {
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.bottomMargin = dp(12)
            layoutParams = lp
            radius = dp(16).toFloat()
            cardElevation = 0f
            strokeWidth = dp(1)
            strokeColor = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOutlineVariant)
            setCardBackgroundColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurfaceContainerLow))
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        col.addView(TextView(this).apply {
            text = getString(R.string.mindstop_shop_group_header, dist.groupKey, dist.baseUnitCode, dist.sampleCount)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(onSurface)
        })
        col.addView(TextView(this).apply {
            text = getString(
                R.string.mindstop_shop_percentiles_lines,
                PriceAnalysisEngine.formatUnitPrice(dist.minUnitPrice),
                PriceAnalysisEngine.formatUnitPrice(dist.p25UnitPrice),
                PriceAnalysisEngine.formatUnitPrice(dist.p50UnitPrice),
                PriceAnalysisEngine.formatUnitPrice(dist.p75UnitPrice),
                PriceAnalysisEngine.formatUnitPrice(dist.maxUnitPrice),
                PriceAnalysisEngine.formatUnitPrice(dist.meanUnitPrice)
            )
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(onSurface)
            setPadding(0, dp(6), 0, dp(4))
        })

        val note = buildString {
            if (dist.sampleUnstable) append(getString(R.string.mindstop_shop_sample_unstable)).append("\n")
            if (dist.excludedIncompatibleCount > 0 || dist.excludedMissingMustHavesCount > 0) {
                append(getString(R.string.mindstop_shop_excluded_note, dist.excludedIncompatibleCount, dist.excludedMissingMustHavesCount)).append("\n")
            }
            append(
                getString(
                    R.string.mindstop_shop_sample_provenance,
                    dateFmt.format(Date(dist.newestCollectedWall)),
                    dist.sources.joinToString(", ").ifBlank { "manual" }
                )
            )
        }
        col.addView(TextView(this).apply {
            text = note
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(onVariant)
            setPadding(0, dp(2), 0, dp(8))
        })

        for (offer in dist.offersSorted) {
            col.addView(TextView(this).apply {
                text = getString(
                    R.string.mindstop_shop_offer_rank_row,
                    offer.input.name,
                    PriceAnalysisEngine.formatUnitPrice(offer.unitPriceYuan),
                    dist.baseUnitCode,
                    bandLabel(offer.positionBand)
                )
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(onSurface)
                setPadding(0, dp(3), 0, dp(3))
            })
        }
        card.addView(col)
        return card
    }

    private fun openSearchCostDialog() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(4))
        }
        val saveInput = EditText(this).apply {
            hint = getString(R.string.mindstop_shop_sc_save_hint)
            setText("20")
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        val probInput = EditText(this).apply {
            hint = getString(R.string.mindstop_shop_sc_prob_hint)
            setText("30")
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        val extraMinInput = EditText(this).apply {
            hint = getString(R.string.mindstop_shop_sc_min_hint)
            setText("20")
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        listOf(saveInput, probInput, extraMinInput).forEach(box::addView)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.mindstop_shop_search_cost_btn)
            .setMessage(getString(R.string.mindstop_shop_sc_disclaimer, MoneyFormat.formatYuan(budgetSettings.hourlyTimeValueCents)))
            .setView(box)
            .setPositiveButton(R.string.mindstop_shop_sc_calc_btn) { _, _ ->
                val saveCents = MoneyFormat.parseYuanToCents(saveInput.text?.toString()) ?: 0L
                val prob = probInput.text?.toString()?.trim()?.toIntOrNull() ?: 0
                val extraMin = extraMinInput.text?.toString()?.trim()?.toIntOrNull() ?: 0
                val est = PriceAnalysisEngine.estimateSearchCost(
                    potentialSavingsCents = saveCents,
                    estimatedProbPercent = prob,
                    extraSearchMinutes = extraMin,
                    hourlyTimeValueCents = budgetSettings.hourlyTimeValueCents
                )
                val verdictRes = if (est.suggestStop) R.string.mindstop_shop_sc_verdict_stop else R.string.mindstop_shop_sc_verdict_continue
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.mindstop_shop_search_cost_btn)
                    .setMessage(
                        getString(
                            R.string.mindstop_shop_sc_result,
                            MoneyFormat.formatYuan(est.expectedBenefitCents),
                            MoneyFormat.formatYuan(est.searchCostCents),
                            getString(verdictRes)
                        )
                    )
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun stopReasonText(reason: StopReason): String = when (reason) {
        StopReason.THREE_TWO_ONE_READY -> getString(R.string.mindstop_shop_stop_321)
        StopReason.WITHIN_BUDGET_QUALIFIED -> getString(R.string.mindstop_shop_stop_budget_ok)
        StopReason.SEARCH_TIME_REACHED -> getString(R.string.mindstop_shop_stop_time_up)
        StopReason.MULTI_SESSION_PAUSE -> getString(R.string.mindstop_shop_stop_durable_pause)
    }

    private fun bandLabel(band: PricePositionBand?): String = when (band) {
        PricePositionBand.BELOW_P25 -> getString(R.string.mindstop_shop_band_below_p25)
        PricePositionBand.P25_TO_P50 -> getString(R.string.mindstop_shop_band_p25_p50)
        PricePositionBand.P50_TO_P75 -> getString(R.string.mindstop_shop_band_p50_p75)
        PricePositionBand.ABOVE_P75 -> getString(R.string.mindstop_shop_band_above_p75)
        null -> ""
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
