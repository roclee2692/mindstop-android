package app.mindstop.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import app.mindstop.core.OemFamily
import app.mindstop.core.OemProfile
import neth.iecal.curbox.R
import neth.iecal.curbox.hardcoded.OemAutostartIntents
import neth.iecal.curbox.services.AppBlockerService
import neth.iecal.curbox.utils.PermissionUtils

/**
 * Device-aware, multi-fallback permission router.
 *
 * Every launch walks a prioritized chain (L1 direct 1-tap dialog / OEM deep link -> L2 OEM list ->
 * L3 App Info fallback), catches SecurityException on locked ROM activities (e.g. HyperOS / ColorOS),
 * and surfaces a concise brand-specific path tip so users never get lost in system settings.
 */
object OemPermissionRouter {

    enum class PermissionStep {
        ACCESSIBILITY,
        OVERLAY,
        BATTERY,
        BACKGROUND_POPUP,
        NOTIFICATIONS,
        EXACT_ALARM,
    }

    fun areNotificationsAllowed(context: Context): Boolean {
        val enabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
        if (!enabled) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        }
        return true
    }

    fun currentFamily(): OemFamily = OemProfile.classify(Build.MANUFACTURER, Build.BRAND)

    fun deviceDisplayLabel(context: Context): String {
        val familyName = when (currentFamily()) {
            OemFamily.XIAOMI -> context.getString(R.string.mindstop_oem_xiaomi)
            OemFamily.HUAWEI_HONOR -> context.getString(R.string.mindstop_oem_huawei)
            OemFamily.OPPO_FAMILY -> context.getString(R.string.mindstop_oem_oppo)
            OemFamily.VIVO_FAMILY -> context.getString(R.string.mindstop_oem_vivo)
            OemFamily.SAMSUNG -> context.getString(R.string.mindstop_oem_samsung)
            OemFamily.MEIZU -> context.getString(R.string.mindstop_oem_meizu)
            OemFamily.GENERIC -> context.getString(R.string.mindstop_oem_generic)
        }
        val model = Build.MODEL?.takeIf { it.isNotBlank() } ?: ""
        return if (model.isNotEmpty()) "$familyName ($model)" else familyName
    }

    fun batteryGuideText(context: Context): String = when (currentFamily()) {
        OemFamily.XIAOMI -> context.getString(R.string.mindstop_oem_tip_battery_xiaomi)
        OemFamily.HUAWEI_HONOR -> context.getString(R.string.mindstop_oem_tip_battery_huawei)
        OemFamily.OPPO_FAMILY -> context.getString(R.string.mindstop_oem_tip_battery_oppo)
        OemFamily.VIVO_FAMILY -> context.getString(R.string.mindstop_oem_tip_battery_vivo)
        OemFamily.SAMSUNG -> context.getString(R.string.mindstop_oem_tip_battery_samsung)
        OemFamily.MEIZU, OemFamily.GENERIC -> context.getString(R.string.mindstop_oem_tip_battery_generic)
    }

    fun accessibilityGuideText(context: Context): String = when (currentFamily()) {
        OemFamily.XIAOMI -> context.getString(R.string.mindstop_oem_tip_a11y_xiaomi)
        OemFamily.HUAWEI_HONOR -> context.getString(R.string.mindstop_oem_tip_a11y_huawei)
        OemFamily.OPPO_FAMILY -> context.getString(R.string.mindstop_oem_tip_a11y_oppo)
        OemFamily.VIVO_FAMILY -> context.getString(R.string.mindstop_oem_tip_a11y_vivo)
        OemFamily.SAMSUNG, OemFamily.MEIZU, OemFamily.GENERIC ->
            context.getString(R.string.mindstop_oem_tip_a11y_generic)
    }

    fun bgPopupGuideText(context: Context): String = when (currentFamily()) {
        OemFamily.XIAOMI -> context.getString(R.string.mindstop_oem_tip_bgpopup_xiaomi)
        OemFamily.VIVO_FAMILY -> context.getString(R.string.mindstop_oem_tip_bgpopup_vivo)
        else -> context.getString(R.string.mindstop_diag_bg_popup_help)
    }

    fun autostartGuideText(context: Context): String = when (currentFamily()) {
        OemFamily.XIAOMI -> context.getString(R.string.mindstop_oem_tip_autostart_xiaomi)
        OemFamily.HUAWEI_HONOR -> context.getString(R.string.mindstop_oem_tip_autostart_huawei)
        OemFamily.OPPO_FAMILY -> context.getString(R.string.mindstop_oem_tip_autostart_oppo)
        OemFamily.VIVO_FAMILY -> context.getString(R.string.mindstop_oem_tip_autostart_vivo)
        else -> context.getString(R.string.mindstop_oem_tip_autostart_generic)
    }

    fun pendingSteps(context: Context, includeExactAlarm: Boolean): List<PermissionStep> {
        val list = ArrayList<PermissionStep>()
        val a11yOn = PermissionUtils.isAccessibilityServiceEnabled(context, AppBlockerService::class.java)
        if (!a11yOn) list += PermissionStep.ACCESSIBILITY

        if (!Settings.canDrawOverlays(context)) list += PermissionStep.OVERLAY

        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(context.packageName)) list += PermissionStep.BATTERY

        if (currentFamily() == OemFamily.XIAOMI && MindstopUi.isMiuiBackgroundPopupAllowed(context) == false) {
            list += PermissionStep.BACKGROUND_POPUP
        }

        if (!areNotificationsAllowed(context)) {
            list += PermissionStep.NOTIFICATIONS
        }

        if (includeExactAlarm && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            !app.mindstop.service.MindstopAlarmScheduler.canScheduleExact(context)
        ) {
            list += PermissionStep.EXACT_ALARM
        }
        return list
    }

    fun stepTitle(context: Context, step: PermissionStep): String = when (step) {
        PermissionStep.ACCESSIBILITY -> context.getString(R.string.mindstop_diag_accessibility)
        PermissionStep.OVERLAY -> context.getString(R.string.mindstop_diag_overlay)
        PermissionStep.BATTERY -> context.getString(R.string.mindstop_oem_step_battery_short)
        PermissionStep.BACKGROUND_POPUP -> context.getString(R.string.mindstop_oem_step_bgpopup_short)
        PermissionStep.NOTIFICATIONS -> context.getString(R.string.mindstop_oem_step_notif_short)
        PermissionStep.EXACT_ALARM -> context.getString(R.string.mindstop_oem_step_alarm_short)
    }

    fun openStep(context: Context, step: PermissionStep) {
        when (step) {
            PermissionStep.ACCESSIBILITY -> openAccessibility(context)
            PermissionStep.OVERLAY -> openOverlay(context)
            PermissionStep.BATTERY -> openBatteryOptimization(context)
            PermissionStep.BACKGROUND_POPUP -> openBackgroundPopup(context)
            PermissionStep.NOTIFICATIONS -> openNotifications(context)
            PermissionStep.EXACT_ALARM -> openExactAlarm(context)
        }
    }

    fun openNotifications(context: Context) {
        showGuideToast(context, context.getString(R.string.mindstop_oem_tip_notifications))
        val candidates = ArrayList<Intent>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            candidates += Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            }
        }
        candidates += appDetailsIntent(context)
        launchFirstAvailable(context, candidates)
    }

    fun openAccessibility(context: Context) {
        showGuideToast(context, accessibilityGuideText(context))
        try {
            PermissionUtils.openAccessibilityServiceScreen(context, AppBlockerService::class.java)
        } catch (_: Exception) {
            launchFirstAvailable(
                context,
                listOf(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS), appDetailsIntent(context))
            )
        }
    }

    fun openOverlay(context: Context) {
        showGuideToast(context, context.getString(R.string.mindstop_oem_tip_overlay))
        val pkg = context.packageName
        val candidates = ArrayList<Intent>()
        candidates += Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:$pkg".toUri())
        if (currentFamily() == OemFamily.XIAOMI) {
            candidates += MindstopUi.openMiuiPermissionsIntent(context)
        }
        candidates += appDetailsIntent(context)
        launchFirstAvailable(context, candidates)
    }

    @SuppressLint("BatteryLife")
    fun openBatteryOptimization(context: Context, forceOemPage: Boolean = false) {
        val pkg = context.packageName
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val alreadyIgnored = pm.isIgnoringBatteryOptimizations(pkg)

        // L1: One-tap native Android dialog whenever not yet granted (zero list searching!)
        if (!alreadyIgnored && !forceOemPage) {
            val oneTap = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:$pkg".toUri())
            if (tryLaunch(context, oneTap)) {
                showGuideToast(context, context.getString(R.string.mindstop_oem_tip_battery_onetap))
                return
            }
        }

        // L2: Brand-specific direct battery saver / background power screen, then L3 App Info fallback
        showGuideToast(context, batteryGuideText(context))
        val appLabel = context.applicationInfo.loadLabel(context.packageManager).toString()
        val candidates = ArrayList<Intent>()
        when (currentFamily()) {
            OemFamily.XIAOMI -> {
                candidates += Intent().apply {
                    component = ComponentName(
                        "com.miui.powerkeeper",
                        "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"
                    )
                    putExtra("package_name", pkg)
                    putExtra("package_label", appLabel)
                }
                candidates += appDetailsIntent(context)
            }
            OemFamily.HUAWEI_HONOR -> {
                candidates += componentIntent(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
                )
                candidates += componentIntent(
                    "com.hihonor.systemmanager",
                    "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
                )
                candidates += componentIntent(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.optimize.process.ProtectActivity"
                )
                candidates += appDetailsIntent(context)
            }
            OemFamily.OPPO_FAMILY -> {
                candidates += componentIntent(
                    "com.oplus.battery",
                    "com.oplus.powermanager.fuelgaue.PowerUsageModelActivity"
                )
                candidates += componentIntent(
                    "com.coloros.oppoguardelf",
                    "com.coloros.powermanager.fuelgaue.PowerUsageModelActivity"
                )
                candidates += appDetailsIntent(context)
            }
            OemFamily.VIVO_FAMILY -> {
                candidates += componentIntent(
                    "com.vivo.abe",
                    "com.vivo.applicationbehaviorengine.ui.ExcessivePowerManagerActivity"
                )
                candidates += componentIntent(
                    "com.iqoo.powersaving",
                    "com.iqoo.powersaving.PowerSavingManagerActivity"
                )
                candidates += appDetailsIntent(context)
            }
            OemFamily.SAMSUNG, OemFamily.MEIZU, OemFamily.GENERIC -> {
                candidates += appDetailsIntent(context)
            }
        }
        candidates += Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        launchFirstAvailable(context, candidates)
    }

    fun openBackgroundPopup(context: Context) {
        showGuideToast(context, bgPopupGuideText(context))
        val pkg = context.packageName
        val candidates = ArrayList<Intent>()
        when (currentFamily()) {
            OemFamily.XIAOMI -> {
                candidates += Intent("miui.intent.action.APP_PERM_EDITOR").apply {
                    setClassName(
                        "com.miui.securitycenter",
                        "com.miui.permcenter.permissions.PermissionsEditorActivity"
                    )
                    putExtra("extra_pkgname", pkg)
                }
                candidates += Intent("miui.intent.action.APP_PERM_EDITOR").apply {
                    putExtra("extra_pkgname", pkg)
                }
            }
            OemFamily.VIVO_FAMILY -> {
                candidates += componentIntent(
                    "com.vivo.permissionmanager",
                    "com.vivo.permissionmanager.activity.SoftPermissionDetailActivity"
                ).apply { putExtra("packagename", pkg) }
                candidates += componentIntent(
                    "com.iqoo.secure",
                    "com.iqoo.secure.safeguard.SoftPermissionDetailActivity"
                ).apply { putExtra("packagename", pkg) }
            }
            OemFamily.OPPO_FAMILY -> {
                candidates += componentIntent(
                    "com.oplus.safecenter",
                    "com.oplus.safecenter.permission.PermissionAppAllPermissionActivity"
                ).apply { putExtra("packageName", pkg) }
            }
            else -> Unit
        }
        candidates += appDetailsIntent(context)
        launchFirstAvailable(context, candidates)
    }

    fun openAutostart(context: Context) {
        showGuideToast(context, autostartGuideText(context))
        val candidates = ArrayList<Intent>()
        when (currentFamily()) {
            OemFamily.XIAOMI -> {
                candidates += componentIntent(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity"
                )
            }
            OemFamily.HUAWEI_HONOR -> {
                candidates += componentIntent(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
                )
                candidates += componentIntent(
                    "com.hihonor.systemmanager",
                    "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
                )
                candidates += componentIntent(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.optimize.process.ProtectActivity"
                )
            }
            OemFamily.OPPO_FAMILY -> {
                candidates += componentIntent(
                    "com.oplus.safecenter",
                    "com.oplus.safecenter.permission.startup.StartupAppListActivity"
                )
                candidates += componentIntent(
                    "com.coloros.safecenter",
                    "com.coloros.safecenter.permission.startup.StartupAppListActivity"
                )
                candidates += componentIntent(
                    "com.coloros.safecenter",
                    "com.coloros.safecenter.startupapp.StartupAppListActivity"
                )
                candidates += componentIntent(
                    "com.oneplus.security",
                    "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"
                )
            }
            OemFamily.VIVO_FAMILY -> {
                candidates += componentIntent(
                    "com.vivo.permissionmanager",
                    "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
                )
                candidates += componentIntent(
                    "com.iqoo.secure",
                    "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"
                )
                candidates += componentIntent(
                    "com.iqoo.secure",
                    "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"
                )
            }
            OemFamily.MEIZU -> {
                candidates += componentIntent(
                    "com.meizu.safe",
                    "com.meizu.safe.permission.SmartBGActivity"
                )
            }
            else -> Unit
        }
        OemAutostartIntents.resolveAutostartIntent(context)?.let { candidates += it }
        candidates += appDetailsIntent(context)
        launchFirstAvailable(context, candidates)
    }

    fun openExactAlarm(context: Context) {
        showGuideToast(context, context.getString(R.string.mindstop_oem_tip_exact_alarm))
        val candidates = ArrayList<Intent>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            candidates += Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, "package:${context.packageName}".toUri())
        }
        candidates += appDetailsIntent(context)
        launchFirstAvailable(context, candidates)
    }

    fun appDetailsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri())

    private fun componentIntent(pkg: String, cls: String): Intent =
        Intent().setComponent(ComponentName(pkg, cls))

    private fun launchFirstAvailable(context: Context, candidates: List<Intent>): Boolean {
        for (candidate in candidates) {
            if (tryLaunch(context, candidate)) return true
        }
        return false
    }

    private fun tryLaunch(context: Context, intent: Intent): Boolean {
        return try {
            val copy = Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (context.packageManager.resolveActivity(copy, 0) == null) return false
            context.startActivity(copy)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun showGuideToast(context: Context, message: String) {
        if (message.isNotBlank()) {
            Toast.makeText(context.applicationContext, message, Toast.LENGTH_LONG).show()
        }
    }
}
