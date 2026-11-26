package app.mindstop.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.net.toUri
import androidx.fragment.app.Fragment
import neth.iecal.curbox.R
import neth.iecal.curbox.databinding.FragmentMindstopOnboardingBinding
import neth.iecal.curbox.services.AppBlockerService
import neth.iecal.curbox.ui.activity.FragmentActivity
import neth.iecal.curbox.utils.PermissionUtils

/** Clean, calm onboarding for Mindstop without unrelated Curbox steps. */
class MindstopOnboardingFragment : Fragment() {

    companion object {
        const val FRAGMENT_ID = "mindstop_onboarding"
    }

    private var _binding: FragmentMindstopOnboardingBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentMindstopOnboardingBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.onboardA11yBtn.setOnClickListener {
            PermissionUtils.openAccessibilityServiceScreen(requireContext(), AppBlockerService::class.java)
        }
        binding.onboardOverlayBtn.setOnClickListener {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:${requireContext().packageName}".toUri()))
        }
        binding.onboardBgBtn.setOnClickListener {
            startActivity(Intent(requireContext(), MindstopDiagnosticsActivity::class.java))
        }
        binding.onboardStart.setOnClickListener { finishOnboarding() }
        binding.onboardSkip.setOnClickListener { finishOnboarding() }
    }

    override fun onResume() {
        super.onResume()
        refreshChecks()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    private fun refreshChecks() {
        val ctx = requireContext()
        val a11yOn = PermissionUtils.isAccessibilityServiceEnabled(ctx, AppBlockerService::class.java)
        val overlayOn = Settings.canDrawOverlays(ctx)
        val power = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        val batteryOk = power.isIgnoringBatteryOptimizations(ctx.packageName)
        val miuiPopup = MindstopUi.isMiuiBackgroundPopupAllowed(ctx)
        val bgOk = batteryOk && (miuiPopup != false)

        binding.onboardA11yStatus.text = getString(if (a11yOn) R.string.mindstop_diag_ok else R.string.mindstop_diag_missing)
        binding.onboardOverlayStatus.text = getString(if (overlayOn) R.string.mindstop_diag_ok else R.string.mindstop_diag_missing)
        binding.onboardBgStatus.text = getString(if (bgOk) R.string.mindstop_diag_ok else R.string.mindstop_diag_check_manually)
        binding.onboardStart.isEnabled = a11yOn
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
