package app.mindstop.service

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import app.mindstop.core.CountdownStage
import app.mindstop.core.IntentionSettings
import app.mindstop.core.OverlayIntensity
import app.mindstop.core.OverlayPosition
import app.mindstop.core.OverlaySize
import app.mindstop.core.SessionCountdown
import neth.iecal.curbox.R

/**
 * Small floating pill shown over a supervised app: a short goal summary, and a countdown once the
 * plan or the quota gets close to its end. Tapping it opens a panel with the full goal, the search
 * words, and buttons to copy them, finish the goal, or end the session.
 *
 * Uses TYPE_ACCESSIBILITY_OVERLAY, so no draw over apps permission is needed. Only the pill and
 * the panel take touches; the rest of the screen stays usable. Must be used on the main thread.
 */
class MindstopSessionOverlay(private val context: Context, private val actions: Actions) {

    interface Actions {
        fun onGoalDone()
        fun onEndSession()
        fun onSwitchToFun() {}
    }

    data class Model(
        val goal: String,
        val goalSummary: String,
        val keywords: String,
        val showGoal: Boolean,
        /** Elapsed realtime when the countdown reaches zero, or null when nothing runs out. */
        val endElapsed: Long?,
        val settings: IntentionSettings,
        val isStudyMode: Boolean = false
    )

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private val density = context.resources.displayMetrics.density

    private var model: Model? = null
    private var pill: TextView? = null
    private var pillPosition: OverlayPosition? = null
    private var panel: View? = null

    private val ticker = object : Runnable {
        override fun run() {
            render()
            if (model != null) handler.postDelayed(this, 1_000L)
        }
    }

    /** True while the pill or the panel is on screen. */
    val isShowing: Boolean get() = pill != null || panel != null

    fun update(next: Model) {
        val wasRunning = model != null
        model = next
        if (panel != null) refreshPanel()
        render()
        if (!wasRunning) handler.postDelayed(ticker, 1_000L)
    }

    fun hide() {
        handler.removeCallbacks(ticker)
        model = null
        removePanel()
        removePill()
    }

    private fun remainingMs(m: Model): Long? =
        m.endElapsed?.let { (it - SystemClock.elapsedRealtime()).coerceAtLeast(0L) }

    private fun render() {
        val m = model ?: return
        val remaining = remainingMs(m)
        val stage = SessionCountdown.stage(remaining, m.showGoal)
        if (stage == CountdownStage.HIDDEN) {
            if (panel == null) removePill()
            return
        }
        val view = ensurePill(m.settings.position) ?: return
        val countdown = remaining?.let { formatCountdown(it) }
        view.text = when (stage) {
            CountdownStage.GOAL -> m.goalSummary
            CountdownStage.STEADY -> if (m.showGoal && countdown != null) "${m.goalSummary}  $countdown" else countdown.orEmpty()
            else -> countdown.orEmpty()
        }
        val baseSize = when (m.settings.size) {
            OverlaySize.SMALL -> 12f
            OverlaySize.MEDIUM -> 15f
            OverlaySize.LARGE -> 19f
        }
        view.textSize = when (stage) {
            CountdownStage.FINAL -> baseSize * 2.4f
            CountdownStage.STRONG -> baseSize * 1.35f
            else -> baseSize
        }
        view.setTypeface(null, if (stage == CountdownStage.FINAL || stage == CountdownStage.STRONG) Typeface.BOLD else Typeface.NORMAL)
        (view.background as? GradientDrawable)?.setColor(colorFor(stage, m.settings.intensity))
    }

    private fun colorFor(stage: CountdownStage, intensity: OverlayIntensity): Int {
        val alpha = when (intensity) {
            OverlayIntensity.LIGHT -> 0x8C
            OverlayIntensity.NORMAL -> 0xCC
            OverlayIntensity.STRONG -> 0xF2
        }
        val rgb = when (stage) {
            CountdownStage.FINAL -> 0xB83834
            CountdownStage.STRONG -> 0xB8620A
            else -> 0x1A3D2E
        }
        return (alpha shl 24) or rgb
    }

    private fun formatCountdown(ms: Long): String {
        val totalSeconds = (ms + 999L) / 1000L
        return if (totalSeconds <= 10L) totalSeconds.toString()
        else "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
    }

    private fun dp(value: Int): Int = (value * density).toInt()

    private fun baseParams(position: OverlayPosition, yOffset: Int) = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    ).apply {
        when (position) {
            OverlayPosition.TOP -> {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = dp(40) + yOffset
            }
            OverlayPosition.MIDDLE -> {
                gravity = Gravity.CENTER_VERTICAL or Gravity.END
                x = dp(8)
                y = yOffset
            }
            OverlayPosition.BOTTOM -> {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                y = dp(96) + yOffset
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun ensurePill(position: OverlayPosition): TextView? {
        pill?.let { existing ->
            if (pillPosition == position) return existing
            removePill()
        }
        val view = TextView(context).apply {
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(dp(16), dp(8), dp(16), dp(8))
            maxLines = 1
            background = GradientDrawable().apply { cornerRadius = dp(20).toFloat() }
            setOnClickListener { togglePanel() }
        }
        return try {
            windowManager.addView(view, baseParams(position, 0))
            pill = view
            pillPosition = position
            view
        } catch (_: Exception) {
            null
        }
    }

    private fun removePill() {
        val current = pill ?: return
        pill = null
        pillPosition = null
        try {
            windowManager.removeView(current)
        } catch (_: Exception) {
        }
    }

    private fun togglePanel() {
        if (panel != null) removePanel() else showPanel()
    }

    private fun showPanel() {
        val m = model ?: return
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(0xF2173427.toInt())
                setStroke(dp(1), 0x667CDAA9)
            }
        }
        fillPanel(box, m)
        val position = m.settings.position
        val offset = when (position) {
            OverlayPosition.TOP -> dp(48)
            OverlayPosition.MIDDLE -> 0
            OverlayPosition.BOTTOM -> dp(48)
        }
        try {
            windowManager.addView(box, baseParams(position, offset))
            panel = box
        } catch (_: Exception) {
        }
    }

    private fun refreshPanel() {
        val box = panel as? LinearLayout ?: return
        val m = model ?: return
        box.removeAllViews()
        fillPanel(box, m)
    }

    private fun fillPanel(box: LinearLayout, m: Model) {
        box.addView(label(
            if (m.goal.isBlank()) context.getString(R.string.mindstop_overlay_no_goal)
            else context.getString(R.string.mindstop_overlay_goal, m.goal),
            bold = true
        ))
        if (m.keywords.isNotBlank()) {
            box.addView(label(context.getString(R.string.mindstop_overlay_keywords, m.keywords), bold = false))
            box.addView(action(R.string.mindstop_overlay_copy) { copyKeywords(m.keywords) })
        }
        if (m.goal.isNotBlank()) {
            box.addView(action(R.string.mindstop_overlay_done) {
                removePanel()
                actions.onGoalDone()
            })
        }
        if (m.isStudyMode) {
            box.addView(action(R.string.mindstop_study_switch_to_fun) {
                removePanel()
                actions.onSwitchToFun()
            })
        }
        box.addView(action(R.string.mindstop_overlay_end) {
            removePanel()
            actions.onEndSession()
        })
        box.addView(action(R.string.mindstop_overlay_close) { removePanel() })
    }

    private fun label(text: String, bold: Boolean) = TextView(context).apply {
        this.text = text
        setTextColor(0xFFFFFFFF.toInt())
        textSize = 15f
        maxWidth = dp(300)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun action(textRes: Int, onClick: () -> Unit) = TextView(context).apply {
        setText(textRes)
        setTextColor(0xFF7CDAA9.toInt())
        textSize = 15f
        setPadding(0, dp(10), 0, dp(10))
        setOnClickListener { onClick() }
    }

    private fun copyKeywords(keywords: String) {
        try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Mindstop", keywords))
            Toast.makeText(context, R.string.mindstop_copied, Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
        }
    }

    private fun removePanel() {
        val current = panel ?: return
        panel = null
        try {
            windowManager.removeView(current)
        } catch (_: Exception) {
        }
        render()
    }
}
