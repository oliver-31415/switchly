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
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import at.saltyy.switchly.R
import at.saltyy.switchly.blocking.BlockingRuntime
import at.saltyy.switchly.theme.AccentColor
import at.saltyy.switchly.ui.ThemeUtils

class BlockerActivity : Activity() {

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

        // Privacy: never show any real content in the system overview/recents thumbnail.
        // (Only affects Switchly screens - Android does not allow changing previews of other apps.)
        runCatching {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                setRecentsScreenshotEnabled(false)
            }
        }

        setContentView(R.layout.activity_blocker)

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
        isVisible = true
        visiblePkg = intent?.getStringExtra(EXTRA_PKG)
        shownAt = System.currentTimeMillis()
        shownPkg = intent?.getStringExtra(EXTRA_PKG)

        tickRunning = true
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, 1000L)
    }

    override fun onPause() {
        flushDelta()
        isVisible = false
        visiblePkg = null
        tickRunning = false
        handler.removeCallbacks(tick)
        super.onPause()
    }

    override fun onBackPressed() {
        handleCloseAction()
    }

    override fun onDestroy() {
        isVisible = false
        visiblePkg = null
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
        val returnToPackageOnClose = intent?.getBooleanExtra(EXTRA_RETURN_TO_PACKAGE_ON_CLOSE, false) ?: false

        // For surface-block popups we prefer revealing the previously running app task again.
        // Re-launching the package from here can behave like a cold start on some OEMs and feel like the app was closed.
        if (returnToPackageOnClose && postAckBackCount <= 0) {
            BlockingRuntime.ensureRunning(this)
            finish()
            return
        }

        // When a controlled back navigation is queued we still need to bring the app task to the foreground first so Accessibility can consume the pending navigation against the correct package.
        if (pkg.isNotBlank() && postAckBackCount > 0) {
            bringPackageToFront(this, pkg)
            queuePendingBackNavigation(pkg, postAckBackCount)
            BlockingRuntime.ensureRunning(this)
            finish()
            return
        }

        sendHome(this)
        BlockingRuntime.ensureRunning(this)
        finish()
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
        private const val EXTRA_RETURN_TO_PACKAGE_ON_CLOSE = "return_to_package_on_close"

        //Used by [AppWatcherService] to re-show the blocker if the user swipes it away from recents.
        @Volatile
        var isVisible: Boolean = false
            private set

        @Volatile
        var visiblePkg: String? = null
            private set

        fun showDetailed(
            context: Context,
            pkg: String,
            label: String?,
            title: String?,
            message: String?,
            postAcknowledgeBackCount: Int = 0,
            returnToPackageOnClose: Boolean = false
        ) {
            val i = Intent(context, BlockerActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION or
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
            }
            context.startActivity(i)
        }

        fun show(context: Context, pkg: String, label: String?) {
            val i = Intent(context, BlockerActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION or
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

        private data class PendingBackNavigation(
            val pkg: String,
            val backCount: Int,
            val createdAt: Long
        )

        @Volatile
        private var pendingBackNavigation: PendingBackNavigation? = null

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

        fun sendHome(context: Context) {
            val home = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(home)
        }
    }
}
