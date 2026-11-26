package app.mindstop.ui

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.mindstop.core.DayKeys
import app.mindstop.core.GardenStage
import app.mindstop.core.GrowthWorldPlanner
import app.mindstop.core.RewardRecord
import app.mindstop.core.RewardStatus
import app.mindstop.core.RewardTrigger
import app.mindstop.service.QuotaRepository
import app.mindstop.service.RewardRepository
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import neth.iecal.curbox.R
import neth.iecal.curbox.databinding.FragmentMindstopGrowthBinding
import java.util.Date

/** Growth World tab: serene botanical garden canvas, pending verification banner, and reward history. */
class MindstopGrowthFragment : Fragment() {

    private var _binding: FragmentMindstopGrowthBinding? = null
    private val binding get() = _binding!!
    private lateinit var quotaRepo: QuotaRepository
    private lateinit var rewardRepo: RewardRepository
    private var latestRecords: List<RewardRecord> = emptyList()

    private val handler = Handler(Looper.getMainLooper())
    private val refresher = object : Runnable {
        override fun run() {
            settleAndRender()
            handler.postDelayed(this, 30_000L)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentMindstopGrowthBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        quotaRepo = QuotaRepository(requireContext())
        rewardRepo = RewardRepository(requireContext())

        val quotes = listOf(
            R.string.mindstop_growth_subtitle,
            R.string.mindstop_reward_quote_1,
            R.string.mindstop_reward_quote_2
        )
        val dayIndex = (System.currentTimeMillis() / 86_400_000L).toInt().mod(quotes.size)
        binding.growthSubtitle.setText(quotes[dayIndex])

        binding.growthOpenSettings.setOnClickListener {
            startActivity(Intent(requireContext(), MindstopRewardSettingsActivity::class.java))
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                rewardRepo.observeAll().collect { records ->
                    latestRecords = records
                    renderWorld(records, System.currentTimeMillis())
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        settleAndRender()
        handler.postDelayed(refresher, 30_000L)
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden && _binding != null) settleAndRender()
    }

    override fun onPause() {
        handler.removeCallbacks(refresher)
        super.onPause()
    }

    override fun onDestroyView() {
        handler.removeCallbacks(refresher)
        _binding = null
        super.onDestroyView()
    }

    private fun settleAndRender() {
        if (_binding == null) return
        viewLifecycleOwner.lifecycleScope.launch {
            val now = withContext(Dispatchers.IO) {
                val trusted = quotaRepo.approxTrustedNow()
                rewardRepo.settleDueRewards(trusted)
                trusted
            }
            if (_binding != null) renderWorld(latestRecords, now)
        }
    }

    private fun renderWorld(records: List<RewardRecord>, nowWall: Long) {
        val todayKey = DayKeys.dayKey(nowWall)
        val world = GrowthWorldPlanner.compute(records, todayKey)

        binding.growthGardenView.render(world)
        binding.growthStageTitle.setText(stageTitleRes(world.stage))
        binding.growthEnergyBadge.text = getString(R.string.mindstop_growth_energy_total, world.confirmedEnergy)
        binding.growthNextTreeLabel.text = getString(
            R.string.mindstop_growth_next_tree,
            world.nextTreeProgress,
            world.energyPerTree
        )
        binding.growthNextTreeProgress.max = world.energyPerTree
        binding.growthNextTreeProgress.progress = world.nextTreeProgress

        binding.growthStatTodayVal.text = getString(R.string.mindstop_growth_energy_total, world.confirmedTodayEnergy)
        binding.growthStatPendingVal.text = world.pendingSaplings.toString()
        binding.growthStatTreesVal.text = world.confirmedTrees.toString()

        val activePending = records.filter { it.status == RewardStatus.PENDING }
        if (activePending.isEmpty()) {
            binding.growthPendingCard.visibility = View.GONE
        } else {
            val earliestUntil = activePending.minOf { it.verifyUntilWall }
            val minutesLeft = (((earliestUntil - nowWall).coerceAtLeast(0L) + 59_999L) / 60_000L).coerceAtLeast(1L)
            binding.growthPendingCard.visibility = View.VISIBLE
            binding.growthPendingTitle.text = getString(R.string.mindstop_growth_pending_title, activePending.size)
            binding.growthPendingBody.text = getString(R.string.mindstop_growth_pending_body, minutesLeft)
        }

        renderRecords(records.take(20))
    }

    private fun renderRecords(records: List<RewardRecord>) {
        val ctx = requireContext()
        val container = binding.growthRecordsContainer
        container.removeAllViews()

        val onSurface = MaterialColors.getColor(container, com.google.android.material.R.attr.colorOnSurface)
        val onSurfaceVariant = MaterialColors.getColor(container, com.google.android.material.R.attr.colorOnSurfaceVariant)
        val primary = MaterialColors.getColor(container, androidx.appcompat.R.attr.colorPrimary)
        val tertiary = MaterialColors.getColor(container, com.google.android.material.R.attr.colorTertiary)

        if (records.isEmpty()) {
            container.addView(buildCard(ctx).apply {
                addView(TextView(ctx).apply {
                    setText(R.string.mindstop_growth_recent_empty)
                    setTextColor(onSurfaceVariant)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    setPadding(dp(16), dp(18), dp(16), dp(18))
                })
            })
            return
        }

        val timeFmt = DateFormat.getTimeFormat(ctx)
        val dateFmt = DateFormat.getDateFormat(ctx)
        for (rec in records) {
            val card = buildCard(ctx)
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(14), dp(16), dp(14))
            }
            val header = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val triggerText = getString(triggerLabelRes(rec.trigger))
            val appName = appLabel(rec.packageName)
            val titleView = TextView(ctx).apply {
                text = getString(R.string.mindstop_growth_record_title_pair, triggerText, appName)
                setTextColor(onSurface)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val statusView = TextView(ctx).apply {
                setText(statusLabelRes(rec.status))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(
                    when (rec.status) {
                        RewardStatus.CONFIRMED -> primary
                        RewardStatus.PENDING -> tertiary
                        RewardStatus.CANCELLED -> onSurfaceVariant
                    }
                )
            }
            header.addView(titleView)
            header.addView(statusView)
            row.addView(header)

            val dateStr = dateFmt.format(Date(rec.createdWall))
            val timeStr = timeFmt.format(Date(rec.createdWall))
            val metaText = if (rec.status == RewardStatus.CANCELLED) {
                getString(R.string.mindstop_growth_record_meta_cancelled, dateStr, timeStr)
            } else {
                getString(R.string.mindstop_growth_record_meta, dateStr, timeStr, rec.energy)
            }
            row.addView(TextView(ctx).apply {
                text = metaText
                setTextColor(onSurfaceVariant)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(0, dp(4), 0, 0)
            })
            card.addView(row)
            container.addView(card)
        }
    }

    private fun buildCard(ctx: android.content.Context): MaterialCardView =
        MaterialCardView(ctx).apply {
            radius = dp(18).toFloat()
            cardElevation = 0f
            strokeWidth = dp(1)
            setCardBackgroundColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurfaceContainerLowest))
            strokeColor = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOutlineVariant)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }

    private fun appLabel(packageName: String): String = try {
        val pm = requireContext().packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    } catch (_: Exception) {
        packageName
    }

    private fun stageTitleRes(stage: GardenStage): Int = when (stage) {
        GardenStage.CLEARING -> R.string.mindstop_growth_stage_0
        GardenStage.SPROUTS -> R.string.mindstop_growth_stage_1
        GardenStage.GROVE -> R.string.mindstop_growth_stage_2
        GardenStage.FOREST_PATH -> R.string.mindstop_growth_stage_3
        GardenStage.SANCTUARY -> R.string.mindstop_growth_stage_4
    }

    private fun triggerLabelRes(trigger: RewardTrigger): Int = when (trigger) {
        RewardTrigger.ACTIVE_EXIT -> R.string.mindstop_growth_trigger_active
        RewardTrigger.INTENTION_DECLINE -> R.string.mindstop_growth_trigger_decline
        RewardTrigger.BLOCK_STOP -> R.string.mindstop_growth_trigger_block
        RewardTrigger.FOCUS_COMPLETE -> R.string.mindstop_growth_trigger_focus
        RewardTrigger.BEDTIME_ON_TIME -> R.string.mindstop_growth_trigger_bedtime_ontime
        RewardTrigger.BEDTIME_LATE_STOP -> R.string.mindstop_growth_trigger_bedtime_late
        RewardTrigger.MORNING_WAKE -> R.string.mindstop_growth_trigger_morning_wake
        RewardTrigger.SHOPPING_INTENTIONAL -> R.string.mindstop_growth_trigger_shop_intentional
        RewardTrigger.SHOPPING_BACKLOG -> R.string.mindstop_growth_trigger_shop_backlog
    }

    private fun statusLabelRes(status: RewardStatus): Int = when (status) {
        RewardStatus.PENDING -> R.string.mindstop_growth_status_pending
        RewardStatus.CONFIRMED -> R.string.mindstop_growth_status_confirmed
        RewardStatus.CANCELLED -> R.string.mindstop_growth_status_cancelled
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()
}
