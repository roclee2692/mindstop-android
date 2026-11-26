package app.mindstop.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import neth.iecal.curbox.R
import neth.iecal.curbox.databinding.FragmentMindstopOnboardingBinding
import neth.iecal.curbox.services.AppBlockerService
import neth.iecal.curbox.ui.activity.FragmentActivity
import neth.iecal.curbox.utils.PermissionUtils

/** Clean, OEM-adapted onboarding with a guided sequential permission wizard. */
class MindstopOnboardingFragment : Fragment() {

    companion object {
        const val FRAGMENT_ID = "mindstop_onboarding"
    }

    private var _binding: FragmentMindstopOnboardingBinding? = null
    private val binding get() = _binding!!
    private var wizardStepInProgress: OemPermissionRouter.PermissionStep? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentMindstopOnboardingBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val ctx = requireContext()
        binding.onboardOemLabel.text = getString(
            R.string.mindstop_oem_detected_label,
            OemPermissionRouter.deviceDisplayLabel(ctx)
        )
        binding.onboardBgDesc.text = OemPermissionRouter.batteryGuideText(ctx)

        binding.onboardWizardBtn.setOnClickListener {
            val next = OemPermissionRouter.pendingSteps(ctx, includeExactAlarm = false).firstOrNull()
            if (next != null) {
                wizardStepInProgress = next
                OemPermissionRouter.openStep(ctx, next)
            }
        }
        binding.onboardA11yBtn.setOnClickListener {
            OemPermissionRouter.openAccessibility(ctx)
        }
        binding.onboardOverlayBtn.setOnClickListener {
            OemPermissionRouter.openOverlay(ctx)
        }
        binding.onboardBgBtn.setOnClickListener {
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(ctx.packageName)) {
                OemPermissionRouter.openBatteryOptimization(ctx)
            } else {
                startActivity(Intent(ctx, MindstopDiagnosticsActivity::class.java))
            }
        }
        binding.onboardStart.setOnClickListener { finishOnboarding() }
        binding.onboardSkip.setOnClickListener { finishOnboarding() }
    }

    override fun onResume() {
        super.onResume()
        val pending = refreshChecks()
        val justFinished = wizardStepInProgress
        wizardStepInProgress = null
        if (justFinished != null && justFinished !in pending && pending.isNotEmpty() && isAdded) {
            val ctx = requireContext()
            val next = pending.first()
            MaterialAlertDialogBuilder(ctx)
                .setTitle(getString(R.string.mindstop_wizard_step_done_title, OemPermissionRouter.stepTitle(ctx, justFinished)))
                .setMessage(getString(R.string.mindstop_wizard_step_next_msg, OemPermissionRouter.stepTitle(ctx, next), pending.size))
                .setPositiveButton(R.string.mindstop_wizard_continue_btn) { _, _ ->
                    wizardStepInProgress = next
                    OemPermissionRouter.openStep(ctx, next)
                }
                .setNegativeButton(R.string.mindstop_wizard_later_btn, null)
                .show()
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    private fun refreshChecks(): List<OemPermissionRouter.PermissionStep> {
        val ctx = requireContext()
        val a11yOn = PermissionUtils.isAccessibilityServiceEnabled(ctx, AppBlockerService::class.java)
        val overlayOn = Settings.canDrawOverlays(ctx)
        val power = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        val batteryOk = power.isIgnoringBatteryOptimizations(ctx.packageName)
        val miuiPopup = MindstopUi.isMiuiBackgroundPopupAllowed(ctx)
        val bgOk = batteryOk && (miuiPopup != false)

        binding.onboardA11yStatus.text = getString(if (a11yOn) R.string.mindstop_diag_ok else R.string.mindstop_diag_missing)
        binding.onboardOverlayStatus.text = getString(if (overlayOn) R.string.mindstop_diag_ok else R.string.mindstop_diag_missing)
        binding.onboardBgStatus.text = getString(if (bgOk) R.string.mindstop_diag_ok else R.string.mindstop_diag_missing)
        binding.onboardBgBtn.text = getString(
            if (batteryOk) R.string.mindstop_diag_open_settings else R.string.mindstop_oem_battery_onetap_btn
        )
        binding.onboardStart.isEnabled = a11yOn

        val pending = OemPermissionRouter.pendingSteps(ctx, includeExactAlarm = false)
        if (pending.isEmpty()) {
            binding.onboardWizardSummary.text = getString(R.string.mindstop_wizard_all_ready)
            binding.onboardWizardBtn.isEnabled = false
            binding.onboardWizardBtn.text = getString(R.string.mindstop_wizard_all_ready_btn)
        } else {
            val next = pending.first()
            binding.onboardWizardSummary.text = getString(R.string.mindstop_wizard_pending_summary, pending.size)
            binding.onboardWizardBtn.isEnabled = true
            binding.onboardWizardBtn.text = getString(
                R.string.mindstop_wizard_next_btn,
                OemPermissionRouter.stepTitle(ctx, next),
                pending.size
            )
        }
        return pending
    }

    private fun finishOnboarding() {
        val ctx = requireContext()
        ctx.getSharedPreferences("AppPreferences", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("isFirstLaunchComplete", true)
            .putBoolean("hasAcceptedTerms", true)
            .apply()
        val intent = Intent(ctx, FragmentActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        startActivity(intent)
        activity?.finish()
    }
}
