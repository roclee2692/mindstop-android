package app.mindstop.ui

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.format.DateFormat
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.doAfterTextChanged
import app.mindstop.core.MorningActivationEngine
import app.mindstop.core.MorningRuntimeMeta
import app.mindstop.core.MorningSessionRecord
import app.mindstop.core.MorningV2Settings
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.Date
import neth.iecal.curbox.R

/**
 * Intentional Extended Sleep picker with a 3-second press-and-hold confirmation bar
 * (inspired by Simple Alarm Clock / one sec intentional friction: adds mild conscious cost
 * without punishing the user with math quizzes or vocabulary tests).
 */
object MorningExtendedSleepDialog {

    @SuppressLint("ClickableViewAccessibility")
    fun show(
        context: Context,
        session: MorningSessionRecord,
        meta: MorningRuntimeMeta,
        settings: MorningV2Settings,
        onConfirmedMinutes: (Int) -> Unit
    ) {
        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        var selectedMinutes = 15
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(8))
        }

        val intro = TextView(context).apply {
            setText(R.string.mindstop_morning_extend_dialog_desc)
            setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant))
        }
        root.addView(intro)

        val presetRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.topMargin = dp(12)
            layoutParams = lp
        }

        val previewText = TextView(context).apply {
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.topMargin = dp(12)
            layoutParams = lp
            setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

        val customInput = EditText(context).apply {
            hint = context.getString(R.string.mindstop_morning_extend_custom_hint)
            inputType = InputType.TYPE_CLASS_NUMBER
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.topMargin = dp(8)
            layoutParams = lp
        }

        val holdProgress = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8))
            lp.topMargin = dp(14)
            layoutParams = lp
        }

        val holdBtn = MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            setText(R.string.mindstop_morning_extend_hold_btn)
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52))
            lp.topMargin = dp(6)
            layoutParams = lp
        }

        val timeFmt = DateFormat.getTimeFormat(context)
        fun refreshPreview() {
            val now = System.currentTimeMillis()
            val target = MorningActivationEngine.computeExtendedSleepTargetWall(session, meta, selectedMinutes, settings, now)
            if (target == null) {
                previewText.setText(R.string.mindstop_morning_extend_capped)
                holdBtn.isEnabled = false
            } else {
                val actualMin = (((target - now) + 30_000L) / 60_000L).toInt().coerceAtLeast(1)
                previewText.text = context.getString(
                    R.string.mindstop_morning_extend_preview,
                    timeFmt.format(Date(target)),
                    actualMin
                )
                holdBtn.isEnabled = true
            }
        }

        val presets = listOf(15, 30, 60)
        for (mins in presets) {
            val btn = MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = context.getString(R.string.mindstop_minutes_format, mins)
                val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                lp.marginEnd = dp(6)
                layoutParams = lp
                setOnClickListener {
                    selectedMinutes = mins
                    customInput.setText("")
                    refreshPreview()
                }
            }
            presetRow.addView(btn)
        }

        customInput.doAfterTextChanged { text ->
            val parsed = text?.toString()?.trim()?.toIntOrNull()
            if (parsed != null && parsed in 5..180) {
                selectedMinutes = parsed
                refreshPreview()
            }
        }

        root.addView(presetRow)
        root.addView(customInput)
        root.addView(previewText)
        root.addView(holdProgress)
        root.addView(holdBtn)
        refreshPreview()

        val dialog: AlertDialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.mindstop_morning_extend_dialog_title)
            .setView(root)
            .setNegativeButton(R.string.cancel, null)
            .create()

        val handler = Handler(Looper.getMainLooper())
        var holdStart = 0L
        var holding = false
        var confirmed = false
        val tick = object : Runnable {
            override fun run() {
                if (!holding || confirmed) return
                val elapsed = System.currentTimeMillis() - holdStart
                val pct = ((elapsed * 100L) / MorningActivationEngine.EXTENDED_SLEEP_HOLD_MS).toInt().coerceIn(0, 100)
                holdProgress.progress = pct
                if (elapsed >= MorningActivationEngine.EXTENDED_SLEEP_HOLD_MS) {
                    confirmed = true
                    holding = false
                    dialog.dismiss()
                    onConfirmedMinutes(selectedMinutes)
                } else {
                    handler.postDelayed(this, 50L)
                }
            }
        }

        holdBtn.setOnTouchListener { v, event ->
            if (!v.isEnabled) return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    holding = true
                    holdStart = System.currentTimeMillis()
                    holdProgress.progress = 0
                    handler.removeCallbacks(tick)
                    handler.post(tick)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    holding = false
                    handler.removeCallbacks(tick)
                    if (!confirmed) holdProgress.progress = 0
                    true
                }
                else -> false
            }
        }
        dialog.setOnDismissListener {
            holding = false
            handler.removeCallbacks(tick)
        }
        dialog.show()
    }
}
