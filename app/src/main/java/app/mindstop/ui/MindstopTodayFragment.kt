package app.mindstop.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import app.mindstop.core.DayKeys
import app.mindstop.core.FocusStatus
import app.mindstop.core.LimitKind
import app.mindstop.core.RuleStatus
import app.mindstop.service.ChallengeRepository
import app.mindstop.service.QuotaRepository
import app.mindstop.service.Stage5Repository
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.progressindicator.LinearProgressIndicator
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import neth.iecal.curbox.R
import neth.iecal.curbox.databinding.FragmentMindstopTodayBinding
import neth.iecal.curbox.services.AppBlockerService
import neth.iecal.curbox.utils.DataStoreManager
import neth.iecal.curbox.utils.PermissionUtils

/** Mindstop Today tab with a daily quote, guard status, and quota cards. */
class MindstopTodayFragment : Fragment() {

    private var _binding: FragmentMindstopTodayBinding? = null
    private val binding get() = _binding!!
    private lateinit var repo: QuotaRepository
    private lateinit var stage5: Stage5Repository
    private lateinit var settings: DataStoreManager
    private var quoteRefreshInFlight = false
    private var renderedQuoteId: String? = null
    private var quotaExpanded = false
    private var lastStatuses: List<RuleStatus> = emptyList()

    private val handler = Handler(Looper.getMainLooper())
    private val refresher = object : Runnable {
        override fun run() {
            refreshAll()
            handler.postDelayed(this, 30_000L)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentMindstopTodayBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        repo = QuotaRepository(requireContext())
        stage5 = Stage5Repository(requireContext())

        settings = DataStoreManager(requireContext())
        quotaExpanded = savedInstanceState?.getBoolean(STATE_QUOTA_EXPANDED, false) ?: quotaExpanded
        binding.todayPoster.setOnClickListener {
            if (childFragmentManager.findFragmentByTag("poster") == null) {
                MindstopPoster().showNow(childFragmentManager, "poster")
            }
        }
        binding.todayQuote.quoteNext.setOnClickListener { refreshQuote(advance = true) }
        binding.todayQuotaToggle.setOnClickListener {
            quotaExpanded = !quotaExpanded
            renderStatuses(lastStatuses)
        }

        binding.todayHealthButton.setOnClickListener {
            startActivity(Intent(requireContext(), MindstopDiagnosticsActivity::class.java))
        }
        binding.todayHealthCard.setOnClickListener {
            startActivity(Intent(requireContext(), MindstopDiagnosticsActivity::class.java))
        }
        binding.todayStartFocus.setOnClickListener {
            startActivity(Intent(requireContext(), MindstopFocusActivity::class.java))
        }
        binding.todayFocusCard.setOnClickListener {
            startActivity(Intent(requireContext(), MindstopFocusActivity::class.java))
        }
        binding.todayBedtime.setOnClickListener {
            startActivity(Intent(requireContext(), MindstopBedtimeActivity::class.java))
        }
        binding.todayMorning.setOnClickListener {
            startActivity(Intent(requireContext(), MindstopMorningActivity::class.java))
        }
        binding.todayShopping.setOnClickListener {
            startActivity(Intent(requireContext(), MindstopShoppingActivity::class.java))
        }
        binding.todayOpenStats.setOnClickListener {
            startActivity(Intent(requireContext(), MindstopStatsActivity::class.java))
        }
        binding.todayPresetGroups.setOnClickListener {
            startActivity(Intent(requireContext(), MindstopPresetGroupsActivity::class.java))
        }
        binding.todayAddRule.setOnClickListener {
            startActivity(Intent(requireContext(), MindstopRuleEditActivity::class.java))
        }
        binding.todayOpenIntentionSettings.setOnClickListener {
            startActivity(Intent(requireContext(), MindstopIntentionSettingsActivity::class.java))
        }

        viewLifecycleOwner.lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                repo.ensureDefaultRules(getString(R.string.mindstop_default_rule_name), System.currentTimeMillis())
                ChallengeRepository(requireContext()).ensureBuiltinBank(System.currentTimeMillis())
            }
            refreshAll()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshAll()
        handler.postDelayed(refresher, 30_000L)
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden && _binding != null) refreshAll()
    }

    override fun onPause() {
        handler.removeCallbacks(refresher)
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_QUOTA_EXPANDED, quotaExpanded)
    }

    override fun onDestroyView() {
        handler.removeCallbacks(refresher)
        renderedQuoteId = null
        _binding = null
        super.onDestroyView()
    }

    private fun refreshAll() {
        if (_binding == null) return
        if (!isHidden) refreshQuote()
        refreshHealth()
        viewLifecycleOwner.lifecycleScope.launch {
            val now = System.currentTimeMillis()
            val dayKey = DayKeys.dayKey(now)
            val sessionId = withContext(Dispatchers.IO) {
                val id = repo.loadMeta(QuotaRepository.KEY_SESSION_ID)?.toLongOrNull()
                val last = repo.loadMeta(QuotaRepository.KEY_LAST_SUPERVISED_WALL)?.toLongOrNull()
                if (id != null && last != null && now - last < app.mindstop.service.MindstopEngine.SESSION_GAP_MS) id else null
            }
            val statuses = withContext(Dispatchers.IO) { repo.dailyRuleStatuses(dayKey, sessionId) }
            val studyMs = withContext(Dispatchers.IO) { repo.studyTotalForDay(dayKey) }
            val activeFocus = withContext(Dispatchers.IO) { stage5.loadActiveFocus() }
            if (_binding != null) {
                renderStatuses(statuses)
                if (studyMs > 0L) {
                    binding.todayStudyCard.visibility = View.VISIBLE
                    binding.todayStudySummary.text = getString(
                        R.string.mindstop_study_today_banner,
                        MindstopUi.formatDuration(requireContext(), studyMs)
                    )
                } else {
                    binding.todayStudyCard.visibility = View.GONE
                }
                if (activeFocus != null && activeFocus.isActive) {
                    binding.todayFocusCard.visibility = View.VISIBLE
                    val res = if (activeFocus.status == FocusStatus.PAUSED) {
                        R.string.mindstop_today_focus_paused_badge
                    } else {
                        R.string.mindstop_today_focus_active_badge
                    }
                    val leftMs = if (activeFocus.status == FocusStatus.PAUSED) {
                        activeFocus.interruptRemainingMs
                    } else {
                        activeFocus.focusRemainingMs
                    }
                    binding.todayFocusBadge.text = getString(res, MindstopUi.formatDuration(requireContext(), leftMs))
                } else {
                    binding.todayFocusCard.visibility = View.GONE
                }
            }
        }
    }

    private fun refreshQuote(advance: Boolean = false) {
        if (quoteRefreshInFlight) return
        quoteRefreshInFlight = true
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val day = DayKeys.dayKey(System.currentTimeMillis())
                val current = settings.selectMindstopQuote(day, advance)
                if (current?.current == renderedQuoteId) return@launch
                renderedQuoteId = current?.current
                val quote = current?.let { app.mindstop.core.DailyQuotes.byId[it.current] }
                with(binding.todayQuote) {
                    root.isVisible = quote != null
                    if (quote != null) {
                        val ctx = requireContext()
                        quotePrimary.text = MindstopQuoteText.primaryText(ctx, quote)
                        quoteSecondary.text = MindstopQuoteText.secondaryText(ctx, quote)
                        val source = MindstopQuoteText.sourceText(ctx, quote)
                        quoteSource.isVisible = source != null
                        quoteSource.text = source.orEmpty()
                    }
                }
            } catch (e: IOException) {
                Log.w("MindstopToday", "Could not refresh the daily quote", e)
            } finally {
                quoteRefreshInFlight = false
            }
        }
    }

    private fun refreshHealth() {
        val ctx = requireContext()
        val a11yOn = PermissionUtils.isAccessibilityServiceEnabled(ctx, AppBlockerService::class.java)
        val power = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        val batteryOk = power.isIgnoringBatteryOptimizations(ctx.packageName)
        val miuiPopup = MindstopUi.isMiuiBackgroundPopupAllowed(ctx)
        val needsAttention = !a11yOn || !batteryOk || (miuiPopup == false)
        binding.todayHealthCard.visibility = if (needsAttention) View.VISIBLE else View.GONE
    }

    private fun renderStatuses(statuses: List<RuleStatus>) {
        lastStatuses = statuses
        val ctx = requireContext()
        val container = binding.todayStatusContainer
        container.removeAllViews()
        if (statuses.isEmpty()) {
            binding.todayQuotaToggle.visibility = View.GONE
            val emptyCard = buildCard(ctx).apply {
                addView(TextView(ctx).apply {
                    text = getString(R.string.mindstop_today_quota_card_empty)
                    setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    setPadding(dp(18), dp(20), dp(18), dp(20))
                })
            }
            container.addView(emptyCard)
            return
        }

        binding.todayQuotaToggle.visibility = View.VISIBLE
        binding.todayQuotaToggle.text = if (quotaExpanded) {
            getString(R.string.mindstop_today_quota_collapse)
        } else {
            getString(R.string.mindstop_today_quota_expand, statuses.size)
        }

        val onSurface = MaterialColors.getColor(container, com.google.android.material.R.attr.colorOnSurface)
        val onSurfaceVariant = MaterialColors.getColor(container, com.google.android.material.R.attr.colorOnSurfaceVariant)
        val primary = MaterialColors.getColor(container, androidx.appcompat.R.attr.colorPrimary)
        val primaryContainer = MaterialColors.getColor(container, com.google.android.material.R.attr.colorPrimaryContainer)
        val onPrimaryContainer = MaterialColors.getColor(container, com.google.android.material.R.attr.colorOnPrimaryContainer)

        if (!quotaExpanded) {
            val tightest = statuses.minByOrNull { it.remainingMs } ?: statuses.first()
            val totalMs = tightest.limitMs + tightest.grantedMs
            val leftMs = tightest.remainingMs.coerceAtLeast(0L)
            val summaryCard = buildCard(ctx, bottomMarginDp = 8).apply {
                setOnClickListener {
                    quotaExpanded = true
                    renderStatuses(lastStatuses)
                }
            }
            val body = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(12), dp(16), dp(12))
            }
            val topRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val title = TextView(ctx).apply {
                text = MindstopUi.ruleDisplayTitle(ctx, tightest.rule)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(onSurface)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = dp(10)
                }
            }
            val leftText = TextView(ctx).apply {
                text = getString(R.string.mindstop_today_left_big, MindstopUi.formatDuration(ctx, leftMs))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(primary)
            }
            topRow.addView(title)
            topRow.addView(leftText)
            body.addView(topRow)

            val progress = LinearProgressIndicator(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(8)
                }
                trackThickness = dp(6)
                trackCornerRadius = dp(4)
                setIndicatorColor(primary)
                trackColor = primaryContainer
                max = (totalMs / 1000L).toInt().coerceAtLeast(1)
                setProgressCompat((tightest.usedMs.coerceAtMost(totalMs) / 1000L).toInt(), false)
            }
            body.addView(progress)

            val sub = TextView(ctx).apply {
                text = if (statuses.size == 1) {
                    getString(
                        R.string.mindstop_today_quota_collapsed_single,
                        MindstopUi.formatDuration(ctx, tightest.usedMs),
                        MindstopUi.formatDuration(ctx, totalMs)
                    )
                } else {
                    getString(
                        R.string.mindstop_today_quota_collapsed_multi,
                        MindstopUi.formatDuration(ctx, tightest.usedMs),
                        MindstopUi.formatDuration(ctx, totalMs),
                        statuses.size
                    )
                }
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(onSurfaceVariant)
                setPadding(0, dp(6), 0, 0)
            }
            body.addView(sub)

            summaryCard.addView(body)
            container.addView(summaryCard)
            return
        }

        for (status in statuses) {
            val card = buildCard(ctx)
            card.setOnClickListener {
                startActivity(
                    Intent(ctx, MindstopRuleEditActivity::class.java)
                        .putExtra(MindstopRuleEditActivity.EXTRA_RULE_ID, status.rule.id)
                )
            }
            val body = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(18), dp(16), dp(18), dp(16))
            }

            val topRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val title = TextView(ctx).apply {
                text = MindstopUi.ruleDisplayTitle(ctx, status.rule)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(onSurface)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val kindLabel = if (status.kind == LimitKind.DAILY) {
                getString(R.string.mindstop_kind_daily)
            } else {
                getString(R.string.mindstop_kind_session)
            }
            val badge = TextView(ctx).apply {
                text = kindLabel
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(onPrimaryContainer)
                setPadding(dp(10), dp(4), dp(10), dp(4))
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = dp(12).toFloat()
                    setColor(primaryContainer)
                }
            }
            topRow.addView(title)
            topRow.addView(badge)
            body.addView(topRow)

            val totalMs = status.limitMs + status.grantedMs
            val leftMs = status.remainingMs.coerceAtLeast(0L)
            val bigLeft = TextView(ctx).apply {
                text = getString(R.string.mindstop_today_left_big, MindstopUi.formatDuration(ctx, leftMs))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(primary)
                setPadding(0, dp(10), 0, dp(2))
            }
            body.addView(bigLeft)

            val progress = LinearProgressIndicator(ctx).apply {
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.topMargin = dp(8)
                layoutParams = lp
                trackThickness = dp(8)
                trackCornerRadius = dp(6)
                setIndicatorColor(primary)
                trackColor = primaryContainer
                max = (totalMs / 1000L).toInt().coerceAtLeast(1)
                setProgressCompat((status.usedMs.coerceAtMost(totalMs) / 1000L).toInt(), false)
            }
            body.addView(progress)

            val sub = TextView(ctx).apply {
                text = getString(
                    R.string.mindstop_today_used_of,
                    MindstopUi.formatDuration(ctx, status.usedMs),
                    MindstopUi.formatDuration(ctx, totalMs)
                )
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(onSurfaceVariant)
                setPadding(0, dp(8), 0, 0)
            }
            body.addView(sub)

            card.addView(body)
            container.addView(card)
        }
    }

    private fun buildCard(ctx: Context, bottomMarginDp: Int = 12): MaterialCardView = MaterialCardView(ctx).apply {
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.bottomMargin = dp(bottomMarginDp)
        layoutParams = lp
        radius = dp(20).toFloat()
        cardElevation = 0f
        strokeWidth = dp(1)
        strokeColor = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOutlineVariant)
        setCardBackgroundColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurfaceContainerLow))
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val STATE_QUOTA_EXPANDED = "quota_expanded"
    }
}
