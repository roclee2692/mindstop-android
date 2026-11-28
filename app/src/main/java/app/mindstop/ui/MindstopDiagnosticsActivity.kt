package app.mindstop.ui

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.mindstop.core.OemFamily
import app.mindstop.core.OemProfile
import app.mindstop.service.QuotaRepository
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import neth.iecal.curbox.R
import neth.iecal.curbox.databinding.ActivityMindstopDiagnosticsBinding
import neth.iecal.curbox.services.AppBlockerService
import neth.iecal.curbox.utils.PermissionUtils

/** Permission and event diagnostics with OEM-aware deep links and a guided one-tap wizard. */
class MindstopDiagnosticsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMindstopDiagnosticsBinding
    private lateinit var repo: QuotaRepository
    private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())
    private var wizardStepInProgress: OemPermissionRouter.PermissionStep? = null

    private val notifPermLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) {
            OemPermissionRouter.openNotifications(this)
        } else {
            refreshChecks()
        }
    }

    private fun requestOrOpenNotifications() {
        val runtimeDenied = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            androidx.core.content.ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (runtimeDenied) {
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            OemPermissionRouter.openNotifications(this)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMindstopDiagnosticsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        MindstopUi.applySystemBarInsets(this, binding.diagRoot)
        MindstopUi.setupToolbar(this, binding.diagToolbar, R.string.mindstop_title_diagnostics)
        repo = QuotaRepository(this)

        binding.diagOemLabel.text = getString(
            R.string.mindstop_oem_detected_label,
            OemPermissionRouter.deviceDisplayLabel(this)
        )
        binding.diagAccessibilityTip.text = OemPermissionRouter.accessibilityGuideText(this)
        binding.diagBatteryTip.text = OemPermissionRouter.batteryGuideText(this)
        binding.diagBgPopupHelp.text = OemPermissionRouter.bgPopupGuideText(this)
        binding.diagAutostartTip.text = OemPermissionRouter.autostartGuideText(this)

        binding.diagWizardButton.setOnClickListener {
            val next = OemPermissionRouter.pendingSteps(this, includeExactAlarm = true).firstOrNull()
            if (next != null) {
                wizardStepInProgress = next
                if (next == OemPermissionRouter.PermissionStep.NOTIFICATIONS) {
                    requestOrOpenNotifications()
                } else {
                    OemPermissionRouter.openStep(this, next)
                }
            }
        }
        binding.diagAccessibilityButton.setOnClickListener {
            OemPermissionRouter.openAccessibility(this)
        }
        binding.diagOverlayButton.setOnClickListener {
            OemPermissionRouter.openOverlay(this)
        }
        binding.diagBatteryButton.setOnClickListener {
            OemPermissionRouter.openBatteryOptimization(this)
        }
        binding.diagBgPopupButton.setOnClickListener {
            OemPermissionRouter.openBackgroundPopup(this)
        }
        binding.diagAutostartButton.setOnClickListener {
            OemPermissionRouter.openAutostart(this)
        }
        binding.diagNotificationsButton.setOnClickListener {
            requestOrOpenNotifications()
        }
        binding.diagExactAlarmButton.setOnClickListener {
            OemPermissionRouter.openExactAlarm(this)
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                repo.observeRecentEvents(50).collect { events ->
                    val container = binding.diagEventsContainer
                    container.removeAllViews()
                    if (events.isEmpty()) {
                        container.addView(eventRow(getString(R.string.mindstop_diag_no_events)))
                    } else {
                        for (event in events) {
                            container.addView(eventRow("${timeFormat.format(Date(event.wall))}  ${event.type}  ${event.detail}"))
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val pending = refreshChecks()
        val justFinished = wizardStepInProgress
        wizardStepInProgress = null
        if (justFinished != null && justFinished !in pending && pending.isNotEmpty()) {
            val next = pending.first()
            MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.mindstop_wizard_step_done_title, OemPermissionRouter.stepTitle(this, justFinished)))
                .setMessage(getString(R.string.mindstop_wizard_step_next_msg, OemPermissionRouter.stepTitle(this, next), pending.size))
                .setPositiveButton(R.string.mindstop_wizard_continue_btn) { _, _ ->
                    wizardStepInProgress = next
                    if (next == OemPermissionRouter.PermissionStep.NOTIFICATIONS) {
                        requestOrOpenNotifications()
                    } else {
                        OemPermissionRouter.openStep(this, next)
                    }
                }
                .setNegativeButton(R.string.mindstop_wizard_later_btn, null)
                .show()
        }
    }

    private fun refreshChecks(): List<OemPermissionRouter.PermissionStep> {
        val accessibilityOn = PermissionUtils.isAccessibilityServiceEnabled(this, AppBlockerService::class.java)
        val overlayOn = Settings.canDrawOverlays(this)
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        val batteryOk = powerManager.isIgnoringBatteryOptimizations(packageName)
        val family = OemPermissionRouter.currentFamily()
        val needsPopup = OemProfile.needsBackgroundPopupPermission(family)
        val needsAutostart = OemProfile.needsAutostartStep(family)

        binding.diagAccessibilityStatus.text = statusText(accessibilityOn)
        binding.diagOverlayStatus.text = statusText(overlayOn)
        binding.diagBatteryStatus.text = statusText(batteryOk)
        binding.diagBatteryButton.text = getString(
            if (batteryOk) R.string.mindstop_oem_battery_check_oem_btn else R.string.mindstop_oem_battery_onetap_btn
        )

        binding.diagBgPopupSection.visibility = if (needsPopup) View.VISIBLE else View.GONE
        if (needsPopup) {
            val allowed = if (family == OemFamily.XIAOMI) MindstopUi.isMiuiBackgroundPopupAllowed(this) else null
            binding.diagBgPopupStatus.text = when (allowed) {
                true -> getString(R.string.mindstop_diag_ok)
                false -> getString(R.string.mindstop_diag_missing)
                null -> getString(R.string.mindstop_diag_check_manually)
            }
        }

        binding.diagAutostartStatus.text = getString(
            if (needsAutostart) R.string.mindstop_diag_check_manually else R.string.mindstop_diag_not_needed
        )
        binding.diagAutostartButton.isEnabled = needsAutostart

        val notifOk = OemPermissionRouter.areNotificationsAllowed(this)
        binding.diagNotificationsStatus.text = statusText(notifOk)

        val exactAlarmOk = app.mindstop.service.MindstopAlarmScheduler.canScheduleExact(this)
        binding.diagExactAlarmStatus.text = statusText(exactAlarmOk)
        binding.diagExactAlarmButton.isEnabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

        val pending = OemPermissionRouter.pendingSteps(this, includeExactAlarm = true)
        if (pending.isEmpty()) {
            binding.diagWizardSummary.text = getString(R.string.mindstop_wizard_all_ready)
            binding.diagWizardButton.isEnabled = false
            binding.diagWizardButton.text = getString(R.string.mindstop_wizard_all_ready_btn)
        } else {
            val next = pending.first()
            binding.diagWizardSummary.text = getString(R.string.mindstop_wizard_pending_summary, pending.size)
            binding.diagWizardButton.isEnabled = true
            binding.diagWizardButton.text = getString(
                R.string.mindstop_wizard_next_btn,
                OemPermissionRouter.stepTitle(this, next),
                pending.size
            )
        }

        lifecycleScope.launch {
            val dayKey = app.mindstop.core.DayKeys.dayKey(System.currentTimeMillis())
            val totals = withContext(Dispatchers.IO) { repo.dailyTotals(dayKey) }
            val totalMs = totals.values.sum()
            binding.diagTodayTotal.text = getString(
                R.string.mindstop_diag_today_total,
                totals.size,
                (totalMs / 60_000L)
            )
        }
        return pending
    }

    private fun statusText(ok: Boolean): String =
        getString(if (ok) R.string.mindstop_diag_ok else R.string.mindstop_diag_missing)

    private fun eventRow(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 12f
        setPadding(0, 6, 0, 6)
    }
}
