package at.saltyy.switchly.blocking

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.ActivityManager
import android.app.KeyguardManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.accessibility.AccessibilityEvent
import at.saltyy.switchly.data.prefs.BlockAttemptStore
import at.saltyy.switchly.data.prefs.BlockCountStore
import at.saltyy.switchly.data.prefs.EmergencyBypassStore
import at.saltyy.switchly.data.prefs.LimitReachedStore
import at.saltyy.switchly.data.prefs.ProfileStore
import at.saltyy.switchly.data.prefs.SwitchModeStore
import at.saltyy.switchly.data.prefs.TempAllowStore
import at.saltyy.switchly.data.prefs.UsageLimitStore
import at.saltyy.switchly.data.prefs.UsageStore
import at.saltyy.switchly.data.prefs.ScheduleStore
import at.saltyy.switchly.feature.blocker.BlockerActivity
import at.saltyy.switchly.platform.receiver.schedule.ScheduleReceiver
import android.content.Intent

/**
 * Main (stable) blocking runtime.
 *
 * Compared to [AppWatcherService] this does NOT rely on Usage Access polling or a foreground service.
 * The system keeps Accessibility services alive much more reliably across OEMs.
 */
class SwitchlyAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())

    private lateinit var pm: PowerManager
    private var km: KeyguardManager? = null

    // Foreground tracking for usage limits
    @Volatile private var currentTopPkg: String? = null
    @Volatile private var lastTickAt: Long = 0L

    // Schedule evaluation fallback (for devices without Exact Alarm permission)
    @Volatile private var lastScheduleMinute: Long = -1L

    // Event noise reduction (especially with TYPE_WINDOW_CONTENT_CHANGED enabled)
    private var lastEventPkg: String? = null
    private var lastEventAt: Long = 0L
    private var lastEventType: Int = 0
    private var lastTransitionAt: Long = 0L

    // Debounce block/attempt stats + blocker UI launches.
    private val lastAttemptAt = HashMap<String, Long>()
    private val lastBlockShownAt = HashMap<String, Long>()
    private var lastGlobalBlockTs: Long = 0L

    private val ATTEMPT_COOLDOWN_MS = 1_200L
    private val BLOCK_SHOWN_COOLDOWN_MS = 800L

    // Schedules can include time-windows (including Wi-Fi/Bluetooth schedules).
    // On Android 12+ exact alarms can be restricted, so we do a lightweight evaluation tick
    // from the always-running AccessibilityService once per minute.
    private var lastScheduleEvalMinute: Long = -1L

    private fun maybeScheduleMinuteTick() {
        val now = System.currentTimeMillis()
        val minuteBucket = now / 60_000L
        if (minuteBucket == lastScheduleEvalMinute) return
        lastScheduleEvalMinute = minuteBucket

        // Avoid unnecessary broadcasts if there are no schedules.
        val hasSchedules = runCatching { ScheduleStore.getAll(this) }.getOrNull()?.isNotEmpty() == true
        if (!hasSchedules) return

        runCatching {
            val i = Intent(ScheduleReceiver.ACTION_TICK)
                .setPackage(packageName)
                .putExtra("time_reason", "accessibility_minute")
            sendBroadcast(i)
        }
    }
    
    private val tick = object : Runnable {
        override fun run() {
            try {
                SwitchModeStore.finishTemporaryEnableIfExpired(this@SwitchlyAccessibilityService)
                SwitchModeStore.finishTemporaryDisableIfExpired(this@SwitchlyAccessibilityService)
                maybeScheduleMinuteTick()
                usageTick()

                // Important: if a schedule/profile switch becomes active while the user stays inside the same app, there may be no new window transition event.
                // Re-check the current top app on the heartbeat so blocks apply immediately at schedule boundaries.
                currentTopPkg?.let { top ->
                    if (top.isNotBlank()) maybeBlockNow(top)
                }
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

        // We only need foreground app changes.
        val i = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOWS_CHANGED or
                // Helps reduce "one frame" flashes on some OEMs (more events, but better responsiveness)
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 0
        }
        serviceInfo = i

        // If the user just enabled Accessibility, remove any "protection inactive" warning.
        runCatching { at.saltyy.switchly.util.ProtectionStatusNotifier.refresh(this) }

        lastTickAt = System.currentTimeMillis()
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, 1_000L)
    }

    override fun onInterrupt() {
        // no-op
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        // If the service gets destroyed/disabled, show warning notification if Switchly is enabled.
        runCatching { at.saltyy.switchly.util.ProtectionStatusNotifier.refresh(this) }
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString()?.trim().orEmpty()
        if (pkg.isBlank()) return

        // Ignore our own windows to avoid loops.
        if (pkg == packageName) return

        val now = System.currentTimeMillis()
        val type = event?.eventType ?: 0

        // Deduplicate noisy event streams
        if (pkg == lastEventPkg) {
            val dt = now - lastEventAt
            val sameType = type == lastEventType

            // Content changed events can fire many times per second - ignore tight bursts.
            if (type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED && dt < 120L) return

            // Some OEMs fire duplicate window-state events - ignore ultra-fast duplicates.
            if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && sameType && dt < 60L) return
        }

        lastEventPkg = pkg
        lastEventAt = now
        lastEventType = type

        val isTransition = type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            type == AccessibilityEvent.TYPE_WINDOWS_CHANGED

        if (isTransition) {
            lastTransitionAt = now
            currentTopPkg = pkg
            maybeBlockNow(pkg)
            return
        }

        // Content changed events are very noisy. We only react to them shortly after a real
        // window transition to reduce one-frame flashes without causing random false blocks.
        if (type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            if (now - lastTransitionAt <= 350L) {
                maybeBlockNow(pkg)
            }
        }
    }

    private fun usageTick() {
        val pkg = currentTopPkg ?: return
        if (pkg.isBlank()) return

        val now = System.currentTimeMillis()
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

        val profile = ProfileStore.getCurrent(this) ?: return
        val blocked = ProfileStore.getBlockedForProfile(this, profile)
        val managed = isManagedPackage(pkg, blocked)
        if (!managed) return

        val limitMin = UsageLimitStore.getLimitMinutes(this, profile, pkg)
        if (limitMin <= 0) return // hard block -> handled by event driven blocker

        UsageStore.addUsageMsToday(this, pkg, delta)

        val usedMs = UsageStore.getUsageMsToday(this, pkg)
        val limitMs = limitMin * 60_000L
        if (usedMs >= limitMs) {
            LimitReachedStore.markReachedToday(this, pkg)
            // If the limit is reached while user stays in the app, kick them out now.
            maybeBlockNow(pkg, force = true)
        }
    }

    private fun maybeBlockNow(pkg: String, force: Boolean = false) {
        // Basic guards
        if (!pm.isInteractive) return
        if (km?.isKeyguardLocked == true) return
        if (EmergencyBypassStore.isActive(this)) return
        if (!SwitchModeStore.isEnabled(this)) return
        if (TempAllowStore.isAllowed(this, pkg)) return

        val profile = ProfileStore.getCurrent(this) ?: return
        val blocked = ProfileStore.getBlockedForProfile(this, profile)
        val managed = isManagedPackage(pkg, blocked)
        if (!managed) return

        val lockActive = SwitchModeStore.isNfcRequiredForDisable(this)
        val limitMin = UsageLimitStore.getLimitMinutes(this, profile, pkg)

        val shouldBlockNow = when {
            limitMin <= 0 -> true
            force -> true
            else -> {
                val usedMs = UsageStore.getUsageMsToday(this, pkg)
                usedMs >= limitMin * 60_000L
            }
        }

        // Optional extra safety: while "lock mode" is active, block high-risk apps ASAP.
        if (lockActive && isHighRisk(pkg)) {
            blockNow(pkg, immediate = true)
            return
        }

        if (!shouldBlockNow) return

        // For hard blocks / limit reached, try to react immediately (reduces preview flash)
        val immediate = force || limitMin <= 0
        blockNow(pkg, immediate = immediate)
    }

    private fun blockNow(pkg: String, immediate: Boolean) {
        val now = System.currentTimeMillis()

        if (BlockerActivity.isVisible) return

        val lastAttempt = lastAttemptAt[pkg] ?: 0L
        val countAttempt = (now - lastAttempt) >= ATTEMPT_COOLDOWN_MS

        // We always want to show the blocker UI as visual feedback.
        // The cooldown only affects analytics counters and prevents excessive stat spam.
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
        }

        val label = runCatching {
            val ai = packageManager.getApplicationInfo(pkg, 0)
            packageManager.getApplicationLabel(ai).toString()
        }.getOrNull() ?: pkg

        // Kick user out of the blocked app, then always show the blocker screen.
        // (Showing the blocker is important as feedback - not just silently closing the app.)
        bounceHomeAndKill(pkg)

        val delayMs = if (immediate) 80L else 120L
        handler.postDelayed({
            runCatching { BlockerActivity.show(this, pkg, label) }
        }, delayMs)
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
        // Settings / Installers / system dialogs that can weaken lock-mode.
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
