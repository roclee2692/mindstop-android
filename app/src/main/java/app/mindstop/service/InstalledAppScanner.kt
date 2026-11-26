package app.mindstop.service

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.os.Build
import android.os.Process
import app.mindstop.core.AppCategoryHint
import app.mindstop.core.InstalledAppInfo

/** Scans launchable installed apps on device (domestic + overseas) into platform-agnostic metadata. */
object InstalledAppScanner {

    fun scanInstalled(context: Context): List<InstalledAppInfo> {
        val launcherApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as? LauncherApps
            ?: return emptyList()
        val activities = try {
            launcherApps.getActivityList(null, Process.myUserHandle())
        } catch (_: Exception) {
            emptyList()
        }
        val byPackage = LinkedHashMap<String, InstalledAppInfo>()
        for (info in activities) {
            val appInfo = info.applicationInfo ?: continue
            val pkg = appInfo.packageName ?: continue
            if (pkg in byPackage) continue
            val label = info.label?.toString()?.takeIf { it.isNotBlank() } ?: pkg
            byPackage[pkg] = InstalledAppInfo(
                packageName = pkg,
                label = label,
                categoryHint = mapCategory(appInfo)
            )
        }
        return byPackage.values.toList()
    }

    private fun mapCategory(appInfo: ApplicationInfo): AppCategoryHint {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return when (appInfo.category) {
                ApplicationInfo.CATEGORY_VIDEO -> AppCategoryHint.VIDEO
                ApplicationInfo.CATEGORY_GAME -> AppCategoryHint.GAME
                ApplicationInfo.CATEGORY_SOCIAL -> AppCategoryHint.SOCIAL
                ApplicationInfo.CATEGORY_NEWS -> AppCategoryHint.NEWS
                else -> AppCategoryHint.OTHER
            }
        }
        @Suppress("DEPRECATION")
        if ((appInfo.flags and ApplicationInfo.FLAG_IS_GAME) != 0) {
            return AppCategoryHint.GAME
        }
        return AppCategoryHint.OTHER
    }
}
