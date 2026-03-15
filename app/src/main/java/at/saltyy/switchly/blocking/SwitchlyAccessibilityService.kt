package at.saltyy.switchly.blocking

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.ActivityManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.app.KeyguardManager
import android.content.Intent
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.preference.PreferenceManager
import at.saltyy.switchly.R
import at.saltyy.switchly.data.prefs.BlockAttemptStore
import at.saltyy.switchly.data.prefs.BlockCountStore
import at.saltyy.switchly.data.prefs.BlockedTimeStore
import at.saltyy.switchly.data.prefs.AttemptLimitStore
import at.saltyy.switchly.data.prefs.OpenCountStore
import at.saltyy.switchly.data.prefs.BlockingToggleKeys
import at.saltyy.switchly.data.prefs.InAppLimitStore
import at.saltyy.switchly.data.prefs.DomainBlockStore
import at.saltyy.switchly.data.prefs.EmergencyBypassStore
import at.saltyy.switchly.data.prefs.LimitReachedStore
import at.saltyy.switchly.data.prefs.ProfileStore
import at.saltyy.switchly.data.prefs.ScheduleStore
import at.saltyy.switchly.data.prefs.SwitchModeStore
import at.saltyy.switchly.data.prefs.TempAllowStore
import at.saltyy.switchly.data.prefs.UsageLimitStore
import at.saltyy.switchly.data.prefs.UsageStore
import at.saltyy.switchly.data.prefs.WebUsageStore
import at.saltyy.switchly.feature.blocker.BlockerActivity
import at.saltyy.switchly.platform.receiver.schedule.ScheduleReceiver
import java.util.Locale
import at.saltyy.switchly.data.prefs.SurfaceUsageStore
import at.saltyy.switchly.data.prefs.SurfaceLimitStore
import at.saltyy.switchly.data.prefs.DomainLimitStore

/**
 * Main (stable) blocking runtime.
 * Compared to AppWatcherService this does NOT rely on Usage Access polling or a foreground service.
 * The system keeps Accessibility services alive much more reliably across OEMs.
 */
class SwitchlyAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())

    private lateinit var pm: PowerManager
    private var km: KeyguardManager? = null

    // Foreground tracking for usage limits
    @Volatile private var currentTopPkg: String? = null
    @Volatile private var lastTickAt: Long = 0L

    // Occasionally re-verify the true foreground app via UsageEvents.
    // Some OEMs/apps don't emit reliable WINDOW_STATE_CHANGED transitions.
    private var lastTopRefreshAt: Long = 0L

    // Attempt-based limits: scan UsageEvents for MOVE_TO_FOREGROUND to count opens reliably.
    private var lastUsageOpenScanAt: Long = 0L
    private var cachedHasAttemptLimitsAt: Long = 0L
    private var cachedHasAttemptLimits: Boolean = false

    // Event noise reduction (especially with TYPE_WINDOW_CONTENT_CHANGED enabled)
    private var lastEventPkg: String? = null
    private var lastEventAt: Long = 0L
    private var lastEventType: Int = 0
    private var lastTransitionAt: Long = 0L

    // Browser state cache for website blocking + per-domain website stats
    @Volatile private var currentBrowserPkg: String? = null
    @Volatile private var currentBrowserDomain: String? = null
    private var browserCandidateDomain: String? = null
    private var browserCandidateSince: Long = 0L

    // In-app surface tracking for usage + limits (Shorts/Reels/Explore)
    @Volatile private var currentSurfaceKey: String? = null
    @Volatile private var currentSurfacePkg: String? = null

    // Per-package probe/enforcement cadence (prevents duplicate heavy scans during event storms)
    private val lastDeepProbeAtByPkg = HashMap<String, Long>()
    private val lastEnforceAtByPkg = HashMap<String, Long>()
    private val lastLowSignalProbeAtByPkg = HashMap<String, Long>()
    private val appEnteredAtByPkg = HashMap<String, Long>()

    // Usage-limit enforcement: system usage stats can lag while an app is still in foreground.
    // Snapshot system + internal usage at app entry, then estimate "live" system usage by
    // adding internal deltas since entry.
    private val usageInternalAtEnterByPkg = HashMap<String, Long>()
    private val usageSystemAtEnterByPkg = HashMap<String, Long>()

    // Debounce block/attempt stats + blocker UI launches.
    private val lastAttemptAt = HashMap<String, Long>()
    private val lastBlockShownAt = HashMap<String, Long>()
    private val lastSurfaceBlockAt = HashMap<String, Long>()
    private val lastOpenCountAt = HashMap<String, Long>()
    private val surfaceEvidenceCount = HashMap<String, Int>()
    private val surfaceEvidenceAt = HashMap<String, Long>()
    private val inAppGraceUntilByPkg = HashMap<String, Long>()
    private val surfaceBlockGuardUntil = HashMap<String, Long>()

    private val SURFACE_BLOCK_COOLDOWN_MS = 1_200L
    private var lastGlobalBlockTs: Long = 0L

    private val ATTEMPT_COOLDOWN_MS = 1_200L
    // Count an "open" when the app becomes foreground. 
    // Keep this short so users can legitimately open/close the same app a few times without missing counts.
    private val OPEN_COUNT_COOLDOWN_MS = 800L
    private val BLOCK_SHOWN_COOLDOWN_MS = 800L
    private val BROWSER_DOMAIN_CONFIRM_MS = 700L
    private val SURFACE_CONFIRM_MS = 850L
    private val INAPP_POST_BLOCK_GRACE_MS = 1_800L
    private val INSTA_REELS_REENTRY_GUARD_MS = 1_800L
    private val INSTA_EXPLORE_REENTRY_GUARD_MS = 2_200L
    private val INAPP_ENTRY_SETTLE_MS = 1_400L
    private val YT_SHORTS_REENTRY_GUARD_MS = 2_200L

    private val INAPP_GLOBAL_LIMIT_KEY_LEGACY = "inapp:global"

    // Short-lived policy cache to avoid repeated SharedPreferences/ProfileStore reads on every event.
    private val POLICY_CACHE_TTL_MS = 1_200L
    private var cachedProfileAt: Long = 0L
    private var cachedProfile: String? = null
    private var cachedBlockedAt: Long = 0L
    private var cachedBlockedProfile: String? = null
    private var cachedBlockedApps: Set<String> = emptySet()
    private var cachedUsageLimitAt: Long = 0L
    private var cachedUsageLimitProfile: String? = null
    private val cachedUsageLimitByPkg = HashMap<String, Int>()

    private var cachedAttemptLimitAt: Long = 0L
    private var cachedAttemptLimitProfile: String? = null
    private val cachedAttemptLimitByPkg = HashMap<String, Int>()
    private var cachedPrefsAt: Long = 0L
    private var cachedPrefsProfile: String = ""
    private val cachedPrefsBool = HashMap<String, Boolean>()
    private var cachedInAppLimitAt: Long = 0L
    private var cachedInAppLimitProfile: String = "default"
    private var cachedInAppLimitMin: Int = 0
    private var cachedSurfaceRulesAt: Long = 0L
    private var cachedSurfaceRulesProfile: String = "default"
    private val cachedSurfaceRuleByKey = HashMap<String, Int>()
    private var cachedDomainBlockEnabledAt: Long = 0L
    private var cachedDomainBlockEnabled: Boolean = false

    // Lightweight profiling counters (1-minute window), persisted for quick diagnostics.
    private data class PerfWindow(
        var events: Int = 0,
        var dedupeDrops: Int = 0,
        var deepProbeRuns: Int = 0,
        var deepProbeSkips: Int = 0,
        var maybeBlockCalls: Int = 0,
        var enforceSkips: Int = 0,
        var websiteScans: Int = 0,
        var inAppScans: Int = 0,
        var inAppNoRuleSkips: Int = 0,
        var lowSignalSkips: Int = 0,
        var rootMisses: Int = 0,
        var blocksShown: Int = 0
    ) {
        fun clear() {
            events = 0
            dedupeDrops = 0
            deepProbeRuns = 0
            deepProbeSkips = 0
            maybeBlockCalls = 0
            enforceSkips = 0
            websiteScans = 0
            inAppScans = 0
            inAppNoRuleSkips = 0
            lowSignalSkips = 0
            rootMisses = 0
            blocksShown = 0
        }

        fun summary(now: Long, startedAt: Long): String {
            val durSec = ((now - startedAt).coerceAtLeast(1L)/1000L)
            return "dur=${durSec}s ev=$events drop=$dedupeDrops probe=$deepProbeRuns " +
                "probeSkip=$deepProbeSkips maybe=$maybeBlockCalls enfSkip=$enforceSkips " +
                "web=$websiteScans inapp=$inAppScans noRule=$inAppNoRuleSkips " +
                "lowSig=$lowSignalSkips rootMiss=$rootMisses blocks=$blocksShown"
        }
    }

    private val perf = PerfWindow()
    private var perfWindowStartedAt: Long = System.currentTimeMillis()
    private var lastPerfFlushAt: Long = 0L

    // Schedule evaluation fallback (for devices without Exact Alarm permission)
    private var lastScheduleEvalMinute: Long = -1L

    private fun maybeFlushPerfCounters() {
        val now = System.currentTimeMillis()
        if (now - lastPerfFlushAt < 60_000L) return
        lastPerfFlushAt = now

        val summary = perf.summary(now, perfWindowStartedAt)
        getSharedPreferences("switchly_perf", MODE_PRIVATE).edit {
            putLong("acc_window_started_at", perfWindowStartedAt)
            putLong("acc_window_ended_at", now)
            putString("acc_window_summary", summary)
        }
        runCatching { Log.d("SwitchlyPerf", summary) }

        perf.clear()
        perfWindowStartedAt = now
    }

    private fun getCurrentProfileCached(now: Long = System.currentTimeMillis()): String? {
        val fresh = (now - cachedProfileAt) <= POLICY_CACHE_TTL_MS
        if (fresh) return cachedProfile

        val next = ProfileStore.getCurrent(this)
        if (next != cachedProfile) {
            cachedBlockedApps = emptySet()
            cachedBlockedProfile = null
            cachedBlockedAt = 0L

            cachedUsageLimitProfile = null
            cachedUsageLimitAt = 0L
            cachedUsageLimitByPkg.clear()

            cachedAttemptLimitProfile = null
            cachedAttemptLimitAt = 0L
            cachedAttemptLimitByPkg.clear()

            cachedPrefsProfile = ""
            cachedPrefsAt = 0L
            cachedPrefsBool.clear()

            cachedInAppLimitProfile = "default"
            cachedInAppLimitAt = 0L
            cachedInAppLimitMin = 0

            cachedSurfaceRulesProfile = "default"
            cachedSurfaceRulesAt = 0L
            cachedSurfaceRuleByKey.clear()
        }
        cachedProfile = next
        cachedProfileAt = now
        return next
    }

    private fun getBlockedAppsCached(profile: String, now: Long = System.currentTimeMillis()): Set<String> {
        val fresh =
            cachedBlockedProfile == profile &&
                (now - cachedBlockedAt) <= POLICY_CACHE_TTL_MS
        if (fresh) return cachedBlockedApps

        val next = ProfileStore.getBlockedForProfile(this, profile)
        cachedBlockedApps = next
        cachedBlockedProfile = profile
        cachedBlockedAt = now
        return next
    }

    private fun getUsageLimitCached(profile: String, pkg: String, now: Long = System.currentTimeMillis()): Int {
        val fresh =
            cachedUsageLimitProfile == profile &&
                (now - cachedUsageLimitAt) <= POLICY_CACHE_TTL_MS
        if (!fresh) {
            cachedUsageLimitProfile = profile
            cachedUsageLimitAt = now
            cachedUsageLimitByPkg.clear()
        }
        return cachedUsageLimitByPkg.getOrPut(pkg) {
            UsageLimitStore.getLimitMinutes(this, profile, pkg)
        }
    }

    private fun getAttemptLimitCached(profile: String, pkg: String, now: Long = System.currentTimeMillis()): Int {
        val fresh =
            cachedAttemptLimitProfile == profile &&
                (now - cachedAttemptLimitAt) <= POLICY_CACHE_TTL_MS
        if (!fresh) {
            cachedAttemptLimitProfile = profile
            cachedAttemptLimitAt = now
            cachedAttemptLimitByPkg.clear()
        }
        return cachedAttemptLimitByPkg.getOrPut(pkg) {
            AttemptLimitStore.getLimitAttempts(this, profile, pkg)
        }
    }

    private fun hasAnyAttemptLimitsCached(now: Long = System.currentTimeMillis()): Boolean {
        if (now - cachedHasAttemptLimitsAt <= 5_000L) return cachedHasAttemptLimits
        val v = runCatching { AttemptLimitStore.hasAnyLimits(this) }.getOrDefault(false)
        cachedHasAttemptLimits = v
        cachedHasAttemptLimitsAt = now
        return v
    }

    private fun isDomainBlockingEnabledCached(now: Long = System.currentTimeMillis()): Boolean {
        val fresh = (now - cachedDomainBlockEnabledAt) <= POLICY_CACHE_TTL_MS
        if (fresh) return cachedDomainBlockEnabled
        val enabled = DomainBlockStore.isEnabled(this)
        cachedDomainBlockEnabled = enabled
        cachedDomainBlockEnabledAt = now
        return enabled
    }

    private fun shouldRunEnforcement(pkg: String, event: AccessibilityEvent?, force: Boolean): Boolean {
        if (force) return true
        val now = System.currentTimeMillis()
        val type = event?.eventType ?: 0

        val minGap = when {
            event == null -> 450L // tick cadence
            type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                type == AccessibilityEvent.TYPE_WINDOWS_CHANGED -> 90L
            type == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED ||
                type == AccessibilityEvent.TYPE_VIEW_CLICKED ||
                type == AccessibilityEvent.TYPE_VIEW_FOCUSED -> 140L
            else -> 220L
        }

        val last = lastEnforceAtByPkg[pkg] ?: 0L
        if (now - last < minGap) {
            perf.enforceSkips++
            return false
        }
        lastEnforceAtByPkg[pkg] = now
        return true
    }

    private fun shouldRunDeepProbe(pkg: String, eventType: Int, isTransition: Boolean, now: Long): Boolean {
        val minGap = when {
            isTransition -> 70L
            eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED ||
                eventType == AccessibilityEvent.TYPE_VIEW_CLICKED ||
                eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED -> 140L
            eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED -> 220L
            else -> 280L
        }

        val last = lastDeepProbeAtByPkg[pkg] ?: 0L
        if (now - last < minGap) {
            perf.deepProbeSkips++
            return false
        }
        lastDeepProbeAtByPkg[pkg] = now
        perf.deepProbeRuns++
        return true
    }

    private fun packageHasInAppFeaturesEnabled(pkg: String): Boolean {
        if (!prefsBoolProfile(true, BlockingToggleKeys.KEY_BLOCK_INAPP)) return false

        return when {
            pkg == "com.google.android.youtube" ->
                prefsBoolProfile(false, BlockingToggleKeys.KEY_BLOCK_YT_SHORTS) ||
                    prefsBoolProfile(false, BlockingToggleKeys.KEY_BLOCK_YT_SEARCH) ||
                    prefsBoolProfile(false, BlockingToggleKeys.KEY_BLOCK_YT_COMMENTS) ||
                    prefsBoolProfile(false, BlockingToggleKeys.KEY_BLOCK_YT_PIP)

            pkg == "com.instagram.android" ->
                prefsBoolProfile(false, BlockingToggleKeys.KEY_BLOCK_IG_REELS) ||
                    prefsBoolProfile(false, BlockingToggleKeys.KEY_BLOCK_IG_EXPLORE) ||
                    prefsBoolProfile(false, BlockingToggleKeys.KEY_BLOCK_IG_STORIES) ||
                    prefsBoolProfile(false, BlockingToggleKeys.KEY_BLOCK_IG_COMMENTS)

            else -> false
        }
    }

    private fun lowSignalNeedlesForPkg(pkg: String): List<String> {
        return when {
            pkg == "com.google.android.youtube" -> listOf("shorts", "search", "comment", "picture")
            pkg == "com.instagram.android" -> listOf("reels", "explore", "story", "comment")
            else -> emptyList()
        }
    }

    private fun shouldSkipLowSignalInApp(pkg: String, event: AccessibilityEvent, now: Long): Boolean {
        // Reliability-first: these apps often emit sparse/noisy class/text signals on surface changes.
        // Skipping probes here can miss legitimate blocks (YouTube Shorts).
        if (pkg == "com.google.android.youtube") {
            return false
        }

        val type = event.eventType
        val lowSignalType =
            type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
                type == AccessibilityEvent.TYPE_VIEW_SCROLLED
        if (!lowSignalType) return false

        val cls = event.className?.toString()?.lowercase(Locale.getDefault()).orEmpty()
        val textSignal = eventTextMatches(event, lowSignalNeedlesForPkg(pkg))
        val classSignal = cls.contains("reel") || cls.contains("short") || cls.contains("story") || cls.contains("explore") || cls.contains("market") || cls.contains("comment") || cls.contains("search")
        if (textSignal || classSignal) return false

        // If we are already tracking a surface, probe slightly more often to detect exits quickly.
        val minGap = if (currentSurfacePkg == pkg) 700L else 1_200L
        val last = lastLowSignalProbeAtByPkg[pkg] ?: 0L
        if (now - last < minGap) {
            perf.lowSignalSkips++
            return true
        }
        lastLowSignalProbeAtByPkg[pkg] = now
        return false
    }

    private fun maybeScheduleMinuteTick() {
        val now = System.currentTimeMillis()
        val minuteBucket = now/60_000L
        if (minuteBucket == lastScheduleEvalMinute) return
        lastScheduleEvalMinute = minuteBucket

        val hasSchedules = runCatching { ScheduleStore.getAll(this) }.getOrNull()?.isNotEmpty() == true
        if (!hasSchedules) return

        runCatching {
            val i = Intent(this@SwitchlyAccessibilityService, ScheduleReceiver::class.java).apply {
                action = ScheduleReceiver.ACTION_TICK
                putExtra("time_reason", "accessibility_minute")
            }
            sendBroadcast(i)
        }
    }

    /**
     * Re-check the current foreground app periodically.
     * Why: schedule boundaries can flip while the user is already inside a blocked app.
     * Without a fresh accessibility event, some devices won't trigger immediate blocking until the user leaves/re-enters the app.
     */
    private fun enforceCurrentForegroundIfNeeded() {
        val pkg = currentTopPkg ?: return
        if (pkg.isBlank()) return
        if (pkg == packageName) return
        maybeBlockNow(pkg)
    }

    private val tick = object : Runnable {
        override fun run() {
            try {
                BlockingRuntime.markAccessibilityHeartbeat(this@SwitchlyAccessibilityService)

                // Ensure temporary enable/disable timers expire correctly even when UI isn't open.
                SwitchModeStore.finishTemporaryEnableIfExpired(this@SwitchlyAccessibilityService)
                SwitchModeStore.finishTemporaryDisableIfExpired(this@SwitchlyAccessibilityService)
                maybeScheduleMinuteTick()
                enforceCurrentForegroundIfNeeded()
                usageTick()
                maybeFlushPerfCounters()
            } catch (_: Throwable) {
                // ignore
            }
            handler.postDelayed(this, 1_000L)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()

        pm = getSystemService(POWER_SERVICE) as PowerManager
        km = getSystemService(KeyguardManager::class.java)

        // Listen to transitions AND content/text events (needed for in-app surfaces + URLs).
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes =
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOWS_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                    AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or
                    AccessibilityEvent.TYPE_VIEW_SCROLLED or
                    AccessibilityEvent.TYPE_VIEW_CLICKED or
                    AccessibilityEvent.TYPE_VIEW_FOCUSED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 0
            flags =
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        }

        runCatching { BlockingRuntime.markAccessibilityHeartbeat(this) }
        runCatching { at.saltyy.switchly.util.ProtectionStatusNotifier.refresh(this) }

        perf.clear()
        perfWindowStartedAt = System.currentTimeMillis()
        lastPerfFlushAt = 0L

        lastTickAt = System.currentTimeMillis()
        lastUsageOpenScanAt = lastTickAt
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, 1_000L)
    }

    override fun onInterrupt() {
        // no-op
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        runCatching { BlockingRuntime.markAccessibilityDisconnected(this) }
        runCatching { maybeFlushPerfCounters() }
        runCatching { at.saltyy.switchly.util.ProtectionStatusNotifier.refresh(this) }
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        runCatching { BlockingRuntime.markAccessibilityHeartbeat(this) }

        perf.events++

        val pkg = event?.packageName?.toString()?.trim().orEmpty()
        if (pkg.isBlank()) return
        if (pkg == packageName) return // avoid loops

        val pendingBackCount = BlockerActivity.consumePendingBackNavigationFor(pkg)
        if (pendingBackCount > 0) {
            val ackNow = System.currentTimeMillis()

            // Prevent immediate re-detection loops while navigating back to app home.
            inAppGraceUntilByPkg[pkg] = ackNow + INAPP_POST_BLOCK_GRACE_MS
            clearSurfaceEvidenceForPackage(pkg)
            if (currentSurfacePkg == pkg) {
                currentSurfaceKey = null
                currentSurfacePkg = null
            }

            var effectiveBackCount = pendingBackCount
            if (pkg == "com.instagram.android") {
                val rootNow = currentRoot(event)
                val stateNow = rootNow?.let { instagramState(it, event) }
                val searchNow = rootNow?.let { isInstagramSearchScreen(it, event) } == true
                val homeSelected = rootNow?.let { hasSelectedLabel(it, listOf("home", "startseite")) } == true

                effectiveBackCount = when {
                    stateNow == "home" || homeSelected -> 0
                    stateNow == "reels" || stateNow == "explore" || searchNow -> pendingBackCount.coerceAtMost(2)
                    // Unknown Instagram state (frequent on rapid transitions): keep one conservative in-app back so users cannot stay in blocked viewers.
                    else -> if (homeSelected) 0 else pendingBackCount.coerceAtMost(1)
                }

                val guardUntil = ackNow + maxOf(INSTA_REELS_REENTRY_GUARD_MS, INSTA_EXPLORE_REENTRY_GUARD_MS) + 900L
                surfaceBlockGuardUntil["$pkg|ig:reels"] = guardUntil
                surfaceBlockGuardUntil["$pkg|ig:explore"] = guardUntil
            } else if (pkg == "com.google.android.youtube") {
                val ytPkg = "com.google.android.youtube"
                val rootNow = currentRoot(event)
                val ytWindowReady = isRootFromPackage(rootNow, ytPkg)
                val shortsNow = ytWindowReady && (rootNow?.let { isYouTubeShortsScreen(it, event) } == true)
                val homeSelected =
                    if (ytWindowReady) {
                        rootNow?.let {
                            hasSelectedLabelInPackage(
                                it,
                                listOf("home", "startseite"),
                                ytPkg
                            )
                        } == true
                    } else {
                        false
                    }

                // Never use BACK for deferred YouTube surface blocks: on some builds Shorts is a root tab and BACK minimizes YouTube to launcher. 
                // Prefer explicit in-app Home tab navigation.
                effectiveBackCount = 0

                // If YouTube root is not active yet, wait briefly for task restore first.
                // Launch fallback only on late retries to avoid launcher flashes.
                val movedToHome = when {
                    !ytWindowReady -> false
                    homeSelected -> true
                    else -> tryNavigateYouTubeToHome(rootNow)
                }

                // Guard against immediate re-trigger while tab transition settles.
                surfaceBlockGuardUntil["$pkg|yt:shorts"] = ackNow + 1_400L

                if (!movedToHome) {
                    // Some YouTube builds need an extra frame before bottom-nav nodes become clickable.
                    val retryDelays = longArrayOf(120L, 260L, 420L, 640L, 860L)
                    for (delay in retryDelays) {
                        handler.postDelayed({
                            runCatching {
                                val rootRetry = rootInActiveWindow

                                // If the active root is not YouTube yet (e.g. launcher while PiP is visible),
                                // bring YouTube task to foreground and wait for the next retry.
                                if (!isRootFromPackage(rootRetry, ytPkg)) {
                                    if (delay >= 860L) {
                                        launchYouTubeHomeFallback()
                                    }
                                    return@runCatching
                                }

                                val alreadyHome =
                                    rootRetry?.let {
                                        hasSelectedLabelInPackage(
                                            it,
                                            listOf("home", "startseite"),
                                            ytPkg
                                        )
                                    } == true
                                if (!alreadyHome) {
                                    tryNavigateYouTubeToHome(rootRetry)
                                }
                            }
                        }, delay)
                    }
                }

                if (shortsNow) {
                    // Additional short guard for repeated attempts from Shorts surface.
                    surfaceBlockGuardUntil["$pkg|yt:shorts"] = ackNow + 1_900L
                }
            }

            if (effectiveBackCount > 0) {
                performBackSequence(effectiveBackCount, initialDelayMs = 0L, stepMs = 130L)
            }

            // Let app navigation settle before processing additional enforcement.
            return
        }

        val now = System.currentTimeMillis()
        val type = event?.eventType ?: 0

        // Deduplicate noisy streams
        if (pkg == lastEventPkg) {
            val dt = now - lastEventAt
            val sameType = type == lastEventType
            if (type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED && dt < 120L) {
                perf.dedupeDrops++
                return
            }
            if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && sameType && dt < 60L) {
                perf.dedupeDrops++
                return
            }
        }

        lastEventPkg = pkg
        lastEventAt = now
        lastEventType = type

        // Keep top pkg updated; many apps do not produce transitions for surfaces like Shorts/Reels.
        val prevTopPkg = currentTopPkg
        currentTopPkg = pkg

        val isTransition =
            type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                type == AccessibilityEvent.TYPE_WINDOWS_CHANGED

        if (isTransition) {
            lastTransitionAt = now
            if (prevTopPkg != pkg) {
                appEnteredAtByPkg[pkg] = now

                // Snapshot usage at entry so limits can be enforced while the app stays open.
                // (Some devices only update UsageStats totalTimeInForeground once the app is backgrounded.)
                runCatching {
                    usageInternalAtEnterByPkg[pkg] = UsageStore.getUsageMsToday(this, pkg)
                    usageSystemAtEnterByPkg[pkg] = getSystemUsageMsToday(pkg, now)
                }

                clearSurfaceEvidenceForPackage(pkg)

                // Attempt-based limits: count an "open" on real transitions.
                // This is more reliable than relying on the periodic tick, because many apps emit transition events immediately and we update currentTopPkg here.
                maybeCountOpenAndEnforceAttemptLimit(pkg, now)
            }
            if (!isBrowserPackage(pkg)) {
                currentBrowserPkg = null
                currentBrowserDomain = null
                browserCandidateDomain = null
                browserCandidateSince = 0L
            }
            if (shouldRunDeepProbe(pkg, type, isTransition = true, now = now)) {
                maybeBlockNow(pkg, event)
            }
            return
        }

        // Special probe for browsers + social apps on content/text/scroll/click updates
        val specialPkg =
            isBrowserPackage(pkg) ||
                pkg == "com.google.android.youtube" ||
                pkg == "com.instagram.android"

        val specialEvent =
            type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
                type == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED ||
                type == AccessibilityEvent.TYPE_VIEW_SCROLLED ||
                type == AccessibilityEvent.TYPE_VIEW_CLICKED ||
                type == AccessibilityEvent.TYPE_VIEW_FOCUSED

        if (specialPkg && specialEvent) {
            if (shouldRunDeepProbe(pkg, type, isTransition = false, now = now)) {
                maybeBlockNow(pkg, event)
            }
        } else if (type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            // Fallback: shortly after transitions, reduce one-frame flash.
            if (now - lastTransitionAt <= 350L) {
                if (shouldRunDeepProbe(pkg, type, isTransition = false, now = now)) {
                    maybeBlockNow(pkg, event)
                }
            }
        }
    }

    private fun usageTick() {
        val now = System.currentTimeMillis()

        // Some apps produce very few accessibility events. 
        // To avoid tracking the wrong foreground package (which would break real-time limits), prefer the active window package when available.
        val rootPkg = runCatching { rootInActiveWindow?.packageName?.toString() }.getOrNull()
        if (!rootPkg.isNullOrBlank() && rootPkg != packageName && rootPkg != currentTopPkg) {
            currentTopPkg = rootPkg
            appEnteredAtByPkg[rootPkg] = now

            // Snapshot usage at entry so limits can be enforced while the app stays open.
            runCatching {
                usageInternalAtEnterByPkg[rootPkg] = UsageStore.getUsageMsToday(this, rootPkg)
                usageSystemAtEnterByPkg[rootPkg] = getSystemUsageMsToday(rootPkg, now)
            }

            clearSurfaceEvidenceForPackage(rootPkg)

            // Count "opens" for attempt-based limits and enforce quickly on entry.
            // (This does NOT require the app to be hard-blocked.)
            maybeCountOpenAndEnforceAttemptLimit(rootPkg, now)
        }

        // Periodically correct foreground package using UsageEvents.
        // This prevents runaway timers when the last seen accessibility event belonged to an app that is no longer actually in the foreground.
        refreshTopPackageIfNeeded(now)
        // Count opens using UsageEvents as a robust fallback (especially on OEMs that miss accessibility transitions when leaving/returning via Recents).
        scanUsageEventsForOpens(now)

        val pkg = rootPkg ?: (currentTopPkg ?: return)
        if (pkg.isBlank()) return
        val last = lastTickAt
        lastTickAt = now

        val delta = (now - last).coerceIn(0L, 5_000L)
        if (delta <= 0L) return

        // Only count usage while Switchly is enabled and screen is interactive.
        if (!pm.isInteractive) return
        if (km?.isKeyguardLocked == true) return
        if (!SwitchModeStore.isEnabled(this)) return
        if (EmergencyBypassStore.isActive(this)) return
        if (TempAllowStore.isAllowed(this, pkg)) return

        val nowForCache = now
        val profile = getCurrentProfileCached(nowForCache) ?: return

        // "Blocked time" should reflect how long apps are configured as blocked while Switchly is enabled (not how long the blocker UI is shown). Track it here, once per tick.
        val blockedSet = getBlockedAppsCached(profile, nowForCache)
        if (blockedSet.isNotEmpty()) {
            for (blockedPkg in blockedSet) {
                BlockedTimeStore.addBlockedMsToday(this, blockedPkg, delta)
            }
        }

        // In-app surface usage tracking (Shorts/Reels/Explore)
        val sk = currentSurfaceKey
        if (!sk.isNullOrBlank() && currentSurfacePkg == pkg) {
            SurfaceUsageStore.addUsageMsToday(this, sk, delta)
        }

        // Website usage tracking (per-domain) for supported browsers.
        // Track even if the browser itself is not in the profile block list.
        if (isBrowserPackage(pkg)) {
            // Some devices/browsers emit sparse transition events, especially when returning via Recents.
            // If we don't have a current domain signal, probe periodically so website stats + limits keep tracking.
            if ((currentBrowserDomain.isNullOrBlank() || currentBrowserPkg != pkg) &&
                shouldRunDeepProbe(pkg, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, isTransition = false, now = now)
            ) {
                maybeBlockWebsite(pkg, null)
            }

            val d = currentBrowserDomain
            if (!d.isNullOrBlank() && currentBrowserPkg == pkg) {
                WebUsageStore.addUsageMsToday(this, d, delta)
            }
        }

        // Usage limits should work even if the app isn't in the "blocked apps" list.
        // (Users can set a daily limit without hard-blocking the app.)
        val limitMin = getUsageLimitCached(profile, pkg, nowForCache)
        if (limitMin <= 0) return // hard block -> handled by event driven blocker

        UsageStore.addUsageMsToday(this, pkg, delta)

        // Prefer Switchly's in-process timer (fast/real-time), but if for any reason it undercounts (service paused, OEM throttling, missed events), fall back to the system-reported foreground time for today.
        val usedMs = getEffectiveUsageMsToday(pkg, now)
        val limitMs = limitMin * 60_000L
        if (usedMs >= limitMs) {
            LimitReachedStore.markReachedToday(this, pkg)
            maybeBlockNow(pkg, force = true)
        }
    }

    private fun getSystemUsageMsToday(pkg: String, now: Long): Long {
        val usm = getSystemService(USAGE_STATS_SERVICE) as? UsageStatsManager ?: return 0L

        val cal = java.util.Calendar.getInstance().apply {
            timeInMillis = now
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        val start = cal.timeInMillis

        return try {
            val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, now) ?: return 0L
            val s = stats.firstOrNull { it.packageName == pkg } ?: return 0L
            // totalTimeInForeground is the most stable signal across API levels.
            s.totalTimeInForeground.coerceAtLeast(0L)
        } catch (_: SecurityException) {
            0L
        } catch (_: Throwable) {
            0L
        }
    }

    /**
     * Returns the best-available "today" usage for [pkg].
     * We combine:
     * - Switchly's internal tick-based usage (real-time)
     * - system usage stats (can lag while app is still foreground)
     * - an estimate that adds internal delta-since-entry onto the system snapshot-at-entry
     */
    private fun getEffectiveUsageMsToday(pkg: String, now: Long): Long {
        val usedInternal = UsageStore.getUsageMsToday(this, pkg)
        val usedSystem = getSystemUsageMsToday(pkg, now)

        val internalAtEnter = usageInternalAtEnterByPkg[pkg] ?: usedInternal
        val systemAtEnter = usageSystemAtEnterByPkg[pkg] ?: usedSystem
        val deltaSinceEnter = (usedInternal - internalAtEnter).coerceAtLeast(0L)
        val estimatedSystemLive = systemAtEnter + deltaSinceEnter

        return maxOf(usedInternal, usedSystem, estimatedSystemLive)
    }

    /**
     * Refreshes [currentTopPkg] using UsageEvents at a low cadence.
     * If Usage Access isn't granted, this is a no-op.
     */
    private fun refreshTopPackageIfNeeded(now: Long) {
        // Don't spam the system service.
        if (now - lastTopRefreshAt < 3_000L) return
        lastTopRefreshAt = now

        val top = runCatching { resolveTopPackageFromUsageEvents(now) }.getOrNull() ?: return
        if (top.isBlank()) return
        if (top == currentTopPkg) return

        currentTopPkg = top
        appEnteredAtByPkg[top] = now

        // Snapshot usage at entry so limits can be enforced while the app stays open.
        // (Some devices only update UsageStats totalTimeInForeground once the app is backgrounded.)
        runCatching {
            usageInternalAtEnterByPkg[top] = UsageStore.getUsageMsToday(this, top)
            usageSystemAtEnterByPkg[top] = getSystemUsageMsToday(top, now)
        }

        clearSurfaceEvidenceForPackage(top)
        if (!isBrowserPackage(top)) {
            currentBrowserPkg = null
            currentBrowserDomain = null
            browserCandidateDomain = null
            browserCandidateSince = 0L
        }

        // If the foreground package was corrected via UsageEvents, also count an "open" here.
        // This covers devices that miss accessibility transition events when switching via Recents.
        maybeCountOpenAndEnforceAttemptLimit(top, now)
    }

    /**
     * Scans UsageEvents for MOVE_TO_FOREGROUND to count app opens reliably.
     *
     * Some OEMs (and some navigation flows) do not emit consistent accessibility transition
     * events when leaving/returning to apps (especially via Recents). UsageEvents is
     * the most reliable cross-device signal for "app became foreground".
     *
     * This is only used when the user has configured at least one attempt limit.
     */
    private fun scanUsageEventsForOpens(now: Long) {
        if (!hasAnyAttemptLimitsCached(now)) {
            lastUsageOpenScanAt = now
            return
        }

        // Throttle scanning to reduce overhead.
        if (lastUsageOpenScanAt == 0L) {
            lastUsageOpenScanAt = now
            return
        }
        if (now - lastUsageOpenScanAt < 1_500L) return

        // Small overlap so we do not miss events due to scheduling jitter.
        val from = (lastUsageOpenScanAt - 400L).coerceAtLeast((now - 30_000L).coerceAtLeast(0L))
        val to = now
        lastUsageOpenScanAt = now

        val usm = getSystemService(USAGE_STATS_SERVICE) as? UsageStatsManager ?: return
        val events = try {
            usm.queryEvents(from, to)
        } catch (_: SecurityException) {
            return
        } catch (_: Throwable) {
            return
        }

        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                val pkg = e.packageName?.trim().orEmpty()
                if (pkg.isNotBlank() && pkg != packageName) {
                    maybeCountOpenAndEnforceAttemptLimit(pkg, e.timeStamp)
                }
            }
        }
    }

    private fun resolveTopPackageFromUsageEvents(now: Long): String? {
        val usm = getSystemService(USAGE_STATS_SERVICE) as? UsageStatsManager ?: return null
        // Look back a short window; we only need the most recent foreground transition.
        val start = (now - 10_000L).coerceAtLeast(0L)
        val events = usm.queryEvents(start, now)
        val e = UsageEvents.Event()
        var lastPkg: String? = null
        var lastTs = 0L

        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                if (e.timeStamp >= lastTs && !e.packageName.isNullOrBlank()) {
                    lastTs = e.timeStamp
                    lastPkg = e.packageName
                }
            }
        }
        return lastPkg
    }

    /**
     * Attempt-based limits: block an app after it has been opened N times today.
     * We count an "open" when the app becomes the foreground package.
     * To avoid noisy OEM foreground flapping, we apply a short per-package cooldown.
     */
    private fun maybeCountOpenAndEnforceAttemptLimit(pkg: String, now: Long) {
        if (pkg.isBlank() || pkg == packageName) return

        // Only count while we would normally enforce.
        if (!pm.isInteractive) return
        if (km?.isKeyguardLocked == true) return
        if (!SwitchModeStore.isEnabled(this)) return
        if (EmergencyBypassStore.isActive(this)) return
        if (TempAllowStore.isAllowed(this, pkg)) return

        val profile = getCurrentProfileCached(now) ?: return
        val attemptLimit = getAttemptLimitCached(profile, pkg, now)
        if (attemptLimit <= 0) return

        // Don't count "opens" while the app is already hard-blocked or time-blocked.
        val blocked = getBlockedAppsCached(profile, now)
        val timeLimitMin = getUsageLimitCached(profile, pkg, now)
        // Treat an app as *hard blocked* only when it has no limits configured.
        // If the user sets a time/attempt limit for an app that is in the blocked list,
        // the limit should take precedence (otherwise it looks "broken").
        val hardBlocked = isManagedPackage(pkg, blocked) && timeLimitMin <= 0 && attemptLimit <= 0
        val timeBlocked = timeLimitMin > 0 && LimitReachedStore.isReachedToday(this, pkg)
        if (hardBlocked || timeBlocked) return

        val last = lastOpenCountAt[pkg] ?: 0L
        if (now - last < OPEN_COUNT_COOLDOWN_MS) return
        lastOpenCountAt[pkg] = now

        OpenCountStore.incrementToday(this, profile, pkg)
        val opensToday = OpenCountStore.getToday(this, profile, pkg)
        if (opensToday > attemptLimit) {
            // Block immediately on the first open past the cap.
            maybeBlockNow(pkg, force = true)
        }
    }

    private fun maybeBlockNow(pkg: String, event: AccessibilityEvent? = null, force: Boolean = false) {
        perf.maybeBlockCalls++

        if (!pm.isInteractive) return
        if (km?.isKeyguardLocked == true) return
        if (EmergencyBypassStore.isActive(this)) return
        if (!SwitchModeStore.isEnabled(this)) return
        if (!shouldRunEnforcement(pkg, event, force)) return
        val tempAllowed = TempAllowStore.isAllowed(this, pkg)

        // Website/domain blocking + in-app blocks (even if app itself isn't blocked)
        // NOTE: temp-allow should not bypass in-app restrictions (Shorts/Reels/etc.).
        maybeBlockWebsite(pkg, event)
        maybeInAppBlock(pkg, event)

        if (tempAllowed) return

        val nowForCache = System.currentTimeMillis()
        val profile = getCurrentProfileCached(nowForCache) ?: return
        val blocked = getBlockedAppsCached(profile, nowForCache)
        val lockActive = SwitchModeStore.isNfcRequiredForDisable(this)
        val limitMin = getUsageLimitCached(profile, pkg, nowForCache)
        val attemptLimit = getAttemptLimitCached(profile, pkg, nowForCache)

        val opensExceeded = attemptLimit > 0 && OpenCountStore.getToday(this, profile, pkg) > attemptLimit
        // Same precedence rule as above: only hard-block when *no* limits are configured.
        val hardBlocked = isManagedPackage(pkg, blocked) && limitMin <= 0 && attemptLimit <= 0

        // Enforcement should apply either when the app is hard-blocked OR when it has a daily limit.
        // (Users can set a daily limit without adding the app to the hard-block list.)
        val managed = isManagedPackage(pkg, blocked) || (limitMin > 0) || (attemptLimit > 0)
        if (!managed) return

        val shouldBlockNow =
            hardBlocked ||
                opensExceeded ||
                (limitMin > 0 && (force || getEffectiveUsageMsToday(pkg, System.currentTimeMillis()) >= limitMin * 60_000L))

        if (lockActive && isHighRisk(pkg)) {
            blockNow(pkg, immediate = true)
            return
        }

        if (!shouldBlockNow) return

        val immediate = hardBlocked || opensExceeded || force
        blockNow(pkg, immediate = immediate)
    }


    private fun performBackSequence(
        backCount: Int,
        initialDelayMs: Long = 0L,
        stepMs: Long = 120L
    ) {
        if (backCount <= 0) return
        for (i in 0 until backCount) {
            val delay = initialDelayMs + i.toLong() * stepMs
            if (delay <= 0L) {
                runCatching { performGlobalAction(GLOBAL_ACTION_BACK) }
            } else {
                handler.postDelayed({
                    runCatching { performGlobalAction(GLOBAL_ACTION_BACK) }
                }, delay)
            }
        }
    }


    private fun pauseActiveMediaPlayback() {
        val audio = getSystemService(AUDIO_SERVICE) as? AudioManager ?: return
        val down = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE)
        val up = KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE)
        runCatching { audio.dispatchMediaKeyEvent(down) }
        runCatching { audio.dispatchMediaKeyEvent(up) }
    }

    /**
     * "Soft" block: keep the app open, but force user out of the current surface (e.g., a website tab, Shorts/Reels), then show the blocker UI as feedback. 
     * This avoids making the whole browser/app unusable.
     */
    private fun softBlockSurface(
        pkg: String,
        appLabel: String,
        title: String,
        message: String,
        backCount: Int = 1,
        deferNavigationUntilAcknowledge: Boolean = false
    ) {
        val now = System.currentTimeMillis()

        // Prevent getting stuck in a block loop while the UI transitions.
        val sk = pkg + "|" + title
        val lastSurf = lastSurfaceBlockAt[sk] ?: 0L
        if (now - lastSurf < SURFACE_BLOCK_COOLDOWN_MS) return
        lastSurfaceBlockAt[sk] = now

        if (BlockerActivity.isVisible) return

        val lastShown = lastBlockShownAt[pkg] ?: 0L
        val canShow =
            (now - lastShown) >= BLOCK_SHOWN_COOLDOWN_MS && (now - lastGlobalBlockTs) >= 250L
        if (!canShow) return

        // Short grace period after a surface block to prevent Reels/Shorts re-detect loops while the app is animating back to feed/home.
        inAppGraceUntilByPkg[pkg] = now + INAPP_POST_BLOCK_GRACE_MS
        clearSurfaceEvidenceForPackage(pkg)
        if (currentSurfacePkg == pkg) {
            currentSurfaceKey = null
            currentSurfacePkg = null
        }

        // Extra re-entry guards are only needed when we auto-navigate away ourselves.
        if (!deferNavigationUntilAcknowledge) {
            if (pkg == "com.instagram.android" && title.contains(getString(R.string.in_app_surface_reels_label), ignoreCase = true)) {
                surfaceBlockGuardUntil["$pkg|ig:reels"] = now + INSTA_REELS_REENTRY_GUARD_MS
            }
            if (pkg == "com.instagram.android" && title.contains(getString(R.string.in_app_surface_explore_label), ignoreCase = true)) {
                surfaceBlockGuardUntil["$pkg|ig:explore"] = now + INSTA_EXPLORE_REENTRY_GUARD_MS
            }
            if (pkg == "com.google.android.youtube" && title.contains(getString(R.string.in_app_surface_shorts_label), ignoreCase = true)) {
                surfaceBlockGuardUntil["$pkg|yt:shorts"] = now + YT_SHORTS_REENTRY_GUARD_MS
            }
        }

        lastBlockShownAt[pkg] = now
        lastGlobalBlockTs = now
        BlockCountStore.incrementToday(this, pkg)
        perf.blocksShown++

        if (!deferNavigationUntilAcknowledge) {
            // Go back out of the current screen/tab immediately.
            performBackSequence(backCount, initialDelayMs = 0L, stepMs = 120L)
        } else if (pkg == "com.google.android.youtube" && title.contains(getString(R.string.in_app_surface_shorts_label), ignoreCase = true)) {
            // Reduce visible PiP/miniplayer flashes while the blocker popup is shown: try to leave Shorts in-app before opening the popup (without global BACK).
            runCatching { pauseActiveMediaPlayback() }
            runCatching {
                val rootNow = rootInActiveWindow
                if (isRootFromPackage(rootNow, "com.google.android.youtube")) {
                    val moved = tryNavigateYouTubeToHome(rootNow)
                    if (moved) {
                        surfaceBlockGuardUntil["$pkg|yt:shorts"] = now + YT_SHORTS_REENTRY_GUARD_MS
                    }
                }
            }
        }

        val showDelay = if (deferNavigationUntilAcknowledge) 0L else 30L
        val resolvedMessage = buildSurfaceBlockMessage(pkg = pkg, title = title, originalMessage = message)
        handler.postDelayed({
            runCatching {
                BlockerActivity.showDetailed(
                    this,
                    pkg,
                    appLabel,
                    title,
                    resolvedMessage,
                    postAcknowledgeBackCount = if (deferNavigationUntilAcknowledge) backCount else 0
                )
            }
        }, showDelay)
    }

    private fun buildSurfaceBlockMessage(pkg: String, title: String, originalMessage: String): String {
        val isWebsiteBlock =
            title == getString(R.string.blocking_website_blocked_title) ||
                title == getString(R.string.blocking_website_limit_reached_title)
        val isInAppFeaturePkg =
            pkg == "com.google.android.youtube" ||
                pkg == "com.instagram.android"

        if (!isInAppFeaturePkg || isWebsiteBlock) return originalMessage

        val base = getString(R.string.blocking_surface_base_message)
        val trimmed = originalMessage.trim()
        val keepDetail = trimmed.startsWith(getString(R.string.blocking_daily_limit_duration_fmt, "" ).substringBefore(":"), ignoreCase = true) ||
            trimmed.startsWith(getString(R.string.blocking_in_app_limit_reached_title).substringBefore("!"), ignoreCase = true)

        return if (keepDetail) {
            "$base\n$trimmed"
        } else {
            base
        }
    }

    private fun blockNow(pkg: String, immediate: Boolean) {
        val now = System.currentTimeMillis()
        if (BlockerActivity.isVisible) return

        val lastAttempt = lastAttemptAt[pkg] ?: 0L
        val countAttempt = (now - lastAttempt) >= ATTEMPT_COOLDOWN_MS

        val lastShown = lastBlockShownAt[pkg] ?: 0L
        val countAsBlock =
            (now - lastShown) >= BLOCK_SHOWN_COOLDOWN_MS && (now - lastGlobalBlockTs) >= 250L

        if (countAttempt) {
            lastAttemptAt[pkg] = now
            BlockAttemptStore.incrementToday(this, pkg)
        }
        if (countAsBlock) {
            lastBlockShownAt[pkg] = now
            lastGlobalBlockTs = now
            BlockCountStore.incrementToday(this, pkg)
            perf.blocksShown++
        }

        val label = runCatching {
            val ai = packageManager.getApplicationInfo(pkg, 0)
            packageManager.getApplicationLabel(ai).toString()
        }.getOrNull() ?: pkg

        bounceHomeAndKill(pkg)

        val delayMs = if (immediate) 80L else 120L
        handler.postDelayed({
            runCatching { BlockerActivity.show(this, pkg, label) }
        }, delayMs)
    }
    
    private fun formatDuration(ms: Long): String {
        val totalMin = (ms/60_000L).coerceAtLeast(0L)
        val h = totalMin/60L
        val m = totalMin % 60L
        return when {
            h > 0L && m > 0L -> "${h}h ${m}m"
            h > 0L -> "${h}h"
            totalMin > 0L -> "${totalMin}m"
            else -> "<1m"
        }
    }

    private fun safeAppLabel(pkg: String): String {
        return runCatching {
            val ai = packageManager.getApplicationInfo(pkg, 0)
            packageManager.getApplicationLabel(ai).toString()
        }.getOrNull() ?: pkg
    }

    private fun surfaceUsageLine(@Suppress("UNUSED_PARAMETER") surfaceKey: String, limitMin: Int): String {
        if (limitMin <= 0) return ""
        return getString(R.string.blocking_daily_limit_duration_fmt, formatDuration(limitMin.toLong() * 60_000L))
    }

    private fun domainUsageLine(@Suppress("UNUSED_PARAMETER") domain: String, limitMin: Int): String {
        if (limitMin <= 0) return ""
        return getString(R.string.blocking_daily_limit_duration_fmt, formatDuration(limitMin.toLong() * 60_000L))
    }

    private fun inAppGlobalLimitMin(): Int {
        val now = System.currentTimeMillis()
        val profile = getCurrentProfileCached(now) ?: "default"

        val fresh = cachedInAppLimitProfile == profile && (now - cachedInAppLimitAt) <= POLICY_CACHE_TTL_MS
        if (fresh) return cachedInAppLimitMin

        val next = InAppLimitStore.getLimitMinutes(this, profile)
        cachedInAppLimitProfile = profile
        cachedInAppLimitAt = now
        cachedInAppLimitMin = next
        return next
    }

    private fun surfaceRuleValue(surfaceKey: String): Int {
        val now = System.currentTimeMillis()
        val profile = getCurrentProfileCached(now) ?: "default"

        val fresh =
            cachedSurfaceRulesProfile == profile &&
                (now - cachedSurfaceRulesAt) <= POLICY_CACHE_TTL_MS
        if (!fresh) {
            cachedSurfaceRulesProfile = profile
            cachedSurfaceRulesAt = now
            cachedSurfaceRuleByKey.clear()
        }

        return cachedSurfaceRuleByKey.getOrPut(surfaceKey) {
            SurfaceLimitStore.getRule(this, profile, surfaceKey)
        }
    }

    private fun inAppTotalUsageMsToday(): Long {
        var total = 0L

        if (prefsBoolProfile(false, BlockingToggleKeys.KEY_BLOCK_YT_SHORTS)) {
            total += SurfaceUsageStore.getUsageMsToday(this, "yt:shorts")
        }
        if (prefsBoolProfile(false, BlockingToggleKeys.KEY_BLOCK_IG_REELS)) {
            total += SurfaceUsageStore.getUsageMsToday(this, "ig:reels")
        }
        if (prefsBoolProfile(false, BlockingToggleKeys.KEY_BLOCK_IG_EXPLORE)) {
            total += SurfaceUsageStore.getUsageMsToday(this, "ig:explore")
        }
        if (prefsBoolProfile(false, BlockingToggleKeys.KEY_BLOCK_IG_STORIES)) {
            total += SurfaceUsageStore.getUsageMsToday(this, "ig:stories")
        }

        return total
    }

    private fun inAppUsageLine(limitMin: Int): String {
        if (limitMin <= 0) return ""
        return "In-app limit: ${formatDuration(limitMin.toLong() * 60_000L)}"
    }
    private fun sanitizeProfile(profile: String): String {
        return profile.trim()
            .lowercase(Locale.getDefault())
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
            .ifBlank { "default" }
    }

    private fun scopedKey(profile: String, baseKey: String): String {
        return "p_${sanitizeProfile(profile)}_$baseKey"
    }

    /**
     * Profile-scoped preference read.
     * Order:
     * 1) scoped key for current profile
     * 2) base (global) key
     * 3) default
     */
    private fun prefsBoolProfile(def: Boolean, baseKey: String): Boolean {
        val now = System.currentTimeMillis()
        val profile = getCurrentProfileCached(now)
        val profileKey = profile ?: ""

        val fresh =
            cachedPrefsProfile == profileKey &&
                (now - cachedPrefsAt) <= POLICY_CACHE_TTL_MS
        if (!fresh) {
            cachedPrefsProfile = profileKey
            cachedPrefsAt = now
            cachedPrefsBool.clear()
        }

        // Global master gate for all in-app surface blockers.
        if (BlockingToggleKeys.isInAppSurfaceKey(baseKey)) {
            val inAppEnabled = prefsBoolProfile(true, BlockingToggleKeys.KEY_BLOCK_INAPP)
            if (!inAppEnabled) {
                cachedPrefsBool[baseKey] = false
                return false
            }
        }

        cachedPrefsBool[baseKey]?.let { return it }

        val sp = PreferenceManager.getDefaultSharedPreferences(this)

        val value = if (!profile.isNullOrBlank()) {
            val scoped = scopedKey(profile, baseKey)
            if (sp.contains(scoped)) {
                sp.getBoolean(scoped, def)
            } else if (sp.contains(baseKey)) {
                sp.getBoolean(baseKey, def)
            } else {
                def
            }
        } else if (sp.contains(baseKey)) {
            sp.getBoolean(baseKey, def)
        } else {
            def
        }

        cachedPrefsBool[baseKey] = value
        return value
    }

    private fun isBrowserPackage(pkg: String): Boolean {
        return pkg == "com.android.chrome" ||
            pkg == "com.brave.browser" ||
            pkg == "com.microsoft.emmx" ||
            pkg == "com.opera.browser" ||
            pkg == "com.opera.browser.beta" ||
            pkg == "com.opera.mini.native" ||
            pkg == "com.sec.android.app.sbrowser" ||
            pkg == "com.sec.android.app.sbrowser.beta" ||
            pkg == "org.mozilla.firefox" ||
            pkg == "org.mozilla.firefox_beta" ||
            pkg == "org.mozilla.fennec_fdroid" ||
            pkg == "org.mozilla.focus" ||
            pkg == "org.mozilla.fenix" ||
            pkg == "com.kiwibrowser.browser" ||
            pkg == "com.vivaldi.browser" ||
            pkg == "com.duckduckgo.mobile.android" ||
            pkg == "com.google.android.apps.chrome" ||
            pkg == "com.chrome.beta" ||
            pkg == "com.chrome.dev"
    }

    private fun browserUrlViewIds(pkg: String): List<String> {
        return when (pkg) {
            "com.android.chrome" -> 
                listOf(
                    "com.android.chrome:id/url_bar"
                )

            "com.brave.browser" -> 
                listOf(
                    "com.brave.browser:id/url_bar", 
                    "com.android.chrome:id/url_bar"
                )

            "com.microsoft.emmx" -> 
                listOf(
                    "com.microsoft.emmx:id/url_bar", 
                    "com.android.chrome:id/url_bar"
                )

            "com.opera.browser", "com.opera.browser.beta", "com.opera.mini.native" ->
                listOf(
                    "com.opera.browser:id/url_field", 
                    "com.opera.browser:id/url_bar", 
                    "com.opera.browser:id/address_bar"
                )

            "com.sec.android.app.sbrowser", "com.sec.android.app.sbrowser.beta" ->
                listOf(
                    "com.sec.android.app.sbrowser:id/location_bar_edit_text", 
                    "com.sec.android.app.sbrowser:id/location_bar"
                )

            "org.mozilla.firefox" -> listOf(
                "org.mozilla.firefox:id/mozac_browser_toolbar_url_view",
                "org.mozilla.firefox:id/mozac_browser_toolbar_edit_url_view",
                "org.mozilla.firefox:id/mozac_browser_toolbar_display_url_view",
                "org.mozilla.firefox:id/mozac_browser_toolbar_origin_view"
            )

            "org.mozilla.firefox_beta" -> 
                listOf(
                    "org.mozilla.firefox_beta:id/mozac_browser_toolbar_url_view",
                    "org.mozilla.firefox_beta:id/mozac_browser_toolbar_edit_url_view",
                    "org.mozilla.firefox_beta:id/mozac_browser_toolbar_display_url_view",
                    "org.mozilla.firefox_beta:id/mozac_browser_toolbar_origin_view"
                )

            "org.mozilla.fennec_fdroid" -> 
                listOf(
                    "org.mozilla.fennec_fdroid:id/mozac_browser_toolbar_url_view",
                    "org.mozilla.fennec_fdroid:id/mozac_browser_toolbar_edit_url_view",
                    "org.mozilla.fennec_fdroid:id/mozac_browser_toolbar_display_url_view",
                    "org.mozilla.fennec_fdroid:id/mozac_browser_toolbar_origin_view"
                )

            "org.mozilla.focus" -> 
                listOf(
                    "org.mozilla.focus:id/urlInputView"
                )

            "org.mozilla.fenix" -> 
                listOf(
                    "org.mozilla.fenix:id/mozac_browser_toolbar_url_view",
                    "org.mozilla.fenix:id/mozac_browser_toolbar_edit_url_view",
                    "org.mozilla.fenix:id/mozac_browser_toolbar_display_url_view",
                    "org.mozilla.fenix:id/mozac_browser_toolbar_origin_view"
                )

            "com.kiwibrowser.browser" -> 
                listOf(
                    "com.kiwibrowser.browser:id/url_bar", 
                    "com.android.chrome:id/url_bar"
                )

            "com.vivaldi.browser" -> 
                listOf(
                    "com.vivaldi.browser:id/url_bar", 
                    "com.android.chrome:id/url_bar"
                )

            "com.duckduckgo.mobile.android" -> 
                listOf(
                    "com.duckduckgo.mobile.android:id/omnibarTextInput"
                )

            "com.google.android.apps.chrome" -> 
                listOf(
                    "com.android.chrome:id/url_bar"
                )

            "com.chrome.beta" -> 
                listOf(
                    "com.chrome.beta:id/url_bar", 
                    "com.android.chrome:id/url_bar"
                )

            "com.chrome.dev" -> 
                listOf(
                    "com.chrome.dev:id/url_bar", 
                    "com.android.chrome:id/url_bar"
                )

            else -> emptyList()
        }
    }

    private fun findBrowserUrlNode(root: AccessibilityNodeInfo, pkg: String): AccessibilityNodeInfo? {
        val ids = browserUrlViewIds(pkg)
        for (id in ids) {
            val nodes = runCatching { root.findAccessibilityNodeInfosByViewId(id) }.getOrNull() ?: emptyList()
            val best = nodes.firstOrNull {
                !it.text?.toString().isNullOrBlank() ||
                    !it.contentDescription?.toString().isNullOrBlank()
            }
                ?: nodes.firstOrNull()
            if (best != null) return best
        }

        // Firefox/Fenix can shift view IDs between versions. Fallback by scanning toolbar-like nodes.
        if (pkg.startsWith("org.mozilla.")) {
            return findAnyNode(root) { node ->
                val vid = node.viewIdResourceName?.lowercase(Locale.getDefault()).orEmpty()
                if (!(vid.contains("mozac") || vid.contains("toolbar") || vid.contains("url") || vid.contains("origin"))) {
                    return@findAnyNode false
                }
                val t = node.text?.toString().orEmpty()
                val cd = node.contentDescription?.toString().orEmpty()
                val c = (t + " " + cd).trim()
                c.contains(".") || c.contains("http", ignoreCase = true)
            }
        }

        return null
    }

    private fun isBrowserAddressEditing(
        root: AccessibilityNodeInfo,
        pkg: String,
        event: AccessibilityEvent?
    ): Boolean {
        val node = findBrowserUrlNode(root, pkg) ?: return false
        val cls = node.className?.toString().orEmpty()
        val isEdit = node.isEditable || cls.contains("EditText", ignoreCase = true)
        val focused = node.isFocused || node.isAccessibilityFocused
        if (focused) return true

        val type = event?.eventType ?: 0
        val inputEvent = type == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED ||
            type == AccessibilityEvent.TYPE_VIEW_FOCUSED ||
            type == AccessibilityEvent.TYPE_VIEW_CLICKED
        return isEdit && inputEvent
    }

    private fun tryExtractDomainFromBrowser(root: AccessibilityNodeInfo?, pkg: String): String? {
        if (root == null) return null
        val urlNode = findBrowserUrlNode(root, pkg)
        val t = urlNode?.text?.toString()?.trim().orEmpty()
        if (t.isNotBlank()) {
            domainFromText(t)?.let { return it }
        }

        val cd = urlNode?.contentDescription?.toString()?.trim().orEmpty()
        if (cd.isNotBlank()) {
            domainFromText(cd)?.let { return it }
        }

        // Fallback: only consider address-bar-like editable nodes (avoid matching page content/tab titles)
        val candidate = findEditableUrlText(root)
        return candidate?.let { domainFromText(it) }
    }

    private fun domainFromText(raw: String): String? {
        val s0 = raw.trim()
        if (s0.isBlank()) return null

        val token = s0.split(" ", "›", "·", "|", "—", "-", " ")
            .firstOrNull { it.contains(".") } ?: s0
        val s = token.trim()

        val rx = Regex("""(?i)(?:https?://)?([a-z0-9.-]+\.[a-z]{2,})(?::\d+)?""")
        val m = rx.find(s)
        val host = m?.groupValues?.getOrNull(1)
        if (!host.isNullOrBlank()) return DomainBlockStore.normalize(host)

        val withScheme = if (s.startsWith("http://") || s.startsWith("https://")) s else "https://$s"
        val parsed = runCatching { withScheme.toUri().host }.getOrNull() ?: return null
        return DomainBlockStore.normalize(parsed)
    }

    private fun findEditableUrlText(node: AccessibilityNodeInfo): String? {
        val t = node.text?.toString()?.trim()
        val cd = node.contentDescription?.toString()?.trim()
        val vid = node.viewIdResourceName?.lowercase(Locale.getDefault()).orEmpty()
        val cls = node.className?.toString().orEmpty()

        val candidate = when {
            !t.isNullOrBlank() -> t
            !cd.isNullOrBlank() -> cd
            else -> null
        }

        val looksLikeUrl = candidate != null && candidate.length in 4..300 && candidate.contains(".")
        val idHints = vid.contains("url") || vid.contains("address") || vid.contains("omnibox") ||
            vid.contains("location") || vid.contains("toolbar")
        val isEdit = node.isEditable || cls.contains("EditText", ignoreCase = true)

        // Ignore actively focused editable fields to avoid blocking while typing/autocomplete.
        val editingNow = node.isFocused || node.isAccessibilityFocused

        if (candidate != null && looksLikeUrl && (idHints || isEdit) && !editingNow) {
            return candidate
        }
        for (i in 0 until node.childCount) {
            val c = node.getChild(i) ?: continue
            val r = findEditableUrlText(c)
            if (r != null) return r
        }
        return null
    }

    private fun findFirstTextMatching(node: AccessibilityNodeInfo, pred: (String) -> Boolean): String? {
        val t = node.text?.toString()
        if (!t.isNullOrBlank() && pred(t)) return t
        for (i in 0 until node.childCount) {
            val c = node.getChild(i) ?: continue
            val r = findFirstTextMatching(c, pred)
            if (r != null) return r
        }
        return null
    }

    private fun currentRoot(event: AccessibilityEvent? = null): AccessibilityNodeInfo? {
        return rootInActiveWindow
            ?: event?.source
            ?: runCatching { windows?.firstOrNull { it.isActive }?.root }.getOrNull()
            ?: runCatching { windows?.firstOrNull()?.root }.getOrNull()
    }

    private fun requiresDomainStability(pkg: String): Boolean {
        // Firefox/Fenix often emits fewer stable URL-bar events than Chromium browsers.
        // Avoid a second-event requirement there, otherwise blocks can be missed.
        return !pkg.startsWith("org.mozilla.")
    }

    private fun maybeBlockWebsite(pkg: String, event: AccessibilityEvent? = null) {
        if (!isBrowserPackage(pkg)) return
        perf.websiteScans++

        val root = currentRoot(event) ?: run {
            perf.rootMisses++
            return
        }

        // Don't block while user is typing in the address bar/autocomplete.
        if (isBrowserAddressEditing(root, pkg, event)) {
            currentBrowserPkg = pkg
            currentBrowserDomain = null
            browserCandidateDomain = null
            browserCandidateSince = 0L
            return
        }

        // Extra guard: ignore direct typing/focus events from the URL field.
        val et = event?.eventType ?: 0
        if (et == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED || et == AccessibilityEvent.TYPE_VIEW_FOCUSED) {
            return
        }

        val host = tryExtractDomainFromBrowser(root, pkg) ?: run {
            browserCandidateDomain = null
            browserCandidateSince = 0L
            return
        }

        // Require a short stable domain signal to avoid premature blocks on autocomplete suggestions.
        // For Firefox/Fenix we skip the second-event requirement because some builds emit fewer URL events.
        val now = System.currentTimeMillis()
        val needStability = requiresDomainStability(pkg)
        if (browserCandidateDomain != host) {
            browserCandidateDomain = host
            browserCandidateSince = now
            if (needStability) return
        }
        if (needStability && (now - browserCandidateSince < BROWSER_DOMAIN_CONFIRM_MS)) {
            return
        }

        // Cache for web usage stats
        currentBrowserPkg = pkg
        currentBrowserDomain = host

        if (!isDomainBlockingEnabledCached()) return
        if (!prefsBoolProfile(true, BlockingToggleKeys.KEY_BLOCK_WEBSITES)) return

        val blockedDomains = DomainBlockStore.getDomains(this)
        val hardBlocked = blockedDomains.any { DomainBlockStore.matches(host, it) }

        val limitMin = DomainLimitStore.getLimitMinutes(this, host)
        val limitReached = limitMin > 0 && WebUsageStore.getUsageMsToday(this, host) >= limitMin.toLong() * 60_000L

        if (!hardBlocked && !limitReached) return

        val appLabel = safeAppLabel(pkg)
        val title = if (hardBlocked) getString(R.string.blocking_website_blocked_title) else getString(R.string.blocking_website_limit_reached_title)
        val msg = domainUsageLine(host, limitMin)

        // Soft block: only leave the current tab/page (keep browser usable)
        softBlockSurface(pkg, appLabel, title, msg, backCount = 1)
    }

    private fun nodeHasViewId(root: AccessibilityNodeInfo, viewIds: List<String>): Boolean {
        for (id in viewIds) {
            val nodes = runCatching { root.findAccessibilityNodeInfosByViewId(id) }.getOrNull() ?: emptyList()
            if (nodes.isNotEmpty()) return true
        }
        return false
    }

    private fun findAnyNode(root: AccessibilityNodeInfo, pred: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (pred(root)) return root
        for (i in 0 until root.childCount) {
            val c = root.getChild(i) ?: continue
            val r = findAnyNode(c, pred)
            if (r != null) return r
        }
        return null
    }

    private fun nodeTextMatches(root: AccessibilityNodeInfo, needles: List<String>): Boolean {
        val n = needles.map { it.lowercase(Locale.getDefault()) }
        return findAnyNode(root) { node ->
            val t = node.text?.toString()?.lowercase(Locale.getDefault())
            val cd = node.contentDescription?.toString()?.lowercase(Locale.getDefault())
            (t != null && n.any { t.contains(it) }) || (cd != null && n.any { cd.contains(it) })
        } != null
    }

    private fun eventTextMatches(event: AccessibilityEvent?, needles: List<String>): Boolean {
        if (event == null) return false
        val n = needles.map { it.lowercase(Locale.getDefault()) }
        val cd = event.contentDescription?.toString()?.lowercase(Locale.getDefault())
        if (cd != null && n.any { cd.contains(it) }) return true
        // event.text is a List<CharSequence>
        val texts = event.text ?: emptyList()
        for (cs in texts) {
            val t = cs?.toString()?.lowercase(Locale.getDefault()) ?: continue
            if (n.any { t.contains(it) }) return true
        }
        return false
    }

    private fun hasSelectedLabel(root: AccessibilityNodeInfo, needles: List<String>): Boolean {
        val n = needles.map { it.lowercase(Locale.getDefault()) }
        return findAnyNode(root) { node ->
            if (!node.isSelected) return@findAnyNode false
            val t = node.text?.toString()?.lowercase(Locale.getDefault())
            val cd = node.contentDescription?.toString()?.lowercase(Locale.getDefault())
            (t != null && n.any { t.contains(it) }) || (cd != null && n.any { cd.contains(it) })
        } != null
    }

    private fun hasSelectedLabelInPackage(
        root: AccessibilityNodeInfo,
        needles: List<String>,
        pkg: String
    ): Boolean {
        val n = needles.map { it.lowercase(Locale.getDefault()) }
        val targetPkg = pkg.lowercase(Locale.getDefault())
        return findAnyNode(root) { node ->
            if (!node.isSelected) return@findAnyNode false
            val nodePkg = node.packageName?.toString()?.lowercase(Locale.getDefault()).orEmpty()
            if (nodePkg != targetPkg) return@findAnyNode false

            val t = node.text?.toString()?.lowercase(Locale.getDefault())
            val cd = node.contentDescription?.toString()?.lowercase(Locale.getDefault())
            (t != null && n.any { t.contains(it) }) || (cd != null && n.any { cd.contains(it) })
        } != null
    }

    private fun isRootFromPackage(root: AccessibilityNodeInfo?, pkg: String): Boolean {
        val r = root ?: return false
        val targetPkg = pkg.lowercase(Locale.getDefault())

        val rootPkg = r.packageName?.toString()?.lowercase(Locale.getDefault()).orEmpty()
        if (rootPkg == targetPkg) return true

        return findAnyNode(r) { node ->
            node.packageName?.toString()?.lowercase(Locale.getDefault()) == targetPkg
        } != null
    }

    private fun clickNodeOrClickableParent(node: AccessibilityNodeInfo?): Boolean {
        var current = node
        var hops = 0
        while (current != null && hops < 6) {
            val canClick =
                current.isClickable ||
                    (current.actionList?.any { it.id == AccessibilityNodeInfo.ACTION_CLICK } == true)
            if (canClick) {
                val clicked = runCatching { current.performAction(AccessibilityNodeInfo.ACTION_CLICK) }.getOrDefault(false)
                if (clicked) return true
            }
            current = current.parent
            hops++
        }
        return false
    }

    private fun launchYouTubeHomeFallback() {
        // Keep navigation inside YouTube app; avoid HOME/launcher transitions.
        val launchIntent = runCatching {
            packageManager.getLaunchIntentForPackage("com.google.android.youtube")?.apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION
                )
            }
        }.getOrNull()

        if (launchIntent != null) {
            runCatching { startActivity(launchIntent) }
            return
        }

        // Last-resort app-scoped deep-link fallback.
        runCatching {
            val deepLink = Intent(Intent.ACTION_VIEW, "vnd.youtube://".toUri()).apply {
                setPackage("com.google.android.youtube")
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION
                )
            }
            startActivity(deepLink)
        }
    }

    private fun tryNavigateYouTubeToHome(root: AccessibilityNodeInfo?): Boolean {
        val r = root ?: return false
        val labels = listOf("home", "startseite")
        val ytPkg = "com.google.android.youtube"

        fun isHomeLabel(node: AccessibilityNodeInfo): Boolean {
            val nodePkg = node.packageName?.toString()?.lowercase(Locale.getDefault()).orEmpty()
            if (nodePkg != ytPkg) return false

            val t = node.text?.toString()?.lowercase(Locale.getDefault()).orEmpty()
            val cd = node.contentDescription?.toString()?.lowercase(Locale.getDefault()).orEmpty()
            return labels.any { t.contains(it) || cd.contains(it) }
        }

        // Prefer bottom navigation/pivot candidates first.
        val navCandidate = findAnyNode(r) { n ->
            if (!isHomeLabel(n)) return@findAnyNode false
            val vid = n.viewIdResourceName?.lowercase(Locale.getDefault()).orEmpty()
            if (vid.isNotEmpty() && !vid.startsWith("$ytPkg:id/")) return@findAnyNode false
            vid.contains("pivot") ||
                vid.contains("bottom") ||
                vid.contains("navigation") ||
                vid.contains("tab") ||
                vid.contains("nav")
        }
        if (clickNodeOrClickableParent(navCandidate)) return true

        val genericCandidate = findAnyNode(r) { n ->
            if (!isHomeLabel(n) || n.isSelected) return@findAnyNode false
            val vid = n.viewIdResourceName?.lowercase(Locale.getDefault()).orEmpty()
            // Avoid accidental clicks on system HOME controls (which would minimize YouTube).
            vid.startsWith("$ytPkg:id/") || vid.isBlank()
        }
        if (clickNodeOrClickableParent(genericCandidate)) return true

        // Already on home tab.
        val alreadyHome = findAnyNode(r) { n -> isHomeLabel(n) && n.isSelected } != null
        return alreadyHome
    }

    private fun maybeInAppBlock(pkg: String, event: AccessibilityEvent? = null) {
        val now = System.currentTimeMillis()
        val graceUntil = inAppGraceUntilByPkg[pkg] ?: 0L
        if (now < graceUntil) return

        // Fast path: ignore apps that do not have in-app surface rules.
        // This avoids expensive root-tree scans and usage reads on every foreground app.
        val supportedInAppPkg =
            pkg == "com.google.android.youtube" ||
                pkg == "com.instagram.android"
        if (!supportedInAppPkg) return

        // Fast path: if there is no active rule/toggle for this app, skip deep tree scans completely.
        if (!packageHasInAppFeaturesEnabled(pkg)) {
            perf.inAppNoRuleSkips++
            if (currentSurfacePkg == pkg) {
                currentSurfaceKey = null
                currentSurfacePkg = null
                clearSurfaceEvidenceForPackage(pkg)
            }
            return
        }

        val eventType = event?.eventType ?: 0
        val isTransitionEvent =
            eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED

        if (event != null && shouldSkipLowSignalInApp(pkg, event, now)) {
            return
        }

        val root = currentRoot(event) ?: run {
            perf.rootMisses++
            return
        }
        perf.inAppScans++

        val globalLimitMin = inAppGlobalLimitMin()
        val globalReached = globalLimitMin > 0 && inAppTotalUsageMsToday() >= globalLimitMin.toLong() * 60_000L

        fun timedBlockMsg(toggleOn: Boolean, surfaceKey: String, surfaceLabel: String): Pair<String, String>? {
            if (!toggleOn) return null

            // Per-surface rule: -1 always block, 0 no specific rule, >0 minutes/day.
            val rule = surfaceRuleValue(surfaceKey)
            if (rule == -1) {
                return getString(R.string.blocking_surface_blocked_title, surfaceLabel) to surfaceUsageLine(surfaceKey, 0)
            }

            // Global in-app limit is a hard cap across all timed sections.
            if (globalReached) {
                return getString(R.string.blocking_in_app_limit_reached_title) to inAppUsageLine(globalLimitMin)
            }

            if (rule > 0) {
                val used = SurfaceUsageStore.getUsageMsToday(this, surfaceKey)
                val reached = used >= rule.toLong() * 60_000L
                if (reached) {
                    return getString(R.string.blocking_surface_limit_reached_title, surfaceLabel) to surfaceUsageLine(surfaceKey, rule)
                }
                return null
            }

            // No per-surface rule.
            // If a global limit exists, allow until the global limit is reached.
            if (globalLimitMin > 0) return null

            // Otherwise, toggled surfaces are blocked immediately.
            return getString(R.string.blocking_surface_blocked_title, surfaceLabel) to surfaceUsageLine(surfaceKey, 0)
        }

        if (pkg == "com.google.android.youtube") {
            // Reliability: some builds only emit transition/content events when entering Shorts.
            val shortsDetected = isYouTubeShortsScreen(root, event)
            val ytQuickEvent =
                eventType == AccessibilityEvent.TYPE_VIEW_CLICKED ||
                    eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED ||
                    eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED ||
                    eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            val isShorts = surfaceConfirmed("yt:shorts", shortsDetected, required = if (ytQuickEvent) 1 else 2)
            if (isShorts) {
                currentSurfaceKey = "yt:shorts"
                currentSurfacePkg = pkg

                val toggle = prefsBoolProfile(false, BlockingToggleKeys.KEY_BLOCK_YT_SHORTS)
                val msg = timedBlockMsg(toggle, "yt:shorts", getString(R.string.in_app_surface_shorts_label))
                if (msg != null) {
                    val appLabel = safeAppLabel(pkg)
                    // Keep Shorts return conservative (one in-app back) to avoid app exits/phone-home jumps.
                    softBlockSurface(pkg, appLabel, msg.first, msg.second, backCount = 1, deferNavigationUntilAcknowledge = true); return
                }
            } else if (currentSurfacePkg == pkg && currentSurfaceKey == "yt:shorts") {
                currentSurfaceKey = null
                currentSurfacePkg = null
            }

            if (prefsBoolProfile(false, BlockingToggleKeys.KEY_BLOCK_YT_SEARCH) && isYouTubeSearchScreen(root, event)) {
                softBlockSurface(pkg, safeAppLabel(pkg), getString(R.string.blocking_search_blocked_title), getString(R.string.blocking_youtube_search_blocked_message), backCount = 1, deferNavigationUntilAcknowledge = true); return
            }
            if (prefsBoolProfile(false, BlockingToggleKeys.KEY_BLOCK_YT_COMMENTS) && isYouTubeCommentsVisible(root)) {
                softBlockSurface(pkg, safeAppLabel(pkg), getString(R.string.blocking_comments_blocked_title), getString(R.string.blocking_youtube_comments_blocked_message), backCount = 1, deferNavigationUntilAcknowledge = true); return
            }
            if (prefsBoolProfile(false, BlockingToggleKeys.KEY_BLOCK_YT_PIP)) {
                val cls = event?.className?.toString().orEmpty()
                if (cls.contains("PictureInPicture", ignoreCase = true)) {
                    softBlockSurface(pkg, safeAppLabel(pkg), getString(R.string.blocking_pip_blocked_title), getString(R.string.blocking_youtube_pip_blocked_message), backCount = 1, deferNavigationUntilAcknowledge = true); return
                }
            }
        }

        if (pkg == "com.instagram.android") {
            val stateById = instagramState(root, event)

            // Extra guards after a previous surface block and during transition events.
            val reelsGuardUntil = surfaceBlockGuardUntil["$pkg|ig:reels"] ?: 0L
            val exploreGuardUntil = surfaceBlockGuardUntil["$pkg|ig:explore"] ?: 0L
            val enteredAt = appEnteredAtByPkg[pkg] ?: 0L
            val igSettled = enteredAt == 0L || (now - enteredAt) >= 350L
            val requiresSettle = isTransitionEvent
            val reelsDetectAllowed = now >= reelsGuardUntil && (!requiresSettle || igSettled)
            val exploreDetectAllowed = now >= exploreGuardUntil && (!requiresSettle || igSettled)

            val homeSelectedNow = hasSelectedLabel(root, listOf("home", "startseite"))
            val reelsTabSelectedNow = hasSelectedLabel(root, listOf("reels"))
            val exploreTabSelectedNow = hasSelectedLabel(root, listOf("search", "suche", "discover", "entdecken", "explore"))
            val searchScreenNow = isInstagramSearchScreen(root, event)

            val blockIgReelsEnabled = prefsBoolProfile(false, BlockingToggleKeys.KEY_BLOCK_IG_REELS)
            val blockIgExploreEnabled = prefsBoolProfile(false, BlockingToggleKeys.KEY_BLOCK_IG_EXPLORE)
            val blockIgStoriesEnabled = prefsBoolProfile(false, BlockingToggleKeys.KEY_BLOCK_IG_STORIES)
            val blockIgCommentsEnabled = prefsBoolProfile(false, BlockingToggleKeys.KEY_BLOCK_IG_COMMENTS)

            val storiesViewerNow = isInstagramStoriesViewer(root, event)
            val commentsVisibleNow = isInstagramCommentsVisible(root)
            val feedCommentsContext = commentsVisibleNow && homeSelectedNow && !reelsTabSelectedNow && !exploreTabSelectedNow

            // Prioritize explicit surface contexts so Explore/Reels do not falsely trigger
            // while user is in Stories or feed comments.
            if (storiesViewerNow) {
                clearSurfaceEvidence("ig:reels", "ig:explore", "ig:explore_search")
                if (currentSurfacePkg == pkg && (currentSurfaceKey == "ig:reels" || currentSurfaceKey == "ig:explore")) {
                    currentSurfaceKey = null
                    currentSurfacePkg = null
                }

                currentSurfaceKey = "ig:stories"
                currentSurfacePkg = pkg

                val storiesMsg = timedBlockMsg(blockIgStoriesEnabled, "ig:stories", getString(R.string.in_app_surface_stories_label))
                if (storiesMsg != null) {
                    val appLabel = safeAppLabel(pkg)
                    softBlockSurface(pkg, appLabel, storiesMsg.first, storiesMsg.second, backCount = 1); return
                }
                return
            }

            val messagesContextNow = isInstagramMessagesScreen(root, event)
            val profileContextNow = isInstagramProfileScreen(root, event)
            if (messagesContextNow || profileContextNow) {
                clearSurfaceEvidence("ig:reels", "ig:explore", "ig:explore_search")
                if (currentSurfacePkg == pkg && (currentSurfaceKey == "ig:reels" || currentSurfaceKey == "ig:explore")) {
                    currentSurfaceKey = null
                    currentSurfacePkg = null
                }

                // Keep dedicated comment blocking behavior independent from Reels/Explore.
                if (blockIgCommentsEnabled && commentsVisibleNow) {
                    val appLabel = safeAppLabel(pkg)
                    softBlockSurface(pkg, appLabel, getString(R.string.blocking_comments_blocked_title), getString(R.string.blocking_instagram_comments_blocked_message), backCount = 1); return
                }
                return
            }

            if (feedCommentsContext) {
                clearSurfaceEvidence("ig:reels", "ig:explore", "ig:explore_search")
                if (currentSurfacePkg == pkg && (currentSurfaceKey == "ig:reels" || currentSurfaceKey == "ig:explore")) {
                    currentSurfaceKey = null
                    currentSurfacePkg = null
                }

                if (blockIgCommentsEnabled) {
                    val appLabel = safeAppLabel(pkg)
                    softBlockSurface(pkg, appLabel, getString(R.string.blocking_comments_blocked_title), getString(R.string.blocking_instagram_comments_blocked_message), backCount = 1); return
                }
                return
            }

            val searchInteractiveEvent = when (eventType) {
                AccessibilityEvent.TYPE_VIEW_CLICKED,
                AccessibilityEvent.TYPE_VIEW_FOCUSED,
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> true
                else -> false
            }
            val homePassiveEvent =
                homeSelectedNow &&
                    !exploreTabSelectedNow &&
                    (eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED ||
                        eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)

            // If home appears selected (and reels tab is not), treat reels detections from scroll/content events
            // as suspicious to avoid delayed false blocks while user just scrolls the feed.
            // Keep this intentionally strict to avoid false Reels hits while scrolling Home feed.
            val reelsEventStrongCue = eventTextMatches(event, listOf("watch more reels", "send reel"))
            val suspiciousHomeReels =
                stateById == "reels" &&
                    homeSelectedNow &&
                    !reelsTabSelectedNow &&
                    (!reelsEventStrongCue || homePassiveEvent) &&
                    (eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED ||
                        eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)

            val exploreEventStrongCue = eventTextMatches(event, listOf("search", "suche", "explore", "discover", "entdecken"))
            val suspiciousHomeExplore =
                stateById == "explore" &&
                    homeSelectedNow &&
                    !exploreTabSelectedNow &&
                    (homePassiveEvent || (!searchInteractiveEvent && !searchScreenNow && !exploreEventStrongCue))

            if (suspiciousHomeReels) {
                clearSurfaceEvidence("ig:reels")
            }
            if (suspiciousHomeExplore || homePassiveEvent) {
                clearSurfaceEvidence("ig:explore", "ig:explore_search")
            }

            if (stateById == "home" || (stateById == null && homeSelectedNow && !reelsTabSelectedNow && !exploreTabSelectedNow)) {
                clearSurfaceEvidence("ig:reels", "ig:explore", "ig:explore_search")
                if (currentSurfacePkg == pkg && (currentSurfaceKey == "ig:reels" || currentSurfaceKey == "ig:explore")) {
                    currentSurfaceKey = null
                    currentSurfacePkg = null
                }
            }

            val reelsState = if (suspiciousHomeReels) null else stateById
            val exploreState = if (suspiciousHomeExplore || homePassiveEvent) null else stateById

            // Reels confirmation tuned for reliability while still guarding home-feed scroll noise.
            val reelsRequired = when (eventType) {
                AccessibilityEvent.TYPE_VIEW_CLICKED,
                AccessibilityEvent.TYPE_VIEW_FOCUSED -> 1
                AccessibilityEvent.TYPE_VIEW_SCROLLED,
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> 2
                else -> 2
            }
            val homeOnlyContext =
                homeSelectedNow &&
                    !reelsTabSelectedNow &&
                    !exploreTabSelectedNow &&
                    !searchScreenNow &&
                    !storiesViewerNow &&
                    !commentsVisibleNow
            val allowReelsDetect = reelsDetectAllowed && !(homeOnlyContext && !reelsEventStrongCue)
            val reelsHit = surfaceConfirmed("ig:reels", allowReelsDetect && reelsState == "reels", required = reelsRequired)
            if (reelsHit) {
                currentSurfaceKey = "ig:reels"
                currentSurfacePkg = pkg

                val msg = timedBlockMsg(blockIgReelsEnabled, "ig:reels", getString(R.string.in_app_surface_reels_label))
                if (msg != null) {
                    val appLabel = safeAppLabel(pkg)
                    // Two backs are safer for Instagram surface exits (viewer -> tab -> feed).
                    softBlockSurface(pkg, appLabel, msg.first, msg.second, backCount = 2, deferNavigationUntilAcknowledge = true); return
                }
            }

            // Explore can be represented differently across Instagram builds.
            // Do not rely only on instagramState()=="explore"; explicit tab/search context must count too.
            val explicitExploreContext =
                exploreTabSelectedNow ||
                    searchScreenNow ||
                    (searchInteractiveEvent && exploreEventStrongCue)

            val allowExploreDetect =
                exploreDetectAllowed &&
                    (
                        explicitExploreContext ||
                            (!homeSelectedNow && exploreState == "explore") ||
                            (searchInteractiveEvent && exploreState == "explore")
                    )

            val exploreDetected =
                !suspiciousHomeExplore &&
                    !homePassiveEvent &&
                    (exploreState == "explore" || explicitExploreContext)

            val exploreRequired = when {
                explicitExploreContext -> 1
                eventType == AccessibilityEvent.TYPE_VIEW_CLICKED ||
                    eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED ||
                    eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> 1
                eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED ||
                    eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> if (homeSelectedNow && !exploreTabSelectedNow && !searchScreenNow) 3 else 2
                else -> 2
            }
            val exploreHit = surfaceConfirmed("ig:explore", allowExploreDetect && exploreDetected, required = exploreRequired)
            if (exploreHit) {
                currentSurfaceKey = "ig:explore"
                currentSurfacePkg = pkg

                val msg = timedBlockMsg(blockIgExploreEnabled, "ig:explore", getString(R.string.in_app_surface_explore_label))
                if (msg != null) {
                    val appLabel = safeAppLabel(pkg)
                    // Explore often needs one extra back to reliably return to feed/home.
                    softBlockSurface(pkg, appLabel, msg.first, msg.second, backCount = 2, deferNavigationUntilAcknowledge = true); return
                }
            }

            val allowSearchDetect =
                !reelsTabSelectedNow &&
                    (
                        exploreTabSelectedNow ||
                            (!homeSelectedNow && searchScreenNow) ||
                            (searchInteractiveEvent && searchScreenNow)
                    )
            val searchHit = surfaceConfirmed(
                "ig:explore_search",
                blockIgExploreEnabled && searchScreenNow && allowSearchDetect && stateById != "reels",
                required = if (allowSearchDetect) 1 else 2
            )
            if (searchHit) {
                currentSurfaceKey = "ig:explore"
                currentSurfacePkg = pkg
                val appLabel = safeAppLabel(pkg)
                softBlockSurface(
                    pkg,
                    appLabel,
                    getString(R.string.blocking_explore_blocked_title),
                    getString(R.string.blocking_instagram_explore_search_blocked_message),
                    backCount = 2,
                    deferNavigationUntilAcknowledge = true
                ); return
            }

            val storiesHit = surfaceConfirmed("ig:stories", isInstagramStoriesViewer(root, event))
            if (storiesHit) {
                currentSurfaceKey = "ig:stories"
                currentSurfacePkg = pkg

                val msg = timedBlockMsg(blockIgStoriesEnabled, "ig:stories", getString(R.string.in_app_surface_stories_label))
                if (msg != null) {
                    val appLabel = safeAppLabel(pkg)
                    softBlockSurface(pkg, appLabel, msg.first, msg.second, backCount = 1); return
                }
            }

            if (!reelsHit && !exploreHit && !storiesHit && currentSurfacePkg == pkg) {
                currentSurfaceKey = null
                currentSurfacePkg = null
            }

            if (blockIgCommentsEnabled && isInstagramCommentsVisible(root)) {
                softBlockSurface(pkg, safeAppLabel(pkg), getString(R.string.blocking_comments_blocked_title), getString(R.string.blocking_instagram_comments_blocked_message), backCount = 1); return
            }
        }
    }


    private fun clearSurfaceEvidence(vararg keys: String) {
        for (key in keys) {
            surfaceEvidenceCount.remove(key)
            surfaceEvidenceAt.remove(key)
            if (currentSurfaceKey == key) {
                currentSurfaceKey = null
                currentSurfacePkg = null
            }
        }
    }

    private fun clearSurfaceEvidenceForPackage(pkg: String) {
        when (pkg) {
            "com.google.android.youtube" -> clearSurfaceEvidence("yt:shorts")
            "com.instagram.android" -> clearSurfaceEvidence("ig:reels", "ig:explore", "ig:explore_search", "ig:stories")
        }
    }

    private fun surfaceConfirmed(key: String, detected: Boolean, required: Int = 2): Boolean {
        val now = System.currentTimeMillis()
        if (!detected) {
            clearSurfaceEvidence(key)
            return false
        }
        val lastAt = surfaceEvidenceAt[key] ?: 0L
        val count = if (now - lastAt <= SURFACE_CONFIRM_MS) (surfaceEvidenceCount[key] ?: 0) + 1 else 1
        surfaceEvidenceAt[key] = now
        surfaceEvidenceCount[key] = count
        return count >= required.coerceAtLeast(1)
    }

    private fun nodeTextOrDesc(node: AccessibilityNodeInfo?): String {
        if (node == null) return ""
        val t = node.text?.toString().orEmpty()
        val cd = node.contentDescription?.toString().orEmpty()
        return listOf(t, cd).filter { it.isNotBlank() }.joinToString(" ").lowercase(Locale.getDefault())
    }

    private fun isInstagramSearchScreen(root: AccessibilityNodeInfo, event: AccessibilityEvent? = null): Boolean {
        return hasSelectedLabel(root, listOf("search", "suche", "discover", "entdecken", "explore")) ||
            nodeTextMatches(root, listOf("search", "suche", "discover", "entdecken", "explore")) ||
            eventTextMatches(event, listOf("search", "suche", "discover", "entdecken", "explore"))
    }

    private fun isInstagramStoriesViewer(root: AccessibilityNodeInfo, event: AccessibilityEvent? = null): Boolean {
        return nodeTextMatches(root, listOf("send message", "nachricht senden", "reply", "antworten", "story")) ||
            eventTextMatches(event, listOf("story", "stories"))
    }

    private fun isInstagramCommentsVisible(root: AccessibilityNodeInfo): Boolean {
        return nodeTextMatches(root, listOf("comments", "kommentare", "add a comment", "kommentar hinzufügen", "view all comments"))
    }

    private fun isInstagramMessagesScreen(root: AccessibilityNodeInfo, event: AccessibilityEvent? = null): Boolean {
        return nodeTextMatches(root, listOf("messages", "nachrichten", "chats", "threads")) ||
            eventTextMatches(event, listOf("messages", "nachrichten"))
    }

    private fun isInstagramProfileScreen(root: AccessibilityNodeInfo, event: AccessibilityEvent? = null): Boolean {
        return hasSelectedLabel(root, listOf("profile")) ||
            nodeTextMatches(root, listOf("edit profile", "profil bearbeiten", "posts", "beiträge", "followers", "follower", "following", "abonniert")) ||
            eventTextMatches(event, listOf("profile", "profil"))
    }

    private fun instagramState(root: AccessibilityNodeInfo, event: AccessibilityEvent? = null): String? {
        return when {
            isInstagramStoriesViewer(root, event) -> "stories"
            hasSelectedLabel(root, listOf("reels")) || eventTextMatches(event, listOf("reels", "watch more reels", "send reel")) -> "reels"
            isInstagramSearchScreen(root, event) -> "explore"
            hasSelectedLabel(root, listOf("home", "startseite")) -> "home"
            else -> null
        }
    }

    private fun isYouTubeShortsScreen(root: AccessibilityNodeInfo, event: AccessibilityEvent? = null): Boolean {
        return nodeTextMatches(root, listOf("shorts", "short")) ||
            eventTextMatches(event, listOf("shorts", "short"))
    }

    private fun isYouTubeSearchScreen(root: AccessibilityNodeInfo, event: AccessibilityEvent? = null): Boolean {
        return nodeTextMatches(root, listOf("search youtube", "youtube durchsuchen", "search")) ||
            eventTextMatches(event, listOf("search", "youtube"))
    }

    private fun isYouTubeCommentsVisible(root: AccessibilityNodeInfo): Boolean {
        return nodeTextMatches(root, listOf("comments", "kommentare", "add a comment", "sort comments"))
    }

    private fun bounceHomeAndKill(pkg: String) {
        // Accessibility can do HOME globally (more reliable than launching CATEGORY_HOME intents)
        runCatching { performGlobalAction(GLOBAL_ACTION_HOME) }
        runCatching {
            val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
            am.killBackgroundProcesses(pkg)
        }
    }

    private fun isHighRisk(pkg: String): Boolean {
        // Settings/Installers/system dialogs that can weaken lock-mode.
        return pkg == "com.android.settings" ||
            pkg == "com.google.android.packageinstaller" ||
            pkg == "com.android.packageinstaller" ||
            pkg.startsWith("com.android.permissioncontroller")
    }

    private fun isManagedPackage(pkg: String, blocked: Set<String>): Boolean {
        // Profiles store exact package names. Using startsWith() can cause accidental matches
        // (especially with OEM/system containers), resulting in random false blocks.
        //
        // Optional: allow prefix entries when they end with ".*".
        return blocked.any { raw ->
            val e = raw.trim()
            when {
                e.isBlank() -> false
                e.endsWith(".*") -> pkg.startsWith(e.removeSuffix(".*"))
                else -> pkg == e
            }
        }
    }
}
