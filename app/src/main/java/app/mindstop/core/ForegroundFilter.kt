package app.mindstop.core

/**
 * Pure classification for transient system UI overlays (Control Center, notification shade,
 * volume panel, permission dialogs, HyperOS system plugins, and soft keyboards / IMEs).
 *
 * Entering a transient overlay MUST NOT be treated as leaving the underlying foreground app
 * (which previously caused false ACTIVE_EXIT rewards when pulling down Control Center and
 * immediate reward revocation when closing it).
 */
object ForegroundFilter {

    val TRANSIENT_SYSTEM_PACKAGES: Set<String> = setOf(
        "com.android.systemui",
        "miui.systemui.plugin",
        "com.miui.notification",
        "com.miui.aod",
        "com.miui.freeform",
        "com.miui.voiceassist",
        "com.xiaomi.aiasst.service",
        "com.miui.contentcatcher",
        "com.xiaomi.misettings",
        "com.lbe.security.miui",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
        "com.oplus.exsystemservice",
        "com.coloros.floatassistant",
        "com.samsung.android.app.cocktailbarservice"
    )

    fun isTransientSystemWindow(packageName: String?, imePackages: Set<String> = emptySet()): Boolean {
        if (packageName.isNullOrBlank()) return false
        if (packageName in TRANSIENT_SYSTEM_PACKAGES) return true
        if (packageName in imePackages) return true
        return false
    }
}
