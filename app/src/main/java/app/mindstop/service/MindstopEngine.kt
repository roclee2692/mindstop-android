package app.mindstop.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.ContextCompat
import app.mindstop.core.BedtimeEngine
import app.mindstop.core.BedtimeEvaluation
import app.mindstop.core.BedtimePhase
import app.mindstop.core.ClockAnchor
import app.mindstop.core.ClockReading
import app.mindstop.core.DayKeys
import app.mindstop.core.FocusEngine
import app.mindstop.core.FocusInterruptNode
import app.mindstop.core.FocusStatus
import app.mindstop.core.ForegroundAccountant
import app.mindstop.core.IntentPurpose
import app.mindstop.core.IntentStatus
import app.mindstop.core.IntentionGate
import app.mindstop.core.IntentionSettings
import app.mindstop.core.OverlayPosition
import app.mindstop.core.PromptMode
import app.mindstop.core.ReminderPlanner
import app.mindstop.core.RewardTrigger
import app.mindstop.core.RuleEngine
import app.mindstop.core.SessionCountdown
import app.mindstop.core.SessionIntent
import app.mindstop.core.TrustedClockModel
import app.mindstop.ui.MindstopBlockActivity
import app.mindstop.ui.MindstopReflectionActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import neth.iecal.curbox.CrashLogger
import neth.iecal.curbox.services.BaseBlockingService

/**
 * Mindstop quota enforcement inside the accessibility service.
 *
 * Follows the same lifecycle as the other features: [setup] from onServiceConnected, cheap
 * [onEvent] calls inline, [onDestroy] for cleanup. All state work runs on a single threaded
 * scope, so the accountant and the clock model never race.
 */
class MindstopEngine {

    companion object {
        const val TICK_MS = 5_000L
        const val FLUSH_EVERY_TICKS = 12
        const val SESSION_GAP_MS = 10 * 60_000L
        const val EMERGENCY_UNLOCK_MS = 5 * 60_000L
        const val PROMPT_MIN_GAP_MS = 1_500L
        const val PROMPT_RETRY_MS = 4_000L
        const val PROMPT_VERIFY_DELAY_MS = 900L
        const val BLOCK_MIN_GAP_MS = 1_500L
        const val KEY_BEDTIME_ALLOW_UNTIL = "bedtime_allow_until"
        private val IGNORED_PACKAGES = setOf("com.android.systemui")
    }

    private lateinit var service: BaseBlockingService
    private lateinit var repo: QuotaRepository
    private lateinit var rewards: RewardRepository
    private lateinit var stage5: Stage5Repository
    private lateinit var morningRepo: MorningRepository

    @Volatile private var crashLogger: CrashLogger? = null

    // A failing check must never take the accessibility service down with it.
    private val errorHandler = CoroutineExceptionHandler { _, error ->
        try {
            crashLogger?.logNonFatalError(error as? Exception ?: RuntimeException(error))
        } catch (_: Exception) {
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1) + errorHandler)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val accountant = ForegroundAccountant(SESSION_GAP_MS)
    private val reminderOverlay by lazy { MindstopReminderOverlay(service) }

    @Volatile private var destroyed = false
    private var ownPackage = ""
    private var screenOn = true
    private var tickCount = 0

    private var clockAnchor: ClockAnchor? = null
    private var lastTrustedWall: Long? = null
    private var lastClockEventKey: String? = null

    private var lastSupervisedPackage: String? = null
    private var lastSupervisedSessionId: Long? = null
    private var lastSupervisedBlocked: Boolean = false
    private var reminderFiredInSessionId: Long? = null

    fun setup(service: BaseBlockingService) {
        this.service = service
        this.crashLogger = CrashLogger(service)
        this.ownPackage = service.packageName
        this.repo = QuotaRepository(service)
        this.rewards = RewardRepository(service)
        this.stage5 = Stage5Repository(service)
        this.morningRepo = MorningRepository(service)
        val powerManager = service.getSystemService(Context.POWER_SERVICE) as PowerManager
        screenOn = powerManager.isInteractive
        registerScreenReceiver()
        scope.launch { restoreState() }
        mainHandler.postDelayed(tick, TICK_MS)
    }

    /** Cheap inline check, called from AppBlockerService.onAccessibilityEvent. */
    fun onEvent(event: AccessibilityEvent?) {
        if (destroyed || event == null) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        if (!screenOn) return

        val foreground = activeWindowPackage() ?: event.packageName?.toString() ?: return
        scope.launch { onForegroundPackage(foreground) }
        // HyperOS transitions can briefly report the previous window in rootInActiveWindow;
        // recheck once settled so resumed apps are never missed.
        mainHandler.removeCallbacks(settleCheck)
        mainHandler.postDelayed(settleCheck, 350L)
    }

    private fun activeWindowPackage(): String? = try {
        service.rootInActiveWindow?.packageName?.toString()
    } catch (_: Exception) {
        null
    }

    private val settleCheck = Runnable {
        if (destroyed || !screenOn) return@Runnable
        val active = activeWindowPackage() ?: return@Runnable
        scope.launch { onForegroundPackage(active) }
    }

    private suspend fun onForegroundPackage(rawPackage: String) {
        if (destroyed) return
        val supervised = rawPackage.isNotEmpty() && rawPackage != ownPackage && rawPackage !in IGNORED_PACKAGES
        val packageName = if (supervised) rawPackage else null
        val nowElapsed = SystemClock.elapsedRealtime()
        val nowWall = trustedNow()

        rewards.settleDueRewards(nowWall)

        val previousPackage = accountant.currentPackage
        val previousSession = accountant.sessionId
        val changed = packageName != previousPackage

        // Stage 4 + v0.3 Phase 1: entering a supervised or night-restricted app during a
        // verification window cancels pending rewards (including pending bedtime rewards).
        var restoredFromCancel = false
        if (changed && packageName != null) {
            val bedtime = stage5.loadBedtime()
            val rules = repo.loadRules()
            val exempt = repo.loadExempt()
            val supervisedSet = RuleEngine.supervisedPackages(rules, exempt)
            val nightRestricted = BedtimeEngine.isRestrictedAtNight(bedtime, packageName, packageName in supervisedSet, exempt)
            val cancelled = rewards.cancelOnReentry(packageName, nowWall, nightRestricted = nightRestricted)
            if (cancelled.isNotEmpty()) {
                val cancelledSession = cancelled.first().sessionId
                if (!repo.cooldownEndedForSession(cancelledSession, nowWall)) {
                    accountant.restore(cancelledSession, nowWall)
                    restoredFromCancel = true
                }
                showReminder(
                    service.getString(neth.iecal.curbox.R.string.mindstop_reward_cancelled_banner),
                    belowPill = false
                )
            }
        }

        if (changed && !restoredFromCancel && packageName != null && previousSession != null &&
            repo.cooldownEndedForSession(previousSession, nowWall)
        ) {
            accountant.forceNewSession()
        }
        if (changed) {
            val prevMode = segmentModeFor(previousSession)
            val closed = accountant.onForeground(packageName, nowElapsed, nowWall)
            persistSegments(closed.map { segmentRows(it.packageName, it.sessionId, it.startWall, it.endWall, prevMode) }.flatten())
            saveSessionState()
        }

        // Stage 5: update Elastic Focus state on foreground switches
        tickActiveFocus(nowWall, enteredPackage = if (changed) packageName else null)

        // Stage 4: detect natural voluntary exit when the user leaves a supervised (non-blocked)
        // app to Home or another non-supervised app (never when Mindstop's own UI pops over it).
        if (changed && rawPackage != ownPackage && previousPackage != null &&
            previousPackage == lastSupervisedPackage && !lastSupervisedBlocked &&
            lastSupervisedSessionId != null
        ) {
            val rules = repo.loadRules()
            val exempt = repo.loadExempt()
            val supervisedSet = RuleEngine.supervisedPackages(rules, exempt)
            val stayedInSupervised = packageName != null && packageName in supervisedSet
            if (!stayedInSupervised) {
                val exitSession = lastSupervisedSessionId!!
                lastSupervisedPackage = null
                lastSupervisedSessionId = null
                lastSupervisedBlocked = false
                issueRewardAndFeedback(previousPackage, exitSession, RewardTrigger.ACTIVE_EXIT, nowWall)
            }
        }

        if (packageName != null) {
            evaluateAndBlock(packageName, nowElapsed, nowWall, foregroundChanged = changed)
        } else if (changed) {
            clearPromptFallback()
            hideSessionOverlay()
        }
    }

    private val sessionOverlay by lazy {
        MindstopSessionOverlay(service, object : MindstopSessionOverlay.Actions {
            override fun onGoalDone() = endByUser(IntentStatus.DONE)
            override fun onEndSession() = endByUser(IntentStatus.ENDED)
            override fun onSwitchToFun() = switchStudyToEntertainment()
        })
    }

    private suspend fun segmentModeFor(sessionId: Long?): String {
        if (sessionId == null) return QuotaRepository.MODE_FUN
        val intent = repo.sessionIntent(sessionId)
        return if (intent != null && intent.purpose == IntentPurpose.STUDY && intent.status == IntentStatus.ACTIVE) {
            QuotaRepository.MODE_STUDY
        } else {
            QuotaRepository.MODE_FUN
        }
    }

    private fun switchStudyToEntertainment() {
        scope.launch {
            val sessionId = accountant.sessionId ?: return@launch
            val pkg = accountant.currentPackage ?: return@launch
            val nowElapsed = SystemClock.elapsedRealtime()
            val nowWall = trustedNow()
            // Preserve all accumulated study time before switching purpose to FUN (SPEC §7).
            val flushed = accountant.flush(nowElapsed)
            persistSegments(flushed.map { segmentRows(it.packageName, it.sessionId, it.startWall, it.endWall, QuotaRepository.MODE_STUDY) }.flatten())
            saveSessionState()
            repo.switchIntentionToEntertainment(sessionId, nowWall)
            showReminder(service.getString(neth.iecal.curbox.R.string.mindstop_study_switched_banner), belowPill = false)
            evaluateAndBlock(pkg, nowElapsed, nowWall, foregroundChanged = true)
        }
    }

    private suspend fun tickActiveFocus(nowWall: Long, enteredPackage: String?) {
        val current = stage5.loadActiveFocus() ?: return
        if (!current.isActive) return
        val ticked = FocusEngine.tick(current, nowWall)
        var next = ticked.state
        if (ticked.justCompleted) {
            stage5.finishFocusSession(next, nowWall)
            mainHandler.post {
                if (destroyed) return@post
                reminderOverlay.showReward(
                    service.getString(neth.iecal.curbox.R.string.mindstop_focus_completed_toast, RewardTrigger.FOCUS_COMPLETE.defaultEnergy),
                    service.getString(neth.iecal.curbox.R.string.mindstop_reward_quote_1)
                )
            }
            return
        }
        ticked.interruptNode?.let { node ->
            val msgRes = when (node) {
                FocusInterruptNode.HALF -> neth.iecal.curbox.R.string.mindstop_focus_interrupt_50
                FocusInterruptNode.EIGHTY -> neth.iecal.curbox.R.string.mindstop_focus_interrupt_80
                FocusInterruptNode.EXHAUSTED -> neth.iecal.curbox.R.string.mindstop_focus_interrupt_100
            }
            showReminder(service.getString(msgRes), belowPill = false)
        }
        if (enteredPackage != null && next.status == FocusStatus.FOCUSING) {
            val exempt = repo.loadExempt()
            if (!FocusEngine.isAllowedDuringFocus(next, enteredPackage, exempt, ownPackage)) {
                val rules = repo.loadRules()
                if (enteredPackage in RuleEngine.supervisedPackages(rules, exempt)) {
                    next = FocusEngine.pauseForInterrupt(next, nowWall)
                }
            }
        }
        stage5.saveActiveFocus(next)
    }

    private fun hideSessionOverlay() {
        mainHandler.post { if (!destroyed) sessionOverlay.hide() }
    }

    private var lastPromptElapsed = Long.MIN_VALUE / 2
    private var lastBlockElapsed = Long.MIN_VALUE / 2
    private var pendingPromptVerify: Runnable? = null
    @Volatile private var promptFallbackShowing = false
    @Volatile private var openedRingSessionId: Long = -1L
    @Volatile private var alarmOverlayShowing = false

    /**
     * Opens the intention prompt using Android 14+ BAL-safe launch. If HyperOS or the OS drops
     * the cold-start Activity launch (for instance right after MindStop was swiped from Recents),
     * verifies 900ms later, retries once, and surfaces a touchable in-service overlay fallback bar.
     */
    private fun promptIntention(packageName: String, sessionId: Long, mode: PromptMode, force: Boolean, nowElapsed: Long) {
        val since = nowElapsed - lastPromptElapsed
        if (since < PROMPT_MIN_GAP_MS || (!force && since < PROMPT_RETRY_MS)) return
        lastPromptElapsed = nowElapsed
        mainHandler.post {
            if (destroyed) return@post
            val intent = buildIntentionIntent(packageName, sessionId, mode)
            val reqCode = ((sessionId.toInt() xor packageName.hashCode()) and 0x3FFF) + 71_000
            BackgroundActivityLauncher.launch(service, intent, reqCode)

            pendingPromptVerify?.let { mainHandler.removeCallbacks(it) }
            val verify = Runnable {
                if (destroyed || !screenOn) return@Runnable
                if (activeWindowPackage() != packageName) return@Runnable
                // Activity did not reach foreground; retry once and show touchable overlay fallback.
                val retryIntent = buildIntentionIntent(packageName, sessionId, mode)
                BackgroundActivityLauncher.launch(service, retryIntent, reqCode + 1)
                promptFallbackShowing = true
                reminderOverlay.showActionBanner(
                    title = service.getString(neth.iecal.curbox.R.string.mindstop_intention_overlay_fallback_title),
                    actionText = service.getString(neth.iecal.curbox.R.string.mindstop_intention_overlay_fallback_btn),
                    onAction = {
                        promptFallbackShowing = false
                        BackgroundActivityLauncher.launch(
                            service,
                            buildIntentionIntent(packageName, sessionId, mode),
                            reqCode + 2
                        )
                    }
                )
                scope.launch {
                    repo.logEvent("intention_launch_fallback", "$packageName ${mode.name}", trustedNow())
                }
            }
            pendingPromptVerify = verify
            mainHandler.postDelayed(verify, PROMPT_VERIFY_DELAY_MS)
        }
    }

    private fun clearPromptFallback() {
        mainHandler.post {
            pendingPromptVerify?.let { mainHandler.removeCallbacks(it) }
            pendingPromptVerify = null
            if (promptFallbackShowing) {
                promptFallbackShowing = false
                if (!destroyed && !alarmOverlayShowing) reminderOverlay.hide()
            }
        }
    }

    private fun buildIntentionIntent(packageName: String, sessionId: Long, mode: PromptMode): Intent =
        Intent(service, app.mindstop.ui.MindstopIntentionActivity::class.java).apply {
            putExtra(app.mindstop.ui.MindstopIntentionActivity.EXTRA_PACKAGE, packageName)
            putExtra(app.mindstop.ui.MindstopIntentionActivity.EXTRA_SESSION_ID, sessionId)
            putExtra(app.mindstop.ui.MindstopIntentionActivity.EXTRA_MODE, mode.name)
        }

    /** The user ended the session from the floating panel. The session itself goes on, so its limits keep counting. */
    private fun endByUser(status: IntentStatus) {
        scope.launch {
            val sessionId = accountant.sessionId ?: return@launch
            val pkg = accountant.currentPackage ?: lastSupervisedPackage
            val nowElapsed = SystemClock.elapsedRealtime()
            val wall = trustedNow()
            val intentBefore = repo.sessionIntent(sessionId)
            val mode = if (intentBefore?.purpose == IntentPurpose.STUDY) QuotaRepository.MODE_STUDY else QuotaRepository.MODE_FUN
            val flushed = accountant.flush(nowElapsed)
            persistSegments(flushed.map { segmentRows(it.packageName, it.sessionId, it.startWall, it.endWall, mode) }.flatten())
            saveSessionState()
            repo.finishIntention(sessionId, status, wall)
            repo.logEvent("session_exit", "session=$sessionId ${status.name} by_user", wall)
            // Clear tracking before pressHome() so the resulting Home event does not double-issue.
            lastSupervisedPackage = null
            lastSupervisedSessionId = null
            lastSupervisedBlocked = false
            if (pkg != null) {
                issueRewardAndFeedback(pkg, sessionId, RewardTrigger.ACTIVE_EXIT, wall)
            }
            val actualMs = repo.allSessionTotals(sessionId).values.sum()
            val shouldOfferReflection = intentBefore?.purpose == IntentPurpose.STUDY && pkg != null
            mainHandler.post {
                if (destroyed) return@post
                sessionOverlay.hide()
                if (shouldOfferReflection && intentBefore != null && pkg != null) {
                    val reflect = Intent(service, MindstopReflectionActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        putExtra(MindstopReflectionActivity.EXTRA_PACKAGE, pkg)
                        putExtra(MindstopReflectionActivity.EXTRA_SESSION_ID, sessionId)
                        putExtra(MindstopReflectionActivity.EXTRA_PURPOSE, intentBefore.purpose.code)
                        putExtra(MindstopReflectionActivity.EXTRA_GOAL, intentBefore.goal)
                        intentBefore.minutes?.let { putExtra(MindstopReflectionActivity.EXTRA_PLANNED_MINUTES, it) }
                        putExtra(MindstopReflectionActivity.EXTRA_ACTUAL_MS, actualMs)
                    }
                    try {
                        service.startActivity(reflect)
                    } catch (_: Exception) {
                        service.pressHome()
                    }
                } else {
                    service.pressHome()
                }
            }
        }
    }

    private suspend fun issueRewardAndFeedback(
        packageName: String,
        sessionId: Long,
        trigger: RewardTrigger,
        nowWall: Long
    ) {
        // v0.3 Phase 1: during evening wind-down or night protection, issue calm bedtime reward
        // and log voluntary stop in bedtime_logs (without requiring the user to reopen Mindstop).
        val bedtime = stage5.loadBedtime()
        val eval = BedtimeEngine.evaluateAtWall(bedtime, nowWall)
        if (bedtime.enabled && eval.isEveningWindDownOrNight && trigger == RewardTrigger.ACTIVE_EXIT) {
            val exempt = repo.loadExempt()
            val rules = repo.loadRules()
            val isNightTarget = BedtimeEngine.isRestrictedAtNight(
                bedtime, packageName, packageName in RuleEngine.supervisedPackages(rules, exempt), exempt
            )
            if (isNightTarget) {
                stage5.recordNightVoluntaryStop(eval, nowWall)
                val onTime = eval.minutesPastSleep == 0
                val nightOutcome = rewards.tryIssueBedtimeReward(
                    packageName = packageName,
                    sessionId = sessionId,
                    nightKey = eval.nightKey,
                    onTime = onTime,
                    nowWall = nowWall
                )
                val nightGranted = nightOutcome.granted
                if (nightGranted != null && nightOutcome.settings.feedbackEnabled) {
                    val titleRes = if (onTime) {
                        neth.iecal.curbox.R.string.mindstop_bedtime_reward_ontime_title
                    } else {
                        neth.iecal.curbox.R.string.mindstop_bedtime_reward_late_title
                    }
                    val title = service.getString(titleRes, nightGranted.energy)
                    val sub = service.getString(
                        neth.iecal.curbox.R.string.mindstop_bedtime_reward_sub,
                        nightOutcome.settings.verifyWindowMinutes
                    )
                    mainHandler.post {
                        if (destroyed) return@post
                        reminderOverlay.showNightReward(title, sub)
                    }
                    return
                }
            }
        }

        val outcome = rewards.tryIssueReward(
            packageName = packageName,
            sessionId = sessionId,
            trigger = trigger,
            nowWall = nowWall,
            hadReminderInSession = reminderFiredInSessionId == sessionId
        )
        val granted = outcome.granted ?: return
        if (!outcome.settings.feedbackEnabled) return
        val quotes = listOf(
            neth.iecal.curbox.R.string.mindstop_reward_quote_1,
            neth.iecal.curbox.R.string.mindstop_reward_quote_2,
            neth.iecal.curbox.R.string.mindstop_reward_quote_3
        )
        val quote = service.getString(quotes[(granted.id % quotes.size).toInt().coerceAtLeast(0)])
        val titleRes = if (granted.isFull) {
            neth.iecal.curbox.R.string.mindstop_reward_feedback_full_title
        } else {
            neth.iecal.curbox.R.string.mindstop_reward_feedback_partial_title
        }
        val title = service.getString(titleRes, granted.energy)
        val sub = service.getString(
            neth.iecal.curbox.R.string.mindstop_reward_feedback_pending_sub,
            outcome.settings.verifyWindowMinutes,
            quote
        )
        mainHandler.post {
            if (destroyed) return@post
            reminderOverlay.showReward(title, sub)
        }
    }

    private val exactCheckPending = AtomicBoolean(false)

    /** Checks again right when a countdown ends, instead of waiting for the next tick. */
    private fun scheduleExactCheck(delayMs: Long) {
        if (!exactCheckPending.compareAndSet(false, true)) return
        mainHandler.postDelayed({
            exactCheckPending.set(false)
            if (destroyed) return@postDelayed
            scope.launch {
                val pkg = accountant.currentPackage ?: return@launch
                evaluateAndBlock(pkg, SystemClock.elapsedRealtime(), trustedNow(), foregroundChanged = false)
            }
        }, delayMs + 300L)
    }

    private val tick = object : Runnable {
        override fun run() {
            if (destroyed) return
            scope.launch { onTick() }
            mainHandler.postDelayed(this, TICK_MS)
        }
    }

    private var nextMorningBonusCheckElapsed = 0L

    private suspend fun onTick() {
        if (destroyed) return
        if (screenOn) {
            val active = activeWindowPackage()
            if (active != null) {
                val supervised = active.isNotEmpty() && active != ownPackage && active !in IGNORED_PACKAGES
                val desired = if (supervised) active else null
                if (desired != accountant.currentPackage) {
                    onForegroundPackage(active)
                    return
                }
            }
        }
        val packageName = accountant.currentPackage
        val nowElapsed = SystemClock.elapsedRealtime()
        val nowWall = trustedNow()
        rewards.settleDueRewards(nowWall)
        if (nowElapsed >= nextMorningBonusCheckElapsed) {
            nextMorningBonusCheckElapsed = nowElapsed + 60_000L
            morningRepo.deliverDueAfternoonBonusIfReady(nowWall)?.let { mins ->
                showReminder(
                    service.getString(neth.iecal.curbox.R.string.mindstop_morning_bonus_afternoon_arrived, mins),
                    belowPill = false
                )
            }
        }
        tickActiveFocus(nowWall, enteredPackage = null)
        if (packageName == null) {
            // Persist the trusted time floor so a reboot cannot move the clock behind it.
            persistClock(nowWall)
            return
        }
        tickCount++
        if (tickCount >= FLUSH_EVERY_TICKS) {
            tickCount = 0
            val mode = segmentModeFor(accountant.sessionId)
            val flushed = accountant.flush(nowElapsed)
            persistSegments(flushed.map { segmentRows(it.packageName, it.sessionId, it.startWall, it.endWall, mode) }.flatten())
            saveSessionState()
        }
        evaluateAndBlock(packageName, nowElapsed, nowWall, foregroundChanged = false)
    }

    private suspend fun evaluateAndBlock(
        packageName: String,
        nowElapsed: Long,
        nowWall: Long,
        foregroundChanged: Boolean
    ): app.mindstop.core.Verdict? {
        val rules = repo.loadRules()
        if (rules.isEmpty()) {
            hideSessionOverlay()
            return null
        }
        val exempt = repo.loadExempt()
        val dayKey = DayKeys.dayKey(nowWall)
        val sessionId = accountant.sessionId
        val activeIntent = sessionId?.let { repo.sessionIntent(it) }
        val isActiveStudy = activeIntent != null &&
            activeIntent.purpose == IntentPurpose.STUDY &&
            activeIntent.status == IntentStatus.ACTIVE

        val openDelta = accountant.openDurationMs(nowElapsed)
        val daily = repo.dailyTotals(dayKey).toMutableMap()
        val session = (sessionId?.let { repo.sessionTotals(it) } ?: emptyMap()).toMutableMap()
        val allSession = (sessionId?.let { repo.allSessionTotals(it) } ?: emptyMap()).toMutableMap()
        if (openDelta > 0L) {
            allSession[packageName] = (allSession[packageName] ?: 0L) + openDelta
            // Independent Study Mode (SPEC §7): study usage does not deduct entertainment quotas.
            if (!isActiveStudy) {
                daily[packageName] = (daily[packageName] ?: 0L) + openDelta
                session[packageName] = (session[packageName] ?: 0L) + openDelta
            }
        }

        val grants = repo.loadGrants(dayKey)
        val cooldowns = repo.loadCooldowns(nowWall)
        val verdict = RuleEngine.evaluate(
            packageName, rules, daily, session, grants, dayKey, sessionId, exempt, cooldowns, nowWall
        )
        val bedtime = stage5.loadBedtime()
        val nightRestricted = BedtimeEngine.isRestrictedAtNight(bedtime, packageName, verdict.supervised, exempt)
        if (!verdict.supervised && !nightRestricted) {
            hideSessionOverlay()
            return verdict
        }

        // Bedtime Wind-down (v0.3 SPEC §4–§8): progressive evening reminders & night protection.
        val bedtimeEval = BedtimeEngine.evaluateAtWall(bedtime, nowWall)
        val wakeStr = BedtimeEngine.formatMinuteOfDay(bedtimeEval.schedule.plannedWakeMinutesOfDay)
        val (oppH, oppM) = BedtimeEngine.formatHoursMinutes(bedtimeEval.plannedSleepOpportunityMinutes)
        if (nightRestricted && bedtimeEval.isEveningWindDownOrNight) {
            stage5.recordNightUsage(bedtimeEval, nowWall)
            if (foregroundChanged && bedtimeEval.isNightRestrictedPhase) {
                stage5.recordNightReopen(bedtimeEval, nowWall)
            }
            val reminderText = when (bedtimeEval.phase) {
                BedtimePhase.WIND_DOWN_PREP -> {
                    val left = bedtimeEval.minutesUntilFunEnd ?: 15
                    service.getString(neth.iecal.curbox.R.string.mindstop_bedtime_phase_prep, left, wakeStr, oppH, oppM)
                }
                BedtimePhase.WIND_DOWN_COUNTDOWN -> {
                    val left = bedtimeEval.minutesUntilFunEnd ?: 5
                    service.getString(neth.iecal.curbox.R.string.mindstop_bedtime_phase_countdown, left, wakeStr)
                }
                else -> null
            }
            if (reminderText != null) {
                val key = "${bedtimeEval.nightKey}|${bedtimeEval.phase.code}"
                if (markFired(key)) {
                    showNightOrStandardReminder(reminderText, lowStim = bedtime.reduceStimulation, belowPill = false)
                    repo.logEvent("reminder_shown", key, nowWall)
                }
            }
        }

        val bedtimeAllowUntil = repo.loadMeta(KEY_BEDTIME_ALLOW_UNTIL)?.toLongOrNull() ?: 0L
        val bedtimeBlocking = !isActiveStudy &&
            nightRestricted &&
            bedtimeEval.isNightRestrictedPhase &&
            nowWall >= bedtimeAllowUntil

        // Morning Activation Stage 1 & Stage 2 distraction guard: restrict supervised high-distraction
        // apps while leaving exempt daily tools unrestricted.
        val morningPrepBlocking = !isActiveStudy &&
            verdict.supervised &&
            packageName !in exempt &&
            morningRepo.isMorningPrepBlockingActive(nowWall)
        if (morningPrepBlocking) {
            hideSessionOverlay()
            if (nowElapsed - lastBlockElapsed >= BLOCK_MIN_GAP_MS) {
                lastBlockElapsed = nowElapsed
                repo.logEvent("morning_prep_redirect", packageName, nowWall)
                mainHandler.post {
                    if (destroyed) return@post
                    reminderOverlay.show(
                        service.getString(neth.iecal.curbox.R.string.mindstop_morning_prep_redirect_banner),
                        belowPill = false
                    )
                    val actIntent = Intent(service, app.mindstop.ui.MindstopMorningActivationActivity::class.java)
                    BackgroundActivityLauncher.launch(
                        service,
                        actIntent,
                        (packageName.hashCode() and 0x3FFF) + 78_000
                    )
                }
            }
            return verdict
        }

        val effectivelyBlocked = (verdict.blocked && !isActiveStudy) || bedtimeBlocking

        lastSupervisedPackage = packageName
        lastSupervisedSessionId = sessionId
        lastSupervisedBlocked = effectivelyBlocked
        if (!effectivelyBlocked) {
            if (verdict.supervised) {
                afterVerdict(
                    packageName, verdict, rules, exempt, dayKey, sessionId,
                    if (isActiveStudy) allSession else session,
                    nowElapsed, nowWall, foregroundChanged, isActiveStudy,
                    nightFunLeftMs = if (nightRestricted && bedtimeEval.minutesUntilFunEnd != null && bedtimeEval.phase in setOf(BedtimePhase.WIND_DOWN_PREP, BedtimePhase.WIND_DOWN_COUNTDOWN)) {
                        bedtimeEval.minutesUntilFunEnd * 60_000L
                    } else null
                )
            } else {
                hideSessionOverlay()
            }
            return verdict
        }

        if (verdict.inCooldown && foregroundChanged) {
            val ruleIds = verdict.cooldowns.map { it.ruleId }.distinct().joinToString(",")
            repo.logEvent("cooldown_reopen_attempt", "$packageName rules=$ruleIds", nowWall)
        }
        hideSessionOverlay()
        if (nowElapsed - lastBlockElapsed < BLOCK_MIN_GAP_MS) return verdict
        lastBlockElapsed = nowElapsed
        // Flush the still-open usage segment to DB before launching MindstopBlockActivity so the
        // block screen immediately sees the exhausted quota (cooldownHelps, blockNote, grantsToUnlock)
        // instead of racing against the subsequent window-change event.
        val flushed = accountant.flush(nowElapsed)
        if (flushed.isNotEmpty()) {
            persistSegments(flushed.map { segmentRows(it.packageName, it.sessionId, it.startWall, it.endWall, QuotaRepository.MODE_FUN) }.flatten())
            saveSessionState()
        }
        if (bedtimeBlocking) {
            stage5.recordNightForcedBlock(bedtimeEval, nowWall)
        }
        val reason = when {
            bedtimeBlocking -> "bedtime:${bedtimeEval.phase.code}"
            verdict.inCooldown -> "cooldown"
            else -> "rule=${verdict.binding?.rule?.id}"
        }
        repo.logEvent(if (bedtimeBlocking) "bedtime_block_shown" else "block_shown", "$packageName $reason", nowWall)
        mainHandler.post {
            if (destroyed) return@post
            // Launch MindstopBlockActivity directly over the blocked app instead of calling
            // pressHome() first: HyperOS aborts background launches once com.miui.home takes focus.
            // MindstopBlockActivity itself sends the user to Home on End Use / Cooldown / Back.
            val intent = Intent(service, MindstopBlockActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(MindstopBlockActivity.EXTRA_BLOCKED_PACKAGE, packageName)
                if (bedtimeBlocking) {
                    val (remH, remM) = BedtimeEngine.formatHoursMinutes(bedtimeEval.remainingSleepOpportunityMinutes)
                    val impact = if (bedtimeEval.phase == BedtimePhase.BUFFER) {
                        service.getString(neth.iecal.curbox.R.string.mindstop_bedtime_block_buffer_impact, wakeStr, oppH, oppM)
                    } else {
                        service.getString(neth.iecal.curbox.R.string.mindstop_bedtime_block_sleep_impact, wakeStr, remH, remM, bedtimeEval.minutesPastSleep)
                    }
                    putExtra(MindstopBlockActivity.EXTRA_BEDTIME_MODE, true)
                    putExtra(MindstopBlockActivity.EXTRA_BEDTIME_END, wakeStr)
                    putExtra(MindstopBlockActivity.EXTRA_BEDTIME_IMPACT, impact)
                    putExtra(MindstopBlockActivity.EXTRA_BEDTIME_NIGHT_KEY, bedtimeEval.nightKey)
                    putExtra(MindstopBlockActivity.EXTRA_BEDTIME_ON_TIME, bedtimeEval.minutesPastSleep == 0)
                    putExtra(MindstopBlockActivity.EXTRA_BEDTIME_LOW_STIM, bedtime.reduceStimulation)
                }
            }
            val launched = BackgroundActivityLauncher.launch(
                service,
                intent,
                (packageName.hashCode() and 0x3FFF) + 75_000
            )
            if (!launched) {
                service.pressHome()
            }
        }
        return verdict
    }

    /** Intention gate, progressive reminders, and the floating pill for an app that may be used. */
    private suspend fun afterVerdict(
        packageName: String,
        verdict: app.mindstop.core.Verdict,
        rules: List<app.mindstop.core.QuotaRule>,
        exempt: Set<String>,
        dayKey: String,
        sessionId: Long?,
        sessionUsage: Map<String, Long>,
        nowElapsed: Long,
        nowWall: Long,
        foregroundChanged: Boolean,
        isActiveStudy: Boolean = false,
        nightFunLeftMs: Long? = null
    ) {
        val settings = repo.loadIntentionSettings()
        val supervisedPkgs = RuleEngine.supervisedPackages(rules, exempt)
        val sessionUsed = sessionUsage.filterKeys { it in supervisedPkgs }.values.sum()
        val promptOn = repo.isIntentionPromptEnabled()
        val intent = if (promptOn && sessionId != null) repo.sessionIntent(sessionId) else null
        if (promptOn && sessionId != null) {
            val mode = IntentionGate.promptNeeded(intent, sessionUsed)
            if (mode != null) {
                hideSessionOverlay()
                if (mode == PromptMode.EXTEND && intent != null) {
                    val key = "$sessionId|${intent.wall}|${intent.minutes}|${ReminderPlanner.PLAN_END}"
                    if (markFired(key)) repo.logEvent("plan_ended", key, nowWall)
                }
                promptIntention(packageName, sessionId, mode, foregroundChanged, nowElapsed)
                return
            }
        }
        clearPromptFallback()
        checkReminders(verdict, dayKey, sessionId, intent, sessionUsed, settings, nowWall)
        updateSessionOverlay(intent, sessionUsed, verdict, settings, nowElapsed, isActiveStudy, nightFunLeftMs)
    }

    private fun updateSessionOverlay(
        intent: SessionIntent?,
        sessionUsed: Long,
        verdict: app.mindstop.core.Verdict,
        settings: IntentionSettings,
        nowElapsed: Long,
        isActiveStudy: Boolean = false,
        nightFunLeftMs: Long? = null
    ) {
        if (!settings.overlayEnabled) {
            hideSessionOverlay()
            return
        }
        val quotaRemaining = if (isActiveStudy) null else verdict.remainingMs
        val effectiveQuota = listOfNotNull(quotaRemaining, nightFunLeftMs).minOrNull()
        val target = SessionCountdown.targetMs(intent?.planRemainingMs(sessionUsed), effectiveQuota)
        val showGoal = intent != null && intent.purpose.hasGoal && intent.goal.isNotBlank()
        val model = MindstopSessionOverlay.Model(
            goal = intent?.goal.orEmpty(),
            goalSummary = intent?.goalSummary().orEmpty(),
            keywords = intent?.keywords.orEmpty(),
            showGoal = showGoal,
            endElapsed = target?.let { nowElapsed + it },
            settings = settings,
            isStudyMode = isActiveStudy
        )
        mainHandler.post { if (!destroyed) sessionOverlay.update(model) }
        if (target != null && target <= TICK_MS) scheduleExactCheck(target)
    }

    // Progressive reminders

    private var firedReminders: MutableSet<String> = mutableSetOf()

    private suspend fun checkReminders(
        verdict: app.mindstop.core.Verdict,
        dayKey: String,
        sessionId: Long?,
        intent: SessionIntent?,
        sessionUsed: Long,
        settings: IntentionSettings,
        nowWall: Long
    ) {
        if (!repo.isReminderEnabled()) return
        val belowPill = settings.overlayEnabled && settings.position == OverlayPosition.TOP
        val binding = verdict.binding
        if (binding != null) {
            val quotaId = ReminderPlanner.quotaReminder(binding.limitMs, binding.grantedMs, binding.usedMs)
            if (quotaId != null) {
                val key = "$dayKey|${binding.rule.id}|${binding.kind}|$quotaId"
                if (markFired(key)) {
                    reminderFiredInSessionId = sessionId
                    showReminder(quotaReminderText(quotaId, ruleDisplayName(binding.rule)), belowPill)
                    repo.logEvent("reminder_shown", key, nowWall)
                }
            }
        }

        val sid = sessionId ?: return
        val plan = intent ?: return
        val total = plan.planMs ?: return
        val nodes = ReminderPlanner.planNodes(total, settings.nodeMinutes, settings.nodePercents)
        val elapsed = plan.planElapsedMs(sessionUsed)
        val id = ReminderPlanner.planReminder(total, elapsed, nodes) ?: return
        // The end of the plan is handled by the extend prompt, not by a banner.
        val node = nodes.firstOrNull { it.id == id } ?: return
        val key = "$sid|${plan.wall}|${plan.minutes}|$id"
        if (markFired(key)) {
            reminderFiredInSessionId = sid
            showReminder(planReminderText(plan, node, total - elapsed), belowPill)
            repo.logEvent("reminder_shown", key, nowWall)
        }
    }
    private suspend fun markFired(key: String): Boolean {
        if (!firedReminders.add(key)) return false
        repo.saveFiredReminders(firedReminders)
        return true
    }

    private fun ruleDisplayName(rule: app.mindstop.core.QuotaRule): String =
        rule.name.ifBlank {
            when (rule.scope) {
                app.mindstop.core.RuleScope.GLOBAL -> service.getString(neth.iecal.curbox.R.string.mindstop_scope_global)
                app.mindstop.core.RuleScope.GROUP -> service.getString(neth.iecal.curbox.R.string.mindstop_scope_group, rule.packages.size)
                app.mindstop.core.RuleScope.APP -> service.getString(neth.iecal.curbox.R.string.mindstop_scope_app)
            }
        }

    private fun quotaReminderText(id: String, ruleName: String): String {
        val res = when (id) {
            "remain_1m" -> neth.iecal.curbox.R.string.mindstop_remind_remain_1m
            "remain_5m" -> neth.iecal.curbox.R.string.mindstop_remind_remain_5m
            "remain_10p" -> neth.iecal.curbox.R.string.mindstop_remind_remain_10p
            "remain_25p" -> neth.iecal.curbox.R.string.mindstop_remind_remain_25p
            else -> neth.iecal.curbox.R.string.mindstop_remind_remain_50p
        }
        return service.getString(res, ruleName)
    }

    private fun planReminderText(plan: SessionIntent, node: ReminderPlanner.PlanNode, remainingMs: Long): String {
        val leftMinutes = ((remainingMs + 59_999L) / 60_000L).coerceAtLeast(1L).toInt()
        return when {
            !node.lastStretch -> service.getString(neth.iecal.curbox.R.string.mindstop_remind_plan_left, leftMinutes)
            plan.purpose.hasGoal && plan.goal.isNotBlank() ->
                service.getString(neth.iecal.curbox.R.string.mindstop_remind_last_goal, leftMinutes, plan.goalSummary(24))
            else -> service.getString(neth.iecal.curbox.R.string.mindstop_remind_last_video, leftMinutes)
        }
    }

    private fun showReminder(text: String, belowPill: Boolean) {
        mainHandler.post {
            if (destroyed) return@post
            reminderOverlay.show(text, belowPill = belowPill)
        }
    }

    private fun showNightOrStandardReminder(text: String, lowStim: Boolean, belowPill: Boolean) {
        mainHandler.post {
            if (destroyed) return@post
            if (lowStim) reminderOverlay.showNightReminder(text, belowPill = belowPill)
            else reminderOverlay.show(text, belowPill = belowPill)
        }
    }
    // Trusted clock

    private suspend fun trustedNow(): Long {
        val reading = ClockReading(
            bootCount = readBootCount(),
            elapsedMs = SystemClock.elapsedRealtime(),
            systemWallMs = System.currentTimeMillis(),
            autoTimeEnabled = isAutoTimeEnabled()
        )
        val decision = TrustedClockModel.resolve(clockAnchor, lastTrustedWall, reading)
        clockAnchor = decision.anchor
        val wall = TrustedClockModel.trustedWall(clockAnchor, reading)
        lastTrustedWall = maxOf(lastTrustedWall ?: Long.MIN_VALUE, wall)
        if (decision.event != null) {
            val key = decision.event.toString()
            if (key != lastClockEventKey) {
                lastClockEventKey = key
                repo.logEvent("clock_ignored", key, wall)
            }
        }
        persistClock(wall)
        return wall
    }

    private var lastClockPersistWall = 0L

    private suspend fun persistClock(wall: Long) {
        // Anchor rows change every read; writing once a minute is enough for crash recovery.
        if (wall - lastClockPersistWall < 60_000L) return
        lastClockPersistWall = wall
        clockAnchor?.let { repo.saveMeta(QuotaRepository.KEY_CLOCK_ANCHOR, TrustedClockModel.encode(it)) }
        repo.saveMeta(QuotaRepository.KEY_LAST_TRUSTED_WALL, (lastTrustedWall ?: wall).toString())
    }

    private fun readBootCount(): Int = try {
        Settings.Global.getInt(service.contentResolver, "boot_count")
    } catch (_: Exception) {
        -1
    }

    private fun isAutoTimeEnabled(): Boolean = try {
        Settings.Global.getInt(service.contentResolver, Settings.Global.AUTO_TIME, 1) == 1
    } catch (_: Exception) {
        true
    }

    // State persistence

    private suspend fun restoreState() {
        firedReminders = repo.loadFiredReminders()
        clockAnchor = TrustedClockModel.decode(repo.loadMeta(QuotaRepository.KEY_CLOCK_ANCHOR))
        lastTrustedWall = repo.loadMeta(QuotaRepository.KEY_LAST_TRUSTED_WALL)?.toLongOrNull()
        val sessionId = repo.loadMeta(QuotaRepository.KEY_SESSION_ID)?.toLongOrNull()
        val lastSupervised = repo.loadMeta(QuotaRepository.KEY_LAST_SUPERVISED_WALL)?.toLongOrNull()
        if (sessionId != null && lastSupervised != null) {
            accountant.restore(sessionId, lastSupervised)
        }
    }

    private suspend fun saveSessionState() {
        accountant.sessionId?.let { repo.saveMeta(QuotaRepository.KEY_SESSION_ID, it.toString()) }
        accountant.lastSupervisedWall?.let {
            repo.saveMeta(QuotaRepository.KEY_LAST_SUPERVISED_WALL, it.toString())
        }
    }

    private suspend fun persistSegments(rows: List<app.mindstop.data.UsageSegmentEntity>) {
        if (rows.isEmpty()) return
        try {
            repo.insertSegments(rows)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
        }
    }

    // Screen & alarm-ring coordination

    private val alarmRingReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (destroyed) return
            when (intent?.action) {
                MindstopAlarmService.ACTION_ALARM_UI_OPENED -> {
                    val openedSid = intent.getLongExtra(MindstopAlarmReceiver.EXTRA_SESSION_ID, -1L)
                    openedRingSessionId = openedSid
                    if (alarmOverlayShowing) {
                        alarmOverlayShowing = false
                        mainHandler.post { if (!destroyed) reminderOverlay.hide() }
                    }
                }
                MindstopAlarmService.ACTION_ALARM_STOPPED -> {
                    openedRingSessionId = -1L
                    alarmOverlayShowing = false
                    mainHandler.post { if (!destroyed) reminderOverlay.hide() }
                }
                MindstopAlarmService.ACTION_ALARM_RINGING -> {
                    val alarmId = intent.getLongExtra(MindstopAlarmReceiver.EXTRA_ALARM_ID, -1L)
                    val sessionId = intent.getLongExtra(MindstopAlarmReceiver.EXTRA_SESSION_ID, -1L)
                    if (alarmId <= 0L) return
                    mainHandler.postDelayed({
                        if (destroyed || openedRingSessionId == sessionId) return@postDelayed
                        val ringIntent = Intent(service, app.mindstop.ui.MindstopAlarmRingActivity::class.java).apply {
                            putExtra(MindstopAlarmReceiver.EXTRA_ALARM_ID, alarmId)
                            putExtra(MindstopAlarmReceiver.EXTRA_SESSION_ID, sessionId)
                        }
                        val reqCode = ((alarmId xor sessionId).toInt() and 0x3FFF) + 65_000
                        BackgroundActivityLauncher.launch(service, ringIntent, reqCode)
                        // Ultimate safety net: if MindstopAlarmRingActivity still hasn't resumed 800ms later,
                        // display a persistent touchable overlay button directly from AccessibilityService.
                        mainHandler.postDelayed({
                            if (destroyed || openedRingSessionId == sessionId) return@postDelayed
                            alarmOverlayShowing = true
                            reminderOverlay.showActionBanner(
                                title = service.getString(neth.iecal.curbox.R.string.mindstop_morning_overlay_ringing_title),
                                actionText = service.getString(neth.iecal.curbox.R.string.mindstop_morning_ring_open_btn),
                                onAction = {
                                    alarmOverlayShowing = false
                                    BackgroundActivityLauncher.launch(service, ringIntent, reqCode + 1)
                                },
                                holdMs = 0L
                            )
                        }, 800L)
                    }, 550L)
                }
            }
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    screenOn = false
                    sessionOverlay.hide()
                    scope.launch {
                        val mode = segmentModeFor(accountant.sessionId)
                        val closed = accountant.onForeground(null, SystemClock.elapsedRealtime(), trustedNow())
                        persistSegments(closed.map { segmentRows(it.packageName, it.sessionId, it.startWall, it.endWall, mode) }.flatten())
                        saveSessionState()
                    }
                }
                Intent.ACTION_SCREEN_ON -> screenOn = true
                Intent.ACTION_USER_PRESENT -> {
                    screenOn = true
                    mainHandler.postDelayed({ resumeForeground() }, 300)
                }
            }
        }
    }

    private fun resumeForeground() {
        if (destroyed || !screenOn) return
        val active = try {
            service.rootInActiveWindow?.packageName?.toString()
        } catch (_: Exception) {
            null
        } ?: return
        scope.launch { onForegroundPackage(active) }
    }

    private fun registerScreenReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        ContextCompat.registerReceiver(service, screenReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        val alarmFilter = IntentFilter().apply {
            addAction(MindstopAlarmService.ACTION_ALARM_RINGING)
            addAction(MindstopAlarmService.ACTION_ALARM_STOPPED)
            addAction(MindstopAlarmService.ACTION_ALARM_UI_OPENED)
        }
        ContextCompat.registerReceiver(service, alarmRingReceiver, alarmFilter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    fun onDestroy() {
        if (destroyed) return
        destroyed = true
        mainHandler.removeCallbacksAndMessages(null)
        if (!::service.isInitialized) {
            scope.cancel()
            return
        }
        reminderOverlay.hide()
        sessionOverlay.hide()
        try {
            runBlocking(Dispatchers.IO) {
                withTimeoutOrNull(1_500L) {
                    val mode = segmentModeFor(accountant.sessionId)
                    val closed = accountant.onForeground(null, SystemClock.elapsedRealtime(), System.currentTimeMillis())
                    persistSegments(closed.map { segmentRows(it.packageName, it.sessionId, it.startWall, it.endWall, mode) }.flatten())
                    saveSessionState()
                    clockAnchor?.let { repo.saveMeta(QuotaRepository.KEY_CLOCK_ANCHOR, TrustedClockModel.encode(it)) }
                    lastTrustedWall?.let { repo.saveMeta(QuotaRepository.KEY_LAST_TRUSTED_WALL, it.toString()) }
                    scope.coroutineContext[Job]?.children?.toList()?.joinAll()
                }
            }
        } finally {
            scope.cancel()
            try {
                service.unregisterReceiver(screenReceiver)
            } catch (_: Exception) {
            }
            try {
                service.unregisterReceiver(alarmRingReceiver)
            } catch (_: Exception) {
            }
        }
    }
}
