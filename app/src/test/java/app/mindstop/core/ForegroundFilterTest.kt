package app.mindstop.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundFilterTest {

    @Test
    fun transientSystemOverlaysAndImesAreIgnored() {
        val imes = setOf("com.sohu.inputmethod.sogou.xiaomi", "com.google.android.inputmethod.latin")

        assertTrue(ForegroundFilter.isTransientSystemWindow("com.android.systemui", imes))
        assertTrue(ForegroundFilter.isTransientSystemWindow("miui.systemui.plugin", imes))
        assertTrue(ForegroundFilter.isTransientSystemWindow("com.miui.notification", imes))
        assertTrue(ForegroundFilter.isTransientSystemWindow("com.miui.voiceassist", imes))
        assertTrue(ForegroundFilter.isTransientSystemWindow("com.android.permissioncontroller", imes))
        assertTrue(ForegroundFilter.isTransientSystemWindow("com.sohu.inputmethod.sogou.xiaomi", imes))
    }

    @Test
    fun realUserAppsAndLaunchersAreNotIgnored() {
        val imes = setOf("com.sohu.inputmethod.sogou.xiaomi")

        assertFalse(ForegroundFilter.isTransientSystemWindow("tv.danmaku.bili", imes))
        assertFalse(ForegroundFilter.isTransientSystemWindow("com.xingin.xhs", imes))
        assertFalse(ForegroundFilter.isTransientSystemWindow("com.miui.home", imes))
        assertFalse(ForegroundFilter.isTransientSystemWindow(null, imes))
        assertFalse(ForegroundFilter.isTransientSystemWindow("", imes))
    }
}
