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

import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.core.widget.NestedScrollView
import at.saltyy.switchly.R
import at.saltyy.switchly.blocking.BlockingRuntime
import at.saltyy.switchly.data.prefs.BlockingToggleKeys
import at.saltyy.switchly.data.prefs.IgnoredUsageAppsStore
import at.saltyy.switchly.data.prefs.InAppRuleStore
import at.saltyy.switchly.data.prefs.ProfileRuleModeStore
import at.saltyy.switchly.data.prefs.ProfileStore
import at.saltyy.switchly.data.prefs.SurfaceLimitStore
import at.saltyy.switchly.theme.AccentColor
import at.saltyy.switchly.theme.CustomAccentApplier
import at.saltyy.switchly.ui.EdgeToEdgeUtils
import at.saltyy.switchly.ui.SegmentedToggleUi
import at.saltyy.switchly.ui.ThemeUtils
import at.saltyy.switchly.ui.dialog.styleSwitchlyDialogButtons
import at.saltyy.switchly.util.EditingLockGuard
import at.saltyy.switchly.util.PackageLaunchIntentCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class InAppRulesActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_FOCUS_PACKAGE = "extra_focus_package"
    }

    private lateinit var container: LinearLayout
    private lateinit var scroll: NestedScrollView
    private lateinit var modeToggle: MaterialButtonToggleGroup
    private lateinit var modeBlockButton: MaterialButton
    private lateinit var modeAllowButton: MaterialButton
    private lateinit var modeSummary: TextView
    private var updatingModeUi = false

    private data class Surface(
        val labelRes: Int,
        val prefKey: String?,
        val surfaceKey: String?,
        val statusRes: Int
    )

    private data class AppGroup(
        val titleRes: Int,
        val packageName: String,
        val surfaces: List<Surface>
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeUtils.applyAccentTheme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_in_app_rules)
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        scroll = findViewById(R.id.scrollContent)
        container = findViewById(R.id.appContainer)
        modeToggle = findViewById(R.id.toggleInAppRuleMode)
        modeBlockButton = findViewById(R.id.btnInAppModeBlock)
        modeAllowButton = findViewById(R.id.btnInAppModeAllow)
        modeSummary = findViewById(R.id.tvInAppRuleModeSummary)

        EdgeToEdgeUtils.setupClassic(activity = this, toolbar = toolbar)
        toolbar.setBackgroundColor(AccentColor.getToolbarColor(this))
        toolbar.subtitle = getString(R.string.in_app_rules_profile_subtitle, currentProfile())
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        setupModeToggle()
        render()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_in_app_rules, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_in_app_info -> {
                showInAppRulesInfo()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onResume() {
        super.onResume()
        if (::container.isInitialized) render()
    }

    private fun showInAppRulesInfo() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.in_app_rules_info_title)
            .setMessage(getString(R.string.in_app_rules_info_body))
            .setPositiveButton(android.R.string.ok, null)
            .show()
            .styleSwitchlyDialogButtons()
    }

    private fun render() {
        CustomAccentApplier.applyIfNeeded(this)
        container.removeAllViews()
        val focusPackage = intent.getStringExtra(EXTRA_FOCUS_PACKAGE).orEmpty()
        var focusView: View? = null

        val profile = currentProfile()
        val hiddenFromPickers = IgnoredUsageAppsStore.getAppPickerHiddenPackages(this)
        val configuredPackages = InAppRuleStore.getPackagesWithEnabledRules(this, profile)
        val visibleGroups = groups().filter { group ->
            isAppInstalled(group.packageName) &&
                (group.packageName !in hiddenFromPickers || group.packageName in configuredPackages)
        }
        updateModeUi()
        if (visibleGroups.isEmpty()) {
            container.addView(emptyState())
            return
        }

        visibleGroups.forEach { group ->
            val card = buildGroupCard(group)
            container.addView(card)
            if (group.packageName == focusPackage) focusView = card
        }

        focusView?.let { targetView ->
            targetView.post {
                targetView.requestFocus()
                scroll.smoothScrollTo(0, targetView.top)
            }
        }
    }

    private fun isInAppAllowMode(): Boolean = InAppRuleStore.isAllowMode(this, currentProfile())

    private fun ruleSwitchText(surfaceLabel: String): String =
        getString(
            if (isInAppAllowMode()) R.string.in_app_rule_allow_surface_fmt else R.string.in_app_rule_block_surface_fmt,
            surfaceLabel
        )

    private fun setSurfaceRuleForMode(surfaceKey: String, checked: Boolean) {
        if (isInAppAllowMode()) {
            // In allow-mode the switch marks an allowed exception, so no block limit rule should be kept for this surface.
            SurfaceLimitStore.clear(this, currentProfile(), surfaceKey)
        } else if (checked) {
            SurfaceLimitStore.setRule(this, currentProfile(), surfaceKey, -1)
        } else {
            SurfaceLimitStore.clear(this, currentProfile(), surfaceKey)
        }
    }

    private fun setupModeToggle() {
        modeToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked || updatingModeUi) return@addOnButtonCheckedListener
            if (EditingLockGuard.isLocked(this)) {
                updateModeUi()
                return@addOnButtonCheckedListener
            }

            val mode = if (checkedId == R.id.btnInAppModeAllow) {
                InAppRuleStore.MODE_ALLOW_SELECTED
            } else {
                InAppRuleStore.MODE_BLOCK_SELECTED
            }
            if (mode == InAppRuleStore.getMode(this, currentProfile())) {
                updateModeUi()
                return@addOnButtonCheckedListener
            }

            InAppRuleStore.setMode(this, currentProfile(), mode)
            BlockingRuntime.ensureRunning(this)
            render()
        }
    }

    private fun updateModeUi() {
        val allowMode = isInAppAllowMode()
        val selectedId = if (allowMode) R.id.btnInAppModeAllow else R.id.btnInAppModeBlock
        val readOnly = EditingLockGuard.isLocked(this)

        updatingModeUi = true
        try {
            if (modeToggle.checkedButtonId != selectedId) {
                modeToggle.check(selectedId)
            }
            modeToggle.isEnabled = !readOnly
            modeBlockButton.isEnabled = !readOnly
            modeAllowButton.isEnabled = !readOnly
            modeToggle.alpha = if (readOnly) 0.62f else 1f
            modeSummary.setText(
                if (allowMode) R.string.in_app_rule_mode_allow_summary
                else R.string.in_app_rule_mode_block_summary
            )
            SegmentedToggleUi.apply(
                this,
                listOf(modeBlockButton, modeAllowButton),
                selectedId,
            )
        } finally {
            updatingModeUi = false
        }
    }

    private fun buildGroupCard(group: AppGroup): View {
        val card = MaterialCardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
            setCardBackgroundColor(ContextCompat.getColor(this@InAppRulesActivity, R.color.switchly_card_bg))
            strokeColor = ContextCompat.getColor(this@InAppRulesActivity, R.color.switchly_card_stroke)
            strokeWidth = dp(1)
            radius = dp(8).toFloat()
        }

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(12))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(ImageView(this).apply {
            appIcon(group.packageName)?.let {
                setImageDrawable(it)
                imageTintList = null
            } ?: run {
                setImageResource(R.drawable.app_blocking_black_24)
                imageTintList = ColorStateList.valueOf(AccentColor.getAccentColorInt(this@InAppRulesActivity))
            }
            layoutParams = LinearLayout.LayoutParams(dp(24), dp(24))
        })
        header.addView(TextView(this).apply {
            text = getString(group.titleRes)
            setTypeface(typeface, Typeface.BOLD)
            textSize = 16f
            setTextColor(onSurfaceColor())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(12)
            }
        })
        header.addView(TextView(this).apply {
            text = getString(R.string.in_app_status_installed)
            alpha = 0.72f
            textSize = 12f
            setTextColor(AccentColor.getAccentColorInt(this@InAppRulesActivity))
        })
        body.addView(header)

        group.surfaces.forEach { surface ->
            body.addView(buildSurfaceRow(surface, group.packageName))
        }

        card.addView(body)
        return card
    }

    private fun buildSurfaceRow(surface: Surface, packageName: String): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, 0)
        }
        row.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(this@InAppRulesActivity).apply {
                text = if (surface.prefKey != null) ruleSwitchText(getString(surface.labelRes)) else getString(surface.labelRes)
                textSize = 15f
                setTextColor(onSurfaceColor())
            })
            addView(TextView(this@InAppRulesActivity).apply {
                text = getString(surface.statusRes)
                alpha = 0.72f
                textSize = 12f
                setTextColor(onSurfaceColor())
            })
        })

        val prefKey = surface.prefKey
        val readOnly = EditingLockGuard.isLocked(this)
        val sw = SwitchCompat(this).apply {
            isEnabled = prefKey != null && !readOnly
            alpha = when {
                prefKey == null -> 0.52f
                readOnly -> 0.45f
                else -> 1f
            }
            if (prefKey != null) {
                isChecked = readProfileBool(prefKey)
                if (isChecked && !readOnly) {
                    surface.surfaceKey?.let { setSurfaceRuleForMode(it, checked = true) }
                }
                if (!readOnly) {
                    setOnCheckedChangeListener { button, checked ->
                        if (EditingLockGuard.isLocked(this@InAppRulesActivity)) {
                            button.setOnCheckedChangeListener(null)
                            button.isChecked = readProfileBool(prefKey)
                            button.isEnabled = false
                            button.alpha = 0.45f
                            button.post { render() }
                            return@setOnCheckedChangeListener
                        }
                        writeProfileBool(prefKey, checked)
                        surface.surfaceKey?.let { surfaceKey ->
                            setSurfaceRuleForMode(surfaceKey, checked)
                        }
                        keepAppAllowedForInAppRule(packageName, prefKey, checked)
                        BlockingRuntime.ensureRunning(this@InAppRulesActivity)
                    }
                }
            }
        }
        CustomAccentApplier.tintSwitch(sw)
        row.addView(sw)
        return row
    }

    private fun groups(): List<AppGroup> = listOf(
        AppGroup(
            R.string.in_app_rules_youtube,
            "com.google.android.youtube",
            listOf(
                Surface(R.string.in_app_surface_shorts_label, BlockingToggleKeys.KEY_BLOCK_YT_SHORTS, "yt:shorts", R.string.in_app_status_experimental),
                Surface(R.string.in_app_surface_subscriptions_label, BlockingToggleKeys.KEY_BLOCK_YT_SUBSCRIPTIONS, "yt:subscriptions", R.string.in_app_status_supported),
                Surface(R.string.in_app_surface_you_label, BlockingToggleKeys.KEY_BLOCK_YT_YOU, "yt:you", R.string.in_app_status_supported),
                Surface(R.string.in_app_surface_mini_player_label, BlockingToggleKeys.KEY_BLOCK_YT_MINI_PLAYER, "yt:miniplayer", R.string.in_app_status_experimental),
                Surface(R.string.in_app_surface_pip_label, BlockingToggleKeys.KEY_BLOCK_YT_PIP, "yt:pip", R.string.in_app_status_experimental)
            )
        ),
        AppGroup(
            R.string.in_app_rules_instagram,
            "com.instagram.android",
            listOf(
                Surface(R.string.in_app_surface_reels_label, BlockingToggleKeys.KEY_BLOCK_IG_REELS, "ig:reels", R.string.in_app_status_experimental),
                Surface(R.string.in_app_surface_explore_label, BlockingToggleKeys.KEY_BLOCK_IG_EXPLORE, "ig:explore", R.string.in_app_status_experimental),
                Surface(R.string.in_app_surface_search_label, BlockingToggleKeys.KEY_BLOCK_IG_SEARCH, "ig:search", R.string.in_app_status_experimental),
                Surface(R.string.in_app_surface_stories_label, BlockingToggleKeys.KEY_BLOCK_IG_STORIES, "ig:stories", R.string.in_app_status_experimental),
                Surface(R.string.in_app_surface_comments_label, BlockingToggleKeys.KEY_BLOCK_IG_COMMENTS, "ig:comments", R.string.in_app_status_experimental)
            )
        ),
        AppGroup(
            R.string.in_app_rules_x,
            "com.twitter.android",
            listOf(
                Surface(R.string.in_app_surface_home_label, BlockingToggleKeys.KEY_BLOCK_X_HOME, "x:foryou", R.string.in_app_status_experimental),
                Surface(R.string.in_app_surface_search_label, BlockingToggleKeys.KEY_BLOCK_X_SEARCH, "x:search", R.string.in_app_status_experimental),
                Surface(R.string.in_app_surface_grok_label, BlockingToggleKeys.KEY_BLOCK_X_GROK, "x:grok", R.string.in_app_status_experimental),
                Surface(R.string.in_app_surface_notifications_label, BlockingToggleKeys.KEY_BLOCK_X_NOTIFICATIONS, "x:notifications", R.string.in_app_status_experimental)
            )
        ),
        AppGroup(
            R.string.in_app_rules_snapchat,
            "com.snapchat.android",
            listOf(
                Surface(R.string.in_app_surface_spotlight_label, BlockingToggleKeys.KEY_BLOCK_SNAP_SPOTLIGHT, "snap:spotlight", R.string.in_app_status_supported),
                Surface(R.string.in_app_surface_stories_label, BlockingToggleKeys.KEY_BLOCK_SNAP_STORIES, "snap:stories", R.string.in_app_status_supported),
                Surface(R.string.in_app_surface_map_label, BlockingToggleKeys.KEY_BLOCK_SNAP_MAP, "snap:map", R.string.in_app_status_supported),
                Surface(R.string.in_app_surface_following_label, BlockingToggleKeys.KEY_BLOCK_SNAP_FOLLOWING, "snap:following", R.string.in_app_status_supported)
            )
        ),
        AppGroup(
            R.string.in_app_rules_facebook,
            "com.facebook.katana",
            listOf(Surface(R.string.in_app_surface_reels_label, null, null, R.string.in_app_status_planned))
        ),
        AppGroup(
            R.string.in_app_rules_tiktok,
            "com.zhiliaoapp.musically",
            listOf(Surface(R.string.in_app_rules_for_you, null, null, R.string.in_app_status_planned))
        )
    )

    private fun emptyState(): View {
        val card = MaterialCardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
            setCardBackgroundColor(ContextCompat.getColor(this@InAppRulesActivity, R.color.switchly_card_bg))
            strokeColor = ContextCompat.getColor(this@InAppRulesActivity, R.color.switchly_card_stroke)
            strokeWidth = dp(1)
            radius = dp(8).toFloat()
        }
        card.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            addView(TextView(this@InAppRulesActivity).apply {
                text = getString(R.string.in_app_rules_empty_installed)
                setTypeface(typeface, Typeface.BOLD)
                textSize = 16f
                setTextColor(onSurfaceColor())
            })
            addView(TextView(this@InAppRulesActivity).apply {
                text = getString(R.string.in_app_rules_empty_installed_summary)
                textSize = 14f
                alpha = 0.72f
                setTextColor(onSurfaceColor())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(4) }
            })
        })
        return card
    }

    private fun isAppInstalled(packageName: String): Boolean =
        PackageLaunchIntentCompat.isLaunchable(this, packageName)

    private fun appIcon(packageName: String): Drawable? {
        return runCatching {
            val ai = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationIcon(ai)
        }.getOrNull()
    }

    private fun currentProfile(): String = ProfileStore.getCurrent(this) ?: "default"

    private fun readProfileBool(baseKey: String): Boolean {
        return InAppRuleStore.isRuleSelected(this, currentProfile(), baseKey)
    }

    private fun writeProfileBool(baseKey: String, value: Boolean) {
        InAppRuleStore.setRuleSelected(this, currentProfile(), baseKey, value)
    }

    private fun keepAppAllowedForInAppRule(packageName: String, baseKey: String, enabled: Boolean) {
        if (!enabled) {
            return
        }
        val profile = currentProfile()
        if (!ProfileRuleModeStore.isAllowMode(this, profile)) {
            return
        }
        if (InAppRuleStore.packageForRuleKey(baseKey) != packageName) {
            return
        }
        val currentAllowed = ProfileStore.getAllowedForProfile(this, profile)
        if (packageName !in currentAllowed) {
            ProfileStore.setAllowedForProfile(this, profile, currentAllowed + packageName)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun onSurfaceColor(): Int =
        MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface, 0)
}
