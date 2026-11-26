package app.mindstop.core

/**
 * Behavioral-category preset groups grounded in smartphone dependency patterns:
 * short-form video, social/community feeds, mobile games, novels/comics/news, shopping/live
 * commerce, and an explicit opt-in messaging group (never enabled by default so urgent
 * communication and payments stay safe).
 */
enum class PresetCategoryId(
    val ruleId: String,
    val defaultDailyMinutes: Int,
    val defaultSessionMinutes: Int,
    val recommendedByDefault: Boolean,
    val optInOnly: Boolean = false
) {
    SHORT_VIDEO("preset_short_video", defaultDailyMinutes = 45, defaultSessionMinutes = 20, recommendedByDefault = true),
    SOCIAL_FEED("preset_social_feed", defaultDailyMinutes = 30, defaultSessionMinutes = 15, recommendedByDefault = false),
    GAMES("preset_games", defaultDailyMinutes = 60, defaultSessionMinutes = 30, recommendedByDefault = false),
    READING_NEWS("preset_reading_news", defaultDailyMinutes = 45, defaultSessionMinutes = 20, recommendedByDefault = false),
    SHOPPING("preset_shopping", defaultDailyMinutes = 30, defaultSessionMinutes = 15, recommendedByDefault = false),
    MESSAGING_OPT_IN("preset_messaging", defaultDailyMinutes = 45, defaultSessionMinutes = 15, recommendedByDefault = false, optInOnly = true)
}

/** Platform-agnostic projection of Android ApplicationInfo.category. */
enum class AppCategoryHint {
    VIDEO,
    GAME,
    SOCIAL,
    NEWS,
    OTHER
}

data class InstalledAppInfo(
    val packageName: String,
    val label: String,
    val categoryHint: AppCategoryHint = AppCategoryHint.OTHER
)

data class ScannedPresetGroup(
    val preset: PresetCategoryId,
    val matchedApps: List<InstalledAppInfo>,
    val defaultSelected: Boolean
)

/**
 * Pure Kotlin two-engine app classifier:
 * 1) Curated domestic (China) + international package catalog — fixes missing Android category
 *    metadata on major Chinese apps (Bilibili, Xiaohongshu, Douyin, Kuaishou, Weibo, Zhihu, etc.).
 * 2) Android ApplicationInfo.category fallback — automatically discovers niche games, overseas
 *    video apps, and newly installed titles.
 * Essential communication, payment, work, navigation, and utility apps are strictly excluded from
 * automatic entertainment buckets.
 */
object PresetGroupScanner {

    val ESSENTIAL_SAFE_EXCLUDES: Set<String> = setOf(
        // Payment, work, maps, transit, food, meetings, local media players
        "com.eg.android.AlipayGphone",
        "com.alibaba.android.rimet",
        "com.ss.android.lark",
        "com.tencent.wework",
        "com.tencent.wemeet.app",
        "us.zoom.videomeetings",
        "com.microsoft.teams",
        "com.autonavi.minimap",
        "com.baidu.BaiduMap",
        "com.google.android.apps.maps",
        "com.sankuai.meituan",
        "me.ele",
        "com.sdu.didi.psnger",
        "com.MobileTicket",
        "com.google.android.gm",
        "com.microsoft.office.outlook",
        "org.videolan.vlc",
        "com.mxtech.videoplayer.ad",
        "com.mxtech.videoplayer.pro",
        "com.miui.gallery",
        "com.miui.calculator",
        "com.android.deskclock",
        "com.android.calendar",
        "com.android.contacts",
        "com.android.dialer",
        "com.android.mms",
        "com.android.settings",
        "com.android.camera",
        "com.miui.weather2"
    )

    val MESSAGING_PACKAGES: Set<String> = setOf(
        "com.tencent.mm",
        "com.tencent.mobileqq",
        "com.tencent.tim",
        "com.whatsapp",
        "com.whatsapp.w4b",
        "org.telegram.messenger",
        "org.telegram.messenger.web",
        "org.thunderdog.challegram",
        "jp.naver.line.android",
        "com.kakao.talk",
        "com.facebook.orca",
        "com.viber.voip",
        "org.thoughtcrime.securesms"
    )

    val SHORT_VIDEO_PACKAGES: Set<String> = setOf(
        // Domestic China short & long video / live streaming
        "com.ss.android.ugc.aweme",          // Douyin
        "com.ss.android.ugc.aweme.lite",     // Douyin Lite
        "com.ss.android.ugc.live",           // Douyin Huoshan
        "com.smile.gifmaker",                // Kuaishou
        "com.kuaishou.nebula",               // Kuaishou Lite
        "tv.danmaku.bili",                   // Bilibili
        "com.bilibili.app.in",               // Bilibili International
        "com.xingin.xhs",                    // Xiaohongshu (RED)
        "com.ss.android.article.video",      // Xigua Video
        "com.tencent.qqlive",                // Tencent Video
        "com.qiyi.video",                    // iQIYI
        "com.qiyi.video.lite",               // iQIYI Lite
        "com.youku.phone",                   // Youku
        "com.hunantv.imgo.activity",         // Mango TV
        "com.duowan.kiwi",                   // Huya Live
        "air.tv.douyu.android",              // Douyu Live
        "com.tencent.weishi",                // Weishi
        "com.baidu.haokan",                  // Haokan Video
        "com.le123.ysdq",                    // Yingshi Daquan
        "com.cmcc.cmvideo",                  // Migu Video
        "com.sohu.sohuvideo",                // Sohu Video
        // International video & live streaming
        "com.google.android.youtube",        // YouTube
        "com.zhiliaoapp.musically",          // TikTok Global
        "com.ss.android.ugc.trill",          // TikTok Asia
        "com.netflix.mediaclient",           // Netflix
        "tv.twitch.android.app",             // Twitch
        "com.disney.disneyplus",             // Disney+
        "com.amazon.avod.thirdpartyclient",  // Prime Video
        "com.wbd.stream",                    // Max
        "com.hbo.hbonow",                    // HBO
        "com.vimeo.android.videoapp",        // Vimeo
        "com.dailymotion.dailymotion",       // Dailymotion
        "tv.danmaku.bilibilihd"              // Bilibili HD
    )

    val SOCIAL_FEED_PACKAGES: Set<String> = setOf(
        // Domestic communities & feeds
        "com.sina.weibo",                    // Weibo
        "com.sina.weibolite",                // Weibo Lite
        "com.zhihu.android",                 // Zhihu
        "com.douban.frodo",                  // Douban
        "com.baidu.tieba",                   // Baidu Tieba
        "com.hupu.games",                    // Hupu
        "gov.pianzong.androidnga",           // NGA
        "com.ruguoapp.jike",                 // Jike
        "com.coolapk.market",                // Coolapk
        "com.lofter.android",                // LOFTER
        "com.immomo.momo",                   // Momo
        "cn.soulapp.android",                // Soul
        "com.p1.mobile.putong",              // Tantan
        "com.xueqiu.android",                // Xueqiu community
        // International social feeds
        "com.instagram.android",             // Instagram
        "com.instagram.barcelona",           // Threads
        "com.twitter.android",               // X / Twitter
        "com.reddit.frontpage",              // Reddit
        "com.facebook.katana",               // Facebook
        "com.facebook.lite",                 // Facebook Lite
        "com.discord",                       // Discord
        "com.snapchat.android",              // Snapchat
        "com.pinterest",                     // Pinterest
        "com.tumblr",                        // Tumblr
        "xyz.blueskyweb.app",                // Bluesky
        "com.quora.android"                  // Quora
    )

    val GAME_PACKAGES: Set<String> = setOf(
        // Popular domestic & international mobile games / game hubs
        "com.tencent.tmgp.sgame",            // Honor of Kings
        "com.tencent.tmgp.pubgmhd",          // Game for Peace
        "com.miHoYo.Yuanshen",               // Genshin Impact CN
        "com.miHoYo.GenshinImpact",          // Genshin Impact Global
        "com.miHoYo.hkrpg",                  // Honkai Star Rail CN
        "com.HoYoverse.hkrpgoversea",        // Honkai Star Rail Global
        "com.miHoYo.Nap",                    // Zenless Zone Zero CN
        "com.HoYoverse.Nap",                 // Zenless Zone Zero Global
        "com.tencent.jkchess",               // Battle of Golden Spatula
        "com.tencent.lolm",                  // League of Legends Wild Rift CN
        "com.riotgames.league.wildrift",     // Wild Rift Global
        "com.netease.party",                 // Eggy Party
        "com.netease.dwrg",                  // Identity V
        "com.hypergryph.arknights",          // Arknights CN
        "com.YoStarEN.Arknights",            // Arknights Global
        "com.bilibili.azurlane",             // Azur Lane CN
        "com.YoStarEN.AzurLane",             // Azur Lane Global
        "com.tencent.tmgp.cf",               // CrossFire Mobile
        "com.tencent.tmgp.cod",              // Call of Duty Mobile CN
        "com.activision.callofduty.shooter", // Call of Duty Mobile Global
        "com.kurogame.mingchao",             // Wuthering Waves CN
        "com.kurogame.wutheringwaves.global",// Wuthering Waves Global
        "com.tencent.ig",                    // PUBG Mobile
        "com.roblox.client",                 // Roblox
        "com.supercell.brawlstars",          // Brawl Stars
        "com.supercell.clashroyale",         // Clash Royale
        "com.supercell.clashofclans",        // Clash of Clans
        "com.mojang.minecraftpe",            // Minecraft
        "com.valvesoftware.android.steam.community",
        "com.taptap"                         // TapTap
    )

    val READING_NEWS_PACKAGES: Set<String> = setOf(
        // Domestic novels, comics, news feeds
        "com.dragon.read",                   // Fanqie Novel
        "com.qidian.QDReader",               // Qidian
        "com.kmxs.reader",                   // Qimao Novel
        "com.jjwxc.reader",                  // Jinjiang
        "com.shuqi.controller",              // Shuqi Novel
        "com.chaozh.iReaderFree",            // iReader
        "com.bilibili.comic",                // Bilibili Comics
        "com.kuaikan.comic",                 // Kuaikan Comics
        "com.tencent.qqcomic",               // Tencent Comics
        "com.ss.android.article.news",       // Toutiao
        "com.ss.android.article.lite",       // Toutiao Lite
        "com.tencent.news",                  // Tencent News
        "com.netease.newsreader.activity",   // NetEase News
        "com.ifeng.news2",                   // Phoenix News
        "com.sina.news",                     // Sina News
        "com.sohu.newsclient",               // Sohu News
        "com.hipu.y",                        // Yidian Zixun
        // International comics & news feeds
        "com.naver.linewebtoon",             // WEBTOON
        "com.wattpad",                       // Wattpad
        "com.flipboard.app"                  // Flipboard
    )

    val SHOPPING_PACKAGES: Set<String> = setOf(
        // Domestic shopping & live commerce
        "com.taobao.taobao",                 // Taobao
        "com.taobao.litetao",                // Taote
        "com.xunmeng.pinduoduo",             // Pinduoduo
        "com.jingdong.app.mall",             // JD.com
        "com.taobao.idlefish",               // Xianyu
        "com.shizhuang.duapp",               // Dewu / Poizon
        "com.achievo.vipshop",               // Vipshop
        "com.suning.mobile.ebuy",            // Suning
        "com.dangdang.buy2",                 // Dangdang
        // International shopping
        "com.amazon.mShop.android.shopping", // Amazon
        "com.einnovation.temu",              // Temu
        "com.zzkko",                         // SHEIN
        "com.ebay.mobile",                   // eBay
        "com.alibaba.aliexpresshd"           // AliExpress
    )

    /** Classifies one installed app into at most one [PresetCategoryId], or null if non-addictive / excluded. */
    fun classify(app: InstalledAppInfo, exempt: Set<String> = emptySet(), ownPackage: String = ""): PresetCategoryId? {
        val pkg = app.packageName
        if (pkg.isBlank() || pkg == ownPackage || pkg in exempt || pkg in ESSENTIAL_SAFE_EXCLUDES) return null

        // 1. Explicit curated catalog (deterministic priority; messaging always isolated first)
        return when {
            pkg in MESSAGING_PACKAGES -> PresetCategoryId.MESSAGING_OPT_IN
            pkg in SHORT_VIDEO_PACKAGES -> PresetCategoryId.SHORT_VIDEO
            pkg in GAME_PACKAGES -> PresetCategoryId.GAMES
            pkg in SOCIAL_FEED_PACKAGES -> PresetCategoryId.SOCIAL_FEED
            pkg in READING_NEWS_PACKAGES -> PresetCategoryId.READING_NEWS
            pkg in SHOPPING_PACKAGES -> PresetCategoryId.SHOPPING
            // 2. Android ApplicationInfo.category fallback for uncatalogued domestic & overseas apps
            app.categoryHint == AppCategoryHint.GAME -> PresetCategoryId.GAMES
            app.categoryHint == AppCategoryHint.VIDEO -> PresetCategoryId.SHORT_VIDEO
            app.categoryHint == AppCategoryHint.SOCIAL -> PresetCategoryId.SOCIAL_FEED
            app.categoryHint == AppCategoryHint.NEWS -> PresetCategoryId.READING_NEWS
            else -> null
        }
    }

    /** Scans all installed apps and groups matched apps into the 6 ordered preset categories. */
    fun scan(
        installed: List<InstalledAppInfo>,
        exempt: Set<String> = emptySet(),
        ownPackage: String = ""
    ): List<ScannedPresetGroup> {
        val buckets = LinkedHashMap<PresetCategoryId, MutableList<InstalledAppInfo>>()
        for (preset in PresetCategoryId.entries) {
            buckets[preset] = ArrayList()
        }
        val seen = HashSet<String>()
        for (app in installed) {
            if (!seen.add(app.packageName)) continue
            val target = classify(app, exempt, ownPackage) ?: continue
            buckets[target]?.add(app)
        }
        return PresetCategoryId.entries.map { preset ->
            val matched = buckets[preset].orEmpty().sortedBy { it.label.lowercase() }
            val defaultSelect = preset.recommendedByDefault && matched.isNotEmpty()
            ScannedPresetGroup(
                preset = preset,
                matchedApps = matched,
                defaultSelected = defaultSelect
            )
        }
    }

    /**
     * Builds (or updates) a [QuotaRule] for [preset] with the scanned [packages].
     * When [existing] is provided, preserves the user's customized limits and name while merging
     * newly discovered packages so re-scanning never wipes manual customizations.
     */
    fun buildRule(
        preset: PresetCategoryId,
        packages: Set<String>,
        localizedName: String,
        existing: QuotaRule? = null
    ): QuotaRule {
        val mergedPackages = if (existing != null) existing.packages + packages else packages
        val scope = if (mergedPackages.size == 1) RuleScope.APP else RuleScope.GROUP
        return QuotaRule(
            id = existing?.id ?: preset.ruleId,
            name = existing?.name?.takeIf { it.isNotBlank() } ?: localizedName,
            scope = scope,
            packages = mergedPackages,
            dailyLimitMs = existing?.dailyLimitMs ?: (preset.defaultDailyMinutes * 60_000L),
            sessionLimitMs = existing?.sessionLimitMs ?: (preset.defaultSessionMinutes * 60_000L),
            enabled = true
        )
    }
}
