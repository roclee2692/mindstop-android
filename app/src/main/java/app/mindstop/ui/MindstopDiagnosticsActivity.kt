package app.mindstop.ui

import android.content.Intent
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.mindstop.service.QuotaRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import neth.iecal.curbox.R
import neth.iecal.curbox.databinding.ActivityMindstopDiagnosticsBinding
import neth.iecal.curbox.hardcoded.OemAutostartIntents
import neth.iecal.curbox.services.AppBlockerService
import neth.iecal.curbox.utils.PermissionUtils
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Permission and event diagnostics for the quota engine, aimed at Xiaomi style OEM problems. */
class MindstopDiagnosticsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMindstopDiagnosticsBinding
    private lateinit var repo: QuotaRepository
    private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMindstopDiagnosticsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        MindstopUi.applySystemBarInsets(this, binding.diagRoot)
        MindstopUi.setupToolbar(this, binding.diagToolbar, R.string.mindstop_title_diagnostics)
        repo = QuotaRepository(this)

        binding.diagAccessibilityButton.setOnClickListener {
            PermissionUtils.openAccessibilityServiceScreen(this, AppBlockerService::class.java)
        }
        binding.diagOverlayButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:$packageName".toUri()))
        }
        binding.diagBatteryButton.setOnClickListener {
            if (MindstopUi.isXiaomiFamily()) {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri())
                )
            } else {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }
        binding.diagBgPopupButton.setOnClickListener {
            startActivity(MindstopUi.openMiuiPermissionsIntent(this))
        }
        binding.diagAutostartButton.setOnClickListener {
            val intent = OemAutostartIntents.resolveAutostartIntent(this)
            if (intent != null) startActivity(intent)
        }
        binding.diagExactAlarmButton.setOnClickListener {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, "package:$packageName".toUri()))
            }
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
        refreshChecks()
    }

    private fun refreshChecks() {
        val accessibilityOn = PermissionUtils.isAccessibilityServiceEnabled(this, AppBlockerService::class.java)
        val overlayOn = Settings.canDrawOverlays(this)
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        val batteryOk = powerManager.isIgnoringBatteryOptimizations(packageName)
        val aggressiveOem = OemAutostartIntents.isLikelyAggressiveOem()
        val autostartAvailable = aggressiveOem && OemAutostartIntents.resolveAutostartIntent(this) != null

        binding.diagAccessibilityStatus.text = statusText(accessibilityOn)
        binding.diagOverlayStatus.text = statusText(overlayOn)
        binding.diagBatteryStatus.text = statusText(batteryOk)
        val xiaomi = MindstopUi.isXiaomiFamily()
        binding.diagBgPopupSection.visibility = if (xiaomi) android.view.View.VISIBLE else android.view.View.GONE
        if (xiaomi) {
            val allowed = MindstopUi.isMiuiBackgroundPopupAllowed(this)
            binding.diagBgPopupStatus.text = when (allowed) {
                true -> getString(R.string.mindstop_diag_ok)
                false -> getString(R.string.mindstop_diag_missing)
                null -> getString(R.string.mindstop_diag_check_manually)
            }
        }
        binding.diagAutostartStatus.text = when {
            !aggressiveOem -> getString(R.string.mindstop_diag_not_needed)
            autostartAvailable -> getString(R.string.mindstop_diag_check_manually)
            else -> getString(R.string.mindstop_diag_check_manually)
        }
        binding.diagAutostartButton.isEnabled = autostartAvailable
        val exactAlarmOk = app.mindstop.service.MindstopAlarmScheduler.canScheduleExact(this)
        binding.diagExactAlarmStatus.text = statusText(exactAlarmOk)
        binding.diagExactAlarmButton.isEnabled = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S

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
    }

    private fun statusText(ok: Boolean): String =
        getString(if (ok) R.string.mindstop_diag_ok else R.string.mindstop_diag_missing)

    private fun eventRow(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 12f
        setPadding(0, 6, 0, 6)
    }
}
