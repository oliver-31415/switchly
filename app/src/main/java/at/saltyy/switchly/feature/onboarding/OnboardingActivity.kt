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

package at.saltyy.switchly.feature.onboarding

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import at.saltyy.switchly.data.prefs.AutomationModeStore
import at.saltyy.switchly.data.prefs.ExactAlarmPermissionSync
import at.saltyy.switchly.data.prefs.NotificationBlockStore
import android.view.View
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.content.getSystemService
import androidx.core.graphics.ColorUtils
import androidx.core.net.toUri
import androidx.viewpager2.widget.ViewPager2
import at.saltyy.switchly.R
import at.saltyy.switchly.blocking.SwitchlyAccessibilityService
import at.saltyy.switchly.data.onboarding.OnboardingPage
import at.saltyy.switchly.data.prefs.EmergencyBypassStore
import at.saltyy.switchly.data.prefs.ProfileStore
import at.saltyy.switchly.data.prefs.ProfileRuleModeStore
import at.saltyy.switchly.data.prefs.ScheduleStore
import at.saltyy.switchly.data.prefs.SwitchModeStore
import at.saltyy.switchly.feature.onboarding.adapters.OnboardingPagerAdapter
import at.saltyy.switchly.feature.picker.AppPickerActivity
import at.saltyy.switchly.feature.schedule.SchedulesActivity
import at.saltyy.switchly.feature.settings.AccessibilityDisclosure
import at.saltyy.switchly.feature.settings.ToggleOptionsActivity
import at.saltyy.switchly.feature.tools.ManageKeysActivity
import at.saltyy.switchly.feature.usage.UsageStatsRepo
import at.saltyy.switchly.theme.AccentColor
import at.saltyy.switchly.ui.MainActivity
import at.saltyy.switchly.ui.ThemeUtils
import at.saltyy.switchly.ui.dialog.showAccented
import at.saltyy.switchly.util.PermissionUtils
import at.saltyy.switchly.util.getIntCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class OnboardingActivity : ComponentActivity() {

    companion object {
        private const val PREFS = "switchly_prefs"
        private const val KEY_DONE = "onboarding_done"
        private const val KEY_VERSION = "onboarding_version"
        private const val KEY_CONTROLS_VISITED = "onboard_controls_visited"
        const val EXTRA_FROM_ONBOARDING = "extra_from_onboarding"
        const val EXTRA_FORCE = "extra_force_tutorial"
        /**
         * Bump this whenever the onboarding flow changes in a way that requires existing users to go through it again.
         * From 220 on, the version is identical to the release number.
         * v1: initial release
         * v2: change app blocking core permission to Accessibility.
         * v3: add required Usage Access step + 7-day usage summary.
         * v4: previously made battery optimization required + improved permission completion states.
         * v5: hide quick summary until Usage Access is granted + add second opt-in confirm for NFC-required optional toggle.
         * v6: onboarding optional-features NFC toggle now shows the same immediate confirm popup as Settings.
         * v220: rework oneboardinhg flow to multiple pages + add new required steps for notification blocking and schedule permissions.
         */
        const val ONBOARDING_VERSION = 220
    }

    private var forced: Boolean = false

    private lateinit var pager: ViewPager2
    private lateinit var pages: List<OnboardingPage>
    private lateinit var pagerAdapter: OnboardingPagerAdapter
    private lateinit var btnNext: MaterialButton
    private lateinit var btnSkip: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeUtils.applyAccentTheme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding)

        forced = intent.getBooleanExtra(EXTRA_FORCE, false)

        val sp = getSharedPreferences(PREFS, MODE_PRIVATE)

        // Cloud restore may rehydrate integer prefs as Long (Firestore stores integral numbers as Long).
        // SharedPreferences crashes with ClassCastException if the stored type differs from the requested one.
        // Read defensively and "heal" it back to Int.
        val seenVersion = sp.getIntCompat(KEY_VERSION, 0)

        // Gate: show onboarding again if the onboarding version changed
        if (!forced && seenVersion >= ONBOARDING_VERSION) {
            finishToMain()
            return
        }

        enableEdgeToEdge()

        pager = findViewById(R.id.viewPager)
        btnNext = findViewById(R.id.btn_next)
        btnSkip = findViewById(R.id.btn_skip)

        applyFooterButtonAccent(btnSkip, btnNext)

        pages = buildPages()
        pagerAdapter = OnboardingPagerAdapter(activity = this, pages = pages)
        pager.adapter = pagerAdapter

        updateButtons(pager.currentItem)

        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateButtons(position)
            }
        })

        btnSkip.setOnClickListener {
            val firstIncompleteRequired = pages.indexOfFirst {
                it.level == OnboardingPage.Level.REQUIRED && it.completionCheck != null && !it.completionCheck.invoke(this)
            }
            if (firstIncompleteRequired >= 0) {
                pager.setCurrentItem(firstIncompleteRequired, true)
                Toast.makeText(
                    this,
                    pages[firstIncompleteRequired].requiredMessage ?: getString(R.string.onb_required_step),
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }

            if (!forced) markDone()
            finishToMain()
        }

        btnNext.setOnClickListener {
            val pos = pager.currentItem
            val page = pages.getOrNull(pos)

            // Gate required permission steps
            if (page != null && page.level == OnboardingPage.Level.REQUIRED && page.completionCheck != null) {
                val ok = page.completionCheck.invoke(this)
                if (!ok) {
                    val msg = page.requiredMessage ?: getString(R.string.onb_required_step)
                    MaterialAlertDialogBuilder(this)
                        .setTitle(page.title)
                        .setMessage(msg)
                        .setPositiveButton(R.string.onb_open) { _, _ ->
                            page.action?.invoke(this)
                        }
                        .setNegativeButton(R.string.cancel, null)
                        .showAccented()
                    return@setOnClickListener
                }
            }

            if (pos < pages.lastIndex) {
                pager.currentItem = pos + 1
            } else {
                if (!forced) markDone()
                finishToMain()
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (::pager.isInitialized && ::pagerAdapter.isInitialized) {
            pagerAdapter.notifyItemChanged(pager.currentItem)
        }
    }

    override fun onResume() {
        super.onResume()
        findViewById<MaterialButton>(R.id.btn_skip)?.let { skip ->
            findViewById<MaterialButton>(R.id.btn_next)?.let { next ->
                applyFooterButtonAccent(skip, next)
            }
        }

        if (!::pager.isInitialized || !::pages.isInitialized) return

        // Rebuild lightweight text pages when the user returns from App Picker, permissions or setup screens.
        // This keeps the final blocked-app summary up to date without forcing a full activity restart.
        val oldPos = pager.currentItem.coerceIn(0, pages.lastIndex)
        val oldTitle = pages.getOrNull(oldPos)?.title
        pages = buildPages()
        pagerAdapter = OnboardingPagerAdapter(activity = this, pages = pages)
        pager.adapter = pagerAdapter
        val target = oldTitle?.let { title -> pages.indexOfFirst { it.title == title }.takeIf { it >= 0 } }
            ?: oldPos.coerceIn(0, pages.lastIndex)
        pager.setCurrentItem(target.coerceIn(0, pages.lastIndex), false)

        val pos = pager.currentItem.coerceIn(0, pages.lastIndex)
        updateButtons(pos)
        pagerAdapter.notifyItemChanged(pos)

        // If the current page was a required setup step and it is now complete, auto-advance once.
        val page = pages.getOrNull(pos) ?: return
        if (page.level == OnboardingPage.Level.REQUIRED && page.completionCheck?.invoke(this) == true) {
            if (pos < pages.lastIndex) {
                pager.post { pager.currentItem = pos + 1 }
            }
        }
    }

    private fun updateButtons(pos: Int) {
        val last = pos == pages.lastIndex
        btnNext.text = if (last) getString(R.string.onb_done) else getString(R.string.onb_next)
        renderPageIndicator(pos)
    }

    private fun renderPageIndicator(activePosition: Int) {
        val container = findViewById<LinearLayout>(R.id.pageIndicator) ?: return
        container.removeAllViews()

        val density = resources.displayMetrics.density
        fun dp(value: Float): Int = (value * density).toInt()

        val accent = AccentColor.getAccentColorInt(this)
        val activeColor = accent
        val inactiveColor = ColorUtils.setAlphaComponent(accent, 88)

        pages.forEachIndexed { index, _ ->
            val dot = View(this)
            val size = if (index == activePosition) dp(18f) else dp(7f)
            val params = LinearLayout.LayoutParams(size, dp(7f)).apply {
                marginStart = dp(3f)
                marginEnd = dp(3f)
            }
            dot.layoutParams = params
            dot.background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = dp(4f).toFloat()
                setColor(if (index == activePosition) activeColor else inactiveColor)
            }
            dot.alpha = if (index == activePosition) 1f else 0.7f
            container.addView(dot)
        }
    }

    private fun applyFooterButtonAccent(btnSkip: MaterialButton, btnNext: MaterialButton) {
        val accent = AccentColor.getAccentColorInt(this)
        val onAccent = readableOnColor(accent)

        btnNext.backgroundTintList = ColorStateList.valueOf(accent)
        btnNext.setTextColor(onAccent)
        btnNext.iconTint = ColorStateList.valueOf(onAccent)

        btnSkip.strokeColor = ColorStateList.valueOf(accent)
        btnSkip.setTextColor(accent)
        btnSkip.iconTint = ColorStateList.valueOf(accent)
    }

    private fun readableOnColor(color: Int): Int {
        val black = ColorUtils.calculateContrast(Color.BLACK, color)
        val white = ColorUtils.calculateContrast(Color.WHITE, color)
        return if (black >= white) Color.BLACK else Color.WHITE
    }

    private fun isBatteryOptimizationIgnored(ctx: Context): Boolean {
        val pm: PowerManager = ctx.getSystemService() ?: return false
        return runCatching { pm.isIgnoringBatteryOptimizations(ctx.packageName) }.getOrDefault(false)
    }

    private fun areNotificationsAllowed(ctx: Context): Boolean {
        val channelEnabled = NotificationManagerCompat.from(ctx).areNotificationsEnabled()
        if (!channelEnabled) return false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ContextCompat.checkSelfPermission(
                ctx,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        }
        return true
    }

    private fun isNotificationBlockingSetupReady(ctx: Context): Boolean {
        return areNotificationsAllowed(ctx) && NotificationBlockStore.hasListenerAccess(ctx)
    }

    private fun isCorePermissionsReady(ctx: Context): Boolean {
        // Accessibility is the only hard requirement for the core blocker.
        // Usage Access stays recommended for fallback diagnostics, usage insights and some edge-case foreground detection, but users should not be blocked from finishing onboarding without it.
        return PermissionUtils.isAccessibilityServiceEnabled(ctx, SwitchlyAccessibilityService::class.java)
    }

    private fun ensureOnboardingProfile(ctx: Context): String {
        val current = ProfileStore.getCurrent(ctx)
        if (!current.isNullOrBlank()) return current

        val fallback = "Default"
        ProfileStore.addProfile(ctx, fallback)
        ProfileStore.setCurrent(ctx, fallback)
        return fallback
    }

    private fun selectedAppsForActiveProfile(ctx: Context): Set<String> {
        val profile = ensureOnboardingProfile(ctx)
        return ProfileStore.getSelectedForProfileMode(ctx, profile)
    }

    private fun hasPickedApps(ctx: Context): Boolean {
        return selectedAppsForActiveProfile(ctx).isNotEmpty()
    }

    private fun appLabelForPackage(ctx: Context, packageName: String): String {
        val pm = ctx.packageManager
        return runCatching {
            val ai = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
            } else {
                pm.getApplicationInfo(packageName, 0)
            }
            pm.getApplicationLabel(ai).toString()
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: packageName
    }

    private fun openFirstMissingPermissionSetting() {
        when {
            !PermissionUtils.isAccessibilityServiceEnabled(this, SwitchlyAccessibilityService::class.java) -> {
                AccessibilityDisclosure.openSettingsWithDisclosure(this, forceShow = true)
            }
            NotificationBlockStore.isEnabled(this) && !isNotificationBlockingSetupReady(this) -> {
                openNotificationSetupFromOnboarding()
            }
            isSchedulePermissionRelevant(this) && !isBatteryOptimizationIgnored(this) -> {
                requestIgnoreBatteryOptimizationFromOnboarding()
            }
            isSchedulePermissionRelevant(this) && !ExactAlarmPermissionSync.canScheduleExactAlarms(this) -> {
                openExactAlarmSetupFromOnboarding()
            }
            isSchedulePermissionRelevant(this) && !areTriggerPermissionsReady(this) -> {
                openTriggerPermissionSetupFromOnboarding()
            }
            isScanPermissionRelevant(this) && !isCameraPermissionReady(this) -> {
                requestCameraPermissionFromOnboarding()
            }
            !UsageStatsRepo.hasUsageAccess(this) -> {
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            }
            else -> {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = "package:$packageName".toUri()
                })
            }
        }
    }

    private fun openNotificationSetupFromOnboarding() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 9201)
            return
        }

        if (!NotificationBlockStore.hasListenerAccess(this)) {
            if (safeStart(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))) return
        }

        if (!safeStart(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            })) {
            safeStart(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = "package:$packageName".toUri()
            })
        }
    }

    private fun requestIgnoreBatteryOptimizationFromOnboarding() {
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = "package:$packageName".toUri()
        }
        if (safeStart(direct)) return

        if (!safeStart(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))) {
            safeStart(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = "package:$packageName".toUri()
            })
        }
    }

    private fun safeStart(intent: Intent): Boolean {
        return runCatching {
            startActivity(intent)
            true
        }.getOrDefault(false)
    }

    private fun hasVisitedControlSetup(ctx: Context): Boolean {
        return ctx.getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_CONTROLS_VISITED, false)
    }

    private fun shouldOfferKeySetup(ctx: Context): Boolean {
        return hasVisitedControlSetup(ctx) && (
            AutomationModeStore.isNfcAllowed(ctx) ||
                AutomationModeStore.isQrChannelAllowed(ctx) ||
                AutomationModeStore.isBarcodeChannelAllowed(ctx)
        )
    }

    private fun shouldOfferScheduleSetup(ctx: Context): Boolean {
        return hasVisitedControlSetup(ctx) && AutomationModeStore.isScheduleAllowed(ctx)
    }

    private fun hasAddedSchedule(ctx: Context): Boolean {
        return runCatching { ScheduleStore.getAll(ctx).isNotEmpty() }.getOrDefault(false)
    }

    private fun selectedKeyChannelLabels(ctx: Context): String {
        val labels = mutableListOf<String>()
        if (AutomationModeStore.isNfcAllowed(ctx)) labels += getString(R.string.pref_mode_nfc_title)
        if (AutomationModeStore.isQrChannelAllowed(ctx)) labels += getString(R.string.pref_mode_qr_title)
        if (AutomationModeStore.isBarcodeChannelAllowed(ctx)) labels += getString(R.string.pref_mode_barcode_title)
        return labels.joinToString(" / ")
    }

    private fun selectedControlModeLabel(ctx: Context): String {
        return when (AutomationModeStore.getMode(ctx)) {
            AutomationModeStore.Mode.SCHEDULE -> getString(R.string.pref_mode_schedule_title)
            AutomationModeStore.Mode.NFC -> getString(R.string.pref_mode_nfc_title)
            AutomationModeStore.Mode.QR -> getString(R.string.pref_mode_qr_title)
            AutomationModeStore.Mode.BARCODE -> getString(R.string.pref_mode_barcode_title)
            AutomationModeStore.Mode.MIXED -> getString(R.string.pref_mode_mixed_title)
        }
    }

    private fun isScanPermissionRelevant(ctx: Context): Boolean {
        return AutomationModeStore.isQrChannelAllowed(ctx) || AutomationModeStore.isBarcodeChannelAllowed(ctx)
    }

    private fun isSchedulePermissionRelevant(ctx: Context): Boolean {
        return AutomationModeStore.isScheduleAllowed(ctx)
    }

    private fun isCameraPermissionReady(ctx: Context): Boolean {
        return ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestCameraPermissionFromOnboarding() {
        requestPermissions(arrayOf(Manifest.permission.CAMERA), 9202)
    }

    private fun areTriggerPermissionsReady(ctx: Context): Boolean {
        val location = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val bluetooth = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        return location && bluetooth
    }

    private fun openTriggerPermissionSetupFromOnboarding() {
        val needsLocation = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
        if (needsLocation) {
            requestPermissions(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                9203
            )
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 9204)
            return
        }

        safeStart(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = "package:$packageName".toUri()
        })
    }

    private fun openExactAlarmSetupFromOnboarding() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                data = "package:$packageName".toUri()
            }
            if (safeStart(intent)) return
        }
        safeStart(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = "package:$packageName".toUri()
        })
    }

    private fun permissionOverviewDescription(ctx: Context): String {
        val parts = mutableListOf<String>()
        if (isSchedulePermissionRelevant(ctx)) parts += getString(R.string.pref_mode_schedule_title)
        if (AutomationModeStore.isNfcAllowed(ctx)) parts += getString(R.string.pref_mode_nfc_title)
        if (AutomationModeStore.isQrChannelAllowed(ctx)) parts += getString(R.string.pref_mode_qr_title)
        if (AutomationModeStore.isBarcodeChannelAllowed(ctx)) parts += getString(R.string.pref_mode_barcode_title)
        val channels = parts.takeIf { it.isNotEmpty() }?.joinToString(" / ") ?: selectedControlModeLabel(ctx)
        return getString(R.string.onb_permissions_hub_mode_desc, channels)
    }

    private fun buildPages(): List<OnboardingPage> {
        val result = mutableListOf<OnboardingPage>()

        result += OnboardingPage(
            iconRes = R.drawable.play_arrow_24,
            title = getString(R.string.onb_welcome_title),
            desc = getString(R.string.onb_welcome_desc),
            detailRows = listOf(
                getString(R.string.onb_core_point_lists),
                getString(R.string.onb_core_point_enabled_state),
                getString(R.string.onb_core_point_controls)
            ),
            level = OnboardingPage.Level.INFO
        )

        result += OnboardingPage(
            iconRes = R.drawable.apps_24,
            title = getString(R.string.onb_pick_title),
            desc = getString(R.string.onb_pick_desc_required),
            level = OnboardingPage.Level.REQUIRED,
            actionLabel = getString(R.string.onb_pick_action),
            action = { act ->
                val profile = ensureOnboardingProfile(act)
                act.startActivity(
                    Intent(act, AppPickerActivity::class.java)
                        .putExtra(EXTRA_FROM_ONBOARDING, true)
                        .putExtra(AppPickerActivity.EXTRA_PROFILE_NAME, profile)
                )
            },
            completionCheck = { ctx -> hasPickedApps(ctx) },
            completedLabel = getString(R.string.onb_apps_picked),
            keepActionEnabledWhenCompleted = true,
            requiredMessage = getString(R.string.onb_required_pick_apps)
        )

        result += OnboardingPage(
            iconRes = R.drawable.tune_24,
            title = getString(R.string.onb_controls_title),
            desc = getString(R.string.onb_controls_desc),
            detailRows = listOf(
                getString(R.string.onb_controls_point_enable_disable),
                getString(R.string.onb_controls_point_lock_edits)
            ),
            level = OnboardingPage.Level.REQUIRED,
            actionLabel = getString(R.string.onb_controls_action),
            action = { act ->
                act.getSharedPreferences(PREFS, MODE_PRIVATE).edit {
                    putBoolean(KEY_CONTROLS_VISITED, true)
                }
                act.startActivity(
                    Intent(act, ToggleOptionsActivity::class.java)
                        .putExtra(ToggleOptionsActivity.EXTRA_VIEW_SECTION, ToggleOptionsActivity.SECTION_BLOCKING)
                )
            },
            completionCheck = { ctx -> hasVisitedControlSetup(ctx) },
            completedLabel = getString(R.string.onb_controls_selected),
            keepActionEnabledWhenCompleted = true,
            requiredMessage = getString(R.string.onb_required_controls)
        )

        if (shouldOfferKeySetup(this)) {
            val channels = selectedKeyChannelLabels(this)
            result += OnboardingPage(
                iconRes = R.drawable.qr_code_24,
                title = getString(R.string.onb_keys_title_selected),
                desc = getString(R.string.onb_keys_desc_selected, channels),
                detailRows = listOf(
                    getString(R.string.onb_keys_point_actions),
                    getString(R.string.onb_keys_point_manage)
                ),
                level = OnboardingPage.Level.OPTIONAL,
                actionLabel = getString(R.string.onb_keys_action),
                action = { act ->
                    act.startActivity(
                        Intent(act, ManageKeysActivity::class.java)
                            .putExtra(ManageKeysActivity.EXTRA_FILTER_FROM_ONBOARDING, true)
                            .putExtra(ManageKeysActivity.EXTRA_SHOW_NFC, AutomationModeStore.isNfcAllowed(act))
                            .putExtra(ManageKeysActivity.EXTRA_SHOW_QR, AutomationModeStore.isQrChannelAllowed(act))
                            .putExtra(ManageKeysActivity.EXTRA_SHOW_BARCODE, AutomationModeStore.isBarcodeChannelAllowed(act))
                    )
                }
            )
        }

        if (shouldOfferScheduleSetup(this)) {
            result += OnboardingPage(
                iconRes = R.drawable.schedule_24,
                title = getString(R.string.onb_schedule_title_selected),
                desc = getString(R.string.onb_schedule_desc_selected),
                detailRows = listOf(
                    getString(R.string.onb_schedule_point_actions),
                    getString(R.string.onb_schedule_point_permissions)
                ),
                level = OnboardingPage.Level.RECOMMENDED,
                actionLabel = getString(R.string.onb_schedule_action),
                action = { act ->
                    act.startActivity(Intent(act, SchedulesActivity::class.java))
                },
                completionCheck = { ctx -> hasAddedSchedule(ctx) },
                completedLabel = getString(R.string.onb_schedule_added),
                keepActionEnabledWhenCompleted = true
            )
        }

        result += OnboardingPage(
            type = OnboardingPage.Type.PERMISSION_OVERVIEW,
            iconRes = R.drawable.security_24,
            title = getString(R.string.onb_permissions_hub_title),
            desc = permissionOverviewDescription(this),
            level = OnboardingPage.Level.REQUIRED,
            actionLabel = getString(R.string.onb_permissions_action),
            action = { act -> (act as? OnboardingActivity)?.openFirstMissingPermissionSetting() },
            completionCheck = { ctx -> isCorePermissionsReady(ctx) },
            requiredMessage = getString(R.string.onb_required_permissions_hub)
        )

        result += OnboardingPage(
            type = OnboardingPage.Type.REVIEW,
            iconRes = R.drawable.play_arrow_24,
            title = getString(R.string.onb_start_title),
            desc = getString(R.string.onb_start_desc_clean),
            level = OnboardingPage.Level.INFO,
            actionLabel = getString(R.string.onb_start_test_action),
            action = { act ->
                SwitchModeStore.setEnabled(act, true)
                Toast.makeText(act, R.string.onb_start_test_toast, Toast.LENGTH_LONG).show()
            }
        )

        return result
    }

    private fun markDone() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit {
            putBoolean(KEY_DONE, true)
            putInt(KEY_VERSION, ONBOARDING_VERSION)
        }
    }

    private fun finishToMain() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        finish()
    }
}
