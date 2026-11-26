package app.mindstop.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Mindstop's own store, kept separate from Curbox's curbox_db so a destructive migration there
 * can never wipe quota rules or usage history. Migrations must be explicit: bump the version and
 * add a Migration instead of falling back to destructive migration.
 */
@Database(
    entities = [RuleEntity::class, UsageSegmentEntity::class, GrantEntity::class, EventEntity::class, MetaEntity::class, IntentionEntity::class,
        BankQuestionEntity::class, ChallengeEntity::class, CooldownEntity::class, RewardEntity::class,
        ReflectionEntity::class, FocusSessionEntity::class, BedtimeLogEntity::class,
        MorningAlarmEntity::class, MorningSessionEntity::class,
        ShoppingTaskEntity::class, ProductCandidateEntity::class, PurchaseBacklogEntity::class, ExpenseRecordEntity::class],
    version = 9,
    exportSchema = false
)
abstract class MindstopDatabase : RoomDatabase() {

    abstract fun ruleDao(): RuleDao
    abstract fun usageSegmentDao(): UsageSegmentDao
    abstract fun grantDao(): GrantDao
    abstract fun eventDao(): EventDao
    abstract fun metaDao(): MetaDao
    abstract fun intentionDao(): IntentionDao
    abstract fun bankQuestionDao(): BankQuestionDao
    abstract fun challengeDao(): ChallengeDao
    abstract fun cooldownDao(): CooldownDao
    abstract fun rewardDao(): RewardDao
    abstract fun reflectionDao(): ReflectionDao
    abstract fun focusSessionDao(): FocusSessionDao
    abstract fun bedtimeLogDao(): BedtimeLogDao
    abstract fun morningAlarmDao(): MorningAlarmDao
    abstract fun morningSessionDao(): MorningSessionDao
    abstract fun shoppingTaskDao(): ShoppingTaskDao
    abstract fun productCandidateDao(): ProductCandidateDao
    abstract fun purchaseBacklogDao(): PurchaseBacklogDao
    abstract fun expenseRecordDao(): ExpenseRecordDao

    companion object {
        @Volatile
        private var INSTANCE: MindstopDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `intentions` (
                        `sessionId` INTEGER NOT NULL PRIMARY KEY,
                        `packageName` TEXT NOT NULL,
                        `wall` INTEGER NOT NULL,
                        `minutes` INTEGER
                    )""".trimIndent()
                )
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `bank_questions` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `type` TEXT NOT NULL,
                        `question` TEXT NOT NULL,
                        `questionKey` TEXT NOT NULL,
                        `optionA` TEXT NOT NULL,
                        `optionB` TEXT NOT NULL,
                        `optionC` TEXT NOT NULL,
                        `optionD` TEXT NOT NULL,
                        `answer` INTEGER NOT NULL,
                        `explanation` TEXT NOT NULL,
                        `difficulty` INTEGER NOT NULL,
                        `enabled` INTEGER NOT NULL,
                        `source` TEXT NOT NULL,
                        `createdWall` INTEGER NOT NULL
                    )""".trimIndent()
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_bank_questions_questionKey` ON `bank_questions` (`questionKey`)")
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `challenges` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `dayKey` TEXT NOT NULL,
                        `ruleId` TEXT NOT NULL,
                        `packageName` TEXT NOT NULL,
                        `renewalIndex` INTEGER NOT NULL,
                        `seed` INTEGER NOT NULL,
                        `baseRequired` INTEGER NOT NULL,
                        `required` INTEGER NOT NULL,
                        `correct` INTEGER NOT NULL,
                        `wrong` INTEGER NOT NULL,
                        `position` INTEGER NOT NULL,
                        `grantMinutes` INTEGER NOT NULL,
                        `difficulty` INTEGER NOT NULL,
                        `types` TEXT NOT NULL,
                        `wrongPolicy` TEXT NOT NULL,
                        `bankIds` TEXT NOT NULL,
                        `status` TEXT NOT NULL,
                        `createdWall` INTEGER NOT NULL,
                        `updatedWall` INTEGER NOT NULL
                    )""".trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_challenges_dayKey_ruleId` ON `challenges` (`dayKey`, `ruleId`)")
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `cooldowns` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `ruleId` TEXT NOT NULL,
                        `sessionId` INTEGER,
                        `startWall` INTEGER NOT NULL,
                        `endWall` INTEGER NOT NULL,
                        `cancelled` INTEGER NOT NULL
                    )""".trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_cooldowns_endWall` ON `cooldowns` (`endWall`)")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `intentions` ADD COLUMN `purpose` TEXT")
                db.execSQL("ALTER TABLE `intentions` ADD COLUMN `goal` TEXT")
                db.execSQL("ALTER TABLE `intentions` ADD COLUMN `keywords` TEXT")
                db.execSQL("ALTER TABLE `intentions` ADD COLUMN `baseUsedMs` INTEGER")
                db.execSQL("ALTER TABLE `intentions` ADD COLUMN `status` TEXT")
                db.execSQL("ALTER TABLE `intentions` ADD COLUMN `endedWall` INTEGER")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `rewards` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `dayKey` TEXT NOT NULL,
                        `groupKey` TEXT NOT NULL,
                        `packageName` TEXT NOT NULL,
                        `sessionId` INTEGER NOT NULL,
                        `trigger` TEXT NOT NULL,
                        `energy` INTEGER NOT NULL,
                        `isFull` INTEGER NOT NULL,
                        `status` TEXT NOT NULL,
                        `createdWall` INTEGER NOT NULL,
                        `verifyUntilWall` INTEGER NOT NULL,
                        `resolvedWall` INTEGER,
                        `cancelReason` TEXT
                    )""".trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_rewards_dayKey_groupKey` ON `rewards` (`dayKey`, `groupKey`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_rewards_status` ON `rewards` (`status`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_rewards_sessionId` ON `rewards` (`sessionId`)")
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `usage_segments` ADD COLUMN `mode` TEXT")
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `reflections` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `dayKey` TEXT NOT NULL,
                        `sessionId` INTEGER NOT NULL,
                        `packageName` TEXT NOT NULL,
                        `purpose` TEXT NOT NULL,
                        `goal` TEXT NOT NULL,
                        `plannedMinutes` INTEGER,
                        `actualMs` INTEGER NOT NULL,
                        `outcome` TEXT NOT NULL,
                        `reasons` TEXT NOT NULL,
                        `createdWall` INTEGER NOT NULL
                    )""".trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_reflections_dayKey` ON `reflections` (`dayKey`)")
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `focus_sessions` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `dayKey` TEXT NOT NULL,
                        `goal` TEXT NOT NULL,
                        `targetFocusMs` INTEGER NOT NULL,
                        `interruptBudgetMs` INTEGER NOT NULL,
                        `focusedMs` INTEGER NOT NULL,
                        `interruptMs` INTEGER NOT NULL,
                        `interruptCount` INTEGER NOT NULL,
                        `whitelistPackages` TEXT NOT NULL,
                        `status` TEXT NOT NULL,
                        `startedWall` INTEGER NOT NULL,
                        `endedWall` INTEGER NOT NULL
                    )""".trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_focus_sessions_dayKey` ON `focus_sessions` (`dayKey`)")
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `bedtime_logs` (
                        `nightKey` TEXT NOT NULL,
                        `isWeekend` INTEGER NOT NULL,
                        `plannedFunEndMin` INTEGER NOT NULL,
                        `plannedSleepMin` INTEGER NOT NULL,
                        `plannedWakeMin` INTEGER NOT NULL,
                        `lastSupervisedWall` INTEGER,
                        `overtimeMinutes` INTEGER NOT NULL,
                        `reopenCount` INTEGER NOT NULL,
                        `voluntaryStopCount` INTEGER NOT NULL,
                        `forcedBlockCount` INTEGER NOT NULL,
                        `selfReportedSleepWall` INTEGER,
                        `updatedWall` INTEGER NOT NULL,
                        PRIMARY KEY(`nightKey`)
                    )""".trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_bedtime_logs_updatedWall` ON `bedtime_logs` (`updatedWall`)")
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `morning_alarms` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `label` TEXT NOT NULL,
                        `hour` INTEGER NOT NULL,
                        `minute` INTEGER NOT NULL,
                        `repeatDaysMask` INTEGER NOT NULL,
                        `enabled` INTEGER NOT NULL,
                        `ringtoneUri` TEXT NOT NULL,
                        `gradualVolumeSeconds` INTEGER NOT NULL,
                        `vibrate` INTEGER NOT NULL,
                        `snoozeMinutes` INTEGER NOT NULL,
                        `dismissMethod` TEXT NOT NULL,
                        `challengeCount` INTEGER NOT NULL,
                        `targetTagOrQr` TEXT NOT NULL,
                        `backupReminderEnabled` INTEGER NOT NULL,
                        `bodyWakeMinutes` INTEGER NOT NULL,
                        `morningJoyMinutes` INTEGER NOT NULL,
                        `bodyActivities` TEXT NOT NULL,
                        `joyActivity` TEXT NOT NULL,
                        `joyAllowanceMinutes` INTEGER NOT NULL,
                        `createdWall` INTEGER NOT NULL,
                        `updatedWall` INTEGER NOT NULL
                    )""".trimIndent()
                )
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `morning_sessions` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `dayKey` TEXT NOT NULL,
                        `alarmId` INTEGER NOT NULL,
                        `scheduledWall` INTEGER NOT NULL,
                        `ringStartedWall` INTEGER NOT NULL,
                        `acknowledgedWall` INTEGER,
                        `completedWall` INTEGER,
                        `status` TEXT NOT NULL,
                        `responseDelaySec` INTEGER NOT NULL,
                        `check5mConfirmed` INTEGER NOT NULL,
                        `motionHintSeen` INTEGER NOT NULL,
                        `joyActivity` TEXT NOT NULL,
                        `rewardId` INTEGER,
                        `updatedWall` INTEGER NOT NULL
                    )""".trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_morning_sessions_dayKey` ON `morning_sessions` (`dayKey`)")
            }
        }

        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `shopping_tasks` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `itemName` TEXT NOT NULL,
                        `purpose` TEXT NOT NULL,
                        `budgetCents` INTEGER NOT NULL,
                        `importance` INTEGER NOT NULL,
                        `urgency` INTEGER NOT NULL,
                        `mustHaves` TEXT NOT NULL,
                        `niceToHaves` TEXT NOT NULL,
                        `keywords` TEXT NOT NULL,
                        `decisionTier` TEXT NOT NULL,
                        `recommendedMinutes` INTEGER NOT NULL,
                        `researchMsTotal` INTEGER NOT NULL,
                        `status` TEXT NOT NULL,
                        `unresolvedQuestions` TEXT NOT NULL,
                        `decisionDateKey` TEXT NOT NULL,
                        `cooldownHours` INTEGER NOT NULL,
                        `createdWall` INTEGER NOT NULL,
                        `updatedWall` INTEGER NOT NULL
                    )""".trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_shopping_tasks_status` ON `shopping_tasks` (`status`)")
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `product_candidates` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `taskId` INTEGER NOT NULL,
                        `name` TEXT NOT NULL,
                        `url` TEXT NOT NULL,
                        `brandModelSpec` TEXT NOT NULL,
                        `comparisonGroupKey` TEXT NOT NULL,
                        `quantityText` TEXT NOT NULL,
                        `unitCode` TEXT NOT NULL,
                        `priceCents` INTEGER NOT NULL,
                        `discountCents` INTEGER NOT NULL,
                        `shippingCents` INTEGER NOT NULL,
                        `meetsMustHaves` INTEGER NOT NULL,
                        `evidenceNote` TEXT NOT NULL,
                        `warrantyNote` TEXT NOT NULL,
                        `notes` TEXT NOT NULL,
                        `source` TEXT NOT NULL,
                        `collectedWall` INTEGER NOT NULL
                    )""".trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_product_candidates_taskId` ON `product_candidates` (`taskId`)")
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `purchase_backlog` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `name` TEXT NOT NULL,
                        `estimatedCents` INTEGER NOT NULL,
                        `reasonNeeded` TEXT NOT NULL,
                        `hasAlternative` INTEGER NOT NULL,
                        `isUrgent` INTEGER NOT NULL,
                        `url` TEXT NOT NULL,
                        `cooldownHours` INTEGER NOT NULL,
                        `cooldownEndsWall` INTEGER NOT NULL,
                        `status` TEXT NOT NULL,
                        `createdWall` INTEGER NOT NULL,
                        `updatedWall` INTEGER NOT NULL
                    )""".trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_purchase_backlog_status` ON `purchase_backlog` (`status`)")
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `expense_records` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `monthKey` TEXT NOT NULL,
                        `dayKey` TEXT NOT NULL,
                        `category` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `amountCents` INTEGER NOT NULL,
                        `refundCents` INTEGER NOT NULL,
                        `taskId` INTEGER,
                        `notes` TEXT NOT NULL,
                        `createdWall` INTEGER NOT NULL
                    )""".trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_expense_records_monthKey` ON `expense_records` (`monthKey`)")
            }
        }

        fun getInstance(context: Context): MindstopDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    MindstopDatabase::class.java,
                    "mindstop_db"
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9)
                    .enableMultiInstanceInvalidation()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
