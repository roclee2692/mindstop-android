package app.mindstop.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.IOException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import neth.iecal.curbox.BuildConfig
import neth.iecal.curbox.R
import neth.iecal.curbox.databinding.FragmentMindstopSettingsBinding
import neth.iecal.curbox.services.AppBlockerService
import neth.iecal.curbox.utils.DataStoreManager
import neth.iecal.curbox.utils.PermissionUtils

/** Mindstop settings tab: permission health, core module shortcuts, and local-first GPL notice. */
class MindstopSettingsFragment : Fragment() {

    private var _binding: FragmentMindstopSettingsBinding? = null
    private val binding get() = _binding!!
    private lateinit var appearanceStore: DataStoreManager

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentMindstopSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        appearanceStore = DataStoreManager(requireContext())
        binding.settingsLanguage.setOnClickListener { selectLanguage() }
        refreshLanguageLabel()
        bindAppearance()
        binding.settingsOpenDiagnostics.setOnClickListener {
            startActivity(Intent(requireContext(), MindstopDiagnosticsActivity::class.java))
        }
        binding.settingsOpenIntention.setOnClickListener {
            startActivity(Intent(requireContext(), MindstopIntentionSettingsActivity::class.java))
        }
        binding.settingsOpenChallenge.setOnClickListener {
            startActivity(Intent(requireContext(), MindstopChallengeSettingsActivity::class.java))
        }
        binding.settingsOpenBank.setOnClickListener {
            startActivity(Intent(requireContext(), MindstopQuestionBankActivity::class.java))
        }
        binding.settingsOpenReward.setOnClickListener {
            startActivity(Intent(requireContext(), MindstopRewardSettingsActivity::class.java))
        }
        binding.settingsOpenFocus.setOnClickListener {
            startActivity(Intent(requireContext(), MindstopFocusActivity::class.java))
        }
        binding.settingsOpenBedtime.setOnClickListener {
            startActivity(Intent(requireContext(), MindstopBedtimeActivity::class.java))
        }
        binding.settingsOpenMorning.setOnClickListener {
            startActivity(Intent(requireContext(), MindstopMorningActivity::class.java))
        }
        binding.settingsOpenShopping.setOnClickListener {
            startActivity(Intent(requireContext(), MindstopShoppingActivity::class.java))
        }
        binding.settingsOpenStats.setOnClickListener {
            startActivity(Intent(requireContext(), MindstopStatsActivity::class.java))
        }
        bindAboutSection()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden && _binding != null) refreshStatus()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    private fun bindAppearance() = withAppearanceStore {
        val saved = appearanceStore.settings.first()
        renderThemeLabel(saved.mindstopThemeMode)
        binding.settingsTheme.setOnClickListener { selectTheme() }
        var showQuote = saved.mindstopShowQuote
        binding.settingsDailyQuote.isChecked = showQuote
        binding.settingsDailyQuote.isEnabled = true
        binding.settingsDailyQuote.setOnCheckedChangeListener { button, enabled ->
            if (enabled == showQuote) return@setOnCheckedChangeListener
            button.isEnabled = false
            withAppearanceStore(
                onFailure = {
                    button.isChecked = showQuote
                    button.isEnabled = true
                }
            ) {
                appearanceStore.updateMindstopShowQuote(enabled)
                showQuote = enabled
                button.isEnabled = true
            }
        }
    }

    private fun selectLanguage() {
        val current = AppCompatDelegate.getApplicationLocales()[0]
        val selected = languageIndex(current?.language)
        val names = languageLabels().map(::getString).toTypedArray()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.mindstop_settings_language)
            .setSingleChoiceItems(names, selected) { dialog, which ->
                dialog.dismiss()
                val languages = when (which) {
                    1 -> LocaleListCompat.forLanguageTags("zh-CN")
                    2 -> LocaleListCompat.forLanguageTags("en")
                    else -> LocaleListCompat.getEmptyLocaleList()
                }
                AppCompatDelegate.setApplicationLocales(languages)
                refreshLanguageLabel()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun refreshLanguageLabel() {
        val index = languageIndex(AppCompatDelegate.getApplicationLocales()[0]?.language)
        binding.settingsLanguage.text = getString(
            R.string.mindstop_settings_language_value, getString(languageLabels()[index])
        )
    }

    private fun languageIndex(language: String?) = when (language) {
        "zh" -> 1
        "en" -> 2
        else -> 0
    }

    private fun languageLabels() = listOf(
        R.string.mindstop_settings_follow_system,
        R.string.mindstop_language_chinese,
        R.string.mindstop_language_english
    )

    private val themes = listOf(
        AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM to R.string.mindstop_settings_follow_system,
        AppCompatDelegate.MODE_NIGHT_NO to R.string.mindstop_theme_light,
        AppCompatDelegate.MODE_NIGHT_YES to R.string.mindstop_theme_dark
    )

    private fun selectTheme() = withAppearanceStore {
        val theme = appearanceStore.settings.first().mindstopThemeMode
        val names = themes.map { getString(it.second) }.toTypedArray()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.mindstop_settings_theme)
            .setSingleChoiceItems(names, themes.indexOfFirst { it.first == theme }) { dialog, which ->
                dialog.dismiss()
                val mode = themes[which].first
                withAppearanceStore {
                    appearanceStore.updateMindstopTheme(mode)
                    renderThemeLabel(mode)
                    MindstopAppearance.applyTheme(requireContext(), mode)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun renderThemeLabel(mode: Int) {
        val label = themes.firstOrNull { it.first == mode }?.second ?: R.string.mindstop_settings_follow_system
        binding.settingsTheme.text = getString(R.string.mindstop_settings_theme_value, getString(label))
    }

    private fun withAppearanceStore(
        onFailure: () -> Unit = {},
        action: suspend () -> Unit
    ) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                action()
            } catch (e: IOException) {
                Log.w("MindstopSettings", "Could not access appearance preferences", e)
                Toast.makeText(requireContext(), R.string.mindstop_settings_appearance_error, Toast.LENGTH_SHORT).show()
                onFailure()
            }
        }
    }

    private fun refreshStatus() {
        val a11yOn = PermissionUtils.isAccessibilityServiceEnabled(requireContext(), AppBlockerService::class.java)
        binding.settingsGuardStatus.setText(
            if (a11yOn) R.string.mindstop_settings_guard_ok else R.string.mindstop_settings_guard_off
        )
    }

    private fun bindAboutSection() {
        binding.settingsAboutBody.text = getString(
            R.string.mindstop_settings_about_body,
            BuildConfig.VERSION_NAME,
            DEVELOPER_NAME,
            REPO_URL,
            RELEASES_URL
        )
        binding.settingsShareDownload.setOnClickListener {
            val shareText = getString(R.string.mindstop_about_share_text, RELEASES_URL, REPO_URL)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, shareText)
            }
            startActivity(Intent.createChooser(send, getString(R.string.mindstop_about_share_download)))
        }
        binding.settingsCopyDownload.setOnClickListener {
            copyToClipboard(RELEASES_URL)
            Toast.makeText(requireContext(), R.string.mindstop_about_link_copied, Toast.LENGTH_SHORT).show()
        }
        binding.settingsOpenReleases.setOnClickListener { openExternalUrl(RELEASES_URL) }
        binding.settingsOpenRepo.setOnClickListener { openExternalUrl(REPO_URL) }
    }

    private fun openExternalUrl(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            copyToClipboard(url)
            Toast.makeText(requireContext(), R.string.mindstop_about_link_copied, Toast.LENGTH_SHORT).show()
        }
    }

    private fun copyToClipboard(text: String) {
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("MindStop", text))
    }

    companion object {
        private const val DEVELOPER_NAME = "Raelon Veritas Lee (@roclee2692)"
        private const val REPO_URL = "https://github.com/roclee2692/mindstop-android"
        private const val RELEASES_URL = "https://github.com/roclee2692/mindstop-android/releases/latest"
    }
}
