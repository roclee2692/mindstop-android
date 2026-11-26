package app.mindstop.core

/**
 * Pure-Kotlin OEM classification for tailored Android background/battery permission guidance.
 */
enum class OemFamily {
    XIAOMI,
    HUAWEI_HONOR,
    OPPO_FAMILY,
    VIVO_FAMILY,
    SAMSUNG,
    MEIZU,
    GENERIC,
}

object OemProfile {

    fun classify(manufacturer: String?, brand: String?): OemFamily {
        val m = (manufacturer ?: "").lowercase()
        val b = (brand ?: "").lowercase()
        fun has(vararg tokens: String) = tokens.any { m.contains(it) || b.contains(it) }
        return when {
            has("xiaomi", "redmi", "poco", "blackshark") -> OemFamily.XIAOMI
            has("huawei", "honor") -> OemFamily.HUAWEI_HONOR
            has("oppo", "oneplus", "realme") -> OemFamily.OPPO_FAMILY
            has("vivo", "iqoo") -> OemFamily.VIVO_FAMILY
            has("samsung") -> OemFamily.SAMSUNG
            has("meizu") -> OemFamily.MEIZU
            else -> OemFamily.GENERIC
        }
    }

    fun needsBackgroundPopupPermission(family: OemFamily): Boolean =
        family == OemFamily.XIAOMI || family == OemFamily.VIVO_FAMILY

    fun needsAutostartStep(family: OemFamily): Boolean =
        family != OemFamily.GENERIC && family != OemFamily.SAMSUNG
}
