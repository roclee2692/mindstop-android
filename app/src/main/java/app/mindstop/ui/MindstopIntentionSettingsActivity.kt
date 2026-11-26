package app.mindstop.ui

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import app.mindstop.core.CalmWait
import app.mindstop.core.IntentionSettings
import app.mindstop.core.OverlayIntensity
import app.mindstop.core.OverlayPosition
import app.mindstop.core.OverlaySize
import app.mindstop.service.QuotaRepository
import com.google.android.material.chip.Chip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import neth.iecal.curbox.R
import neth.iecal.curbox.databinding.ActivityMindstopIntentionSettingsBinding

/** Edits the calm wait, the plan reminder points, and how the floating pill looks. */
class MindstopIntentionSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMindstopIntentionSettingsBinding
    private lateinit var repo: QuotaRepository
    private var loaded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMindstopIntentionSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        MindstopUi.applySystemBarInsets(this, binding.isRoot)
        MindstopUi.setupToolbar(this, binding.isToolbar, R.string.mindstop_open_intention_settings)
        repo = QuotaRepository(this)
        binding.isWaitGroup.setOnCheckedStateChangeListener { _, _ ->
            binding.isWaitCustomLayout.visibility = if (selectedWaitPreset() == CUSTOM) View.VISIBLE else View.GONE
        }
        binding.isSave.setOnClickListener { save() }
        lifecycleScope.launch {
            val settings = withContext(Dispatchers.IO) { repo.loadIntentionSettings() }
            render(settings)
            loaded = true
        }
    }

    private fun render(settings: IntentionSettings) {
        val group = binding.isWaitGroup
        group.removeAllViews()
        val isPreset = settings.waitSeconds in CalmWait.PRESETS
        for (value in CalmWait.PRESETS + CUSTOM) {
            val chip = Chip(this).apply {
                id = View.generateViewId()
                tag = value
                isCheckable = true
                text = if (value == CUSTOM) getString(R.string.mindstop_intention_custom)
                else getString(R.string.mindstop_is_seconds, value)
            }
            group.addView(chip)
            if ((isPreset && value == settings.waitSeconds) || (!isPreset && value == CUSTOM)) group.check(chip.id)
        }
        if (!isPreset) binding.isWaitCustom.setText(settings.waitSeconds.toString())
        binding.isNodes.setText(IntentionSettings.formatNodes(settings.nodeMinutes, settings.nodePercents))
        binding.isOverlaySwitch.isChecked = settings.overlayEnabled
        binding.isPositionGroup.check(
            when (settings.position) {
                OverlayPosition.TOP -> R.id.is_position_top
                OverlayPosition.MIDDLE -> R.id.is_position_middle
                OverlayPosition.BOTTOM -> R.id.is_position_bottom
            }
        )
        binding.isSizeGroup.check(
            when (settings.size) {
                OverlaySize.SMALL -> R.id.is_size_small
                OverlaySize.MEDIUM -> R.id.is_size_medium
                OverlaySize.LARGE -> R.id.is_size_large
            }
        )
        binding.isIntensityGroup.check(
            when (settings.intensity) {
                OverlayIntensity.LIGHT -> R.id.is_intensity_light
                OverlayIntensity.NORMAL -> R.id.is_intensity_normal
                OverlayIntensity.STRONG -> R.id.is_intensity_strong
            }
        )
    }

    private fun selectedWaitPreset(): Int? {
        val id = binding.isWaitGroup.checkedChipId
        if (id == View.NO_ID) return null
        return binding.isWaitGroup.findViewById<Chip>(id)?.tag as? Int
    }

    private fun save() {
        if (!loaded) return
        val wait = when (val preset = selectedWaitPreset()) {
            null -> null
            CUSTOM -> binding.isWaitCustom.text?.toString()?.trim()?.toIntOrNull()
                ?.takeIf { it in 0..CalmWait.MAX_SECONDS }
            else -> preset
        }
        if (wait == null) {
            Toast.makeText(this, getString(R.string.mindstop_is_wait_invalid, CalmWait.MAX_SECONDS), Toast.LENGTH_LONG).show()
            return
        }
        val nodes = IntentionSettings.parseNodes(binding.isNodes.text?.toString().orEmpty())
        if (nodes == null) {
            binding.isNodesLayout.error = getString(R.string.mindstop_is_nodes_invalid)
            return
        }
        binding.isNodesLayout.error = null
        val settings = IntentionSettings(
            waitSeconds = wait,
            nodeMinutes = nodes.first,
            nodePercents = nodes.second,
            overlayEnabled = binding.isOverlaySwitch.isChecked,
            position = when (binding.isPositionGroup.checkedButtonId) {
                R.id.is_position_middle -> OverlayPosition.MIDDLE
                R.id.is_position_bottom -> OverlayPosition.BOTTOM
                else -> OverlayPosition.TOP
            },
            size = when (binding.isSizeGroup.checkedButtonId) {
                R.id.is_size_small -> OverlaySize.SMALL
                R.id.is_size_large -> OverlaySize.LARGE
                else -> OverlaySize.MEDIUM
            },
            intensity = when (binding.isIntensityGroup.checkedButtonId) {
                R.id.is_intensity_light -> OverlayIntensity.LIGHT
                R.id.is_intensity_strong -> OverlayIntensity.STRONG
                else -> OverlayIntensity.NORMAL
            }
        )
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { repo.saveIntentionSettings(settings, System.currentTimeMillis()) }
            Toast.makeText(this@MindstopIntentionSettingsActivity, R.string.mindstop_cs_saved, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    companion object {
        private const val CUSTOM = -1
    }
}
