package app.mindstop.service

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
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
import kotlin.math.abs
import neth.iecal.curbox.R

/**
 * Draggable, orientation-aware floating goal & countdown pill shown over a supervised app.
 *
 * - Users can freely drag the pill anywhere inside screen safe bounds so it never permanently
 *   blocks search bars (e.g. Bilibili / Xiaohongshu top search) or video controls.
 * - Remembers portrait and landscape positions independently in SharedPreferences.
 * - Short tap toggles the detail/action panel, which auto-positions inside screen bounds.
 * - Uses TYPE_ACCESSIBILITY_OVERLAY so no draw-over-apps permission is required.
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

    companion object {
        private const val PREFS_NAME = "mindstop_overlay_pos"
    }

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val handler = Handler(Looper.getMainLooper())
    private val density = context.resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private var model: Model? = null
    private var pill: TextView? = null
    private var pillParams: WindowManager.LayoutParams? = null
    private var pillPosition: OverlayPosition? = null
    private var lastOrientation: Int = -1
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
        val orientation = context.resources.configuration.orientation
        if (pill != null && lastOrientation != -1 && lastOrientation != orientation) {
            removePanel()
            removePill()
        }
        lastOrientation = orientation

        val view = ensurePill(m.settings.position) ?: return
        val countdown = remaining?.let { formatCountdown(it) }
        view.text = when (stage) {
            CountdownStage.GOAL -> m.goalSummary
            CountdownStage.STEADY -> if (m.showGoal && countdown != null) "${m.goalSummary}  $countdown" else countdown.orEmpty()
            else -> countdown.orEmpty()
        }
        val baseSize = when (m.settings.size) {
            OverlaySize.SMALL -> 12f
            OverlaySize.MEDIUM -> 14f
            OverlaySize.LARGE -> 18f
        }
        view.textSize = when (stage) {
            CountdownStage.FINAL -> baseSize * 2.4f
            CountdownStage.STRONG -> baseSize * 1.35f
            else -> baseSize
        }
        view.setTypeface(null, if (stage == CountdownStage.FINAL || stage == CountdownStage.STRONG) Typeface.BOLD else Typeface.NORMAL)
        (view.background as? GradientDrawable)?.setColor(colorFor(stage, m.settings.intensity))
        clampPillInsideScreen(view)
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

    private fun screenSize(): Pair<Int, Int> {
        val dm = context.resources.displayMetrics
        return dm.widthPixels.coerceAtLeast(320) to dm.heightPixels.coerceAtLeast(480)
    }

    private fun orientationKey(): String =
        if (context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) "land" else "port"

    private fun buildInitialPillParams(position: OverlayPosition): WindowManager.LayoutParams {
        val (sw, sh) = screenSize()
        val keyPrefix = "${position.name}_${orientationKey()}"
        val savedX = prefs.getFloat("${keyPrefix}_x", -1f)
        val savedY = prefs.getFloat("${keyPrefix}_y", -1f)
        val defaultX: Int
        val defaultY: Int
        if (savedX in 0f..1f && savedY in 0f..1f) {
            defaultX = (savedX * sw).toInt()
            defaultY = (savedY * sh).toInt()
        } else {
            when (position) {
                OverlayPosition.TOP -> {
                    // Tucked right of center below status bar to stay clear of top search inputs.
                    defaultX = (sw * 0.45f).toInt().coerceAtLeast(dp(12))
                    defaultY = if (orientationKey() == "land") dp(14) else dp(88)
                }
                OverlayPosition.MIDDLE -> {
                    defaultX = (sw * 0.58f).toInt().coerceAtLeast(dp(12))
                    defaultY = (sh * 0.44f).toInt()
                }
                OverlayPosition.BOTTOM -> {
                    defaultX = (sw * 0.30f).toInt().coerceAtLeast(dp(12))
                    defaultY = (sh - dp(128)).coerceAtLeast(dp(64))
                }
            }
        }
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = defaultX.coerceIn(dp(8), (sw - dp(80)).coerceAtLeast(dp(8)))
            y = defaultY.coerceIn(dp(12), (sh - dp(64)).coerceAtLeast(dp(12)))
        }
    }

    private fun clampPillInsideScreen(view: TextView) {
        val lp = pillParams ?: return
        val (sw, sh) = screenSize()
        val w = view.width.takeIf { it > 0 } ?: dp(140)
        val h = view.height.takeIf { it > 0 } ?: dp(38)
        val minY = if (orientationKey() == "land") dp(10) else dp(32)
        val maxX = (sw - w - dp(8)).coerceAtLeast(dp(8))
        val maxY = (sh - h - dp(24)).coerceAtLeast(minY)
        val clampedX = lp.x.coerceIn(dp(8), maxX)
        val clampedY = lp.y.coerceIn(minY, maxY)
        if (clampedX != lp.x || clampedY != lp.y) {
            lp.x = clampedX
            lp.y = clampedY
            try {
                windowManager.updateViewLayout(view, lp)
            } catch (_: Exception) {
            }
        }
    }

    private fun savePillPosition(position: OverlayPosition, lp: WindowManager.LayoutParams) {
        val (sw, sh) = screenSize()
        val keyPrefix = "${position.name}_${orientationKey()}"
        prefs.edit()
            .putFloat("${keyPrefix}_x", (lp.x.toFloat() / sw.toFloat()).coerceIn(0f, 1f))
            .putFloat("${keyPrefix}_y", (lp.y.toFloat() / sh.toFloat()).coerceIn(0f, 1f))
            .apply()
    }

    @SuppressLint("SetTextI18n", "ClickableViewAccessibility")
    private fun ensurePill(position: OverlayPosition): TextView? {
        pill?.let { existing ->
            if (pillPosition == position) return existing
            removePill()
        }
        val params = buildInitialPillParams(position)
        val view = TextView(context).apply {
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(dp(14), dp(7), dp(14), dp(7))
            maxLines = 1
            maxWidth = (screenSize().first * 0.72f).toInt().coerceAtLeast(dp(160))
            ellipsize = android.text.TextUtils.TruncateAt.END
            background = GradientDrawable().apply { cornerRadius = dp(20).toFloat() }
        }

        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var dragging = false

        view.setOnTouchListener { v, event ->
            val lp = pillParams ?: return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = lp.x
                    startY = lp.y
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downRawX).toInt()
                    val dy = (event.rawY - downRawY).toInt()
                    if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        dragging = true
                        removePanel()
                    }
                    if (dragging) {
                        val (sw, sh) = screenSize()
                        val w = v.width.takeIf { it > 0 } ?: dp(140)
                        val h = v.height.takeIf { it > 0 } ?: dp(38)
                        val minY = if (orientationKey() == "land") dp(10) else dp(32)
                        lp.x = (startX + dx).coerceIn(dp(8), (sw - w - dp(8)).coerceAtLeast(dp(8)))
                        lp.y = (startY + dy).coerceIn(minY, (sh - h - dp(24)).coerceAtLeast(minY))
                        try {
                            windowManager.updateViewLayout(v, lp)
                        } catch (_: Exception) {
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        savePillPosition(position, lp)
                    } else if (event.actionMasked == MotionEvent.ACTION_UP) {
                        togglePanel()
                    }
                    dragging = false
                    true
                }
                else -> false
            }
        }

        return try {
            windowManager.addView(view, params)
            pill = view
            pillParams = params
            pillPosition = position
            view.post { clampPillInsideScreen(view) }
            view
        } catch (_: Exception) {
            null
        }
    }

    private fun removePill() {
        val current = pill ?: return
        pill = null
        pillParams = null
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
        val (sw, sh) = screenSize()
        val lpPill = pillParams
        val panelParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            if (lpPill != null && lpPill.y > sh / 2) {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                y = (sh - lpPill.y + dp(8)).coerceIn(dp(48), (sh / 2).coerceAtLeast(dp(64)))
            } else {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                val topBase = (lpPill?.y ?: dp(88)) + dp(46)
                y = topBase.coerceIn(dp(48), (sh - dp(240)).coerceAtLeast(dp(48)))
            }
        }
        box.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                removePanel()
                true
            } else {
                false
            }
        }
        try {
            windowManager.addView(box, panelParams)
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
