package neth.iecal.curbox

import android.app.Application
import android.os.Build
import android.util.Log
import app.mindstop.ui.MindstopAppearance
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import neth.iecal.curbox.utils.DataStoreManager
import rikka.shizuku.ShizukuProvider

class Curbox : Application() {
    override fun onCreate() {
        val mainProcess = isMainProcess()
        // ShizukuProvider lives only in the main process (multiprocess=false), but the
        // accessibility service that drives grayscale runs in :app_blocker_service. Multi
        // process support lets the provider broadcast the Shizuku binder to that process (and
        // refresh it when it dies/changes), so its write_secure_settings shell commands and the
        // one time WRITE_SECURE_SETTINGS self grant actually run. Must happen as early as
        // possible, in every process, before requestBinderForNonProviderProcess runs.
        try {
            ShizukuProvider.enableMultiProcessSupport(mainProcess)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        Thread.setDefaultUncaughtExceptionHandler(CrashLogger(this))
        super.onCreate()
        if (mainProcess) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                CoroutineScope(Dispatchers.Main).launch {
                    try {
                        val mode = DataStoreManager(this@Curbox).settings.first().mindstopThemeMode
                        MindstopAppearance.applyTheme(this@Curbox, mode)
                    } catch (e: IOException) {
                        Log.w("MindstopAppearance", "Could not read the saved app theme", e)
                    }
                }
            }
            neth.iecal.curbox.utils.UsageStatsCleaner.cleanNowIfDue(this)
            neth.iecal.curbox.utils.SettingsChangeDelayUtils.applyDuePendingChangesAsync(this)
        }
    }

    private fun isMainProcess(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return getProcessName() == packageName
        }
        val procName = run {
            try {
                java.io.File("/proc/self/cmdline").readText().trim { it <= ' ' || it == ' ' }
            } catch (e: Exception) {
                packageName
            }
        }
        return procName == packageName
    }
}
