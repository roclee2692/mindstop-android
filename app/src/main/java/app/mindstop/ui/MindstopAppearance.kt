package app.mindstop.ui

import android.app.UiModeManager
import android.content.Context
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate

internal object MindstopAppearance {
    fun applyTheme(context: Context, mode: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val platformMode = when (mode) {
                AppCompatDelegate.MODE_NIGHT_NO -> UiModeManager.MODE_NIGHT_NO
                AppCompatDelegate.MODE_NIGHT_YES -> UiModeManager.MODE_NIGHT_YES
                else -> UiModeManager.MODE_NIGHT_AUTO
            }
            context.getSystemService(UiModeManager::class.java).setApplicationNightMode(platformMode)
        } else {
            AppCompatDelegate.setDefaultNightMode(mode)
        }
    }
}
