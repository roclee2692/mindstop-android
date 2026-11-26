package app.mindstop.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MindstopPresetGroupsTest {

    @Test
    fun scansDomesticAndInternationalAppsIntoCorrectBuckets() {
        val installed = listOf(
            InstalledAppInfo("tv.danmaku.bili", "哔哩哔哩", AppCategoryHint.OTHER),
            InstalledAppInfo("com.xingin.xhs", "小红书", AppCategoryHint.OTHER),
            InstalledAppInfo("com.google.android.youtube", "YouTube", AppCategoryHint.VIDEO),
            InstalledAppInfo("com.ss.android.ugc.aweme", "抖音", AppCategoryHint.OTHER),
            InstalledAppInfo("com.sina.weibo", "微博", AppCategoryHint.OTHER),
            InstalledAppInfo("com.instagram.android", "Instagram", AppCategoryHint.SOCIAL),
            InstalledAppInfo("com.miHoYo.Yuanshen", "原神", AppCategoryHint.OTHER),
            InstalledAppInfo("com.example.indie.rpg", "Indie RPG", AppCategoryHint.GAME),
            InstalledAppInfo("com.dragon.read", "番茄小说", AppCategoryHint.OTHER),
            InstalledAppInfo("com.taobao.taobao", "淘宝", AppCategoryHint.OTHER),
            InstalledAppInfo("com.tencent.mm", "微信", AppCategoryHint.SOCIAL),
            InstalledAppInfo("com.eg.android.AlipayGphone", "支付宝", AppCategoryHint.OTHER),
            InstalledAppInfo("com.autonavi.minimap", "高德地图", AppCategoryHint.OTHER),
            InstalledAppInfo("app.mindstop.android.debug", "知止", AppCategoryHint.OTHER)
        )

        val scanned = PresetGroupScanner.scan(
            installed = installed,
            exempt = setOf("com.miui.calculator"),
            ownPackage = "app.mindstop.android.debug"
        ).associateBy { it.preset }

        assertEquals(
            setOf("tv.danmaku.bili", "com.xingin.xhs", "com.google.android.youtube", "com.ss.android.ugc.aweme"),
            scanned.getValue(PresetCategoryId.SHORT_VIDEO).matchedApps.map { it.packageName }.toSet()
        )
        assertTrue(scanned.getValue(PresetCategoryId.SHORT_VIDEO).defaultSelected)

        // WeChat is isolated into MESSAGING_OPT_IN (never auto-included in SOCIAL_FEED or defaultSelected!)
        assertEquals(
            setOf("com.sina.weibo", "com.instagram.android"),
            scanned.getValue(PresetCategoryId.SOCIAL_FEED).matchedApps.map { it.packageName }.toSet()
        )
        assertEquals(
            setOf("com.tencent.mm"),
            scanned.getValue(PresetCategoryId.MESSAGING_OPT_IN).matchedApps.map { it.packageName }.toSet()
        )
        assertFalse(scanned.getValue(PresetCategoryId.MESSAGING_OPT_IN).defaultSelected)

        // Both curated CN game and uncatalogued CATEGORY_GAME app are caught
        assertEquals(
            setOf("com.miHoYo.Yuanshen", "com.example.indie.rpg"),
            scanned.getValue(PresetCategoryId.GAMES).matchedApps.map { it.packageName }.toSet()
        )
        assertEquals(
            setOf("com.dragon.read"),
            scanned.getValue(PresetCategoryId.READING_NEWS).matchedApps.map { it.packageName }.toSet()
        )
        assertEquals(
            setOf("com.taobao.taobao"),
            scanned.getValue(PresetCategoryId.SHOPPING).matchedApps.map { it.packageName }.toSet()
        )

        // Essential apps and Mindstop itself are never classified
        assertNull(PresetGroupScanner.classify(InstalledAppInfo("com.eg.android.AlipayGphone", "支付宝", AppCategoryHint.SOCIAL)))
        assertNull(PresetGroupScanner.classify(InstalledAppInfo("com.autonavi.minimap", "高德地图", AppCategoryHint.OTHER)))
        assertNull(PresetGroupScanner.classify(InstalledAppInfo("app.mindstop.android.debug", "知止"), ownPackage = "app.mindstop.android.debug"))
    }

    @Test
    fun respectsExemptListAndDeduplicatesPackages() {
        val installed = listOf(
            InstalledAppInfo("tv.danmaku.bili", "哔哩哔哩"),
            InstalledAppInfo("tv.danmaku.bili", "哔哩哔哩"),
            InstalledAppInfo("com.google.android.youtube", "YouTube", AppCategoryHint.VIDEO)
        )
        val scanned = PresetGroupScanner.scan(installed, exempt = setOf("tv.danmaku.bili"))
        val video = scanned.first { it.preset == PresetCategoryId.SHORT_VIDEO }
        assertEquals(listOf("com.google.android.youtube"), video.matchedApps.map { it.packageName })
    }

    @Test
    fun buildRuleMergesExistingCustomLimitsAndPackages() {
        val fresh = PresetGroupScanner.buildRule(
            preset = PresetCategoryId.SHORT_VIDEO,
            packages = setOf("tv.danmaku.bili", "com.ss.android.ugc.aweme"),
            localizedName = "短视频与视频流"
        )
        assertEquals("preset_short_video", fresh.id)
        assertEquals(RuleScope.GROUP, fresh.scope)
        assertEquals(45 * 60_000L, fresh.dailyLimitMs)
        assertEquals(20 * 60_000L, fresh.sessionLimitMs)

        // Re-scanning after user customized daily limit to 30m and added another app preserves custom settings
        val customized = fresh.copy(name = "我的视频组", dailyLimitMs = 30 * 60_000L)
        val merged = PresetGroupScanner.buildRule(
            preset = PresetCategoryId.SHORT_VIDEO,
            packages = setOf("tv.danmaku.bili", "com.google.android.youtube"),
            localizedName = "短视频与视频流",
            existing = customized
        )
        assertEquals("我的视频组", merged.name)
        assertEquals(30 * 60_000L, merged.dailyLimitMs)
        assertEquals(setOf("tv.danmaku.bili", "com.ss.android.ugc.aweme", "com.google.android.youtube"), merged.packages)
    }
}
