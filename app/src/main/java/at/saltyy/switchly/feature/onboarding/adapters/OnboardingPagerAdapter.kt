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
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.content.getSystemService
import androidx.core.graphics.ColorUtils
import androidx.core.text.HtmlCompat
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import at.saltyy.switchly.R
import at.saltyy.switchly.blocking.SwitchlyAccessibilityService
import at.saltyy.switchly.data.onboarding.OnboardingPage
import at.saltyy.switchly.data.prefs.AutomationModeStore
import at.saltyy.switchly.data.prefs.ExactAlarmPermissionSync
import at.saltyy.switchly.data.prefs.NotificationBlockStore
import at.saltyy.switchly.data.prefs.ProfileRuleModeStore
import at.saltyy.switchly.data.prefs.ProfileStore
import at.saltyy.switchly.feature.settings.AccessibilityDisclosure
import at.saltyy.switchly.feature.usage.UsageStatsRepo
import at.saltyy.switchly.theme.AccentColor
import at.saltyy.switchly.util.PermissionUtils
import at.saltyy.switchly.util.NfcLaunchAccessCompat
import at.saltyy.switchly.ui.dialog.showAccented
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.card.MaterialCardView

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

            if (page.iconRes != null) {
                icon.isVisible = true
                icon.setImageResource(page.iconRes)
            } else {
                icon.isVisible = false
            }

            badge.text = when (page.level) {
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

            renderDetails(activity, page, accent)
            applyPageSpacing(page)

            if (page.type == OnboardingPage.Type.PERMISSION_OVERVIEW) {
                btn.isVisible = false
                btn.isEnabled = false
                btn.setOnClickListener(null)
                return
            }

            val hasAction = page.action != null && !page.actionLabel.isNullOrBlank()
            val completedButStillEditable = completed && hasAction && page.keepActionEnabledWhenCompleted
            btn.isVisible = hasAction || completed

            if (completedButStillEditable) {
                btn.text = page.completedLabel ?: page.actionLabel
                btn.isEnabled = true
                btn.alpha = 1f
                btn.backgroundTintList = ColorStateList.valueOf(accent)
                btn.setTextColor(onAccent)
                btn.setOnClickListener { page.action?.invoke(activity) }
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
                btn.setOnClickListener { page.action?.invoke(activity) }
            } else {
                btn.setOnClickListener(null)
                btn.isEnabled = false
            }
        }

        private fun applyPageSpacing(page: OnboardingPage) {
            val density = itemView.resources.displayMetrics.density
            fun dp(value: Float): Int = (value * density).toInt()

            val isPermissionOverview = page.type == OnboardingPage.Type.PERMISSION_OVERVIEW

            fun applyCenteredScrollSpacing() {
                // All onboarding pages should feel like the permission page: start lower/near the visual middle, then let the user scroll down through the content.
                // Do not rely on LinearLayout vertical gravity, because long pages snap back to the top.
                val viewportHeight = itemView.height.takeIf { it > 0 } ?: itemView.resources.displayMetrics.heightPixels
                val dynamicTop = (viewportHeight * 0.24f).toInt()
                    .coerceIn(dp(130f), dp(205f))
                val dynamicBottom = if (isPermissionOverview) dp(48f) else dp(56f)
                pageContent.setPadding(dp(24f), dynamicTop, dp(24f), dynamicBottom)
                pageContent.gravity = android.view.Gravity.CENTER_HORIZONTAL
            }

            applyCenteredScrollSpacing()
            itemView.doOnLayout { applyCenteredScrollSpacing() }

            val lp = detailsContainer.layoutParams as? ViewGroup.MarginLayoutParams
            if (lp != null) {
                lp.topMargin = if (isPermissionOverview) dp(18f) else dp(16f)
                detailsContainer.layoutParams = lp
            }
            detailsContainer.setPadding(0, if (isPermissionOverview) dp(2f) else 0, 0, 0)
        }

        private fun renderDetails(activity: Activity, page: OnboardingPage, accent: Int) {
            detailsContainer.removeAllViews()
            detailsContainer.isVisible = false

            if (page.type == OnboardingPage.Type.REVIEW) {
                renderReviewDetails(activity, accent)
                return
            }

            if (page.type == OnboardingPage.Type.PERMISSION_OVERVIEW) {
                detailsContainer.isVisible = true

                val scheduleSelected = AutomationModeStore.isScheduleAllowed(activity)
                val nfcSelected = AutomationModeStore.isNfcAllowed(activity)
                val scanSelected = AutomationModeStore.isQrChannelAllowed(activity) ||
                    AutomationModeStore.isBarcodeChannelAllowed(activity)
                val notificationBlockingSelected = NotificationBlockStore.isEnabled(activity)

                // Always show the core blocker permission. This is the only hard requirement.
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

                if (notificationBlockingSelected) {
                    addPermissionRow(
                        activity,
                        title = activity.getString(R.string.onb_perm_notifications_title),
                        iconRes = R.drawable.notifications_24,
                        subtitle = activity.getString(R.string.onb_perm_notifications_desc),
                        checked = notificationsReady(activity),
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
                        checked = batteryReady(activity),
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
                        checked = triggerPermissionsReady(activity),
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
                        checked = cameraReady(activity),
                        required = false,
                        accent = accent,
                        onClick = { openCameraSetup(activity) }
                    )
                }

                // Usage Access is not required, but it helps insights and fallback diagnostics. Keep it last so mode-specific setup stays first.
                addPermissionRow(
                    activity,
                    title = activity.getString(R.string.onb_perm_usage_title),
                    iconRes = R.drawable.bar_chart_24,
                    subtitle = activity.getString(R.string.onb_perm_usage_desc),
                    checked = UsageStatsRepo.hasUsageAccess(activity),
                    required = false,
                    accent = accent,
                    onClick = { activity.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
                )

                return
            }

            if (page.detailRows.isNotEmpty()) {
                detailsContainer.isVisible = true
                page.detailRows.forEachIndexed { index, row -> addDetailRow(activity, row, accent, iconForDetailRow(page, index)) }
            }
        }

        private fun renderReviewDetails(activity: Activity, accent: Int) {
            detailsContainer.removeAllViews()
            detailsContainer.isVisible = true

            val profile = ProfileStore.getCurrent(activity)?.takeIf { it.isNotBlank() } ?: "Default"
            val selected = ProfileStore.getSelectedForProfileMode(activity, profile).toList().sorted()
            val appLabels = selected.map { pkg -> appLabelForPackage(activity, pkg) }.sortedBy { it.lowercase() }
            val listLabel = if (ProfileRuleModeStore.isAllowMode(activity, profile)) {
                activity.getString(R.string.onb_review_allowed_mode_label)
            } else {
                activity.getString(R.string.onb_review_blocked_mode_label)
            }

            addOptionRow(
                activity = activity,
                iconRes = R.drawable.account_box_24,
                title = activity.getString(R.string.onb_review_profile_title),
                subtitle = profile,
                leadingColored = true,
                accent = accent,
                clickable = false,
                onClick = null,
                fixedHeightDp = 88f
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
                fixedHeightDp = 88f
            )

            addOptionRow(
                activity = activity,
                iconRes = R.drawable.apps_24,
                title = activity.resources.getQuantityString(R.plurals.onb_review_app_list_title, selected.size, listLabel, selected.size),
                subtitle = activity.getString(R.string.onb_review_app_list_subtitle),
                leadingColored = true,
                accent = accent,
                clickable = selected.isNotEmpty(),
                onClick = {
                    showSelectedAppsDialog(activity, listLabel, appLabels)
                },
                fixedHeightDp = 96f
            )
        }

        private fun showSelectedAppsDialog(activity: Activity, listLabel: String, appLabels: List<String>) {
            if (appLabels.isEmpty()) {
                MaterialAlertDialogBuilder(activity)
                    .setTitle(listLabel)
                    .setMessage(activity.getString(R.string.onb_review_no_apps))
                    .setPositiveButton(android.R.string.ok, null)
                    .showAccented()
                return
            }

            MaterialAlertDialogBuilder(activity)
                .setTitle(activity.resources.getQuantityString(R.plurals.onb_review_apps_dialog_title, appLabels.size, listLabel, appLabels.size))
                .setItems(appLabels.toTypedArray(), null)
                .setPositiveButton(android.R.string.ok, null)
                .showAccented()
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
            val rowHeight = fixedHeightDp?.let { dp(it) } ?: when {
                hasInfo && hasStatus -> dp(100f)
                hasInfo -> dp(88f)
                else -> dp(76f)
            }

            val card = MaterialCardView(activity).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    rowHeight
                ).apply { topMargin = dp(8f) }
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
                    LinearLayout.LayoutParams.MATCH_PARENT
                )
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
                maxLines = if (hasInfo || hasStatus) 1 else 2
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
                    maxLines = 1
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
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    includeFontPadding = false
                }
                texts.addView(statusView)
            }

            row.addView(texts)

            if (clickable) {
                val arrow = ImageView(activity).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(22f), dp(22f)).apply { marginStart = dp(12f) }
                    setImageResource(R.drawable.arrow_forward_ios_24)
                    imageTintList = ColorStateList.valueOf(ColorUtils.setAlphaComponent(if (highlighted) accent else onSurface, if (highlighted) 210 else 130))
                    contentDescription = null
                }
                row.addView(arrow)
            }

            card.addView(row)
            detailsContainer.addView(card)
        }

        private fun notificationsReady(ctx: Context): Boolean {
            if (!NotificationManagerCompat.from(ctx).areNotificationsEnabled()) return false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                return false
            }
            return !NotificationBlockStore.isEnabled(ctx) || NotificationBlockStore.hasListenerAccess(ctx)
        }

        private fun openNotificationsSetup(activity: Activity) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 9211)
                return
            }

            if (!NotificationBlockStore.hasListenerAccess(activity)) {
                runCatching { activity.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
                    .onFailure { openAppDetails(activity) }
                return
            }

            runCatching {
                activity.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName)
                })
            }.onFailure { openAppDetails(activity) }
        }

        private fun openBatterySetup(activity: Activity) {
            val alreadyAllowed = runCatching {
                activity.getSystemService(PowerManager::class.java)
                    ?.isIgnoringBatteryOptimizations(activity.packageName) == true
            }.getOrDefault(false)

            val settingsIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            if (!alreadyAllowed && safeStart(activity, settingsIntent)) {
                return
            }
            if (!safeStart(activity, settingsIntent)) {
                openAppDetails(activity)
            }
        }

        private fun cameraReady(ctx: Context): Boolean {
            return ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                    data = "package:${activity.packageName}".toUri()
                }
                if (safeStart(activity, intent)) return
            }
            openAppDetails(activity)
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
                if (safeStart(activity, intent)) return
            }
        }

        private fun openTriggerPermissionSetup(activity: Activity) {
            val needsLocation = ContextCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
            if (needsLocation) {
                activity.requestPermissions(
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                    9212
                )
                return
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                ContextCompat.checkSelfPermission(activity, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
            ) {
                activity.requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 9213)
                return
            }

            openAppDetails(activity)
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

        private fun batteryReady(ctx: Context): Boolean {
            val pm = ctx.getSystemService<PowerManager>() ?: return false
            return runCatching { pm.isIgnoringBatteryOptimizations(ctx.packageName) }.getOrDefault(false)
        }

        private fun triggerPermissionsReady(ctx: Context): Boolean {
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
    }
}

private fun readableOnColor(color: Int): Int {
    val black = ColorUtils.calculateContrast(Color.BLACK, color)
    val white = ColorUtils.calculateContrast(Color.WHITE, color)
    return if (black >= white) Color.BLACK else Color.WHITE
}

private fun onboardingHeroIconTint(context: Context): Int {
    val isNight = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES
    return if (isNight) Color.WHITE else Color.rgb(24, 32, 28)
}
