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

package at.saltyy.switchly.feature.blocker

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.View
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.net.toUri
import at.saltyy.switchly.R
import at.saltyy.switchly.blocking.BlockingRuntime
import at.saltyy.switchly.data.prefs.SwitchModeStore
import at.saltyy.switchly.theme.AccentColor
import at.saltyy.switchly.ui.ThemeUtils
import java.lang.ref.WeakReference

class BlockerActivity : ComponentActivity() {

    private lateinit var titleView: TextView
    private lateinit var appNameView: TextView
    private lateinit var messageView: TextView
    private lateinit var btnClose: Button

    private val handler = Handler(Looper.getMainLooper())
    private var tickRunning = false

    private var shownAt: Long = 0L
    private var shownPkg: String? = null

    private val tick = object : Runnable {
        override fun run() {
            if (!tickRunning) return
            flushDelta()
            handler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeUtils.applyAccentTheme(this)
        super.onCreate(savedInstanceState)
        suppressOpenActivityTransition()
        suppressLegacyPendingTransition()
        currentActivityRef = WeakReference(this)

        if (!SwitchModeStore.isEnabled(this)) {
            clearVisibilityState("created_while_disabled")
            finish()
            return
        }

        // Privacy: never show any real content in the system overview/recents thumbnail.
        // (Only affects Switchly screens - Android does not allow changing previews of other apps.)
        runCatching {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                setRecentsScreenshotEnabled(false)
            }
        }

        setContentView(R.layout.activity_blocker)
        runCatching { window.setWindowAnimations(0) }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                handleCloseAction()
            }
        })

        // Make window background follow current theme (prevents "always dark" background on some OEMs)
        runCatching {
            val bg = resolveThemeColor(android.R.attr.colorBackground, Color.WHITE)
            window.decorView.setBackgroundColor(bg)

            // Set correct status/nav icon appearance (dark icons on light bg, light icons on dark bg)
            WindowCompat.setDecorFitsSystemWindows(window, true)
            val controller = WindowInsetsControllerCompat(window, window.decorView)
            val isLightBg = isColorLight(bg)
            controller.isAppearanceLightStatusBars = isLightBg
            controller.isAppearanceLightNavigationBars = isLightBg
        }

        setShowWhenLocked(true)
        setTurnScreenOn(true)

        titleView = findViewById(R.id.blocker_title)
        appNameView = findViewById(R.id.blocker_app_name)
        messageView = findViewById(R.id.blocker_message)
        btnClose = findViewById(R.id.btn_close)

        btnClose.backgroundTintList = AccentColor.getActiveColor(this)

        titleView.text = getString(R.string.blocked_app_default)
        messageView.text = getString(R.string.blocked_message)

        btnClose.setOnClickListener {
            handleCloseAction()
        }

        applyFromIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyFromIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        if (!SwitchModeStore.isEnabled(this)) {
            clearVisibilityState("resumed_while_disabled")
            finish()
            return
        }
        val pkg = intent?.getStringExtra(EXTRA_PKG)
        isVisible = true
        visiblePkg = pkg
        lastResumedAtRealtime = SystemClock.elapsedRealtime()
        shownAt = System.currentTimeMillis()
        shownPkg = pkg
        BlockingRuntime.markBlockerActivityState(this, pkg.orEmpty(), "resumed", "focus=$hasWindowFocusNow")

        tickRunning = true
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, 1000L)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        hasWindowFocusNow = hasFocus
        val pkg = intent?.getStringExtra(EXTRA_PKG)
        if (hasFocus) {
            isVisible = true
            visiblePkg = pkg
            lastFocusedAtRealtime = SystemClock.elapsedRealtime()
        }
        BlockingRuntime.markBlockerActivityState(this, pkg.orEmpty(), if (hasFocus) "focused" else "focus_lost", "visible=$isVisible")
    }

    override fun onPause() {
        flushDelta()
        lastPausedAtRealtime = SystemClock.elapsedRealtime()
        hasWindowFocusNow = false
        isVisible = false
        visiblePkg = null
        tickRunning = false
        handler.removeCallbacks(tick)
        BlockingRuntime.markBlockerActivityState(this, shownPkg.orEmpty(), "paused")
        super.onPause()
    }

    override fun onDestroy() {
        hasWindowFocusNow = false
        isVisible = false
        visiblePkg = null
        BlockingRuntime.markBlockerActivityState(this, shownPkg.orEmpty(), "destroyed")
        if (currentActivityRef?.get() === this) {
            currentActivityRef = null
        }
        super.onDestroy()
    }

    override fun onStop() {
        flushDelta()
        super.onStop()
    }

    private fun flushDelta() {
        // "Blocked time" stats should reflect how long an app was configured as blocked while Switchly was enabled (independent of this UI being visible).
        // Tracking time here would incorrectly measure "time the blocker screen was shown".
        // Keep this method only to keep the internal timestamp in sync.
        shownAt = System.currentTimeMillis()
    }

    private fun applyFromIntent(intent: Intent?) {
        val pkg = intent?.getStringExtra(EXTRA_PKG).orEmpty()
        val label = intent?.getStringExtra(EXTRA_LABEL).orEmpty()
        val title = intent?.getStringExtra(EXTRA_TITLE).orEmpty()
        val msg = intent?.getStringExtra(EXTRA_MESSAGE).orEmpty()

        if (title.isNotBlank()) {
            titleView.text = title
        } else {
            titleView.text = getString(R.string.blocked_app_default)
        }

        if (msg.isNotBlank()) {
            messageView.text = msg
        } else {
            messageView.text = getString(R.string.blocked_message)
        }

        if (label.isNotBlank()) {
            appNameView.text = label
            appNameView.visibility = View.VISIBLE
        } else {
            if (pkg.isNotBlank()) {
                appNameView.text = pkg
                appNameView.visibility = View.VISIBLE
            } else {
                appNameView.visibility = View.GONE
            }
        }
    }

    private fun handleCloseAction() {
        val pkg = intent?.getStringExtra(EXTRA_PKG).orEmpty()
        val postAckBackCount = intent?.getIntExtra(EXTRA_POST_ACK_BACK_COUNT, 0) ?: 0
        val postAckYoutubeHome = intent?.getBooleanExtra(EXTRA_POST_ACK_YOUTUBE_HOME, false) ?: false
        val postAckYoutubeClose = intent?.getBooleanExtra(EXTRA_POST_ACK_YOUTUBE_CLOSE, false) ?: false
        val returnToPackageOnClose = intent?.getBooleanExtra(EXTRA_RETURN_TO_PACKAGE_ON_CLOSE, false) ?: false

        // YouTube surfaces should keep the popup visible first, then return only the YouTube task to its Home tab.
        // Do not send the user to the phone launcher here; that feels like YouTube was closed instead of blocked.
        if (pkg == "com.google.android.youtube" && (postAckYoutubeHome || postAckYoutubeClose)) {
            pauseActiveMediaPlayback()
            queuePendingYouTubeHomeRedirect(pkg)
            if (postAckYoutubeHome) {
                launchYouTubeHome(this)
            } else {
                bringPackageToFront(this, pkg)
            }
            BlockingRuntime.ensureRunning(this)
            finishWithoutAnimation()
            return
        }

        // For surface-block popups we prefer revealing the previously running app task again.
        // Re-launching the package from here can behave like a cold start on some OEMs and feel like the app was closed.
        if (returnToPackageOnClose && postAckBackCount <= 0) {
            BlockingRuntime.ensureRunning(this)
            finishWithoutAnimation()
            return
        }

        // When a controlled back navigation is queued we still need to bring the app task to the foreground first so Accessibility can consume the pending navigation against the correct package.
        if (pkg.isNotBlank() && postAckBackCount > 0) {
            bringPackageToFront(this, pkg)
            queuePendingBackNavigation(pkg, postAckBackCount)
            BlockingRuntime.ensureRunning(this)
            finishWithoutAnimation()
            return
        }

        sendHome(this)
        BlockingRuntime.ensureRunning(this)
        finishWithoutAnimation()
    }

    private fun finishWithoutAnimation() {
        suppressCloseActivityTransition()
        suppressLegacyPendingTransition()
        finish()
        suppressCloseActivityTransition()
        suppressLegacyPendingTransition()
    }

    private fun suppressOpenActivityTransition() {
        suppressActivityTransitionCompat(OVERRIDE_TRANSITION_OPEN)
    }

    private fun suppressCloseActivityTransition() {
        suppressActivityTransitionCompat(OVERRIDE_TRANSITION_CLOSE)
    }

    private fun suppressActivityTransitionCompat(transitionType: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            runCatching {
                Activity::class.java
                    .getMethod(
                        "overrideActivityTransition",
                        Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType
                    )
                    .invoke(this, transitionType, 0, 0)
            }
        }
    }

    private fun suppressLegacyPendingTransition() {
        runCatching {
            Activity::class.java
                .getMethod(
                    "overridePendingTransition",
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType
                )
                .invoke(this, 0, 0)
        }
    }

    private fun pauseActiveMediaPlayback() {
        val audio = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val down = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE)
        val up = KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE)
        runCatching { audio.dispatchMediaKeyEvent(down) }
        runCatching { audio.dispatchMediaKeyEvent(up) }
    }

    private fun killBackgroundPackage(context: Context, pkg: String) {
        if (pkg.isBlank()) return
        runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return@runCatching
            am.killBackgroundProcesses(pkg)
        }
    }

    private fun resolveThemeColor(attr: Int, fallback: Int): Int {
        val tv = TypedValue()
        return if (theme.resolveAttribute(attr, tv, true)) {
            tv.data
        } else fallback
    }

    private fun isColorLight(color: Int): Boolean {
        // Simple luminance check; good enough for choosing dark/light system bar icons
        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)
        val luminance = (0.299 * r + 0.587 * g + 0.114 * b)
        return luminance >= 186
    }

    companion object {
        private const val EXTRA_PKG = "pkg"
        private const val EXTRA_LABEL = "label"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_MESSAGE = "message"
        private const val EXTRA_POST_ACK_BACK_COUNT = "post_ack_back_count"
        private const val EXTRA_POST_ACK_YOUTUBE_HOME = "post_ack_youtube_home"
        private const val EXTRA_POST_ACK_YOUTUBE_CLOSE = "post_ack_youtube_close"
        private const val EXTRA_RETURN_TO_PACKAGE_ON_CLOSE = "return_to_package_on_close"

        //Used by [AppWatcherService] to re-show the blocker if the user swipes it away from recents.
        @Volatile
        var isVisible: Boolean = false
            private set

        @Volatile
        var visiblePkg: String? = null
            private set

        @Volatile
        private var hasWindowFocusNow: Boolean = false

        @Volatile
        private var lastResumedAtRealtime: Long = 0L

        @Volatile
        private var lastFocusedAtRealtime: Long = 0L

        @Volatile
        private var lastPausedAtRealtime: Long = 0L

        private const val OVERRIDE_TRANSITION_OPEN = 0
        private const val OVERRIDE_TRANSITION_CLOSE = 1

        private const val VISIBLE_TTL_MS = 2_500L

        @Volatile
        private var currentActivityRef: WeakReference<BlockerActivity>? = null

        fun clearVisibilityState(reason: String = "cleared") {
            isVisible = false
            visiblePkg = null
            hasWindowFocusNow = false
            lastResumedAtRealtime = 0L
            lastFocusedAtRealtime = 0L
            lastPausedAtRealtime = SystemClock.elapsedRealtime()
            pendingBackNavigation = null

            currentActivityRef?.get()?.let { activity ->
                if (!activity.isFinishing) {
                    activity.runOnUiThread {
                        if (!activity.isFinishing) activity.finish()
                    }
                }
            }
        }

        fun isRecentlyResumedFor(pkg: String, ttlMs: Long = VISIBLE_TTL_MS): Boolean {
            if (pkg.isBlank()) return false
            val visibleForSamePackage = visiblePkg == pkg
            if (!isVisible || !visibleForSamePackage) return false
            val age = SystemClock.elapsedRealtime() - lastResumedAtRealtime
            return age in 0..ttlMs
        }

        fun isRecentlyFocusedFor(pkg: String, ttlMs: Long = VISIBLE_TTL_MS): Boolean {
            if (pkg.isBlank()) return false
            val visibleForSamePackage = visiblePkg == pkg
            if (!isVisible || !hasWindowFocusNow || !visibleForSamePackage) return false
            val age = SystemClock.elapsedRealtime() - lastFocusedAtRealtime
            return age in 0..ttlMs
        }

        fun debugVisibilityState(pkg: String): String {
            val now = SystemClock.elapsedRealtime()
            return "target=$pkg visible=$isVisible visiblePkg=${visiblePkg ?: "-"} focus=$hasWindowFocusNow resumedAgeMs=${ageOrMissing(now, lastResumedAtRealtime)} focusAgeMs=${ageOrMissing(now, lastFocusedAtRealtime)} pausedAgeMs=${ageOrMissing(now, lastPausedAtRealtime)}"
        }

        private fun ageOrMissing(now: Long, value: Long): String {
            if (value <= 0L) return "-"
            return (now - value).coerceAtLeast(0L).toString()
        }

        fun showDetailed(
            context: Context,
            pkg: String,
            label: String?,
            title: String?,
            message: String?,
            postAcknowledgeBackCount: Int = 0,
            returnToPackageOnClose: Boolean = false,
            postAcknowledgeYouTubeHome: Boolean = false,
            postAcknowledgeYouTubeClose: Boolean = false
        ) {
            if (!SwitchModeStore.isEnabled(context)) {
                clearVisibilityState("show_detailed_skipped_disabled")
                return
            }
            val i = Intent(context, BlockerActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION or
                        Intent.FLAG_ACTIVITY_NO_USER_ACTION or
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP
                )
                putExtra(EXTRA_PKG, pkg)
                if (!label.isNullOrEmpty()) putExtra(EXTRA_LABEL, label)
                if (!title.isNullOrEmpty()) putExtra(EXTRA_TITLE, title)
                if (!message.isNullOrEmpty()) putExtra(EXTRA_MESSAGE, message)
                if (postAcknowledgeBackCount > 0) {
                    putExtra(EXTRA_POST_ACK_BACK_COUNT, postAcknowledgeBackCount)
                }
                if (returnToPackageOnClose) {
                    putExtra(EXTRA_RETURN_TO_PACKAGE_ON_CLOSE, true)
                }
                if (postAcknowledgeYouTubeHome) {
                    putExtra(EXTRA_POST_ACK_YOUTUBE_HOME, true)
                }
                if (postAcknowledgeYouTubeClose) {
                    putExtra(EXTRA_POST_ACK_YOUTUBE_CLOSE, true)
                }
            }
            context.startActivity(i)
        }

        fun show(context: Context, pkg: String, label: String?) {
            if (!SwitchModeStore.isEnabled(context)) {
                clearVisibilityState("show_skipped_disabled")
                return
            }
            val i = Intent(context, BlockerActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION or
                        Intent.FLAG_ACTIVITY_NO_USER_ACTION or
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP
                )
                putExtra(EXTRA_PKG, pkg)
                if (!label.isNullOrEmpty()) putExtra(EXTRA_LABEL, label)
            }
            context.startActivity(i)
        }

        private fun bringPackageToFront(context: Context, pkg: String) {
            runCatching {
                context.packageManager.getLaunchIntentForPackage(pkg)?.apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                            Intent.FLAG_ACTIVITY_NO_ANIMATION
                    )
                }?.let { context.startActivity(it) }
            }
        }

        private fun launchYouTubeHome(context: Context) {
            // Use an explicit YouTube Home entry instead of ACTION_MAIN/CATEGORY_HOME.
            // This prevents OK on a Shorts block from falling back to the phone launcher.
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
                context.packageManager.getLaunchIntentForPackage("com.google.android.youtube")?.apply {
                    addFlags(flags)
                }
            ).filterNotNull()

            for (intent in candidates) {
                val started = runCatching {
                    context.startActivity(intent)
                    true
                }.getOrDefault(false)
                if (started) return
            }
        }

        private data class PendingBackNavigation(
            val pkg: String,
            val backCount: Int,
            val createdAt: Long
        )

        private data class PendingYouTubeHomeRedirect(
            val pkg: String,
            val createdAt: Long
        )

        @Volatile
        private var pendingBackNavigation: PendingBackNavigation? = null

        @Volatile
        private var pendingYouTubeHomeRedirect: PendingYouTubeHomeRedirect? = null

        private const val PENDING_NAV_TTL_MS = 8_000L

        @Synchronized
        fun queuePendingBackNavigation(pkg: String, backCount: Int) {
            if (pkg.isBlank() || backCount <= 0) return
            pendingBackNavigation = PendingBackNavigation(pkg = pkg, backCount = backCount, createdAt = System.currentTimeMillis())
        }

        @Synchronized
        fun consumePendingBackNavigationFor(pkg: String): Int {
            val pending = pendingBackNavigation ?: return 0
            val now = System.currentTimeMillis()
            if ((now - pending.createdAt) > PENDING_NAV_TTL_MS) {
                pendingBackNavigation = null
                return 0
            }
            if (pending.pkg != pkg) return 0
            pendingBackNavigation = null
            return pending.backCount.coerceAtLeast(0)
        }

        @Synchronized
        fun queuePendingYouTubeHomeRedirect(pkg: String) {
            if (pkg != "com.google.android.youtube") return
            pendingYouTubeHomeRedirect = PendingYouTubeHomeRedirect(pkg = pkg, createdAt = System.currentTimeMillis())
        }

        @Synchronized
        fun consumePendingYouTubeHomeRedirectFor(pkg: String): Boolean {
            val pending = pendingYouTubeHomeRedirect ?: return false
            val now = System.currentTimeMillis()
            if ((now - pending.createdAt) > PENDING_NAV_TTL_MS) {
                pendingYouTubeHomeRedirect = null
                return false
            }
            if (pending.pkg != pkg) return false
            pendingYouTubeHomeRedirect = null
            return true
        }

        fun sendHome(context: Context) {
            val home = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
            }

            try {
                context.startActivity(home)
            } catch (_: SecurityException) {
                // Some OEMs can resolve ACTION_MAIN/CATEGORY_HOME to protected setup/update wrappers (for example Samsung FOTA setup wizard). 
                // In that case, moving the current task back is safer than crashing the blocker UI.
                (context as? Activity)?.moveTaskToBack(true)
            } catch (_: RuntimeException) {
                // Best-effort fallback for broken/locked launcher resolution paths.
                (context as? Activity)?.moveTaskToBack(true)
            }
        }
    }
}
