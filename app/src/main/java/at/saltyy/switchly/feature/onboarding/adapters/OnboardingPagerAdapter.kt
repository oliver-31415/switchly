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

package at.saltyy.switchly.feature.onboarding.adapters

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.graphics.ColorUtils
import androidx.core.text.HtmlCompat
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.recyclerview.widget.RecyclerView
import at.saltyy.switchly.R
import at.saltyy.switchly.blocking.SwitchlyAccessibilityService
import at.saltyy.switchly.data.onboarding.OnboardingPage
import at.saltyy.switchly.data.prefs.AutomationModeStore
import at.saltyy.switchly.data.prefs.ExactAlarmPermissionSync
import at.saltyy.switchly.data.prefs.IgnoredUsageAppsStore
import at.saltyy.switchly.data.prefs.NotificationBlockStore
import at.saltyy.switchly.data.prefs.ProfileStore
import at.saltyy.switchly.feature.onboarding.OnboardingUsagePreviewRenderer
import at.saltyy.switchly.feature.faq.FaqActivity
import at.saltyy.switchly.feature.settings.AccessibilityDisclosure
import at.saltyy.switchly.feature.settings.PermissionsActivity
import at.saltyy.switchly.feature.settings.HomeModeDialogHelper
import at.saltyy.switchly.feature.settings.ToggleOptionsActivity
import at.saltyy.switchly.feature.usage.IgnoredUsageAppsActivity
import at.saltyy.switchly.feature.usage.UsageStatsRepo
import at.saltyy.switchly.security.AppLockStore
import at.saltyy.switchly.theme.AccentColor
import at.saltyy.switchly.util.PackageManagerApiCompat
import at.saltyy.switchly.util.PermissionSetupChecks
import at.saltyy.switchly.util.PermissionUtils
import at.saltyy.switchly.util.NfcLaunchAccessCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.card.MaterialCardView
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.switchmaterial.SwitchMaterial

class OnboardingPagerAdapter(
    private val activity: Activity,
    private val pages: List<OnboardingPage>
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_onboarding_page, parent, false)
        return StandardVH(v)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        (holder as StandardVH).bind(activity, pages[position])
    }

    override fun getItemCount(): Int = pages.size

    class StandardVH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val pageContent = itemView.findViewById<LinearLayout>(R.id.pageContent)
        private val iconCard = itemView.findViewById<MaterialCardView>(R.id.iconCard)
        private val icon = itemView.findViewById<ImageView>(R.id.icon)
        private val title = itemView.findViewById<TextView>(R.id.title)
        private val desc = itemView.findViewById<TextView>(R.id.desc)
        private val badge = itemView.findViewById<TextView>(R.id.badge)
        private val detailsContainer = itemView.findViewById<LinearLayout>(R.id.detailsContainer)
        private val btn = itemView.findViewById<MaterialButton>(R.id.btn_action)

        fun bind(activity: Activity, page: OnboardingPage) {
            title.text = page.title

            // Allow lightweight formatting (bold, line breaks, bullet points) in onboarding copy.
            desc.text = HtmlCompat.fromHtml(page.desc, HtmlCompat.FROM_HTML_MODE_COMPACT)

            // Keep onboarding copy centered and consistent across all steps.
            desc.gravity = android.view.Gravity.CENTER
            desc.textAlignment = View.TEXT_ALIGNMENT_CENTER
            desc.maxWidth = Int.MAX_VALUE
            desc.setPaddingRelative(0, 0, 0, 0)
            desc.setLineSpacing(0f, 1.0f)
            desc.includeFontPadding = true

            val showHero = page.type != OnboardingPage.Type.USAGE_PREVIEW
            iconCard.isVisible = showHero
            if (showHero && page.iconRes != null) {
                icon.isVisible = true
                icon.setImageResource(page.iconRes)
            } else {
                icon.isVisible = false
            }

            badge.text = when (page.level) {
                OnboardingPage.Level.START -> activity.getString(R.string.onb_badge_start)
                OnboardingPage.Level.REQUIRED -> activity.getString(R.string.onb_badge_required)
                OnboardingPage.Level.RECOMMENDED -> activity.getString(R.string.onb_badge_recommended)
                OnboardingPage.Level.OPTIONAL -> activity.getString(R.string.onb_badge_optional)
                OnboardingPage.Level.INFO -> ""
            }
            badge.isVisible = page.level != OnboardingPage.Level.INFO

            val completed = page.completionCheck?.invoke(activity) == true
            val accent = AccentColor.getAccentColorInt(activity)
            val onAccent = readableOnColor(accent)
            val heroIconTint = onboardingHeroIconTint(activity)

            // Keep onboarding hero icon card in sync with selected accent (including custom color).
            iconCard.setCardBackgroundColor(accent)
            icon.imageTintList = ColorStateList.valueOf(heroIconTint)
            icon.setColorFilter(heroIconTint)

            placeActionButton(page)
            renderDetails(activity, page, accent)
            applyPageSpacing(page)

            if (
                page.type == OnboardingPage.Type.PERMISSION_OVERVIEW ||
                page.type == OnboardingPage.Type.USAGE_PERMISSION ||
                page.type == OnboardingPage.Type.USAGE_PREVIEW
            ) {
                btn.isVisible = false
                btn.isEnabled = false
                btn.setOnClickListener(null)
                return
            }

            val hasAction = page.action != null && !page.actionLabel.isNullOrBlank()
            btn.updateLayoutParams<LinearLayout.LayoutParams> {
                width = if (page.type == OnboardingPage.Type.REVIEW) {
                    ViewGroup.LayoutParams.MATCH_PARENT
                } else {
                    ViewGroup.LayoutParams.WRAP_CONTENT
                }
            }
            if (page.type == OnboardingPage.Type.REVIEW && hasAction) {
                btn.setIconResource(R.drawable.keyboard_arrow_right_24)
                btn.iconGravity = MaterialButton.ICON_GRAVITY_TEXT_END
                btn.iconPadding = (8 * itemView.resources.displayMetrics.density).toInt()
            } else {
                btn.icon = null
            }
            val completedButStillEditable = completed && hasAction && page.keepActionEnabledWhenCompleted
            btn.isVisible = hasAction || completed

            if (completedButStillEditable) {
                btn.text = page.completedLabel ?: page.actionLabel
                btn.isEnabled = true
                btn.alpha = 1f
                btn.backgroundTintList = ColorStateList.valueOf(accent)
                btn.setTextColor(onAccent)
                btn.setOnClickListener { runPageAction(activity, page, accent) }
            } else if (completed) {
                btn.text = page.completedLabel ?: activity.getString(R.string.onb_granted)
                btn.isEnabled = false
                btn.alpha = 0.85f
                btn.backgroundTintList = ColorStateList.valueOf(accent)
                btn.setTextColor(onAccent)
                btn.setOnClickListener(null)
            } else if (hasAction) {
                btn.text = page.actionLabel
                btn.isEnabled = true
                btn.alpha = 1f
                btn.backgroundTintList = ColorStateList.valueOf(accent)
                btn.setTextColor(onAccent)
                btn.setOnClickListener { runPageAction(activity, page, accent) }
            } else {
                btn.setOnClickListener(null)
                btn.isEnabled = false
            }
        }

        private fun runPageAction(activity: Activity, page: OnboardingPage, accent: Int) {
            if (page.optionalPreview == OnboardingPage.OptionalPreview.HOME_MODES) {
                HomeModeDialogHelper.showHomeLayoutModeDialog(activity) {
                    detailsContainer.removeAllViews()
                    addCompactHomeModeGrid(activity, accent)
                }
            } else {
                page.action?.invoke(activity)
            }
        }

        private fun placeActionButton(page: OnboardingPage) {
            val detailsIndex = pageContent.indexOfChild(detailsContainer)
            val buttonIndex = pageContent.indexOfChild(btn)
            if (detailsIndex < 0 || buttonIndex < 0) return

            val shouldBeBeforeDetails =
                page.type == OnboardingPage.Type.HOME_CUSTOMIZATION
            if (shouldBeBeforeDetails && buttonIndex > detailsIndex) {
                pageContent.removeView(btn)
                pageContent.addView(btn, pageContent.indexOfChild(detailsContainer))
            } else if (!shouldBeBeforeDetails && buttonIndex < detailsIndex) {
                pageContent.removeView(btn)
                pageContent.addView(btn, pageContent.indexOfChild(detailsContainer) + 1)
            }
        }

        private fun applyPageSpacing(page: OnboardingPage) {
            val density = itemView.resources.displayMetrics.density
            fun dp(value: Float): Int = (value * density).toInt()

            val isPermissionOverview = page.type == OnboardingPage.Type.PERMISSION_OVERVIEW
            val isUsagePreview = page.type == OnboardingPage.Type.USAGE_PREVIEW
            val isHomeModes = page.optionalPreview == OnboardingPage.OptionalPreview.HOME_MODES

            fun applyCenteredScrollSpacing() {
                val viewportHeight = itemView.height.takeIf { it > 0 }
                    ?: itemView.resources.displayMetrics.heightPixels
                val sharedHeroTop = (viewportHeight * 0.075f).toInt()
                    .coerceIn(dp(40f), dp(68f))
                val dynamicTop = if (isUsagePreview) dp(18f) else sharedHeroTop
                val dynamicBottom = if (isPermissionOverview) dp(32f) else dp(24f)
                pageContent.setPadding(dp(24f), dynamicTop, dp(24f), dynamicBottom)
                pageContent.gravity = android.view.Gravity.CENTER_HORIZONTAL

                // Every standard onboarding page uses the same hero and icon dimensions so the visual anchor does not jump vertically while swiping between steps.
                iconCard.updateLayoutParams<ViewGroup.LayoutParams> {
                    width = dp(144f)
                    height = dp(144f)
                }
                icon.updateLayoutParams<ViewGroup.LayoutParams> {
                    width = dp(84f)
                    height = dp(84f)
                }
            }

            applyCenteredScrollSpacing()
            itemView.doOnLayout { applyCenteredScrollSpacing() }

            val lp = detailsContainer.layoutParams as? ViewGroup.MarginLayoutParams
            if (lp != null) {
                lp.topMargin = when {
                    isUsagePreview -> dp(8f)
                    isPermissionOverview -> dp(18f)
                    isHomeModes -> dp(10f)
                    else -> dp(16f)
                }
                detailsContainer.layoutParams = lp
            }
            detailsContainer.setPadding(0, if (isPermissionOverview) dp(2f) else 0, 0, 0)
        }

        private fun renderDetails(activity: Activity, page: OnboardingPage, accent: Int) {
            detailsContainer.removeAllViews()
            detailsContainer.isVisible = false

            if (page.type == OnboardingPage.Type.USAGE_PERMISSION) {
                detailsContainer.isVisible = true
                val usageAccessReady = UsageStatsRepo.hasUsageAccess(activity)
                addUnifiedOnboardingRow(
                    activity = activity,
                    title = activity.getString(R.string.onb_usage_report_private_title),
                    iconRes = R.drawable.lock_24,
                    info = activity.getString(R.string.onb_usage_report_private_desc),
                    status = activity.getString(R.string.onb_usage_report_private_status),
                    highlighted = false,
                    accent = accent,
                    clickable = false,
                    onClick = null,
                    fixedHeightDp = 100f
                )
                addPermissionOptionRow(
                    activity = activity,
                    title = activity.getString(R.string.onb_perm_usage_title),
                    iconRes = R.drawable.bar_chart_24,
                    info = activity.getString(R.string.onb_perm_usage_desc),
                    status = activity.getString(
                        if (usageAccessReady) {
                            R.string.onb_usage_permission_status_ready
                        } else {
                            R.string.onb_usage_permission_status_optional
                        }
                    ),
                    checked = usageAccessReady,
                    accent = accent,
                    onClick = { activity.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
                )
                return
            }

            if (page.type == OnboardingPage.Type.USAGE_PREVIEW) {
                detailsContainer.isVisible = true
                OnboardingUsagePreviewRenderer.render(
                    activity = activity,
                    container = detailsContainer,
                    accent = accent
                )
                return
            }

            if (page.type == OnboardingPage.Type.APP_SELECTION) {
                renderAppSelectionDetails(activity, page, accent)
                return
            }

            if (page.type == OnboardingPage.Type.HOME_CUSTOMIZATION) {
                renderHomeCustomizationDetails(activity, accent)
                return
            }

            if (page.type == OnboardingPage.Type.REVIEW) {
                renderReviewDetails(activity, page, accent)
                return
            }

            if (page.type == OnboardingPage.Type.OPTIONAL_SETUP && page.optionalPreview != null) {
                renderOptionalSetupDetails(activity, page, accent)
                return
            }

            if (page.type == OnboardingPage.Type.PERMISSION_OVERVIEW) {
                detailsContainer.isVisible = true

                val scheduleSelected = AutomationModeStore.isScheduleAllowed(activity)
                val nfcSelected = AutomationModeStore.isNfcAllowed(activity)
                val scanSelected = AutomationModeStore.isQrChannelAllowed(activity) ||
                    AutomationModeStore.isBarcodeChannelAllowed(activity)
                val notificationBlockingSelected = NotificationBlockStore.isEnabled(activity)

                // Core permissions are always visible and required, regardless of selected modes.
                addPermissionRow(
                    activity,
                    title = activity.getString(R.string.onb_perm_accessibility_title),
                    iconRes = R.drawable.security_24,
                    subtitle = activity.getString(R.string.onb_perm_accessibility_desc),
                    checked = PermissionUtils.isAccessibilityServiceEnabled(activity, SwitchlyAccessibilityService::class.java),
                    required = true,
                    accent = accent,
                    onClick = { AccessibilityDisclosure.openSettingsWithDisclosure(activity, forceShow = true) }
                )

                addPermissionRow(
                    activity,
                    title = activity.getString(R.string.onb_perm_usage_title),
                    iconRes = R.drawable.bar_chart_24,
                    subtitle = activity.getString(R.string.onb_perm_usage_desc),
                    checked = UsageStatsRepo.hasUsageAccess(activity),
                    required = true,
                    accent = accent,
                    onClick = { activity.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
                )

                if (notificationBlockingSelected) {
                    addPermissionRow(
                        activity,
                        title = activity.getString(R.string.onb_perm_notifications_title),
                        iconRes = R.drawable.notifications_24,
                        subtitle = activity.getString(R.string.onb_perm_notifications_desc),
                        checked = PermissionSetupChecks.notificationsReady(
                            activity,
                            requireListenerAccess = true
                        ),
                        required = false,
                        accent = accent,
                        onClick = { openNotificationsSetup(activity) }
                    )
                }

                if (scheduleSelected) {
                    addPermissionRow(
                        activity,
                        title = activity.getString(R.string.onb_perm_battery_title),
                        iconRes = R.drawable.battery_24,
                        subtitle = activity.getString(R.string.onb_perm_battery_desc),
                        checked = PermissionSetupChecks.batteryOptimizationReady(activity),
                        required = false,
                        accent = accent,
                        onClick = { openBatterySetup(activity) }
                    )
                    addPermissionRow(
                        activity,
                        title = activity.getString(R.string.onb_perm_exact_alarm_title),
                        iconRes = R.drawable.alarm_24,
                        subtitle = activity.getString(R.string.onb_perm_exact_alarm_desc),
                        checked = exactAlarmsReady(activity),
                        required = false,
                        accent = accent,
                        onClick = { openExactAlarmSetup(activity) }
                    )
                    addPermissionRow(
                        activity,
                        title = activity.getString(R.string.onb_perm_triggers_title),
                        iconRes = R.drawable.location_on_24,
                        subtitle = activity.getString(R.string.onb_perm_triggers_desc),
                        checked = PermissionSetupChecks.scheduleTriggerPermissionsReady(activity),
                        required = false,
                        accent = accent,
                        onClick = { openTriggerPermissionSetup(activity) }
                    )
                }

                if (nfcSelected) {
                    val nfcLaunchReady = NfcLaunchAccessCompat.isLikelyAllowed(activity)
                    addPermissionOptionRow(
                        activity = activity,
                        title = activity.getString(R.string.onb_perm_nfc_title),
                        iconRes = R.drawable.nfc_24,
                        info = activity.getString(R.string.onb_perm_nfc_desc),
                        status = if (nfcLaunchReady) {
                            activity.getString(R.string.onb_permission_status_ready)
                        } else {
                            activity.getString(R.string.onb_permission_status_manual)
                        },
                        checked = nfcLaunchReady,
                        accent = accent,
                        onClick = { openNfcLaunchSetup(activity) }
                    )
                }

                if (scanSelected) {
                    addPermissionRow(
                        activity,
                        title = activity.getString(R.string.onb_perm_camera_title),
                        iconRes = R.drawable.qr_code_24,
                        subtitle = activity.getString(R.string.onb_perm_camera_desc),
                        checked = PermissionSetupChecks.cameraReady(activity),
                        required = false,
                        accent = accent,
                        onClick = { openCameraSetup(activity) }
                    )
                }

                return
            }

            if (page.detailRows.isNotEmpty()) {
                detailsContainer.isVisible = true
                page.detailRows.forEachIndexed { index, row -> addDetailRow(activity, row, accent, iconForDetailRow(page, index)) }
            }
        }

        private fun renderOptionalSetupDetails(
            activity: Activity,
            page: OnboardingPage,
            accent: Int
        ) {
            detailsContainer.removeAllViews()
            detailsContainer.isVisible = true

            when (page.optionalPreview) {
                OnboardingPage.OptionalPreview.HIDDEN_APPS -> {
                    val usageHidden = IgnoredUsageAppsStore.getIgnoredPackages(activity).size
                    val pickerHidden = IgnoredUsageAppsStore.getAppPickerHiddenPackages(activity).size
                    val openUsageHiddenApps: () -> Unit = {
                        activity.startActivity(IgnoredUsageAppsActivity.intent(activity, showAppPickers = false))
                    }
                    val openAppPickerHiddenApps: () -> Unit = {
                        activity.startActivity(IgnoredUsageAppsActivity.intent(activity, showAppPickers = true))
                    }

                    addUnifiedOnboardingRow(
                        activity = activity,
                        title = activity.getString(R.string.onb_optional_hidden_usage_row_title),
                        iconRes = R.drawable.bar_chart_24,
                        info = activity.getString(R.string.onb_optional_hidden_usage_row_desc),
                        status = activity.getString(R.string.onb_optional_hidden_count, usageHidden),
                        highlighted = usageHidden > 0,
                        accent = accent,
                        clickable = true,
                        onClick = openUsageHiddenApps,
                        fixedHeightDp = 96f
                    )
                    addUnifiedOnboardingRow(
                        activity = activity,
                        title = activity.getString(R.string.onb_optional_hidden_picker_row_title),
                        iconRes = R.drawable.visibility_off_24,
                        info = activity.getString(R.string.onb_optional_hidden_picker_row_desc),
                        status = activity.getString(R.string.onb_optional_hidden_count, pickerHidden),
                        highlighted = pickerHidden > 0,
                        accent = accent,
                        clickable = true,
                        onClick = openAppPickerHiddenApps,
                        fixedHeightDp = 96f
                    )
                }

                OnboardingPage.OptionalPreview.HOME_MODES -> {
                    addCompactHomeModeGrid(activity, accent)
                }

                OnboardingPage.OptionalPreview.DISPLAY -> {
                    val openTiles: () -> Unit = {
                        activity.startActivity(
                            Intent(activity, ToggleOptionsActivity::class.java)
                                .putExtra(
                                    ToggleOptionsActivity.EXTRA_VIEW_SECTION,
                                    ToggleOptionsActivity.SECTION_DISPLAY
                                )
                                .putExtra(
                                    ToggleOptionsActivity.EXTRA_DISPLAY_TARGET,
                                    ToggleOptionsActivity.DISPLAY_TARGET_TILES
                                )
                        )
                    }
                    val openWidgets: () -> Unit = {
                        activity.startActivity(
                            Intent(activity, ToggleOptionsActivity::class.java)
                                .putExtra(
                                    ToggleOptionsActivity.EXTRA_VIEW_SECTION,
                                    ToggleOptionsActivity.SECTION_DISPLAY
                                )
                                .putExtra(
                                    ToggleOptionsActivity.EXTRA_DISPLAY_TARGET,
                                    ToggleOptionsActivity.DISPLAY_TARGET_WIDGETS
                                )
                        )
                    }

                    addOptionRow(
                        activity = activity,
                        iconRes = R.drawable.tune_24,
                        title = activity.getString(R.string.onb_optional_display_tiles_title),
                        subtitle = activity.getString(R.string.onb_optional_display_tiles_desc),
                        leadingColored = false,
                        accent = accent,
                        clickable = true,
                        onClick = openTiles,
                        fixedHeightDp = 92f
                    )
                    addOptionRow(
                        activity = activity,
                        iconRes = R.drawable.widget_apps_24,
                        title = activity.getString(R.string.onb_optional_display_widgets_title),
                        subtitle = activity.getString(R.string.onb_optional_display_widgets_desc),
                        leadingColored = false,
                        accent = accent,
                        clickable = true,
                        onClick = openWidgets,
                        fixedHeightDp = 92f
                    )
                }

                OnboardingPage.OptionalPreview.APP_LOCK -> {
                    val appLockEnabled = AppLockStore.isEnabled(activity)
                    val uninstallProtectionEnabled = AppLockStore.isStrictProtectionEnabled(activity)
                    val openAppLock: () -> Unit = {
                        page.action?.invoke(activity)
                        Unit
                    }

                    addUnifiedOnboardingRow(
                        activity = activity,
                        title = activity.getString(R.string.onb_optional_app_lock_row_title),
                        iconRes = R.drawable.lock_24,
                        info = activity.getString(R.string.onb_optional_app_lock_row_desc),
                        status = activity.getString(if (appLockEnabled) R.string.state_enabled else R.string.state_disabled),
                        highlighted = appLockEnabled,
                        accent = accent,
                        clickable = true,
                        onClick = openAppLock,
                        fixedHeightDp = 96f
                    )
                    addUnifiedOnboardingRow(
                        activity = activity,
                        title = activity.getString(R.string.onb_optional_uninstall_row_title),
                        iconRes = R.drawable.security_24,
                        info = activity.getString(R.string.onb_optional_uninstall_row_desc),
                        status = activity.getString(if (uninstallProtectionEnabled) R.string.state_enabled else R.string.state_disabled),
                        highlighted = uninstallProtectionEnabled,
                        accent = accent,
                        clickable = true,
                        onClick = openAppLock,
                        fixedHeightDp = 96f
                    )
                }

                OnboardingPage.OptionalPreview.FEATURE_ACCESS -> {
                    addToggleOptionRow(
                        activity = activity,
                        title = activity.getString(R.string.pref_mixed_allow_app_picking_title),
                        subtitle = activity.getString(R.string.pref_mixed_allow_app_picking_summary),
                        iconRes = R.drawable.apps_24,
                        checked = AutomationModeStore.isAppPickerAllowedWhileEnabled(activity),
                        accent = accent,
                        onCheckedChanged = { enabled ->
                            AutomationModeStore.setMixedAllowAppPicking(activity, enabled)
                        }
                    )
                    addToggleOptionRow(
                        activity = activity,
                        title = activity.getString(R.string.pref_mixed_allow_profile_switching_title),
                        subtitle = activity.getString(R.string.pref_mixed_allow_profile_switching_summary),
                        iconRes = R.drawable.account_box_24,
                        checked = AutomationModeStore.isProfileSwitchingAllowedWhileEnabled(activity),
                        accent = accent,
                        onCheckedChanged = { enabled ->
                            AutomationModeStore.setMixedAllowProfileSwitching(activity, enabled)
                        }
                    )
                }

                OnboardingPage.OptionalPreview.FAQ -> {
                    val openBackgroundChecklist: () -> Unit = {
                        activity.startActivity(
                            FaqActivity.intent(
                                context = activity,
                                category = FaqActivity.CATEGORY_BACKGROUND_ACCESS,
                                questionResId = R.string.faq_q_background_access_checklist
                            )
                        )
                    }
                    val openDeviceSpecificSteps: () -> Unit = {
                        activity.startActivity(
                            FaqActivity.intent(
                                context = activity,
                                category = FaqActivity.CATEGORY_BACKGROUND_ACCESS,
                                questionResId = R.string.faq_q_dontkillmyapp
                            )
                        )
                    }
                    addOptionRow(
                        activity = activity,
                        iconRes = R.drawable.battery_24,
                        title = activity.getString(R.string.onb_optional_faq_background_title),
                        subtitle = activity.getString(R.string.onb_optional_faq_background_desc),
                        leadingColored = false,
                        accent = accent,
                        clickable = true,
                        onClick = openBackgroundChecklist,
                        fixedHeightDp = 92f
                    )
                    addOptionRow(
                        activity = activity,
                        iconRes = R.drawable.language_24,
                        title = activity.getString(R.string.onb_optional_faq_device_steps_title),
                        subtitle = activity.getString(R.string.onb_optional_faq_device_steps_desc),
                        leadingColored = false,
                        accent = accent,
                        clickable = true,
                        onClick = openDeviceSpecificSteps,
                        fixedHeightDp = 92f
                    )
                }

                OnboardingPage.OptionalPreview.SUPPORT -> {
                    val openSupport: () -> Unit = {
                        page.action?.invoke(activity)
                        Unit
                    }
                    addOptionRow(
                        activity = activity,
                        iconRes = R.drawable.info_24,
                        title = activity.getString(R.string.onb_optional_support_report_title),
                        subtitle = activity.getString(R.string.onb_optional_support_report_desc),
                        leadingColored = false,
                        accent = accent,
                        clickable = true,
                        onClick = openSupport,
                        fixedHeightDp = 92f
                    )
                    addOptionRow(
                        activity = activity,
                        iconRes = R.drawable.mail_24,
                        title = activity.getString(R.string.onb_optional_support_feedback_title),
                        subtitle = activity.getString(R.string.onb_optional_support_feedback_desc),
                        leadingColored = false,
                        accent = accent,
                        clickable = true,
                        onClick = openSupport,
                        fixedHeightDp = 92f
                    )
                }

                null -> detailsContainer.isVisible = false
            }
        }

        private fun addCompactHomeModeGrid(activity: Activity, accent: Int) {
            val density = itemView.resources.displayMetrics.density
            fun dp(value: Float): Int = (value * density).toInt()

            val surface = ContextCompat.getColor(activity, R.color.switchly_card_bg)
            val outline = ContextCompat.getColor(activity, R.color.switchly_card_stroke)
            val onSurface = MaterialColors.getColor(itemView, com.google.android.material.R.attr.colorOnSurface)
            val choices = HomeModeDialogHelper.homeLayoutModeChoices(activity)

            choices.chunked(2).forEachIndexed { rowIndex, pair ->
                val row = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                }

                pair.forEachIndexed { index, choice ->
                    val selected = choice.selected
                    val card = MaterialCardView(activity).apply {
                        layoutParams = LinearLayout.LayoutParams(0, dp(110f), 1f).apply {
                            topMargin = if (rowIndex == 0) dp(8f) else dp(10f)
                            if (index == 0) marginEnd = dp(5f) else marginStart = dp(5f)
                        }
                        radius = dp(17f).toFloat()
                        cardElevation = 0f
                        strokeWidth = dp(if (selected) 2f else 1f)
                        strokeColor = if (selected) accent else outline
                        setCardBackgroundColor(
                            if (selected) ColorUtils.setAlphaComponent(accent, 22) else surface
                        )
                        isClickable = true
                        isFocusable = true
                    }

                    val content = LinearLayout(activity).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = android.view.Gravity.CENTER
                        setPadding(dp(10f), dp(10f), dp(10f), dp(8f))
                    }
                    content.addView(ImageView(activity).apply {
                        setImageResource(choice.iconRes)
                        imageTintList = ColorStateList.valueOf(accent)
                        contentDescription = null
                    }, LinearLayout.LayoutParams(dp(30f), dp(30f)))
                    content.addView(TextView(activity).apply {
                        text = choice.title
                        textSize = 13.5f
                        typeface = android.graphics.Typeface.DEFAULT_BOLD
                        setTextColor(onSurface)
                        gravity = android.view.Gravity.CENTER
                        maxLines = 1
                        setPadding(0, dp(7f), 0, 0)
                    })
                    content.addView(TextView(activity).apply {
                        text = activity.getString(
                            if (selected) R.string.onb_home_mode_selected else R.string.onb_optional_home_mode_tap
                        )
                        textSize = 11.5f
                        setTextColor(if (selected) accent else ColorUtils.setAlphaComponent(onSurface, 150))
                        gravity = android.view.Gravity.CENTER
                        maxLines = 1
                        setPadding(0, dp(4f), 0, 0)
                    })

                    card.addView(content)
                    card.setOnClickListener {
                        HomeModeDialogHelper.setHomeLayoutMode(activity, choice.mode) {
                            detailsContainer.removeAllViews()
                            addCompactHomeModeGrid(activity, accent)
                        }
                    }
                    row.addView(card)
                }

                if (pair.size == 1) {
                    row.addView(View(activity), LinearLayout.LayoutParams(0, dp(104f), 1f).apply {
                        topMargin = if (rowIndex == 0) dp(8f) else dp(10f)
                        marginStart = dp(5f)
                    })
                }
                detailsContainer.addView(row)
            }
        }

        private fun addToggleOptionRow(
            activity: Activity,
            title: String,
            subtitle: String,
            iconRes: Int,
            checked: Boolean,
            accent: Int,
            onCheckedChanged: (Boolean) -> Unit
        ) {
            val density = itemView.resources.displayMetrics.density
            fun dp(value: Float): Int = (value * density).toInt()

            val surface = ContextCompat.getColor(activity, R.color.switchly_card_bg)
            val outline = ContextCompat.getColor(activity, R.color.switchly_card_stroke)
            val onSurface = MaterialColors.getColor(itemView, com.google.android.material.R.attr.colorOnSurface)

            val card = MaterialCardView(activity).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(8f) }
                minimumHeight = dp(92f)
                radius = dp(16f).toFloat()
                cardElevation = 0f
                isClickable = true
                isFocusable = true
            }

            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                minimumHeight = dp(92f)
                setPadding(dp(16f), dp(10f), dp(10f), dp(10f))
            }

            row.addView(ImageView(activity).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(14f).toFloat()
                    setColor(ColorUtils.setAlphaComponent(accent, 18))
                }
                setPadding(dp(8f), dp(8f), dp(8f), dp(8f))
                setImageResource(iconRes)
                imageTintList = ColorStateList.valueOf(accent)
                contentDescription = null
            }, LinearLayout.LayoutParams(dp(38f), dp(38f)).apply { marginEnd = dp(13f) })

            val texts = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            texts.addView(TextView(activity).apply {
                text = title
                textSize = 14.8f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(onSurface)
                maxLines = 2
                includeFontPadding = false
            })
            texts.addView(TextView(activity).apply {
                text = subtitle
                textSize = 12.3f
                setTextColor(ColorUtils.setAlphaComponent(onSurface, 175))
                setPadding(0, dp(4f), 0, 0)
                maxLines = 2
                includeFontPadding = false
            })
            row.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

            val toggle = SwitchMaterial(activity).apply {
                isChecked = checked
                contentDescription = title
                thumbTintList = ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(accent, ColorUtils.setAlphaComponent(onSurface, 150))
                )
                trackTintList = ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(ColorUtils.setAlphaComponent(accent, 105), ColorUtils.setAlphaComponent(onSurface, 45))
                )
            }
            row.addView(toggle, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(10f) })

            fun refreshCard(enabled: Boolean) {
                card.strokeWidth = dp(if (enabled) 2f else 1f)
                card.strokeColor = if (enabled) accent else outline
                card.setCardBackgroundColor(
                    if (enabled) ColorUtils.setAlphaComponent(accent, 18) else surface
                )
            }
            refreshCard(checked)

            toggle.setOnCheckedChangeListener { _, enabled ->
                onCheckedChanged(enabled)
                refreshCard(enabled)
            }
            card.setOnClickListener { toggle.toggle() }
            card.addView(row)
            detailsContainer.addView(card)
        }

        private data class SelectedAppEntry(
            val packageName: String,
            val label: String,
            val icon: Drawable
        )

        private fun renderAppSelectionDetails(
            activity: Activity,
            page: OnboardingPage,
            accent: Int
        ) {
            detailsContainer.removeAllViews()
            detailsContainer.isVisible = page.detailRows.isNotEmpty()
            page.detailRows.forEachIndexed { index, row ->
                addDetailRow(activity, row, accent, iconForDetailRow(page, index))
            }
        }

        private fun renderHomeCustomizationDetails(activity: Activity, accent: Int) {
            detailsContainer.removeAllViews()
            detailsContainer.isVisible = true
            HomeModeDialogHelper.homeLayoutModeChoices(activity).forEach { choice ->
                addHomeModeRow(activity, choice, accent)
            }
        }

        private fun addHomeModeRow(
            activity: Activity,
            choice: HomeModeDialogHelper.HomeLayoutModeChoice,
            accent: Int,
        ) {
            val density = itemView.resources.displayMetrics.density
            fun dp(value: Float): Int = (value * density).toInt()

            val surface = ContextCompat.getColor(activity, R.color.switchly_card_bg)
            val onSurface = MaterialColors.getColor(
                itemView,
                com.google.android.material.R.attr.colorOnSurface
            )
            val outline = ContextCompat.getColor(activity, R.color.switchly_card_stroke)
            val card = MaterialCardView(activity).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(8f) }
                radius = dp(16f).toFloat()
                cardElevation = 0f
                strokeWidth = dp(if (choice.selected) 2f else 1f)
                strokeColor = if (choice.selected) accent else outline
                setCardBackgroundColor(
                    if (choice.selected) ColorUtils.setAlphaComponent(accent, 0x12) else surface
                )
                isClickable = true
                isFocusable = true
            }
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                minimumHeight = dp(82f)
                setPadding(dp(14f), dp(10f), dp(14f), dp(10f))
            }
            row.addView(ImageView(activity).apply {
                setImageResource(choice.iconRes)
                imageTintList = ColorStateList.valueOf(accent)
                contentDescription = null
            }, LinearLayout.LayoutParams(dp(28f), dp(28f)))

            val textColumn = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14f), 0, dp(8f), 0)
            }
            textColumn.addView(TextView(activity).apply {
                text = choice.title
                setTextColor(onSurface)
                textSize = 15f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            })
            textColumn.addView(TextView(activity).apply {
                text = choice.summary
                setTextColor(ColorUtils.setAlphaComponent(onSurface, 0xA8))
                textSize = 12.5f
                maxLines = 2
                setPadding(0, dp(2f), 0, 0)
            })
            if (choice.selected) {
                textColumn.addView(TextView(activity).apply {
                    text = activity.getString(
                        if (choice.mode == ToggleOptionsActivity.HOME_MODE_CUSTOM) {
                            R.string.onb_home_custom_selected_hint
                        } else {
                            R.string.onb_home_mode_selected
                        }
                    )
                    setTextColor(accent)
                    textSize = 12f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    setPadding(0, dp(4f), 0, 0)
                })
            }
            row.addView(
                textColumn,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )

            if (choice.selected) {
                row.addView(ImageView(activity).apply {
                    setImageResource(R.drawable.keyboard_arrow_right_24)
                    imageTintList = ColorStateList.valueOf(accent)
                    contentDescription = null
                    isVisible = choice.mode == ToggleOptionsActivity.HOME_MODE_CUSTOM
                }, LinearLayout.LayoutParams(dp(28f), dp(28f)))
            }

            card.addView(row)
            card.setOnClickListener {
                HomeModeDialogHelper.setHomeLayoutMode(activity, choice.mode) {
                    renderHomeCustomizationDetails(activity, accent)
                }
                if (choice.mode == ToggleOptionsActivity.HOME_MODE_CUSTOM) {
                    HomeModeDialogHelper.showCustomizeHomeDialog(activity) {
                        renderHomeCustomizationDetails(activity, accent)
                    }
                }
            }
            detailsContainer.addView(card)
        }

        private fun renderReviewDetails(activity: Activity, page: OnboardingPage, accent: Int) {
            detailsContainer.removeAllViews()
            detailsContainer.isVisible = true

            val profile = activeProfile(activity)
            val selectedApps = selectedAppEntries(activity, profile)

            addOptionRow(
                activity = activity,
                iconRes = R.drawable.account_box_24,
                title = activity.getString(R.string.onb_review_profile_title),
                subtitle = profile,
                leadingColored = true,
                accent = accent,
                clickable = false,
                onClick = null,
                fixedHeightDp = 76f
            )

            addOptionRow(
                activity = activity,
                iconRes = R.drawable.tune_24,
                title = activity.getString(R.string.onb_review_mode_title),
                subtitle = selectedControlModeLabel(activity),
                leadingColored = true,
                accent = accent,
                clickable = false,
                onClick = null,
                fixedHeightDp = 76f
            )

            if (selectedApps.isEmpty()) {
                addEmptyAppsState(activity, accent)
            } else {
                addReviewAppsSummaryCard(
                    activity = activity,
                    page = page,
                    apps = selectedApps,
                    accent = accent
                )
            }

        }

        private fun addReviewAppsSummaryCard(
            activity: Activity,
            page: OnboardingPage,
            apps: List<SelectedAppEntry>,
            accent: Int
        ) {
            val density = itemView.resources.displayMetrics.density
            fun dp(value: Float): Int = (value * density).toInt()
            val surface = ContextCompat.getColor(activity, R.color.switchly_card_bg)
            val outline = ContextCompat.getColor(activity, R.color.switchly_card_stroke)
            val onSurface = MaterialColors.getColor(itemView, com.google.android.material.R.attr.colorOnSurface)

            val card = MaterialCardView(activity).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(14f) }
                minimumHeight = dp(118f)
                radius = dp(18f).toFloat()
                cardElevation = 0f
                strokeWidth = dp(1.5f)
                strokeColor = ColorUtils.blendARGB(outline, accent, 0.68f)
                setCardBackgroundColor(ColorUtils.blendARGB(surface, accent, 0.07f))
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    (activity as? at.saltyy.switchly.feature.onboarding.OnboardingActivity)
                        ?.showReviewAppsDialog()
                }
            }

            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                minimumHeight = dp(118f)
                setPadding(dp(14f), dp(14f), dp(12f), dp(14f))
            }

            val iconCluster = FrameLayout(activity).apply {
                layoutParams = LinearLayout.LayoutParams(dp(106f), dp(72f)).apply {
                    marginEnd = dp(14f)
                }
            }
            apps.take(4).forEachIndexed { index, app ->
                val size = dp(46f)
                val iconCard = MaterialCardView(activity).apply {
                    layoutParams = FrameLayout.LayoutParams(size, size).apply {
                        leftMargin = dp(index * 20f)
                        topMargin = if (index % 2 == 0) 0 else dp(20f)
                    }
                    radius = dp(13f).toFloat()
                    strokeWidth = dp(2f)
                    strokeColor = surface
                    cardElevation = dp(2f).toFloat()
                    setCardBackgroundColor(surface)
                }
                iconCard.addView(ImageView(activity).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                    setPadding(dp(5f), dp(5f), dp(5f), dp(5f))
                    setImageDrawable(app.icon)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    contentDescription = null
                })
                iconCluster.addView(iconCard)
            }
            row.addView(iconCluster)

            val labels = LinearLayout(activity).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                orientation = LinearLayout.VERTICAL
            }
            labels.addView(TextView(activity).apply {
                text = activity.getString(R.string.onb_review_apps_card_title)
                textSize = 15.5f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(onSurface)
                includeFontPadding = false
            })
            labels.addView(TextView(activity).apply {
                text = activity.resources.getQuantityString(
                    R.plurals.onb_review_apps_card_subtitle,
                    apps.size,
                    apps.size
                )
                textSize = 12.5f
                setTextColor(ColorUtils.setAlphaComponent(onSurface, 170))
                setPadding(0, dp(5f), 0, 0)
                maxLines = 2
                includeFontPadding = false
            })
            row.addView(labels)

            row.addView(MaterialCheckBox(activity).apply {
                layoutParams = LinearLayout.LayoutParams(dp(48f), dp(48f)).apply { marginStart = dp(8f) }
                isChecked = true
                isClickable = false
                isFocusable = false
                buttonTintList = ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(accent, ColorUtils.setAlphaComponent(onSurface, 110))
                )
                contentDescription = null
            })

            card.addView(row)
            detailsContainer.addView(card)
        }

        private fun activeProfile(activity: Activity): String =
            ProfileStore.getCurrent(activity)?.takeIf { it.isNotBlank() } ?: "Default"

        private fun selectedAppEntries(activity: Activity, profile: String): List<SelectedAppEntry> {
            val pm = activity.packageManager
            return ProfileStore.getSelectedForProfileMode(activity, profile)
                .map { packageName ->
                    runCatching {
                        val appInfo = PackageManagerApiCompat.getApplicationInfo(
                            packageManager = pm,
                            packageName = packageName,
                        )
                        SelectedAppEntry(
                            packageName = packageName,
                            label = pm.getApplicationLabel(appInfo).toString().ifBlank { packageName },
                            icon = pm.getApplicationIcon(appInfo)
                        )
                    }.getOrElse {
                        SelectedAppEntry(
                            packageName = packageName,
                            label = packageName.substringAfterLast('.').replaceFirstChar { ch -> ch.uppercase() },
                            icon = pm.defaultActivityIcon
                        )
                    }
                }
                .sortedBy { it.label.lowercase() }
        }

        private fun addEmptyAppsState(activity: Activity, accent: Int) {
            addUnifiedOnboardingRow(
                activity = activity,
                title = activity.getString(R.string.onb_pick_empty_title),
                iconRes = R.drawable.apps_24,
                info = activity.getString(R.string.onb_pick_empty_desc),
                status = activity.getString(R.string.onb_pick_empty_status),
                highlighted = false,
                accent = accent,
                clickable = false,
                onClick = null,
                fixedHeightDp = 96f
            )
        }

        private fun selectedControlModeLabel(ctx: Context): String {
            return when (AutomationModeStore.getMode(ctx)) {
                AutomationModeStore.Mode.SCHEDULE -> ctx.getString(R.string.pref_mode_schedule_title)
                AutomationModeStore.Mode.NFC -> ctx.getString(R.string.pref_mode_nfc_title)
                AutomationModeStore.Mode.QR -> ctx.getString(R.string.pref_mode_qr_title)
                AutomationModeStore.Mode.BARCODE -> ctx.getString(R.string.pref_mode_barcode_title)
                AutomationModeStore.Mode.MIXED -> ctx.getString(R.string.pref_mode_mixed_title)
            }
        }

        private fun iconForDetailRow(page: OnboardingPage, index: Int): Int {
            return when (page.iconRes) {
                R.drawable.play_arrow_24 -> when (index) {
                    0 -> R.drawable.apps_24
                    1 -> R.drawable.toggle_on_24
                    else -> R.drawable.tune_24
                }
                R.drawable.apps_24 -> when (index) {
                    0 -> R.drawable.apps_24
                    1 -> R.drawable.security_24
                    else -> R.drawable.tune_24
                }
                R.drawable.tune_24 -> if (index == 0) R.drawable.toggle_on_24 else R.drawable.lock_24
                R.drawable.schedule_24 -> if (index == 0) R.drawable.schedule_24 else R.drawable.toggle_on_24
                R.drawable.qr_code_24 -> if (index == 0) R.drawable.qr_code_24 else R.drawable.folder_24
                R.drawable.help_24 -> if (index == 0) R.drawable.help_24 else R.drawable.mail_24
                else -> page.iconRes ?: R.drawable.info_24
            }
        }

        private fun addPermissionRow(
            activity: Activity,
            title: String,
            iconRes: Int,
            subtitle: String,
            checked: Boolean,
            required: Boolean,
            accent: Int,
            onClick: (() -> Unit)
        ) {
            val status = if (checked) {
                activity.getString(R.string.onb_permission_status_ready)
            } else if (required) {
                "${activity.getString(R.string.onb_permission_status_missing)} · ${activity.getString(R.string.onb_badge_required)}"
            } else {
                activity.getString(R.string.onb_permission_status_missing)
            }
            addPermissionOptionRow(
                activity = activity,
                title = title,
                iconRes = iconRes,
                info = subtitle,
                status = status,
                checked = checked,
                accent = accent,
                onClick = onClick
            )
        }

        private fun addPermissionOptionRow(
            activity: Activity,
            title: String,
            iconRes: Int,
            info: String,
            status: String,
            checked: Boolean,
            accent: Int,
            onClick: (() -> Unit)
        ) {
            addUnifiedOnboardingRow(
                activity = activity,
                title = title,
                iconRes = iconRes,
                info = info,
                status = status,
                highlighted = checked,
                accent = accent,
                clickable = true,
                onClick = onClick,
                fixedHeightDp = 100f
            )
        }

        private fun addDetailRow(activity: Activity, text: String, accent: Int, iconRes: Int?) {
            addUnifiedOnboardingRow(
                activity = activity,
                title = text,
                iconRes = iconRes,
                info = null,
                status = null,
                highlighted = false,
                accent = accent,
                clickable = false,
                onClick = null,
                fixedHeightDp = 76f
            )
        }

        private fun addOptionRow(
            activity: Activity,
            iconRes: Int?,
            title: String,
            subtitle: String?,
            leadingColored: Boolean,
            accent: Int,
            clickable: Boolean,
            onClick: (() -> Unit)?,
            fixedHeightDp: Float? = null
        ) {
            addUnifiedOnboardingRow(
                activity = activity,
                title = title,
                iconRes = iconRes,
                info = subtitle,
                status = null,
                highlighted = leadingColored,
                accent = accent,
                clickable = clickable,
                onClick = onClick,
                fixedHeightDp = fixedHeightDp ?: if (subtitle.isNullOrBlank()) 76f else 88f
            )
        }

        private fun addUnifiedOnboardingRow(
            activity: Activity,
            title: String,
            iconRes: Int?,
            info: String?,
            status: String?,
            highlighted: Boolean,
            accent: Int,
            clickable: Boolean,
            onClick: (() -> Unit)?,
            fixedHeightDp: Float? = null
        ) {
            val density = itemView.resources.displayMetrics.density
            fun dp(value: Float): Int = (value * density).toInt()

            val surface = ContextCompat.getColor(activity, R.color.switchly_card_bg)
            val onSurface = MaterialColors.getColor(itemView, com.google.android.material.R.attr.colorOnSurface)
            val outline = ContextCompat.getColor(activity, R.color.switchly_card_stroke)
            val softAccent = ColorUtils.setAlphaComponent(accent, 18)

            val hasInfo = !info.isNullOrBlank()
            val hasStatus = !status.isNullOrBlank()
            val rowMinHeight = fixedHeightDp?.let { dp(it) } ?: when {
                hasInfo && hasStatus -> dp(100f)
                hasInfo -> dp(88f)
                else -> dp(76f)
            }

            val card = MaterialCardView(activity).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(8f) }
                minimumHeight = rowMinHeight
                radius = dp(16f).toFloat()
                cardElevation = 0f
                strokeWidth = dp(1f)
                strokeColor = if (highlighted) ColorUtils.setAlphaComponent(accent, 150) else outline
                setCardBackgroundColor(if (highlighted) softAccent else surface)
                isClickable = clickable
                isFocusable = clickable
                if (clickable) setOnClickListener { onClick?.invoke() }
            }

            val row = LinearLayout(activity).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                minimumHeight = rowMinHeight
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(dp(16f), dp(10f), dp(12f), dp(10f))
            }

            if (iconRes != null) {
                val iconBg = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(14f).toFloat()
                    setColor(if (highlighted) ColorUtils.setAlphaComponent(accent, 38) else softAccent)
                }
                val leadingIcon = ImageView(activity).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(38f), dp(38f)).apply { marginEnd = dp(13f) }
                    background = iconBg
                    setPadding(dp(8f), dp(8f), dp(8f), dp(8f))
                    setImageResource(iconRes)
                    imageTintList = ColorStateList.valueOf(ColorUtils.setAlphaComponent(accent, if (highlighted) 255 else 225))
                    contentDescription = null
                }
                row.addView(leadingIcon)
            }

            val texts = LinearLayout(activity).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }

            val titleView = TextView(activity).apply {
                text = title
                textSize = 14.8f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(onSurface)
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                includeFontPadding = false
            }
            texts.addView(titleView)

            if (hasInfo) {
                val infoView = TextView(activity).apply {
                    text = info
                    textSize = 12.4f
                    alpha = 0.78f
                    setTextColor(onSurface)
                    setPadding(0, dp(5f), 0, 0)
                    maxLines = 3
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    includeFontPadding = false
                }
                texts.addView(infoView)
            }

            if (hasStatus) {
                val statusView = TextView(activity).apply {
                    text = status
                    textSize = 12.2f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    setTextColor(if (highlighted) accent else ColorUtils.setAlphaComponent(onSurface, 185))
                    setPadding(0, dp(7f), 0, 0)
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    includeFontPadding = false
                }
                texts.addView(statusView)
            }

            row.addView(texts)

            if (clickable) {
                val arrow = ImageView(activity).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(22f), dp(22f)).apply { marginStart = dp(12f) }
                    setImageResource(R.drawable.keyboard_arrow_right_24)
                    imageTintList = ColorStateList.valueOf(ColorUtils.setAlphaComponent(if (highlighted) accent else onSurface, if (highlighted) 210 else 130))
                    contentDescription = null
                }
                row.addView(arrow)
            }

            card.addView(row)
            detailsContainer.addView(card)
        }

        private fun openNotificationsSetup(activity: Activity) {
            openPermissionsScreen(activity, PermissionsActivity.SECTION_NOTIFICATIONS)
        }

        @SuppressLint("BatteryLife")
        private fun openBatterySetup(activity: Activity) {
            if (safeStart(activity, Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = "package:${activity.packageName}".toUri()
                })) {
                return
            }
            openPermissionsScreen(activity, PermissionsActivity.SECTION_BATTERY)
        }

        private fun openCameraSetup(activity: Activity) {
            if (ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                activity.requestPermissions(arrayOf(Manifest.permission.CAMERA), 9214)
                return
            }
            openAppDetails(activity)
        }

        private fun exactAlarmsReady(ctx: Context): Boolean {
            return ExactAlarmPermissionSync.canScheduleExactAlarms(ctx)
        }

        private fun openExactAlarmSetup(activity: Activity) {
            openPermissionsScreen(activity, PermissionsActivity.SECTION_TRIGGERS)
        }

        private fun openNfcLaunchSetup(activity: Activity) {
            val intents = listOf(
                Intent("android.settings.MANAGE_SPECIAL_APP_ACCESSES"),
                Intent(Settings.ACTION_NFC_SETTINGS),
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = "package:${activity.packageName}".toUri()
                }
            )
            for (intent in intents) {
                if (safeStart(activity, intent)) {
                    return
                }
            }
        }

        private fun openTriggerPermissionSetup(activity: Activity) {
            openPermissionsScreen(activity, PermissionsActivity.SECTION_TRIGGERS)
        }

        private fun openPermissionsScreen(activity: Activity, section: String) {
            safeStart(activity, Intent(activity, PermissionsActivity::class.java).apply {
                putExtra(PermissionsActivity.EXTRA_FROM_ONBOARDING, true)
                putExtra(PermissionsActivity.EXTRA_FOCUS_SECTION, section)
            })
        }

        private fun openAppDetails(activity: Activity) {
            safeStart(activity, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = "package:${activity.packageName}".toUri()
            })
        }

        private fun safeStart(activity: Activity, intent: Intent): Boolean {
            return runCatching {
                activity.startActivity(intent)
                true
            }.getOrDefault(false)
        }

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

private fun onboardingHeroIconTint(context: Context): Int {
    val isNight = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES
    return if (isNight) {
        Color.WHITE
    } else {
        Color.rgb(24, 32, 28)
    }
}
