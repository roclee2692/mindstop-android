package app.mindstop.ui

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import android.provider.Settings
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import app.mindstop.core.QuotaRule
import app.mindstop.core.RuleScope
import com.google.android.material.appbar.MaterialToolbar
import neth.iecal.curbox.R

/** Shared visual, inset, formatting, and Xiaomi HyperOS helpers for Mindstop screens. */
object MindstopUi {

    private const val OP_MIUI_BACKGROUND_START_ACTIVITY = 10021

    /**
     * Enables edge to edge and pads [root] for status bar, navigation bar, and IME without losing
     * the padding already declared in XML.
     */
    fun applySystemBarInsets(activity: ComponentActivity, root: View) {
        activity.enableEdgeToEdge()
        val left = root.paddingLeft
        val top = root.paddingTop
        val right = root.paddingRight
        val bottom = root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(
                left + bars.left,
                top + bars.top,
                right + bars.right,
                bottom + maxOf(bars.bottom, ime.bottom)
            )
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    fun setupToolbar(activity: AppCompatActivity, toolbar: MaterialToolbar, titleRes: Int) {
        toolbar.setTitle(titleRes)
        toolbar.setNavigationIcon(R.drawable.ic_back_arrow)
        toolbar.setNavigationOnClickListener { activity.finish() }
    }

    fun formatDuration(context: Context, ms: Long): String {
        val totalMinutes = (ms + 30_000L) / 60_000L
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return if (hours > 0) {
            context.getString(R.string.mindstop_duration_hm, hours, minutes)
        } else {
            context.getString(R.string.mindstop_minutes_format, minutes)
        }
    }

    fun ruleDisplayTitle(context: Context, rule: QuotaRule): String =
        rule.name.ifBlank {
            when (rule.scope) {
                RuleScope.GLOBAL -> context.getString(R.string.mindstop_scope_global)
                RuleScope.GROUP -> context.getString(R.string.mindstop_scope_group, rule.packages.size)
                RuleScope.APP -> context.getString(R.string.mindstop_scope_app)
            }
        }

    fun ruleDetailLine(context: Context, rule: QuotaRule): String {
        val parts = ArrayList<String>()
        rule.dailyLimitMs?.let { parts.add(context.getString(R.string.mindstop_rule_daily_part, formatDuration(context, it))) }
        rule.sessionLimitMs?.let { parts.add(context.getString(R.string.mindstop_rule_session_part, formatDuration(context, it))) }
        val scope = when (rule.scope) {
            RuleScope.GLOBAL -> context.getString(R.string.mindstop_scope_global)
            RuleScope.GROUP -> context.getString(R.string.mindstop_scope_group, rule.packages.size)
            RuleScope.APP -> context.getString(R.string.mindstop_scope_app)
        }
        if (rule.name.isNotBlank()) parts.add(scope)
        val suffix = if (rule.enabled) "" else context.getString(R.string.mindstop_rule_disabled)
        return parts.joinToString(" · ") + suffix
    }

    fun isXiaomiFamily(): Boolean {
        val maker = Build.MANUFACTURER.lowercase()
        val brand = Build.BRAND.lowercase()
        return listOf("xiaomi", "redmi", "poco").any { maker.contains(it) || brand.contains(it) }
    }

    /**
     * Checks Xiaomi MIUI / HyperOS "Display pop up windows while running in the background"
     * permission (op 10021). Returns null when unavailable or on non Xiaomi devices.
     */
    fun isMiuiBackgroundPopupAllowed(context: Context): Boolean? {
        if (!isXiaomiFamily()) return null
        return try {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val method = AppOpsManager::class.java.getMethod(
                "checkOpNoThrow",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                String::class.java
            )
            val mode = method.invoke(appOps, OP_MIUI_BACKGROUND_START_ACTIVITY, Process.myUid(), context.packageName) as Int
            mode == AppOpsManager.MODE_ALLOWED
        } catch (_: Exception) {
            null
        }
    }

    fun openMiuiPermissionsIntent(context: Context): Intent {
        val miui = Intent("miui.intent.action.APP_PERM_EDITOR").apply {
            setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity")
            putExtra("extra_pkgname", context.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (context.packageManager.resolveActivity(miui, 0) != null) return miui
        val miuiActionOnly = Intent("miui.intent.action.APP_PERM_EDITOR").apply {
            putExtra("extra_pkgname", context.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (context.packageManager.resolveActivity(miuiActionOnly, 0) != null) return miuiActionOnly
        return Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri()).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}
