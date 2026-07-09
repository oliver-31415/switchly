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
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
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
import at.saltyy.switchly.data.prefs.InAppRuleStore
import at.saltyy.switchly.data.prefs.LimitReachedStore
import at.saltyy.switchly.data.prefs.LastBlockReasonStore
import at.saltyy.switchly.data.prefs.OpenCountStore
import at.saltyy.switchly.data.prefs.ProfileStore
import at.saltyy.switchly.data.prefs.ProfileRuleModeStore
import at.saltyy.switchly.data.prefs.ProfileUsageStore
import at.saltyy.switchly.data.prefs.ScheduleStore
import at.saltyy.switchly.data.prefs.SurfaceLimitStore
import at.saltyy.switchly.data.prefs.SurfaceUsageStore
import at.saltyy.switchly.data.prefs.SwitchModeStore
import at.saltyy.switchly.data.prefs.TempAllowStore
import at.saltyy.switchly.data.prefs.UsageLimitStore
import at.saltyy.switchly.data.prefs.UsageLimitResetStore
import at.saltyy.switchly.data.prefs.UsageLimitSessionRuntimeStore
import at.saltyy.switchly.data.prefs.UsageStore
import at.saltyy.switchly.data.prefs.WebUsageStore
import at.saltyy.switchly.feature.blocker.BlockerActivity
import at.saltyy.switchly.platform.receiver.schedule.ScheduleReceiver
import at.saltyy.switchly.util.AppBlockSafety
import at.saltyy.switchly.util.AppUsageToday
import at.saltyy.switchly.util.PackageLaunchIntentCompat
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
    private var lastYouTubeRetryProbeAt: Long = 0L
    private var lastYouTubeShortsCloseAt: Long = 0L
    private var lastYouTubeFloatingPlayerBlockAt: Long = 0L

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
    // Snapshot system + internal usage at app entry, then estimate "live" system usage by adding internal deltas since entry.
    private val usageInternalAtEnterByPkg = HashMap<String, Long>()
    private val usageSystemAtEnterByPkg = HashMap<String, Long>()

    // Optional time-limit reset mode: per active Switchly/profile session.
    // Existing limits stay daily by default.
    // Session counters are intentionally runtime-scoped: they reset when Switchly/profile enters a new active session, and they do not touch daily stats.
    private var activeLimitSessionProfile: String? = null
    private var activeLimitSessionGeneration: Long = -1L
    private var activeLimitSessionStartedAt: Long = 0L
    private val sessionLimitUsageMsByKey = HashMap<String, Long>()
    private val sessionLimitReachedKeys = HashSet<String>()

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
    // Apps such as Gmail and Instagram can emit multiple ACTIVITY_RESUMED events while the user is still inside the same app, so a short cooldown alone would incorrectly count one real open many times.
    private val OPEN_COUNT_COOLDOWN_MS = 800L
    private val BLOCK_SHOWN_COOLDOWN_MS = 800L
    private val HOME_BOUNCE_COOLDOWN_MS = 900L
    private var lastHomeBounceAt: Long = 0L
    private val SURFACE_CONFIRM_MS = 850L
    private val SURFACE_HINT_TTL_MS = 4_500L

    // YouTube surface labels
    private val YT_HOME_LABELS = listOf("home", "startseite")
    private val YT_SHORTS_LABELS = listOf("shorts")
    private val YT_YOU_LABELS = listOf("you", "du", "library", "bibliothek")

    private val YT_SHORTS_PLAYER_HINT_LABELS = listOf(
        "like", "dislike", "share", "teilen", "remix",
        "use this sound", "sound",
        "subscribe", "abonnieren",
        "comments", "kommentare"
    )
    private val YT_COMMENTS_LABELS = listOf(
        "comments", "kommentare",
        "add a comment", "sort comments", "top comments", "write a comment"
    )
    private val YT_SUBSCRIPTIONS_LABELS = listOf(
        "subscriptions", "subscription", "subscriptions tab",
        "my subscriptions", "subscriptions button", "selected subscriptions",
        "subscriptions, tab", "subscription, tab",
        "abos", "abo", "abonnements", "abonnement",
        "abo, tab", "abos, tab"
    )
    private val YT_SEARCH_LABELS = listOf(
        "search youtube", "youtube durchsuchen", "youtube suchen",
        "search", "suche",
        "search button", "search tab", "open search"
    )
    private val YT_SEARCH_SCREEN_LABELS = listOf(
        "search youtube", "youtube durchsuchen", "youtube suchen",
        "search history", "suchverlauf"
    )

    private val YT_MINI_PLAYER_NAME_LABELS = listOf("miniplayer", "mini player", "mini-player")
    private val YT_MINI_PLAYER_CLOSE_LABELS = listOf(
        "close player", "close mini player", "close miniplayer",
        "dismiss player", "dismiss mini player",
        "close video", "video schließen",
        "player schließen", "player ausblenden",
        "miniplayer schließen", "mini player schließen", "miniplayer ausblenden",
        "schließen"
    )
    private val YT_MINI_PLAYER_PLAY_PAUSE_LABELS = listOf(
        "play", "resume", "pause", "paused", "pause video",
        "abspielen", "fortsetzen", "pausieren",
        "video pausieren", "wiedergabe pausieren", "wiedergabe fortsetzen"
    )
    private val YT_MINI_PLAYER_HINT_LABELS = YT_MINI_PLAYER_NAME_LABELS + listOf(
        "close player", "close mini player", "close miniplayer",
        "dismiss player", "player schließen", "miniplayer schließen"
    )

    private val YT_REAL_PIP_LABELS = listOf("picture-in-picture", "picture in picture", "bild-im-bild", "pip")
    private val YT_PIP_LABELS = YT_REAL_PIP_LABELS + YT_MINI_PLAYER_NAME_LABELS
    private val YT_LOW_SIGNAL_LABELS = listOf("shorts", "you", "library", "picture", "pip", "miniplayer")

    // Instagram surface labels
    private val IG_HOME_LABELS = listOf("home", "startseite")
    private val IG_REELS_LABELS = listOf("reels")
    private val IG_EXPLORE_LABELS = listOf("search", "suche", "discover", "entdecken", "explore")
    private val IG_REELS_STRONG_EVENT_LABELS = listOf("watch more reels", "send reel")
    private val IG_LOW_SIGNAL_LABELS = listOf("reels", "explore", "search", "story", "comment")

    private val IG_STORIES_VIEWER_LABELS = listOf(
        "send message", "nachricht senden",
        "reply to story", "antworten",
        "viewers", "zuschauer"
    )
    private val IG_STORIES_EVENT_LABELS = listOf("reply to story", "send message", "nachricht senden")
    private val IG_COMMENTS_LABELS = listOf(
        "comments", "kommentare",
        "add a comment", "kommentar hinzufügen",
        "view all comments"
    )

    private val IG_MESSAGES_EVENT_LABELS = listOf(
        "messages", "nachrichten", "chats", "direct", "inbox", "posteingang",
        "message requests", "nachrichtenanfragen",
        "search messages", "nachrichten suchen",
        "new message", "neue nachricht"
    )
    private val IG_MESSAGES_SCREEN_LABELS = listOf(
        "message requests", "nachrichtenanfragen",
        "search messages", "nachrichten suchen",
        "new message", "neue nachricht",
        "primary", "general"
    )

    private val IG_PROFILE_SELECTED_LABELS = listOf("profile", "profil")
    private val IG_PROFILE_CONTEXT_LABELS = listOf(
        "edit profile", "profil bearbeiten",
        "professional dashboard", "dashboard für professionelle"
    )
    private val IG_PROFILE_EVENT_LABELS = listOf(
        "profile tab", "profil tab",
        "edit profile", "profil bearbeiten"
    )

    // X surface labels
    private val X_HOME_LABELS = listOf("home", "home timeline", "startseite", "for you", "following")
    private val X_HOME_NAV_LABELS = listOf("home", "home timeline", "startseite")
    private val X_SEARCH_LABELS = listOf("search", "explore")
    private val X_GROK_LABELS = listOf("grok")
    private val X_NOTIFICATIONS_LABELS = listOf("notifications", "notification")
    private val X_NOTIFICATIONS_NAV_LABELS = listOf("notifications", "notification", "benachrichtigungen")
    private val X_LOW_SIGNAL_LABELS = listOf(
        "for you", "following",
        "search", "explore", "grok",
        "notification", "notifications", "home"
    )

    // Snapchat surface labels
    private val SNAP_MAP_LABELS = listOf("map", "snap map", "karte")
    private val SNAP_SPOTLIGHT_LABELS = listOf("spotlight")
    private val SNAP_FOLLOWING_LABELS = listOf("following", "folgen")
    private val SNAP_CAMERA_LABELS = listOf("camera", "capture")
    private val SNAP_CHAT_LABELS = listOf("chat", "chats")
    private val SNAP_SAFE_LABELS = listOf("camera", "capture", "chat", "chats", "kamera")
    private val SNAP_LOW_SIGNAL_LABELS = listOf("map", "stories", "spotlight", "following", "chat", "camera")

    private val SNAP_STORIES_LABELS = listOf(
        "stories", "story", "storys",
        "geschichte", "geschichten",
        "discover", "subscriptions",
        "friends' stories", "friend stories", "publisher stories",
        "reply to story", "story reply"
    )
    private val SNAP_STORY_VIEWER_LABELS = listOf(
        "reply to story", "story reply", "reply to snap", "snap reply",
        "watch story",
        "next story", "previous story", "next snap", "previous snap",
        "view next snap", "view previous snap", "tap to view next snap",
        "tap to skip", "hold to pause", "swipe up",
        "geschichte ansehen", "auf story antworten"
    )
    private val SNAP_STORY_REPLY_LABELS = listOf(
        "send a chat", "send chat", "send message", "reply",
        "antworten", "nachricht senden"
    )

    private val INAPP_POST_BLOCK_GRACE_MS = 3_500L
    private val SNAP_POST_BLOCK_GRACE_MS = 450L
    private val YT_MINI_PLAYER_CLEANUP_GRACE_MS = 650L
    private val INSTA_REELS_REENTRY_GUARD_MS = 1_800L
    private val INSTA_EXPLORE_REENTRY_GUARD_MS = 2_200L
    private val INAPP_ENTRY_SETTLE_MS = 1_400L
    private val YT_SHORTS_REENTRY_GUARD_MS = 5_000L
    private val PIP_KILL_COOLDOWN_MS = 8_000L

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
    private val lastPipKillAtByPkg = HashMap<String, Long>()

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
        return when {
            pkg == "com.google.android.youtube" ->
                inAppSurfaceRuleEnabled(BlockingToggleKeys.KEY_BLOCK_YT_SHORTS) ||
                    inAppSurfaceRuleEnabled(BlockingToggleKeys.KEY_BLOCK_YT_SUBSCRIPTIONS) ||
                    inAppSurfaceRuleEnabled(BlockingToggleKeys.KEY_BLOCK_YT_YOU) ||
                    inAppSurfaceRuleEnabled(BlockingToggleKeys.KEY_BLOCK_YT_MINI_PLAYER) ||
                    inAppSurfaceRuleEnabled(BlockingToggleKeys.KEY_BLOCK_YT_PIP)

            pkg == "com.instagram.android" ->
                inAppSurfaceRuleEnabled(BlockingToggleKeys.KEY_BLOCK_IG_REELS) ||
                    inAppSurfaceRuleEnabled(BlockingToggleKeys.KEY_BLOCK_IG_EXPLORE) ||
                    inAppSurfaceRuleEnabled(BlockingToggleKeys.KEY_BLOCK_IG_SEARCH) ||
                    inAppSurfaceRuleEnabled(BlockingToggleKeys.KEY_BLOCK_IG_STORIES) ||
                    inAppSurfaceRuleEnabled(BlockingToggleKeys.KEY_BLOCK_IG_COMMENTS)

            pkg == "com.twitter.android" ->
                inAppSurfaceRuleEnabled(BlockingToggleKeys.KEY_BLOCK_X_HOME) ||
                    inAppSurfaceRuleEnabled(BlockingToggleKeys.KEY_BLOCK_X_SEARCH) ||
                    inAppSurfaceRuleEnabled(BlockingToggleKeys.KEY_BLOCK_X_GROK) ||
                    inAppSurfaceRuleEnabled(BlockingToggleKeys.KEY_BLOCK_X_NOTIFICATIONS)

            pkg == "com.snapchat.android" ->
                inAppSurfaceRuleEnabled(BlockingToggleKeys.KEY_BLOCK_SNAP_MAP) ||
                    inAppSurfaceRuleEnabled(BlockingToggleKeys.KEY_BLOCK_SNAP_STORIES) ||
                    inAppSurfaceRuleEnabled(BlockingToggleKeys.KEY_BLOCK_SNAP_SPOTLIGHT) ||
                    inAppSurfaceRuleEnabled(BlockingToggleKeys.KEY_BLOCK_SNAP_FOLLOWING)

            else -> false
        }
    }

    private fun lowSignalNeedlesForPkg(pkg: String): List<String> {
        return when {
            pkg == "com.google.android.youtube" -> YT_LOW_SIGNAL_LABELS
            pkg == "com.instagram.android" -> IG_LOW_SIGNAL_LABELS
            pkg == "com.twitter.android" -> X_LOW_SIGNAL_LABELS
            pkg == "com.snapchat.android" -> SNAP_LOW_SIGNAL_LABELS
            else -> emptyList()
        }
    }

    private fun shouldSkipLowSignalInApp(pkg: String, event: AccessibilityEvent, now: Long): Boolean {
        // Reliability-first: these apps often emit sparse/noisy class/text signals on surface changes.
        // Skipping probes here can miss legitimate blocks (YouTube Shorts).
        if (pkg == "com.google.android.youtube" ||
            pkg == "com.instagram.android" ||
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
        BlockingRuntime.markAccessibilityEvent(this, pkg, event?.eventType ?: 0)

        if (maybeBlockSwitchlySettingsBypassSurface(pkg, event)) return
        if (maybeBlockUninstallFrictionSurface(pkg, event)) return

        if (pkg == "com.google.android.youtube" && maybeBlockYouTubeHomeShortsBeforeDedupe(event)) return

        val pendingYouTubeHomeRedirectFlags =
            if (pkg == "com.google.android.youtube") BlockerActivity.consumePendingYouTubeHomeRedirectFlagsFor(pkg) else 0
        if (pendingYouTubeHomeRedirectFlags != 0) {
            val ackNow = System.currentTimeMillis()
            val cleanupShorts =
                (pendingYouTubeHomeRedirectFlags and BlockerActivity.FLAG_YOUTUBE_CLEANUP_SHORTS) != 0
            val cleanupMini =
                (pendingYouTubeHomeRedirectFlags and BlockerActivity.FLAG_YOUTUBE_CLEANUP_MINI) != 0
            inAppGraceUntilByPkg[pkg] = ackNow + maxOf(INAPP_POST_BLOCK_GRACE_MS, YT_SHORTS_REENTRY_GUARD_MS)
            surfaceBlockGuardUntil["$pkg|yt:shorts"] = ackNow + YT_SHORTS_REENTRY_GUARD_MS
            surfaceBlockGuardUntil["$pkg|yt:subscriptions"] = ackNow + YT_SHORTS_REENTRY_GUARD_MS
            clearSurfaceEvidenceForPackage(pkg)
            clearSurfaceHintForPackage(pkg)
            if (currentSurfacePkg == pkg) {
                currentSurfaceKey = null
                currentSurfacePkg = null
            }
            appendBlockingLog(
                category = "yt_home_redirect",
                key = "post-ack-youtube-home|$pkg",
                message = "reason=post_ack pkg=$pkg cleanupShorts=$cleanupShorts cleanupMini=$cleanupMini"
            )
            runCatching { blockLaunchController.pauseActiveMediaPlayback() }
            val postAckRoot = youtubeCurrentRoot(event)
            if (cleanupMini) {
                dismissYouTubeMiniPlayer("post_ack_before_home")
            }
            if (cleanupShorts) {
                cleanupYouTubeShortsAfterAck("post_ack")
                handler.postDelayed({ cleanupYouTubeShortsAfterAck("post_ack_retry_300") }, 300L)
                handler.postDelayed({ cleanupYouTubeShortsAfterAck("post_ack_retry_750") }, 750L)
                handler.postDelayed({ cleanupYouTubeShortsAfterAck("post_ack_retry_1300") }, 1_300L)
            } else if (cleanupMini) {
                redirectYouTubeToHome("post_ack", postAckRoot, allowLaunchFallback = false, closeMiniPlayer = true)
                handler.postDelayed({ dismissYouTubeMiniPlayer("post_ack_retry_300") }, 300L)
                handler.postDelayed({ dismissYouTubeMiniPlayer("post_ack_retry_750") }, 750L)
            } else {
                redirectYouTubeToHome("post_ack", postAckRoot, allowLaunchFallback = false, closeMiniPlayer = false)
            }
            return
        }

        val pendingBackCount = BlockerActivity.consumePendingBackNavigationFor(pkg)
        if (pendingBackCount > 0) {
            val ackNow = System.currentTimeMillis()

            // Prevent immediate re-detection loops while navigating back to app home.
            inAppGraceUntilByPkg[pkg] = ackNow + INAPP_POST_BLOCK_GRACE_MS
            clearSurfaceEvidenceForPackage(pkg)
            clearSurfaceHintForPackage(pkg)
            if (currentSurfacePkg == pkg) {
                currentSurfaceKey = null
                currentSurfacePkg = null
            }

            var effectiveBackCount = pendingBackCount
            if (pkg == "com.instagram.android") {
                val rootNow = currentRoot(event)
                val stateNow = rootNow?.let { instagramState(it, event) }
                val searchNow = rootNow?.let { isInstagramSearchScreen(it, event) } == true
                val homeSelected = rootNow?.let { hasSelectedLabel(it, IG_HOME_LABELS) } == true

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
                val rootNow = youtubeCurrentRoot(event)
                val ytWindowReady = isRootFromPackage(rootNow, ytPkg)
                val shortsNow = ytWindowReady && (rootNow?.let { isYouTubeShortsScreen(it, event) || isLikelyYouTubeShortsPlayer(it, event) } == true)
                val homeFeedShortsNow = ytWindowReady && rootNow?.let { isYouTubeHomeFeedShortsPlayer(it, event) } == true
                val homeSelected =
                    if (ytWindowReady) {
                        rootNow?.let {
                            hasSelectedLabelInPackage(
                                it,
                                YT_HOME_LABELS,
                                ytPkg
                            )
                        } == true
                    } else {
                        false
                    }

                // Never use BACK for deferred YouTube surface blocks: on some builds Shorts is a root tab and BACK minimizes YouTube to launcher.
                // Prefer explicit in-app Home tab navigation. Pause first to reduce YouTube mini-player leftovers.
                effectiveBackCount = 0
                runCatching { blockLaunchController.pauseActiveMediaPlayback() }
                if (homeFeedShortsNow) {
                    closeYouTubeShortsPlayer("pending_back", rootNow)
                }

                // If YouTube root is not active yet, wait briefly for task restore first.
                // Launch fallback only on late retries to avoid launcher flashes.
                val movedToHome = when {
                    !ytWindowReady -> false
                    homeSelected && !homeFeedShortsNow -> true
                    else -> tryNavigateYouTubeToAllowedSafeSurface(rootNow, "pending_back")
                }

                // Guard against immediate re-trigger while tab transition settles.
                surfaceBlockGuardUntil["$pkg|yt:shorts"] = ackNow + 1_400L

                if (!movedToHome) {
                    // Some YouTube builds need an extra frame before bottom-nav nodes become clickable.
                    val retryDelays = longArrayOf(120L, 260L, 420L, 640L, 860L)
                    for (delay in retryDelays) {
                        handler.postDelayed({
                            runCatching {
                                val rootRetry = youtubeCurrentRoot()

                                // If the active root is not YouTube yet (e.g. launcher while PiP is visible), bring YouTube task to foreground and wait for the next retry.
                                if (!isRootFromPackage(rootRetry, ytPkg)) {
                                    if (delay >= 860L) {
                                        launchYouTubeHomeFallback()
                                    }
                                    return@runCatching
                                }

                                tryNavigateYouTubeToAllowedSafeSurface(rootRetry, "pending_back_retry_$delay")
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
            BlockingRuntime.markForegroundPackage(this, pkg, "accessibility_transition")
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
            if (pkg == "com.google.android.youtube" ||
                type == AccessibilityEvent.TYPE_WINDOWS_CHANGED ||
                type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                maybeBlockYouTubeFloatingPlayer(event, "transition")
                if (pkg == "com.google.android.youtube") {
                    scheduleYouTubeContentRetryProbe(type, now)
                }
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
            if (pkg == "com.google.android.youtube") {
                scheduleYouTubeContentRetryProbe(type, now)
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

    private fun scheduleYouTubeContentRetryProbe(eventType: Int, now: Long) {
        val relevant =
            eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
                eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED ||
                eventType == AccessibilityEvent.TYPE_VIEW_CLICKED ||
                eventType == AccessibilityEvent.TYPE_VIEW_SELECTED ||
                eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED
        if (!relevant) return
        if (now - lastYouTubeRetryProbeAt < 260L) return
        lastYouTubeRetryProbeAt = now

        val pkg = "com.google.android.youtube"
        longArrayOf(50L, 100L, 150L).forEach { delay ->
            handler.postDelayed({
                runCatching {
                    val retryNow = System.currentTimeMillis()
                    if (surfaceGuardActive(pkg, "yt:shorts", retryNow)) {
                        return@runCatching
                    }
                    val root = youtubeCurrentRoot()
                    if (root != null && isYouTubeShortsScreen(root)) {
                        rememberSurfaceHint(pkg, "yt:shorts", retryNow)
                    }
                    if (root != null || findYouTubeWindowRoot() != null) {
                        maybeBlockNow(pkg, null)
                        maybeBlockYouTubeFloatingPlayer(null, "retry_$delay")
                    }
                }
            }, delay)
        }
    }

    private fun usageTick() {
        val now = System.currentTimeMillis()

        // Some apps produce very few accessibility events.
        // To avoid tracking the wrong foreground package (which would break real-time limits), prefer the active window package when available.
        val rootPkg = runCatching { rootInActiveWindow?.packageName?.toString() }.getOrNull()
        if (!rootPkg.isNullOrBlank() && rootPkg != packageName && rootPkg != currentTopPkg) {
            currentTopPkg = rootPkg
            BlockingRuntime.markForegroundPackage(this, rootPkg, "active_window_root")
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
        if (!pm.isInteractive) {
            clearActiveLimitSession()
            return
        }
        if (km?.isKeyguardLocked == true) {
            clearActiveLimitSession()
            return
        }
        if (!SwitchModeStore.isEnabled(this)) {
            clearActiveLimitSession()
            return
        }
        if (EmergencyBypassStore.isActive(this)) {
            clearActiveLimitSession()
            return
        }

        val nowForCache = now
        val profile = getCurrentProfileCached(nowForCache)?.takeIf { it.isNotBlank() } ?: run {
            clearActiveLimitSession()
            return
        }
        ensureActiveLimitSession(profile, nowForCache)

        if (TempAllowStore.isAllowed(this, pkg)) return

        // Track app usage for Today statistics even when no daily limit is configured.
        // Limit enforcement still happens below and continues to depend on the active profile + limit.
        UsageStore.addUsageMsToday(this, pkg, delta)

        // App limits are profile-specific.
        // Keep a separate per-profile counter so switching from a hard-blocked profile into a limited profile does not immediately inherit stale/over-counted usage from a different profile.
        ProfileUsageStore.addUsageMsToday(this, profile, pkg, delta)
        if (UsageLimitResetStore.isSessionMode(this, profile, pkg)) {
            addSessionLimitUsageMs(profile, pkg, delta)
        }

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

        val safeProfile = profile

        // Usage limits should work even if the app isn't in the "blocked apps" list.
        // (Users can set a daily limit without hard-blocking the app.)
        val limitMin = getUsageLimitCached(safeProfile, pkg, nowForCache)
        if (limitMin <= 0) return // hard block -> handled by event driven blocker

        // Enforce app limits using usage accumulated while the current profile is active.
        // The overall usage dashboard may show a different value because it is intentionally not profile-scoped.
        val usedMs = getEnforcedLimitUsageMs(safeProfile, pkg)
        val limitMs = limitMin * 60_000L
        val sessionMode = UsageLimitResetStore.isSessionMode(this, safeProfile, pkg)
        if (sessionMode) {
            publishSessionLimitState(safeProfile, pkg, limitMin, usedMs, reached = usedMs >= limitMs)
        }
        if (usedMs >= limitMs) {
            appendBlockingLog(
                category = "app_limit_reached",
                key = "app-limit-reached|$safeProfile|$pkg",
                message = "profile=$safeProfile pkg=$pkg limitMin=$limitMin usageMs=$usedMs limitMs=$limitMs",
                throttleMs = 2_000L
            )
            markLimitReached(safeProfile, pkg)
            if (sessionMode) {
                publishSessionLimitState(safeProfile, pkg, limitMin, usedMs, reached = true)
            }
            maybeBlockNow(pkg, force = true)
        }
    }

    private fun getSystemUsageMsToday(pkg: String, _now: Long): Long {
        return try {
            UsageStore.getUsageMsToday(this, pkg)
        } catch (_: Throwable) {
            0L
        }
    }

    /**
     * Returns the "today" app usage used for user-facing app timers.
     *
     * We prefer the system-reported value when Usage Access is granted so Switchly stays aligned with Android's own screen-time numbers.
     * If Usage Access is missing, we fall back to Switchly's internal counter.
     */
    private fun getEffectiveUsageMsToday(pkg: String, now: Long): Long {
        return AppUsageToday.getUsageMsToday(this, pkg, now)
    }

    /**
     * Usage used for enforcing profile-specific app limits.
     *
     * Default behavior stays per day/profile. If a limit is configured as "per active session", the runtime-scoped session counter is used instead.
     */
    private fun getEnforcedLimitUsageMs(profile: String, pkg: String): Long {
        return if (UsageLimitResetStore.isSessionMode(this, profile, pkg)) {
            sessionLimitUsageMsByKey[limitSessionKey(profile, pkg)] ?: 0L
        } else {
            ProfileUsageStore.getUsageMsToday(this, profile, pkg)
        }
    }

    private fun ensureActiveLimitSession(profile: String, now: Long) {
        val generation = SwitchModeStore.getLimitSessionGeneration(this)
        if (activeLimitSessionProfile == profile && activeLimitSessionGeneration == generation && activeLimitSessionStartedAt > 0L) return
        activeLimitSessionProfile = profile
        activeLimitSessionGeneration = generation
        activeLimitSessionStartedAt = now
        sessionLimitUsageMsByKey.clear()
        sessionLimitReachedKeys.clear()
        UsageLimitSessionRuntimeStore.clearAll(this)
        appendBlockingLog(
            category = "limit_session",
            key = "limit-session-start|$profile|$generation",
            message = "profile=$profile generation=$generation startedAt=$activeLimitSessionStartedAt",
            throttleMs = 1_500L
        )
    }

    private fun clearActiveLimitSession() {
        activeLimitSessionProfile = null
        activeLimitSessionGeneration = -1L
        activeLimitSessionStartedAt = 0L
        sessionLimitUsageMsByKey.clear()
        sessionLimitReachedKeys.clear()
        UsageLimitSessionRuntimeStore.clearAll(this)
    }

    private fun limitSessionKey(profile: String, pkg: String): String = "$profile|$pkg"

    private fun publishSessionLimitState(profile: String, pkg: String, limitMinutes: Int, usedMs: Long, reached: Boolean) {
        if (limitMinutes <= 0) return
        UsageLimitSessionRuntimeStore.update(
            context = this,
            profile = profile,
            pkg = pkg,
            generation = activeLimitSessionGeneration.takeIf { it >= 0L } ?: SwitchModeStore.getLimitSessionGeneration(this),
            startedAt = activeLimitSessionStartedAt,
            usedMs = usedMs,
            limitMs = limitMinutes.toLong() * 60_000L,
            reached = reached
        )
    }

    private fun addSessionLimitUsageMs(profile: String, pkg: String, deltaMs: Long) {
        if (deltaMs <= 0L) return
        val key = limitSessionKey(profile, pkg)
        sessionLimitUsageMsByKey[key] = (sessionLimitUsageMsByKey[key] ?: 0L) + deltaMs
    }

    private fun isLimitReached(profile: String, pkg: String, limitMinutes: Int): Boolean {
        if (limitMinutes <= 0) return false
        return if (UsageLimitResetStore.isSessionMode(this, profile, pkg)) {
            sessionLimitReachedKeys.contains(limitSessionKey(profile, pkg)) ||
                getEnforcedLimitUsageMs(profile, pkg) >= limitMinutes * 60_000L
        } else {
            LimitReachedStore.isReachedToday(this, profile, pkg)
        }
    }

    private fun markLimitReached(profile: String, pkg: String) {
        if (UsageLimitResetStore.isSessionMode(this, profile, pkg)) {
            sessionLimitReachedKeys.add(limitSessionKey(profile, pkg))
        } else {
            LimitReachedStore.markReachedToday(this, profile, pkg)
        }
    }

    /**
     * Refreshes [currentTopPkg] using UsageEvents at a low cadence.
     * If Usage Access isn't granted, this is a no-op.
     * The UsageStats binder can stall unpredictably on some devices, so the query runs on a  worker thread and only the lightweight state update is posted back to the main thread.
     */
    private fun refreshTopPackageIfNeeded(now: Long) {
        // Keep this fairly tight: some devices miss accessibility transitions when an app is resumed from Recents/Overview, so UsageEvents becomes the fallback that re-enforces hard blocks. Running it roughly once per tick keeps reopen loopholes short.
        if (now - lastTopRefreshAt < 1_000L) return
        if (topRefreshInFlight) return

        lastTopRefreshAt = now
        topRefreshInFlight = true

        val start = (now - 60_000L).coerceAtLeast(0L)
        usageWorker?.post {
            val top = runCatching { usageEventsForegroundResolver.resolveTopPackage(start, now) }.getOrNull()
            topRefreshInFlight = false
            if (top.isNullOrBlank()) {
                BlockingRuntime.markUsageTopResolution(this, null, "usage_events_empty_60s")
                return@post
            }

            handler.post {
                BlockingRuntime.markUsageTopResolution(this, top, "usage_events_60s")
                applyResolvedTopPackage(top, now)
            }
        } ?: run {
            topRefreshInFlight = false
        }
    }

    private fun applyResolvedTopPackage(top: String, now: Long) {
        if (top.isBlank()) return
        BlockingRuntime.markForegroundPackage(this, top, "usage_events")
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
        val timeBlocked = timeLimitMin > 0 && isLimitReached(profile, pkg, timeLimitMin)
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
        fun markRuntimeBlockCheck(reason: String, details: String = "") {
            BlockingRuntime.markBlockingCheck(this, pkg, reason, details)
        }
        if (AppBlockSafety.isHardExcluded(this, pkg)) {
            markRuntimeBlockCheck("hard_excluded")
            return
        }
        val now = System.currentTimeMillis()

        // Strict-mode lockout fallback: when Settings or another strict recovery surface is temporarily allowed, do not let loop-protection suppression immediately bounce it back home again.
        // This keeps the short recovery window real instead of only showing a toast while the activity is still closed.
        if (AppBlockSafety.requiresStrictModeForBlocking(this, pkg) && TempAllowStore.isAllowed(this, pkg)) {
            markRuntimeBlockCheck("strict_lockout_recovery_allowed")
            appendBlockingLog(
                category = "temp_allow",
                key = "temp-allow|$pkg",
                message = "pkg=$pkg reason=strict_lockout_recovery",
                throttleMs = 2_000L
            )
            return
        }

        if (isBlockSuppressed(pkg, now)) {
            markRuntimeBlockCheck("loop_suppressed")
            enforceSuppressedBlock(pkg, now)
            return
        }

        if (!pm.isInteractive) {
            markRuntimeBlockCheck("device_not_interactive")
            return
        }
        if (km?.isKeyguardLocked == true) {
            markRuntimeBlockCheck("keyguard_locked")
            return
        }
        if (EmergencyBypassStore.isActive(this)) {
            markRuntimeBlockCheck("emergency_active")
            return
        }
        if (!SwitchModeStore.isEnabled(this)) {
            markRuntimeBlockCheck("switchly_disabled")
            return
        }
        if (!shouldRunEnforcement(pkg, event, force)) {
            markRuntimeBlockCheck("enforcement_throttled", "force=$force event=${eventTypeLabel(event)}")
            return
        }
        val tempAllowed = TempAllowStore.isAllowed(this, pkg)

        // Website/domain blocking + in-app blocks (even if app itself isn't blocked)
        // NOTE: temp-allow should not bypass in-app restrictions (Shorts/Reels/etc.).
        maybeBlockWebsite(pkg, event)
        maybeInAppBlock(pkg, event)

        if (tempAllowed) {
            markRuntimeBlockCheck("temp_allowed")
            return
        }

        val nowForCache = System.currentTimeMillis()
        val profile = getCurrentProfileCached(nowForCache)
        if (profile == null) {
            markRuntimeBlockCheck("no_active_profile")
            clearActiveLimitSession()
            return
        }
        ensureActiveLimitSession(profile, nowForCache)
        val allowMode = ProfileRuleModeStore.isAllowMode(this, profile)
        val blocked = if (allowMode) {
            ProfileStore.getAllowedForProfile(this, profile)
        } else {
            getBlockedAppsCached(profile, nowForCache)
        }
        val allowModeListed = allowMode && pkg in ProfileStore.getLaunchablePackages(this)
        val essentialAllowed = allowMode && AppBlockSafety.isAllowModeEssential(this, pkg)
        val lockActive = SwitchModeStore.isNfcRequiredForDisable(this)
        val limitMin = getUsageLimitCached(profile, pkg, nowForCache)
        val attemptLimit = getAttemptLimitCached(profile, pkg, nowForCache)

        val opensExceeded = attemptLimit > 0 && OpenCountStore.getToday(this, profile, pkg) > attemptLimit
        val effectiveUsageMsToday = getEnforcedLimitUsageMs(profile, pkg)
        val decision = resolveAppBlockDecision(
            pkg = pkg,
            blockedPackages = blocked,
            limitMinutes = limitMin,
            attemptLimit = attemptLimit,
            opensExceeded = opensExceeded,
            effectiveUsageMsToday = effectiveUsageMsToday,
            lockActive = lockActive,
            highRisk = isHighRiskBlockTarget(this, pkg),
            force = force,
            allowMode = allowMode,
            essentialAllowed = essentialAllowed,
            allowModeListed = allowModeListed
        )

        if (limitMin > 0 || attemptLimit > 0) {
            val limitMs = limitMin * 60_000L
            val hardBlocked = if (allowMode) allowModeListed && !isManagedPackage(pkg, blocked) && limitMin <= 0 && attemptLimit <= 0 && !essentialAllowed else isManagedPackage(pkg, blocked) && limitMin <= 0 && attemptLimit <= 0
            appendBlockingLog(
                category = "app_limit_decision",
                key = "app-limit-decision|$profile|$pkg",
                message = "profile=$profile mode=${if (allowMode) "allow" else "block"} pkg=$pkg hardBlocked=$hardBlocked allowModeListed=$allowModeListed essentialAllowed=$essentialAllowed limitMin=$limitMin limitUsageMs=$effectiveUsageMsToday globalUsageMs=${getEffectiveUsageMsToday(pkg, System.currentTimeMillis())} limitMs=$limitMs attemptLimit=$attemptLimit opensExceeded=$opensExceeded force=$force shouldBlock=${decision.shouldBlock}",
                throttleMs = 2_000L
            )
        }

        if (!decision.shouldBlock) {
            val hardBlocked = if (allowMode) allowModeListed && !isManagedPackage(pkg, blocked) && limitMin <= 0 && attemptLimit <= 0 && !essentialAllowed else isManagedPackage(pkg, blocked) && limitMin <= 0 && attemptLimit <= 0
            val managed = hardBlocked || isManagedPackage(pkg, blocked) || limitMin > 0 || attemptLimit > 0
            markRuntimeBlockCheck(
                reason = if (managed) "decision_allow" else "not_managed_for_profile",
                details = "profile=$profile blockedCount=${blocked.size} hardBlocked=$hardBlocked allowModeListed=$allowModeListed essentialAllowed=$essentialAllowed limitMin=$limitMin attemptLimit=$attemptLimit opensExceeded=$opensExceeded limitUsageMs=$effectiveUsageMsToday force=$force event=${eventTypeLabel(event)}"
            )
            return
        }
        markRuntimeBlockCheck(
            reason = "decision_block",
            details = "profile=$profile limitMin=$limitMin attemptLimit=$attemptLimit opensExceeded=$opensExceeded immediate=${decision.immediate} force=$force event=${eventTypeLabel(event)}"
        )
        val appRule = when {
            allowMode && allowModeListed && !isManagedPackage(pkg, blocked) -> getString(R.string.block_reason_rule_allow_selected)
            limitMin > 0 && effectiveUsageMsToday >= limitMin * 60_000L -> getString(R.string.block_reason_rule_daily_time_limit)
            opensExceeded -> getString(R.string.block_reason_rule_open_limit)
            lockActive && isHighRiskBlockTarget(this, pkg) -> getString(R.string.block_reason_rule_strict_lock)
            else -> getString(R.string.block_reason_rule_blocked_apps)
        }
        val appMode = if (allowMode) {
            getString(R.string.block_reason_mode_allow_not_allowed)
        } else {
            getString(R.string.block_reason_mode_block_selected)
        }
        rememberBlockReason(
            pkg = pkg,
            profile = profile,
            rule = appRule,
            mode = appMode,
            source = getString(R.string.block_reason_source_app_blocking),
            matched = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg),
            result = getString(R.string.block_reason_result_blocked)
        )
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

    private fun rememberBlockReason(
        pkg: String,
        label: String? = null,
        profile: String? = null,
        rule: String,
        mode: String? = null,
        source: String,
        matched: String? = null,
        result: String? = null,
        details: String? = null
    ) {
        LastBlockReasonStore.mark(
            this,
            pkg = pkg,
            label = label,
            profile = profile,
            rule = rule,
            mode = mode,
            source = source,
            matched = matched,
            result = result,
            details = details
        )
    }

    private fun softBlockSurface(
        pkg: String,
        appLabel: String,
        title: String,
        message: String,
        backCount: Int = 1,
        deferNavigationUntilAcknowledge: Boolean = false,
        returnToPackageOnClose: Boolean = false,
        forceShow: Boolean = false,
        postAcknowledgeYouTubeHome: Boolean = false,
        postAcknowledgeYouTubeClose: Boolean = false,
        postAcknowledgeYouTubeCleanupShorts: Boolean = false,
        postAcknowledgeYouTubeCleanupMini: Boolean = true,
        prePopupPhoneHome: Boolean = false,
        prePopupYouTubeHome: Boolean = false,
        prePopupYouTubeCloseShorts: Boolean = false
    ) {
        if (!SwitchModeStore.isEnabled(this)) {
            BlockerActivity.clearVisibilityState("surface_block_skipped_disabled")
            return
        }
        val now = System.currentTimeMillis()

        // Prevent getting stuck in a block loop while the UI transitions.
        val sk = pkg + "|" + title
        val lastSurf = lastSurfaceBlockAt[sk] ?: 0L
        if (!forceShow && now - lastSurf < SURFACE_BLOCK_COOLDOWN_MS) return
        lastSurfaceBlockAt[sk] = now

        if (BlockerActivity.isVisible && !forceShow) return

        val lastShown = lastBlockShownAt[pkg] ?: 0L
        val canShow =
            forceShow || ((now - lastShown) >= BLOCK_SHOWN_COOLDOWN_MS && (now - lastGlobalBlockTs) >= 250L)
        if (!canShow) return

        appendBlockingLog(
            category = "surface_block",
            key = "surface-block|$pkg|$title",
            message = "pkg=$pkg title=${sanitizeWebsiteSignal(title, 80)} backCount=$backCount defer=$deferNavigationUntilAcknowledge returnToPkg=$returnToPackageOnClose force=$forceShow preHome=$prePopupPhoneHome preYtHome=$prePopupYouTubeHome"
        )

        rememberBlockReason(
            pkg = pkg,
            label = appLabel,
            profile = ProfileStore.getCurrent(this),
            rule = title,
            source = getString(R.string.block_reason_source_in_app),
            matched = title,
            result = getString(R.string.block_reason_result_surface_blocked)
        )

        // Short grace period after a surface block to prevent re-detect loops while the app animates away.
        // Snapchat needs a much tighter window: after blocking one story, opening the next story should be checked immediately.
        val postBlockGraceMs = if (pkg == "com.snapchat.android") SNAP_POST_BLOCK_GRACE_MS else INAPP_POST_BLOCK_GRACE_MS
        inAppGraceUntilByPkg[pkg] = now + postBlockGraceMs
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
            // Keep Shorts on the popup path; any Home navigation is controlled separately so the popup can stay visible.
            surfaceBlockGuardUntil["$pkg|yt:shorts"] = now + YT_SHORTS_REENTRY_GUARD_MS
        }

        if (prePopupPhoneHome) {
            // Move the user to the phone launcher before showing the feedback popup.
            // This is intentionally only used for surfaces where the launcher should remain visible behind the popup.
            // YouTube Shorts no longer uses this path because the expected UX is YouTube Home behind the popup.
            blockLaunchController.postHome(delayMs = 60L)
        }

        val isYouTubeShortsPopup =
            pkg == "com.google.android.youtube" &&
                title.contains(getString(R.string.in_app_surface_shorts_label), ignoreCase = true)
        val isYouTubeHomeFeedShortsPopup =
            prePopupYouTubeHome &&
                prePopupYouTubeCloseShorts &&
                isYouTubeShortsPopup &&
                youtubeCurrentRoot()?.let { isYouTubeHomeFeedShortsPlayer(it) } == true

        if (prePopupYouTubeHome && pkg == "com.google.android.youtube") {
            prepareYouTubeHomeBeforeSurfacePopup(
                reason = if (isYouTubeShortsPopup) "shorts_pre_popup_yt_home" else "pre_popup_yt_home",
                allowLaunchFallback = !isYouTubeShortsPopup,
                closeVisibleShorts = isYouTubeHomeFeedShortsPopup,
                closeMiniPlayer = prePopupYouTubeCloseShorts
            )
        }

        val showDelay = when {
            // For Shorts opened from Home, first close the player with BACK, then show the popup.
            // Otherwise YouTube keeps the same Short under the blocker because Home is already selected.
            isYouTubeHomeFeedShortsPopup -> 180L
            // Bottom-nav Shorts should use the older flow: move YouTube to Home before the popup, then OK only dismisses the popup.
            // A second Home redirect after OK can restore Shorts again.
            prePopupYouTubeHome && isYouTubeShortsPopup -> 0L
            prePopupYouTubeHome -> 900L
            prePopupPhoneHome -> 260L
            deferNavigationUntilAcknowledge -> 0L
            pkg == "com.snapchat.android" -> 320L
            else -> 30L
        }
        val resolvedMessage = buildSurfaceBlockMessage(pkg = pkg, title = title, originalMessage = message)

        fun showSurfacePopup(reason: String) {
            runCatching {
                appendBlockingLog(
                    category = "surface_popup_show",
                    key = "surface-popup-show|$pkg|$title|$reason",
                    message = "pkg=$pkg title=${sanitizeWebsiteSignal(title, 80)} reason=$reason state=${BlockerActivity.debugVisibilityState(pkg)}",
                    throttleMs = 700L
                )
                BlockerActivity.showDetailed(
                    this,
                    pkg,
                    appLabel,
                    title,
                    resolvedMessage,
                    postAcknowledgeBackCount = if (deferNavigationUntilAcknowledge) backCount else 0,
                    returnToPackageOnClose = returnToPackageOnClose,
                    postAcknowledgeYouTubeHome = postAcknowledgeYouTubeHome,
                    postAcknowledgeYouTubeClose = postAcknowledgeYouTubeClose,
                    postAcknowledgeYouTubeCleanupShorts = postAcknowledgeYouTubeCleanupShorts,
                    postAcknowledgeYouTubeCleanupMini = postAcknowledgeYouTubeCleanupMini
                )
            }
        }

        handler.postDelayed({
            showSurfacePopup("primary")
            if (prePopupYouTubeHome) {
                handler.postDelayed({
                    if (!BlockerActivity.isRecentlyFocusedFor(pkg, ttlMs = 900L)) {
                        showSurfacePopup("yt_home_retry")
                    }
                }, 420L)
            }
        }, showDelay)
    }

    private fun homeThenBlockSurface(
        pkg: String,
        appLabel: String,
        title: String,
        message: String,
        surfaceKey: String,
        forceShow: Boolean = false
    ) {
        if (!SwitchModeStore.isEnabled(this)) {
            BlockerActivity.clearVisibilityState("surface_home_block_skipped_disabled")
            return
        }
        val now = System.currentTimeMillis()
        val sk = pkg + "|" + title + "|home"
        val lastSurf = lastSurfaceBlockAt[sk] ?: 0L
        if (!forceShow && now - lastSurf < SURFACE_BLOCK_COOLDOWN_MS) return
        lastSurfaceBlockAt[sk] = now

        val lastShown = lastBlockShownAt[pkg] ?: 0L
        val canShow = forceShow || ((now - lastShown) >= BLOCK_SHOWN_COOLDOWN_MS && (now - lastGlobalBlockTs) >= 250L)
        if (!canShow) return

        appendBlockingLog(
            category = "surface_block",
            key = "surface-home-block|$pkg|$title",
            message = "pkg=$pkg title=${sanitizeWebsiteSignal(title, 80)} action=home_then_popup surface=$surfaceKey force=$forceShow"
        )

        rememberBlockReason(
            pkg = pkg,
            label = appLabel,
            profile = ProfileStore.getCurrent(this),
            rule = title,
            source = getString(R.string.block_reason_source_in_app),
            matched = surfaceKey,
            result = getString(R.string.block_reason_result_surface_blocked)
        )

        inAppGraceUntilByPkg[pkg] = now + maxOf(INAPP_POST_BLOCK_GRACE_MS, YT_SHORTS_REENTRY_GUARD_MS)
        surfaceBlockGuardUntil["$pkg|$surfaceKey"] = now + maxOf(INAPP_POST_BLOCK_GRACE_MS, YT_SHORTS_REENTRY_GUARD_MS)
        clearSurfaceEvidenceForPackage(pkg)
        if (currentSurfacePkg == pkg) {
            currentSurfaceKey = null
            currentSurfacePkg = null
        }

        lastBlockShownAt[pkg] = now
        lastGlobalBlockTs = now
        BlockCountStore.incrementToday(this, pkg)
        perf.blocksShown++

        if (pkg == "com.google.android.youtube") {
            // Keep the user inside YouTube and move only the blocked surface back to YouTube Home.
            // Also close YouTube's own mini-player when it appears after leaving Shorts.
            runCatching { blockLaunchController.pauseActiveMediaPlayback() }
            dismissYouTubeMiniPlayer("surface_${surfaceKey}_before_home")
            redirectYouTubeToHome("surface:$surfaceKey", youtubeCurrentRoot())
            handler.postDelayed({ dismissYouTubeMiniPlayer("surface_${surfaceKey}_retry_350") }, 350L)
            handler.postDelayed({ dismissYouTubeMiniPlayer("surface_${surfaceKey}_retry_900") }, 900L)
        } else {
            blockLaunchController.postHome()
            blockLaunchController.postKillBackgroundPackage(pkg, delayMs = 180L)
        }

        val resolvedMessage = buildSurfaceBlockMessage(pkg = pkg, title = title, originalMessage = message)
        handler.postDelayed({
            runCatching {
                if (!SwitchModeStore.isEnabled(this)) return@postDelayed
                BlockerActivity.showDetailed(
                    this,
                    pkg,
                    appLabel,
                    title,
                    resolvedMessage,
                    postAcknowledgeBackCount = 0,
                    returnToPackageOnClose = false
                )
            }
        }, 260L)
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

    private data class SettingsBypassSurfaceSignal(
        val reason: String,
        val backCount: Int = 1
    )

    private fun maybeBlockSwitchlySettingsBypassSurface(pkg: String, event: AccessibilityEvent?): Boolean {
        if (!SwitchModeStore.isEnabled(this)) return false
        if (EmergencyBypassStore.isActive(this)) return false
        if (TempAllowStore.isAllowed(this, pkg)) return false
        if (!AutomationModeStore.isUninstallFrictionEnabled(this)) return false
        if (!isSettingsBypassPackage(pkg)) return false

        val root = currentRoot(event) ?: return false
        val signal = detectSwitchlySettingsBypassSurface(root, event) ?: return false
        val appLabel = safeAppLabel(pkg)
        appendBlockingLog(
            category = "settings_bypass",
            key = "settings-bypass|$pkg|${signal.reason}",
            message = "pkg=$pkg reason=${signal.reason} event=${eventTypeLabel(event)}",
            throttleMs = 1_200L
        )
        rememberBlockReason(
            pkg = pkg,
            label = appLabel,
            profile = ProfileStore.getCurrent(this),
            rule = getString(R.string.block_reason_rule_settings_bypass),
            source = getString(R.string.block_reason_source_strict_mode),
            matched = signal.reason,
            result = getString(R.string.block_reason_result_blocked)
        )
        showSettingsBypassBlock(
            pkg = pkg,
            appLabel = appLabel,
            reason = signal.reason
        )
        return true
    }

    private fun showSettingsBypassBlock(pkg: String, appLabel: String, reason: String) {
        if (!SwitchModeStore.isEnabled(this)) {
            BlockerActivity.clearVisibilityState("settings_bypass_skipped_disabled")
            return
        }

        val now = System.currentTimeMillis()
        val key = "$pkg|settings_bypass"
        val lastSurf = lastSurfaceBlockAt[key] ?: 0L
        if (now - lastSurf < SURFACE_BLOCK_COOLDOWN_MS) return
        lastSurfaceBlockAt[key] = now
        lastBlockShownAt[pkg] = now
        lastGlobalBlockTs = now
        BlockCountStore.incrementToday(this, pkg)
        perf.blocksShown++

        appendBlockingLog(
            category = "settings_bypass",
            key = "settings-bypass-stable|$pkg|$reason",
            message = "pkg=$pkg reason=$reason action=home_then_popup",
            throttleMs = 1_200L
        )

        // Leave Settings first, then show the explanation.
        // Showing the popup while Android is still handling the Settings BACK/HOME transition can make the popup lose focus or close itself on some OEMs.
        blockLaunchController.postHome()
        handler.postDelayed({
            if (!SwitchModeStore.isEnabled(this)) {
                BlockerActivity.clearVisibilityState("settings_bypass_popup_skipped_disabled")
                return@postDelayed
            }
            runCatching {
                BlockerActivity.showDetailed(
                    this,
                    pkg,
                    appLabel,
                    getString(R.string.blocking_settings_bypass_title),
                    getString(R.string.blocking_settings_bypass_message),
                    postAcknowledgeBackCount = 0,
                    returnToPackageOnClose = false
                )
            }.onFailure { err ->
                val error = err.javaClass.simpleName
                appendBlockingLog(
                    category = "settings_bypass",
                    key = "settings-bypass-popup-failed|$pkg",
                    message = "pkg=$pkg reason=$reason result=popup_failed error=$error",
                    throttleMs = 2_500L
                )
            }
        }, 360L)
    }

    private fun isSettingsBypassPackage(pkg: String): Boolean {
        return pkg == "com.android.settings" ||
            pkg == "com.samsung.android.settings" ||
            pkg == "com.miui.securitycenter" ||
            pkg == "com.miui.securitycenter.remote" ||
            pkg == "com.coloros.safecenter" ||
            pkg == "com.oplus.safecenter" ||
            pkg == "com.vivo.permissionmanager" ||
            pkg == "com.huawei.systemmanager" ||
            pkg == "com.google.android.permissioncontroller"
    }

    private fun detectSwitchlySettingsBypassSurface(
        root: AccessibilityNodeInfo,
        event: AccessibilityEvent?
    ): SettingsBypassSurfaceSignal? {
        val lowered = collectNodeTextBlob(root, event)
        val loweredIds = collectNodeIdBlob(root)
        val packageLower = packageName.lowercase(Locale.ROOT)

        val mentionsSwitchly =
            lowered.contains("switchly") ||
                lowered.contains(packageLower) ||
                loweredIds.contains(packageLower)

        if (!mentionsSwitchly) return null

        val hasAccessibility =
            lowered.contains("accessibility") ||
                lowered.contains("bedienungshilfe") ||
                lowered.contains("bedienungshilfen") ||
                lowered.contains("barrierefreiheit") ||
                lowered.contains("zugriffshilfe") ||
                lowered.contains("use switchly") ||
                lowered.contains("switchly verwenden") ||
                loweredIds.contains("accessibility")

        val hasUsageAccess =
            lowered.contains("usage access") ||
                lowered.contains("apps with usage access") ||
                lowered.contains("nutzungszugriff") ||
                lowered.contains("nutzungsdatenzugriff") ||
                loweredIds.contains("usage_access")

        val hasNotificationAccess =
            lowered.contains("notification access") ||
                lowered.contains("notification listener") ||
                lowered.contains("benachrichtigungszugriff") ||
                lowered.contains("benachrichtigungszugriff") ||
                loweredIds.contains("notification_access") ||
                loweredIds.contains("notification_listener")

        val hasBatteryOrBackground =
            lowered.contains("battery") ||
                lowered.contains("unrestricted") ||
                lowered.contains("optimized") ||
                lowered.contains("background usage") ||
                lowered.contains("background activity") ||
                lowered.contains("akku") ||
                lowered.contains("batterie") ||
                lowered.contains("hintergrund") ||
                loweredIds.contains("battery") ||
                loweredIds.contains("background")

        val hasAppInfo =
            lowered.contains("app info") ||
                lowered.contains("app details") ||
                lowered.contains("app-information") ||
                lowered.contains("app-informationen") ||
                lowered.contains("force stop") ||
                lowered.contains("stopp erzwingen") ||
                loweredIds.contains("app_info") ||
                loweredIds.contains("force_stop")

        val hasSpecialAccess =
            lowered.contains("special app access") ||
                lowered.contains("special access") ||
                lowered.contains("spezieller app-zugriff") ||
                lowered.contains("spezieller zugriff")

        return when {
            hasAccessibility -> SettingsBypassSurfaceSignal("accessibility")
            hasUsageAccess -> SettingsBypassSurfaceSignal("usage_access")
            hasNotificationAccess -> SettingsBypassSurfaceSignal("notification_access")
            hasBatteryOrBackground -> SettingsBypassSurfaceSignal("battery_background")
            hasAppInfo -> SettingsBypassSurfaceSignal("app_info")
            hasSpecialAccess -> SettingsBypassSurfaceSignal("special_app_access")
            else -> null
        }
    }

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
        if (!SwitchModeStore.isEnabled(this)) {
            BlockerActivity.clearVisibilityState("block_now_skipped_disabled")
            BlockingRuntime.markBlockingCheck(this, pkg, "switchly_disabled", "blockNow pkg=$pkg")
            return
        }
        val now = System.currentTimeMillis()
        val allowLoopSafetyMode = shouldUseLoopSafetyMode(this, pkg)
        if (allowLoopSafetyMode && isBlockSuppressed(pkg, now)) {
            enforceSuppressedBlock(pkg, now)
            return
        }
        if (!allowLoopSafetyMode) {
            suppressedBlockingUntilByPkg.remove(pkg)
        }
        if (SwitchModeStore.isEnabled(this) && BlockerActivity.isRecentlyFocusedFor(pkg)) {
            appendBlockingLog(
                category = "app_block_skip",
                key = "app-block-visible|$pkg",
                message = "pkg=$pkg reason=blocker_visible ${BlockerActivity.debugVisibilityState(pkg)}",
                throttleMs = 1_500L
            )
            return
        }
        if (SwitchModeStore.isEnabled(this) && BlockerActivity.isVisible) {
            appendBlockingLog(
                category = "app_block_retry",
                key = "app-block-not-focused|$pkg",
                message = "pkg=$pkg reason=blocker_not_focused ${BlockerActivity.debugVisibilityState(pkg)}",
                throttleMs = 1_500L
            )
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
            BlockingRuntime.markBlockShown(
                this,
                pkg,
                "immediate=$immediate label=${sanitizeWebsiteSignal(label, 80)} countAttempt=$countAttempt countAsBlock=$countAsBlock"
            )
        }

        val multiWindowDetails = multiWindowBlockFallbackDetails(pkg)
        if (multiWindowDetails != null) {
            val details = "action=home_then_blocker $multiWindowDetails"
            appendBlockingLog(
                category = "multiwindow_block",
                key = "multiwindow-block|$pkg",
                message = "pkg=$pkg $details",
                throttleMs = 1_500L
            )
            BlockingRuntime.markMultiWindowBlock(this, pkg, details)
            blockLaunchController.postHome()
            blockLaunchController.postKillBackgroundPackage(pkg, delayMs = 80L)
            blockLaunchController.showAppBlocker(pkg, label, delayMs = 240L)
            return
        }

        val delayMs = if (immediate) 20L else 60L
        blockLaunchController.showAppBlocker(pkg, label, delayMs)
    }

    private fun multiWindowBlockFallbackDetails(pkg: String): String? {
        if (pkg.isBlank()) return null

        val activeWindows = runCatching { windows }.getOrNull().orEmpty()
        if (activeWindows.size <= 1) return null

        val appWindowPackages = LinkedHashSet<String>()
        var blockedPackageVisible = false

        for (window in activeWindows) {
            val isAppWindow = runCatching { window.type == AccessibilityWindowInfo.TYPE_APPLICATION }.getOrDefault(false)
            if (!isAppWindow) continue

            val rootPkg = runCatching { window.root?.packageName?.toString()?.trim().orEmpty() }.getOrDefault("")
            if (rootPkg.isBlank()) continue

            appWindowPackages += rootPkg
            if (rootPkg == pkg) blockedPackageVisible = true
        }

        // In split-screen/freeform/pop-up modes Android can keep the blocked app in one application window while Switchly opens the blocker in another one.
        // In that case the Activity blocker does not cover the whole display, so we first leave multi-window by going Home and then show the normal block screen.
        if (!blockedPackageVisible || appWindowPackages.size <= 1) return null

        val packages = appWindowPackages
            .joinToString(",")
            .take(180)
        return "windows=${activeWindows.size} appWindows=${appWindowPackages.size} packages=$packages"
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

        if (inAppSurfaceRuleEnabled(BlockingToggleKeys.KEY_BLOCK_YT_SHORTS)) {
            total += SurfaceUsageStore.getUsageMsToday(this, "yt:shorts")
        }
        if (inAppSurfaceRuleEnabled(BlockingToggleKeys.KEY_BLOCK_YT_SUBSCRIPTIONS)) {
            total += SurfaceUsageStore.getUsageMsToday(this, "yt:subscriptions")
        }
        if (inAppSurfaceRuleEnabled(BlockingToggleKeys.KEY_BLOCK_YT_YOU)) {
            total += SurfaceUsageStore.getUsageMsToday(this, "yt:you")
        }
        if (inAppSurfaceRuleEnabled(BlockingToggleKeys.KEY_BLOCK_IG_REELS)) {
            total += SurfaceUsageStore.getUsageMsToday(this, "ig:reels")
        }
        if (inAppSurfaceRuleEnabled(BlockingToggleKeys.KEY_BLOCK_IG_EXPLORE)) {
            total += SurfaceUsageStore.getUsageMsToday(this, "ig:explore")
        }
        if (inAppSurfaceRuleEnabled(BlockingToggleKeys.KEY_BLOCK_IG_SEARCH)) {
            total += SurfaceUsageStore.getUsageMsToday(this, "ig:search")
        }
        if (inAppSurfaceRuleEnabled(BlockingToggleKeys.KEY_BLOCK_IG_STORIES)) {
            total += SurfaceUsageStore.getUsageMsToday(this, "ig:stories")
        }
        if (inAppSurfaceRuleEnabled(BlockingToggleKeys.KEY_BLOCK_X_HOME)) {
            total += SurfaceUsageStore.getUsageMsToday(this, "x:foryou")
        }
        if (inAppSurfaceRuleEnabled(BlockingToggleKeys.KEY_BLOCK_X_SEARCH)) {
            total += SurfaceUsageStore.getUsageMsToday(this, "x:search")
        }
        if (inAppSurfaceRuleEnabled(BlockingToggleKeys.KEY_BLOCK_X_GROK)) {
            total += SurfaceUsageStore.getUsageMsToday(this, "x:grok")
        }
        if (inAppSurfaceRuleEnabled(BlockingToggleKeys.KEY_BLOCK_X_NOTIFICATIONS)) {
            total += SurfaceUsageStore.getUsageMsToday(this, "x:notifications")
        }
        if (inAppSurfaceRuleEnabled(BlockingToggleKeys.KEY_BLOCK_SNAP_MAP)) {
            total += SurfaceUsageStore.getUsageMsToday(this, "snap:map")
        }
        if (inAppSurfaceRuleEnabled(BlockingToggleKeys.KEY_BLOCK_SNAP_STORIES)) {
            total += SurfaceUsageStore.getUsageMsToday(this, "snap:stories")
        }
        if (inAppSurfaceRuleEnabled(BlockingToggleKeys.KEY_BLOCK_SNAP_SPOTLIGHT)) {
            total += SurfaceUsageStore.getUsageMsToday(this, "snap:spotlight")
        }
        if (inAppSurfaceRuleEnabled(BlockingToggleKeys.KEY_BLOCK_SNAP_FOLLOWING)) {
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

    private fun inAppSurfaceRuleEnabled(baseKey: String): Boolean {
        val now = System.currentTimeMillis()
        val profile = getCurrentProfileCached(now) ?: "default"
        return InAppRuleStore.shouldBlockSurface(this, profile, baseKey)
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
        return cd.contains("address") || cd.contains("search or enter") || cd.contains("search") || cd.contains("url")
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
                // Firefox/Fenix can emit autocomplete/suggestion events that already contain a blocked domain while the user is still typing in the address bar.
                // As long as the editable URL field itself is active, treat that as editing and do not allow website blocking to trigger yet.
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

    private fun youtubeCurrentRoot(event: AccessibilityEvent? = null): AccessibilityNodeInfo? {
        val active = rootInActiveWindow
        if (isRootFromPackage(active, "com.google.android.youtube")) return active
        val source = runCatching { event?.source }.getOrNull()
        if (isRootFromPackage(source, "com.google.android.youtube")) return source
        return findYouTubeWindowRoot() ?: active ?: source
    }

    private fun findYouTubeWindowRoot(): AccessibilityNodeInfo? {
        val activeWindows = runCatching { windows }.getOrNull().orEmpty()
        for (window in activeWindows) {
            val isAppWindow = runCatching { window.type == AccessibilityWindowInfo.TYPE_APPLICATION }.getOrDefault(false)
            val root = runCatching { window.root }.getOrNull() ?: continue
            if (isRootFromPackage(root, "com.google.android.youtube")) return root
            if (isAppWindow && containsPackageNode(root, "com.google.android.youtube")) return root
        }
        return null
    }

    private fun containsPackageNode(root: AccessibilityNodeInfo, pkg: String): Boolean {
        return findAnyNode(root) { node ->
            node.packageName?.toString()?.lowercase(Locale.getDefault()) == pkg
        } != null
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
        // Requiring a second confirmation event there can cause website blocking to never trigger on some devices/builds even after navigation has committed.
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

        val blockWebsitesEnabled = DomainBlockStore.isEnabled(this)
        val blockedDomainsList = if (blockWebsitesEnabled) DomainBlockStore.getEnabledDomains(this).toList() else emptyList()

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
                if (!isFirefoxFamily(pkg) && browserWebsiteState.hasRecentDomainSignal(pkg, now)) {
                    appendBlockingLog(
                        category = "website_detect",
                        key = "web-sticky-no-host|$pkg|${eventTypeLabel(event)}",
                        message = "pkg=$pkg result=no_host_keep_recent event=${eventTypeLabel(event)}",
                        throttleMs = 2_000L
                    )
                    return
                }
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
            if (needStability) {
                // For Chromium-based browsers, blocking still waits for a short stable signal to avoid reacting to autocomplete/address-bar noise.
                // Usage statistics can already keep the visible host as the best current signal, otherwise dynamic pages such as Instagram may be under-counted while every event stays in candidate/waiting state.
                browserWebsiteState.updateCurrentDomain(pkg, host, now)
                return
            }
        }
        val candidateAgeMs = browserWebsiteState.candidateAgeMs(now)
        if (needStability && candidateAgeMs < BrowserWebsiteState.DOMAIN_CONFIRM_MS) {
            // Keep website usage stats moving while the host is still waiting for block confirmation.
            // This does not weaken website blocking, because enforcement below still returns until the confirmation window has passed.
            browserWebsiteState.updateCurrentDomain(pkg, host, now)
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

        val hardBlocked = DomainBlockStore.shouldBlockHost(this, host)

        val limitMin = if (DomainBlockStore.isRuleEnabledForHost(this, host)) {
            DomainLimitStore.getLimitMinutes(this, host)
        } else {
            0
        }
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

        // Prefer redirecting the current browser task to a safe page so the browser stays open without dropping the user out of the whole app.
        // Fall back to a single BACK only if the redirect is not supported by the current browser build.
        val redirected = tryRedirectBrowserToSafePage(pkg)
        val websiteAllowMode = ProfileStore.getCurrent(this)
            ?.let { ProfileRuleModeStore.isAllowMode(this, it) } == true
        rememberBlockReason(
            pkg = pkg,
            label = appLabel,
            profile = ProfileStore.getCurrent(this),
            rule = if (hardBlocked) getString(R.string.block_reason_rule_website_rule) else getString(R.string.block_reason_rule_website_limit),
            mode = if (websiteAllowMode) {
                getString(R.string.block_reason_mode_website_allow_not_allowed)
            } else {
                getString(R.string.block_reason_mode_website_block_selected)
            },
            source = getString(R.string.block_reason_source_website),
            matched = host,
            result = getString(R.string.block_reason_result_blocked)
        )

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
        val root = currentRoot(null) ?: return
        if (!isRootFromPackage(root, pkg)) return

        val blockedDomains = DomainBlockStore.getEnabledDomains(this).toList()
        val allowWebsiteMode = ProfileStore.getCurrent(this)?.let { ProfileRuleModeStore.isAllowMode(this, it) } == true
        if (blockedDomains.isEmpty() && !allowWebsiteMode) return

        val visibleHost = tryExtractDomainFromBrowserUrlViews(root, pkg)
            ?: inferFirefoxDomainFromTexts(root, null, blockedDomains)
            ?: browserWebsiteState.currentPendingDomain(pkg)
            ?: browserWebsiteState.currentTrackedDomain(pkg, System.currentTimeMillis(), isFirefox = true)
            ?: return

        val hardBlocked = DomainBlockStore.shouldBlockHost(this, visibleHost)
        val limitMin = if (DomainBlockStore.isRuleEnabledForHost(this, visibleHost)) {
            DomainLimitStore.getLimitMinutes(this, visibleHost)
        } else {
            0
        }
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
        // Some Chromium/OEM combinations can restore the blocked tab immediately after our safe-page redirect.
        // Re-check shortly after a successful website block and enforce once more if the same blocked host is still visible.
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
        val root = currentRoot(null) ?: return
        if (!isRootFromPackage(root, pkg)) return

        val visibleHost = tryExtractDomainFromBrowserUrlViews(root, pkg)
            ?: tryExtractDomainFromBrowser(root, pkg, null)
            ?: return

        val stillBlocked = DomainBlockStore.shouldBlockHost(this, visibleHost)
        val limitMin = if (DomainBlockStore.isRuleEnabledForHost(this, visibleHost)) {
            DomainLimitStore.getLimitMinutes(this, visibleHost)
        } else {
            0
        }
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
            // First try one browser back step, then bounce Home on the final attempt so the blocked page cannot remain visible even if Chrome/Xiaomi restores the tab content.
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
        val found = findAnyNode(root) { node ->
            val t = node.text?.toString().orEmpty()
            val cd = node.contentDescription?.toString().orEmpty()
            val vid = node.viewIdResourceName?.lowercase(Locale.getDefault()).orEmpty()
            anyNeedleMatches(t, needles) || anyNeedleMatches(cd, needles) || anyNeedleMatches(vid, needles)
        }
        return try {
            found != null
        } finally {
        }
    }

    private fun eventTextMatches(event: AccessibilityEvent?, needles: List<String>): Boolean {
        if (event == null) return false
        val cd = event.contentDescription?.toString().orEmpty()
        if (anyNeedleMatches(cd, needles)) return true
        val texts = event.text ?: emptyList()
        for (cs in texts) {
            val t = cs?.toString().orEmpty()
            if (anyNeedleMatches(t, needles)) return true
        }
        return false
    }

    private fun hasSelectedLabel(root: AccessibilityNodeInfo, needles: List<String>): Boolean {
        val found = findAnyNode(root) { node ->
            if (!node.isSelected) return@findAnyNode false
            val t = node.text?.toString().orEmpty()
            val cd = node.contentDescription?.toString().orEmpty()
            val vid = node.viewIdResourceName?.lowercase(Locale.getDefault()).orEmpty()
            anyNeedleMatches(t, needles) || anyNeedleMatches(cd, needles) || anyNeedleMatches(vid, needles)
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
        val targetPkg = pkg.lowercase(Locale.getDefault())
        val found = findAnyNode(root) { node ->
            if (!node.isSelected) return@findAnyNode false
            val nodePkg = node.packageName?.toString()?.lowercase(Locale.getDefault()).orEmpty()
            if (nodePkg != targetPkg) return@findAnyNode false

            val t = node.text?.toString().orEmpty()
            val cd = node.contentDescription?.toString().orEmpty()
            val vid = node.viewIdResourceName?.lowercase(Locale.getDefault()).orEmpty()
            anyNeedleMatches(t, needles) || anyNeedleMatches(cd, needles) || anyNeedleMatches(vid, needles)
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
            anyNeedleMatches(text, needles)
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
        return tapScreenAtPoint(x, y)
    }

    private fun tapScreenAtPoint(x: Float, y: Float): Boolean {
        val width = resources.displayMetrics.widthPixels.coerceAtLeast(1)
        val height = resources.displayMetrics.heightPixels.coerceAtLeast(1)
        val safeX = x.coerceIn(1f, width.toFloat() - 1f)
        val safeY = y.coerceIn(1f, height.toFloat() - 1f)
        val path = Path().apply { moveTo(safeX, safeY) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 50L)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return runCatching { dispatchGesture(gesture, null, null) }.getOrDefault(false)
    }

    private fun swipeScreen(fromX: Float, fromY: Float, toX: Float, toY: Float, durationMs: Long = 180L): Boolean {
        val width = resources.displayMetrics.widthPixels.coerceAtLeast(1)
        val height = resources.displayMetrics.heightPixels.coerceAtLeast(1)
        val safeFromX = fromX.coerceIn(1f, width.toFloat() - 1f)
        val safeFromY = fromY.coerceIn(1f, height.toFloat() - 1f)
        val safeToX = toX.coerceIn(1f, width.toFloat() - 1f)
        val safeToY = toY.coerceIn(1f, height.toFloat() - 1f)
        val path = Path().apply {
            moveTo(safeFromX, safeFromY)
            lineTo(safeToX, safeToY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
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

        if (tryClickAnyLabelInPackage(r, pkg, SNAP_CAMERA_LABELS)) return true

        val cameraNode = findBestSnapchatBottomTabNode(r, 0.50f)
        val cameraClicked = try {
            clickNodeOrClickableParent(cameraNode)
        } finally {
        }
        if (cameraClicked) return true

        if (tapScreenAtRatio(0.50f, 0.90f) || tapScreenAtRatio(0.50f, 0.86f)) return true

        if (tryClickAnyLabelInPackage(r, pkg, SNAP_CHAT_LABELS)) return true

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
        return tryClickAnyLabelInPackage(r, pkg, X_HOME_NAV_LABELS)
    }

    private fun tryNavigateTwitterToNotifications(root: AccessibilityNodeInfo?): Boolean {
        val r = root ?: return false
        val pkg = "com.twitter.android"
        return tryClickAnyLabelInPackage(r, pkg, X_NOTIFICATIONS_NAV_LABELS)
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
                SNAP_MAP_LABELS.any { combinedHaystack.contains(it) } -> "snap:map"
                SNAP_SPOTLIGHT_LABELS.any { combinedHaystack.contains(it) } -> "snap:spotlight"
                SNAP_STORIES_LABELS.any { combinedHaystack.contains(it) } -> "snap:stories"
                SNAP_FOLLOWING_LABELS.any { combinedHaystack.contains(it) } -> "snap:following"
                SNAP_SAFE_LABELS.any { combinedHaystack.contains(it) } -> "snap:safe"
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

    private fun dismissOrClickNodeOrClickableParent(node: AccessibilityNodeInfo?): Boolean {
        val dismissed = runCatching { node?.performAction(AccessibilityNodeInfo.ACTION_DISMISS) == true }.getOrDefault(false)
        return dismissed || clickNodeOrClickableParent(node)
    }

    private fun launchYouTubeHomeFallback(reason: String = "fallback") {
        // Keep navigation inside YouTube.
        // Prefer explicit YouTube Home/web entry intents over the package launch intent because YouTube's default launch can restore the last Shorts task or leave the device on the phone launcher after closing the mini-player.
        val flags = Intent.FLAG_ACTIVITY_NEW_TASK or
            Intent.FLAG_ACTIVITY_SINGLE_TOP or
            Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
            Intent.FLAG_ACTIVITY_NO_ANIMATION

        val candidates = listOf(
            Intent(Intent.ACTION_VIEW, "https://www.youtube.com/".toUri()).apply {
                setPackage("com.google.android.youtube")
                addFlags(flags)
            },
            Intent(Intent.ACTION_VIEW, "vnd.youtube://www.youtube.com/".toUri()).apply {
                setPackage("com.google.android.youtube")
                addFlags(flags)
            },
            PackageLaunchIntentCompat.getLaunchIntent(this, "com.google.android.youtube")?.apply {
                addFlags(flags)
            }
        ).filterNotNull()

        for ((index, intent) in candidates.withIndex()) {
            val started = runCatching {
                startActivity(intent)
                true
            }.getOrDefault(false)
            appendBlockingLog(
                category = "yt_home_launch",
                key = "yt-home-launch|$reason|$index",
                message = "reason=$reason candidate=$index started=$started uri=${intent.dataString.orEmpty()}",
                throttleMs = 900L
            )
            if (started) return
        }
    }

    private fun prepareYouTubeHomeBeforeSurfacePopup(
        reason: String,
        allowLaunchFallback: Boolean = true,
        closeVisibleShorts: Boolean = false,
        closeMiniPlayer: Boolean = true
    ) {
        runCatching { blockLaunchController.pauseActiveMediaPlayback() }

        fun attempt(step: String, allowFallbackThisStep: Boolean) {
            runCatching {
                val r = youtubeCurrentRoot()
                val rootIsYouTube = isRootFromPackage(r, "com.google.android.youtube")
                val moved = if (rootIsYouTube) {
                    tryNavigateYouTubeToAllowedSafeSurface(r, "$reason:$step")
                } else {
                    false
                }
                if (!moved && allowLaunchFallback && allowFallbackThisStep) {
                    launchYouTubeHomeFallback("$reason:$step")
                }
                if (closeMiniPlayer) {
                    dismissYouTubeMiniPlayer("$reason:$step")
                }
                appendBlockingLog(
                    category = "yt_home_pre_popup",
                    key = "yt-home-pre-popup|$reason|$step",
                    message = "reason=$reason step=$step rootIsYouTube=$rootIsYouTube moved=$moved fallback=${allowLaunchFallback && allowFallbackThisStep}",
                    throttleMs = 650L
                )
            }
        }

        // YouTube Home-feed Shorts is special: the smoother path is:
        // 1) pause/close any player chrome,
        // 2) close the Home-feed Shorts player before the blocker covers YouTube,
        // 3) launch the blocker on the next main-loop tick so the Home transition happens underneath it.
        // Bottom-tab Shorts should keep the older behavior and just navigate back to YouTube Home.
        if (closeVisibleShorts) {
            closeYouTubeShortsPlayer("$reason:before")
        }
        if (closeMiniPlayer) {
            dismissYouTubeMiniPlayer("$reason:before")
        }
        attempt("home_tab_immediate", allowFallbackThisStep = true)
        handler.postDelayed({ attempt("home_tab_retry_120", allowFallbackThisStep = false) }, 120L)
        handler.postDelayed({ attempt("settle_360", allowFallbackThisStep = false) }, 360L)
        if (closeMiniPlayer) {
            handler.postDelayed({ dismissYouTubeMiniPlayer("$reason:mini_retry_650") }, 650L)
        }
    }

    private fun redirectYouTubeToHome(
        reason: String,
        root: AccessibilityNodeInfo? = null,
        allowLaunchFallback: Boolean = true,
        closeMiniPlayer: Boolean = true
    ) {
        // A clicked YouTube tab/search icon can open its destination after our Accessibility event is delivered.
        // Try immediately, then retry shortly after the UI has switched.
        // This keeps the user inside YouTube but moves them back to Home instead of minimizing the app with BACK.
        runCatching { blockLaunchController.pauseActiveMediaPlayback() }

        fun attempt(tag: String) {
            runCatching {
                val r = youtubeCurrentRoot()
                val moved = if (isRootFromPackage(r, "com.google.android.youtube")) {
                    tryNavigateYouTubeToAllowedSafeSurface(r, "$reason:$tag")
                } else {
                    false
                }
                if (!moved && allowLaunchFallback) launchYouTubeHomeFallback("$reason:$tag")
                if (closeMiniPlayer) {
                    dismissYouTubeMiniPlayer("$reason:$tag")
                }
                appendBlockingLog(
                    category = "yt_home_redirect",
                    key = "yt-home-redirect|$reason|$tag",
                    message = "reason=$reason step=$tag moved=$moved fallback=$allowLaunchFallback",
                    throttleMs = 900L
                )
            }
        }

        runCatching {
            val moved = if (isRootFromPackage(root, "com.google.android.youtube")) {
                tryNavigateYouTubeToAllowedSafeSurface(root, "$reason:immediate")
            } else {
                false
            }
            if (!moved && allowLaunchFallback) launchYouTubeHomeFallback("$reason:immediate")
            if (closeMiniPlayer) {
                dismissYouTubeMiniPlayer("$reason:immediate")
            }
            appendBlockingLog(
                category = "yt_home_redirect",
                key = "yt-home-redirect|$reason|immediate",
                message = "reason=$reason step=immediate moved=$moved fallback=$allowLaunchFallback",
                throttleMs = 900L
            )
        }
        handler.postDelayed({ attempt("retry_250") }, 250L)
        handler.postDelayed({ attempt("retry_700") }, 700L)
    }

    private fun dismissYouTubeMiniPlayer(reason: String, allowFallbackTap: Boolean = false): Boolean {
        val root = youtubeCurrentRoot() ?: return false
        if (!isRootFromPackage(root, "com.google.android.youtube")) return false

        val width = resources.displayMetrics.widthPixels.coerceAtLeast(1)
        val height = resources.displayMetrics.heightPixels.coerceAtLeast(1)
        val bounds = Rect()
        var matchedLabel = "-"

        val closeNode = findAnyNode(root) { node ->
            val nodePkg = node.packageName?.toString()?.lowercase(Locale.getDefault()).orEmpty()
            if (nodePkg.isNotBlank() && nodePkg != "com.google.android.youtube") return@findAnyNode false

            runCatching { node.getBoundsInScreen(bounds) }.getOrNull()
            if (bounds.isEmpty) return@findAnyNode false

            val centerY = bounds.exactCenterY() / height.toFloat()
            val centerX = bounds.exactCenterX() / width.toFloat()
            // YouTube's mini-player close button is normally in the lower half of the app.
            // Keeping this spatial guard prevents accidental clicks on normal top-bar close buttons.
            if (centerY < 0.45f) return@findAnyNode false

            val text = node.text?.toString().orEmpty()
            val desc = node.contentDescription?.toString().orEmpty()
            val viewId = node.viewIdResourceName?.lowercase(Locale.getDefault()).orEmpty()
            val signal = "$text $desc $viewId".lowercase(Locale.getDefault())

            val labelMatch = anyNeedleMatches(signal, YT_MINI_PLAYER_CLOSE_LABELS)
            val idMatch = viewId.contains("close") && (viewId.contains("player") || viewId.contains("mini"))
            val miniContext =
                signal.contains("mini") ||
                    signal.contains("player") ||
                    centerY > 0.62f ||
                    centerX > 0.62f
            val clickable = node.isClickable || (node.actionList?.any { it.id == AccessibilityNodeInfo.ACTION_CLICK } == true)
            val clickableParent = firstClickableAncestorInPackage(node, "com.google.android.youtube") != null
            val matched = (labelMatch || idMatch) && miniContext && (clickable || clickableParent)
            if (matched) matchedLabel = sanitizeWebsiteSignal(signal, 80)
            matched
        }

        val clicked = clickNodeOrClickableParent(closeNode)
        if (clicked) {
            appendBlockingLog(
                category = "yt_mini_close",
                key = "yt-mini-close|$reason",
                message = "reason=$reason clicked=true match=$matchedLabel",
                throttleMs = 500L
            )
            return true
        }

        if (allowFallbackTap && (isLikelyYouTubeMiniPlayerVisible(root) || hasYouTubeMiniPlayerGeometry(root))) {
            val pauseAxisClicked = closeYouTubeMiniPlayerFromPlayPauseAxis(root, reason)
            if (pauseAxisClicked) return true

            val actionDismissed = dismissYouTubeMiniPlayerByAccessibilityAction(root, reason)
            if (actionDismissed) return true

            val boundsClicked = tapYouTubeMiniPlayerCloseByBounds(root, reason)
            if (boundsClicked) return true

            val swiped = swipeYouTubeMiniPlayerAway(root, reason)
            if (swiped) return true

            val fallbackClicked =
                tapScreenAtRatio(0.94f, 0.68f) ||
                    tapScreenAtRatio(0.94f, 0.76f) ||
                    tapScreenAtRatio(0.94f, 0.82f) ||
                    tapScreenAtRatio(0.94f, 0.86f) ||
                    tapScreenAtRatio(0.94f, 0.88f) ||
                    tapScreenAtRatio(0.88f, 0.76f)
            appendBlockingLog(
                category = "yt_mini_close",
                key = "yt-mini-close-fallback|$reason",
                message = "reason=$reason fallbackClicked=$fallbackClicked",
                throttleMs = 500L
            )
            return fallbackClicked
        }
        return false
    }

    private fun dismissYouTubeMiniPlayerByAccessibilityAction(root: AccessibilityNodeInfo, reason: String): Boolean {
        val playerBounds = findYouTubeMiniPlayerBounds(root) ?: return false
        val width = resources.displayMetrics.widthPixels.coerceAtLeast(1)
        val height = resources.displayMetrics.heightPixels.coerceAtLeast(1)
        val bounds = Rect()
        var matchedSignal = "-"

        val node = findAnyNode(root) { candidate ->
            val nodePkg = candidate.packageName?.toString()?.lowercase(Locale.getDefault()).orEmpty()
            if (nodePkg.isNotBlank() && nodePkg != "com.google.android.youtube") return@findAnyNode false

            runCatching { candidate.getBoundsInScreen(bounds) }.getOrNull()
            if (bounds.isEmpty) return@findAnyNode false
            val inMiniArea =
                bounds.left >= playerBounds.left - width * 0.04f &&
                    bounds.right <= playerBounds.right + width * 0.04f &&
                    bounds.top >= playerBounds.top - height * 0.04f &&
                    bounds.bottom <= playerBounds.bottom + height * 0.04f
            if (!inMiniArea) return@findAnyNode false

            val centerX = bounds.exactCenterX() / width.toFloat()
            val text = candidate.text?.toString().orEmpty()
            val desc = candidate.contentDescription?.toString().orEmpty()
            val viewId = candidate.viewIdResourceName?.lowercase(Locale.getDefault()).orEmpty()
            val signal = "$text $desc $viewId".lowercase(Locale.getDefault())
            val hasDismiss = candidate.actionList?.any { it.id == AccessibilityNodeInfo.ACTION_DISMISS } == true
            val closeish =
                anyNeedleMatches(signal, YT_MINI_PLAYER_CLOSE_LABELS) ||
                    viewId.contains("close") ||
                    viewId.contains("dismiss") ||
                    centerX > 0.74f
            val actionable =
                hasDismiss ||
                    candidate.isClickable ||
                    candidate.actionList?.any { it.id == AccessibilityNodeInfo.ACTION_CLICK } == true
            val matched = closeish && actionable
            if (matched) matchedSignal = sanitizeWebsiteSignal(signal, 80)
            matched
        } ?: return false

        val dismissed = runCatching { node.performAction(AccessibilityNodeInfo.ACTION_DISMISS) }.getOrDefault(false)
        val clicked = if (!dismissed) clickNodeOrClickableParent(node) else false
        appendBlockingLog(
            category = "yt_mini_close",
            key = "yt-mini-close-action|$reason",
            message = "reason=$reason dismissed=$dismissed clicked=$clicked match=$matchedSignal",
            throttleMs = 500L
        )
        return dismissed || clicked
    }

    private fun closeYouTubeMiniPlayerFromPlayPauseAxis(root: AccessibilityNodeInfo, reason: String): Boolean {
        val playerBounds = findYouTubeMiniPlayerBounds(root) ?: return false
        val width = resources.displayMetrics.widthPixels.coerceAtLeast(1)
        val height = resources.displayMetrics.heightPixels.coerceAtLeast(1)
        val bounds = Rect()

        val pauseCandidates = mutableListOf<AccessibilityNodeInfo>()
        findAnyNode(root) { node ->
            val nodePkg = node.packageName?.toString()?.lowercase(Locale.getDefault()).orEmpty()
            if (nodePkg.isNotBlank() && nodePkg != "com.google.android.youtube") return@findAnyNode false

            runCatching { node.getBoundsInScreen(bounds) }.getOrNull()
            if (bounds.isEmpty) return@findAnyNode false
            val centerX = bounds.exactCenterX()
            val centerY = bounds.exactCenterY()
            val inMiniArea =
                centerX >= playerBounds.left &&
                    centerX <= playerBounds.right &&
                    centerY >= playerBounds.top &&
                    centerY <= playerBounds.bottom
            if (!inMiniArea) return@findAnyNode false

            val text = node.text?.toString().orEmpty()
            val desc = node.contentDescription?.toString().orEmpty()
            val viewId = node.viewIdResourceName?.lowercase(Locale.getDefault()).orEmpty()
            val className = node.className?.toString()?.lowercase(Locale.getDefault()).orEmpty()
            val signal = "$text $desc $viewId".lowercase(Locale.getDefault())
            val playPauseId =
                viewId.contains("play") ||
                    viewId.contains("pause") ||
                    viewId.contains("player_control") ||
                    viewId.contains("control")
            val playPauseLabel = anyNeedleMatches(signal, YT_MINI_PLAYER_PLAY_PAUSE_LABELS)
            val leftSideControlLike =
                centerX <= playerBounds.left + playerBounds.width() * 0.48f &&
                    bounds.width() <= width * 0.32f &&
                    bounds.height() <= height * 0.14f &&
                    (
                        viewId.contains("button") ||
                            viewId.contains("control") ||
                            className.contains("button") ||
                            className.contains("image") ||
                            desc.isNotBlank()
                        )
            val actionable =
                node.isClickable ||
                    node.actionList?.any { it.id == AccessibilityNodeInfo.ACTION_CLICK } == true ||
                    firstClickableAncestorInPackage(node, "com.google.android.youtube") != null
            if ((playPauseId || playPauseLabel || leftSideControlLike) && actionable) {
                pauseCandidates.add(node)
            }
            pauseCandidates.size >= 10
        }
        if (pauseCandidates.isEmpty()) return false

        var tapped = false
        pauseCandidates.forEach { pauseNode ->
            val pauseBounds = Rect()
            runCatching { pauseNode.getBoundsInScreen(pauseBounds) }.getOrNull()
            if (pauseBounds.isEmpty) return@forEach

            val parentSiblingClicked = closeYouTubeMiniPlayerFromPauseSiblings(pauseNode, playerBounds, reason)
            if (parentSiblingClicked) return true

            val closeSibling = findRightSideMiniPlayerActionNode(root, playerBounds, pauseBounds)
            val siblingClicked = dismissOrClickNodeOrClickableParent(closeSibling)
            if (siblingClicked) {
                appendBlockingLog(
                    category = "yt_mini_close",
                    key = "yt-mini-close-pause-axis-node|$reason",
                    message = "reason=$reason pause=${pauseBounds.left},${pauseBounds.top},${pauseBounds.right},${pauseBounds.bottom} clicked=true",
                    throttleMs = 500L
                )
                return true
            }

            val y = pauseBounds.exactCenterY().coerceIn(playerBounds.top + 1f, playerBounds.bottom - 1f)
            val xCandidates = listOf(
                playerBounds.right - width * 0.025f,
                playerBounds.right - width * 0.045f,
                playerBounds.right - width * 0.065f,
                playerBounds.right - width * 0.095f,
                playerBounds.left + playerBounds.width() * 0.88f,
                playerBounds.left + playerBounds.width() * 0.94f,
                playerBounds.left + playerBounds.width() * 0.98f
            )
            xCandidates.forEach { x ->
                if (tapScreenAtPoint(x, y)) tapped = true
            }
        }
        appendBlockingLog(
            category = "yt_mini_close",
            key = "yt-mini-close-pause-axis-tap|$reason",
            message = "reason=$reason candidates=${pauseCandidates.size} tapped=$tapped",
            throttleMs = 500L
        )
        return tapped
    }

    private fun closeYouTubeMiniPlayerFromPauseSiblings(
        workingPauseNode: AccessibilityNodeInfo,
        playerBounds: Rect,
        reason: String
    ): Boolean {
        val pauseBounds = Rect()
        runCatching { workingPauseNode.getBoundsInScreen(pauseBounds) }.getOrNull()
        if (pauseBounds.isEmpty) return false

        var container = runCatching { workingPauseNode.parent }.getOrNull()
        var hops = 0
        while (container != null && hops < 5) {
            val closeNode = findBestRightSideMiniPlayerControlInContainer(container, playerBounds, pauseBounds)
            val clicked = dismissOrClickNodeOrClickableParent(closeNode)
            if (clicked) {
                appendBlockingLog(
                    category = "yt_mini_close",
                    key = "yt-mini-close-parent-sibling|$reason",
                    message = "reason=$reason hops=$hops pause=${pauseBounds.left},${pauseBounds.top},${pauseBounds.right},${pauseBounds.bottom}",
                    throttleMs = 500L
                )
                return true
            }
            container = runCatching { container?.parent }.getOrNull()
            hops++
        }

        return false
    }

    private fun findBestRightSideMiniPlayerControlInContainer(
        container: AccessibilityNodeInfo,
        playerBounds: Rect,
        pauseBounds: Rect
    ): AccessibilityNodeInfo? {
        data class WorkItem(val node: AccessibilityNodeInfo, val depth: Int)

        val width = resources.displayMetrics.widthPixels.coerceAtLeast(1)
        val height = resources.displayMetrics.heightPixels.coerceAtLeast(1)
        val pauseCenterX = pauseBounds.exactCenterX()
        val pauseCenterY = pauseBounds.exactCenterY()
        val maxYDistance = maxOf(52f, playerBounds.height() * 0.72f)
        val bounds = Rect()
        val stack = ArrayDeque<WorkItem>()
        stack.addLast(WorkItem(container, 0))
        var visited = 0
        var best: AccessibilityNodeInfo? = null
        var bestScore = Int.MIN_VALUE

        while (stack.isNotEmpty() && visited < 120) {
            val item = stack.removeLast()
            val node = item.node
            visited++

            runCatching { node.getBoundsInScreen(bounds) }.getOrNull()
            if (!bounds.isEmpty) {
                val nodePkg = node.packageName?.toString()?.lowercase(Locale.getDefault()).orEmpty()
                val centerX = bounds.exactCenterX()
                val centerY = bounds.exactCenterY()
                val text = node.text?.toString().orEmpty()
                val desc = node.contentDescription?.toString().orEmpty()
                val viewId = node.viewIdResourceName?.lowercase(Locale.getDefault()).orEmpty()
                val signal = "$text $desc $viewId".lowercase(Locale.getDefault())
                val samePauseNode =
                    abs(centerX - pauseCenterX) < 2f &&
                        abs(centerY - pauseCenterY) < 2f &&
                        abs(bounds.width() - pauseBounds.width()) < 2 &&
                        abs(bounds.height() - pauseBounds.height()) < 2
                val closeSignal =
                    anyNeedleMatches(signal, YT_MINI_PLAYER_CLOSE_LABELS) ||
                        viewId.contains("close") ||
                        viewId.contains("dismiss")
                val sameAxisRightSide =
                    centerX > pauseCenterX &&
                        centerX >= playerBounds.left + playerBounds.width() * 0.55f &&
                        centerX <= playerBounds.right + width * 0.04f &&
                        centerY >= playerBounds.top - height * 0.04f &&
                        centerY <= playerBounds.bottom + height * 0.04f &&
                        abs(centerY - pauseCenterY) <= maxYDistance
                val smallEnough =
                    bounds.width() <= width * 0.34f &&
                        bounds.height() <= height * 0.18f
                val actionable =
                    node.isClickable ||
                        node.actionList?.any {
                            it.id == AccessibilityNodeInfo.ACTION_CLICK ||
                                it.id == AccessibilityNodeInfo.ACTION_DISMISS
                        } == true ||
                        firstClickableAncestorInPackage(node, "com.google.android.youtube") != null

                if (
                    !samePauseNode &&
                    (nodePkg.isBlank() || nodePkg == "com.google.android.youtube") &&
                    actionable &&
                    smallEnough &&
                    (closeSignal || sameAxisRightSide)
                ) {
                    val closeScore = if (closeSignal) 1_000_000 else 0
                    val rightScore = centerX.toInt()
                    val yScore = (height - abs(centerY - pauseCenterY).toInt())
                    val sizeScore = maxOf(0, 20_000 - bounds.width() * bounds.height())
                    val score = closeScore + rightScore + yScore + sizeScore
                    if (score > bestScore) {
                        bestScore = score
                        best = node
                    }
                }
            }

            if (item.depth < 4) {
                val childCount = runCatching { node.childCount }.getOrDefault(0)
                for (i in childCount - 1 downTo 0) {
                    val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
                    stack.addLast(WorkItem(child, item.depth + 1))
                }
            }
        }

        return best
    }

    private fun findRightSideMiniPlayerActionNode(
        root: AccessibilityNodeInfo,
        playerBounds: Rect,
        pauseBounds: Rect
    ): AccessibilityNodeInfo? {
        val width = resources.displayMetrics.widthPixels.coerceAtLeast(1)
        val height = resources.displayMetrics.heightPixels.coerceAtLeast(1)
        val bounds = Rect()
        var best: AccessibilityNodeInfo? = null
        var bestScore = Int.MIN_VALUE
        val pauseCenterY = pauseBounds.exactCenterY()

        findAnyNode(root) { node ->
            val nodePkg = node.packageName?.toString()?.lowercase(Locale.getDefault()).orEmpty()
            if (nodePkg.isNotBlank() && nodePkg != "com.google.android.youtube") return@findAnyNode false

            runCatching { node.getBoundsInScreen(bounds) }.getOrNull()
            if (bounds.isEmpty) return@findAnyNode false
            val centerX = bounds.exactCenterX()
            val centerY = bounds.exactCenterY()
            if (centerX <= pauseBounds.exactCenterX()) return@findAnyNode false
            if (centerX < playerBounds.left || centerX > playerBounds.right) return@findAnyNode false
            if (centerY < playerBounds.top || centerY > playerBounds.bottom) return@findAnyNode false
            if (abs(centerY - pauseCenterY) > height * 0.12f) return@findAnyNode false
            if (bounds.width() > width * 0.34f || bounds.height() > height * 0.18f) return@findAnyNode false

            val text = node.text?.toString().orEmpty()
            val desc = node.contentDescription?.toString().orEmpty()
            val viewId = node.viewIdResourceName?.lowercase(Locale.getDefault()).orEmpty()
            val signal = "$text $desc $viewId".lowercase(Locale.getDefault())
            val actionable =
                node.isClickable ||
                    node.actionList?.any {
                        it.id == AccessibilityNodeInfo.ACTION_CLICK ||
                            it.id == AccessibilityNodeInfo.ACTION_DISMISS
                    } == true ||
                    firstClickableAncestorInPackage(node, "com.google.android.youtube") != null
            if (!actionable) return@findAnyNode false

            val closeScore = if (
                anyNeedleMatches(signal, YT_MINI_PLAYER_CLOSE_LABELS) ||
                viewId.contains("close") ||
                viewId.contains("dismiss")
            ) 1_000_000 else 0
            val rightScore = centerX.toInt()
            val yScore = (height - abs(centerY - pauseCenterY).toInt())
            val score = closeScore + rightScore + yScore
            if (score > bestScore) {
                bestScore = score
                best = node
            }
            false
        }

        return best
    }

    private fun swipeYouTubeMiniPlayerAway(root: AccessibilityNodeInfo, reason: String): Boolean {
        val playerBounds = findYouTubeMiniPlayerBounds(root) ?: return false
        val width = resources.displayMetrics.widthPixels.coerceAtLeast(1)
        val height = resources.displayMetrics.heightPixels.coerceAtLeast(1)
        val fromX = playerBounds.exactCenterX()
        val fromY = playerBounds.exactCenterY()
        val toX = minOf(width - 4f, playerBounds.right + width * 0.36f)
        val toY = minOf(height - 4f, playerBounds.bottom + playerBounds.height() * 1.35f)
        val swiped =
            swipeScreen(fromX, fromY, toX, fromY, durationMs = 220L) ||
                swipeScreen(playerBounds.left + playerBounds.width() * 0.25f, fromY, toX, fromY, durationMs = 260L) ||
                swipeScreen(fromX, fromY, fromX, toY, durationMs = 220L) ||
                swipeScreen(playerBounds.left + playerBounds.width() * 0.78f, fromY, fromX, toY, durationMs = 220L)
        appendBlockingLog(
            category = "yt_mini_close",
            key = "yt-mini-close-swipe|$reason",
            message = "reason=$reason bounds=${playerBounds.left},${playerBounds.top},${playerBounds.right},${playerBounds.bottom} swiped=$swiped",
            throttleMs = 500L
        )
        return swiped
    }

    private fun tapYouTubeMiniPlayerCloseByBounds(root: AccessibilityNodeInfo, reason: String): Boolean {
        val playerBounds = findYouTubeMiniPlayerBounds(root) ?: return false
        val closeX1 = playerBounds.right - playerBounds.width() * 0.06f
        val closeX2 = playerBounds.right - resources.displayMetrics.widthPixels.coerceAtLeast(1) * 0.035f
        val closeX3 = playerBounds.left + playerBounds.width() * 0.94f
        val closeY1 = playerBounds.exactCenterY()
        val closeY2 = playerBounds.top + playerBounds.height() * 0.42f
        val closeY3 = playerBounds.top + playerBounds.height() * 0.62f

        val clicked =
            tapScreenAtPoint(closeX1, closeY1) ||
                tapScreenAtPoint(closeX2, closeY1) ||
                tapScreenAtPoint(closeX3, closeY1) ||
                tapScreenAtPoint(closeX1, closeY2) ||
                tapScreenAtPoint(closeX1, closeY3)

        appendBlockingLog(
            category = "yt_mini_close",
            key = "yt-mini-close-bounds|$reason",
            message = "reason=$reason bounds=${playerBounds.left},${playerBounds.top},${playerBounds.right},${playerBounds.bottom} clicked=$clicked",
            throttleMs = 500L
        )
        return clicked
    }

    private fun maybeBlockYouTubeFloatingPlayer(
        event: AccessibilityEvent?,
        reason: String,
        rootOverride: AccessibilityNodeInfo? = null,
        force: Boolean = false
    ): Boolean {
        val pkg = "com.google.android.youtube"
        if (!SwitchModeStore.isEnabled(this)) return false
        val blockMiniPlayer = inAppSurfaceRuleEnabled(BlockingToggleKeys.KEY_BLOCK_YT_MINI_PLAYER)
        val blockPictureInPicture = inAppSurfaceRuleEnabled(BlockingToggleKeys.KEY_BLOCK_YT_PIP)
        val blockShorts = inAppSurfaceRuleEnabled(BlockingToggleKeys.KEY_BLOCK_YT_SHORTS)
        if (!blockMiniPlayer && !blockPictureInPicture && !blockShorts) return false

        val now = System.currentTimeMillis()
        if (!force && now - lastYouTubeFloatingPlayerBlockAt < 420L) return false

        val root = rootOverride ?: youtubeCurrentRoot(event)
        val eventSignal = youtubeEventSourceSignal(event, maxHops = 3)
        val eventRealPip = isLikelyYouTubePictureInPicture(root, event) ||
            anyNeedleMatches(eventSignal, YT_REAL_PIP_LABELS)
        val windowPip = isYouTubePictureInPictureWindowVisible()
        val miniVisible =
            root?.let { isLikelyYouTubeMiniPlayerVisible(it) || hasYouTubeMiniPlayerGeometry(it) } == true ||
                anyNeedleMatches(eventSignal, YT_MINI_PLAYER_NAME_LABELS)

        val selectedSurface = root?.let { detectYouTubeSelectedSurface(it) ?: resolveYouTubeSurfaceFromEvent(event) }
        val explicitShortsFloatingContext =
            eventRealPip ||
                windowPip ||
                selectedSurface == "yt:shorts" ||
                isYouTubeShortsEntryEvent(event)
        val shortsFloating =
            blockShorts &&
                (eventRealPip || windowPip || (miniVisible && explicitShortsFloatingContext)) &&
                root?.let { isLikelyYouTubeShortsPlayer(it, event) || isYouTubeHomeFeedShortsPlayer(it, event) || hasYouTubeDeepShortsSignal(it) } == true
        if (shortsFloating) {
            lastYouTubeFloatingPlayerBlockAt = now
            logInAppSurfaceDetect(
                pkg,
                "yt:shorts",
                enabled = true,
                event = event,
                detail = "floating_shorts reason=$reason realPip=$eventRealPip windowPip=$windowPip mini=$miniVisible"
            )
            if (eventRealPip || windowPip) {
                runCatching { blockLaunchController.pauseActiveMediaPlayback() }
                killYouTubePictureInPicture(pkg)
                return true
            }
            return closeYouTubeShortsPlayer("floating_$reason", root)
        }

        if (!eventRealPip && !windowPip && !miniVisible) return false
        val surfaceKey = if (eventRealPip || windowPip) "yt:pip" else "yt:miniplayer"
        if ((surfaceKey == "yt:pip" && !blockPictureInPicture) || (surfaceKey == "yt:miniplayer" && !blockMiniPlayer)) {
            return false
        }
        lastYouTubeFloatingPlayerBlockAt = now

        logInAppSurfaceDetect(
            pkg,
            surfaceKey,
            enabled = true,
            event = event,
            detail = "floating reason=$reason realPip=$eventRealPip windowPip=$windowPip mini=$miniVisible"
        )

        clearSurfaceEvidence(surfaceKey)
        clearSurfaceHintForPackage(pkg)

        if (eventRealPip || windowPip) {
            runCatching { blockLaunchController.pauseActiveMediaPlayback() }
            dismissYouTubeMiniPlayer("pip_rule_$reason", allowFallbackTap = true)
            killYouTubePictureInPicture(pkg)
            return true
        }

        blockYouTubeMiniPlayer(reason)
        return true
    }

    private fun maybeBlockYouTubeHomeShortsBeforeDedupe(event: AccessibilityEvent?): Boolean {
        if (!SwitchModeStore.isEnabled(this)) return false
        if (!inAppSurfaceRuleEnabled(BlockingToggleKeys.KEY_BLOCK_YT_SHORTS)) return false

        val type = event?.eventType ?: return false
        val relevant =
            type == AccessibilityEvent.TYPE_VIEW_CLICKED ||
                type == AccessibilityEvent.TYPE_VIEW_SELECTED ||
                type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
                type == AccessibilityEvent.TYPE_WINDOWS_CHANGED
        if (!relevant) return false

        val pkg = "com.google.android.youtube"
        val now = System.currentTimeMillis()
        if (surfaceGuardActive(pkg, "yt:shorts", now)) return false

        val root = youtubeCurrentRoot(event) ?: return false
        val detected =
            isYouTubeHomeShortsShelfEvent(event, root) ||
                isYouTubeHomeFeedShortsPlayer(root, event)
        if (!detected) return false

        rememberSurfaceHint(pkg, "yt:shorts", now)
        currentSurfaceKey = "yt:shorts"
        currentSurfacePkg = pkg
        runCatching { blockLaunchController.pauseActiveMediaPlayback() }
        softBlockSurface(
            pkg = pkg,
            appLabel = safeAppLabel(pkg),
            title = getString(R.string.blocking_surface_blocked_title, getString(R.string.in_app_surface_shorts_label)),
            message = surfaceUsageLine("yt:shorts", 0),
            backCount = 0,
            deferNavigationUntilAcknowledge = false,
            returnToPackageOnClose = true,
            forceShow = true,
            prePopupYouTubeHome = true,
            prePopupYouTubeCloseShorts = false,
            postAcknowledgeYouTubeCleanupShorts = true,
            postAcknowledgeYouTubeCleanupMini = false
        )
        return true
    }

    private fun blockYouTubeMiniPlayer(reason: String) {
        val pkg = "com.google.android.youtube"
        val now = System.currentTimeMillis()
        inAppGraceUntilByPkg[pkg] = now + YT_MINI_PLAYER_CLEANUP_GRACE_MS
        clearSurfaceEvidence("yt:shorts")
        clearSurfaceHintForPackage(pkg)
        surfaceBlockGuardUntil["$pkg|yt:shorts"] = now + 2_800L
        attemptYouTubeMiniPlayerCloseStrategy("mini_rule_${reason}_immediate", "axis")

        listOf(
            120L to "axis",
            320L to "action",
            700L to "bounds",
            1_100L to "swipe",
            1_600L to "all"
        ).forEach { (delay, strategy) ->
            handler.postDelayed({
                runCatching {
                    attemptYouTubeMiniPlayerCloseStrategy("mini_rule_${reason}_retry_${delay}_$strategy", strategy)
                }
            }, delay)
        }
    }

    private fun attemptYouTubeMiniPlayerCloseStrategy(reason: String, strategy: String): Boolean {
        val root = youtubeCurrentRoot() ?: return false
        if (!isRootFromPackage(root, "com.google.android.youtube")) return false
        if (!isLikelyYouTubeMiniPlayerVisible(root) && !hasYouTubeMiniPlayerGeometry(root)) return false

        return when (strategy) {
            "axis" -> closeYouTubeMiniPlayerFromPlayPauseAxis(root, reason)
            "action" -> dismissYouTubeMiniPlayerByAccessibilityAction(root, reason)
            "bounds" -> tapYouTubeMiniPlayerCloseByBounds(root, reason)
            "swipe" -> swipeYouTubeMiniPlayerAway(root, reason)
            else -> dismissYouTubeMiniPlayer(reason, allowFallbackTap = true)
        }
    }

    private fun cleanupYouTubeShortsAfterAck(reason: String): Boolean {
        val root = youtubeCurrentRoot() ?: return false
        if (!isRootFromPackage(root, "com.google.android.youtube")) return false

        val stillShorts =
            isYouTubeShortsScreen(root) ||
                isYouTubeHomeFeedShortsPlayer(root) ||
                isLikelyYouTubeShortsPlayer(root)
        if (!stillShorts) {
            tryNavigateYouTubeToAllowedSafeSurface(root, reason)
            return false
        }

        val now = System.currentTimeMillis()
        if (now - lastYouTubeShortsCloseAt < 1_800L) {
            tryNavigateYouTubeToAllowedSafeSurface(root, reason)
            return true
        }

        return closeYouTubeShortsPlayer(reason, root, recoverToYouTubeHome = true)
    }

    private fun closeYouTubeShortsPlayer(
        reason: String,
        root: AccessibilityNodeInfo? = youtubeCurrentRoot(),
        recoverToYouTubeHome: Boolean = false
    ): Boolean {
        val r = root ?: return false
        if (!isRootFromPackage(r, "com.google.android.youtube") && !containsPackageNode(r, "com.google.android.youtube")) {
            return false
        }
        if (!isYouTubeShortsScreen(r) && !hasYouTubeDeepShortsSignal(r)) return false

        val now = System.currentTimeMillis()
        if (now - lastYouTubeShortsCloseAt < 320L) return true
        lastYouTubeShortsCloseAt = now

        runCatching { blockLaunchController.pauseActiveMediaPlayback() }
        dismissYouTubeMiniPlayer("shorts_close_$reason", allowFallbackTap = true)
        val backSent = runCatching { performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK) }.getOrDefault(false)
        surfaceBlockGuardUntil["com.google.android.youtube|yt:shorts"] = now + YT_SHORTS_REENTRY_GUARD_MS
        appendBlockingLog(
            category = "yt_shorts_close",
            key = "yt-shorts-close|$reason",
            message = "reason=$reason backSent=$backSent",
            throttleMs = 650L
        )
        handler.postDelayed({
            runCatching {
                dismissYouTubeMiniPlayer("shorts_close_${reason}_settle_120", allowFallbackTap = true)
                val retryRoot = youtubeCurrentRoot()
                tryNavigateYouTubeToAllowedSafeSurface(retryRoot, "shorts_close_${reason}_settle_120")
            }
        }, 120L)
        handler.postDelayed({
            runCatching {
                dismissYouTubeMiniPlayer("shorts_close_${reason}_settle_360", allowFallbackTap = true)
                val retryRoot = youtubeCurrentRoot()
                tryNavigateYouTubeToAllowedSafeSurface(retryRoot, "shorts_close_${reason}_settle_360")
            }
        }, 360L)
        if (recoverToYouTubeHome) {
            handler.postDelayed({
                runCatching {
                    val retryRoot = youtubeCurrentRoot()
                    tryNavigateYouTubeToAllowedSafeSurface(retryRoot, "shorts_close_${reason}_recover_720")
                }
            }, 720L)
            handler.postDelayed({
                runCatching {
                    val retryRoot = youtubeCurrentRoot()
                    tryNavigateYouTubeToAllowedSafeSurface(retryRoot, "shorts_close_${reason}_recover_1400")
                }
            }, 1_400L)
        }
        return backSent
    }

    private fun tryNavigateYouTubeToAllowedSafeSurface(root: AccessibilityNodeInfo?, reason: String): Boolean {
        val r = root ?: return false
        if (!isRootFromPackage(r, "com.google.android.youtube")) return false

        val homeBlocked = false
        val subscriptionsBlocked = inAppSurfaceRuleEnabled(BlockingToggleKeys.KEY_BLOCK_YT_SUBSCRIPTIONS)
        val youBlocked = inAppSurfaceRuleEnabled(BlockingToggleKeys.KEY_BLOCK_YT_YOU)

        val moved = when {
            !homeBlocked && tryNavigateYouTubeToHome(r) -> true
            !subscriptionsBlocked && tryNavigateYouTubeToSubscriptions(r) -> true
            !youBlocked && tryNavigateYouTubeToYou(r) -> true
            else -> false
        }

        appendBlockingLog(
            category = "yt_safe_redirect",
            key = "yt-safe-redirect|$reason",
            message = "reason=$reason moved=$moved homeBlocked=$homeBlocked subscriptionsBlocked=$subscriptionsBlocked youBlocked=$youBlocked",
            throttleMs = 900L
        )

        if (!moved && homeBlocked) {
            blockLaunchController.postHome()
            return true
        }
        return moved
    }

    private fun tryNavigateYouTubeToHome(root: AccessibilityNodeInfo?): Boolean {
        return tryNavigateYouTubeToTab(
            root = root,
            labels = YT_HOME_LABELS,
            fallbackX = 0.12f,
            fallbackY = 0.90f
        )
    }

    private fun tryNavigateYouTubeToSubscriptions(root: AccessibilityNodeInfo?): Boolean {
        return tryNavigateYouTubeToTab(
            root = root,
            labels = YT_SUBSCRIPTIONS_LABELS,
            fallbackX = 0.70f,
            fallbackY = 0.90f
        )
    }

    private fun tryNavigateYouTubeToYou(root: AccessibilityNodeInfo?): Boolean {
        return tryNavigateYouTubeToTab(
            root = root,
            labels = YT_YOU_LABELS,
            fallbackX = 0.92f,
            fallbackY = 0.90f
        )
    }

    private fun tryNavigateYouTubeToTab(
        root: AccessibilityNodeInfo?,
        labels: List<String>,
        fallbackX: Float,
        fallbackY: Float
    ): Boolean {
        val r = root ?: return false
        val ytPkg = "com.google.android.youtube"

        fun isTargetLabel(node: AccessibilityNodeInfo): Boolean {
            val nodePkg = node.packageName?.toString()?.lowercase(Locale.getDefault()).orEmpty()
            if (nodePkg != ytPkg) return false

            val t = node.text?.toString()?.lowercase(Locale.getDefault()).orEmpty()
            val cd = node.contentDescription?.toString()?.lowercase(Locale.getDefault()).orEmpty()
            return labels.any { t.contains(it) || cd.contains(it) }
        }

        fun isBottomNavigationNode(node: AccessibilityNodeInfo): Boolean {
            val bounds = Rect()
            runCatching { node.getBoundsInScreen(bounds) }.getOrNull()
            if (bounds.isEmpty) return false
            val height = resources.displayMetrics.heightPixels.coerceAtLeast(1)
            return bounds.exactCenterY() / height.toFloat() >= 0.68f
        }

        // Prefer bottom navigation/pivot candidates first.
        val navCandidate = findAnyNode(r) { n ->
            if (!isTargetLabel(n)) return@findAnyNode false
            if (!isBottomNavigationNode(n)) return@findAnyNode false
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
            if (!isTargetLabel(n) || n.isSelected) return@findAnyNode false
            if (!isBottomNavigationNode(n)) return@findAnyNode false
            val vid = n.viewIdResourceName?.lowercase(Locale.getDefault()).orEmpty()
            // Avoid accidental clicks on system controls outside YouTube.
            vid.startsWith("$ytPkg:id/") || vid.isBlank()
        }
        if (clickNodeOrClickableParent(genericCandidate)) return true

        val alreadySelectedNode = findAnyNode(r) { n -> isTargetLabel(n) && n.isSelected && isBottomNavigationNode(n) }
        if (alreadySelectedNode != null) return true

        return tapScreenAtRatio(fallbackX, fallbackY) || tapScreenAtRatio(fallbackX, 0.86f)
    }

    private fun isSnapchatStoryViewer(root: AccessibilityNodeInfo, event: AccessibilityEvent? = null): Boolean {
        if (nodeTextMatches(root, SNAP_STORY_VIEWER_LABELS) || eventTextMatches(event, SNAP_STORY_VIEWER_LABELS)) {
            return true
        }

        val storiesSurfaceActive =
            detectSnapchatSelectedSurface(root) == "snap:stories" ||
                resolveSnapchatSurfaceFromEvent(event, allowFocused = false) == "snap:stories" ||
                hasSelectedLabelInPackage(root, SNAP_STORIES_LABELS, "com.snapchat.android")
        if (!storiesSurfaceActive) return false

        return nodeTextMatches(root, SNAP_STORY_REPLY_LABELS) ||
            eventTextMatches(event, SNAP_STORY_REPLY_LABELS)
    }

    private fun isSnapchatChatContext(root: AccessibilityNodeInfo, event: AccessibilityEvent? = null): Boolean {
        if (isSnapchatStoryViewer(root, event)) return false

        val selected = detectSnapchatSelectedSurface(root)
        if (selected == "snap:stories") return false
        if (selected == "snap:chat") return true
        val eventSurface = resolveSnapchatSurfaceFromEvent(event, allowFocused = false)
        if (eventSurface == "snap:stories") return false
        if (eventSurface == "snap:chat") return true
        return nodeTextMatches(
            root,
            listOf(
                "send a chat",
                "send chat",
                "chat input",
                "type a chat",
                "message",
                "bitmoji",
                "call",
                "video call"
            )
        )
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

        val root = if (pkg == "com.google.android.youtube") {
            youtubeCurrentRoot(event)
        } else {
            currentRoot(event)
        } ?: run {
            perf.rootMisses++
            return
        }
        perf.inAppScans++

        fun timedBlockMsg(toggleOn: Boolean, surfaceKey: String, surfaceLabel: String): Pair<String, String>? {
            if (!toggleOn) return null
            // Timed in-app limits are ignored for now because they were hard to explain and debug reliably.
            return getString(R.string.blocking_surface_blocked_title, surfaceLabel) to surfaceUsageLine(surfaceKey, 0)
        }

        if (pkg == "com.google.android.youtube") {
            val blockYtHomeEnabled = false
            val blockYtShortsEnabled = inAppSurfaceRuleEnabled(BlockingToggleKeys.KEY_BLOCK_YT_SHORTS)
            val blockYtSubscriptionsEnabled = inAppSurfaceRuleEnabled(BlockingToggleKeys.KEY_BLOCK_YT_SUBSCRIPTIONS)
            val blockYtYouEnabled = inAppSurfaceRuleEnabled(BlockingToggleKeys.KEY_BLOCK_YT_YOU)
            val blockYtMiniPlayerEnabled = inAppSurfaceRuleEnabled(BlockingToggleKeys.KEY_BLOCK_YT_MINI_PLAYER)
            val blockYtPipEnabled = inAppSurfaceRuleEnabled(BlockingToggleKeys.KEY_BLOCK_YT_PIP)

            val ytTappedSurface = resolveYouTubeSurfaceFromEvent(event, allowFocused = true)
            val ytEnteredAt = appEnteredAtByPkg[pkg] ?: 0L
            val ytSettled = ytEnteredAt == 0L || (now - ytEnteredAt) >= 350L
            val ytSelectedSurface = if (ytSettled) detectYouTubeSelectedSurface(root) else null
            val ytShortsEntryNow = isYouTubeShortsEntryEvent(event) || isYouTubeHomeShortsShelfEvent(event, root)
            val ytHomeSurfaceCandidate = ytTappedSurface == "yt:home" || ytSelectedSurface == "yt:home"
            val ytExplicitShortsContext = ytTappedSurface == "yt:shorts" || ytSelectedSurface == "yt:shorts" || ytShortsEntryNow
            val ytShortsPlayerNow =
                when {
                    ytHomeSurfaceCandidate -> isYouTubeHomeFeedShortsPlayer(root, event)
                    ytExplicitShortsContext -> isLikelyYouTubeShortsPlayer(root, event) || hasYouTubeShortsPlayerControl(root)
                    else -> isYouTubeShortsScreen(root, event) ||
                        (eventTextMatches(event, YT_SHORTS_PLAYER_HINT_LABELS) && hasYouTubeShortsPlayerControl(root))
                }
            val ytPipEntryNow = isYouTubePipEntryEvent(event)
            val ytMiniPlayerNow = isLikelyYouTubeMiniPlayerVisible(root)
            val ytMiniPlayerGeometryNow = hasYouTubeMiniPlayerGeometry(root)
            val ytShortsGuardActive = surfaceGuardActive(pkg, "yt:shorts", now)

            if ((blockYtMiniPlayerEnabled || blockYtPipEnabled) && maybeBlockYouTubeFloatingPlayer(event, "in_app_scan", root)) return

            if (!ytShortsGuardActive && (ytShortsEntryNow || ytShortsPlayerNow)) {
                rememberSurfaceHint(pkg, "yt:shorts", now)
            }
            if (ytPipEntryNow) {
                rememberSurfaceHint(pkg, "yt:pip", now)
            }

            val ytHomeNow = (ytTappedSurface == "yt:home" || ytSelectedSurface == "yt:home") &&
                !ytShortsEntryNow &&
                !ytShortsPlayerNow &&
                !ytMiniPlayerNow &&
                !ytMiniPlayerGeometryNow
            if (ytHomeNow && !blockYtHomeEnabled) {
                clearSurfaceEvidence("yt:home", "yt:shorts", "yt:subscriptions", "yt:you")
                clearSurfaceHintForPackage(pkg)
                if (currentSurfacePkg == pkg) {
                    currentSurfaceKey = null
                    currentSurfacePkg = null
                }
            }

            fun handleYtSurface(
                surfaceKey: String,
                enabled: Boolean,
                detected: Boolean,
                label: String,
                hardHomeBlock: Boolean = false,
                backCount: Int = 1,
                deferNavigationUntilAcknowledge: Boolean = true,
                returnToPackageOnClose: Boolean = false,
                forceShow: Boolean = false,
                postAcknowledgeYouTubeHome: Boolean = false,
                postAcknowledgeYouTubeClose: Boolean = false,
                postAcknowledgeYouTubeCleanupShorts: Boolean = false,
                postAcknowledgeYouTubeCleanupMini: Boolean = true,
                prePopupPhoneHome: Boolean = false,
                prePopupYouTubeHome: Boolean = false
            ): Boolean {
                if (surfaceKey == "yt:shorts" && eventType == 0) {
                    clearSurfaceEvidence(surfaceKey)
                    return false
                }
                val conflictingTap = ytTappedSurface != null && ytTappedSurface != surfaceKey && ytTappedSurface != "yt:home"
                val conflictingSelected = ytSelectedSurface != null && ytSelectedSurface != surfaceKey && ytSelectedSurface != "yt:home"
                if (conflictingTap || conflictingSelected) {
                    clearSurfaceEvidence(surfaceKey)
                    return false
                }
                if (detected) {
                    logInAppSurfaceDetect(pkg, surfaceKey, enabled, event, "tapped=$ytTappedSurface selected=$ytSelectedSurface hardHome=$hardHomeBlock")
                }
                val strongCue = recentSurfaceHintMatches(pkg, surfaceKey, now) || ytTappedSurface == surfaceKey || ytSelectedSurface == surfaceKey
                val hit = surfaceConfirmed(surfaceKey, enabled && detected, required = if (strongCue || ytQuickEvent(eventType)) 1 else 2)
                if (!hit) return false

                currentSurfaceKey = surfaceKey
                currentSurfacePkg = pkg
                val msg = timedBlockMsg(enabled, surfaceKey, label) ?: return false
                val appLabel = safeAppLabel(pkg)

                if (surfaceKey == "yt:home") {
                    runCatching { blockLaunchController.pauseActiveMediaPlayback() }
                    tryNavigateYouTubeToAllowedSafeSurface(root, "home_block_pre_popup")
                    surfaceBlockGuardUntil["$pkg|yt:home"] = now + INAPP_POST_BLOCK_GRACE_MS
                }

                if (surfaceKey == "yt:shorts" || surfaceKey == "yt:subscriptions") {
                    // Pause before the surface redirect.
                    // Shorts uses a very fast in-app Home tap followed immediately by the popup, so the Home animation should be hidden underneath the blocker instead of appearing first.
                    runCatching { blockLaunchController.pauseActiveMediaPlayback() }
                    if (surfaceKey == "yt:shorts") {
                        dismissYouTubeMiniPlayer("pre_popup_shorts")
                    }
                    surfaceBlockGuardUntil["$pkg|$surfaceKey"] = now + maxOf(YT_SHORTS_REENTRY_GUARD_MS, INAPP_POST_BLOCK_GRACE_MS)
                }

                if (hardHomeBlock) {
                    homeThenBlockSurface(
                        pkg = pkg,
                        appLabel = appLabel,
                        title = msg.first,
                        message = msg.second,
                        surfaceKey = surfaceKey,
                        forceShow = forceShow
                    )
                } else {
                    softBlockSurface(
                        pkg,
                        appLabel,
                        msg.first,
                        msg.second,
                        backCount = backCount,
                        deferNavigationUntilAcknowledge = deferNavigationUntilAcknowledge,
                        returnToPackageOnClose = returnToPackageOnClose,
                        forceShow = forceShow,
                        postAcknowledgeYouTubeHome = postAcknowledgeYouTubeHome,
                        postAcknowledgeYouTubeClose = postAcknowledgeYouTubeClose,
                        postAcknowledgeYouTubeCleanupShorts = postAcknowledgeYouTubeCleanupShorts,
                        postAcknowledgeYouTubeCleanupMini = postAcknowledgeYouTubeCleanupMini,
                        prePopupPhoneHome = prePopupPhoneHome,
                        prePopupYouTubeHome = prePopupYouTubeHome
                    )
                }
                return true
            }

            val homeDetected =
                ytTappedSurface == "yt:home" ||
                    ytSelectedSurface == "yt:home" ||
                    ytHomeNow ||
                    isYouTubeHomeScreen(root, event)
            if (handleYtSurface(
                    surfaceKey = "yt:home",
                    enabled = blockYtHomeEnabled,
                    detected = homeDetected,
                    label = getString(R.string.in_app_surface_home_label),
                    hardHomeBlock = false,
                    backCount = 0,
                    deferNavigationUntilAcknowledge = false,
                    returnToPackageOnClose = false,
                    forceShow = true
                )) return

            val subscriptionsDetected =
                ytTappedSurface == "yt:subscriptions" ||
                    ytSelectedSurface == "yt:subscriptions" ||
                    recentSurfaceHintMatches(pkg, "yt:subscriptions", now) ||
                    isYouTubeSubscriptionsScreen(root, event)
            if (handleYtSurface(
                    surfaceKey = "yt:subscriptions",
                    enabled = blockYtSubscriptionsEnabled,
                    detected = subscriptionsDetected,
                    label = getString(R.string.in_app_surface_subscriptions_label),
                    hardHomeBlock = false,
                    backCount = 0,
                    deferNavigationUntilAcknowledge = true,
                    returnToPackageOnClose = false,
                    forceShow = true,
                    postAcknowledgeYouTubeHome = true
                )) return

            val ytHomeSelectedWithoutShortsPlayer =
                ytSelectedSurface == "yt:home" &&
                    !ytShortsEntryNow &&
                    !ytShortsPlayerNow &&
                    !ytMiniPlayerNow &&
                    !ytMiniPlayerGeometryNow
            val ytBottomShortsNow =
                !ytShortsGuardActive &&
                    (ytTappedSurface == "yt:shorts" || ytSelectedSurface == "yt:shorts")
            val shortsDetected =
                !ytShortsGuardActive && (
                    ytBottomShortsNow ||
                    ytShortsEntryNow ||
                    ytShortsPlayerNow ||
                    isYouTubeShortsScreen(root, event)
                    )
            if (handleYtSurface(
                    surfaceKey = "yt:shorts",
                    enabled = blockYtShortsEnabled,
                    detected = shortsDetected,
                    label = getString(R.string.in_app_surface_shorts_label),
                    hardHomeBlock = false,
                    backCount = 0,
                    deferNavigationUntilAcknowledge = false,
                    returnToPackageOnClose = true,
                    forceShow = true,
                    postAcknowledgeYouTubeHome = false,
                    postAcknowledgeYouTubeClose = false,
                    postAcknowledgeYouTubeCleanupShorts = true,
                    postAcknowledgeYouTubeCleanupMini = false,
                    prePopupPhoneHome = false,
                    prePopupYouTubeHome = true
                )) return

            val youDetected =
                ytTappedSurface == "yt:you" ||
                    ytSelectedSurface == "yt:you" ||
                    recentSurfaceHintMatches(pkg, "yt:you", now) ||
                    isYouTubeYouScreen(root, event)
            if (handleYtSurface(
                    surfaceKey = "yt:you",
                    enabled = blockYtYouEnabled,
                    detected = youDetected,
                    label = getString(R.string.in_app_surface_you_label)
                )) return

            val ytPipHintNow = recentSurfaceHintMatches(pkg, "yt:pip", now)
            val ytRealPipNow = ytPipEntryNow || isLikelyYouTubePictureInPicture(root, event)
            if (blockYtMiniPlayerEnabled && (ytMiniPlayerNow || ytMiniPlayerGeometryNow) && !ytRealPipNow && maybeBlockYouTubeFloatingPlayer(event, "in_app_mini", root)) return
            if (blockYtPipEnabled && (ytPipHintNow || ytRealPipNow)) {
                if (maybeBlockYouTubeFloatingPlayer(event, "in_app_pip", root, force = true)) return
            }

            if (blockYtPipEnabled && (ytTappedSurface == "yt:home" || ytSelectedSurface == "yt:home")) {
                clearSurfaceEvidence("yt:pip")
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

            val instagramPositionSurface = detectInstagramSelectedSurfaceByPosition(root) ?: instagramSurfaceFromEventPosition(event)
            val homeSelectedNow = instagramPositionSurface == "ig:home" || hasSelectedLabel(root, IG_HOME_LABELS)
            val reelsTabSelectedNow = instagramPositionSurface == "ig:reels" || hasSelectedLabel(root, IG_REELS_LABELS)
            val exploreTabSelectedNow = instagramPositionSurface == "ig:explore" || hasSelectedLabel(root, IG_EXPLORE_LABELS)
            val searchScreenNow = isInstagramSearchScreen(root, event)
            val reelsHintNow = recentSurfaceHintMatches(pkg, "ig:reels", now)
            val exploreHintNow = recentSurfaceHintMatches(pkg, "ig:explore", now)
            val searchHintNow = recentSurfaceHintMatches(pkg, "ig:search", now)

            val blockIgReelsEnabled = inAppSurfaceRuleEnabled(BlockingToggleKeys.KEY_BLOCK_IG_REELS)
            val blockIgExploreEnabled = inAppSurfaceRuleEnabled(BlockingToggleKeys.KEY_BLOCK_IG_EXPLORE)
            val blockIgSearchEnabled = inAppSurfaceRuleEnabled(BlockingToggleKeys.KEY_BLOCK_IG_SEARCH)
            val blockIgStoriesEnabled = inAppSurfaceRuleEnabled(BlockingToggleKeys.KEY_BLOCK_IG_STORIES)
            val blockIgCommentsEnabled = inAppSurfaceRuleEnabled(BlockingToggleKeys.KEY_BLOCK_IG_COMMENTS)

            val storiesViewerNow = isInstagramStoriesViewer(root, event)
            val commentsVisibleNow = isInstagramCommentsVisible(root)
            val feedCommentsContext = commentsVisibleNow && homeSelectedNow && !reelsTabSelectedNow && !exploreTabSelectedNow
            val messagesContextNow = isInstagramMessagesScreen(root, event)
            val profileContextNow = isInstagramProfileScreen(root, event)

            fun immediateInstagramSurfaceBlock(surfaceKey: String, enabled: Boolean, label: String, detected: Boolean, backCount: Int = 2): Boolean {
                if (!detected || !enabled) return false
                if (messagesContextNow || profileContextNow || storiesViewerNow || feedCommentsContext) return false
                logInAppSurfaceDetect(pkg, surfaceKey, enabled, event, "immediate_fast_path state=$stateById pos=$instagramPositionSurface")
                currentSurfaceKey = surfaceKey
                currentSurfacePkg = pkg
                val msg = timedBlockMsg(enabled, surfaceKey, label) ?: return false
                val appLabel = safeAppLabel(pkg)
                softBlockSurface(pkg, appLabel, msg.first, msg.second, backCount = backCount, deferNavigationUntilAcknowledge = true, forceShow = true)
                return true
            }

            // Explicit tab clicks/selected bottom-nav positions should block immediately.
            // The more conservative state machine below is still kept for scroll/content events, but it was too easy for the guards to suppress every Instagram block on some builds.
            val explicitReelsHit = reelsTabSelectedNow || instagramPositionSurface == "ig:reels" || reelsHintNow || isInstagramBottomNavEvent(event, IG_REELS_LABELS)
            val explicitExploreHit = exploreTabSelectedNow || instagramPositionSurface == "ig:explore" || exploreHintNow || isInstagramBottomNavEvent(event, IG_EXPLORE_LABELS)
            val explicitSearchHit = searchScreenNow || searchHintNow
            if (immediateInstagramSurfaceBlock("ig:reels", blockIgReelsEnabled, getString(R.string.in_app_surface_reels_label), explicitReelsHit)) return
            if (immediateInstagramSurfaceBlock("ig:explore", blockIgExploreEnabled, getString(R.string.in_app_surface_explore_label), explicitExploreHit)) return
            if (immediateInstagramSurfaceBlock("ig:search", blockIgSearchEnabled, getString(R.string.in_app_surface_search_label), explicitSearchHit)) return

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

            // If home appears selected (and reels tab is not), treat reels detections from scroll/content events as suspicious to avoid delayed false blocks while user just scrolls the feed.
            // Keep this intentionally strict to avoid false Reels hits while scrolling Home feed.
            val reelsEventStrongCue = eventTextMatches(event, IG_REELS_STRONG_EVENT_LABELS)
            val suspiciousHomeReels =
                stateById == "reels" &&
                    homeSelectedNow &&
                    !reelsTabSelectedNow &&
                    (!reelsEventStrongCue || homePassiveEvent) &&
                    (eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED ||
                        eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)

            val exploreEventStrongCue = eventTextMatches(event, IG_EXPLORE_LABELS)
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
            val reelsDetected = allowReelsDetect && (reelsState == "reels" || reelsHintNow)
            if (reelsDetected) {
                logInAppSurfaceDetect(pkg, "ig:reels", blockIgReelsEnabled, event, "state=$reelsState hint=$reelsHintNow")
            }
            val reelsHit = surfaceConfirmed("ig:reels", blockIgReelsEnabled && reelsDetected, required = if (reelsHintNow) 1 else reelsRequired)
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
                        exploreHintNow ||
                            explicitExploreContext ||
                            (!homeSelectedNow && exploreState == "explore") ||
                            (searchInteractiveEvent && exploreState == "explore")
                    )

            val exploreDetected =
                !suspiciousHomeExplore &&
                    !homePassiveEvent &&
                    !searchScreenNow &&
                    (exploreHintNow || exploreState == "explore" || explicitExploreContext)

            val exploreRequired = when {
                explicitExploreContext -> 1
                eventType == AccessibilityEvent.TYPE_VIEW_CLICKED ||
                    eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED ||
                    eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> 1
                eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED ||
                    eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> if (homeSelectedNow && !exploreTabSelectedNow && !searchScreenNow) 3 else 2
                else -> 2
            }
            if (allowExploreDetect && exploreDetected) {
                logInAppSurfaceDetect(pkg, "ig:explore", blockIgExploreEnabled, event, "state=$exploreState hint=$exploreHintNow searchNow=$searchScreenNow")
            }
            val exploreHit = surfaceConfirmed("ig:explore", blockIgExploreEnabled && allowExploreDetect && exploreDetected, required = if (exploreHintNow) 1 else exploreRequired)
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
                    !messagesContextNow &&
                    !profileContextNow &&
                    (
                        searchHintNow ||
                            exploreTabSelectedNow ||
                            (!homeSelectedNow && searchScreenNow) ||
                            (searchInteractiveEvent && searchScreenNow)
                    )
            val searchDetected = (searchScreenNow || searchHintNow) && allowSearchDetect && stateById != "reels"
            if (searchDetected) {
                logInAppSurfaceDetect(pkg, "ig:search", blockIgSearchEnabled, event, "state=$stateById hint=$searchHintNow messages=$messagesContextNow")
            }
            val searchHit = surfaceConfirmed(
                "ig:search",
                blockIgSearchEnabled && searchDetected,
                required = if (searchHintNow || allowSearchDetect) 1 else 2
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
            val blockHomeEnabled = inAppSurfaceRuleEnabled(BlockingToggleKeys.KEY_BLOCK_X_HOME)
            val blockSearchEnabled = inAppSurfaceRuleEnabled(BlockingToggleKeys.KEY_BLOCK_X_SEARCH)
            val blockGrokEnabled = inAppSurfaceRuleEnabled(BlockingToggleKeys.KEY_BLOCK_X_GROK)
            val blockNotificationsEnabled = inAppSurfaceRuleEnabled(BlockingToggleKeys.KEY_BLOCK_X_NOTIFICATIONS)

            val homeNeedles = X_HOME_LABELS
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

            val searchNeedles = X_SEARCH_LABELS
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

            val grokNeedles = X_GROK_LABELS
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

            val notificationNeedles = X_NOTIFICATIONS_LABELS
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
            val blockSnapMapEnabled = inAppSurfaceRuleEnabled(BlockingToggleKeys.KEY_BLOCK_SNAP_MAP)
            val blockSnapStoriesEnabled = inAppSurfaceRuleEnabled(BlockingToggleKeys.KEY_BLOCK_SNAP_STORIES)
            val blockSnapSpotlightEnabled = inAppSurfaceRuleEnabled(BlockingToggleKeys.KEY_BLOCK_SNAP_SPOTLIGHT)
            val blockSnapFollowingEnabled = inAppSurfaceRuleEnabled(BlockingToggleKeys.KEY_BLOCK_SNAP_FOLLOWING)

            val snapTappedSurface = resolveSnapchatSurfaceFromEvent(event, allowFocused = false)
            val snapEnteredAt = appEnteredAtByPkg[pkg] ?: 0L
            val snapSettled = snapEnteredAt == 0L || (now - snapEnteredAt) >= 350L
            val snapSelectedSurface = if (snapSettled) detectSnapchatSelectedSurface(root) else null
            val snapSafeNow =
                snapTappedSurface == "snap:safe" ||
                    snapSelectedSurface == "snap:safe" ||
                    (snapSettled && hasSelectedLabelInPackage(root, SNAP_SAFE_LABELS, pkg))
            if (snapSafeNow) {
                clearSurfaceEvidence("snap:map", "snap:stories", "snap:spotlight", "snap:following")
                clearSurfaceHintForPackage(pkg)
                if (currentSurfacePkg == pkg) {
                    currentSurfaceKey = null
                    currentSurfacePkg = null
                }
            }

            val mapImmediate = snapchatImmediateSurfaceHit("snap:map", event)
            val mapDetected =
                mapImmediate ||
                    recentSurfaceHintMatches(pkg, "snap:map", now) ||
                    snapTappedSurface == "snap:map" ||
                    snapSelectedSurface == "snap:map" ||
                    (snapSettled && hasSelectedLabelInPackage(root, SNAP_MAP_LABELS, pkg))
            if (mapDetected) {
                logInAppSurfaceDetect(pkg, "snap:map", blockSnapMapEnabled, event, "tapped=$snapTappedSurface selected=$snapSelectedSurface immediate=$mapImmediate")
            }
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

            val snapChatContextNow = isSnapchatChatContext(root, event)
            val storiesImmediate = snapchatImmediateSurfaceHit("snap:stories", event)
            val storiesViewerDetected = !snapChatContextNow && isSnapchatStoryViewer(root, event)
            val storiesDetected =
                !snapChatContextNow &&
                    (storiesImmediate ||
                        storiesViewerDetected ||
                        recentSurfaceHintMatches(pkg, "snap:stories", now) ||
                        snapTappedSurface == "snap:stories" ||
                        snapSelectedSurface == "snap:stories" ||
                        (snapSettled && hasSelectedLabelInPackage(root, SNAP_STORIES_LABELS, pkg)))
            if (storiesDetected) {
                logInAppSurfaceDetect(pkg, "snap:stories", blockSnapStoriesEnabled, event, "tapped=$snapTappedSurface selected=$snapSelectedSurface immediate=$storiesImmediate viewer=$storiesViewerDetected")
            }
            val storiesHit = surfaceConfirmed(
                "snap:stories",
                blockSnapStoriesEnabled && storiesDetected,
                required = if (storiesViewerDetected || recentSurfaceHintMatches(pkg, "snap:stories", now) || snapTappedSurface == "snap:stories" || snapSelectedSurface == "snap:stories" || snapchatImmediateSurfaceHit("snap:stories", event)) 1 else 2
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
                    (snapSettled && hasSelectedLabelInPackage(root, SNAP_SPOTLIGHT_LABELS, pkg))
            if (spotlightDetected) {
                logInAppSurfaceDetect(pkg, "snap:spotlight", blockSnapSpotlightEnabled, event, "tapped=$snapTappedSurface selected=$snapSelectedSurface immediate=$spotlightImmediate")
            }
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
                    (snapSettled && hasSelectedLabelInPackage(root, SNAP_FOLLOWING_LABELS, pkg))
            if (followingDetected) {
                logInAppSurfaceDetect(pkg, "snap:following", blockSnapFollowingEnabled, event, "tapped=$snapTappedSurface selected=$snapSelectedSurface immediate=$followingImmediate")
            }
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

    private fun logInAppSurfaceDetect(
        pkg: String,
        surfaceKey: String,
        enabled: Boolean,
        event: AccessibilityEvent?,
        detail: String = ""
    ) {
        appendBlockingLog(
            category = "in_app_detect",
            key = "inapp-detect|$pkg|$surfaceKey",
            message = "pkg=$pkg surface=$surfaceKey enabled=$enabled event=${eventTypeLabel(event)}${if (detail.isBlank()) "" else " $detail"}",
            throttleMs = 3_500L
        )
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
            "com.google.android.youtube" -> {
                when (resolveYouTubeSurfaceFromEvent(event, allowFocused = false)) {
                    "yt:home" -> clearSurfaceHintForPackage(pkg)
                    "yt:shorts" -> rememberSurfaceHint(pkg, "yt:shorts", now)
                    "yt:subscriptions" -> rememberSurfaceHint(pkg, "yt:subscriptions", now)
                    "yt:you" -> rememberSurfaceHint(pkg, "yt:you", now)
                }
                if (isYouTubeShortsEntryEvent(event)) {
                    rememberSurfaceHint(pkg, "yt:shorts", now)
                    logInAppSurfaceDetect(pkg, "yt:shorts", true, event, "entry_hint")
                }
                if (isYouTubePipEntryEvent(event)) {
                    rememberSurfaceHint(pkg, "yt:pip", now)
                    logInAppSurfaceDetect(pkg, "yt:pip", true, event, "entry_hint")
                }
            }
            "com.instagram.android" -> {
                when (instagramSurfaceFromEventPosition(event)) {
                    "ig:home", "ig:profile" -> clearSurfaceHintForPackage(pkg)
                    "ig:reels" -> rememberSurfaceHint(pkg, "ig:reels", now)
                    "ig:explore" -> {
                        rememberSurfaceHint(pkg, "ig:explore", now)
                        rememberSurfaceHint(pkg, "ig:search", now)
                    }
                    else -> when {
                        isInstagramBottomNavEvent(event, IG_HOME_LABELS) -> clearSurfaceHintForPackage(pkg)
                        isInstagramBottomNavEvent(event, IG_REELS_LABELS) -> rememberSurfaceHint(pkg, "ig:reels", now)
                        isInstagramBottomNavEvent(event, IG_EXPLORE_LABELS) -> {
                            rememberSurfaceHint(pkg, "ig:explore", now)
                            rememberSurfaceHint(pkg, "ig:search", now)
                        }
                        eventOrSourceMatches(event, IG_MESSAGES_EVENT_LABELS) -> clearSurfaceHintForPackage(pkg)
                    }
                }
            }
            "com.twitter.android" -> {
                when {
                    eventOrSourceMatches(event, X_HOME_LABELS) -> rememberSurfaceHint(pkg, "x:foryou", now)
                    eventOrSourceMatches(event, X_SEARCH_LABELS) -> rememberSurfaceHint(pkg, "x:search", now)
                    eventOrSourceMatches(event, X_GROK_LABELS) -> rememberSurfaceHint(pkg, "x:grok", now)
                    eventOrSourceMatches(event, X_NOTIFICATIONS_LABELS) -> rememberSurfaceHint(pkg, "x:notifications", now)
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

    private fun surfaceGuardActive(pkg: String, surfaceKey: String, now: Long = System.currentTimeMillis()): Boolean {
        return now < (surfaceBlockGuardUntil["$pkg|$surfaceKey"] ?: 0L)
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
        // Keep Search detection strict.
        // Instagram Direct/Messages also contains a search field, so generic root text matching would incorrectly block DMs.
        if (isInstagramMessagesScreen(root, event) || isInstagramStoriesViewer(root, event)) return false

        val searchLabels = IG_EXPLORE_LABELS
        return hasSelectedLabel(root, searchLabels) ||
            instagramSurfaceFromEventPosition(event) == "ig:explore" ||
            isInstagramBottomNavEvent(event, searchLabels)
    }

    private fun isInstagramBottomNavEvent(event: AccessibilityEvent?, labels: List<String>): Boolean {
        val type = event?.eventType ?: return false
        if (type != AccessibilityEvent.TYPE_VIEW_CLICKED &&
            type != AccessibilityEvent.TYPE_VIEW_SELECTED &&
            type != AccessibilityEvent.TYPE_VIEW_FOCUSED) return false

        var current = runCatching { event.source }.getOrNull()
        val width = resources.displayMetrics.widthPixels.coerceAtLeast(1)
        val height = resources.displayMetrics.heightPixels.coerceAtLeast(1)
        var hops = 0
        val bounds = Rect()

        while (current != null && hops < 5) {
            val nodePkg = current.packageName?.toString()?.lowercase(Locale.getDefault()).orEmpty()
            if (nodePkg.isBlank() || nodePkg == "com.instagram.android") {
                runCatching { current.getBoundsInScreen(bounds) }.getOrNull()
                val centerY = if (bounds.isEmpty) 0f else bounds.exactCenterY() / height.toFloat()
                val widthRatio = if (bounds.isEmpty) 1f else bounds.width() / width.toFloat()
                val heightRatio = if (bounds.isEmpty) 1f else bounds.height() / height.toFloat()
                val directText = nodeTextOrDesc(current)
                val viewId = current.viewIdResourceName?.lowercase(Locale.getDefault()).orEmpty()

                if (centerY >= 0.70f && widthRatio <= 0.36f && heightRatio <= 0.24f &&
                    (anyNeedleMatches(directText, labels) || anyNeedleMatches(viewId, labels))) {
                    return true
                }
            }

            current = runCatching { current.parent }.getOrNull()
            hops++
        }

        return false
    }

    private fun instagramBottomSurfaceFromCenterX(centerX: Float): String? {
        // Typical Instagram bottom nav: Home | Search/Explore | Create | Reels | Profile.
        return when {
            centerX < 0.18f -> "ig:home"
            centerX < 0.38f -> "ig:explore"
            centerX in 0.58f..0.80f -> "ig:reels"
            centerX > 0.80f -> "ig:profile"
            else -> null
        }
    }

    private fun instagramSurfaceFromEventPosition(event: AccessibilityEvent?): String? {
        val type = event?.eventType ?: return null
        if (type != AccessibilityEvent.TYPE_VIEW_CLICKED &&
            type != AccessibilityEvent.TYPE_VIEW_SELECTED &&
            type != AccessibilityEvent.TYPE_VIEW_FOCUSED) return null

        var current = runCatching { event.source }.getOrNull()
        val width = resources.displayMetrics.widthPixels.coerceAtLeast(1)
        val height = resources.displayMetrics.heightPixels.coerceAtLeast(1)
        val bounds = Rect()
        var hops = 0
        while (current != null && hops < 5) {
            val nodePkg = current.packageName?.toString()?.lowercase(Locale.getDefault()).orEmpty()
            if (nodePkg.isBlank() || nodePkg == "com.instagram.android") {
                runCatching { current.getBoundsInScreen(bounds) }.getOrNull()
                if (!bounds.isEmpty) {
                    val centerY = bounds.exactCenterY() / height.toFloat()
                    val widthRatio = bounds.width() / width.toFloat()
                    val heightRatio = bounds.height() / height.toFloat()
                    if (centerY >= 0.70f && widthRatio <= 0.36f && heightRatio <= 0.24f) {
                        return instagramBottomSurfaceFromCenterX(bounds.exactCenterX() / width.toFloat())
                    }
                }
            }
            current = runCatching { current.parent }.getOrNull()
            hops++
        }
        return null
    }

    private fun detectInstagramSelectedSurfaceByPosition(root: AccessibilityNodeInfo): String? {
        val width = resources.displayMetrics.widthPixels.coerceAtLeast(1)
        val height = resources.displayMetrics.heightPixels.coerceAtLeast(1)
        val found = findAnyNode(root) { node ->
            val active = node.isSelected || node.isFocused || node.isAccessibilityFocused || node.isCheckedCompat()
            if (!active) return@findAnyNode false
            val nodePkg = node.packageName?.toString()?.lowercase(Locale.getDefault()).orEmpty()
            if (nodePkg.isNotBlank() && nodePkg != "com.instagram.android") return@findAnyNode false

            val bounds = Rect()
            runCatching { node.getBoundsInScreen(bounds) }.getOrNull()
            if (bounds.isEmpty) return@findAnyNode false
            val centerY = bounds.exactCenterY() / height.toFloat()
            if (centerY < 0.70f) return@findAnyNode false
            val widthRatio = bounds.width() / width.toFloat()
            val heightRatio = bounds.height() / height.toFloat()
            widthRatio <= 0.36f && heightRatio <= 0.24f
        } ?: return null

        val bounds = Rect()
        runCatching { found.getBoundsInScreen(bounds) }.getOrNull()
        if (bounds.isEmpty) return null
        return instagramBottomSurfaceFromCenterX(bounds.exactCenterX() / width.toFloat())
    }

    private fun isInstagramStoriesViewer(root: AccessibilityNodeInfo, event: AccessibilityEvent? = null): Boolean {
        // Keep this strict: the normal Instagram Home feed often contains a stories row.
        // Generic "story" text in the root would suppress Reels/Explore/Search blocking completely.
        return nodeTextMatches(root, IG_STORIES_VIEWER_LABELS) ||
            eventTextMatches(event, IG_STORIES_EVENT_LABELS)
    }

    private fun isInstagramCommentsVisible(root: AccessibilityNodeInfo): Boolean {
        return nodeTextMatches(root, IG_COMMENTS_LABELS)
    }

    private fun isInstagramMessagesScreen(root: AccessibilityNodeInfo, event: AccessibilityEvent? = null): Boolean {
        // Keep this deliberately strict.
        // The normal Instagram Home/Explore/Reels screens often expose a "Messages"/DM icon somewhere in the root tree, which must not suppress all in-app blocking.
        if (eventTextMatches(event, IG_MESSAGES_EVENT_LABELS)) return true
        return nodeTextMatches(root, IG_MESSAGES_SCREEN_LABELS)
    }

    private fun isInstagramProfileScreen(root: AccessibilityNodeInfo, event: AccessibilityEvent? = null): Boolean {
        // Keep profile detection narrow.
        // Words like "followers", "following" or "posts" can appear in normal feeds/search results and would otherwise disable all Instagram surface blocking.
        return hasSelectedLabel(root, IG_PROFILE_SELECTED_LABELS) ||
            nodeTextMatches(root, IG_PROFILE_CONTEXT_LABELS) ||
            eventTextMatches(event, IG_PROFILE_EVENT_LABELS)
    }

    private fun instagramState(root: AccessibilityNodeInfo, event: AccessibilityEvent? = null): String? {
        val positionSurface = instagramSurfaceFromEventPosition(event) ?: detectInstagramSelectedSurfaceByPosition(root)
        return when {
            isInstagramStoriesViewer(root, event) -> "stories"
            positionSurface == "ig:reels" || hasSelectedLabel(root, IG_REELS_LABELS) || eventTextMatches(event, IG_REELS_LABELS + IG_REELS_STRONG_EVENT_LABELS) -> "reels"
            positionSurface == "ig:explore" || isInstagramSearchScreen(root, event) -> "explore"
            positionSurface == "ig:home" || hasSelectedLabel(root, IG_HOME_LABELS) -> "home"
            positionSurface == "ig:profile" -> "profile"
            else -> null
        }
    }

    private fun ytQuickEvent(eventType: Int): Boolean {
        return eventType == AccessibilityEvent.TYPE_VIEW_CLICKED ||
            eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED ||
            eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED ||
            eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED
    }

    private fun resolveYouTubeSurfaceFromEvent(event: AccessibilityEvent?, allowFocused: Boolean = true): String? {
        if (event == null) return null
        val type = event.eventType
        if (type != AccessibilityEvent.TYPE_VIEW_CLICKED &&
            type != AccessibilityEvent.TYPE_VIEW_SELECTED &&
            type != AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED &&
            type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            !(allowFocused && type == AccessibilityEvent.TYPE_VIEW_FOCUSED)) return null

        val source = runCatching { event.source }.getOrNull()
        val sourcePkg = source?.packageName?.toString().orEmpty()
        if (sourcePkg.isNotBlank() && sourcePkg != "com.google.android.youtube") return null

        val viewId = source?.viewIdResourceName?.lowercase(Locale.getDefault()).orEmpty()
        val surfaceFromPosition = youtubeSurfaceFromEventPosition(event)
        val sourceLooksLikeBottomNav =
            viewId.contains("pivot") ||
                viewId.contains("bottom") ||
                viewId.contains("navigation") ||
                viewId.contains("tab") ||
                viewId.contains("nav")
        val eventText = event.text?.joinToString(" ").orEmpty()
        val eventDesc = event.contentDescription?.toString().orEmpty()
        val directSignal = listOf(
            nodeTextOrDesc(source),
            viewId,
            eventText,
            eventDesc
        ).filter { it.isNotBlank() }.joinToString(" ")

        val sourceLooksLikeYouTubeBottomNav = sourceLooksLikeBottomNav || surfaceFromPosition != null

        // Prefer direct click/focus signals, but only map YouTube tabs when the event really comes from the bottom nav.
        // Player controls can expose texts like "Subscribed", channel names or "You" and must not become tab blocks.
        when {
            anyNeedleMatches(directSignal, YT_SEARCH_LABELS) -> return "yt:search"
            sourceLooksLikeYouTubeBottomNav && anyNeedleMatches(directSignal, YT_SUBSCRIPTIONS_LABELS) -> return "yt:subscriptions"
            anyNeedleMatches(directSignal, YT_SHORTS_LABELS) &&
                (surfaceFromPosition == "yt:shorts" || sourceLooksLikeBottomNav) -> return "yt:shorts"
            sourceLooksLikeYouTubeBottomNav && anyNeedleMatches(directSignal, YT_YOU_LABELS) -> return "yt:you"
            sourceLooksLikeYouTubeBottomNav && anyNeedleMatches(directSignal, YT_HOME_LABELS) -> return "yt:home"
        }

        return when {
            // Do not resolve Search from broad parent/subtree text.
            // The Search icon and bottom nav labels are often visible on many YouTube screens, so only direct bottom-nav signals should produce tab surfaces.
            surfaceFromPosition == "yt:shorts" -> "yt:shorts"
            else -> surfaceFromPosition
        }
    }

    private fun detectYouTubeSelectedSurface(root: AccessibilityNodeInfo): String? {
        detectYouTubeSelectedSurfaceByPosition(root)?.let { return it }
        return when {
            hasActiveYouTubeBottomLabel(root, YT_HOME_LABELS) -> "yt:home"
            hasActiveYouTubeBottomLabel(root, YT_SHORTS_LABELS) -> "yt:shorts"
            hasActiveYouTubeBottomLabel(root, YT_SUBSCRIPTIONS_LABELS) -> "yt:subscriptions"
            hasActiveYouTubeBottomLabel(root, YT_YOU_LABELS) -> "yt:you"
            else -> null
        }
    }

    private fun detectYouTubeSelectedSurfaceByPosition(root: AccessibilityNodeInfo): String? {
        val width = resources.displayMetrics.widthPixels.coerceAtLeast(1)
        val height = resources.displayMetrics.heightPixels.coerceAtLeast(1)
        val found = findAnyNode(root) { node ->
            val active = node.isSelected || node.isCheckedCompat()
            if (!active) return@findAnyNode false
            val nodePkg = node.packageName?.toString()?.lowercase(Locale.getDefault()).orEmpty()
            if (nodePkg.isNotBlank() && nodePkg != "com.google.android.youtube") return@findAnyNode false

            val bounds = Rect()
            runCatching { node.getBoundsInScreen(bounds) }.getOrNull()
            if (bounds.isEmpty) return@findAnyNode false
            val centerY = bounds.exactCenterY() / height.toFloat()
            if (centerY < 0.72f) return@findAnyNode false

            val widthRatio = bounds.width() / width.toFloat()
            val heightRatio = bounds.height() / height.toFloat()
            if (widthRatio > 0.35f || heightRatio > 0.22f) return@findAnyNode false

            true
        } ?: return null

        val bounds = Rect()
        runCatching { found.getBoundsInScreen(bounds) }.getOrNull()
        if (bounds.isEmpty) return null
        return youtubeSurfaceFromBottomCenter(bounds.exactCenterX() / width.toFloat())
    }

    private fun youtubeSurfaceFromBottomCenter(centerX: Float): String? {
        // Typical YouTube bottom nav layout: Home | Shorts | Create | Subscriptions | You.
        // Keep the middle area ignored so the create button is not treated as a blocked surface.
        return when {
            centerX < 0.22f -> "yt:home"
            centerX < 0.44f -> "yt:shorts"
            centerX in 0.58f..0.82f -> "yt:subscriptions"
            centerX > 0.82f -> "yt:you"
            else -> null
        }
    }

    private fun youtubeSurfaceFromEventPosition(event: AccessibilityEvent?): String? {
        val type = event?.eventType ?: return null
        if (type != AccessibilityEvent.TYPE_VIEW_CLICKED &&
            type != AccessibilityEvent.TYPE_VIEW_SELECTED &&
            type != AccessibilityEvent.TYPE_VIEW_FOCUSED) return null

        var current = runCatching { event.source }.getOrNull()
        val width = resources.displayMetrics.widthPixels.coerceAtLeast(1)
        val height = resources.displayMetrics.heightPixels.coerceAtLeast(1)
        var hops = 0
        val bounds = Rect()
        while (current != null && hops < 5) {
            val nodePkg = current.packageName?.toString()?.lowercase(Locale.getDefault()).orEmpty()
            if (nodePkg.isBlank() || nodePkg == "com.google.android.youtube") {
                runCatching { current.getBoundsInScreen(bounds) }.getOrNull()
                if (!bounds.isEmpty) {
                    val centerY = bounds.exactCenterY() / height.toFloat()
                    val widthRatio = bounds.width() / width.toFloat()
                    val heightRatio = bounds.height() / height.toFloat()
                    if (centerY >= 0.72f && widthRatio <= 0.35f && heightRatio <= 0.22f) {
                        return youtubeSurfaceFromBottomCenter(bounds.exactCenterX() / width.toFloat())
                    }
                }
            }
            current = runCatching { current.parent }.getOrNull()
            hops++
        }
        return null
    }

    private fun hasActiveYouTubeBottomLabel(
        root: AccessibilityNodeInfo,
        needles: List<String>
    ): Boolean {
        val width = resources.displayMetrics.widthPixels.coerceAtLeast(1)
        val height = resources.displayMetrics.heightPixels.coerceAtLeast(1)
        val bounds = Rect()
        val found = findAnyNode(root) { node ->
            val active = node.isSelected || node.isCheckedCompat()
            if (!active) return@findAnyNode false
            val nodePkg = node.packageName?.toString()?.lowercase(Locale.getDefault()).orEmpty()
            if (nodePkg.isNotBlank() && nodePkg != "com.google.android.youtube") return@findAnyNode false

            runCatching { node.getBoundsInScreen(bounds) }.getOrNull()
            if (bounds.isEmpty) return@findAnyNode false
            val centerY = bounds.exactCenterY() / height.toFloat()
            if (centerY < 0.72f) return@findAnyNode false

            val widthRatio = bounds.width() / width.toFloat()
            val heightRatio = bounds.height() / height.toFloat()
            if (widthRatio > 0.35f || heightRatio > 0.22f) return@findAnyNode false

            val t = node.text?.toString().orEmpty()
            val cd = node.contentDescription?.toString().orEmpty()
            val vid = node.viewIdResourceName?.lowercase(Locale.getDefault()).orEmpty()
            anyNeedleMatches(t, needles) || anyNeedleMatches(cd, needles) || anyNeedleMatches(vid, needles)
        }
        return found != null
    }

    private fun hasActiveLabelInPackage(
        root: AccessibilityNodeInfo,
        needles: List<String>,
        pkg: String
    ): Boolean {
        // Keep this strict to avoid matching all visible bottom navigation labels.
        val targetPkg = pkg.lowercase(Locale.getDefault())
        val found = findAnyNode(root) { node ->
            val active = node.isSelected || node.isCheckedCompat()
            if (!active) return@findAnyNode false
            val nodePkg = node.packageName?.toString()?.lowercase(Locale.getDefault()).orEmpty()
            if (nodePkg.isNotBlank() && nodePkg != targetPkg) return@findAnyNode false
            val t = node.text?.toString().orEmpty()
            val cd = node.contentDescription?.toString().orEmpty()
            val vid = node.viewIdResourceName?.lowercase(Locale.getDefault()).orEmpty()
            anyNeedleMatches(t, needles) || anyNeedleMatches(cd, needles) || anyNeedleMatches(vid, needles)
        }
        return found != null
    }

    private fun hasTopYouTubeLabel(root: AccessibilityNodeInfo, labels: List<String>, maxScreenPercent: Int = 45): Boolean {
        val maxY = resources.displayMetrics.heightPixels * maxScreenPercent / 100
        val bounds = Rect()
        val found = findAnyNode(root) { node ->
            val nodePkg = node.packageName?.toString().orEmpty()
            if (nodePkg.isNotBlank() && nodePkg != "com.google.android.youtube") return@findAnyNode false
            runCatching { node.getBoundsInScreen(bounds) }.getOrNull()
            if (bounds.top > maxY) return@findAnyNode false
            val t = node.text?.toString().orEmpty()
            val cd = node.contentDescription?.toString().orEmpty()
            val vid = node.viewIdResourceName?.lowercase(Locale.getDefault()).orEmpty()
            anyNeedleMatches(t, labels) || anyNeedleMatches(cd, labels) || anyNeedleMatches(vid, labels)
        }
        return found != null
    }

    private fun anyNeedleMatches(text: String, needles: List<String>): Boolean {
        val lowered = text.lowercase(Locale.getDefault())
        return needles.any { rawNeedle ->
            val needle = rawNeedle.lowercase(Locale.getDefault())
            if (needle.length <= 3) {
                Regex("(^|\\b)${Regex.escape(needle)}(\\b|$)").containsMatchIn(lowered)
            } else {
                lowered.contains(needle)
            }
        }
    }

    private fun isYouTubeShortsEntryEvent(event: AccessibilityEvent?): Boolean {
        val type = event?.eventType ?: return false
        if (type != AccessibilityEvent.TYPE_VIEW_CLICKED &&
            type != AccessibilityEvent.TYPE_VIEW_SELECTED
        ) return false

        val source = runCatching { event.source }.getOrNull()
        val sourcePkg = source?.packageName?.toString()?.lowercase(Locale.getDefault()).orEmpty()
        if (sourcePkg.isNotBlank() && sourcePkg != "com.google.android.youtube") return false

        val surfaceFromPosition = youtubeSurfaceFromEventPosition(event)
        if (surfaceFromPosition == "yt:shorts") return true
        if (surfaceFromPosition == "yt:home" || surfaceFromPosition == "yt:subscriptions" || surfaceFromPosition == "yt:you") {
            return false
        }

        val signal = youtubeEventSourceSignal(event, maxHops = 4)
        if (!anyNeedleMatches(signal, YT_SHORTS_LABELS)) return false
        val viewId = source?.viewIdResourceName?.lowercase(Locale.getDefault()).orEmpty()
        return viewId.contains("pivot") ||
            viewId.contains("bottom") ||
            viewId.contains("navigation") ||
            viewId.contains("tab") ||
            viewId.contains("nav")
    }

    private fun isYouTubeHomeShortsShelfEvent(event: AccessibilityEvent?, root: AccessibilityNodeInfo): Boolean {
        val type = event?.eventType ?: return false
        if (type != AccessibilityEvent.TYPE_VIEW_CLICKED) return false
        if (!isRootFromPackage(root, "com.google.android.youtube")) return false

        val clickBounds = youtubeSourceBounds(event, maxHops = 5) ?: return false
        val shelfBounds = youtubeHomeShortsShelfLabelBounds(root) ?: return false
        return isClickInsideYouTubeHomeShortsTile(root, clickBounds, shelfBounds)
    }

    private fun isClickInsideYouTubeHomeShortsTile(
        root: AccessibilityNodeInfo,
        clickBounds: Rect,
        shelfBounds: Rect
    ): Boolean {
        val width = resources.displayMetrics.widthPixels.coerceAtLeast(1)
        val height = resources.displayMetrics.heightPixels.coerceAtLeast(1)
        val clickCenterX = clickBounds.exactCenterX()
        val clickCenterY = clickBounds.exactCenterY()
        val shelfBottom = shelfBounds.bottom.toFloat()
        val maxShelfY = minOf(height * 0.88f, shelfBottom + height * 0.46f)
        if (clickCenterY < shelfBottom || clickCenterY > maxShelfY) return false

        val bounds = Rect()
        val found = findAnyNode(root) { node ->
            val nodePkg = node.packageName?.toString()?.lowercase(Locale.getDefault()).orEmpty()
            if (nodePkg.isNotBlank() && nodePkg != "com.google.android.youtube") return@findAnyNode false

            runCatching { node.getBoundsInScreen(bounds) }.getOrNull()
            if (bounds.isEmpty) return@findAnyNode false
            if (bounds.top < shelfBottom - height * 0.04f || bounds.bottom > maxShelfY + height * 0.08f) {
                return@findAnyNode false
            }

            val widthRatio = bounds.width() / width.toFloat()
            val heightRatio = bounds.height() / height.toFloat()
            val portraitTile = widthRatio in 0.20f..0.58f &&
                heightRatio in 0.16f..0.54f &&
                bounds.height().toFloat() >= bounds.width().toFloat() * 1.12f
            if (!portraitTile) return@findAnyNode false

            val text = node.text?.toString().orEmpty()
            val desc = node.contentDescription?.toString().orEmpty()
            val viewId = node.viewIdResourceName?.lowercase(Locale.getDefault()).orEmpty()
            val className = node.className?.toString()?.lowercase(Locale.getDefault()).orEmpty()
            val signal = "$text $desc $viewId $className"
            val explicitShortsTile =
                anyNeedleMatches(signal, YT_SHORTS_LABELS) ||
                    viewId.contains("reel") ||
                    viewId.contains("short") ||
                    className.contains("reel")
            if (!explicitShortsTile) return@findAnyNode false

            val expanded = Rect(bounds).apply {
                inset(-(width * 0.03f).toInt(), -(height * 0.025f).toInt())
            }
            expanded.contains(clickCenterX.toInt(), clickCenterY.toInt())
        }
        return found != null
    }

    private fun isYouTubeHomeShortsShelfVisible(root: AccessibilityNodeInfo): Boolean {
        return youtubeHomeShortsShelfLabelBounds(root) != null
    }

    private fun isYouTubeHomeShortsGridVisible(root: AccessibilityNodeInfo): Boolean {
        if (!isRootFromPackage(root, "com.google.android.youtube")) return false
        val selectedSurface = detectYouTubeSelectedSurface(root)
        if (selectedSurface != null && selectedSurface != "yt:home") return false

        val width = resources.displayMetrics.widthPixels.coerceAtLeast(1)
        val height = resources.displayMetrics.heightPixels.coerceAtLeast(1)
        val candidates = ArrayList<Rect>(6)
        val bounds = Rect()
        val foundPair = findAnyNode(root) { node ->
            val nodePkg = node.packageName?.toString()?.lowercase(Locale.getDefault()).orEmpty()
            if (nodePkg.isNotBlank() && nodePkg != "com.google.android.youtube") return@findAnyNode false

            runCatching { node.getBoundsInScreen(bounds) }.getOrNull()
            if (bounds.isEmpty) return@findAnyNode false

            val centerY = bounds.exactCenterY() / height.toFloat()
            if (centerY !in 0.24f..0.88f) return@findAnyNode false

            val widthRatio = bounds.width() / width.toFloat()
            val heightRatio = bounds.height() / height.toFloat()
            val portraitTile = widthRatio in 0.24f..0.58f &&
                heightRatio in 0.16f..0.58f &&
                bounds.height().toFloat() >= bounds.width().toFloat() * 0.78f
            if (!portraitTile) return@findAnyNode false

            val candidate = Rect(bounds)
            val paired = candidates.any { other ->
                abs(other.top - candidate.top) <= height * 0.08f &&
                    abs(other.exactCenterX() - candidate.exactCenterX()) >= width * 0.22f
            }
            candidates += candidate
            paired
        }
        return foundPair != null
    }

    private fun youtubeHomeShortsShelfLabelBounds(root: AccessibilityNodeInfo): Rect? {
        val height = resources.displayMetrics.heightPixels.coerceAtLeast(1)
        val bounds = Rect()
        val found = findAnyNode(root) { node ->
            val nodePkg = node.packageName?.toString()?.lowercase(Locale.getDefault()).orEmpty()
            if (nodePkg.isNotBlank() && nodePkg != "com.google.android.youtube") return@findAnyNode false

            runCatching { node.getBoundsInScreen(bounds) }.getOrNull()
            if (bounds.isEmpty) return@findAnyNode false
            val centerY = bounds.exactCenterY() / height.toFloat()
            if (centerY !in 0.16f..0.72f) return@findAnyNode false

            val text = node.text?.toString().orEmpty()
            val desc = node.contentDescription?.toString().orEmpty()
            val viewId = node.viewIdResourceName?.lowercase(Locale.getDefault()).orEmpty()
            anyNeedleMatches("$text $desc $viewId", YT_SHORTS_LABELS)
        } ?: return null

        val out = Rect()
        return runCatching {
            found.getBoundsInScreen(out)
            if (out.isEmpty) null else Rect(out)
        }.getOrNull()
    }

    private fun youtubeSourceBounds(event: AccessibilityEvent?, maxHops: Int): Rect? {
        var current = runCatching { event?.source }.getOrNull()
        var hops = 0
        val bounds = Rect()
        while (current != null && hops <= maxHops) {
            val node = current ?: break
            val nodePkg = node.packageName?.toString()?.lowercase(Locale.getDefault()).orEmpty()
            if (nodePkg.isBlank() || nodePkg == "com.google.android.youtube") {
                runCatching { node.getBoundsInScreen(bounds) }.getOrNull()
                if (!bounds.isEmpty) return Rect(bounds)
            }
            current = runCatching { node.parent }.getOrNull()
            hops++
        }
        return null
    }

    private fun isYouTubePipEntryEvent(event: AccessibilityEvent?): Boolean {
        val type = event?.eventType ?: return false
        if (type != AccessibilityEvent.TYPE_VIEW_CLICKED &&
            type != AccessibilityEvent.TYPE_VIEW_SELECTED &&
            type != AccessibilityEvent.TYPE_VIEW_FOCUSED &&
            type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            type != AccessibilityEvent.TYPE_WINDOWS_CHANGED
        ) return false

        val source = runCatching { event.source }.getOrNull()
        val sourcePkg = source?.packageName?.toString()?.lowercase(Locale.getDefault()).orEmpty()
        if (sourcePkg.isNotBlank() && sourcePkg != "com.google.android.youtube") return false

        return anyNeedleMatches(youtubeEventSourceSignal(event, maxHops = 3), YT_REAL_PIP_LABELS)
    }

    private fun youtubeEventSourceSignal(event: AccessibilityEvent?, maxHops: Int): String {
        if (event == null) return ""
        val parts = ArrayList<String>(16)
        event.text?.forEach { text ->
            text?.toString()?.takeIf { it.isNotBlank() }?.let { parts += it }
        }
        event.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { parts += it }

        var current = runCatching { event.source }.getOrNull()
        var hops = 0
        while (current != null && hops <= maxHops) {
            val node = current ?: break
            val nodePkg = node.packageName?.toString()?.lowercase(Locale.getDefault()).orEmpty()
            if (nodePkg.isBlank() || nodePkg == "com.google.android.youtube") {
                node.text?.toString()?.takeIf { it.isNotBlank() }?.let { parts += it }
                node.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { parts += it }
                node.viewIdResourceName?.takeIf { it.isNotBlank() }?.let { parts += it }
            }
            current = runCatching { node.parent }.getOrNull()
            hops++
        }
        return parts.joinToString(" ").lowercase(Locale.getDefault())
    }

    private fun isLikelyYouTubeShortsPlayer(root: AccessibilityNodeInfo, event: AccessibilityEvent? = null): Boolean {
        if (isYouTubeShortsScreen(root, event)) return true
        return hasYouTubeDeepShortsSignal(root) && hasYouTubeShortsPlayerGeometry(root)
    }

    private fun isYouTubeHomeFeedShortsPlayer(root: AccessibilityNodeInfo, event: AccessibilityEvent? = null): Boolean {
        if (!isRootFromPackage(root, "com.google.android.youtube")) return false
        val type = event?.eventType ?: return false
        if (type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
            type == AccessibilityEvent.TYPE_VIEW_SCROLLED ||
            type == AccessibilityEvent.TYPE_WINDOWS_CHANGED ||
            type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        ) {
            // OK: layout/player changes can reveal a Home-feed Shorts viewer.
        } else {
            return false
        }
        if (isLikelyYouTubeMiniPlayerVisible(root) || hasYouTubeMiniPlayerGeometry(root)) return false
        val selectedSurface = detectYouTubeSelectedSurface(root) ?: resolveYouTubeSurfaceFromEvent(event)
        if (selectedSurface != null && selectedSurface != "yt:home") return false
        if (isYouTubeHomeShortsShelfVisible(root)) return false
        if (hasYouTubeDeepShortsSignal(root) && hasYouTubeShortsPlayerGeometry(root)) return true
        return false
    }

    private fun hasYouTubeShortsPlayerGeometry(root: AccessibilityNodeInfo): Boolean {
        if (!isRootFromPackage(root, "com.google.android.youtube")) return false

        val width = resources.displayMetrics.widthPixels.coerceAtLeast(1)
        val height = resources.displayMetrics.heightPixels.coerceAtLeast(1)
        val bounds = Rect()
        val found = findAnyNode(root) { node ->
            val nodePkg = node.packageName?.toString()?.lowercase(Locale.getDefault()).orEmpty()
            if (nodePkg.isNotBlank() && nodePkg != "com.google.android.youtube") return@findAnyNode false

            runCatching { node.getBoundsInScreen(bounds) }.getOrNull()
            if (bounds.isEmpty) return@findAnyNode false

            val widthRatio = bounds.width() / width.toFloat()
            val heightRatio = bounds.height() / height.toFloat()
            val centerY = bounds.exactCenterY() / height.toFloat()
            val portraitPlayer =
                widthRatio in 0.64f..1.04f &&
                    heightRatio in 0.62f..1.04f &&
                    centerY in 0.36f..0.64f &&
                    bounds.height() > bounds.width()
            if (!portraitPlayer) return@findAnyNode false

            val text = node.text?.toString().orEmpty()
            val desc = node.contentDescription?.toString().orEmpty()
            val viewId = node.viewIdResourceName?.lowercase(Locale.getDefault()).orEmpty()
            val signal = "$text $desc $viewId"
            anyNeedleMatches(signal, YT_SHORTS_LABELS) ||
                viewId.contains("reel") ||
                viewId.contains("short")
        }
        return found != null
    }

    private fun hasYouTubeDeepShortsSignal(root: AccessibilityNodeInfo): Boolean {
        if (!isRootFromPackage(root, "com.google.android.youtube") && !containsPackageNode(root, "com.google.android.youtube")) {
            return false
        }
        val reelIdHit = findAnyNode(root) { node ->
            val nodePkg = node.packageName?.toString()?.lowercase(Locale.getDefault()).orEmpty()
            if (nodePkg.isNotBlank() && nodePkg != "com.google.android.youtube") return@findAnyNode false
            val viewId = node.viewIdResourceName?.lowercase(Locale.getDefault()).orEmpty()
            val className = node.className?.toString()?.lowercase(Locale.getDefault()).orEmpty()
            viewId.contains("reel") || className.contains("reel")
        } != null
        if (reelIdHit) return true

        return hasYouTubeShortsPlayerControl(root) && hasYouTubeShortsPlayerGeometry(root)
    }

    private fun hasYouTubeShortsPlayerControl(root: AccessibilityNodeInfo): Boolean {
        val width = resources.displayMetrics.widthPixels.coerceAtLeast(1)
        val height = resources.displayMetrics.heightPixels.coerceAtLeast(1)
        val bounds = Rect()
        val matchedControls = LinkedHashSet<String>()
        val found = findAnyNode(root) { node ->
            val nodePkg = node.packageName?.toString()?.lowercase(Locale.getDefault()).orEmpty()
            if (nodePkg.isNotBlank() && nodePkg != "com.google.android.youtube") return@findAnyNode false

            runCatching { node.getBoundsInScreen(bounds) }.getOrNull()
            if (bounds.isEmpty) return@findAnyNode false
            val centerX = bounds.exactCenterX() / width.toFloat()
            val centerY = bounds.exactCenterY() / height.toFloat()
            if (centerX < 0.56f || centerY !in 0.18f..0.92f) return@findAnyNode false

            val text = node.text?.toString().orEmpty()
            val desc = node.contentDescription?.toString().orEmpty()
            val viewId = node.viewIdResourceName?.lowercase(Locale.getDefault()).orEmpty()
            val signal = "$text $desc $viewId".lowercase(Locale.getDefault())
            YT_SHORTS_PLAYER_HINT_LABELS.firstOrNull { raw ->
                signal.contains(raw.lowercase(Locale.getDefault()))
            }?.let { matchedControls += it }
            matchedControls.size >= 2
        }
        return found != null
    }

    private fun isLikelyYouTubeMiniPlayerVisible(root: AccessibilityNodeInfo?): Boolean {
        val r = root ?: return false
        if (!isRootFromPackage(r, "com.google.android.youtube")) return false

        val width = resources.displayMetrics.widthPixels.coerceAtLeast(1)
        val height = resources.displayMetrics.heightPixels.coerceAtLeast(1)
        val bounds = Rect()
        val found = findAnyNode(r) { node ->
            val nodePkg = node.packageName?.toString()?.lowercase(Locale.getDefault()).orEmpty()
            if (nodePkg.isNotBlank() && nodePkg != "com.google.android.youtube") return@findAnyNode false

            runCatching { node.getBoundsInScreen(bounds) }.getOrNull()
            if (bounds.isEmpty) return@findAnyNode false
            val centerY = bounds.exactCenterY() / height.toFloat()
            val widthRatio = bounds.width() / width.toFloat()
            val heightRatio = bounds.height() / height.toFloat()
            if (centerY < 0.46f || widthRatio > 0.96f || heightRatio > 0.36f) return@findAnyNode false

            val text = node.text?.toString().orEmpty()
            val desc = node.contentDescription?.toString().orEmpty()
            val viewId = node.viewIdResourceName?.lowercase(Locale.getDefault()).orEmpty()
            val signal = "$text $desc $viewId"
            anyNeedleMatches(signal, YT_PIP_LABELS) ||
                anyNeedleMatches(signal, YT_MINI_PLAYER_CLOSE_LABELS) ||
                (viewId.contains("mini") && viewId.contains("player"))
        }
        return found != null
    }

    private fun hasYouTubeMiniPlayerGeometry(root: AccessibilityNodeInfo): Boolean {
        return findYouTubeMiniPlayerBounds(root) != null
    }

    private fun findYouTubeMiniPlayerBounds(root: AccessibilityNodeInfo): Rect? {
        if (!isRootFromPackage(root, "com.google.android.youtube")) return null

        val selectedSurface = detectYouTubeSelectedSurface(root)
        if (selectedSurface == "yt:shorts") return null

        val width = resources.displayMetrics.widthPixels.coerceAtLeast(1)
        val height = resources.displayMetrics.heightPixels.coerceAtLeast(1)
        val bounds = Rect()
        var bestBounds: Rect? = null
        var bestScore = -1

        findAnyNode(root) { node ->
            val nodePkg = node.packageName?.toString()?.lowercase(Locale.getDefault()).orEmpty()
            if (nodePkg.isNotBlank() && nodePkg != "com.google.android.youtube") return@findAnyNode false

            runCatching { node.getBoundsInScreen(bounds) }.getOrNull()
            if (bounds.isEmpty) return@findAnyNode false

            val centerY = bounds.exactCenterY() / height.toFloat()
            val widthRatio = bounds.width() / width.toFloat()
            val heightRatio = bounds.height() / height.toFloat()
            if (centerY !in 0.50f..0.90f) return@findAnyNode false
            if (widthRatio !in 0.28f..1.02f || heightRatio !in 0.035f..0.20f) return@findAnyNode false

            val text = node.text?.toString().orEmpty()
            val desc = node.contentDescription?.toString().orEmpty()
            val viewId = node.viewIdResourceName?.lowercase(Locale.getDefault()).orEmpty()
            val signal = "$text $desc $viewId".lowercase(Locale.getDefault())
            val bottomNavLike =
                viewId.contains("pivot") ||
                    viewId.contains("navigation") ||
                    viewId.contains("bottom_bar") ||
                    viewId.contains("bottom_nav") ||
                    (centerY > 0.78f && widthRatio < 0.36f && anyNeedleMatches(signal, YT_HOME_LABELS + YT_SHORTS_LABELS + YT_SUBSCRIPTIONS_LABELS + YT_YOU_LABELS))
            if (bottomNavLike) return@findAnyNode false

            val miniId = viewId.contains("mini") ||
                viewId.contains("compact") ||
                (viewId.contains("player") && centerY > 0.58f)
            val mediaHint = anyNeedleMatches(signal, YT_MINI_PLAYER_HINT_LABELS) ||
                anyNeedleMatches(signal, YT_MINI_PLAYER_CLOSE_LABELS)
            val compactBottomPlayer =
                centerY in 0.66f..0.90f &&
                    bounds.top >= (height * 0.54f).toInt() &&
                    bounds.bottom <= (height * 0.94f).toInt() &&
                    widthRatio in 0.48f..1.02f &&
                    heightRatio in 0.045f..0.135f

            val matched = miniId || mediaHint || compactBottomPlayer
            if (!matched) return@findAnyNode false

            val score =
                (if (miniId) 1_000_000 else 0) +
                    (if (mediaHint) 500_000 else 0) +
                    bounds.width() * bounds.height()
            if (score > bestScore) {
                bestScore = score
                bestBounds = Rect(bounds)
            }
            false
        }
        return bestBounds
    }

    private fun isYouTubePictureInPictureWindowVisible(): Boolean {
        val width = resources.displayMetrics.widthPixels.coerceAtLeast(1)
        val height = resources.displayMetrics.heightPixels.coerceAtLeast(1)
        val bounds = Rect()
        val activeWindows = runCatching { windows }.getOrNull().orEmpty()

        for (window in activeWindows) {
            val inPip = runCatching {
                (AccessibilityWindowInfo::class.java
                    .getMethod("isInPictureInPictureMode")
                    .invoke(window) as? Boolean) == true
            }.getOrDefault(false)

            val root = runCatching { window.root }.getOrNull()
            val containsYouTube = isRootFromPackage(root, "com.google.android.youtube") ||
                root?.let { containsPackageNode(it, "com.google.android.youtube") } == true
            if (!containsYouTube && !inPip) continue

            runCatching { window.getBoundsInScreen(bounds) }.getOrNull()
            if (inPip && containsYouTube) return true
            if (bounds.isEmpty || !containsYouTube) continue

            val widthRatio = bounds.width() / width.toFloat()
            val heightRatio = bounds.height() / height.toFloat()
            if (widthRatio in 0.12f..0.82f && heightRatio in 0.08f..0.46f) {
                return true
            }
        }

        return false
    }

    private fun isYouTubeShortsScreen(root: AccessibilityNodeInfo, event: AccessibilityEvent? = null): Boolean {
        // Do not treat any visible "Shorts" text as the Shorts surface.
        // YouTube keeps the bottom navigation visible on unrelated screens such as Subscriptions and the profile/You tab.
        return detectYouTubeSelectedSurface(root) == "yt:shorts" || resolveYouTubeSurfaceFromEvent(event) == "yt:shorts"
    }

    private fun isYouTubeSubscriptionsScreen(root: AccessibilityNodeInfo, event: AccessibilityEvent? = null): Boolean {
        return detectYouTubeSelectedSurface(root) == "yt:subscriptions" ||
            resolveYouTubeSurfaceFromEvent(event) == "yt:subscriptions" ||
            // On some YouTube builds the Subscriptions tab itself is not marked selected, but the page heading near the top still exposes the active surface.
            hasTopYouTubeLabel(root, YT_SUBSCRIPTIONS_LABELS, maxScreenPercent = 35)
    }

    private fun isYouTubeYouScreen(root: AccessibilityNodeInfo, event: AccessibilityEvent? = null): Boolean {
        return detectYouTubeSelectedSurface(root) == "yt:you" || resolveYouTubeSurfaceFromEvent(event) == "yt:you"
    }

    private fun isYouTubeHomeScreen(root: AccessibilityNodeInfo, event: AccessibilityEvent? = null): Boolean {
        return detectYouTubeSelectedSurface(root) == "yt:home" || resolveYouTubeSurfaceFromEvent(event) == "yt:home"
    }

    private fun isYouTubeSearchScreen(root: AccessibilityNodeInfo, event: AccessibilityEvent? = null): Boolean {
        val source = runCatching { event?.source }.getOrNull()
        val sourceText = nodeTextOrDesc(source)
        val sourceLooksLikeSearch = anyNeedleMatches(sourceText, YT_SEARCH_LABELS)
        val sourceClass = source?.className?.toString().orEmpty().lowercase(Locale.getDefault())
        val textInputFocused = source?.isFocused == true &&
            (sourceClass.contains("edittext") || sourceText.isNotBlank()) &&
            (sourceLooksLikeSearch || anyNeedleMatches(sourceText, YT_SEARCH_SCREEN_LABELS))

        return resolveYouTubeSurfaceFromEvent(event) == "yt:search" ||
            textInputFocused ||
            eventOrSourceMatches(event, YT_SEARCH_SCREEN_LABELS) ||
            hasTopYouTubeLabel(root, YT_SEARCH_SCREEN_LABELS, maxScreenPercent = 30)
    }

    private fun isYouTubeCommentsVisible(root: AccessibilityNodeInfo): Boolean {
        return nodeTextMatches(root, YT_COMMENTS_LABELS)
    }

    private fun isLikelyYouTubePictureInPicture(root: AccessibilityNodeInfo?, event: AccessibilityEvent? = null): Boolean {
        // Keep PiP detection intentionally conservative.
        // Earlier broad text/small-window checks caused normal YouTube screens to loop as "Picture-in-picture mode".
        val type = event?.eventType ?: return false
        if (type != AccessibilityEvent.TYPE_WINDOWS_CHANGED && type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return false
        val className = event.className?.toString()?.lowercase(Locale.getDefault()).orEmpty()
        return className.contains("pictureinpicture") || className.contains("pinnedstack")
    }

    private fun killYouTubePictureInPicture(pkg: String) {
        val now = System.currentTimeMillis()
        val last = lastPipKillAtByPkg[pkg] ?: 0L
        if (now - last < PIP_KILL_COOLDOWN_MS) {
            appendBlockingLog(
                category = "pip_block",
                key = "pip-cooldown|$pkg",
                message = "pkg=$pkg skipped=cooldown ageMs=${now - last}"
            )
            return
        }
        lastPipKillAtByPkg[pkg] = now
        appendBlockingLog(
            category = "pip_block",
            key = "pip-kill|$pkg",
            message = "pkg=$pkg action=bounce_home_and_kill"
        )
        clearSurfaceEvidence("yt:pip")
        clearSurfaceHintForPackage(pkg)
        surfaceBlockGuardUntil["$pkg|yt:pip"] = now + PIP_KILL_COOLDOWN_MS
        bounceHomeAndKill(pkg)
    }

    private fun bounceHomeAndKill(pkg: String) {
        blockLaunchController.bounceHomeAndKill(pkg)
    }

    private fun AccessibilityNodeInfo.isCheckedCompat(): Boolean {
        return runCatching {
            if (Build.VERSION.SDK_INT >= 36) {
                val checkedState = AccessibilityNodeInfo::class.java
                    .getMethod("getChecked")
                    .invoke(this) as? Int ?: 0
                checkedState != 0
            } else {
                AccessibilityNodeInfo::class.java
                    .getMethod("isChecked")
                    .invoke(this) as? Boolean == true
            }
        }.getOrDefault(false)
    }

}
