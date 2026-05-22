/*
 * Switchly
 * Copyright (C) 2025-2026 Saltyy
 * Copyright (C) 2026 Switchly Contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package at.saltyy.switchly.blocking

import at.saltyy.switchly.BuildConfig
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.preference.PreferenceManager
import at.saltyy.switchly.R
import at.saltyy.switchly.data.prefs.AppLogStore
import at.saltyy.switchly.data.prefs.AttemptLimitStore
import at.saltyy.switchly.data.prefs.AutomationModeStore
import at.saltyy.switchly.data.prefs.BlockAttemptStore
import at.saltyy.switchly.data.prefs.BlockCountStore
import at.saltyy.switchly.data.prefs.BlockedTimeStore
import at.saltyy.switchly.data.prefs.BlockingToggleKeys
import at.saltyy.switchly.data.prefs.DomainBlockStore
import at.saltyy.switchly.data.prefs.DomainLimitStore
import at.saltyy.switchly.data.prefs.EmergencyBypassStore
import at.saltyy.switchly.data.prefs.InAppLimitStore
import at.saltyy.switchly.data.prefs.LimitReachedStore
import at.saltyy.switchly.data.prefs.OpenCountStore
import at.saltyy.switchly.data.prefs.ProfileStore
import at.saltyy.switchly.data.prefs.ProfileUsageStore
import at.saltyy.switchly.data.prefs.ScheduleStore
import at.saltyy.switchly.data.prefs.SurfaceLimitStore
import at.saltyy.switchly.data.prefs.SurfaceUsageStore
import at.saltyy.switchly.data.prefs.SwitchModeStore
import at.saltyy.switchly.data.prefs.TempAllowStore
import at.saltyy.switchly.data.prefs.UsageLimitStore
import at.saltyy.switchly.data.prefs.UsageStore
import at.saltyy.switchly.data.prefs.WebUsageStore
import at.saltyy.switchly.feature.blocker.BlockerActivity
import at.saltyy.switchly.feature.usage.UsageStatsRepo
import at.saltyy.switchly.platform.receiver.schedule.ScheduleReceiver
import at.saltyy.switchly.util.AppBlockSafety
import at.saltyy.switchly.util.AppUsageToday
import java.util.ArrayDeque
import java.util.Locale
import kotlin.math.abs

/**
 * Main (stable) blocking runtime.
 * Compared to AppWatcherService this does NOT rely on Usage Access polling or a foreground service.
 * The system keeps Accessibility services alive much more reliably across OEMs.
 */
class SwitchlyAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var usageWorkerThread: HandlerThread? = null
    private var usageWorker: Handler? = null
    private val usageEventsForegroundResolver by lazy { UsageEventsForegroundResolver(this) }
    private val blockLaunchController by lazy { BlockLaunchController(this, handler) }
    @Volatile private var topRefreshInFlight: Boolean = false
    @Volatile private var usageOpenScanInFlight: Boolean = false

    private lateinit var pm: PowerManager
    private var km: KeyguardManager? = null

    // Foreground tracking for usage limits
    @Volatile private var currentTopPkg: String? = null
    @Volatile private var lastTickAt: Long = 0L

    // Occasionally re-verify the true foreground app via UsageEvents.
    // Some OEMs/apps don't emit reliable WINDOW_STATE_CHANGED transitions.
    private var lastTopRefreshAt: Long = 0L

    // Attempt-based limits: scan UsageEvents for ACTIVITY_RESUMED to count opens reliably.
    private var lastUsageOpenScanAt: Long = 0L
    private var cachedHasAttemptLimitsAt: Long = 0L
    private var cachedHasAttemptLimits: Boolean = false

    // Event noise reduction (especially with TYPE_WINDOW_CONTENT_CHANGED enabled)
    private var lastEventPkg: String? = null
    private var lastEventAt: Long = 0L
    private var lastEventType: Int = 0
    private var lastTransitionAt: Long = 0L

    // Browser state cache for website blocking + per-domain website stats
    private val browserWebsiteState = BrowserWebsiteState()
    private val lastDiagnosticLogAtByKey = HashMap<String, Long>()

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
    private val lastWebsiteFollowUpAt = HashMap<String, Long>()
    private val lastFirefoxShortcutProbeAt = HashMap<String, Long>()
    private val lastOpenCountAt = HashMap<String, Long>()
    private var lastOpenSessionPkg: String? = null
    private var lastOpenSessionAt: Long = 0L
    private val inAppSurfaceEvidence = InAppSurfaceEvidence()
    private val inAppGraceUntilByPkg = HashMap<String, Long>()
    private val surfaceBlockGuardUntil = HashMap<String, Long>()

    private val SURFACE_BLOCK_COOLDOWN_MS = 1_200L
    private val WEBSITE_REDIRECT_FOLLOW_UP_COOLDOWN_MS = 1_800L
    private val FIREFOX_SHORTCUT_PROBE_COOLDOWN_MS = 2_500L
    private var lastGlobalBlockTs: Long = 0L
    private val recentBlockEvents = ArrayDeque<Pair<String, Long>>()
    private val LOOP_BREAKER_WINDOW_MS = 12_000L
    private val LOOP_BREAKER_THRESHOLD = 5
    private val LOOP_BREAKER_SUPPRESS_MS = 8_000L
    private val STRICT_LOCKOUT_RECOVERY_ALLOW_MS = 30_000L
    private val suppressedBlockingUntilByPkg = HashMap<String, Long>()

    private val ATTEMPT_COOLDOWN_MS = 1_200L
    // Count an "open" once per foreground session. 
    // Apps such as Gmail and Instagram can emit multiple ACTIVITY_RESUMED events while the user is still inside the same app, 
    // so a short cooldown alone would incorrectly count one real open many times.
    private val OPEN_COUNT_COOLDOWN_MS = 800L
    private val BLOCK_SHOWN_COOLDOWN_MS = 800L
    private val HOME_BOUNCE_COOLDOWN_MS = 900L
    private var lastHomeBounceAt: Long = 0L
    private val SURFACE_CONFIRM_MS = 850L
    private val SURFACE_HINT_TTL_MS = 2_200L
    private val INAPP_POST_BLOCK_GRACE_MS = 1_800L
    private val INSTA_REELS_REENTRY_GUARD_MS = 1_800L
    private val INSTA_EXPLORE_REENTRY_GUARD_MS = 2_200L
    private val INAPP_ENTRY_SETTLE_MS = 1_400L
    private val YT_SHORTS_REENTRY_GUARD_MS = 2_200L

    private val MAX_NODE_SCAN_COUNT = 120
    private val MAX_NODE_SCAN_DEPTH = 12

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
        if (BuildConfig.DEBUG) runCatching { Log.d("SwitchlyPerf", summary) }

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
        if (AppBlockSafety.isHardExcluded(this, pkg)) return 0
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
        if (AppBlockSafety.isHardExcluded(this, pkg)) return 0
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
            pkg == "com.snapchat.android" &&
                (type == AccessibilityEvent.TYPE_VIEW_CLICKED || type == AccessibilityEvent.TYPE_VIEW_SELECTED) -> 0L
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
            pkg == "com.snapchat.android" &&
                (eventType == AccessibilityEvent.TYPE_VIEW_CLICKED || eventType == AccessibilityEvent.TYPE_VIEW_SELECTED) -> 0L
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
                    prefsBoolProfile(false, BlockingToggleKeys.KEY_BLOCK_IG_SEARCH) ||
                    prefsBoolProfile(false, BlockingToggleKeys.KEY_BLOCK_IG_STORIES) ||
                    prefsBoolProfile(false, BlockingToggleKeys.KEY_BLOCK_IG_COMMENTS)

            pkg == "com.twitter.android" ->
                prefsBoolProfile(false, BlockingToggleKeys.KEY_BLOCK_X_HOME) ||
                    prefsBoolProfile(false, BlockingToggleKeys.KEY_BLOCK_X_SEARCH) ||
                    prefsBoolProfile(false, BlockingToggleKeys.KEY_BLOCK_X_GROK) ||
                    prefsBoolProfile(false, BlockingToggleKeys.KEY_BLOCK_X_NOTIFICATIONS)

            pkg == "com.snapchat.android" ->
                prefsBoolProfile(false, BlockingToggleKeys.KEY_BLOCK_SNAP_MAP) ||
                    prefsBoolProfile(false, BlockingToggleKeys.KEY_BLOCK_SNAP_STORIES) ||
                    prefsBoolProfile(false, BlockingToggleKeys.KEY_BLOCK_SNAP_SPOTLIGHT) ||
                    prefsBoolProfile(false, BlockingToggleKeys.KEY_BLOCK_SNAP_FOLLOWING)

            else -> false
        }
    }

    private fun lowSignalNeedlesForPkg(pkg: String): List<String> {
        return when {
            pkg == "com.google.android.youtube" -> listOf("shorts", "search", "comment", "picture")
            pkg == "com.instagram.android" -> listOf("reels", "explore", "search", "story", "comment")
            pkg == "com.twitter.android" -> listOf("for you", "following", "search", "explore", "grok", "notification", "notifications", "home")
            pkg == "com.snapchat.android" -> listOf("map", "stories", "spotlight", "following", "chat", "camera")
            else -> emptyList()
        }
    }

    private fun shouldSkipLowSignalInApp(pkg: String, event: AccessibilityEvent, now: Long): Boolean {
        // Reliability-first: these apps often emit sparse/noisy class/text signals on surface changes.
        // Skipping probes here can miss legitimate blocks (YouTube Shorts).
        if (pkg == "com.google.android.youtube" ||
            pkg == "com.twitter.android" ||
            pkg == "com.snapchat.android") {
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
        usageWorkerThread?.quitSafely()
        usageWorkerThread = HandlerThread("switchly-usage-worker").apply { start() }
        usageWorker = Handler(usageWorkerThread!!.looper)
        topRefreshInFlight = false
        usageOpenScanInFlight = false
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, 1_000L)
    }

    override fun onInterrupt() {
        // no-op
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        usageWorkerThread?.quitSafely()
        usageWorkerThread = null
        usageWorker = null
        topRefreshInFlight = false
        usageOpenScanInFlight = false
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

        if (maybeBlockUninstallFrictionSurface(pkg, event)) return

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
                blockLaunchController.performBackSequence(effectiveBackCount, initialDelayMs = 0L, stepMs = 130L)
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
                browserWebsiteState.clearCurrent()
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
                pkg == "com.instagram.android" ||
                pkg == "com.twitter.android" ||
                pkg == "com.snapchat.android"

        val specialEvent =
            type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
                type == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED ||
                type == AccessibilityEvent.TYPE_VIEW_SCROLLED ||
                type == AccessibilityEvent.TYPE_VIEW_CLICKED ||
                type == AccessibilityEvent.TYPE_VIEW_SELECTED ||
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
        if (pkg == packageName) return
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
        val profile = getCurrentProfileCached(nowForCache)

        // Track app usage for Today statistics even when no daily limit is configured.
        // Limit enforcement still happens below and continues to depend on the active profile + limit.
        UsageStore.addUsageMsToday(this, pkg, delta)

        if (!profile.isNullOrBlank()) {
            // App limits are profile-specific. 
            // Keep a separate per-profile counter so switching from a hard-blocked profile into a limited profile does not immediately inherit stale/over-counted usage from a different profile.
            ProfileUsageStore.addUsageMsToday(this, profile, pkg, delta)

            // "Blocked time" should reflect how long apps are configured as blocked while Switchly is enabled (not how long the blocker UI is shown). Track it here, once per tick.
            val blockedSet = getBlockedAppsCached(profile, nowForCache)
            if (blockedSet.isNotEmpty()) {
                for (blockedPkg in blockedSet) {
                    BlockedTimeStore.addBlockedMsToday(this, blockedPkg, delta)
                }
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
            if (browserWebsiteState.needsDomainProbe(pkg) &&
                shouldRunDeepProbe(pkg, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, isTransition = false, now = now)
            ) {
                maybeBlockWebsite(pkg, null)
            }

            val d = browserWebsiteState.currentTrackedDomain(pkg, now, isFirefox = isFirefoxFamily(pkg))
            if (!d.isNullOrBlank()) {
                WebUsageStore.addUsageMsToday(this, d, delta)
            }
        }

        val safeProfile = profile ?: return

        // Usage limits should work even if the app isn't in the "blocked apps" list.
        // (Users can set a daily limit without hard-blocking the app.)
        val limitMin = getUsageLimitCached(safeProfile, pkg, nowForCache)
        if (limitMin <= 0) return // hard block -> handled by event driven blocker

        // Enforce app limits using usage accumulated while the current profile is active.
        // The overall usage dashboard may show a different value because it is intentionally not profile-scoped.
        val usedMs = getProfileLimitUsageMsToday(safeProfile, pkg)
        val limitMs = limitMin * 60_000L
        if (usedMs >= limitMs) {
            appendBlockingLog(
                category = "app_limit_reached",
                key = "app-limit-reached|$safeProfile|$pkg",
                message = "profile=$safeProfile pkg=$pkg limitMin=$limitMin usageMs=$usedMs limitMs=$limitMs",
                throttleMs = 2_000L
            )
            LimitReachedStore.markReachedToday(this, safeProfile, pkg)
            maybeBlockNow(pkg, force = true)
        }
    }

    private fun getSystemUsageMsToday(pkg: String, now: Long): Long {
        return try {
            UsageStatsRepo.getTodayMsForPackage(this, pkg, now)
        } catch (_: Throwable) {
            0L
        }
    }

    /**
     * Returns the "today" app usage used for user-facing app timers.
     *
     * We prefer the system-reported value when Usage Access is granted so Switchly stays aligned
     * with Android's own screen-time numbers. If Usage Access is missing, we fall back to
     * Switchly's internal counter.
     */
    private fun getEffectiveUsageMsToday(pkg: String, now: Long): Long {
        return AppUsageToday.getUsageMsToday(this, pkg, now)
    }

    /**
     * Usage used for enforcing profile-specific app limits.
     * The public usage dashboard can show overall daily usage, but limits configured inside a profile should be evaluated against usage accumulated while that profile is active.
     * This avoids false early blocks after switching from a hard-blocking profile to a limited profile.
     */
    private fun getProfileLimitUsageMsToday(profile: String, pkg: String): Long {
        return ProfileUsageStore.getUsageMsToday(this, profile, pkg)
    }

    /**
     * Refreshes [currentTopPkg] using UsageEvents at a low cadence.
     * If Usage Access isn't granted, this is a no-op.
     * The UsageStats binder can stall unpredictably on some devices, so the query runs on a  worker thread and only the lightweight state update is posted back to the main thread.
     */
    private fun refreshTopPackageIfNeeded(now: Long) {
        // Keep this fairly tight: some devices miss accessibility transitions when an app is
        // resumed from Recents/Overview, so UsageEvents becomes the fallback that re-enforces
        // hard blocks. Running it roughly once per tick keeps reopen loopholes short.
        if (now - lastTopRefreshAt < 1_000L) return
        if (topRefreshInFlight) return

        lastTopRefreshAt = now
        topRefreshInFlight = true

        val start = (now - 10_000L).coerceAtLeast(0L)
        usageWorker?.post {
            val top = runCatching { usageEventsForegroundResolver.resolveTopPackage(start, now) }.getOrNull()
            topRefreshInFlight = false
            if (top.isNullOrBlank()) return@post

            handler.post {
                applyResolvedTopPackage(top, now)
            }
        } ?: run {
            topRefreshInFlight = false
        }
    }

    private fun applyResolvedTopPackage(top: String, now: Long) {
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
            browserWebsiteState.clearCurrent()
        }

        // If the foreground package was corrected via UsageEvents, also count an "open" here.
        // This covers devices that miss accessibility transition events when switching via Recents.
        maybeCountOpenAndEnforceAttemptLimit(top, now)

        // And enforce immediately in the same correction pass.
        // Without this, hard-blocked apps reopened from Recents can slip through until the next accessibility event or periodic tick notices the corrected top package.
        maybeBlockNow(top, force = true)
    }

    /**
     * Scans UsageEvents for ACTIVITY_RESUMED to count app opens reliably.
     *
     * Some OEMs (and some navigation flows) do not emit consistent accessibility transition events when leaving/returning to apps (especially via Recents). 
     * UsageEvents is the most reliable cross-device signal for "app became foreground".
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
        if (usageOpenScanInFlight) return

        val from = (lastUsageOpenScanAt - 400L).coerceAtLeast((now - 30_000L).coerceAtLeast(0L))
        val to = now
        lastUsageOpenScanAt = now
        usageOpenScanInFlight = true

        usageWorker?.post {
            val events = runCatching { usageEventsForegroundResolver.queryForegroundEvents(from, to) }.getOrDefault(emptyList())
            usageOpenScanInFlight = false
            if (events.isEmpty()) return@post

            handler.post {
                events.forEach { (pkg, ts) ->
                    maybeCountOpenAndEnforceAttemptLimit(pkg, ts)
                }
            }
        } ?: run {
            usageOpenScanInFlight = false
        }
    }

    /**
     * Attempt-based limits: block an app after it has been opened N times today.
     * We count an "open" when the app becomes the foreground package.
     * To avoid noisy OEM foreground flapping, we apply a short per-package cooldown.
     */
    private fun maybeCountOpenAndEnforceAttemptLimit(pkg: String, now: Long) {
        if (pkg.isBlank() || pkg == packageName) return
        if (AppBlockSafety.isHardExcluded(this, pkg)) return

        val previousSessionPkg = lastOpenSessionPkg
        val sameForegroundSession = previousSessionPkg == pkg

        // Track every observed foreground package, even if that package has no attempt limit.
        // This lets the next limited app count as a new open after the user really switched away.
        lastOpenSessionPkg = pkg
        lastOpenSessionAt = now

        if (sameForegroundSession) {
            // Still inside the same app session. 
            // Do not count repeated ACTIVITY_RESUMED events from internal activity changes, tab switches, notification updates, or OEM foreground flaps.
            return
        }

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
        // If the user sets a time/attempt limit for an app that is in the blocked list, the limit should take precedence (otherwise it looks "broken").
        val hardBlocked = isManagedPackage(pkg, blocked) && timeLimitMin <= 0 && attemptLimit <= 0
        val timeBlocked = timeLimitMin > 0 && LimitReachedStore.isReachedToday(this, profile, pkg)
        if (hardBlocked || timeBlocked) return

        val last = lastOpenCountAt[pkg] ?: 0L
        if (now - last < OPEN_COUNT_COOLDOWN_MS) return
        lastOpenCountAt[pkg] = now

        val opensToday = OpenCountStore.incrementToday(this, profile, pkg)
        appendBlockingLog(
            category = "open_count",
            key = "open-count|$profile|$pkg",
            message = "profile=$profile pkg=$pkg opens=$opensToday limit=$attemptLimit previousSession=${previousSessionPkg ?: "none"}",
            throttleMs = 1_000L
        )

        if (opensToday > attemptLimit) {
            // Block immediately on the first open past the cap.
            maybeBlockNow(pkg, force = true)
        }
    }

    private fun maybeBlockNow(pkg: String, event: AccessibilityEvent? = null, force: Boolean = false) {
        perf.maybeBlockCalls++
        if (AppBlockSafety.isHardExcluded(this, pkg)) return
        val now = System.currentTimeMillis()

        // Strict-mode lockout fallback: when Settings or another strict recovery surface is temporarily allowed, do not let loop-protection suppression immediately bounce it back home again. 
        // This keeps the short recovery window real instead of only showing a toast while the activity is still closed.
        if (AppBlockSafety.requiresStrictModeForBlocking(this, pkg) && TempAllowStore.isAllowed(this, pkg)) {
            appendBlockingLog(
                category = "temp_allow",
                key = "temp-allow|$pkg",
                message = "pkg=$pkg reason=strict_lockout_recovery",
                throttleMs = 2_000L
            )
            return
        }

        if (isBlockSuppressed(pkg, now)) {
            enforceSuppressedBlock(pkg, now)
            return
        }

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
        val effectiveUsageMsToday = getProfileLimitUsageMsToday(profile, pkg)
        val decision = resolveAppBlockDecision(
            pkg = pkg,
            blockedPackages = blocked,
            limitMinutes = limitMin,
            attemptLimit = attemptLimit,
            opensExceeded = opensExceeded,
            effectiveUsageMsToday = effectiveUsageMsToday,
            lockActive = lockActive,
            highRisk = isHighRiskBlockTarget(this, pkg),
            force = force
        )

        if (limitMin > 0 || attemptLimit > 0) {
            val limitMs = limitMin * 60_000L
            val hardBlocked = isManagedPackage(pkg, blocked) && limitMin <= 0 && attemptLimit <= 0
            appendBlockingLog(
                category = "app_limit_decision",
                key = "app-limit-decision|$profile|$pkg",
                message = "profile=$profile pkg=$pkg hardBlocked=$hardBlocked limitMin=$limitMin profileUsageMs=$effectiveUsageMsToday globalUsageMs=${getEffectiveUsageMsToday(pkg, System.currentTimeMillis())} limitMs=$limitMs attemptLimit=$attemptLimit opensExceeded=$opensExceeded force=$force shouldBlock=${decision.shouldBlock}",
                throttleMs = 2_000L
            )
        }

        if (!decision.shouldBlock) return
        blockNow(pkg, immediate = decision.immediate)
    }

    private fun isBlockSuppressed(pkg: String, now: Long = System.currentTimeMillis()): Boolean {
        val until = suppressedBlockingUntilByPkg[pkg] ?: return false
        if (until <= now) {
            suppressedBlockingUntilByPkg.remove(pkg)
            return false
        }
        return true
    }

    private fun enforceSuppressedBlock(pkg: String, now: Long = System.currentTimeMillis()) {
        if (!isBlockSuppressed(pkg, now)) return

        appendBlockingLog(
            category = "loop_block",
            key = "loop-block|$pkg",
            message = "pkg=$pkg silent=true",
            throttleMs = 1_500L
        )

        clearSurfaceEvidenceForPackage(pkg)
        inAppGraceUntilByPkg[pkg] = maxOf(inAppGraceUntilByPkg[pkg] ?: 0L, now + 1_500L)
        lastBlockShownAt[pkg] = now
        lastGlobalBlockTs = now

        blockLaunchController.postHome()
        blockLaunchController.postHome(delayMs = 180L)
        blockLaunchController.postKillBackgroundPackage(pkg, delayMs = 120L)
    }

    private fun shouldSuppressForLoop(pkg: String, now: Long = System.currentTimeMillis()): Boolean {
        while (recentBlockEvents.isNotEmpty() && now - recentBlockEvents.first().second > LOOP_BREAKER_WINDOW_MS) {
            recentBlockEvents.removeFirst()
        }
        recentBlockEvents.addLast(pkg to now)
        val count = recentBlockEvents.count { it.first == pkg }
        if (count < LOOP_BREAKER_THRESHOLD) return false

        AppLogStore.append(this, "Blocking", "Loop protection triggered package=$pkg mode=safety suppressMs=$LOOP_BREAKER_SUPPRESS_MS enforcement=home_bounce")
        suppressedBlockingUntilByPkg[pkg] = now + LOOP_BREAKER_SUPPRESS_MS
        clearSurfaceEvidenceForPackage(pkg)
        inAppGraceUntilByPkg[pkg] = now + LOOP_BREAKER_SUPPRESS_MS
        lastBlockShownAt[pkg] = now
        lastGlobalBlockTs = now
        blockLaunchController.postHome()
        blockLaunchController.postHome(delayMs = 250L)
        blockLaunchController.postKillBackgroundPackage(pkg)
        runCatching {
            Log.w("Switchly", "Loop breaker triggered for $pkg in safety mode; using home-bounce enforcement for ${LOOP_BREAKER_SUPPRESS_MS}ms")
        }
        if (AppBlockSafety.requiresStrictModeForBlocking(this, pkg)) {
            TempAllowStore.allow(this, pkg, STRICT_LOCKOUT_RECOVERY_ALLOW_MS)
            handler.post {
                runCatching {
                    Toast.makeText(this, getString(R.string.app_picker_settings_temporarily_allowed), Toast.LENGTH_LONG).show()
                }
            }
        }
        return true
    }

    /**
     * "Soft" block: keep the app open, but force user out of the current surface (e.g., a website tab, Shorts/Reels), then show the blocker UI as feedback.
     * This avoids making the whole browser/app unusable.
     */
    private fun appendBlockingLog(category: String, key: String, message: String, throttleMs: Long = 2_500L) {
        val now = System.currentTimeMillis()
        val last = lastDiagnosticLogAtByKey[key] ?: 0L
        if (now - last < throttleMs) return
        lastDiagnosticLogAtByKey[key] = now
        AppLogStore.append(this, "Blocking", "[$category] $message")
    }

    private fun softBlockSurface(
        pkg: String,
        appLabel: String,
        title: String,
        message: String,
        backCount: Int = 1,
        deferNavigationUntilAcknowledge: Boolean = false,
        returnToPackageOnClose: Boolean = false
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

        appendBlockingLog(
            category = "surface_block",
            key = "surface-block|$pkg|$title",
            message = "pkg=$pkg title=${sanitizeWebsiteSignal(title, 80)} backCount=$backCount defer=$deferNavigationUntilAcknowledge returnToPkg=$returnToPackageOnClose"
        )

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
            blockLaunchController.performBackSequence(backCount, initialDelayMs = 0L, stepMs = 120L)
        } else if (pkg == "com.google.android.youtube" && title.contains(getString(R.string.in_app_surface_shorts_label), ignoreCase = true)) {
            // Reduce visible PiP/miniplayer flashes while the blocker popup is shown: try to leave Shorts in-app before opening the popup (without global BACK).
            runCatching { blockLaunchController.pauseActiveMediaPlayback() }
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

        val showDelay = when {
            deferNavigationUntilAcknowledge -> 0L
            pkg == "com.snapchat.android" -> 320L
            else -> 30L
        }
        val resolvedMessage = buildSurfaceBlockMessage(pkg = pkg, title = title, originalMessage = message)
        handler.postDelayed({
            runCatching {
                BlockerActivity.showDetailed(
                    this,
                    pkg,
                    appLabel,
                    title,
                    resolvedMessage,
                    postAcknowledgeBackCount = if (deferNavigationUntilAcknowledge) backCount else 0,
                    returnToPackageOnClose = returnToPackageOnClose
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
                pkg == "com.instagram.android" ||
                pkg == "com.twitter.android" ||
                pkg == "com.snapchat.android"

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

    private data class UninstallSurfaceSignal(
        val reason: String,
        val backCount: Int = 1
    )

    private fun maybeBlockUninstallFrictionSurface(pkg: String, event: AccessibilityEvent?): Boolean {
        if (!SwitchModeStore.isEnabled(this)) return false
        if (EmergencyBypassStore.isActive(this)) return false
        if (TempAllowStore.isAllowed(this, pkg)) return false
        if (!AutomationModeStore.isUninstallFrictionEnabled(this)) return false
        if (pkg != "com.android.settings" && pkg != "com.android.vending" && pkg != "com.google.android.packageinstaller" && pkg != "com.android.packageinstaller") {
            return false
        }

        val root = currentRoot(event) ?: return false
        val signal = detectSwitchlyUninstallSurface(pkg, root, event) ?: return false
        val appLabel = safeAppLabel(pkg)
        appendBlockingLog(
            category = "uninstall_friction",
            key = "uninstall-friction|$pkg|${signal.reason}",
            message = "pkg=$pkg reason=${signal.reason} event=${eventTypeLabel(event)}",
            throttleMs = 1_200L
        )
        softBlockSurface(
            pkg = pkg,
            appLabel = appLabel,
            title = getString(R.string.blocking_uninstall_friction_title),
            message = getString(R.string.blocking_uninstall_friction_message),
            backCount = signal.backCount,
            deferNavigationUntilAcknowledge = false,
            returnToPackageOnClose = false
        )
        return true
    }

    private fun detectSwitchlyUninstallSurface(
        pkg: String,
        root: AccessibilityNodeInfo,
        event: AccessibilityEvent?
    ): UninstallSurfaceSignal? {
        val lowered = collectNodeTextBlob(root, event)
        val loweredIds = collectNodeIdBlob(root)

        val mentionsSwitchly =
            lowered.contains("switchly") ||
                lowered.contains(packageName.lowercase(Locale.ROOT)) ||
                loweredIds.contains(packageName.lowercase(Locale.ROOT))

        if (!mentionsSwitchly) return null

        val hasUninstall = lowered.contains("uninstall") || lowered.contains("deinstall") || lowered.contains("remove")
        val hasAppInfo = lowered.contains("app info") || lowered.contains("app-info") || lowered.contains("app details") || lowered.contains("app-information") || lowered.contains("anwendungsinfo") || lowered.contains("app-informationen")
        val hasForceStop = lowered.contains("force stop") || lowered.contains("stopp erzwingen")
        val uninstallId = loweredIds.contains("uninstall") || loweredIds.contains("btn_uninstall")
        val installerPrompt = lowered.contains("do you want to uninstall") || lowered.contains("möchtest du") || lowered.contains("wirklich deinstallieren")

        return when (pkg) {
            "com.android.settings" -> {
                when {
                    (hasUninstall || uninstallId) && (hasAppInfo || hasForceStop || loweredIds.contains("force_stop")) -> UninstallSurfaceSignal("settings_app_info")
                    (hasAppInfo && hasUninstall) -> UninstallSurfaceSignal("settings_app_info")
                    else -> null
                }
            }
            "com.android.vending" -> {
                when {
                    hasUninstall || uninstallId -> UninstallSurfaceSignal("play_store_uninstall")
                    else -> null
                }
            }
            "com.google.android.packageinstaller", "com.android.packageinstaller" -> {
                when {
                    hasUninstall || installerPrompt || uninstallId -> UninstallSurfaceSignal("package_installer_uninstall")
                    else -> null
                }
            }
            else -> null
        }
    }

    private fun collectNodeTextBlob(root: AccessibilityNodeInfo, event: AccessibilityEvent?): String {
        data class WorkItem(val node: AccessibilityNodeInfo, val depth: Int, val owned: Boolean)

        val parts = ArrayList<String>(24)
        event?.text?.forEach { if (!it.isNullOrBlank()) parts += it.toString() }
        event?.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { parts += it }

        val stack = ArrayDeque<WorkItem>()
        stack.addLast(WorkItem(root, 0, false))
        var visited = 0
        while (stack.isNotEmpty() && visited < MAX_NODE_SCAN_COUNT && parts.size < 80) {
            val item = stack.removeLast()
            val current = item.node
            try {
                visited++
                current.text?.toString()?.takeIf { it.isNotBlank() }?.let { parts += it }
                current.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { parts += it }
                if (item.depth >= MAX_NODE_SCAN_DEPTH) continue
                val childCount = runCatching { current.childCount }.getOrDefault(0)
                for (i in childCount - 1 downTo 0) {
                    if (visited + stack.size >= MAX_NODE_SCAN_COUNT) break
                    val child = runCatching { current.getChild(i) }.getOrNull() ?: continue
                    stack.addLast(WorkItem(child, item.depth + 1, true))
                }
            } finally {
            }
        }
        return parts.joinToString(separator = " ").lowercase(Locale.ROOT)
    }

    private fun collectNodeIdBlob(root: AccessibilityNodeInfo): String {
        data class WorkItem(val node: AccessibilityNodeInfo, val depth: Int, val owned: Boolean)

        val parts = ArrayList<String>(24)
        val stack = ArrayDeque<WorkItem>()
        stack.addLast(WorkItem(root, 0, false))
        var visited = 0
        while (stack.isNotEmpty() && visited < MAX_NODE_SCAN_COUNT && parts.size < 48) {
            val item = stack.removeLast()
            val current = item.node
            try {
                visited++
                runCatching { current.viewIdResourceName }.getOrNull()?.takeIf { it.isNotBlank() }?.let { parts += it }
                if (item.depth >= MAX_NODE_SCAN_DEPTH) continue
                val childCount = runCatching { current.childCount }.getOrDefault(0)
                for (i in childCount - 1 downTo 0) {
                    if (visited + stack.size >= MAX_NODE_SCAN_COUNT) break
                    val child = runCatching { current.getChild(i) }.getOrNull() ?: continue
                    stack.addLast(WorkItem(child, item.depth + 1, true))
                }
            } finally {
            }
        }
        return parts.joinToString(separator = " ").lowercase(Locale.ROOT)
    }

    private fun blockNow(pkg: String, immediate: Boolean) {
        val now = System.currentTimeMillis()
        val allowLoopSafetyMode = shouldUseLoopSafetyMode(this, pkg)
        if (allowLoopSafetyMode && isBlockSuppressed(pkg, now)) {
            enforceSuppressedBlock(pkg, now)
            return
        }
        if (!allowLoopSafetyMode) {
            suppressedBlockingUntilByPkg.remove(pkg)
        }
        if (BlockerActivity.isVisible) {
            appendBlockingLog(
                category = "app_block_skip",
                key = "app-block-visible|$pkg",
                message = "pkg=$pkg reason=blocker_visible",
                throttleMs = 1_500L
            )
            return
        }
        if (allowLoopSafetyMode && shouldSuppressForLoop(pkg, now)) return

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

        if (countAsBlock) {
            appendBlockingLog(
                category = "app_block",
                key = "app-block|$pkg",
                message = "pkg=$pkg immediate=$immediate label=${sanitizeWebsiteSignal(label, 80)} countAttempt=$countAttempt countAsBlock=$countAsBlock",
                throttleMs = 1_500L
            )
        }

        val delayMs = if (immediate) 20L else 60L
        blockLaunchController.showAppBlocker(pkg, label, delayMs)
    }

    private fun safeAppLabel(pkg: String): String {
        return runCatching {
            val ai = packageManager.getApplicationInfo(pkg, 0)
            packageManager.getApplicationLabel(ai).toString()
        }.getOrNull() ?: pkg
    }

    private fun surfaceUsageLine(surfaceKey: String, limitMin: Int): String {
        if (limitMin <= 0) return ""
        return getString(R.string.blocking_daily_limit_duration_fmt, formatDuration(limitMin.toLong() * 60_000L))
    }

    private fun domainUsageLine(domain: String, limitMin: Int): String {
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
        if (prefsBoolProfile(false, BlockingToggleKeys.KEY_BLOCK_IG_SEARCH)) {
            total += SurfaceUsageStore.getUsageMsToday(this, "ig:search")
        }
        if (prefsBoolProfile(false, BlockingToggleKeys.KEY_BLOCK_IG_STORIES)) {
            total += SurfaceUsageStore.getUsageMsToday(this, "ig:stories")
        }
        if (prefsBoolProfile(false, BlockingToggleKeys.KEY_BLOCK_X_HOME)) {
            total += SurfaceUsageStore.getUsageMsToday(this, "x:foryou")
        }
        if (prefsBoolProfile(false, BlockingToggleKeys.KEY_BLOCK_X_SEARCH)) {
            total += SurfaceUsageStore.getUsageMsToday(this, "x:search")
        }
        if (prefsBoolProfile(false, BlockingToggleKeys.KEY_BLOCK_X_GROK)) {
            total += SurfaceUsageStore.getUsageMsToday(this, "x:grok")
        }
        if (prefsBoolProfile(false, BlockingToggleKeys.KEY_BLOCK_X_NOTIFICATIONS)) {
            total += SurfaceUsageStore.getUsageMsToday(this, "x:notifications")
        }
        if (prefsBoolProfile(false, BlockingToggleKeys.KEY_BLOCK_SNAP_MAP)) {
            total += SurfaceUsageStore.getUsageMsToday(this, "snap:map")
        }
        if (prefsBoolProfile(false, BlockingToggleKeys.KEY_BLOCK_SNAP_STORIES)) {
            total += SurfaceUsageStore.getUsageMsToday(this, "snap:stories")
        }
        if (prefsBoolProfile(false, BlockingToggleKeys.KEY_BLOCK_SNAP_SPOTLIGHT)) {
            total += SurfaceUsageStore.getUsageMsToday(this, "snap:spotlight")
        }
        if (prefsBoolProfile(false, BlockingToggleKeys.KEY_BLOCK_SNAP_FOLLOWING)) {
            total += SurfaceUsageStore.getUsageMsToday(this, "snap:following")
        }

        return total
    }

    private fun inAppUsageLine(limitMin: Int): String {
        if (limitMin <= 0) return ""
        return "In-app limit: ${formatDuration(limitMin.toLong() * 60_000L)}"
    }
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

    private fun tryExtractDomainFromBrowserUrlViews(root: AccessibilityNodeInfo, pkg: String): String? {
        val ids = browserUrlViewIds(pkg)
        for (id in ids) {
            val nodes = runCatching { root.findAccessibilityNodeInfosByViewId(id) }.getOrNull() ?: emptyList()
            try {
                for (node in nodes) {
                    val candidates = sequenceOf(
                        node.text?.toString(),
                        node.contentDescription?.toString()
                    )
                    for (raw in candidates) {
                        val value = raw?.trim().orEmpty()
                        if (value.isBlank()) continue
                        domainFromText(value)?.let { return it }
                    }
                }
            } finally {
            }
        }
        return null
    }

    private fun findBrowserUrlNode(root: AccessibilityNodeInfo, pkg: String): AccessibilityNodeInfo? {
        val ids = browserUrlViewIds(pkg)
        for (id in ids) {
            val nodes = runCatching { root.findAccessibilityNodeInfosByViewId(id) }.getOrNull() ?: emptyList()
            val copy = try {
                val best = nodes.firstOrNull {
                    !it.text?.toString().isNullOrBlank() ||
                        !it.contentDescription?.toString().isNullOrBlank()
                } ?: nodes.firstOrNull()
                best?.let { it }
            } finally {
            }
            if (copy != null) return copy
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

    private fun eventLooksLikeBrowserAddressEditing(pkg: String, event: AccessibilityEvent?): Boolean {
        if (event == null || !isBrowserPackage(pkg)) return false

        val type = event.eventType
        val firefoxContentChange = isFirefoxFamily(pkg) && type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        val addressishEvent =
            type == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED ||
                type == AccessibilityEvent.TYPE_VIEW_FOCUSED ||
                type == AccessibilityEvent.TYPE_VIEW_CLICKED ||
                type == AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED ||
                firefoxContentChange
        if (!addressishEvent) return false

        val source = runCatching { event.source?.let { it } }.getOrNull()
        try {
            if (source != null) {
                val vid = source.viewIdResourceName?.lowercase(Locale.getDefault()).orEmpty()
                val cls = source.className?.toString().orEmpty()
                val idHints =
                    vid.contains("url") ||
                        vid.contains("address") ||
                        vid.contains("omnibox") ||
                        vid.contains("location") ||
                        vid.contains("toolbar") ||
                        vid.contains("mozac")

                if (firefoxContentChange) {
                    val firefoxEditIds = firefoxEditingViewIds(pkg)
                    val explicitEditNode = firefoxEditIds.any { it.equals(source.viewIdResourceName, ignoreCase = true) }
                    if ((source.isEditable || cls.contains("EditText", ignoreCase = true) || explicitEditNode) && idHints) {
                        return true
                    }
                } else {
                    if (idHints) return true
                    if (source.isEditable || cls.contains("EditText", ignoreCase = true)) return true
                }
            }
        } finally {
        }

        val cd = event.contentDescription?.toString()?.lowercase(Locale.getDefault()).orEmpty()
        if (cd.contains("address") || cd.contains("search or enter") || cd.contains("search") || cd.contains("url")) {
            return true
        }

        return false
    }

    private fun isBrowserAddressEditing(
        root: AccessibilityNodeInfo,
        pkg: String,
        event: AccessibilityEvent?
    ): Boolean {
        val node = findBrowserUrlNode(root, pkg) ?: return false
        try {
            if (isFirefoxFamily(pkg)) {
                val firefoxEditIds = firefoxEditingViewIds(pkg)
                if (firefoxEditIds.isNotEmpty() && nodeHasViewId(root, firefoxEditIds)) {
                    browserWebsiteState.noteAddressEditing(pkg)
                    return true
                }
            }

            val cls = node.className?.toString().orEmpty()
            val isEdit = node.isEditable || cls.contains("EditText", ignoreCase = true)
            val focused = node.isFocused || node.isAccessibilityFocused

            val type = event?.eventType ?: 0
            val inputEvent = type == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED ||
                type == AccessibilityEvent.TYPE_VIEW_FOCUSED ||
                type == AccessibilityEvent.TYPE_VIEW_CLICKED

            if (isFirefoxFamily(pkg)) {
                // Firefox/Fenix can emit autocomplete/suggestion events that already contain a
                // blocked domain while the user is still typing in the address bar.
                // As long as the editable URL field itself is active, treat that as editing and
                // do not allow website blocking to trigger yet.
                if (isEdit && focused) {
                    browserWebsiteState.noteAddressEditing(pkg)
                    return true
                }
                val editing = isEdit && inputEvent
                if (editing) browserWebsiteState.noteAddressEditing(pkg)
                return editing
            }

            if (focused) {
                browserWebsiteState.noteAddressEditing(pkg)
                return true
            }
            val editing = isEdit && inputEvent
            if (editing) browserWebsiteState.noteAddressEditing(pkg)
            return editing
        } finally {
        }
    }

    private fun firefoxEventDomainSignal(event: AccessibilityEvent?): String? {
        if (event == null) return null

        event.text?.forEach { part ->
            val raw = part?.toString()?.trim().orEmpty()
            if (raw.isNotEmpty()) {
                domainFromText(raw)?.let { return it }
            }
        }

        val sourceCopy = runCatching { event.source?.let { it } }.getOrNull()
        try {
            if (sourceCopy != null) {
                val direct = sequenceOf(
                    sourceCopy.text?.toString(),
                    sourceCopy.contentDescription?.toString()
                )
                for (raw in direct) {
                    val value = raw?.trim().orEmpty()
                    if (value.isNotEmpty()) {
                        domainFromText(value)?.let { return it }
                    }
                }

                val parent = runCatching { sourceCopy.parent?.let { it } }.getOrNull()
                try {
                    if (parent != null) {
                        val parentDirect = sequenceOf(
                            parent.text?.toString(),
                            parent.contentDescription?.toString()
                        )
                        for (raw in parentDirect) {
                            val value = raw?.trim().orEmpty()
                            if (value.isNotEmpty()) {
                                domainFromText(value)?.let { return it }
                            }
                        }

                        val childCount = runCatching { parent.childCount }.getOrDefault(0)
                        for (i in 0 until childCount) {
                            val child = runCatching { parent.getChild(i) }.getOrNull() ?: continue
                            try {
                                val childDirect = sequenceOf(
                                    child.text?.toString(),
                                    child.contentDescription?.toString()
                                )
                                for (raw in childDirect) {
                                    val value = raw?.trim().orEmpty()
                                    if (value.isNotEmpty()) {
                                        domainFromText(value)?.let { return it }
                                    }
                                }
                            } finally {
                            }
                        }
                    }
                } finally {
                }
            }
        } finally {
        }

        return null
    }

    private fun tryExtractDomainFromBrowser(
        root: AccessibilityNodeInfo?,
        pkg: String,
        event: AccessibilityEvent? = null
    ): String? {
        if (root == null) return null

        tryExtractDomainFromBrowserUrlViews(root, pkg)?.let { return it }

        val firefoxEditing = isFirefoxFamily(pkg) && isBrowserAddressEditing(root, pkg, event)
        if (firefoxEditing) return null
        if (event != null && browserWebsiteState.addressEditingRecently(pkg)) return null

        val urlNode = findBrowserUrlNode(root, pkg)
        try {
            val t = urlNode?.text?.toString()?.trim().orEmpty()
            if (t.isNotBlank()) {
                domainFromText(t)?.let { return it }
            }

            val cd = urlNode?.contentDescription?.toString()?.trim().orEmpty()
            if (cd.isNotBlank()) {
                domainFromText(cd)?.let { return it }
            }
        } finally {
        }

        if (isFirefoxFamily(pkg) && !firefoxEditing) {
            firefoxEventDomainSignal(event)?.let { return it }
            return null
        }

        val candidate = findEditableUrlText(root)
        return candidate?.let { domainFromText(it) }
    }

    private fun domainFromText(raw: String): String? {
        val s0 = raw.trim()
        if (s0.isBlank()) return null

        val token = s0.split(" ", "›", "·", "|", "—", " ")
            .firstOrNull { it.contains(".") } ?: s0
        val s = token.trim()

        val rx = Regex("(?i)(?:https?://)?([a-z0-9.-]+\\.[a-z]{2,})(?::\\d+)?")
        val m = rx.find(s)
        val host = m?.groupValues?.getOrNull(1)
        if (!host.isNullOrBlank()) return DomainBlockStore.normalize(host)

        val withScheme = if (s.startsWith("http://") || s.startsWith("https://")) s else "https://$s"
        val parsed = runCatching { withScheme.toUri().host }.getOrNull() ?: return null
        return DomainBlockStore.normalize(parsed)
    }

    private fun isFirefoxAddressBarInputEvent(pkg: String, event: AccessibilityEvent?): Boolean {
        if (event == null || !isFirefoxFamily(pkg)) return false

        val type = event.eventType
        val textInputEvent =
            type == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED ||
                type == AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED
        if (!textInputEvent) return false

        val source = runCatching { event.source?.let { it } }.getOrNull()
        try {
            if (source != null) {
                val viewId = source.viewIdResourceName.orEmpty()
                val viewIdLower = viewId.lowercase(Locale.getDefault())
                val className = source.className?.toString().orEmpty()
                val explicitFirefoxEdit = firefoxEditingViewIds(pkg).any { it.equals(viewId, ignoreCase = true) }
                val looksLikeAddressBar =
                    explicitFirefoxEdit ||
                        viewIdLower.contains("edit_url") ||
                        viewIdLower.contains("url") ||
                        viewIdLower.contains("toolbar") ||
                        viewIdLower.contains("mozac")

                if (looksLikeAddressBar) return true
                if (source.isEditable && className.contains("EditText", ignoreCase = true)) return true
            }
        } finally {
        }

        return false
    }

    private fun firefoxLooksLikeLoadedPageEvent(pkg: String, event: AccessibilityEvent?): Boolean {
        if (event == null || !isFirefoxFamily(pkg)) return false
        if (isFirefoxAddressBarInputEvent(pkg, event)) return false

        val type = event.eventType
        val pageishEvent =
            type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
                type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                type == AccessibilityEvent.TYPE_VIEW_SCROLLED
        if (!pageishEvent) return false

        val source = runCatching { event.source?.let { it } }.getOrNull()
        try {
            if (source != null) {
                val cls = source.className?.toString().orEmpty()
                val text = source.text?.toString().orEmpty()
                val cd = source.contentDescription?.toString().orEmpty()
                if (cls.contains("WebView", ignoreCase = true) && (text.isNotBlank() || cd.isNotBlank())) {
                    return true
                }
            }
        } finally {
        }

        return false
    }

    private fun normalizeFirefoxFallbackText(raw: String?): String {
        return raw
            ?.replace("\n", " ")
            ?.replace("\r", " ")
            ?.replace("\t", " ")
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            .orEmpty()
    }

    private fun collectFirefoxFallbackTexts(
        root: AccessibilityNodeInfo,
        event: AccessibilityEvent?
    ): List<String> {
        val out = linkedSetOf<String>()

        fun add(raw: String?) {
            val value = normalizeFirefoxFallbackText(raw)
            if (value.isNotBlank()) out += value
        }

        event?.text?.forEach { add(it?.toString()) }
        add(event?.contentDescription?.toString())

        val source = runCatching { event?.source?.let { it } }.getOrNull()
        try {
            if (source != null) {
                add(source.text?.toString())
                add(source.contentDescription?.toString())

                val parent = runCatching { source.parent?.let { it } }.getOrNull()
                try {
                    if (parent != null) {
                        add(parent.text?.toString())
                        add(parent.contentDescription?.toString())
                    }
                } finally {
                }
            }
        } finally {
        }

        data class WorkItem(val node: AccessibilityNodeInfo, val depth: Int, val owned: Boolean)
        val stack = ArrayDeque<WorkItem>()
        stack.addLast(WorkItem(root, 0, false))
        var visited = 0

        while (stack.isNotEmpty() && visited < 120 && out.size < 40) {
            val item = stack.removeLast()
            val current = item.node
            try {
                visited++
                add(current.text?.toString())
                add(current.contentDescription?.toString())

                if (item.depth >= 6) continue

                val childCount = runCatching { current.childCount }.getOrDefault(0)
                for (i in childCount - 1 downTo 0) {
                    if (visited + stack.size >= 140) break
                    val child = runCatching { current.getChild(i) }.getOrNull() ?: continue
                    stack.addLast(WorkItem(child, item.depth + 1, true))
                }
            } finally {
            }
        }

        return out.toList()
    }

    private fun firefoxDomainAliases(domain: String): List<String> {
        val normalized = DomainBlockStore.normalize(domain) ?: return emptyList()
        return when (normalized) {
            "youtube.com" -> listOf("youtube")
            "discord.com" -> listOf("discord")
            "instagram.com" -> listOf("instagram")
            "x.com" -> listOf("twitter", "x.com", "it's what's happening")
            else -> listOf(normalized.substringBefore('.')).filter { it.length >= 4 }
        }
    }

    private fun inferFirefoxDomainFromTexts(
        root: AccessibilityNodeInfo,
        event: AccessibilityEvent?,
        blockedDomains: List<String>
    ): String? {
        val haystacks = collectFirefoxFallbackTexts(root, event)
            .map { it.lowercase(Locale.getDefault()) }
            .filter { it.isNotBlank() }

        if (haystacks.isEmpty()) return null

        for (domain in blockedDomains) {
            val normalized = DomainBlockStore.normalize(domain) ?: continue
            val aliases = firefoxDomainAliases(normalized)
            if (aliases.isEmpty()) continue
            if (haystacks.any { text -> aliases.any { alias -> alias.isNotBlank() && text.contains(alias) } }) {
                return normalized
            }
        }

        return null
    }

    private fun findEditableUrlText(node: AccessibilityNodeInfo): String? {
        data class WorkItem(val node: AccessibilityNodeInfo, val depth: Int, val owned: Boolean)

        val stack = ArrayDeque<WorkItem>()
        stack.addLast(WorkItem(node, 0, false))
        var visited = 0

        while (stack.isNotEmpty() && visited < MAX_NODE_SCAN_COUNT) {
            val item = stack.removeLast()
            val current = item.node
            try {
                visited++

                val t = current.text?.toString()?.trim()
                val cd = current.contentDescription?.toString()?.trim()
                val vid = current.viewIdResourceName?.lowercase(Locale.getDefault()).orEmpty()
                val cls = current.className?.toString().orEmpty()

                val candidate = when {
                    !t.isNullOrBlank() -> t
                    !cd.isNullOrBlank() -> cd
                    else -> null
                }

                val looksLikeUrl = candidate != null && candidate.length in 4..300 && candidate.contains(".")
                val idHints = vid.contains("url") || vid.contains("address") || vid.contains("omnibox") ||
                    vid.contains("location") || vid.contains("toolbar")
                val isEdit = current.isEditable || cls.contains("EditText", ignoreCase = true)
                val editingNow = current.isFocused || current.isAccessibilityFocused

                if (candidate != null && looksLikeUrl && (idHints || isEdit) && !editingNow) {
                    return candidate
                }

                if (item.depth >= MAX_NODE_SCAN_DEPTH) continue

                val childCount = runCatching { current.childCount }.getOrDefault(0)
                for (i in childCount - 1 downTo 0) {
                    if (visited + stack.size >= MAX_NODE_SCAN_COUNT) break
                    val child = runCatching { current.getChild(i) }.getOrNull() ?: continue
                    stack.addLast(WorkItem(child, item.depth + 1, true))
                }
            } finally {
            }
        }
        return null
    }

    private fun findFirstTextMatching(node: AccessibilityNodeInfo, pred: (String) -> Boolean): String? {
        data class WorkItem(val node: AccessibilityNodeInfo, val depth: Int, val owned: Boolean)

        val stack = ArrayDeque<WorkItem>()
        stack.addLast(WorkItem(node, 0, false))
        var visited = 0

        while (stack.isNotEmpty() && visited < MAX_NODE_SCAN_COUNT) {
            val item = stack.removeLast()
            val current = item.node
            try {
                visited++
                val t = current.text?.toString()
                if (!t.isNullOrBlank() && pred(t)) return t

                if (item.depth >= MAX_NODE_SCAN_DEPTH) continue

                val childCount = runCatching { current.childCount }.getOrDefault(0)
                for (i in childCount - 1 downTo 0) {
                    if (visited + stack.size >= MAX_NODE_SCAN_COUNT) break
                    val child = runCatching { current.getChild(i) }.getOrNull() ?: continue
                    stack.addLast(WorkItem(child, item.depth + 1, true))
                }
            } finally {
            }
        }
        return null
    }

    private fun currentRoot(event: AccessibilityEvent? = null): AccessibilityNodeInfo? {
        return rootInActiveWindow
            ?: event?.source
            ?: runCatching { windows?.firstOrNull { it.isActive }?.root }.getOrNull()
            ?: runCatching { windows?.firstOrNull()?.root }.getOrNull()
    }

    private fun tryRedirectBrowserToSafePage(pkg: String): Boolean {
        val safeUris = listOf("about:blank", "about:home")
        for (raw in safeUris) {
            val intent = runCatching {
                Intent(Intent.ACTION_VIEW, raw.toUri()).apply {
                    setPackage(pkg)
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                            Intent.FLAG_ACTIVITY_NO_ANIMATION
                    )
                }
            }.getOrNull() ?: continue
            val ok = runCatching {
                startActivity(intent)
                true
            }.getOrDefault(false)
            if (ok) {
                browserWebsiteState.resetAfterRedirect(pkg)
                return true
            }
        }
        return false
    }

    private fun requiresDomainStability(pkg: String): Boolean {
        // Firefox/Fenix often emits fewer stable URL events than Chromium-based browsers.
        // Requiring a second confirmation event there can cause website blocking to never trigger
        // on some devices/builds even after navigation has committed.
        return !isFirefoxFamily(pkg)
    }

    private fun maybeBlockWebsite(pkg: String, event: AccessibilityEvent? = null) {
        if (!isBrowserPackage(pkg)) return
        perf.websiteScans++

        val now = System.currentTimeMillis()
        if (eventLooksLikeBrowserAddressEditing(pkg, event)) {
            browserWebsiteState.noteAddressEditing(pkg, now)
        }
        val firefoxAddressBarInputEvent = isFirefoxAddressBarInputEvent(pkg, event)

        val root = currentRoot(event) ?: run {
            perf.rootMisses++
            return
        }

        // Don't block while user is typing in the address bar/autocomplete.
        val editingNow = isBrowserAddressEditing(root, pkg, event)
        val recentEditing = event != null && browserWebsiteState.addressEditingRecently(pkg, now)
        val loadedFirefoxPageEvent = firefoxLooksLikeLoadedPageEvent(pkg, event)
        if (editingNow || firefoxAddressBarInputEvent || (recentEditing && !loadedFirefoxPageEvent)) {
            if (isFirefoxFamily(pkg)) {
                val pendingHost = firefoxEventDomainSignal(event)
                if (!pendingHost.isNullOrBlank()) {
                    browserWebsiteState.notePendingDomain(pkg, pendingHost, now)
                }
                appendBlockingLog(
                    category = "website_skip",
                    key = "web-skip-edit|$pkg|${eventTypeLabel(event)}",
                    message = "pkg=$pkg reason=address_editing editingNow=$editingNow recentEditing=$recentEditing addressBarInput=$firefoxAddressBarInputEvent loadedPage=$loadedFirefoxPageEvent pendingHost=${sanitizeWebsiteSignal(pendingHost)} ${firefoxSignalSummary(root, event)}",
                    throttleMs = 1_500L
                )
            }
            browserWebsiteState.clearCurrentFor(pkg)
            return
        }

        val blockWebsitesEnabled = isDomainBlockingEnabledCached() && prefsBoolProfile(true, BlockingToggleKeys.KEY_BLOCK_WEBSITES)
        val blockedDomainsList = if (blockWebsitesEnabled) DomainBlockStore.getDomains(this).toList() else emptyList()

        val host = tryExtractDomainFromBrowser(root, pkg, event)
            ?: if (isFirefoxFamily(pkg) && blockWebsitesEnabled) {
                inferFirefoxDomainFromTexts(root, event, blockedDomainsList)
            } else {
                null
            }
            ?: run {
                if (isFirefoxFamily(pkg) && (loadedFirefoxPageEvent || !recentEditing)) {
                    browserWebsiteState.currentPendingDomain(pkg, now)?.let { pendingHost ->
                        appendBlockingLog(
                            category = "website_detect",
                            key = "web-firefox-pending-host|$pkg|$pendingHost",
                            message = "pkg=$pkg result=using_pending_host host=${sanitizeWebsiteSignal(pendingHost)} event=${eventTypeLabel(event)} loadedPage=$loadedFirefoxPageEvent ${firefoxSignalSummary(root, event)}",
                            throttleMs = 1_200L
                        )
                        pendingHost
                    }
                } else null
            }
            ?: run {
                if (isFirefoxFamily(pkg) && loadedFirefoxPageEvent) {
                    browserWebsiteState.currentTrackedDomain(pkg, now, isFirefox = true)?.let { cachedHost ->
                        appendBlockingLog(
                            category = "website_detect",
                            key = "web-firefox-cached-host|$pkg|$cachedHost",
                            message = "pkg=$pkg result=using_cached_host host=${sanitizeWebsiteSignal(cachedHost)} event=${eventTypeLabel(event)} ${firefoxSignalSummary(root, event)}",
                            throttleMs = 1_500L
                        )
                        cachedHost
                    }
                } else null
            }
            ?: run {
                if (isFirefoxFamily(pkg)) {
                    appendBlockingLog(
                        category = "website_detect",
                        key = "web-no-host|$pkg|${eventTypeLabel(event)}",
                        message = "pkg=$pkg result=no_host blockEnabled=$blockWebsitesEnabled blockedCount=${blockedDomainsList.size} ${firefoxSignalSummary(root, event)}",
                        throttleMs = 1_500L
                    )
                    if (blockWebsitesEnabled && blockedDomainsList.isNotEmpty()) {
                        scheduleFirefoxShortcutWebsiteProbe(pkg, blockedDomainsList)
                    }
                }
                browserWebsiteState.resetCandidate()
                return
            }

        // Require a short stable domain signal to avoid premature blocks on autocomplete suggestions.
        // For Firefox/Fenix we skip the second-event requirement because some builds emit fewer URL events.
        val needStability = requiresDomainStability(pkg)
        if (browserWebsiteState.noteCandidate(host, now)) {
            appendBlockingLog(
                category = "website_detect",
                key = "web-candidate|$pkg|$host",
                message = "pkg=$pkg host=${sanitizeWebsiteSignal(host)} state=candidate needStability=$needStability event=${eventTypeLabel(event)}" +
                    if (isFirefoxFamily(pkg)) " ${firefoxSignalSummary(root, event)}" else "",
                throttleMs = 1_500L
            )
            if (needStability) return
        }
        val candidateAgeMs = browserWebsiteState.candidateAgeMs(now)
        if (needStability && candidateAgeMs < BrowserWebsiteState.DOMAIN_CONFIRM_MS) {
            appendBlockingLog(
                category = "website_detect",
                key = "web-wait-stable|$pkg|$host",
                message = "pkg=$pkg host=${sanitizeWebsiteSignal(host)} state=waiting_stability ageMs=$candidateAgeMs confirmMs=${BrowserWebsiteState.DOMAIN_CONFIRM_MS} event=${eventTypeLabel(event)}",
                throttleMs = 1_500L
            )
            return
        }

        // Cache for web usage stats
        browserWebsiteState.updateCurrentDomain(pkg, host, now)
        if (isFirefoxFamily(pkg)) {
            browserWebsiteState.clearPendingDomain(pkg)
        }

        if (!blockWebsitesEnabled) return

        val blockedDomains = blockedDomainsList
        val hardBlocked = blockedDomains.any { DomainBlockStore.matches(host, it) }

        val limitMin = DomainLimitStore.getLimitMinutes(this, host)
        val usageMs = if (limitMin > 0) WebUsageStore.getUsageMsToday(this, host) else 0L
        val limitReached = limitMin > 0 && usageMs >= limitMin.toLong() * 60_000L

        if (!hardBlocked && !limitReached) {
            if (isFirefoxFamily(pkg)) {
                appendBlockingLog(
                    category = "website_detect",
                    key = "web-allowed|$pkg|$host",
                    message = "pkg=$pkg host=${sanitizeWebsiteSignal(host)} matched=false limitMin=$limitMin usageMs=$usageMs event=${eventTypeLabel(event)}",
                    throttleMs = 10_000L
                )
            }
            return
        }

        val appLabel = safeAppLabel(pkg)
        val title = if (hardBlocked) getString(R.string.blocking_website_blocked_title) else getString(R.string.blocking_website_limit_reached_title)
        val msg = domainUsageLine(host, limitMin)

        // Prefer redirecting the current browser task to a safe page so the browser stays open
        // without dropping the user out of the whole app. Fall back to a single BACK only if the
        // redirect is not supported by the current browser build.
        val redirected = tryRedirectBrowserToSafePage(pkg)
        appendBlockingLog(
            category = "website_block",
            key = "web-block|$pkg|$host",
            message = "pkg=$pkg host=${sanitizeWebsiteSignal(host)} hardBlocked=$hardBlocked limitReached=$limitReached limitMin=$limitMin usageMs=$usageMs redirected=$redirected event=${eventTypeLabel(event)}" +
                if (isFirefoxFamily(pkg)) " ${firefoxSignalSummary(root, event)}" else "",
            throttleMs = 1_500L
        )
        scheduleWebsiteBlockFollowUp(pkg, host, appLabel, title, msg, redirected)
        softBlockSurface(
            pkg,
            appLabel,
            title,
            msg,
            backCount = if (redirected) 0 else 1,
            deferNavigationUntilAcknowledge = !redirected,
            returnToPackageOnClose = true
        )
    }

    private fun scheduleFirefoxShortcutWebsiteProbe(pkg: String, blockedDomains: List<String>) {
        if (!isFirefoxFamily(pkg)) return
        val now = System.currentTimeMillis()
        val last = lastFirefoxShortcutProbeAt[pkg] ?: 0L
        if (now - last < FIREFOX_SHORTCUT_PROBE_COOLDOWN_MS) return
        lastFirefoxShortcutProbeAt[pkg] = now

        appendBlockingLog(
            category = "website_followup",
            key = "web-firefox-shortcut-probe-scheduled|$pkg",
            message = "pkg=$pkg reason=no_host_firefox_shortcut blockedCount=${blockedDomains.size}",
            throttleMs = 1_500L
        )

        handler.postDelayed({ enforceFirefoxShortcutWebsiteIfVisible(pkg) }, 450L)
        handler.postDelayed({ enforceFirefoxShortcutWebsiteIfVisible(pkg) }, 1_250L)
        handler.postDelayed({ enforceFirefoxShortcutWebsiteIfVisible(pkg) }, 2_200L)
    }

    private fun enforceFirefoxShortcutWebsiteIfVisible(pkg: String) {
        if (!SwitchModeStore.isEnabled(this)) return
        if (EmergencyBypassStore.isActive(this)) return
        if (!isDomainBlockingEnabledCached()) return
        if (!prefsBoolProfile(true, BlockingToggleKeys.KEY_BLOCK_WEBSITES)) return

        val root = currentRoot(null) ?: return
        if (!isRootFromPackage(root, pkg)) return

        val blockedDomains = DomainBlockStore.getDomains(this).toList()
        if (blockedDomains.isEmpty()) return

        val visibleHost = tryExtractDomainFromBrowserUrlViews(root, pkg)
            ?: inferFirefoxDomainFromTexts(root, null, blockedDomains)
            ?: browserWebsiteState.currentPendingDomain(pkg)
            ?: browserWebsiteState.currentTrackedDomain(pkg, System.currentTimeMillis(), isFirefox = true)
            ?: return

        val hardBlocked = blockedDomains.any { DomainBlockStore.matches(visibleHost, it) }
        val limitMin = DomainLimitStore.getLimitMinutes(this, visibleHost)
        val limitReached = limitMin > 0 && WebUsageStore.getUsageMsToday(this, visibleHost) >= limitMin.toLong() * 60_000L
        if (!hardBlocked && !limitReached) return

        val appLabel = safeAppLabel(pkg)
        val title = if (hardBlocked) getString(R.string.blocking_website_blocked_title) else getString(R.string.blocking_website_limit_reached_title)
        val msg = domainUsageLine(visibleHost, limitMin)

        appendBlockingLog(
            category = "website_followup",
            key = "web-firefox-shortcut-block|$pkg|$visibleHost",
            message = "pkg=$pkg host=${sanitizeWebsiteSignal(visibleHost)} reason=firefox_home_shortcut hardBlocked=$hardBlocked limitReached=$limitReached",
            throttleMs = 1_000L
        )

        val redirected = tryRedirectBrowserToSafePage(pkg)
        scheduleWebsiteBlockFollowUp(pkg, visibleHost, appLabel, title, msg, redirected)
        softBlockSurface(
            pkg,
            appLabel,
            title,
            msg,
            backCount = if (redirected) 0 else 1,
            deferNavigationUntilAcknowledge = !redirected,
            returnToPackageOnClose = true
        )
    }

    private fun scheduleWebsiteBlockFollowUp(
        pkg: String,
        host: String,
        appLabel: String,
        title: String,
        message: String,
        redirected: Boolean
    ) {
        // Some Chromium/OEM combinations can restore the blocked tab immediately after our
        // safe-page redirect. Re-check shortly after a successful website block and enforce once
        // more if the same blocked host is still visible.
        val now = System.currentTimeMillis()
        val key = "$pkg|$host"
        val last = lastWebsiteFollowUpAt[key] ?: 0L
        if (now - last < WEBSITE_REDIRECT_FOLLOW_UP_COOLDOWN_MS) return
        lastWebsiteFollowUpAt[key] = now

        val firstDelay = if (redirected) 650L else 1_100L
        handler.postDelayed({ enforceWebsiteBlockIfStillVisible(pkg, host, appLabel, title, message, finalAttempt = false) }, firstDelay)
        handler.postDelayed({ enforceWebsiteBlockIfStillVisible(pkg, host, appLabel, title, message, finalAttempt = true) }, firstDelay + 1_250L)
    }

    private fun enforceWebsiteBlockIfStillVisible(
        pkg: String,
        host: String,
        appLabel: String,
        title: String,
        message: String,
        finalAttempt: Boolean
    ) {
        if (!SwitchModeStore.isEnabled(this)) return
        if (EmergencyBypassStore.isActive(this)) return
        if (!isDomainBlockingEnabledCached()) return
        if (!prefsBoolProfile(true, BlockingToggleKeys.KEY_BLOCK_WEBSITES)) return

        val root = currentRoot(null) ?: return
        if (!isRootFromPackage(root, pkg)) return

        val visibleHost = tryExtractDomainFromBrowserUrlViews(root, pkg)
            ?: tryExtractDomainFromBrowser(root, pkg, null)
            ?: return

        val stillBlocked = DomainBlockStore.matches(visibleHost, host) ||
            DomainBlockStore.getDomains(this).any { DomainBlockStore.matches(visibleHost, it) }
        val limitMin = DomainLimitStore.getLimitMinutes(this, visibleHost)
        val limitReached = limitMin > 0 && WebUsageStore.getUsageMsToday(this, visibleHost) >= limitMin.toLong() * 60_000L

        if (!stillBlocked && !limitReached) return

        appendBlockingLog(
            category = "website_followup",
            key = "web-followup|$pkg|$visibleHost|$finalAttempt",
            message = "pkg=$pkg host=${sanitizeWebsiteSignal(visibleHost)} original=${sanitizeWebsiteSignal(host)} final=$finalAttempt",
            throttleMs = 1_000L
        )

        val redirectedAgain = tryRedirectBrowserToSafePage(pkg)
        if (!redirectedAgain || finalAttempt) {
            // Last resort for browsers/OEMs that immediately restore the blocked tab after redirect.
            // First try one browser back step, then bounce Home on the final attempt so the blocked
            // page cannot remain visible even if Chrome/Xiaomi restores the tab content.
            blockLaunchController.performBackSequence(1, initialDelayMs = 80L, stepMs = 120L)
            if (finalAttempt) {
                blockLaunchController.postHome(delayMs = 220L)
                blockLaunchController.postHome(delayMs = 520L)
            }
            if (!BlockerActivity.isVisible && finalAttempt) {
                softBlockSurface(
                    pkg,
                    appLabel,
                    title,
                    message,
                    backCount = 0,
                    deferNavigationUntilAcknowledge = false,
                    returnToPackageOnClose = true
                )
            }
        }
    }

    private fun nodeHasViewId(root: AccessibilityNodeInfo, viewIds: List<String>): Boolean {
        for (id in viewIds) {
            val nodes = runCatching { root.findAccessibilityNodeInfosByViewId(id) }.getOrNull() ?: emptyList()
            val hasNodes = try {
                nodes.isNotEmpty()
            } finally {
            }
            if (hasNodes) return true
        }
        return false
    }

    private fun findAnyNode(root: AccessibilityNodeInfo, pred: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        data class WorkItem(val node: AccessibilityNodeInfo, val depth: Int, val owned: Boolean)

        val stack = ArrayDeque<WorkItem>()
        stack.addLast(WorkItem(root, 0, false))
        var visited = 0

        while (stack.isNotEmpty() && visited < MAX_NODE_SCAN_COUNT) {
            val item = stack.removeLast()
            val current = item.node
            try {
                visited++
                if (pred(current)) {
                    return current
                }

                if (item.depth >= MAX_NODE_SCAN_DEPTH) continue

                val childCount = runCatching { current.childCount }.getOrDefault(0)
                for (i in childCount - 1 downTo 0) {
                    if (visited + stack.size >= MAX_NODE_SCAN_COUNT) break
                    val child = runCatching { current.getChild(i) }.getOrNull() ?: continue
                    stack.addLast(WorkItem(child, item.depth + 1, true))
                }
            } finally {
            }
        }
        return null
    }

    private fun nodeTextMatches(root: AccessibilityNodeInfo, needles: List<String>): Boolean {
        val n = needles.map { it.lowercase(Locale.getDefault()) }
        val found = findAnyNode(root) { node ->
            val t = node.text?.toString()?.lowercase(Locale.getDefault())
            val cd = node.contentDescription?.toString()?.lowercase(Locale.getDefault())
            (t != null && n.any { t.contains(it) }) || (cd != null && n.any { cd.contains(it) })
        }
        return try {
            found != null
        } finally {
        }
    }

    private fun eventTextMatches(event: AccessibilityEvent?, needles: List<String>): Boolean {
        if (event == null) return false
        val n = needles.map { it.lowercase(Locale.getDefault()) }
        val cd = event.contentDescription?.toString()?.lowercase(Locale.getDefault())
        if (cd != null && n.any { cd.contains(it) }) return true
        val texts = event.text ?: emptyList()
        for (cs in texts) {
            val t = cs?.toString()?.lowercase(Locale.getDefault()) ?: continue
            if (n.any { t.contains(it) }) return true
        }
        return false
    }

    private fun hasSelectedLabel(root: AccessibilityNodeInfo, needles: List<String>): Boolean {
        val n = needles.map { it.lowercase(Locale.getDefault()) }
        val found = findAnyNode(root) { node ->
            if (!node.isSelected) return@findAnyNode false
            val t = node.text?.toString()?.lowercase(Locale.getDefault())
            val cd = node.contentDescription?.toString()?.lowercase(Locale.getDefault())
            (t != null && n.any { t.contains(it) }) || (cd != null && n.any { cd.contains(it) })
        }
        return try {
            found != null
        } finally {
        }
    }

    private fun hasSelectedLabelInPackage(
        root: AccessibilityNodeInfo,
        needles: List<String>,
        pkg: String
    ): Boolean {
        val n = needles.map { it.lowercase(Locale.getDefault()) }
        val targetPkg = pkg.lowercase(Locale.getDefault())
        val found = findAnyNode(root) { node ->
            if (!node.isSelected) return@findAnyNode false
            val nodePkg = node.packageName?.toString()?.lowercase(Locale.getDefault()).orEmpty()
            if (nodePkg != targetPkg) return@findAnyNode false

            val t = node.text?.toString()?.lowercase(Locale.getDefault())
            val cd = node.contentDescription?.toString()?.lowercase(Locale.getDefault())
            (t != null && n.any { t.contains(it) }) || (cd != null && n.any { cd.contains(it) })
        }
        return try {
            found != null
        } finally {
        }
    }

    private fun eventOrSourceMatches(event: AccessibilityEvent?, needles: List<String>): Boolean {
        if (eventTextMatches(event, needles)) return true
        val source = runCatching { event?.source }.getOrNull()
        return try {
            val text = nodeTextOrDesc(source)
            val lowered = needles.map { it.lowercase(Locale.getDefault()) }
            lowered.any { text.contains(it) }
        } finally {
        }
    }

    private fun findNodeWithLabelInPackage(
        root: AccessibilityNodeInfo,
        labels: List<String>,
        pkg: String,
        requireUnselected: Boolean = false
    ): AccessibilityNodeInfo? {
        val lowered = labels.map { it.lowercase(Locale.getDefault()) }
        val targetPkg = pkg.lowercase(Locale.getDefault())
        return findAnyNode(root) { node ->
            val nodePkg = node.packageName?.toString()?.lowercase(Locale.getDefault()).orEmpty()
            if (nodePkg != targetPkg) return@findAnyNode false
            if (requireUnselected && node.isSelected) return@findAnyNode false
            val text = node.text?.toString()?.lowercase(Locale.getDefault()).orEmpty()
            val desc = node.contentDescription?.toString()?.lowercase(Locale.getDefault()).orEmpty()
            lowered.any { text.contains(it) || desc.contains(it) }
        }
    }

    private fun tryClickAnyLabelInPackage(root: AccessibilityNodeInfo?, pkg: String, labels: List<String>): Boolean {
        val r = root ?: return false
        val preferred = findNodeWithLabelInPackage(r, labels, pkg, requireUnselected = true)
        if (clickNodeOrClickableParent(preferred)) return true
        val fallback = findNodeWithLabelInPackage(r, labels, pkg, requireUnselected = false)
        return clickNodeOrClickableParent(fallback)
    }

    private fun snapchatBottomTabSurfaceFromCenterX(centerX: Float): String {
        val anchors = listOf(
            0.12f to "snap:map",
            0.31f to "snap:chat",
            0.50f to "snap:safe",
            0.69f to "snap:stories",
            0.88f to "snap:spotlight"
        )
        return anchors.minByOrNull { abs(centerX - it.first) }?.second ?: "snap:safe"
    }

    private fun snapchatBottomTabFromNode(node: AccessibilityNodeInfo?): String? {
        val direct = node ?: return null

        fun classifyBottomTab(candidate: AccessibilityNodeInfo?): String? {
            val n = candidate ?: return null
            val nodePkg = n.packageName?.toString()?.lowercase(Locale.getDefault()).orEmpty()
            if (nodePkg != "com.snapchat.android") return null

            val bounds = Rect()
            runCatching { n.getBoundsInScreen(bounds) }
            if (bounds.isEmpty) return null

            val width = resources.displayMetrics.widthPixels.coerceAtLeast(1)
            val height = resources.displayMetrics.heightPixels.coerceAtLeast(1)
            val centerX = bounds.exactCenterX() / width.toFloat()
            val centerY = bounds.exactCenterY() / height.toFloat()
            val widthRatio = bounds.width() / width.toFloat()
            val heightRatio = bounds.height() / height.toFloat()
            val isBottomZone = centerY >= 0.72f
            val isReasonableNode = widthRatio <= 0.30f && heightRatio <= 0.16f
            if (!isBottomZone || !isReasonableNode) return null

            return snapchatBottomTabSurfaceFromCenterX(centerX)
        }

        classifyBottomTab(direct)?.let { return it }

        val normalized = firstClickableAncestorInPackage(direct, "com.snapchat.android") ?: direct
        return try {
            classifyBottomTab(normalized)
        } finally {
        }
    }

    private fun detectSnapchatSelectedSurface(root: AccessibilityNodeInfo?): String? {
        val r = root ?: return null
        val width = resources.displayMetrics.widthPixels.coerceAtLeast(1)
        val height = resources.displayMetrics.heightPixels.coerceAtLeast(1)
        var best: Pair<String, Float>? = null

        val found = findAnyNode(r) { node ->
            val nodePkg = node.packageName?.toString()?.lowercase(Locale.getDefault()).orEmpty()
            if (nodePkg != "com.snapchat.android") return@findAnyNode false
            if (!node.isSelected) return@findAnyNode false

            val bounds = Rect()
            runCatching { node.getBoundsInScreen(bounds) }
            if (bounds.isEmpty) return@findAnyNode false

            val centerX = bounds.exactCenterX() / width.toFloat()
            val centerY = bounds.exactCenterY() / height.toFloat()
            if (centerY < 0.72f) return@findAnyNode false

            best = snapchatBottomTabSurfaceFromCenterX(centerX) to centerY
            true
        }
        try {
            return best?.first
        } finally {
        }
    }

    private fun tapScreenAtRatio(centerXRatio: Float, centerYRatio: Float = 0.90f): Boolean {
        val width = resources.displayMetrics.widthPixels.coerceAtLeast(1)
        val height = resources.displayMetrics.heightPixels.coerceAtLeast(1)
        val x = (width * centerXRatio).coerceIn(1f, width.toFloat() - 1f)
        val y = (height * centerYRatio).coerceIn(1f, height.toFloat() - 1f)
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 50L)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return runCatching { dispatchGesture(gesture, null, null) }.getOrDefault(false)
    }

    private fun firstClickableAncestorInPackage(
        node: AccessibilityNodeInfo?,
        pkg: String,
        maxHops: Int = 6
    ): AccessibilityNodeInfo? {
        var current = node?.let { it } ?: return null
        var hops = 0
        val targetPkg = pkg.lowercase(Locale.getDefault())
        while (hops < maxHops) {
            val nodePkg = current.packageName?.toString()?.lowercase(Locale.getDefault()).orEmpty()
            val canClick = current.isClickable || (current.actionList?.any { it.id == AccessibilityNodeInfo.ACTION_CLICK } == true)
            if (nodePkg == targetPkg && canClick) return current
            val parent = runCatching { current.parent }.getOrNull()
            current = parent ?: return null
            hops++
        }
        return null
    }

    private fun findBestSnapchatBottomTabNode(
        root: AccessibilityNodeInfo,
        targetCenterRatio: Float
    ): AccessibilityNodeInfo? {
        val width = resources.displayMetrics.widthPixels.coerceAtLeast(1)
        val height = resources.displayMetrics.heightPixels.coerceAtLeast(1)
        val generic = findBestBottomTabNodeInPackage(root, "com.snapchat.android", targetCenterRatio) ?: return null
        val bounds = Rect()
        runCatching { generic.getBoundsInScreen(bounds) }
        if (bounds.isEmpty) return generic

        val widthRatio = bounds.width() / width.toFloat()
        val heightRatio = bounds.height() / height.toFloat()
        val likelyOversized = widthRatio > 0.42f || heightRatio > 0.20f
        return if (likelyOversized) {
            null
        } else {
            generic
        }
    }

    private fun findBestBottomTabNodeInPackage(
        root: AccessibilityNodeInfo,
        pkg: String,
        targetCenterRatio: Float
    ): AccessibilityNodeInfo? {
        data class WorkItem(val node: AccessibilityNodeInfo, val depth: Int, val owned: Boolean)

        val stack = ArrayDeque<WorkItem>()
        stack.addLast(WorkItem(root, 0, false))
        val width = resources.displayMetrics.widthPixels.coerceAtLeast(1)
        val height = resources.displayMetrics.heightPixels.coerceAtLeast(1)
        val targetPkg = pkg.lowercase(Locale.getDefault())
        var visited = 0
        var bestNode: AccessibilityNodeInfo? = null
        var bestScore = Float.MAX_VALUE

        while (stack.isNotEmpty() && visited < MAX_NODE_SCAN_COUNT) {
            val item = stack.removeLast()
            val current = item.node
            try {
                visited++

                val nodePkg = current.packageName?.toString()?.lowercase(Locale.getDefault()).orEmpty()
                val canClick =
                    current.isClickable ||
                        (current.actionList?.any { it.id == AccessibilityNodeInfo.ACTION_CLICK } == true)
                if (nodePkg == targetPkg && canClick) {
                    val bounds = Rect()
                    runCatching { current.getBoundsInScreen(bounds) }
                    if (!bounds.isEmpty) {
                        val centerX = bounds.exactCenterX() / width.toFloat()
                        val centerY = bounds.exactCenterY() / height.toFloat()
                        if (centerY >= 0.72f) {
                            val score = abs(centerX - targetCenterRatio) + abs(centerY - 0.90f) * 0.35f
                            if (score < bestScore) {
                                bestNode = current
                                bestScore = score
                            }
                        }
                    }
                }

                if (item.depth >= MAX_NODE_SCAN_DEPTH) continue

                val childCount = runCatching { current.childCount }.getOrDefault(0)
                for (i in childCount - 1 downTo 0) {
                    if (visited + stack.size >= MAX_NODE_SCAN_COUNT) break
                    val child = runCatching { current.getChild(i) }.getOrNull() ?: continue
                    stack.addLast(WorkItem(child, item.depth + 1, true))
                }
            } finally {
            }
        }

        return bestNode
    }

    private fun tryNavigateSnapchatToCamera(root: AccessibilityNodeInfo?): Boolean {
        val r = root ?: return tapScreenAtRatio(0.50f, 0.90f)
        val pkg = "com.snapchat.android"

        if (tryClickAnyLabelInPackage(r, pkg, listOf("camera", "capture"))) return true

        val cameraNode = findBestSnapchatBottomTabNode(r, 0.50f)
        val cameraClicked = try {
            clickNodeOrClickableParent(cameraNode)
        } finally {
        }
        if (cameraClicked) return true

        if (tapScreenAtRatio(0.50f, 0.90f) || tapScreenAtRatio(0.50f, 0.86f)) return true

        if (tryClickAnyLabelInPackage(r, pkg, listOf("chat", "chats"))) return true

        val chatNode = findBestSnapchatBottomTabNode(r, 0.30f)
        val chatClicked = try {
            clickNodeOrClickableParent(chatNode)
        } finally {
        }
        if (chatClicked) return true

        return tapScreenAtRatio(0.30f, 0.90f)
    }

    private fun tryNavigateTwitterToHome(root: AccessibilityNodeInfo?): Boolean {
        val r = root ?: return false
        val pkg = "com.twitter.android"
        return tryClickAnyLabelInPackage(r, pkg, listOf("home", "home timeline", "startseite"))
    }

    private fun tryNavigateTwitterToNotifications(root: AccessibilityNodeInfo?): Boolean {
        val r = root ?: return false
        val pkg = "com.twitter.android"
        return tryClickAnyLabelInPackage(r, pkg, listOf("notifications", "notification", "benachrichtigungen"))
    }

    private fun isTwitterSurfaceSelected(root: AccessibilityNodeInfo, labels: List<String>): Boolean {
        return hasSelectedLabelInPackage(root, labels, "com.twitter.android")
    }

    private fun resolveSnapchatSurfaceFromEvent(event: AccessibilityEvent?, allowFocused: Boolean = false): String? {
        if (event == null) return null
        val type = event.eventType
        val interactive =
            type == AccessibilityEvent.TYPE_VIEW_CLICKED ||
                type == AccessibilityEvent.TYPE_VIEW_SELECTED ||
                (allowFocused && type == AccessibilityEvent.TYPE_VIEW_FOCUSED)
        if (!interactive) return null

        val source = runCatching { event.source }.getOrNull()
        val rawSourceSurface = snapchatBottomTabFromNode(source)
        if (type == AccessibilityEvent.TYPE_VIEW_CLICKED && rawSourceSurface != null) {
            return try {
                rawSourceSurface
            } finally {
            }
        }

        val target = firstClickableAncestorInPackage(source, "com.snapchat.android") ?: source
        return try {
            val eventHaystack = buildString {
                append(event.contentDescription?.toString().orEmpty())
                append(' ')
                event.text?.forEach {
                    append(it?.toString().orEmpty())
                    append(' ')
                }
            }.lowercase(Locale.getDefault())
            val sourceHaystack = buildString {
                append(nodeTextOrDesc(source))
                append(' ')
                append(source?.viewIdResourceName?.orEmpty())
            }.lowercase(Locale.getDefault())
            val targetHaystack = buildString {
                append(nodeTextOrDesc(target))
                append(' ')
                append(target?.viewIdResourceName?.orEmpty())
            }.lowercase(Locale.getDefault())
            val combinedHaystack = listOf(eventHaystack, sourceHaystack, targetHaystack)
                .filter { it.isNotBlank() }
                .joinToString(" ")

            when {
                listOf("map", "snap map").any { combinedHaystack.contains(it) } -> "snap:map"
                listOf("stories", "story").any { combinedHaystack.contains(it) } -> "snap:stories"
                combinedHaystack.contains("spotlight") -> "snap:spotlight"
                combinedHaystack.contains("following") -> "snap:following"
                listOf("camera", "chat", "chats", "capture").any { combinedHaystack.contains(it) } -> "snap:safe"
                rawSourceSurface != null && rawSourceSurface != "snap:safe" -> rawSourceSurface
                else -> {
                    val targetSurface = snapchatBottomTabFromNode(target)
                    when {
                        targetSurface != null && targetSurface != "snap:safe" -> targetSurface
                        else -> rawSourceSurface
                    }
                }
            }
        } finally {
        }
    }

    private fun isRootFromPackage(root: AccessibilityNodeInfo?, pkg: String): Boolean {
        val r = root ?: return false
        val targetPkg = pkg.lowercase(Locale.getDefault())

        val rootPkg = r.packageName?.toString()?.lowercase(Locale.getDefault()).orEmpty()
        if (rootPkg == targetPkg) return true

        val found = findAnyNode(r) { node ->
            node.packageName?.toString()?.lowercase(Locale.getDefault()) == targetPkg
        }
        return try {
            found != null
        } finally {
        }
    }

    private fun clickNodeOrClickableParent(node: AccessibilityNodeInfo?): Boolean {
        var current = node
        var hops = 0
        while (current != null && hops < 6) {
            val next = runCatching { current.parent }.getOrNull()
            try {
                val canClick =
                    current.isClickable ||
                        (current.actionList?.any { it.id == AccessibilityNodeInfo.ACTION_CLICK } == true)
                if (canClick) {
                    val clicked = runCatching { current.performAction(AccessibilityNodeInfo.ACTION_CLICK) }.getOrDefault(false)
                    if (clicked) {
                        return true
                    }
                }
            } finally {
            }
            current = next
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
        val alreadyHomeNode = findAnyNode(r) { n -> isHomeLabel(n) && n.isSelected }
        return try {
            alreadyHomeNode != null
        } finally {
        }
    }

    private fun snapchatImmediateSurfaceHit(surfaceKey: String, event: AccessibilityEvent?): Boolean {
        if (event == null) return false
        val type = event.eventType
        if (type != AccessibilityEvent.TYPE_VIEW_CLICKED && type != AccessibilityEvent.TYPE_VIEW_SELECTED) return false
        val resolved = resolveSnapchatSurfaceFromEvent(event, allowFocused = false) ?: return false
        return resolved == surfaceKey
    }

    private fun maybeInAppBlock(pkg: String, event: AccessibilityEvent? = null) {
        val now = System.currentTimeMillis()
        val graceUntil = inAppGraceUntilByPkg[pkg] ?: 0L
        if (now < graceUntil) return

        // Fast path: ignore apps that do not have in-app surface rules.
        // This avoids expensive root-tree scans and usage reads on every foreground app.
        val supportedInAppPkg =
            pkg == "com.google.android.youtube" ||
                pkg == "com.instagram.android" ||
                pkg == "com.twitter.android" ||
                pkg == "com.snapchat.android"
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
        captureSurfaceHintFromEvent(pkg, event, now)
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
            val blockIgSearchEnabled = prefsBoolProfile(false, BlockingToggleKeys.KEY_BLOCK_IG_SEARCH)
            val blockIgStoriesEnabled = prefsBoolProfile(false, BlockingToggleKeys.KEY_BLOCK_IG_STORIES)
            val blockIgCommentsEnabled = prefsBoolProfile(false, BlockingToggleKeys.KEY_BLOCK_IG_COMMENTS)

            val storiesViewerNow = isInstagramStoriesViewer(root, event)
            val commentsVisibleNow = isInstagramCommentsVisible(root)
            val feedCommentsContext = commentsVisibleNow && homeSelectedNow && !reelsTabSelectedNow && !exploreTabSelectedNow

            // Prioritize explicit surface contexts so Explore/Reels do not falsely trigger
            // while user is in Stories or feed comments.
            if (storiesViewerNow) {
                clearSurfaceEvidence("ig:reels", "ig:explore", "ig:search")
                if (currentSurfacePkg == pkg && (currentSurfaceKey == "ig:reels" || currentSurfaceKey == "ig:explore" || currentSurfaceKey == "ig:search")) {
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
                clearSurfaceEvidence("ig:reels", "ig:explore", "ig:search")
                if (currentSurfacePkg == pkg && (currentSurfaceKey == "ig:reels" || currentSurfaceKey == "ig:explore" || currentSurfaceKey == "ig:search")) {
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
                clearSurfaceEvidence("ig:reels", "ig:explore", "ig:search")
                if (currentSurfacePkg == pkg && (currentSurfaceKey == "ig:reels" || currentSurfaceKey == "ig:explore" || currentSurfaceKey == "ig:search")) {
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
                clearSurfaceEvidence("ig:explore", "ig:search")
            }

            if (stateById == "home" || (stateById == null && homeSelectedNow && !reelsTabSelectedNow && !exploreTabSelectedNow)) {
                clearSurfaceEvidence("ig:reels", "ig:explore", "ig:search")
                if (currentSurfacePkg == pkg && (currentSurfaceKey == "ig:reels" || currentSurfaceKey == "ig:explore" || currentSurfaceKey == "ig:search")) {
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
                (exploreTabSelectedNow && !searchScreenNow) ||
                    (searchInteractiveEvent && exploreEventStrongCue && !searchScreenNow)

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
                    !searchScreenNow &&
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
                "ig:search",
                blockIgSearchEnabled && searchScreenNow && allowSearchDetect && stateById != "reels",
                required = if (allowSearchDetect) 1 else 2
            )
            if (searchHit) {
                currentSurfaceKey = "ig:search"
                currentSurfacePkg = pkg

                val msg = timedBlockMsg(blockIgSearchEnabled, "ig:search", getString(R.string.in_app_surface_search_label))
                if (msg != null) {
                    val appLabel = safeAppLabel(pkg)
                    softBlockSurface(
                        pkg,
                        appLabel,
                        msg.first,
                        msg.second,
                        backCount = 2,
                        deferNavigationUntilAcknowledge = true
                    ); return
                }
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

            if (!reelsHit && !exploreHit && !searchHit && !storiesHit && currentSurfacePkg == pkg) {
                currentSurfaceKey = null
                currentSurfacePkg = null
            }

            if (blockIgCommentsEnabled && isInstagramCommentsVisible(root)) {
                softBlockSurface(pkg, safeAppLabel(pkg), getString(R.string.blocking_comments_blocked_title), getString(R.string.blocking_instagram_comments_blocked_message), backCount = 1); return
            }
        }

        if (pkg == "com.twitter.android") {
            val blockHomeEnabled = prefsBoolProfile(false, BlockingToggleKeys.KEY_BLOCK_X_HOME)
            val blockSearchEnabled = prefsBoolProfile(false, BlockingToggleKeys.KEY_BLOCK_X_SEARCH)
            val blockGrokEnabled = prefsBoolProfile(false, BlockingToggleKeys.KEY_BLOCK_X_GROK)
            val blockNotificationsEnabled = prefsBoolProfile(false, BlockingToggleKeys.KEY_BLOCK_X_NOTIFICATIONS)

            val homeNeedles = listOf("home", "home timeline", "startseite", "for you", "following")
            val homeDetected =
                recentSurfaceHintMatches(pkg, "x:foryou", now) ||
                    isTwitterSurfaceSelected(root, homeNeedles) ||
                    eventTextMatches(event, homeNeedles)
            val homeHit = surfaceConfirmed(
                "x:foryou",
                blockHomeEnabled && homeDetected,
                required = if (recentSurfaceHintMatches(pkg, "x:foryou", now) || eventTextMatches(event, homeNeedles)) 1 else 2
            )
            if (homeHit) {
                currentSurfaceKey = "x:foryou"
                currentSurfacePkg = pkg
                val msg = timedBlockMsg(blockHomeEnabled, "x:foryou", getString(R.string.in_app_surface_home_label))
                if (msg != null) {
                    val appLabel = safeAppLabel(pkg)
                    val redirected = if (!blockNotificationsEnabled) tryNavigateTwitterToNotifications(root) else false
                    softBlockSurface(pkg, appLabel, msg.first, msg.second, backCount = 0, deferNavigationUntilAcknowledge = !redirected, returnToPackageOnClose = true); return
                }
            }

            val searchNeedles = listOf("search", "explore")
            val searchDetected =
                recentSurfaceHintMatches(pkg, "x:search", now) ||
                    isTwitterSurfaceSelected(root, searchNeedles) ||
                    eventTextMatches(event, searchNeedles)
            val searchHit = surfaceConfirmed(
                "x:search",
                blockSearchEnabled && searchDetected,
                required = if (recentSurfaceHintMatches(pkg, "x:search", now) || eventTextMatches(event, searchNeedles)) 1 else 2
            )
            if (searchHit) {
                currentSurfaceKey = "x:search"
                currentSurfacePkg = pkg
                val msg = timedBlockMsg(blockSearchEnabled, "x:search", getString(R.string.in_app_surface_search_label))
                if (msg != null) {
                    val appLabel = safeAppLabel(pkg)
                    val redirected = tryNavigateTwitterToHome(root)
                    softBlockSurface(pkg, appLabel, msg.first, msg.second, backCount = 0, deferNavigationUntilAcknowledge = !redirected, returnToPackageOnClose = true); return
                }
            }

            val grokNeedles = listOf("grok")
            val grokDetected =
                recentSurfaceHintMatches(pkg, "x:grok", now) ||
                    isTwitterSurfaceSelected(root, grokNeedles) ||
                    eventTextMatches(event, grokNeedles)
            val grokHit = surfaceConfirmed(
                "x:grok",
                blockGrokEnabled && grokDetected,
                required = if (recentSurfaceHintMatches(pkg, "x:grok", now) || eventTextMatches(event, grokNeedles)) 1 else 2
            )
            if (grokHit) {
                currentSurfaceKey = "x:grok"
                currentSurfacePkg = pkg
                val msg = timedBlockMsg(blockGrokEnabled, "x:grok", getString(R.string.in_app_surface_grok_label))
                if (msg != null) {
                    val appLabel = safeAppLabel(pkg)
                    val redirected = tryNavigateTwitterToHome(root)
                    softBlockSurface(pkg, appLabel, msg.first, msg.second, backCount = 0, deferNavigationUntilAcknowledge = !redirected, returnToPackageOnClose = true); return
                }
            }

            val notificationNeedles = listOf("notifications", "notification")
            val notificationsDetected =
                recentSurfaceHintMatches(pkg, "x:notifications", now) ||
                    isTwitterSurfaceSelected(root, notificationNeedles) ||
                    eventTextMatches(event, notificationNeedles)
            val notificationsHit = surfaceConfirmed(
                "x:notifications",
                blockNotificationsEnabled && notificationsDetected,
                required = if (recentSurfaceHintMatches(pkg, "x:notifications", now) || eventTextMatches(event, notificationNeedles)) 1 else 2
            )
            if (notificationsHit) {
                currentSurfaceKey = "x:notifications"
                currentSurfacePkg = pkg
                val msg = timedBlockMsg(blockNotificationsEnabled, "x:notifications", getString(R.string.in_app_surface_notifications_label))
                if (msg != null) {
                    val appLabel = safeAppLabel(pkg)
                    val redirected = tryNavigateTwitterToHome(root)
                    softBlockSurface(pkg, appLabel, msg.first, msg.second, backCount = 0, deferNavigationUntilAcknowledge = !redirected, returnToPackageOnClose = true); return
                }
            }

            if (!homeHit && !searchHit && !grokHit && !notificationsHit && currentSurfacePkg == pkg) {
                currentSurfaceKey = null
                currentSurfacePkg = null
            }
        }

        if (pkg == "com.snapchat.android") {
            val blockSnapMapEnabled = prefsBoolProfile(false, BlockingToggleKeys.KEY_BLOCK_SNAP_MAP)
            val blockSnapStoriesEnabled = prefsBoolProfile(false, BlockingToggleKeys.KEY_BLOCK_SNAP_STORIES)
            val blockSnapSpotlightEnabled = prefsBoolProfile(false, BlockingToggleKeys.KEY_BLOCK_SNAP_SPOTLIGHT)
            val blockSnapFollowingEnabled = prefsBoolProfile(false, BlockingToggleKeys.KEY_BLOCK_SNAP_FOLLOWING)

            val snapTappedSurface = resolveSnapchatSurfaceFromEvent(event, allowFocused = false)
            val snapEnteredAt = appEnteredAtByPkg[pkg] ?: 0L
            val snapSettled = snapEnteredAt == 0L || (now - snapEnteredAt) >= 350L
            val snapSelectedSurface = if (snapSettled) detectSnapchatSelectedSurface(root) else null

            val mapImmediate = snapchatImmediateSurfaceHit("snap:map", event)
            val mapDetected =
                mapImmediate ||
                    recentSurfaceHintMatches(pkg, "snap:map", now) ||
                    snapTappedSurface == "snap:map" ||
                    snapSelectedSurface == "snap:map" ||
                    (snapSettled && hasSelectedLabelInPackage(root, listOf("map", "snap map"), pkg))
            val mapHit = surfaceConfirmed(
                "snap:map",
                blockSnapMapEnabled && mapDetected,
                required = if (recentSurfaceHintMatches(pkg, "snap:map", now) || snapTappedSurface == "snap:map" || snapSelectedSurface == "snap:map" || snapchatImmediateSurfaceHit("snap:map", event)) 1 else 2
            )
            if (mapHit) {
                currentSurfaceKey = "snap:map"
                currentSurfacePkg = pkg
                val msg = timedBlockMsg(blockSnapMapEnabled, "snap:map", getString(R.string.in_app_surface_map_label))
                if (msg != null) {
                    val appLabel = safeAppLabel(pkg)
                    val redirected = tryNavigateSnapchatToCamera(root)
                    softBlockSurface(pkg, appLabel, msg.first, msg.second, backCount = 0, deferNavigationUntilAcknowledge = !redirected, returnToPackageOnClose = true); return
                }
            }

            val storiesImmediate = snapchatImmediateSurfaceHit("snap:stories", event)
            val storiesDetected =
                storiesImmediate ||
                    recentSurfaceHintMatches(pkg, "snap:stories", now) ||
                    snapTappedSurface == "snap:stories" ||
                    snapSelectedSurface == "snap:stories" ||
                    (snapSettled && hasSelectedLabelInPackage(root, listOf("stories"), pkg))
            val storiesHit = surfaceConfirmed(
                "snap:stories",
                blockSnapStoriesEnabled && storiesDetected,
                required = if (recentSurfaceHintMatches(pkg, "snap:stories", now) || snapTappedSurface == "snap:stories" || snapSelectedSurface == "snap:stories" || snapchatImmediateSurfaceHit("snap:stories", event)) 1 else 2
            )
            if (storiesHit) {
                currentSurfaceKey = "snap:stories"
                currentSurfacePkg = pkg
                val msg = timedBlockMsg(blockSnapStoriesEnabled, "snap:stories", getString(R.string.in_app_surface_stories_label))
                if (msg != null) {
                    val appLabel = safeAppLabel(pkg)
                    val redirected = tryNavigateSnapchatToCamera(root)
                    softBlockSurface(pkg, appLabel, msg.first, msg.second, backCount = 0, deferNavigationUntilAcknowledge = !redirected, returnToPackageOnClose = true); return
                }
            }

            val spotlightImmediate = snapchatImmediateSurfaceHit("snap:spotlight", event)
            val spotlightDetected =
                spotlightImmediate ||
                    recentSurfaceHintMatches(pkg, "snap:spotlight", now) ||
                    snapTappedSurface == "snap:spotlight" ||
                    snapSelectedSurface == "snap:spotlight" ||
                    (snapSettled && hasSelectedLabelInPackage(root, listOf("spotlight"), pkg))
            val spotlightHit = surfaceConfirmed(
                "snap:spotlight",
                blockSnapSpotlightEnabled && spotlightDetected,
                required = if (recentSurfaceHintMatches(pkg, "snap:spotlight", now) || snapTappedSurface == "snap:spotlight" || snapSelectedSurface == "snap:spotlight" || snapchatImmediateSurfaceHit("snap:spotlight", event)) 1 else 2
            )
            if (spotlightHit) {
                currentSurfaceKey = "snap:spotlight"
                currentSurfacePkg = pkg
                val msg = timedBlockMsg(blockSnapSpotlightEnabled, "snap:spotlight", getString(R.string.in_app_surface_spotlight_label))
                if (msg != null) {
                    val appLabel = safeAppLabel(pkg)
                    val redirected = tryNavigateSnapchatToCamera(root)
                    softBlockSurface(pkg, appLabel, msg.first, msg.second, backCount = 0, deferNavigationUntilAcknowledge = !redirected, returnToPackageOnClose = true); return
                }
            }

            val followingImmediate = snapchatImmediateSurfaceHit("snap:following", event)
            val followingDetected =
                followingImmediate ||
                    recentSurfaceHintMatches(pkg, "snap:following", now) ||
                    snapTappedSurface == "snap:following" ||
                    snapSelectedSurface == "snap:following" ||
                    (snapSettled && hasSelectedLabelInPackage(root, listOf("following"), pkg))
            val followingHit = surfaceConfirmed(
                "snap:following",
                blockSnapFollowingEnabled && followingDetected,
                required = if (recentSurfaceHintMatches(pkg, "snap:following", now) || snapTappedSurface == "snap:following" || snapSelectedSurface == "snap:following" || snapchatImmediateSurfaceHit("snap:following", event)) 1 else 2
            )
            if (followingHit) {
                currentSurfaceKey = "snap:following"
                currentSurfacePkg = pkg
                val msg = timedBlockMsg(blockSnapFollowingEnabled, "snap:following", getString(R.string.in_app_surface_following_label))
                if (msg != null) {
                    val appLabel = safeAppLabel(pkg)
                    val redirected = tryNavigateSnapchatToCamera(root)
                    softBlockSurface(pkg, appLabel, msg.first, msg.second, backCount = 0, deferNavigationUntilAcknowledge = !redirected, returnToPackageOnClose = true); return
                }
            }

            val activeSnapSurface =
                mapHit || storiesHit || spotlightHit || followingHit
            if (!activeSnapSurface && currentSurfacePkg == pkg &&
                (currentSurfaceKey == "snap:map" || currentSurfaceKey == "snap:stories" || currentSurfaceKey == "snap:spotlight" || currentSurfaceKey == "snap:following")) {
                currentSurfaceKey = null
                currentSurfacePkg = null
            }
        }
    }

    private fun rememberSurfaceHint(pkg: String, key: String, now: Long = System.currentTimeMillis()) {
        inAppSurfaceEvidence.rememberSurfaceHint(pkg, key, now)
    }

    private fun recentSurfaceHintMatches(pkg: String, key: String, now: Long = System.currentTimeMillis()): Boolean =
        inAppSurfaceEvidence.recentSurfaceHintMatches(pkg, key, SURFACE_HINT_TTL_MS, now)

    private fun clearSurfaceHintForPackage(pkg: String) {
        inAppSurfaceEvidence.clearSurfaceHintForPackage(pkg)
    }

    private fun captureSurfaceHintFromEvent(pkg: String, event: AccessibilityEvent?, now: Long = System.currentTimeMillis()) {
        if (event == null) return
        val type = event.eventType
        val interactive =
            type == AccessibilityEvent.TYPE_VIEW_CLICKED ||
                type == AccessibilityEvent.TYPE_VIEW_SELECTED ||
                type == AccessibilityEvent.TYPE_VIEW_FOCUSED
        if (!interactive) return

        when (pkg) {
            "com.twitter.android" -> {
                when {
                    eventOrSourceMatches(event, listOf("home", "home timeline", "startseite", "for you", "following")) -> rememberSurfaceHint(pkg, "x:foryou", now)
                    eventOrSourceMatches(event, listOf("search", "explore")) -> rememberSurfaceHint(pkg, "x:search", now)
                    eventOrSourceMatches(event, listOf("grok")) -> rememberSurfaceHint(pkg, "x:grok", now)
                    eventOrSourceMatches(event, listOf("notifications", "notification")) -> rememberSurfaceHint(pkg, "x:notifications", now)
                }
            }
            "com.snapchat.android" -> {
                when (resolveSnapchatSurfaceFromEvent(event, allowFocused = false)) {
                    "snap:map" -> rememberSurfaceHint(pkg, "snap:map", now)
                    "snap:stories" -> rememberSurfaceHint(pkg, "snap:stories", now)
                    "snap:spotlight" -> rememberSurfaceHint(pkg, "snap:spotlight", now)
                    "snap:following" -> rememberSurfaceHint(pkg, "snap:following", now)
                    "snap:safe" -> clearSurfaceHintForPackage(pkg)
                }
            }
        }
    }

    private fun clearSurfaceEvidence(vararg keys: String) {
        inAppSurfaceEvidence.clearSurfaceEvidence(*keys)
        for (key in keys) {
            if (currentSurfaceKey == key) {
                currentSurfaceKey = null
                currentSurfacePkg = null
            }
        }
    }

    private fun clearSurfaceEvidenceForPackage(pkg: String) {
        clearSurfaceEvidence(*inAppSurfaceEvidence.surfaceKeysForPackage(pkg))
        clearSurfaceHintForPackage(pkg)
    }

    private fun surfaceConfirmed(key: String, detected: Boolean, required: Int = 2): Boolean {
        if (!detected) {
            clearSurfaceEvidence(key)
            return false
        }
        return inAppSurfaceEvidence.surfaceConfirmed(
            key = key,
            required = required,
            confirmMs = SURFACE_CONFIRM_MS,
            now = System.currentTimeMillis()
        )
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
        // Do not treat any visible "Shorts" text as the Shorts surface.
        // YouTube keeps the bottom navigation visible on unrelated screens such as Subscriptions and the profile/You tab, so a global node-text scan causes false positives there. 
        // The Shorts surface should be detected only when the YouTube Shorts tab/surface itself is selected or the selected event source is the Shorts tab.
        return hasSelectedLabelInPackage(root, listOf("shorts"), "com.google.android.youtube") || isSelectedYouTubeShortsEvent(event)
    }

    private fun isSelectedYouTubeShortsEvent(event: AccessibilityEvent?): Boolean {
        if (event == null) return false
        val source = runCatching { event.source }.getOrNull() ?: return false

        return try {
            val sourcePkg = source.packageName?.toString().orEmpty()
            if (sourcePkg != "com.google.android.youtube") return false
            if (!source.isSelected) return false

            val text = nodeTextOrDesc(source)
            text.contains("shorts")
        } finally {
        }
    }

    private fun isYouTubeSearchScreen(root: AccessibilityNodeInfo, event: AccessibilityEvent? = null): Boolean {
        return nodeTextMatches(root, listOf("search youtube", "youtube durchsuchen", "search")) ||
            eventTextMatches(event, listOf("search", "youtube"))
    }

    private fun isYouTubeCommentsVisible(root: AccessibilityNodeInfo): Boolean {
        return nodeTextMatches(root, listOf("comments", "kommentare", "add a comment", "sort comments"))
    }

    private fun bounceHomeAndKill(pkg: String) {
        blockLaunchController.bounceHomeAndKill(pkg)
    }

}
