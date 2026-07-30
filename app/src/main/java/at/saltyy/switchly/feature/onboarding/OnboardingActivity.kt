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
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import at.saltyy.switchly.data.prefs.AutomationModeStore
import at.saltyy.switchly.data.prefs.ExactAlarmPermissionSync
import at.saltyy.switchly.data.prefs.NotificationBlockStore
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.graphics.ColorUtils
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.doOnLayout
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import at.saltyy.switchly.R
import at.saltyy.switchly.blocking.SwitchlyAccessibilityService
import at.saltyy.switchly.data.onboarding.OnboardingPage
import at.saltyy.switchly.data.prefs.ProfileStore
import at.saltyy.switchly.data.prefs.ScheduleStore
import at.saltyy.switchly.data.prefs.SwitchModeStore
import at.saltyy.switchly.feature.onboarding.adapters.OnboardingPagerAdapter
import at.saltyy.switchly.feature.faq.FaqActivity
import at.saltyy.switchly.feature.settings.AppLockSettingsActivity
import at.saltyy.switchly.feature.settings.BlockingFeaturesActivity
import at.saltyy.switchly.feature.settings.HomeModeDialogHelper
import at.saltyy.switchly.feature.picker.AppPickerActivity
import at.saltyy.switchly.feature.schedule.SchedulesActivity
import at.saltyy.switchly.feature.settings.AccessibilityDisclosure
import at.saltyy.switchly.feature.settings.PermissionsActivity
import at.saltyy.switchly.feature.settings.ToggleOptionsActivity
import at.saltyy.switchly.feature.tools.ManageKeysActivity
import at.saltyy.switchly.feature.support.SupportActivity
import at.saltyy.switchly.feature.usage.IgnoredUsageAppsActivity
import at.saltyy.switchly.feature.usage.UsageStatsRepo
import at.saltyy.switchly.theme.AccentColor
import at.saltyy.switchly.ui.MainActivity
import at.saltyy.switchly.ui.ThemeUtils
import at.saltyy.switchly.ui.dialog.showAccented
import at.saltyy.switchly.util.PackageManagerApiCompat
import at.saltyy.switchly.util.PermissionSetupChecks
import at.saltyy.switchly.util.PermissionUtils
import at.saltyy.switchly.util.NfcLaunchAccessCompat
import at.saltyy.switchly.util.getIntCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.color.MaterialColors
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
         * v220: rework onboarding flow to multiple pages + add new required steps for notification blocking and schedule permissions.
         * v221: refresh setup for Launch via NFC guidance and 2.2.1 beta permission/backup polish.
         * v222: compact spacing, shared permission checks, optional in-flow usage preview and icon-based review flow.
         */
        const val ONBOARDING_VERSION = 222
    }

    private var forced: Boolean = false
    private lateinit var pager: ViewPager2
    private lateinit var pages: List<OnboardingPage>
    private lateinit var pagerAdapter: OnboardingPagerAdapter
    private lateinit var btnNext: MaterialButton
    private lateinit var btnSkip: MaterialButton
    private lateinit var btnOptionalSetup: MaterialButton
    private lateinit var buttonSpacer: View

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (::pager.isInitialized && ::pagerAdapter.isInitialized) {
            pagerAdapter.notifyItemChanged(pager.currentItem)
        }
    }

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
        WindowCompat.setDecorFitsSystemWindows(window, false)

        pager = findViewById(R.id.viewPager)
        btnNext = findViewById(R.id.btn_next)
        btnSkip = findViewById(R.id.btn_skip)
        btnOptionalSetup = findViewById(R.id.btn_optional_setup)
        buttonSpacer = findViewById(R.id.onboardingButtonSpacer)

        applySystemBarInsets()
        applyFooterButtonAccent(btnSkip, btnNext)

        pages = buildPages()
        pagerAdapter = OnboardingPagerAdapter(
            activity = this,
            pages = pages
        )
        pager.adapter = pagerAdapter
        installRequiredPageSwipeGuard()
        onBackPressedDispatcher.addCallback(this) {
            val position = pager.currentItem
            if (position > 0) {
                pager.setCurrentItem(position - 1, true)
            } else {
                finishToMain(skipOnboardingGateOnce = true)
            }
        }

        updateButtons(pager.currentItem)

        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateButtons(position)
            }
        })

        btnSkip.setOnClickListener {
            val currentPage = pages.getOrNull(pager.currentItem)
            if (currentPage?.type == OnboardingPage.Type.OPTIONAL_SETUP) {
                finishOnboarding()
            } else {
                // Leaving required setup early intentionally does not mark onboarding as completed,
                // so it can be continued on the next launch. Forced tutorial views simply return to Home.
                finishToMain(skipOnboardingGateOnce = true)
            }
        }

        btnOptionalSetup.setOnClickListener {
            val optionalPage = pages.indexOfFirst { it.type == OnboardingPage.Type.OPTIONAL_SETUP }
            if (optionalPage >= 0) {
                pager.setCurrentItem(optionalPage, true)
            }
        }

        btnNext.setOnClickListener {
            val pos = pager.currentItem
            val page = pages.getOrNull(pos)

            // The review list is editable.
            // Keep the core requirement intact if apps were removed there before the user starts Switchly.
            if (page?.type == OnboardingPage.Type.REVIEW && !hasPickedApps(this)) {
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.onb_pick_title)
                    .setMessage(R.string.onb_required_pick_apps)
                    .setPositiveButton(R.string.onb_pick_action) { _, _ -> openOnboardingAppPicker() }
                    .setNegativeButton(R.string.cancel, null)
                    .showAccented()
                return@setOnClickListener
            }

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

            val finishesOnboarding = page?.type == OnboardingPage.Type.REVIEW ||
                (page?.type == OnboardingPage.Type.OPTIONAL_SETUP && pos == lastOptionalSetupPageIndex()) ||
                pos == pages.lastIndex

            if (!finishesOnboarding) {
                pager.currentItem = pos + 1
            } else {
                finishOnboarding()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        findViewById<MaterialButton>(R.id.btn_skip)?.let { skip ->
            findViewById<MaterialButton>(R.id.btn_next)?.let { next ->
                applyFooterButtonAccent(skip, next)
            }
        }

        if (!::pager.isInitialized || !::pages.isInitialized) {
            return
        }

        if (UsageStatsRepo.hasUsageAccess(this)) {
            OnboardingUsagePreviewRenderer.prefetch(this)
        }
        rebuildPagesKeepingPosition()
        val pos = pager.currentItem.coerceIn(0, pages.lastIndex)
        val page = pages.getOrNull(pos) ?: return
        if (page.level == OnboardingPage.Level.REQUIRED && page.completionCheck?.invoke(this) == true) {
            if (pos < pages.lastIndex && shouldAutoAdvanceAfterReturn(page)) {
                pager.post { pager.currentItem = pos + 1 }
            }
        }
    }

    private fun finishOnboarding() {
        if (!forced) {
            SwitchModeStore.setEnabled(this, true)
            Toast.makeText(this, R.string.onb_start_test_toast, Toast.LENGTH_LONG).show()
            markDone()
        }
        finishToMain(skipOnboardingGateOnce = true)
    }

    private fun lastOptionalSetupPageIndex(): Int =
        pages.indexOfLast { it.type == OnboardingPage.Type.OPTIONAL_SETUP }

    private fun shouldAutoAdvanceAfterReturn(page: OnboardingPage): Boolean {
        if (page.type == OnboardingPage.Type.APP_SELECTION) {
            // Keep the user on this page after the picker so the selected count and the safety guidance remain visible before continuing.
            return false
        }
        if (page.type == OnboardingPage.Type.USAGE_PERMISSION) {
            // Usage Access is optional.
            // After returning from Settings, keep this permission card visible so the user can choose Next and deliberately enter the report page.
            return false
        }
        if (page.type == OnboardingPage.Type.PERMISSION_OVERVIEW) {
            // Keep the overview visible until every displayed setup row is ready.
            // This prevents users from missing Usage Access or an optional mode-specific permission after return.
            return areVisiblePermissionRowsReady(this)
        }
        return true
    }

    private fun areVisiblePermissionRowsReady(ctx: Context): Boolean {
        if (!isCorePermissionsReady(ctx)) {
            return false
        }

        if (NotificationBlockStore.isEnabled(ctx) && !isNotificationBlockingSetupReady(ctx)) {
            return false
        }

        if (isSchedulePermissionRelevant(ctx)) {
            if (!PermissionSetupChecks.batteryOptimizationReady(ctx)) {
                return false
            }
            if (!ExactAlarmPermissionSync.canScheduleExactAlarms(ctx)) {
                return false
            }
            if (!areTriggerPermissionsReady(ctx)) {
                return false
            }
        }

        if (AutomationModeStore.isNfcAllowed(ctx) && !NfcLaunchAccessCompat.isLikelyAllowed(ctx)) {
            return false
        }

        if (isScanPermissionRelevant(ctx) && !PermissionSetupChecks.cameraReady(ctx)) {
            return false
        }

        return true
    }

    private fun rebuildPagesKeepingPosition() {
        if (!::pager.isInitialized || !::pages.isInitialized || pages.isEmpty()) return

        // App selection, permissions and optional setup can all change while another screen is open.
        // Rebuild the lightweight page model and keep the user on the same logical step.
        val oldPos = pager.currentItem.coerceIn(0, pages.lastIndex)
        val oldPage = pages.getOrNull(oldPos)

        pages = buildPages()
        pagerAdapter = OnboardingPagerAdapter(
            activity = this,
            pages = pages
        )
        pager.adapter = pagerAdapter

        val matchingPage = oldPage?.let { old ->
            pages.indexOfFirst { page -> page.type == old.type && page.title == old.title }
        } ?: -1
        val target = matchingPage.takeIf { it >= 0 } ?: oldPos.coerceIn(0, pages.lastIndex)

        pager.setCurrentItem(target, false)
        updateButtons(target)
    }

    private fun installRequiredPageSwipeGuard() {
        val recyclerView = pager.getChildAt(0) as? RecyclerView ?: return
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop

        recyclerView.addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
            private var startX = 0f
            private var startPage = 0
            private var blockingForwardSwipe = false

            override fun onInterceptTouchEvent(rv: RecyclerView, event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = event.x
                        startPage = pager.currentItem
                        blockingForwardSwipe = false
                    }

                    MotionEvent.ACTION_MOVE -> {
                        val deltaX = event.x - startX
                        val isRtl = pager.layoutDirection == View.LAYOUT_DIRECTION_RTL
                        val movingForward = if (isRtl) {
                            deltaX > touchSlop
                        } else {
                            deltaX < -touchSlop
                        }

                        if (movingForward && isRequiredPageIncomplete(startPage)) {
                            blockingForwardSwipe = true
                            rv.stopScroll()
                            pager.setCurrentItem(startPage, false)
                            return true
                        }
                    }

                    MotionEvent.ACTION_UP,
                    MotionEvent.ACTION_CANCEL -> blockingForwardSwipe = false
                }

                return blockingForwardSwipe
            }

            override fun onTouchEvent(rv: RecyclerView, event: MotionEvent) {
                if (event.actionMasked == MotionEvent.ACTION_UP ||
                    event.actionMasked == MotionEvent.ACTION_CANCEL
                ) {
                    rv.stopScroll()
                    pager.setCurrentItem(startPage, false)
                    blockingForwardSwipe = false
                }
            }
        })
    }

    private fun isRequiredPageIncomplete(position: Int): Boolean {
        val page = pages.getOrNull(position) ?: return false
        return page.level == OnboardingPage.Level.REQUIRED &&
            page.completionCheck?.invoke(this) == false
    }

    private fun updateButtons(pos: Int) {
        val page = pages.getOrNull(pos)
        val isReviewPage = page?.type == OnboardingPage.Type.REVIEW
        val isOptionalPage = page?.type == OnboardingPage.Type.OPTIONAL_SETUP
        val isLastOptionalPage = isOptionalPage && pos == lastOptionalSetupPageIndex()
        val finishesOnboarding = isReviewPage || isLastOptionalPage || pos == pages.lastIndex

        btnNext.text = when {
            !finishesOnboarding -> getString(R.string.onb_next)
            forced -> getString(R.string.onb_done)
            else -> getString(R.string.onb_start_button)
        }
        btnSkip.text = getString(R.string.onb_skip)
        btnSkip.visibility = when {
            isReviewPage || isLastOptionalPage || pos == pages.lastIndex -> View.GONE
            else -> View.VISIBLE
        }
        buttonSpacer.visibility = btnSkip.visibility
        btnOptionalSetup.visibility = if (isReviewPage) View.VISIBLE else View.GONE

        btnNext.isEnabled = when {
            page == null -> false
            page.type == OnboardingPage.Type.REVIEW -> hasPickedApps(this)
            page.level == OnboardingPage.Level.REQUIRED && page.completionCheck != null -> {
                page.completionCheck.invoke(this)
            }
            else -> true
        }

        applyFooterButtonAccent(btnSkip, btnNext)
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
        val isCustom = AccentColor.getOption(this) == AccentColor.Option.CUSTOM
        val surface = ContextCompat.getColor(this, R.color.switchly_card_bg)
        val outline = ContextCompat.getColor(this, R.color.switchly_card_stroke)
        val onSurface = MaterialColors.getColor(btnSkip, com.google.android.material.R.attr.colorOnSurface)
        val disabledBackground = ColorUtils.blendARGB(surface, onSurface, 0.10f)
        val disabledForeground = ColorUtils.setAlphaComponent(onSurface, 105)

        // Enabled = selected accent. Missing required setup = an actually neutral disabled state.
        // Using a state list also prevents CUSTOM's late runtime retint pass from flattening both states back to one accent color.
        btnNext.backgroundTintList = ColorStateList(
            arrayOf(
                intArrayOf(android.R.attr.state_enabled),
                intArrayOf(-android.R.attr.state_enabled)
            ),
            intArrayOf(accent, disabledBackground)
        )
        btnNext.setTextColor(
            ColorStateList(
                arrayOf(
                    intArrayOf(android.R.attr.state_enabled),
                    intArrayOf(-android.R.attr.state_enabled)
                ),
                intArrayOf(onAccent, disabledForeground)
            )
        )
        btnNext.iconTint = btnNext.textColors

        if (isCustom) {
            // Preset accent themes already render the outlined secondary action correctly.
            // CUSTOM has no dedicated theme resource, so give "Set up later" a neutral tonal surface.
            btnSkip.backgroundTintList = ColorStateList.valueOf(surface)
            btnSkip.strokeColor = ColorStateList.valueOf(ColorUtils.blendARGB(outline, accent, 0.58f))
        } else {
            btnSkip.strokeColor = ColorStateList.valueOf(accent)
        }
        btnSkip.setTextColor(accent)
        btnSkip.iconTint = ColorStateList.valueOf(accent)

        findViewById<MaterialButton>(R.id.btn_optional_setup)?.apply {
            backgroundTintList = ColorStateList.valueOf(surface)
            strokeColor = ColorStateList.valueOf(ColorUtils.blendARGB(outline, accent, 0.72f))
            setTextColor(accent)
            iconTint = ColorStateList.valueOf(accent)
        }

        findViewById<ImageView>(R.id.nextArrow)?.apply {
            isEnabled = btnNext.isEnabled
            imageTintList = ColorStateList.valueOf(
                if (btnNext.isEnabled) onAccent else disabledForeground
            )
            alpha = if (btnNext.isEnabled) 0.9f else 0.55f
        }
    }

    private fun readableOnColor(color: Int): Int {
        val black = ColorUtils.calculateContrast(Color.BLACK, color)
        val white = ColorUtils.calculateContrast(Color.WHITE, color)
        return if (black >= white) {
            Color.BLACK
        } else {
            Color.WHITE
        }
    }

    private fun isNotificationBlockingSetupReady(ctx: Context): Boolean {
        return PermissionSetupChecks.notificationsReady(ctx, requireListenerAccess = true)
    }

    // Checks the two permissions required for reliable blocking and usage-aware insights.
    private fun isCorePermissionsReady(ctx: Context): Boolean {
        return PermissionUtils.isAccessibilityServiceEnabled(
            ctx,
            SwitchlyAccessibilityService::class.java
        ) && UsageStatsRepo.hasUsageAccess(ctx)
    }

    private fun ensureOnboardingProfile(ctx: Context): String {
        val current = ProfileStore.getCurrent(ctx)
        if (!current.isNullOrBlank()) {
            return current
        }

        val fallback = "Default"
        ProfileStore.addProfile(ctx, fallback)
        ProfileStore.setCurrent(ctx, fallback)
        return fallback
    }

    private fun openOnboardingAppPicker() {
        val profile = ensureOnboardingProfile(this)
        startActivity(
            Intent(this, AppPickerActivity::class.java)
                .putExtra(EXTRA_FROM_ONBOARDING, true)
                .putExtra(AppPickerActivity.EXTRA_PROFILE_NAME, profile)
        )
    }

    private fun selectedAppsForActiveProfile(ctx: Context): Set<String> {
        val profile = ensureOnboardingProfile(ctx)
        return ProfileStore.getSelectedForProfileMode(ctx, profile)
    }

    private fun hasPickedApps(ctx: Context): Boolean {
        return selectedAppsForActiveProfile(ctx).isNotEmpty()
    }

    private fun openFirstMissingPermissionSetting() {
        when {
            !PermissionUtils.isAccessibilityServiceEnabled(this, SwitchlyAccessibilityService::class.java) -> {
                AccessibilityDisclosure.openSettingsWithDisclosure(this, forceShow = true)
            }
            !UsageStatsRepo.hasUsageAccess(this) -> {
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            }
            NotificationBlockStore.isEnabled(this) && !isNotificationBlockingSetupReady(this) -> {
                openNotificationSetupFromOnboarding()
            }
            isSchedulePermissionRelevant(this) && !PermissionSetupChecks.batteryOptimizationReady(this) -> {
                requestIgnoreBatteryOptimizationFromOnboarding()
            }
            isSchedulePermissionRelevant(this) && !ExactAlarmPermissionSync.canScheduleExactAlarms(this) -> {
                openExactAlarmSetupFromOnboarding()
            }
            isSchedulePermissionRelevant(this) && !areTriggerPermissionsReady(this) -> {
                openTriggerPermissionSetupFromOnboarding()
            }
            isScanPermissionRelevant(this) && !PermissionSetupChecks.cameraReady(this) -> {
                requestCameraPermissionFromOnboarding()
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
            permissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
            return
        }

        if (!NotificationBlockStore.hasListenerAccess(this)) {
            if (safeStart(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))) {
                return
            }
        }

        if (!safeStart(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            })) {
            safeStart(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = "package:$packageName".toUri()
            })
        }
    }

    @SuppressLint("BatteryLife")
    private fun requestIgnoreBatteryOptimizationFromOnboarding() {
        if (safeStart(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = "package:$packageName".toUri()
            })) {
            return
        }
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

    private fun requestCameraPermissionFromOnboarding() {
        permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA))
    }

    private fun areTriggerPermissionsReady(ctx: Context): Boolean {
        return PermissionSetupChecks.scheduleTriggerPermissionsReady(ctx)
    }

    private fun openTriggerPermissionSetupFromOnboarding() {
        startActivity(Intent(this, PermissionsActivity::class.java).apply {
            putExtra(PermissionsActivity.EXTRA_FROM_ONBOARDING, true)
            putExtra(PermissionsActivity.EXTRA_FOCUS_SECTION, PermissionsActivity.SECTION_TRIGGERS)
        })
    }

    private fun openExactAlarmSetupFromOnboarding() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                data = "package:$packageName".toUri()
            }
            if (safeStart(intent)) {
                return
            }
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

    private fun applySystemBarInsets() {
        val density = resources.displayMetrics.density
        fun dp(value: Float): Int = (value * density).toInt()

        val initialPagerLeft = pager.paddingLeft
        val initialPagerTop = pager.paddingTop
        val initialPagerRight = pager.paddingRight
        val initialPagerBottom = pager.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(pager) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.updatePadding(
                left = initialPagerLeft + bars.left,
                top = initialPagerTop + bars.top,
                right = initialPagerRight + bars.right
            )
            insets
        }

        val bottomBar = findViewById<View>(R.id.bottomBar)
        val initialLayoutParams = bottomBar.layoutParams as ViewGroup.MarginLayoutParams
        val initialLeft = initialLayoutParams.leftMargin
        val initialRight = initialLayoutParams.rightMargin
        val initialBottom = initialLayoutParams.bottomMargin

        fun updatePagerFooterSpace() {
            val params = bottomBar.layoutParams as? ViewGroup.MarginLayoutParams ?: return
            pager.updatePadding(
                bottom = initialPagerBottom + bottomBar.height + params.bottomMargin + dp(8f)
            )
        }

        ViewCompat.setOnApplyWindowInsetsListener(bottomBar) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.navigationBars() or
                    WindowInsetsCompat.Type.systemGestures() or
                    WindowInsetsCompat.Type.displayCutout()
            )
            view.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                leftMargin = initialLeft + bars.left
                rightMargin = initialRight + bars.right
                bottomMargin = initialBottom + bars.bottom
            }
            view.post { updatePagerFooterSpace() }
            insets
        }
        bottomBar.doOnLayout { updatePagerFooterSpace() }
        bottomBar.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updatePagerFooterSpace()
        }

        ViewCompat.requestApplyInsets(pager)
        ViewCompat.requestApplyInsets(bottomBar)
    }

    private fun buildPages(): List<OnboardingPage> {
        val result = mutableListOf<OnboardingPage>()
        val selectedAppCount = selectedAppsForActiveProfile(this).size

        result += OnboardingPage(
            iconRes = R.drawable.play_arrow_24,
            title = getString(R.string.onb_welcome_title),
            desc = getString(R.string.onb_welcome_desc),
            detailRows = listOf(
                getString(R.string.onb_core_point_choose),
                getString(R.string.onb_core_point_control)
            ),
            level = OnboardingPage.Level.START
        )

        result += OnboardingPage(
            type = OnboardingPage.Type.USAGE_PERMISSION,
            iconRes = R.drawable.bar_chart_24,
            title = getString(R.string.onb_usage_report_title),
            desc = getString(R.string.onb_usage_report_desc),
            level = OnboardingPage.Level.OPTIONAL,
            completionCheck = { ctx -> UsageStatsRepo.hasUsageAccess(ctx) }
        )

        // The early report remains optional. Core setup asks for Usage Access again later because Switchly needs it for normal operation.
        if (UsageStatsRepo.hasUsageAccess(this)) {
            result += OnboardingPage(
                type = OnboardingPage.Type.USAGE_PREVIEW,
                iconRes = R.drawable.bar_chart_24,
                title = getString(R.string.onb_usage_preview_title),
                desc = getString(R.string.onb_usage_preview_desc),
                level = OnboardingPage.Level.INFO
            )
        }

        result += OnboardingPage(
            type = OnboardingPage.Type.APP_SELECTION,
            iconRes = R.drawable.apps_24,
            title = getString(R.string.onb_pick_title),
            desc = getString(R.string.onb_pick_desc_required),
            detailRows = listOf(
                getString(R.string.onb_pick_point_goal),
                getString(R.string.onb_pick_point_system_apps)
            ),
            level = OnboardingPage.Level.REQUIRED,
            actionLabel = getString(R.string.onb_pick_action),
            action = { act ->
                (act as? OnboardingActivity)?.openOnboardingAppPicker()
            },
            completionCheck = { ctx -> hasPickedApps(ctx) },
            completedLabel = resources.getQuantityString(
                R.plurals.onb_apps_picked_count,
                selectedAppCount,
                selectedAppCount
            ),
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
            type = OnboardingPage.Type.HOME_CUSTOMIZATION,
            iconRes = R.drawable.dashboard_24,
            title = getString(R.string.onb_home_customize_title),
            desc = getString(R.string.onb_home_customize_desc),
            level = OnboardingPage.Level.OPTIONAL
        )

        result += OnboardingPage(
            type = OnboardingPage.Type.REVIEW,
            iconRes = R.drawable.play_arrow_24,
            title = getString(R.string.onb_start_title),
            desc = getString(R.string.onb_start_desc_clean),
            level = OnboardingPage.Level.INFO
        )

        result += OnboardingPage(
            type = OnboardingPage.Type.OPTIONAL_SETUP,
            iconRes = R.drawable.visibility_off_24,
            title = getString(R.string.onb_optional_hidden_apps_title),
            desc = getString(R.string.onb_optional_hidden_apps_desc),
            level = OnboardingPage.Level.OPTIONAL,
            optionalPreview = OnboardingPage.OptionalPreview.HIDDEN_APPS,
            actionLabel = getString(R.string.onb_optional_manage_hidden_action),
            action = { act -> act.startActivity(IgnoredUsageAppsActivity.intent(act)) }
        )

        result += OnboardingPage(
            type = OnboardingPage.Type.OPTIONAL_SETUP,
            iconRes = R.drawable.dashboard_24,
            title = getString(R.string.onb_optional_home_modes_title),
            desc = getString(R.string.onb_optional_home_modes_desc),
            level = OnboardingPage.Level.OPTIONAL,
            optionalPreview = OnboardingPage.OptionalPreview.HOME_MODES,
            actionLabel = getString(R.string.onb_optional_home_settings_action),
            action = { act -> HomeModeDialogHelper.showHomeLayoutModeDialog(act) }
        )

        result += OnboardingPage(
            type = OnboardingPage.Type.OPTIONAL_SETUP,
            iconRes = R.drawable.tune_24,
            title = getString(R.string.onb_optional_display_title),
            desc = getString(R.string.onb_optional_display_desc),
            level = OnboardingPage.Level.OPTIONAL,
            optionalPreview = OnboardingPage.OptionalPreview.DISPLAY,
            actionLabel = getString(R.string.onb_optional_display_more_action),
            action = { act ->
                act.startActivity(
                    Intent(act, ToggleOptionsActivity::class.java)
                        .putExtra(
                            ToggleOptionsActivity.EXTRA_VIEW_SECTION,
                            ToggleOptionsActivity.SECTION_DISPLAY
                        )
                )
            }
        )

        result += OnboardingPage(
            type = OnboardingPage.Type.OPTIONAL_SETUP,
            iconRes = R.drawable.lock_24,
            title = getString(R.string.onb_optional_app_lock_title),
            desc = getString(R.string.onb_optional_app_lock_desc),
            level = OnboardingPage.Level.OPTIONAL,
            optionalPreview = OnboardingPage.OptionalPreview.APP_LOCK,
            actionLabel = getString(R.string.onb_optional_protection_settings_action),
            action = { act -> act.startActivity(Intent(act, AppLockSettingsActivity::class.java)) }
        )

        result += OnboardingPage(
            type = OnboardingPage.Type.OPTIONAL_SETUP,
            iconRes = R.drawable.security_24,
            title = getString(R.string.onb_optional_feature_access_title),
            desc = getString(R.string.onb_optional_feature_access_desc),
            level = OnboardingPage.Level.OPTIONAL,
            optionalPreview = OnboardingPage.OptionalPreview.FEATURE_ACCESS,
            actionLabel = getString(R.string.onb_optional_access_more_action),
            action = { act -> act.startActivity(Intent(act, BlockingFeaturesActivity::class.java)) }
        )

        result += OnboardingPage(
            type = OnboardingPage.Type.OPTIONAL_SETUP,
            iconRes = R.drawable.help_24,
            title = getString(R.string.onb_optional_faq_title),
            desc = getString(R.string.onb_optional_faq_desc),
            level = OnboardingPage.Level.OPTIONAL,
            optionalPreview = OnboardingPage.OptionalPreview.FAQ,
            actionLabel = getString(R.string.onb_optional_faq_open_action),
            action = { act -> act.startActivity(Intent(act, FaqActivity::class.java)) }
        )

        result += OnboardingPage(
            type = OnboardingPage.Type.OPTIONAL_SETUP,
            iconRes = R.drawable.info_24,
            title = getString(R.string.onb_optional_support_title),
            desc = getString(R.string.onb_optional_support_desc),
            level = OnboardingPage.Level.OPTIONAL,
            optionalPreview = OnboardingPage.OptionalPreview.SUPPORT,
            actionLabel = getString(R.string.onb_optional_support_open_action),
            action = { act -> act.startActivity(Intent(act, SupportActivity::class.java)) }
        )

        return result
    }

    private data class ReviewAppEntry(
        val packageName: String,
        val label: String,
        val icon: android.graphics.drawable.Drawable
    )

    private fun reviewAppEntries(profile: String): List<ReviewAppEntry> {
        val pm = packageManager
        return ProfileStore.getSelectedForProfileMode(this, profile)
            .map { packageName ->
                runCatching {
                    val info = PackageManagerApiCompat.getApplicationInfo(
                        packageManager = pm,
                        packageName = packageName,
                    )
                    ReviewAppEntry(
                        packageName = packageName,
                        label = pm.getApplicationLabel(info).toString().ifBlank { packageName },
                        icon = pm.getApplicationIcon(info)
                    )
                }.getOrElse {
                    ReviewAppEntry(
                        packageName = packageName,
                        label = packageName.substringAfterLast('.').replaceFirstChar { ch -> ch.uppercase() },
                        icon = pm.defaultActivityIcon
                    )
                }
            }
            .sortedBy { it.label.lowercase() }
    }

    internal fun showReviewAppsDialog() {
        val profile = ensureOnboardingProfile(this)
        val apps = reviewAppEntries(profile)
        if (apps.isEmpty()) {
            openOnboardingAppPicker()
            return
        }

        val density = resources.displayMetrics.density
        fun dp(value: Float): Int = (value * density).toInt()
        val accent = AccentColor.getAccentColorInt(this)
        val root = findViewById<View>(android.R.id.content)
        val onSurface = MaterialColors.getColor(root, com.google.android.material.R.attr.colorOnSurface)
        val surface = ContextCompat.getColor(this, R.color.switchly_card_bg)
        val outline = ContextCompat.getColor(this, R.color.switchly_card_stroke)
        val pendingSelection = apps.mapTo(linkedSetOf()) { it.packageName }

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            setPadding(dp(4f), dp(2f), dp(4f), dp(6f))
        }
        val list = LinearLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            orientation = LinearLayout.VERTICAL
        }
        scroll.addView(list)

        apps.forEach { app ->
            val card = MaterialCardView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dp(5f)
                    bottomMargin = dp(5f)
                }
                minimumHeight = dp(72f)
                radius = dp(16f).toFloat()
                strokeWidth = dp(1.5f)
                cardElevation = 0f
                isClickable = true
                isFocusable = true
            }

            val row = LinearLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                minimumHeight = dp(72f)
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(dp(14f), dp(10f), dp(10f), dp(10f))
            }
            row.addView(ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(44f), dp(44f)).apply {
                    marginEnd = dp(13f)
                }
                setImageDrawable(app.icon)
                scaleType = ImageView.ScaleType.FIT_CENTER
                contentDescription = null
            })
            row.addView(TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                text = app.label
                textSize = 15f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(onSurface)
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                includeFontPadding = false
            })
            val check = MaterialCheckBox(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(48f), dp(48f)).apply {
                    marginStart = dp(8f)
                }
                isClickable = false
                isFocusable = false
                buttonTintList = ColorStateList(
                    arrayOf(
                        intArrayOf(android.R.attr.state_checked),
                        intArrayOf()
                    ),
                    intArrayOf(
                        accent,
                        ColorUtils.setAlphaComponent(onSurface, 115)
                    )
                )
                contentDescription = null
            }
            row.addView(check)
            card.addView(row)

            fun renderSelection() {
                val selected = app.packageName in pendingSelection
                check.isChecked = selected
                card.strokeColor = if (selected) {
                    ColorUtils.blendARGB(outline, accent, 0.72f)
                } else {
                    outline
                }
                card.setCardBackgroundColor(
                    if (selected) ColorUtils.blendARGB(surface, accent, 0.07f) else surface
                )
                card.alpha = if (selected) 1f else 0.78f
                card.contentDescription = getString(
                    if (selected) R.string.onb_review_app_selected_content_description
                    else R.string.onb_review_app_unselected_content_description,
                    app.label
                )
            }

            card.setOnClickListener {
                if (app.packageName in pendingSelection) {
                    if (pendingSelection.size <= 1) {
                        Toast.makeText(this, R.string.onb_keep_one_app, Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    pendingSelection.remove(app.packageName)
                } else {
                    pendingSelection.add(app.packageName)
                }
                renderSelection()
            }
            renderSelection()
            list.addView(card)
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.onb_review_apps_dialog_title)
            .setMessage(R.string.onb_review_apps_dialog_desc)
            .setView(scroll)
            .setNegativeButton(R.string.cancel, null)
            .setNeutralButton(R.string.onb_review_apps_change) { _, _ -> openOnboardingAppPicker() }
            .setPositiveButton(R.string.ok, null)
            .create()

        dialog.setOnDismissListener { rebuildPagesKeepingPosition() }
        dialog.show()
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            if (pendingSelection.isEmpty()) {
                Toast.makeText(this, R.string.onb_keep_one_app, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            ProfileStore.setSelectedForProfileMode(this, profile, pendingSelection)
            dialog.dismiss()
        }
        at.saltyy.switchly.theme.CustomAccentApplier.applyToDialog(dialog)
    }

    private fun markDone() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit(commit = true) {
            putBoolean(KEY_DONE, true)
            putInt(KEY_VERSION, ONBOARDING_VERSION)
        }
        MainActivity.queueBottomNavTour(this)
    }

    private fun finishToMain(skipOnboardingGateOnce: Boolean = false) {
        startActivity(
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_SKIP_ONBOARDING_GATE_ONCE, skipOnboardingGateOnce)
                .putExtra(EXTRA_FROM_ONBOARDING, true)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        finish()
    }
}
