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

package at.saltyy.switchly.feature.settings

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import androidx.transition.AutoTransition
import androidx.transition.TransitionManager
import at.saltyy.switchly.R
import at.saltyy.switchly.blocking.BlockingRuntime
import at.saltyy.switchly.data.prefs.BlockingToggleKeys
import at.saltyy.switchly.data.prefs.InAppLimitStore
import at.saltyy.switchly.data.prefs.ProfileStore
import at.saltyy.switchly.data.prefs.SurfaceLimitStore
import at.saltyy.switchly.data.prefs.SwitchModeStore
import at.saltyy.switchly.theme.AccentColor
import at.saltyy.switchly.theme.CustomAccentApplier
import at.saltyy.switchly.ui.EdgeToEdgeUtils
import at.saltyy.switchly.ui.ThemeUtils
import at.saltyy.switchly.ui.dialog.styleSwitchlyDialogButtons
import at.saltyy.switchly.util.EditingLockGuard
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.Locale

class InAppBlockingActivity : AppCompatActivity() {

    private var contentReady = false

    private val prefs by lazy { PreferenceManager.getDefaultSharedPreferences(this) }

    private fun sanitizeProfile(profile: String): String {
        return profile.trim()
            .lowercase(Locale.getDefault())
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
            .ifBlank { "default" }
    }

    private fun currentProfile(): String = ProfileStore.getCurrent(this) ?: "default"

    private fun scopedKey(baseKey: String): String {
        val p = sanitizeProfile(currentProfile())
        return "p_${p}_$baseKey"
    }

    private fun readProfileBool(baseKey: String, def: Boolean = false): Boolean {
        val k = scopedKey(baseKey)
        if (prefs.contains(k)) return prefs.getBoolean(k, def)

        if (prefs.contains(baseKey)) {
            val v = prefs.getBoolean(baseKey, def)
            // One-time copy into scoped key for current profile.
            prefs.edit { putBoolean(k, v) }
            return v
        }

        return def
    }

    private fun writeProfileBool(baseKey: String, value: Boolean) {
        prefs.edit { putBoolean(scopedKey(baseKey), value) }
    }

    private fun getInAppLimitMinutesForProfile(): Int {
        return InAppLimitStore.getLimitMinutes(this, currentProfile())
    }

    private fun getSurfaceRule(surfaceKey: String): Int {
        return SurfaceLimitStore.getRule(this, currentProfile(), surfaceKey)
    }

    private fun setSurfaceRule(surfaceKey: String, rule: Int) {
        SurfaceLimitStore.setRule(this, currentProfile(), surfaceKey, rule)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeUtils.applyAccentTheme(this)
        super.onCreate(savedInstanceState)
        if (SwitchModeStore.isBaseEnabled(this) || EditingLockGuard.blockWithDialog(this, R.string.edit_locked_manage_inapp)) {
            if (SwitchModeStore.isBaseEnabled(this)) {
                EditingLockGuard.showLockedDialog(this, R.string.edit_locked_manage_inapp)
            }
            return
        }
        setContentView(R.layout.activity_in_app_blocking)
        contentReady = true

        CustomAccentApplier.applyIfNeeded(this)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setBackgroundColor(AccentColor.getToolbarColor(this))
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        // Website blocking (global)
        // In-app limit (applies to timed in-app surfaces that are toggled on)
        val tvLimit = findViewById<TextView>(R.id.tvInAppLimitValue)
        val btnLimit = findViewById<Button>(R.id.btnInAppLimit)
        val openLimit = { showInAppLimitDialog(tvLimit) }
        tvLimit.setOnClickListener { openLimit() }
        btnLimit.setOnClickListener { openLimit() }

        // Global in-app master (same key as Toggle Options → Blocking controls)
        val rowInAppMaster = findViewById<View>(R.id.rowInAppMaster)
        val swInAppMaster = findViewById<SwitchCompat>(R.id.swInAppMaster)
        swInAppMaster.isChecked = readProfileBool(BlockingToggleKeys.KEY_BLOCK_INAPP, true)
        rowInAppMaster.setOnClickListener { swInAppMaster.toggle() }
        swInAppMaster.setOnCheckedChangeListener { _, checked ->
            writeProfileBool(BlockingToggleKeys.KEY_BLOCK_INAPP, checked)
            // Make sure service policy cache refreshes quickly
            BlockingRuntime.ensureRunning(this)
        }

        // YouTube
        setupTimedSwitch(
            R.id.swYtShorts,
            BlockingToggleKeys.KEY_BLOCK_YT_SHORTS,
            surfaceKey = "yt:shorts",
            surfaceLabel = getString(R.string.in_app_surface_shorts_label),
            tvLimit = tvLimit
        )
        setupTimedSwitch(
            R.id.swYtSubscriptions,
            BlockingToggleKeys.KEY_BLOCK_YT_SUBSCRIPTIONS,
            surfaceKey = "yt:subscriptions",
            surfaceLabel = getString(R.string.in_app_surface_subscriptions_label),
            tvLimit = tvLimit
        )
        setupTimedSwitch(
            R.id.swYtYou,
            BlockingToggleKeys.KEY_BLOCK_YT_YOU,
            surfaceKey = "yt:you",
            surfaceLabel = getString(R.string.in_app_surface_you_label),
            tvLimit = tvLimit
        )
        setupSwitch(R.id.swYtPip, BlockingToggleKeys.KEY_BLOCK_YT_PIP)

        // Instagram
        setupTimedSwitch(
            R.id.swIgReels,
            BlockingToggleKeys.KEY_BLOCK_IG_REELS,
            surfaceKey = "ig:reels",
            surfaceLabel = getString(R.string.in_app_surface_reels_label),
            tvLimit = tvLimit
        )
        setupTimedSwitch(
            R.id.swIgExplore,
            BlockingToggleKeys.KEY_BLOCK_IG_EXPLORE,
            surfaceKey = "ig:explore",
            surfaceLabel = getString(R.string.in_app_surface_explore_label),
            tvLimit = tvLimit
        )
        setupTimedSwitch(
            R.id.swIgSearch,
            BlockingToggleKeys.KEY_BLOCK_IG_SEARCH,
            surfaceKey = "ig:search",
            surfaceLabel = getString(R.string.in_app_surface_search_label),
            tvLimit = tvLimit
        )
        setupSwitch(R.id.swIgComments, BlockingToggleKeys.KEY_BLOCK_IG_COMMENTS)
        setupTimedSwitch(
            R.id.swIgStories,
            BlockingToggleKeys.KEY_BLOCK_IG_STORIES,
            surfaceKey = "ig:stories",
            surfaceLabel = getString(R.string.in_app_surface_stories_label),
            tvLimit = tvLimit
        )

        // X/Twitter
        setupTimedSwitch(
            R.id.swXForYou,
            BlockingToggleKeys.KEY_BLOCK_X_HOME,
            surfaceKey = "x:foryou",
            surfaceLabel = getString(R.string.in_app_surface_home_label),
            tvLimit = tvLimit
        )
        setupTimedSwitch(
            R.id.swXSearch,
            BlockingToggleKeys.KEY_BLOCK_X_SEARCH,
            surfaceKey = "x:search",
            surfaceLabel = getString(R.string.in_app_surface_search_label),
            tvLimit = tvLimit
        )
        setupTimedSwitch(
            R.id.swXGrok,
            BlockingToggleKeys.KEY_BLOCK_X_GROK,
            surfaceKey = "x:grok",
            surfaceLabel = getString(R.string.in_app_surface_grok_label),
            tvLimit = tvLimit
        )
        setupTimedSwitch(
            R.id.swXNotifications,
            BlockingToggleKeys.KEY_BLOCK_X_NOTIFICATIONS,
            surfaceKey = "x:notifications",
            surfaceLabel = getString(R.string.in_app_surface_notifications_label),
            tvLimit = tvLimit
        )

        // Snapchat
        setupTimedSwitch(
            R.id.swSnapMap,
            BlockingToggleKeys.KEY_BLOCK_SNAP_MAP,
            surfaceKey = "snap:map",
            surfaceLabel = getString(R.string.in_app_surface_map_label),
            tvLimit = tvLimit
        )
        setupTimedSwitch(
            R.id.swSnapStories,
            BlockingToggleKeys.KEY_BLOCK_SNAP_STORIES,
            surfaceKey = "snap:stories",
            surfaceLabel = getString(R.string.in_app_surface_stories_label),
            tvLimit = tvLimit
        )
        setupTimedSwitch(
            R.id.swSnapSpotlight,
            BlockingToggleKeys.KEY_BLOCK_SNAP_SPOTLIGHT,
            surfaceKey = "snap:spotlight",
            surfaceLabel = getString(R.string.in_app_surface_spotlight_label),
            tvLimit = tvLimit
        )
        setupTimedSwitch(
            R.id.swSnapFollowing,
            BlockingToggleKeys.KEY_BLOCK_SNAP_FOLLOWING,
            surfaceKey = "snap:following",
            surfaceLabel = getString(R.string.in_app_surface_following_label),
            tvLimit = tvLimit
        )

        // Expand/collapse sections
        bindExpand(R.id.headerYouTube, R.id.contentYouTube, R.id.arrowYouTube)
        bindExpand(R.id.headerInstagram, R.id.contentInstagram, R.id.arrowInstagram)
        bindExpand(R.id.headerX, R.id.contentX, R.id.arrowX)
        bindExpand(R.id.headerSnapchat, R.id.contentSnapchat, R.id.arrowSnapchat)

        refreshInAppLimit(tvLimit)
    }

    override fun onResume() {
        super.onResume()

        // If editing is locked, onCreate() exits before setContentView().
        // Android still calls onResume(), so avoid touching layout views that do not exist in that path.
        if (!contentReady) return

        CustomAccentApplier.applyIfNeeded(this)
        val limitView: TextView? = findViewById(R.id.tvInAppLimitValue)
        limitView?.let { refreshInAppLimit(it) }
        val masterSwitch: SwitchCompat? = findViewById(R.id.swInAppMaster)
        masterSwitch?.isChecked = readProfileBool(BlockingToggleKeys.KEY_BLOCK_INAPP, true)
    }

    private fun setupSwitch(switchId: Int, prefKey: String) {
        val sw = findViewById<SwitchCompat>(switchId)
        sw.isChecked = readProfileBool(prefKey, false)
        sw.setOnCheckedChangeListener { _, checked ->
            writeProfileBool(prefKey, checked)
            // Make sure the service is alive, so changes feel instant.
            BlockingRuntime.ensureRunning(this)
        }
    }

    /**
     * Like [setupSwitch], but for a specific in-app surface.
     */
    private fun setupTimedSwitch(
        switchId: Int,
        prefKey: String,
        surfaceKey: String,
        surfaceLabel: String,
        tvLimit: TextView
    ) {
        val sw = findViewById<SwitchCompat>(switchId)
        sw.isChecked = readProfileBool(prefKey, false)
        if (sw.isChecked) {
            setSurfaceRule(surfaceKey, -1)
        }

        sw.setOnLongClickListener(null)
        sw.setOnCheckedChangeListener { _, checked ->
            writeProfileBool(prefKey, checked)
            if (checked) {
                setSurfaceRule(surfaceKey, -1)
            } else {
                SurfaceLimitStore.clear(this, currentProfile(), surfaceKey)
            }
            BlockingRuntime.ensureRunning(this)
            refreshInAppLimit(tvLimit)
        }
    }

    private fun showSurfaceRuleDialog(
        surfaceKey: String,
        surfaceLabel: String,
        tvLimit: TextView,
        onCancel: (() -> Unit)?
    ) {
        val globalMin = getInAppLimitMinutesForProfile()

        val options = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()

        if (globalMin > 0) {
            options.add(getString(R.string.in_app_surface_use_global_limit_fmt, globalMin))
            actions.add { setSurfaceRule(surfaceKey, 0) }
        } else {
            options.add(getString(R.string.in_app_surface_set_global_limit))
            actions.add {
                showInAppLimitDialog(tvLimit)
                setSurfaceRule(surfaceKey, 0)
            }
        }

        options.add(getString(R.string.in_app_surface_always_block))
        actions.add { setSurfaceRule(surfaceKey, -1) }

        options.add("15 min/day")
        actions.add { setSurfaceRule(surfaceKey, 15) }
        options.add("30 min/day")
        actions.add { setSurfaceRule(surfaceKey, 30) }
        options.add("60 min/day")
        actions.add { setSurfaceRule(surfaceKey, 60) }

        options.add(getString(R.string.in_app_surface_custom))
        actions.add { showCustomSurfaceLimitDialog(surfaceKey, surfaceLabel, tvLimit) }

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.in_app_surface_rule_dialog_title, surfaceLabel))
            .setItems(options.toTypedArray()) { _, which ->
                actions.getOrNull(which)?.invoke()
                BlockingRuntime.ensureRunning(this)
                refreshInAppLimit(tvLimit)
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> onCancel?.invoke() }
            .setOnCancelListener { onCancel?.invoke() }
            .create()

        dialog.setOnShowListener { dialog.styleSwitchlyDialogButtons() }
        dialog.show()
    }

    private fun showCustomSurfaceLimitDialog(surfaceKey: String, surfaceLabel: String, tvLimit: TextView) {
        val current = getSurfaceRule(surfaceKey).coerceAtLeast(0)

        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = getString(R.string.minutes_hint)
            setText(if (current > 0) current.toString() else "")
        }

        val container = FrameLayout(this).apply {
            val m = (24 * resources.displayMetrics.density).toInt()
            setPadding(m, 0, m, 0)
            addView(
                input,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.in_app_surface_daily_limit_title, surfaceLabel))
            .setMessage(getString(R.string.set_daily_limit_desc))
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val minutes = input.text?.toString()?.trim()?.toIntOrNull() ?: 0
                if (minutes > 0) {
                    setSurfaceRule(surfaceKey, minutes)
                } else {
                    // Treat empty/0 as: use global limit.
                    setSurfaceRule(surfaceKey, 0)
                }
                BlockingRuntime.ensureRunning(this)
                refreshInAppLimit(tvLimit)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        dialog.setOnShowListener { dialog.styleSwitchlyDialogButtons() }
        dialog.show()
    }

    private fun bindExpand(headerId: Int, contentId: Int, arrowId: Int) {
        val header = findViewById<View>(headerId)
        val content = findViewById<View>(contentId)
        val arrow = findViewById<ImageView>(arrowId)

        header.setOnClickListener {
            val show = content.visibility != View.VISIBLE
            val parent = header.parent
            val vg = parent as? ViewGroup ?: return@setOnClickListener

            TransitionManager.beginDelayedTransition(vg, AutoTransition())
            content.visibility = if (show) View.VISIBLE else View.GONE
            arrow.animate().rotation(if (show) 180f else 0f).setDuration(150).start()
        }
    }

    private fun refreshInAppLimit(tv: TextView) {
        val limitMin = getInAppLimitMinutesForProfile()
        tv.text = if (limitMin <= 0) getString(R.string.no_limit)
        else getString(R.string.daily_limit_value_format, limitMin)
    }

    private fun showInAppLimitDialog(tv: TextView) {
        val current = getInAppLimitMinutesForProfile()

        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = getString(R.string.minutes_hint)
            setText(if (current > 0) current.toString() else "")
        }

        val inputContainer = FrameLayout(this).apply {
            val pad = (24f * resources.displayMetrics.density).toInt()
            setPadding(pad, 0, pad, 0)
            addView(
                input,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.in_app_blocking_limit_title))
            .setMessage(getString(R.string.in_app_blocking_limit_desc))
            .setView(inputContainer)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val minutes = input.text?.toString()?.trim()?.toIntOrNull() ?: 0
                InAppLimitStore.setLimitMinutes(this, currentProfile(), minutes)

                BlockingRuntime.ensureRunning(this)
                refreshInAppLimit(tv)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.clear_limit) { _, _ ->
                InAppLimitStore.setLimitMinutes(this, currentProfile(), 0)
                BlockingRuntime.ensureRunning(this)
                refreshInAppLimit(tv)
            }
            .create()

        dialog.setOnShowListener { dialog.styleSwitchlyDialogButtons() }
        dialog.show()
    }
}