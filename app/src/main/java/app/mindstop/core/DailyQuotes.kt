package app.mindstop.core

import kotlin.random.Random

/**
 * Offline daily quote catalog and non-repeating deck selector.
 * Keeps zero network permissions while rotating through 100 curated bilingual entries
 * (one automatic advance per calendar day, plus optional manual advance).
 */
internal object DailyQuotes {

    data class Quote(
        val id: String,
        val zh: String,
        val en: String,
        val zhSource: String? = null,
        val enSource: String? = null,
    )

    val catalog: List<Quote> = listOf(
        // Original 12 (IDs preserved for seamless DataStore upgrade)
        Quote(
            "purpose",
            "带着目的打开，带着满足离开。",
            "Open with purpose. Leave with satisfaction."
        ),
        Quote(
            "enjoy",
            "你可以享受这一刻，也可以决定何时结束。",
            "Enjoy the moment. Choose when it ends."
        ),
        Quote(
            "promise",
            "今天又兑现了一个对自己的承诺。",
            "You kept another promise to yourself today."
        ),
        Quote(
            "rest",
            "休息也是计划的一部分。",
            "Rest is part of the plan, too."
        ),
        Quote(
            "return",
            "发现走神，就是回到目标的开始。",
            "Noticing a distraction is the first step back to your goal."
        ),
        Quote(
            "next",
            "把下一步留给自己。",
            "Keep the next move yours."
        ),
        Quote(
            "room",
            "适时停下，给在意的事留一点时间。",
            "Stop when you choose, and make time for what matters."
        ),
        Quote(
            "small",
            "每一次按计划停下，都算数。",
            "Every time you stop as planned, it counts."
        ),
        Quote(
            "enough",
            "觉得够了，就可以停下。",
            "When it feels like enough, you can stop."
        ),
        Quote(
            "choice",
            "先问问自己，这一刻想做什么。",
            "Ask yourself what you want to do with this moment."
        ),
        Quote(
            "dao",
            "知足不辱，知止不殆，可以长久。",
            "Know what is enough and when to stop. Then you can endure.",
            "《道德经》第四十四章",
            "Tao Te Ching, Chapter 44"
        ),
        Quote(
            "walden",
            "我走进树林，因为我想有意识地生活。",
            "I went to the woods because I wished to live deliberately.",
            "梭罗《瓦尔登湖》",
            "Henry David Thoreau, Walden"
        ),

        // Classics (public domain, verified sources)
        Quote(
            "daxue",
            "知止而后有定，定而后能静。",
            "Knowing where to stop brings steadiness; steadiness brings calm.",
            "《礼记·大学》",
            "The Great Learning"
        ),
        Quote(
            "lunyu_time",
            "逝者如斯夫，不舍昼夜。",
            "Time passes like this flowing stream, never pausing day or night.",
            "《论语·子罕》",
            "Analects of Confucius, Book 9"
        ),
        Quote(
            "xunzi_step",
            "不积跬步，无以至千里。",
            "Without taking small steps, one cannot travel a thousand miles.",
            "《荀子·劝学》",
            "Xunzi, Encouraging Learning"
        ),
        Quote(
            "aurelius_mind",
            "你拥有掌控自己心智的力量，而非外在事件。领悟这一点，你便获得力量。",
            "You have power over your mind, not outside events. Realize this, and you will find strength.",
            "马可·奥勒留《沉思录》",
            "Marcus Aurelius, Meditations"
        ),
        Quote(
            "aurelius_dawn",
            "清晨不愿起身时，告诉自己：我醒来是为了做人该做的事。",
            "At dawn, when you have trouble getting out of bed, tell yourself: I have to go to work as a human being.",
            "马可·奥勒留《沉思录》",
            "Marcus Aurelius, Meditations"
        ),
        Quote(
            "seneca_time",
            "不是我们拥有的时间太少，而是我们浪费得太多。",
            "It is not that we have a short time to live, but that we waste a lot of it.",
            "塞涅卡《论生命之短暂》",
            "Seneca, On the Shortness of Life"
        ),
        Quote(
            "seneca_busy",
            "匆忙而无目的，不是勤奋，只是疲惫。",
            "Restlessness is not industry; motion without purpose only tires the mind.",
            "塞涅卡《道德书简》",
            "Seneca, Moral Letters"
        ),
        Quote(
            "epictetus_free",
            "无法主宰自己的人，称不上自由。",
            "No one is free who is not master of themselves.",
            "爱比克泰德《语录》",
            "Epictetus, Discourses"
        ),
        Quote(
            "pascal_room",
            "人类许多烦恼，源于无法安静地独处一室。",
            "Many of humanity's troubles stem from an inability to sit quietly in a room alone.",
            "帕斯卡《思想录》",
            "Blaise Pascal, Pensees"
        ),
        Quote(
            "zhuzi_dawn",
            "黎明即起，洒扫庭除，要内外整洁。",
            "Rise at dawn, sweep the courtyard, and keep both inner and outer spaces clear.",
            "《朱子家训》",
            "Maxims of Zhu Bolu"
        ),
        Quote(
            "laozi_know",
            "胜人者有力，自胜者强。",
            "Mastering others takes strength; mastering yourself is true power.",
            "《道德经》第三十三章",
            "Tao Te Ching, Chapter 33"
        ),
        Quote(
            "zhuangzi_focus",
            "用志不分，乃凝于神。",
            "When intention is undivided, the spirit gathers into clarity.",
            "《庄子·达生》",
            "Zhuangzi, Mastering Life"
        ),

        // Self-control & Agency (知止与选择权)
        Quote(
            "agency_1",
            "休息不是浪费时间，失去选择权才是。",
            "Rest is not wasted time. Losing control is."
        ),
        Quote(
            "agency_2",
            "别让下一条推送决定你的下一步。",
            "Do not let the next feed item decide your next move."
        ),
        Quote(
            "agency_3",
            "真正的自由，是能决定何时开始，也能决定何时结束。",
            "True freedom is choosing when to begin and when to stop."
        ),
        Quote(
            "agency_4",
            "手机是工具，方向盘一直在你手里。",
            "Your phone is a tool. You still hold the steering wheel."
        ),
        Quote(
            "agency_5",
            "停下三秒，很多冲动自己就会退潮。",
            "Pause for three seconds, and many urges fade on their own."
        ),
        Quote(
            "agency_6",
            "你不需要完美自律，只需要多一次清醒的选择。",
            "You do not need perfect discipline, just one more conscious choice."
        ),
        Quote(
            "agency_7",
            "把注意力花在真正滋养你的事情上。",
            "Spend your attention on things that truly nourish you."
        ),
        Quote(
            "agency_8",
            "能随时放下屏幕，才算真正拥有休闲。",
            "Leisure feels real when you can set the screen down at will."
        ),
        Quote(
            "agency_9",
            "算法很懂如何留住你，但只有你懂自己想去哪里。",
            "Algorithms know how to keep you; only you know where you want to go."
        ),
        Quote(
            "agency_10",
            "先问自己是不是真的想看，还是只是手惯性点开了。",
            "Ask whether you truly want to watch, or simply tapped out of habit."
        ),
        Quote(
            "agency_11",
            "给娱乐设边界，反而让娱乐更尽兴。",
            "Setting a boundary around fun makes the fun feel better."
        ),
        Quote(
            "agency_12",
            "一次超时不会抹去你过去所有的好习惯。",
            "One late session never erases all the good habits you built."
        ),
        Quote(
            "agency_13",
            "克制不是苛责自己，而是保护未来的自己。",
            "Self restraint is not punishment; it is caring for your future self."
        ),
        Quote(
            "agency_14",
            "好习惯靠温和坚持，不靠一时自责。",
            "Lasting habits grow from gentle consistency, not harsh guilt."
        ),
        Quote(
            "agency_15",
            "关掉屏幕那一刻的清爽，比多刷十分钟更难得。",
            "The clarity of locking your screen beats ten more minutes of scrolling."
        ),
        Quote(
            "agency_16",
            "不必和所有娱乐为敌，只需分清主次。",
            "Entertainment is not the enemy; losing your priorities is."
        ),
        Quote(
            "agency_17",
            "现在停下，依然来得及。",
            "It is never too late to stop right now."
        ),
        Quote(
            "agency_18",
            "把碎片时间还给呼吸、散步和发呆。",
            "Give spare minutes back to breathing, walking, and quiet thought."
        ),
        Quote(
            "agency_19",
            "越是疲惫时，越要选真正能恢复精力的休息方式。",
            "When tired, choose rest that actually restores your energy."
        ),
        Quote(
            "agency_20",
            "你的注意力很珍贵，别廉价交给无限下拉。",
            "Your attention is precious. Do not trade it cheaply to an endless scroll."
        ),

        // Focus & Learning (专注、学习与复盘)
        Quote(
            "focus_1",
            "一次只做好眼前这一件事。",
            "Do just one thing well right now."
        ),
        Quote(
            "focus_2",
            "开始前写下目标，比事后懊悔更省力。",
            "Writing a goal before you start beats regretting how time passed."
        ),
        Quote(
            "focus_3",
            "查完资料就回来，别顺手滑进推荐流。",
            "Return right after your lookup, before the recommendation feed pulls you in."
        ),
        Quote(
            "focus_4",
            "允许自己短暂停顿，但记得回到主线。",
            "Allow brief pauses, and remember the way back to your main track."
        ),
        Quote(
            "focus_5",
            "专注二十分钟的收获，胜过心不在焉两小时。",
            "Twenty focused minutes outweigh two distracted hours."
        ),
        Quote(
            "focus_6",
            "先完成一个最小步骤，阻力就会减半。",
            "Finish one tiny step first, and half the resistance disappears."
        ),
        Quote(
            "focus_7",
            "复盘不是批评自己，而是看清下次怎么更顺手。",
            "Reflection is not blame; it is seeing how to make next time smoother."
        ),
        Quote(
            "focus_8",
            "把手机放远一臂的距离，大脑就安静一半。",
            "Keep the phone an arm's length away, and your mind grows quieter."
        ),
        Quote(
            "focus_9",
            "深度思考需要一段不被通知打断的空白。",
            "Deep thinking needs a stretch of silence free from notifications."
        ),
        Quote(
            "focus_10",
            "慢一点没关系，只要方向是你自己选的。",
            "Going slow is fine when the direction is truly yours."
        ),
        Quote(
            "focus_11",
            "今天的专注，是在给明天的从容攒底气。",
            "Today's focus builds tomorrow's calm."
        ),
        Quote(
            "focus_12",
            "任务太大时，只看接下来十五分钟要做什么。",
            "When a task feels huge, look only at the next fifteen minutes."
        ),
        Quote(
            "focus_13",
            "少开一个无关标签页，就少一次注意力泄漏。",
            "One fewer stray tab means one fewer leak in your attention."
        ),
        Quote(
            "focus_14",
            "把学习当成探索，而不是应付倒计时。",
            "Treat study as exploration, not merely surviving a timer."
        ),
        Quote(
            "focus_15",
            "记录真实的自己，比维持漂亮的数字更有用。",
            "Honest records teach more than flattering numbers ever will."
        ),
        Quote(
            "focus_16",
            "合上书和合上手机一样，都需要一个清晰的句号。",
            "Closing an app, like closing a book, deserves a clear full stop."
        ),

        // Bedtime & Wind-down (夜间收尾与安眠)
        Quote(
            "night_1",
            "白天没做完的事，明天清醒时做更快。",
            "Unfinished tasks move faster with a rested mind tomorrow."
        ),
        Quote(
            "night_2",
            "今晚早点收尾，是送给明天早晨最好的礼物。",
            "Winding down on time tonight is the best gift to tomorrow morning."
        ),
        Quote(
            "night_3",
            "深夜的视频明天还在，但今晚的睡眠不会重来。",
            "Videos will still be there tomorrow; tonight's sleep will not repeat."
        ),
        Quote(
            "night_4",
            "让屏幕比你先休息十分钟。",
            "Let your screen rest ten minutes before you do."
        ),
        Quote(
            "night_5",
            "睡前不需要再吸收新信息，只需让思绪慢慢降落。",
            "Before bed, you need no new feeds, only a gentle landing."
        ),
        Quote(
            "night_6",
            "今晚已经很努力了，可以安心合上手机。",
            "You did enough today. It is safe to put the phone down."
        ),
        Quote(
            "night_7",
            "留一点睡前缓冲，把白天的喧嚣关在门外。",
            "Keep a quiet buffer before bed to leave daytime noise outside."
        ),
        Quote(
            "night_8",
            "哪怕比计划晚了一点，现在放下手机依然值得肯定。",
            "Even if it is past your plan, stopping right now still matters."
        ),
        Quote(
            "night_9",
            "调暗灯光，放慢节奏，告诉身体一天已经收官。",
            "Dim the light, slow the pace, and let your body know the day is done."
        ),
        Quote(
            "night_10",
            "好睡眠不是靠强迫入睡，而是靠提前停下刺激。",
            "Good sleep starts not by forcing rest, but by lowering stimulation early."
        ),
        Quote(
            "night_11",
            "把没看完的内容放进收藏夹，而不是放进熬夜里。",
            "Save unfinished posts for daylight, not for midnight."
        ),
        Quote(
            "night_12",
            "安静的夜晚，适合听一首舒缓的曲子，或者什么也不做。",
            "A quiet night fits soft music, or simply doing nothing at all."
        ),
        Quote(
            "night_13",
            "给手机充上电，也给自己充上电。",
            "Plug in your phone, and let yourself recharge too."
        ),
        Quote(
            "night_14",
            "今天的句号画得越干脆，明天的开头越轻盈。",
            "A clean ending tonight makes tomorrow's start lighter."
        ),

        // Morning Activation (晨间启动与唤醒)
        Quote(
            "morning_1",
            "醒来后先喝一口温水，再决定今天看什么消息。",
            "Drink a glass of water first before checking messages."
        ),
        Quote(
            "morning_2",
            "刚醒时觉得困很正常，先拉开窗帘让光线帮帮你。",
            "Feeling groggy on waking is normal. Open the curtains and let light help."
        ),
        Quote(
            "morning_3",
            "不必刚睁眼就冲刺，先给自己十五分钟温和启动。",
            "No need to sprint at wake up. Give yourself fifteen gentle minutes."
        ),
        Quote(
            "morning_4",
            "早晨有一件期待的小事，起床就会容易很多。",
            "Having one small joy to look forward to makes mornings much easier."
        ),
        Quote(
            "morning_5",
            "洗把脸、伸个懒腰，身体动起来，困意就会散开。",
            "Wash your face and stretch; once the body moves, sleepiness clears."
        ),
        Quote(
            "morning_6",
            "早晨的头十分钟，别急着交给别人的消息和热搜。",
            "Do not hand your first ten morning minutes to hot trends and alerts."
        ),
        Quote(
            "morning_7",
            "把今天过成一个你愿意回想的日子。",
            "Make today a day you will be glad to look back on."
        ),
        Quote(
            "morning_8",
            "新的一天从双脚落地开始，不需要太多犹豫。",
            "A new day begins the moment your feet touch the floor."
        ),
        Quote(
            "morning_9",
            "听一首喜欢的歌，陪自己平稳度过起床过渡期。",
            "Play a favorite song to carry you smoothly through the morning fog."
        ),
        Quote(
            "morning_10",
            "昨天的遗憾留在昨天，今天有全新的额度。",
            "Leave yesterday's slips behind; today arrives with a fresh slate."
        ),
        Quote(
            "morning_11",
            "穿好衣服、整理桌面，用一个小动作开启掌控感。",
            "Get dressed or tidy your desk; one small action sparks momentum."
        ),
        Quote(
            "morning_12",
            "允许自己慢慢清醒，只要不重新滑回被窝。",
            "Take your time waking up, as long as you stay out of bed."
        ),
        Quote(
            "morning_13",
            "认真吃一顿早饭，比刷半小时信息流更有能量。",
            "A real breakfast fuels you far better than half an hour of scrolling."
        ),
        Quote(
            "morning_14",
            "起床后先做一件简单确定的事，给自己一个好开头。",
            "Start with one simple, certain task to set a steady tone."
        ),

        // Intentional Shopping & Clarity (理性消费与生活质感)
        Quote(
            "shop_1",
            "真正需要的东西，放二十四小时后依然值得买。",
            "Something you truly need will still be worth buying after twenty four hours."
        ),
        Quote(
            "shop_2",
            "省下的不止是预算，还有反复比价消耗的心力。",
            "You save not only money, but the energy drained by endless comparison."
        ),
        Quote(
            "shop_3",
            "好物让人生活更轻盈，而不是让房间和账单更拥挤。",
            "Good purchases lighten your life rather than crowding your space and budget."
        ),
        Quote(
            "shop_4",
            "找齐三个靠谱候选，就可以停下来做决定了。",
            "Once you have three solid options, you can stop searching and decide."
        ),
        Quote(
            "shop_5",
            "低价不是唯一标准，适合且省心同样珍贵。",
            "Lowest price is not everything; fit and peace of mind count too."
        ),
        Quote(
            "shop_6",
            "先写清预算和刚需，再去逛商城，才不容易迷路。",
            "Write your budget and must haves before browsing so you do not get lost."
        ),
        Quote(
            "shop_7",
            "花一小时为省十块钱反复纠结，也许不划算。",
            "Spending an hour agonizing to save a small coin may cost more than it saves."
        ),
        Quote(
            "shop_8",
            "买下改善生活的好物很棒，先放进待买清单看看也很好。",
            "Buying something that improves life is great; parking it on a wishlist first is smart too."
        ),
        Quote(
            "shop_9",
            "不被限时倒计时催促，消费的节奏由你掌握。",
            "Do not let flash countdowns rush you; you set the pace of your spending."
        ),
        Quote(
            "shop_10",
            "看清单位价格，也看清自己的真实使用频率。",
            "Check the unit price, and check how often you will truly use it."
        ),
        Quote(
            "shop_11",
            "克制冲动，是为了在真正想要的事物上更从容。",
            "Holding back on impulse leaves more room for what you genuinely value."
        ),
        Quote(
            "shop_12",
            "少而精的选择，往往带来更长久的满足。",
            "Fewer, better choices tend to bring longer lasting satisfaction."
        ),
    )

    val byId: Map<String, Quote> = catalog.associateBy { it.id }
    val ids: List<String> = catalog.map { it.id }

    data class State(val current: String, val day: String, val remaining: List<String>) {
        fun encode(): String = listOf(current, day, remaining.joinToString(",")).joinToString("|")
    }

    fun decode(value: String): State? {
        val parts = value.split('|')
        if (parts.size != 3 || parts[0] !in byId || parts[1].isEmpty()) return null
        val remaining = parts[2].split(',').filter { it in byId && it != parts[0] }.distinct()
        return State(parts[0], parts[1], remaining)
    }

    fun select(previous: State?, day: String, advance: Boolean = false, random: Random = Random.Default): State {
        if (!advance && previous?.day == day) return previous

        val remaining = previous?.remaining.orEmpty().ifEmpty {
            val round = ids.shuffled(random)
            if (round.first() == previous?.current) round.drop(1) + round.first() else round
        }
        return State(remaining.first(), day, remaining.drop(1))
    }
}
