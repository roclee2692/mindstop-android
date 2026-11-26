package app.mindstop.ui

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import app.mindstop.core.RewardSettings
import app.mindstop.service.QuotaRepository
import app.mindstop.service.RewardRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import neth.iecal.curbox.R
import neth.iecal.curbox.databinding.ActivityMindstopRewardSettingsBinding

/** Configures verification window, per-group daily reward cap, feedback banner, and experimental gate. */
class MindstopRewardSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMindstopRewardSettingsBinding
    private lateinit var quotaRepo: QuotaRepository
    private lateinit var rewardRepo: RewardRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMindstopRewardSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        MindstopUi.applySystemBarInsets(this, binding.rewardSettingsRoot)
        MindstopUi.setupToolbar(this, binding.rewardSettingsToolbar, R.string.mindstop_reward_settings_title)

        quotaRepo = QuotaRepository(this)
        rewardRepo = RewardRepository(this)

        lifecycleScope.launch {
            val current = withContext(Dispatchers.IO) { rewardRepo.loadSettings() }
            binding.rewardVerifyMinutes.setText(current.verifyWindowMinutes.toString())
            binding.rewardDailyCap.setText(current.maxFullPerGroupPerDay.toString())
            binding.rewardFeedbackSwitch.isChecked = current.feedbackEnabled
            binding.rewardReminderFirstSwitch.isChecked = current.requireReminderFirst
        }

        binding.rewardSettingsSave.setOnClickListener { save() }
    }

    private fun save() {
        val verify = binding.rewardVerifyMinutes.text?.toString()?.trim()?.toIntOrNull()
        val cap = binding.rewardDailyCap.text?.toString()?.trim()?.toIntOrNull()
        if (verify == null || verify !in RewardSettings.MIN_VERIFY_MINUTES..RewardSettings.MAX_VERIFY_MINUTES ||
            cap == null || cap !in RewardSettings.MIN_MAX_FULL_PER_GROUP_PER_DAY..RewardSettings.MAX_MAX_FULL_PER_GROUP_PER_DAY
        ) {
            Toast.makeText(this, R.string.mindstop_reward_settings_invalid, Toast.LENGTH_SHORT).show()
            return
        }
        val updated = RewardSettings(
            verifyWindowMinutes = verify,
            maxFullPerGroupPerDay = cap,
            requireReminderFirst = binding.rewardReminderFirstSwitch.isChecked,
            feedbackEnabled = binding.rewardFeedbackSwitch.isChecked
        )
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                val now = quotaRepo.approxTrustedNow()
                rewardRepo.saveSettings(updated, now)
            }
            Toast.makeText(this@MindstopRewardSettingsActivity, R.string.mindstop_reward_settings_saved, Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
