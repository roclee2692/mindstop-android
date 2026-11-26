package app.mindstop.service

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Non touchable banner shown from the accessibility service for progressive reminders and
 * immediate exit reward feedback. Uses TYPE_ACCESSIBILITY_OVERLAY so it works without the draw
 * over apps permission. Plain Android views only (no Material components in service context).
 */
class MindstopReminderOverlay(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private var view: View? = null

    private val dismisser = Runnable { hide() }

    @SuppressLint("InflateParams")
    fun show(text: String, holdMs: Long = HOLD_MS, belowPill: Boolean = false) {
        hide()
        val density = context.resources.displayMetrics.density
        val banner = TextView(context).apply {
            this.text = text
            textSize = 14f
            setPadding((18 * density).toInt(), (11 * density).toInt(), (18 * density).toInt(), (11 * density).toInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 20 * density
                setColor(0xEB1A3D2E.toInt())
                setStroke((1 * density).toInt(), 0x807CDAA9.toInt())
            }
            setTextColor(0xFFF3FBF6.toInt())
        }
        attach(banner, holdMs, belowPill)
    }

    /** Crisp, warm two-line achievement feedback banner shown immediately on voluntary exit. */
    @SuppressLint("InflateParams")
    fun showReward(title: String, subtitle: String, holdMs: Long = REWARD_HOLD_MS) {
        hide()
        val density = context.resources.displayMetrics.density
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * density).toInt(), (13 * density).toInt(), (20 * density).toInt(), (13 * density).toInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 22 * density
                setColor(0xF2153A2A.toInt())
                setStroke((1.5f * density).toInt().coerceAtLeast(1), 0xCC6CE0A8.toInt())
            }
            alpha = 0f
        }
        card.addView(TextView(context).apply {
            text = title
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setTextColor(0xFF9AF0C5.toInt())
        })
        if (subtitle.isNotBlank()) {
            card.addView(TextView(context).apply {
                text = subtitle
                textSize = 13f
                setPadding(0, (4 * density).toInt(), 0, 0)
                setTextColor(0xFFEAF7EF.toInt())
            })
        }
        attach(card, holdMs, belowPill = false)
        card.animate().alpha(1f).setDuration(220L).start()
    }

    /** Low-brightness, quiet evening wind-down reminder banner (v0.3 §5–§6). */
    @SuppressLint("InflateParams")
    fun showNightReminder(text: String, holdMs: Long = HOLD_MS, belowPill: Boolean = false) {
        hide()
        val density = context.resources.displayMetrics.density
        val banner = TextView(context).apply {
            this.text = text
            textSize = 13.5f
            setPadding((18 * density).toInt(), (11 * density).toInt(), (18 * density).toInt(), (11 * density).toInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 18 * density
                setColor(0xE0111B16.toInt())
                setStroke((1 * density).toInt(), 0x669DB9A8.toInt())
            }
            setTextColor(0xFFD5E3DA.toInt())
        }
        attach(banner, holdMs, belowPill)
    }

    /** Quiet, brief, low-brightness ginkgo reward card for bedtime wind-down (v0.3 §7). */
    @SuppressLint("InflateParams")
    fun showNightReward(title: String, subtitle: String, holdMs: Long = NIGHT_REWARD_HOLD_MS) {
        hide()
        val density = context.resources.displayMetrics.density
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((18 * density).toInt(), (12 * density).toInt(), (18 * density).toInt(), (12 * density).toInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 20 * density
                setColor(0xE6101B16.toInt())
                setStroke((1 * density).toInt(), 0x77C8B27A.toInt())
            }
            alpha = 0f
        }
        card.addView(TextView(context).apply {
            text = title
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(0xFFD8C48E.toInt())
        })
        if (subtitle.isNotBlank()) {
            card.addView(TextView(context).apply {
                text = subtitle
                textSize = 12.5f
                setPadding(0, (3 * density).toInt(), 0, 0)
                setTextColor(0xFFC2D4C8.toInt())
            })
        }
        attach(card, holdMs, belowPill = false)
        card.animate().alpha(0.92f).setDuration(300L).start()
    }

    private fun attach(target: View, holdMs: Long, belowPill: Boolean) {
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            // Leaves room for the session pill when it also sits at the top.
            y = if (belowPill) 320 else 120
        }
        try {
            windowManager.addView(target, params)
            view = target
            handler.postDelayed(dismisser, holdMs)
        } catch (_: Exception) {
        }
    }

    fun hide() {
        handler.removeCallbacks(dismisser)
        val current = view ?: return
        view = null
        try {
            windowManager.removeView(current)
        } catch (_: Exception) {
        }
    }

    companion object {
        const val HOLD_MS = 6_000L
        const val REWARD_HOLD_MS = 4_500L
        const val NIGHT_REWARD_HOLD_MS = 3_800L
    }
}
