package app.mindstop.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import app.mindstop.core.ActiveFocusState
import app.mindstop.core.DayKeys
import app.mindstop.core.FocusConfig
import app.mindstop.core.FocusEngine
import app.mindstop.core.FocusStatus
import app.mindstop.core.RewardTrigger
import app.mindstop.data.FocusSessionEntity
import app.mindstop.service.QuotaRepository
import app.mindstop.service.Stage5Repository
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import neth.iecal.curbox.R
import neth.iecal.curbox.databinding.ActivityMindstopFocusBinding
import neth.iecal.curbox.ui.activity.SelectAppsActivity

/**
 * Elastic Focus Mode screen (SPEC §15):
 * custom focus duration, cumulative interrupt budget, learning tool whitelist, live pause/resume
 * countdown, anti-arbitrage early-return praise, and recent focus history.
 */
class MindstopFocusActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMindstopFocusBinding
    private lateinit var quotaRepo: QuotaRepository
    private lateinit var stage5: Stage5Repository

    private var whitelist: Set<String> = emptySet()
    private var activeState: ActiveFocusState? = null
    private var tickInFlight = false
    private var uiTickCounter = 0
    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            tickActiveOnUi()
            handler.postDelayed(this, 1_000L)
        }
    }

    private val appsPicker = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val apps = result.data?.getStringArrayListExtra("SELECTED_APPS") ?: return@registerForActivityResult
        whitelist = apps.toSet()
        renderWhitelistSummary()
        lifecycleScope.launch(Dispatchers.IO) { stage5.saveFocusWhitelist(whitelist) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMindstopFocusBinding.inflate(layoutInflater)
        setContentView(binding.root)
        MindstopUi.applySystemBarInsets(this, binding.focusRoot)
        MindstopUi.setupToolbar(this, binding.focusToolbar, R.string.mindstop_focus_title)

        quotaRepo = QuotaRepository(this)
        stage5 = Stage5Repository(this)

        binding.focusDurationInput.setText(FocusConfig.DEFAULT_FOCUS_MINUTES.toString())
        binding.focusInterruptInput.setText(FocusConfig.DEFAULT_INTERRUPT_MINUTES.toString())

        bindPresetChip(binding.focusPreset25, 25, binding.focusDurationInput)
        bindPresetChip(binding.focusPreset45, 45, binding.focusDurationInput)
        bindPresetChip(binding.focusPreset60, 60, binding.focusDurationInput)
        bindPresetChip(binding.focusPreset90, 90, binding.focusDurationInput)

        bindPresetChip(binding.focusInt5, 5, binding.focusInterruptInput)
        bindPresetChip(binding.focusInt10, 10, binding.focusInterruptInput)
        bindPresetChip(binding.focusInt15, 15, binding.focusInterruptInput)
        bindPresetChip(binding.focusInt20, 20, binding.focusInterruptInput)

        binding.focusPickWhitelist.setOnClickListener {
            val pick = Intent(this, SelectAppsActivity::class.java).apply {
                putStringArrayListExtra("PRE_SELECTED_APPS", ArrayList(whitelist))
            }
            appsPicker.launch(pick)
        }
        binding.focusStartBtn.setOnClickListener { startFocus() }
        binding.focusTogglePauseBtn.setOnClickListener { togglePause() }
        binding.focusAbandonBtn.setOnClickListener { abandonFocus() }

        lifecycleScope.launch {
            whitelist = withContext(Dispatchers.IO) { stage5.loadFocusWhitelist() }
            renderWhitelistSummary()
            refreshActiveState()
        }

        lifecycleScope.launch {
            stage5.observeRecentFocus(10).collectLatest { rows ->
                renderRecent(rows)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch { refreshActiveState() }
        handler.postDelayed(ticker, 1_000L)
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    private fun bindPresetChip(
        chip: com.google.android.material.chip.Chip,
        minutes: Int,
        input: com.google.android.material.textfield.TextInputEditText
    ) {
        chip.text = getString(R.string.mindstop_minutes_format, minutes)
        chip.setOnClickListener { input.setText(minutes.toString()) }
    }

    private fun renderWhitelistSummary() {
        binding.focusWhitelistSummary.text = getString(R.string.mindstop_focus_whitelist_count, whitelist.size)
    }

    private suspend fun refreshActiveState() {
        val loaded = withContext(Dispatchers.IO) { stage5.loadActiveFocus() }
        activeState = loaded
        renderState(loaded)
    }

    private fun tickActiveOnUi() {
        val current = activeState ?: return
        if (!current.isActive || tickInFlight) return
        tickInFlight = true
        uiTickCounter++
        lifecycleScope.launch {
            try {
                val now = System.currentTimeMillis()
                val result = FocusEngine.tick(current, now)
                if (result.justCompleted) {
                    val fresh = withContext(Dispatchers.IO) { stage5.loadActiveFocus() }
                    if (fresh != null && fresh.isActive) {
                        withContext(Dispatchers.IO) { stage5.finishFocusSession(result.state, now) }
                        Toast.makeText(
                            this@MindstopFocusActivity,
                            getString(R.string.mindstop_focus_completed_toast, RewardTrigger.FOCUS_COMPLETE.defaultEnergy),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    activeState = null
                    renderState(null)
                } else {
                    activeState = result.state
                    if (uiTickCounter % 15 == 0) {
                        withContext(Dispatchers.IO) { stage5.saveActiveFocus(result.state) }
                    }
                    renderState(result.state)
                }
            } finally {
                tickInFlight = false
            }
        }
    }

    private fun startFocus() {
        val focusMin = binding.focusDurationInput.text?.toString()?.trim()?.toIntOrNull()
        val intMin = binding.focusInterruptInput.text?.toString()?.trim()?.toIntOrNull()
        if (focusMin == null || focusMin !in FocusConfig.MIN_FOCUS_MINUTES..FocusConfig.MAX_FOCUS_MINUTES ||
            intMin == null || intMin !in 0..FocusConfig.MAX_INTERRUPT_MINUTES
        ) {
            Toast.makeText(this, R.string.mindstop_focus_invalid, Toast.LENGTH_SHORT).show()
            return
        }
        val config = FocusConfig(
            goal = binding.focusGoalInput.text?.toString()?.trim().orEmpty(),
            targetFocusMinutes = focusMin,
            interruptBudgetMinutes = intMin,
            whitelistPackages = whitelist
        )
        lifecycleScope.launch {
            val started = withContext(Dispatchers.IO) {
                val now = quotaRepo.approxTrustedNow()
                val state = FocusEngine.start(config, now, DayKeys.dayKey(now))
                stage5.saveActiveFocus(state)
                state
            }
            activeState = started
            renderState(started)
        }
    }

    private fun togglePause() {
        val current = activeState ?: return
        lifecycleScope.launch {
            val now = withContext(Dispatchers.IO) { quotaRepo.approxTrustedNow() }
            if (current.status == FocusStatus.FOCUSING) {
                val paused = FocusEngine.pauseForInterrupt(current, now)
                withContext(Dispatchers.IO) { stage5.saveActiveFocus(paused) }
                activeState = paused
                renderState(paused)
            } else if (current.status == FocusStatus.PAUSED) {
                val resumed = FocusEngine.resumeFromInterrupt(current, now)
                withContext(Dispatchers.IO) { stage5.saveActiveFocus(resumed.state) }
                activeState = resumed.state
                renderState(resumed.state)
                if (resumed.showEarlyReturnPraise) {
                    Toast.makeText(this@MindstopFocusActivity, R.string.mindstop_focus_early_return_praise, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun abandonFocus() {
        val current = activeState ?: return
        lifecycleScope.launch {
            val completed = withContext(Dispatchers.IO) {
                val now = quotaRepo.approxTrustedNow()
                val ended = FocusEngine.abandon(current, now)
                stage5.finishFocusSession(ended, now)
            }
            activeState = null
            renderState(null)
            if (completed) {
                Toast.makeText(
                    this@MindstopFocusActivity,
                    getString(R.string.mindstop_focus_completed_toast, RewardTrigger.FOCUS_COMPLETE.defaultEnergy),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun renderState(state: ActiveFocusState?) {
        val active = state != null && state.isActive
        binding.focusSetupPanel.visibility = if (active) View.GONE else View.VISIBLE
        binding.focusActiveCard.visibility = if (active) View.VISIBLE else View.GONE
        if (!active || state == null) return

        binding.focusActiveStatus.setText(
            if (state.status == FocusStatus.PAUSED) R.string.mindstop_focus_paused_label
            else R.string.mindstop_focus_running_label
        )
        if (state.goal.isNotBlank()) {
            binding.focusActiveGoal.visibility = View.VISIBLE
            binding.focusActiveGoal.text = getString(R.string.mindstop_overlay_goal, state.goal)
        } else {
            binding.focusActiveGoal.visibility = View.GONE
        }
        binding.focusActiveRemaining.text = getString(
            R.string.mindstop_focus_remaining,
            formatCountdown(state.focusRemainingMs)
        )
        binding.focusActiveInterruptRemaining.text = getString(
            R.string.mindstop_focus_interrupt_remaining,
            formatCountdown(state.interruptRemainingMs)
        )
        binding.focusTogglePauseBtn.setText(
            if (state.status == FocusStatus.PAUSED) R.string.mindstop_focus_resume_btn
            else R.string.mindstop_focus_pause_btn
        )
    }

    private fun renderRecent(rows: List<FocusSessionEntity>) {
        val container = binding.focusRecentContainer
        container.removeAllViews()
        val onSurface = MaterialColors.getColor(container, com.google.android.material.R.attr.colorOnSurface)
        val onVariant = MaterialColors.getColor(container, com.google.android.material.R.attr.colorOnSurfaceVariant)
        if (rows.isEmpty()) {
            container.addView(TextView(this).apply {
                setText(R.string.mindstop_focus_recent_empty)
                setTextColor(onVariant)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            })
            return
        }
        for (row in rows) {
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
                setPadding(dp(16), dp(12), dp(16), dp(12))
            }
            val statusLabel = if (row.status == FocusStatus.COMPLETED.code) {
                getString(R.string.mindstop_focus_status_completed)
            } else {
                getString(R.string.mindstop_focus_status_abandoned)
            }
            val titleText = if (row.goal.isNotBlank()) "${row.goal} · $statusLabel" else statusLabel
            box.addView(TextView(this).apply {
                text = titleText
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(onSurface)
            })
            val metaText = "${row.dayKey} · ${MindstopUi.formatDuration(this, row.focusedMs)} / ${MindstopUi.formatDuration(this, row.targetFocusMs)}"
            box.addView(TextView(this).apply {
                text = metaText
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(onVariant)
                setPadding(0, dp(4), 0, 0)
            })
            card.addView(box)
            container.addView(card)
        }
    }

    private fun formatCountdown(ms: Long): String {
        val totalSec = (ms / 1_000L).coerceAtLeast(0L)
        val hours = totalSec / 3600L
        val mins = (totalSec % 3600L) / 60L
        val secs = totalSec % 60L
        return if (hours > 0L) "%d:%02d:%02d".format(hours, mins, secs) else "%02d:%02d".format(mins, secs)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
