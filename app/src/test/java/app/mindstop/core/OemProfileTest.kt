package app.mindstop.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OemProfileTest {

    @Test
    fun classifiesMajorChineseAndGlobalOems() {
        assertEquals(OemFamily.XIAOMI, OemProfile.classify("Xiaomi", "Redmi"))
        assertEquals(OemFamily.XIAOMI, OemProfile.classify("Xiaomi", "POCO"))
        assertEquals(OemFamily.HUAWEI_HONOR, OemProfile.classify("HUAWEI", "HUAWEI"))
        assertEquals(OemFamily.HUAWEI_HONOR, OemProfile.classify("HONOR", "HONOR"))
        assertEquals(OemFamily.OPPO_FAMILY, OemProfile.classify("OPPO", "OPPO"))
        assertEquals(OemFamily.OPPO_FAMILY, OemProfile.classify("OnePlus", "OnePlus"))
        assertEquals(OemFamily.OPPO_FAMILY, OemProfile.classify("realme", "realme"))
        assertEquals(OemFamily.VIVO_FAMILY, OemProfile.classify("vivo", "vivo"))
        assertEquals(OemFamily.VIVO_FAMILY, OemProfile.classify("vivo", "iQOO"))
        assertEquals(OemFamily.SAMSUNG, OemProfile.classify("samsung", "samsung"))
        assertEquals(OemFamily.MEIZU, OemProfile.classify("Meizu", "meizu"))
        assertEquals(OemFamily.GENERIC, OemProfile.classify("Google", "google"))
    }

    @Test
    fun reportsExtraPopupAndAutostartRequirementsAccurately() {
        assertTrue(OemProfile.needsBackgroundPopupPermission(OemFamily.XIAOMI))
        assertTrue(OemProfile.needsBackgroundPopupPermission(OemFamily.VIVO_FAMILY))
        assertFalse(OemProfile.needsBackgroundPopupPermission(OemFamily.SAMSUNG))
        assertFalse(OemProfile.needsBackgroundPopupPermission(OemFamily.GENERIC))

        assertTrue(OemProfile.needsAutostartStep(OemFamily.XIAOMI))
        assertTrue(OemProfile.needsAutostartStep(OemFamily.HUAWEI_HONOR))
        assertTrue(OemProfile.needsAutostartStep(OemFamily.OPPO_FAMILY))
        assertFalse(OemProfile.needsAutostartStep(OemFamily.SAMSUNG))
        assertFalse(OemProfile.needsAutostartStep(OemFamily.GENERIC))
    }
}
