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

import android.content.Context
import android.content.ComponentName
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.content.withStyledAttributes
import androidx.core.graphics.ColorUtils
import androidx.core.widget.ImageViewCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.PreferenceManager
import at.saltyy.switchly.R
import at.saltyy.switchly.widget.QrScanWidgetProvider
import at.saltyy.switchly.widget.NextScheduleWidgetProvider
import at.saltyy.switchly.widget.BlockedNotificationsWidgetProvider
import at.saltyy.switchly.widget.BarcodeScanWidgetProvider
import at.saltyy.switchly.feature.widget.ActiveTimerWidgetProvider
import at.saltyy.switchly.data.prefs.AutomationModeStore
import at.saltyy.switchly.data.prefs.AutostartStore
import at.saltyy.switchly.data.prefs.BlockingToggleKeys
import at.saltyy.switchly.data.prefs.EmergencyBypassStore
import at.saltyy.switchly.data.prefs.NotificationBlockStore
import at.saltyy.switchly.data.prefs.SwitchModeStore
import at.saltyy.switchly.feature.faq.FaqActivity
import at.saltyy.switchly.feature.onboarding.QuickTileHelper
import at.saltyy.switchly.theme.AccentColor
import at.saltyy.switchly.theme.CustomAccentApplier
import at.saltyy.switchly.ui.EdgeToEdgeUtils
import at.saltyy.switchly.ui.ThemeUtils
import at.saltyy.switchly.ui.dialog.styleSwitchlyDialogButtons
import at.saltyy.switchly.util.LocaleHelper
import at.saltyy.switchly.util.SwitchlyAppAccessGuard
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

open class ToggleOptionsActivity : AppCompatActivity() {

    private lateinit var cardNfcLockedHint: MaterialCardView
    private lateinit var btnNfcLockedHowTo: ImageButton
    private lateinit var btnInfoEnablePairedUids: ImageButton
    private lateinit var btnInfoLockSwitchlyAppAccess: ImageButton
    private val accentSwitches = mutableListOf<SwitchMaterial>()
    private val detailButtons = mutableListOf<ImageButton>()
    private var ignoreControlModeListener = false
    private var ignoreMixedChannelListener = false
    private var updatingUi = false
    private var activeSectionFilter: String? = null

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrapContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeUtils.applyAccentTheme(this)
        super.onCreate(savedInstanceState)
        if (SwitchlyAppAccessGuard.blockIfLocked(this)) return
        setContentView(R.layout.activity_toggle_options)
        CustomAccentApplier.applyIfNeeded(this)

        val requestedSection = normalizeSection(
            intent?.getStringExtra(EXTRA_SCROLL_TO_SECTION)
                ?: intent?.getStringExtra(EXTRA_VIEW_SECTION)
        )

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        EdgeToEdgeUtils.setupClassic(activity = this, toolbar = toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.setBackgroundColor(AccentColor.getToolbarColor(this))

        val ctx = this
        val sp = PreferenceManager.getDefaultSharedPreferences(ctx)
        SwitchModeStore.ensureInit(this)

        cardNfcLockedHint = findViewById(R.id.cardNfcLockedHint)
        btnNfcLockedHowTo = findViewById(R.id.btnNfcLockedHowTo)

        btnInfoEnablePairedUids = findViewById(R.id.btnInfoEnablePairedUids)
        btnInfoLockSwitchlyAppAccess = findViewById(R.id.btnInfoLockSwitchlyAppAccess)

        findViewById<MaterialButton>(R.id.btnOpenFaqTips).setOnClickListener {
            startActivity(Intent(this, FaqActivity::class.java))
        }

        // Info icon should follow the selected accent color.
        tintInfoIcon(btnNfcLockedHowTo)
        tintInfoIcon(btnInfoEnablePairedUids)
        tintInfoIcon(btnInfoLockSwitchlyAppAccess)

        btnNfcLockedHowTo.setOnClickListener {
            val dialog = MaterialAlertDialogBuilder(this)
                .setTitle(R.string.toggle_locked_nfc_action)
                .setMessage(R.string.toggle_locked_nfc_howto)
                .setPositiveButton(R.string.ok, null)
                .create()
            dialog.setOnShowListener { dialog.styleSwitchlyDialogButtons() }
            dialog.show()
        }

        btnInfoEnablePairedUids.setOnClickListener {
            val dialog = MaterialAlertDialogBuilder(this)
                .setTitle(R.string.toggle_info_paired_uids_title)
                .setMessage(
                    if (PreferenceManager.getDefaultSharedPreferences(this)
                            .getBoolean(BlockingToggleKeys.KEY_ENABLE_PAIRED_UIDS, false)
                    ) {
                        R.string.toggle_info_paired_uids_body_enabled
                    } else {
                        R.string.toggle_info_paired_uids_body_disabled
                    }
                )
                .setPositiveButton(R.string.ok, null)
                .create()
            dialog.setOnShowListener { dialog.styleSwitchlyDialogButtons() }
            dialog.show()
        }

        val showLockAndTempInfo: () -> Unit = {
            val dialog = MaterialAlertDialogBuilder(this)
                .setTitle(R.string.toggle_info_lock_and_temp_title)
                .setMessage(R.string.toggle_info_lock_and_temp_body)
                .setPositiveButton(R.string.ok, null)
                .create()
            dialog.setOnShowListener { dialog.styleSwitchlyDialogButtons() }
            dialog.show()
        }

        btnInfoLockSwitchlyAppAccess.setOnClickListener { showLockAndTempInfo() }

        // --- Switches (Control mode)
        val switchModeSchedule = findViewById<SwitchMaterial>(R.id.switchModeSchedule)
        val switchModeNfc = findViewById<SwitchMaterial>(R.id.switchModeNfc)
        val switchModeQr = findViewById<SwitchMaterial>(R.id.switchModeQr)
        val switchModeBarcode = findViewById<SwitchMaterial>(R.id.switchModeBarcode)
        val switchModeMixed = findViewById<SwitchMaterial>(R.id.switchModeMixed)
        val switchAllowButtonEnable = findViewById<SwitchMaterial>(R.id.switchAllowButtonEnable)

        // --- Switches (Mixed mode channels)
        val cardMixedChannels = findViewById<MaterialCardView>(R.id.cardMixedChannels)
        val tvMixedChannelsSection = findViewById<TextView>(R.id.tvMixedChannelsSection)
        val tvMixedChannelsSectionSummary = findViewById<TextView>(R.id.tvMixedChannelsSectionSummary)
        val switchMixedAllowSchedule = findViewById<SwitchMaterial>(R.id.switchMixedAllowSchedule)
        val switchMixedAllowNfc = findViewById<SwitchMaterial>(R.id.switchMixedAllowNfc)
        val switchMixedAllowQr = findViewById<SwitchMaterial>(R.id.switchMixedAllowQr)
        val switchMixedAllowBarcode = findViewById<SwitchMaterial>(R.id.switchMixedAllowBarcode)
        val switchMixedAllowButton = findViewById<SwitchMaterial>(R.id.switchMixedAllowButton)
        val switchMixedAllowAppPicking = findViewById<SwitchMaterial>(R.id.switchMixedAllowAppPicking)
        val switchMixedAllowProfileSwitching = findViewById<SwitchMaterial>(R.id.switchMixedAllowProfileSwitching)
        val switchMixedAllowScheduleEditing = findViewById<SwitchMaterial>(R.id.switchMixedAllowScheduleEditing)
        val switchMixedAllowNfcTagWriting = findViewById<SwitchMaterial>(R.id.switchMixedAllowNfcTagWriting)
        val switchLockSwitchlyAppAccess = findViewById<SwitchMaterial>(R.id.switchLockSwitchlyAppAccess)

        // --- Switches (Additional features)
        val switchRequireNfcUnlock = findViewById<SwitchMaterial>(R.id.switchRequireNfcUnlock)

        // --- Switches (Protection)
        val switchBlockNotifications = findViewById<SwitchMaterial>(R.id.switchBlockNotifications)
        val switchAutostart = findViewById<SwitchMaterial>(R.id.switchAutostart)

        // --- Switches (UI & Info)
        val switchEmergency = findViewById<SwitchMaterial>(R.id.switchEmergency)
        val switchShowQuickActions = findViewById<SwitchMaterial>(R.id.switchShowQuickActions)
        val switchShowTemporaryMode = findViewById<SwitchMaterial>(R.id.switchShowTemporaryMode)
        val switchShowActiveDuration = findViewById<SwitchMaterial>(R.id.switchShowActiveDuration)
        val switchShowEmergencyUnlock = findViewById<SwitchMaterial>(R.id.switchShowEmergencyUnlock)
        val tvHomeLayoutModeValue = findViewById<TextView>(R.id.tvHomeLayoutModeValue)

        // --- Switches (In-app)
        val switchEnablePairedUids = findViewById<SwitchMaterial>(R.id.switchEnablePairedUids)
        val switchAutoPairOnWrite = findViewById<SwitchMaterial>(R.id.switchAutoPairOnWrite)

        // Rows clickable
        val rowModeSchedule = findViewById<View>(R.id.rowModeSchedule)
        val rowModeNfc = findViewById<View>(R.id.rowModeNfc)
        val rowModeQr = findViewById<View>(R.id.rowModeQr)
        val rowModeBarcode = findViewById<View>(R.id.rowModeBarcode)
        val rowModeMixed = findViewById<View>(R.id.rowModeMixed)
        val rowAllowButtonEnable = findViewById<View>(R.id.rowAllowButtonEnable)

        val rowMixedAllowSchedule = findViewById<View>(R.id.rowMixedAllowSchedule)
        val rowMixedAllowNfc = findViewById<View>(R.id.rowMixedAllowNfc)
        val rowMixedAllowQr = findViewById<View>(R.id.rowMixedAllowQr)
        val rowMixedAllowBarcode = findViewById<View>(R.id.rowMixedAllowBarcode)
        val rowMixedAllowButton = findViewById<View>(R.id.rowMixedAllowButton)
        val rowMixedAllowAppPicking = findViewById<View>(R.id.rowMixedAllowAppPicking)
        val rowMixedAllowProfileSwitching = findViewById<View>(R.id.rowMixedAllowProfileSwitching)
        val rowMixedAllowScheduleEditing = findViewById<View>(R.id.rowMixedAllowScheduleEditing)
        val rowMixedAllowNfcTagWriting = findViewById<View>(R.id.rowMixedAllowNfcTagWriting)
        val dividerAfterMixedAllowSchedule = findViewById<View>(R.id.dividerAfterMixedAllowSchedule)
        val dividerAfterMixedAllowNfc = findViewById<View>(R.id.dividerAfterMixedAllowNfc)
        val dividerAfterMixedAllowQr = findViewById<View>(R.id.dividerAfterMixedAllowQr)
        val dividerAfterMixedAllowBarcode = findViewById<View>(R.id.dividerAfterMixedAllowBarcode)
        val dividerAfterMixedAllowProfileSwitching = findViewById<View>(R.id.dividerAfterMixedAllowProfileSwitching)
        val rowLockSwitchlyAppAccess = findViewById<View>(R.id.rowLockSwitchlyAppAccess)

        val rowRequireNfcUnlock = findViewById<View>(R.id.rowRequireNfcUnlock)
        val rowQuickTile = findViewById<View>(R.id.rowQuickTile)
        val rowQrQuickTile = findViewById<View>(R.id.rowQrQuickTile)
        val rowBarcodeQuickTile = findViewById<View>(R.id.rowBarcodeQuickTile)
        val dividerAfterQrQuickTile = findViewById<View>(R.id.dividerAfterQrQuickTile)
        val dividerAfterBarcodeQuickTile = findViewById<View>(R.id.dividerAfterBarcodeQuickTile)

        val rowBlockNotifs = findViewById<View>(R.id.rowBlockNotifications)
        val rowAutostart = findViewById<View>(R.id.rowAutostart)

        val rowEmergency = findViewById<View>(R.id.rowEmergency)
        val dividerBeforeEmergency = findViewById<View>(R.id.dividerAfterAutostart)
        val rowShowQuickActions = findViewById<View>(R.id.rowShowQuickActions)
        val rowShowTemporaryMode = findViewById<View>(R.id.rowShowTemporaryMode)
        val rowShowEmergencyUnlock = findViewById<View>(R.id.rowShowEmergencyUnlock)
        val rowHomeLayoutDetailed = findViewById<View>(R.id.rowHomeLayoutDetailed)
        val dividerAfterHomeLayoutDetailed = findViewById<View>(R.id.dividerAfterHomeLayoutDetailed)
        val rowHomeLayoutCustom = findViewById<View>(R.id.rowHomeLayoutCustom)
        val dividerAfterHomeLayoutCustom = findViewById<View>(R.id.dividerAfterHomeLayoutCustom)
        val showHomeModeRowsInControls = false
        rowHomeLayoutDetailed.visibility = if (showHomeModeRowsInControls) View.VISIBLE else View.GONE
        dividerAfterHomeLayoutDetailed.visibility = if (showHomeModeRowsInControls) View.VISIBLE else View.GONE
        val rowWidgetBarcode = findViewById<View>(R.id.rowWidgetBarcode)
        val rowWidgetQr = findViewById<View>(R.id.rowWidgetQr)
        val rowWidgetNextSchedule = findViewById<View>(R.id.rowWidgetNextSchedule)
        val rowWidgetActiveTimer = findViewById<View>(R.id.rowWidgetActiveTimer)
        val rowWidgetBlockedNotifications = findViewById<View>(R.id.rowWidgetBlockedNotifications)
        val rowEnablePairedUids = findViewById<View>(R.id.rowEnablePairedUids)
        val rowAutoPairOnWrite = findViewById<View>(R.id.rowAutoPairOnWrite)
        val accent = AccentColor.getAccentColorInt(this)
        val green = ContextCompat.getColor(this, R.color.accent_green)
        val blue = ContextCompat.getColor(this, R.color.accent_blue)
        val teal = ContextCompat.getColor(this, R.color.accent_teal)
        val purple = ContextCompat.getColor(this, R.color.accent_purple)
        val orange = ContextCompat.getColor(this, R.color.accent_orange)
        val amber = ContextCompat.getColor(this, R.color.accent_amber)
        val gray = ContextCompat.getColor(this, R.color.accent_gray)
        tintLeadingIcon(rowModeSchedule, accent)
        tintLeadingIcon(rowModeNfc, accent)
        tintLeadingIcon(rowModeQr, accent)
        tintLeadingIcon(rowModeBarcode, accent)
        tintLeadingIcon(rowModeMixed, accent)
        tintLeadingIcon(rowAllowButtonEnable, accent)
        tintLeadingIcon(rowMixedAllowSchedule, accent)
        tintLeadingIcon(rowMixedAllowNfc, accent)
        tintLeadingIcon(rowMixedAllowQr, accent)
        tintLeadingIcon(rowMixedAllowBarcode, accent)
        tintLeadingIcon(rowMixedAllowButton, accent)
        tintLeadingIcon(rowMixedAllowAppPicking, accent)
        tintLeadingIcon(rowMixedAllowProfileSwitching, accent)
        tintLeadingIcon(rowMixedAllowScheduleEditing, accent)
        tintLeadingIcon(rowMixedAllowNfcTagWriting, accent)
        tintLeadingIcon(rowLockSwitchlyAppAccess, accent)
        tintLeadingIcon(rowRequireNfcUnlock, accent)
        tintLeadingIcon(rowQuickTile, accent)
        tintLeadingIcon(rowQrQuickTile, accent)
        tintLeadingIcon(rowBarcodeQuickTile, accent)
        tintLeadingIcon(rowBlockNotifs, accent)
        tintLeadingIcon(rowAutostart, accent)
        tintLeadingIcon(rowEmergency, accent)
        tintLeadingIcon(rowShowQuickActions, accent)
        tintLeadingIcon(rowShowTemporaryMode, accent)
        tintLeadingIcon(rowShowEmergencyUnlock, accent)
        tintLeadingIcon(rowHomeLayoutDetailed, accent)
        tintLeadingIcon(rowHomeLayoutCustom, accent)
        tintLeadingIcon(rowWidgetBarcode, accent)
        tintLeadingIcon(rowWidgetQr, accent)
        tintLeadingIcon(rowWidgetNextSchedule, accent)
        tintLeadingIcon(rowWidgetActiveTimer, accent)
        tintLeadingIcon(rowWidgetBlockedNotifications, accent)
        tintLeadingIcon(rowEnablePairedUids, accent)
        tintLeadingIcon(rowAutoPairOnWrite, accent)

        addInlineDetailsAction(
            row = rowModeSchedule,
            switchView = switchModeSchedule,
            titleRes = R.string.pref_mode_schedule_title,
            summaryRes = R.string.pref_mode_schedule_summary,
            detailsRes = R.string.toggle_detail_mode_schedule
        )
        addInlineDetailsAction(
            row = rowModeNfc,
            switchView = switchModeNfc,
            titleRes = R.string.pref_mode_nfc_title,
            summaryRes = R.string.pref_mode_nfc_summary,
            detailsRes = R.string.toggle_detail_mode_nfc
        )
        addInlineDetailsAction(
            row = rowModeQr,
            switchView = switchModeQr,
            titleRes = R.string.pref_mode_qr_title,
            summaryRes = R.string.pref_mode_qr_summary,
            detailsRes = R.string.toggle_detail_mode_qr
        )
        addInlineDetailsAction(
            row = rowModeBarcode,
            switchView = switchModeBarcode,
            titleRes = R.string.pref_mode_barcode_title,
            summaryRes = R.string.pref_mode_barcode_summary,
            detailsRes = R.string.toggle_detail_mode_barcode
        )
        addInlineDetailsAction(
            row = rowModeMixed,
            switchView = switchModeMixed,
            titleRes = R.string.pref_mode_mixed_title,
            summaryRes = R.string.pref_mode_mixed_summary,
            detailsRes = R.string.toggle_detail_mode_mixed
        )
        addInlineDetailsAction(
            row = rowAllowButtonEnable,
            switchView = switchAllowButtonEnable,
            titleRes = R.string.pref_allow_button_enable_title,
            summaryRes = R.string.pref_allow_button_enable_summary,
            detailsRes = R.string.toggle_detail_allow_button_enable
        )
        addInlineDetailsAction(
            row = rowMixedAllowSchedule,
            switchView = switchMixedAllowSchedule,
            titleRes = R.string.pref_mixed_allow_schedule_title,
            summaryRes = R.string.pref_mixed_allow_schedule_summary,
            detailsRes = R.string.toggle_detail_mixed_allow_schedule
        )
        addInlineDetailsAction(
            row = rowMixedAllowNfc,
            switchView = switchMixedAllowNfc,
            titleRes = R.string.pref_mixed_allow_nfc_title,
            summaryRes = R.string.pref_mixed_allow_nfc_summary,
            detailsRes = R.string.toggle_detail_mixed_allow_nfc
        )
        addInlineDetailsAction(
            row = rowMixedAllowQr,
            switchView = switchMixedAllowQr,
            titleRes = R.string.pref_mixed_allow_qr_title,
            summaryRes = R.string.pref_mixed_allow_qr_summary,
            detailsRes = R.string.toggle_detail_mixed_allow_qr
        )
        addInlineDetailsAction(
            row = rowMixedAllowBarcode,
            switchView = switchMixedAllowBarcode,
            titleRes = R.string.pref_mixed_allow_barcode_title,
            summaryRes = R.string.pref_mixed_allow_barcode_summary,
            detailsRes = R.string.toggle_detail_mixed_allow_barcode
        )
        addInlineDetailsAction(
            row = rowMixedAllowButton,
            switchView = switchMixedAllowButton,
            titleRes = R.string.pref_mixed_allow_button_title,
            summaryRes = R.string.pref_mixed_allow_button_summary,
            detailsRes = R.string.toggle_detail_mixed_allow_button
        )
        addInlineDetailsAction(
            row = rowMixedAllowAppPicking,
            switchView = switchMixedAllowAppPicking,
            titleRes = R.string.pref_mixed_allow_app_picking_title,
            summaryRes = R.string.pref_mixed_allow_app_picking_summary,
            detailsRes = R.string.toggle_detail_mixed_allow_app_picking
        )
        addInlineDetailsAction(
            row = rowMixedAllowProfileSwitching,
            switchView = switchMixedAllowProfileSwitching,
            titleRes = R.string.pref_mixed_allow_profile_switching_title,
            summaryRes = R.string.pref_mixed_allow_profile_switching_summary,
            detailsRes = R.string.toggle_detail_mixed_allow_profile_switching
        )
        addInlineDetailsAction(
            row = rowMixedAllowNfcTagWriting,
            switchView = switchMixedAllowNfcTagWriting,
            titleRes = R.string.pref_mixed_allow_nfc_tag_writing_title,
            summaryRes = R.string.pref_mixed_allow_nfc_tag_writing_summary,
            detailsRes = R.string.toggle_detail_mixed_allow_nfc_tag_writing
        )
        addInlineDetailsAction(
            row = rowLockSwitchlyAppAccess,
            switchView = switchLockSwitchlyAppAccess,
            titleRes = R.string.pref_lock_switchly_app_access_title,
            summaryRes = R.string.pref_lock_switchly_app_access_summary,
            detailsRes = R.string.toggle_detail_lock_switchly_app_access
        )
        addInlineDetailsAction(
            row = rowAutoPairOnWrite,
            switchView = switchAutoPairOnWrite,
            titleRes = R.string.pref_auto_pair_on_write_title,
            summaryRes = R.string.pref_auto_pair_on_write_summary,
            detailsRes = R.string.toggle_detail_auto_pair_on_write
        )
        addInlineDetailsAction(
            row = rowBlockNotifs,
            switchView = switchBlockNotifications,
            titleRes = R.string.pref_block_notifications_title,
            summaryRes = R.string.pref_block_notifications_summary,
            detailsRes = R.string.toggle_detail_block_notifications
        )
        addInlineDetailsAction(
            row = rowAutostart,
            switchView = switchAutostart,
            titleRes = R.string.pref_autostart_title,
            summaryRes = R.string.pref_autostart_summary,
            detailsRes = R.string.toggle_detail_autostart
        )
        addInlineDetailsAction(
            row = rowEmergency,
            switchView = switchEmergency,
            titleRes = R.string.pref_emergency_title,
            summaryRes = R.string.pref_emergency_summary,
            detailsRes = R.string.toggle_detail_emergency
        )
        addInlineDetailsAction(
            row = rowShowQuickActions,
            switchView = switchShowQuickActions,
            titleRes = R.string.pref_show_quick_actions_title,
            summaryRes = R.string.pref_show_quick_actions_summary,
            detailsRes = R.string.toggle_detail_quick_actions
        )
        addInlineDetailsAction(
            row = rowShowTemporaryMode,
            switchView = switchShowTemporaryMode,
            titleRes = R.string.pref_show_temporary_mode_title,
            summaryRes = R.string.pref_show_temporary_mode_summary,
            detailsRes = R.string.toggle_detail_show_temporary_mode
        )
        addInlineDetailsAction(
            row = rowShowEmergencyUnlock,
            switchView = switchShowEmergencyUnlock,
            titleRes = R.string.pref_show_emergency_unlock_title,
            summaryRes = R.string.pref_show_emergency_unlock_summary,
            detailsRes = R.string.toggle_detail_show_emergency_unlock
        )

        accentSwitches.clear()
        accentSwitches += listOf(
            switchModeSchedule,
            switchModeNfc,
            switchModeQr,
            switchModeBarcode,
            switchModeMixed,
            switchAllowButtonEnable,
            switchMixedAllowSchedule,
            switchMixedAllowNfc,
            switchMixedAllowQr,
            switchMixedAllowBarcode,
            switchMixedAllowButton,
            switchMixedAllowAppPicking,
            switchMixedAllowProfileSwitching,
            switchMixedAllowScheduleEditing,
            switchMixedAllowNfcTagWriting,
            switchLockSwitchlyAppAccess,
            switchBlockNotifications,
            switchAutostart,
            switchEmergency,
            switchShowQuickActions,
            switchShowTemporaryMode,
            switchShowActiveDuration,
            switchShowEmergencyUnlock,
        )
        applySwitchAccentTints()

        SwitchModeStore.setNfcRequiredForDisable(ctx, false)
        rowRequireNfcUnlock.visibility = View.GONE
        findViewById<View>(R.id.dividerRequireNfcUnlock)?.visibility = View.GONE
        switchRequireNfcUnlock.isChecked = false

        switchAllowButtonEnable.isChecked = AutomationModeStore.isButtonEnableAllowed(ctx)
        switchMixedAllowSchedule.isChecked = AutomationModeStore.isMixedAllowSchedule(ctx)
        switchMixedAllowNfc.isChecked = AutomationModeStore.isMixedAllowNfc(ctx)
        switchMixedAllowQr.isChecked = AutomationModeStore.isMixedAllowQr(ctx)
        switchMixedAllowBarcode.isChecked = AutomationModeStore.isMixedAllowBarcode(ctx)
        switchMixedAllowButton.isChecked = AutomationModeStore.isMixedAllowButton(ctx)
        switchMixedAllowAppPicking.isChecked = AutomationModeStore.isMixedAllowAppPicking(ctx)
        switchMixedAllowProfileSwitching.isChecked = AutomationModeStore.isMixedAllowProfileSwitching(ctx)
        rowMixedAllowScheduleEditing.visibility = View.GONE
        dividerAfterMixedAllowProfileSwitching.visibility = View.GONE
        switchMixedAllowNfcTagWriting.isChecked = AutomationModeStore.isMixedAllowNfcTagWriting(ctx)
        switchLockSwitchlyAppAccess.isChecked = AutomationModeStore.isSwitchlyAppAccessLockEnabled(ctx)

        switchBlockNotifications.isChecked = NotificationBlockStore.isEnabled(ctx)
        switchAutostart.isChecked = AutostartStore.isEnabled(ctx)

        switchEmergency.isChecked = EmergencyBypassStore.isFeatureEnabled(ctx)
        switchShowQuickActions.isChecked = sp.getBoolean(KEY_SHOW_QUICK_ACTIONS, true)
        switchShowTemporaryMode.isChecked = sp.getBoolean(KEY_SHOW_TEMPORARY_MODE, true)
        switchShowActiveDuration.isChecked = sp.getBoolean(KEY_SHOW_ACTIVE_DURATION, true)
        switchShowEmergencyUnlock.isChecked = sp.getBoolean(KEY_SHOW_EMERGENCY_UNLOCK, true)

        fun currentHomeLayoutMode(): String {
            return HomeModeDialogHelper.currentHomeLayoutMode(this)
        }

        fun homeLayoutModeLabel(mode: String): String =
            HomeModeDialogHelper.homeLayoutModeLabel(this, mode)

        fun refreshHomeLayoutModeValue() {
            val mode = currentHomeLayoutMode()
            tvHomeLayoutModeValue.text = homeLayoutModeLabel(mode)
            val customVisible = showHomeModeRowsInControls && mode == HOME_MODE_CUSTOM
            rowHomeLayoutDetailed.visibility = if (showHomeModeRowsInControls) View.VISIBLE else View.GONE
            dividerAfterHomeLayoutDetailed.visibility = if (showHomeModeRowsInControls) View.VISIBLE else View.GONE
            rowHomeLayoutCustom.visibility = if (customVisible) View.VISIBLE else View.GONE
            dividerAfterHomeLayoutCustom.visibility = if (customVisible) View.VISIBLE else View.GONE
        }
        refreshHomeLayoutModeValue()

        fun showCustomizeHomeDialog() {
            HomeModeDialogHelper.showCustomizeHomeDialog(this) {
                refreshHomeLayoutModeValue()
            }
        }

        fun showHomeLayoutModeDialog() {
            HomeModeDialogHelper.showHomeLayoutModeDialog(this) {
                refreshHomeLayoutModeValue()
            }
        }

        fun refreshEmergencyFeatureVisibility() {
            // Emergency itself is a safety/feature option and should not be hidden by the
            // Home shortcut visibility toggle.
            rowEmergency.visibility = View.VISIBLE
            dividerBeforeEmergency.visibility = View.VISIBLE
        }
        refreshEmergencyFeatureVisibility()

        val nfcSupported = AutomationModeStore.isNfcSupported(ctx)
        switchEnablePairedUids.isChecked =
            nfcSupported && sp.getBoolean(BlockingToggleKeys.KEY_ENABLE_PAIRED_UIDS, false)
        switchAutoPairOnWrite.isChecked =
            nfcSupported && sp.getBoolean(BlockingToggleKeys.KEY_AUTO_PAIR_ON_WRITE, false)

        fun modeLabel(mode: AutomationModeStore.Mode): String = when (mode) {
            AutomationModeStore.Mode.SCHEDULE -> getString(R.string.pref_mode_schedule_title)
            AutomationModeStore.Mode.NFC -> getString(R.string.pref_mode_nfc_title)
            AutomationModeStore.Mode.QR -> getString(R.string.pref_mode_qr_title)
            AutomationModeStore.Mode.BARCODE -> getString(R.string.pref_mode_barcode_title)
            AutomationModeStore.Mode.MIXED -> getString(R.string.pref_mode_mixed_title)
        }

        fun canChangeControlMode(showToast: Boolean = true): Boolean {
            val allowed = !SwitchModeStore.isEnabled(ctx)
            if (!allowed && showToast) {
                Toast.makeText(
                    ctx,
                    getString(R.string.mode_switch_requires_switchly_disabled),
                    Toast.LENGTH_SHORT
                ).show()
            }
            return allowed
        }

        fun applyControlModeSelection(mode: AutomationModeStore.Mode, userInitiated: Boolean) {
            AutomationModeStore.setMode(ctx, mode)
            val appliedMode = AutomationModeStore.getMode(ctx)

            ignoreControlModeListener = true
            switchModeSchedule.isChecked = appliedMode == AutomationModeStore.Mode.SCHEDULE
            switchModeNfc.isChecked = appliedMode == AutomationModeStore.Mode.NFC
            switchModeQr.isChecked = appliedMode == AutomationModeStore.Mode.QR
            switchModeBarcode.isChecked = appliedMode == AutomationModeStore.Mode.BARCODE
            switchModeMixed.isChecked = appliedMode == AutomationModeStore.Mode.MIXED
            ignoreControlModeListener = false

            val showMixedOnly = appliedMode == AutomationModeStore.Mode.MIXED
            val isNfc = appliedMode == AutomationModeStore.Mode.NFC
            val showControlMethods = activeSectionFilter == null || activeSectionFilter == SECTION_BLOCKING
            val showAccessWhileActive = activeSectionFilter == null || activeSectionFilter == SECTION_OTHER

            cardMixedChannels.visibility = View.VISIBLE
            tvMixedChannelsSection.visibility = View.VISIBLE
            tvMixedChannelsSection.setText(
                when {
                    showAccessWhileActive && !showControlMethods -> R.string.toggle_section_additional_features
                    showControlMethods && !showMixedOnly -> R.string.toggle_section_manual_controls
                    else -> R.string.toggle_section_mixed_channels
                }
            )
            tvMixedChannelsSectionSummary.setText(
                when {
                    showAccessWhileActive && !showControlMethods -> R.string.toggle_section_additional_features_summary
                    showControlMethods && !showMixedOnly -> R.string.toggle_section_manual_controls_summary
                    else -> R.string.toggle_section_mixed_channels_summary
                }
            )

            // Control methods: how Switchly can be enabled/disabled.
            rowMixedAllowSchedule.visibility = if (showControlMethods && showMixedOnly) View.VISIBLE else View.GONE
            rowMixedAllowNfc.visibility = if (showControlMethods && showMixedOnly) View.VISIBLE else View.GONE
            rowMixedAllowQr.visibility = if (showControlMethods && showMixedOnly) View.VISIBLE else View.GONE
            rowMixedAllowBarcode.visibility = if (showControlMethods && showMixedOnly) View.VISIBLE else View.GONE
            rowMixedAllowButton.visibility = if (showControlMethods) View.VISIBLE else View.GONE

            dividerAfterMixedAllowSchedule.visibility = if (showControlMethods && showMixedOnly) View.VISIBLE else View.GONE
            dividerAfterMixedAllowNfc.visibility = if (showControlMethods && showMixedOnly) View.VISIBLE else View.GONE
            dividerAfterMixedAllowQr.visibility = if (showControlMethods && showMixedOnly) View.VISIBLE else View.GONE
            dividerAfterMixedAllowBarcode.visibility = if (showControlMethods && showMixedOnly) View.VISIBLE else View.GONE

            // Access while active: what can still be edited while protection is on.
            rowMixedAllowAppPicking.visibility = if (showAccessWhileActive) View.VISIBLE else View.GONE
            rowMixedAllowProfileSwitching.visibility = if (showAccessWhileActive) View.VISIBLE else View.GONE
            dividerAfterMixedAllowProfileSwitching.visibility = if (showAccessWhileActive) View.VISIBLE else View.GONE
            rowAllowButtonEnable.visibility =
                if (showControlMethods && !switchMixedAllowButton.isChecked) View.VISIBLE else View.GONE

            // Hidden legacy/unused access option.
            rowMixedAllowScheduleEditing.visibility = View.GONE

            rowMixedAllowNfcTagWriting.visibility =
                if (showAccessWhileActive && (isNfc || showMixedOnly)) View.VISIBLE else View.GONE

            refreshMixedChannelInteractivity()
        }

        fun onControlModeRowClicked(target: AutomationModeStore.Mode) {
            if (!AutomationModeStore.isModeSupported(ctx, target)) {
                Toast.makeText(
                    ctx,
                    getString(R.string.mode_not_supported_on_device),
                    Toast.LENGTH_SHORT
                ).show()
                return
            }

            val active = AutomationModeStore.getMode(ctx)
            if (target == active) {
                Toast.makeText(
                    ctx,
                    getString(R.string.mode_already_active_fmt, modeLabel(target)),
                    Toast.LENGTH_SHORT
                ).show()
                return
            }

            if (!canChangeControlMode()) {
                return
            }

            Toast.makeText(
                ctx,
                getString(R.string.mode_switch_info_toast),
                Toast.LENGTH_SHORT
            ).show()
            applyControlModeSelection(target, userInitiated = true)
        }

        rowModeSchedule.setOnClickListener { onControlModeRowClicked(AutomationModeStore.Mode.SCHEDULE) }
        rowModeNfc.setOnClickListener { onControlModeRowClicked(AutomationModeStore.Mode.NFC) }
        rowModeQr.setOnClickListener { onControlModeRowClicked(AutomationModeStore.Mode.QR) }
        rowModeBarcode.setOnClickListener { onControlModeRowClicked(AutomationModeStore.Mode.BARCODE) }
        rowModeMixed.setOnClickListener { onControlModeRowClicked(AutomationModeStore.Mode.MIXED) }

        fun bindModeSwitch(sw: SwitchMaterial, mode: AutomationModeStore.Mode) {
            sw.setOnCheckedChangeListener { _, checked ->
                if (ignoreControlModeListener) return@setOnCheckedChangeListener

                val active = AutomationModeStore.getMode(ctx)
                if (!checked && active == mode) {
                    ignoreControlModeListener = true
                    sw.isChecked = true
                    ignoreControlModeListener = false
                    Toast.makeText(ctx, getString(R.string.mode_one_must_stay_active), Toast.LENGTH_SHORT).show()
                    return@setOnCheckedChangeListener
                }

                if (checked && active != mode) {
                    if (!AutomationModeStore.isModeSupported(ctx, mode)) {
                        ignoreControlModeListener = true
                        sw.isChecked = false
                        ignoreControlModeListener = false
                        Toast.makeText(
                            ctx,
                            getString(R.string.mode_not_supported_on_device),
                            Toast.LENGTH_SHORT
                        ).show()
                        return@setOnCheckedChangeListener
                    }
                    if (!canChangeControlMode()) {
                        ignoreControlModeListener = true
                        applyControlModeSelection(active, userInitiated = false)
                        ignoreControlModeListener = false
                        return@setOnCheckedChangeListener
                    }
                    applyControlModeSelection(mode, userInitiated = true)
                }
            }
        }

        bindModeSwitch(switchModeSchedule, AutomationModeStore.Mode.SCHEDULE)
        bindModeSwitch(switchModeNfc, AutomationModeStore.Mode.NFC)
        bindModeSwitch(switchModeQr, AutomationModeStore.Mode.QR)
        bindModeSwitch(switchModeBarcode, AutomationModeStore.Mode.BARCODE)
        bindModeSwitch(switchModeMixed, AutomationModeStore.Mode.MIXED)

        applyControlModeSelection(AutomationModeStore.getMode(ctx), userInitiated = false)
        refreshNfcLockedHint()

        // Row click toggles switch
        rowAllowButtonEnable.setOnClickListener {
            if (canEditMixedChannels()) switchAllowButtonEnable.toggle()
        }
        rowMixedAllowSchedule.setOnClickListener {
            if (canEditMixedChannels()) switchMixedAllowSchedule.toggle()
        }
        rowMixedAllowNfc.setOnClickListener {
            if (AutomationModeStore.isNfcSupported(ctx) && canEditMixedChannels()) {
                switchMixedAllowNfc.toggle()
            }
        }
        rowMixedAllowQr.setOnClickListener {
            if (AutomationModeStore.isCameraSupported(ctx) && canEditMixedChannels()) {
                switchMixedAllowQr.toggle()
            }
        }
        rowMixedAllowBarcode.setOnClickListener {
            if (AutomationModeStore.isCameraSupported(ctx) && canEditMixedChannels()) {
                switchMixedAllowBarcode.toggle()
            }
        }
        rowMixedAllowButton.setOnClickListener {
            if (canEditMixedChannels()) switchMixedAllowButton.toggle()
        }
        rowMixedAllowAppPicking.setOnClickListener {
            if (canEditActiveAccess()) switchMixedAllowAppPicking.toggle()
        }
        rowMixedAllowProfileSwitching.setOnClickListener {
            if (canEditActiveAccess()) switchMixedAllowProfileSwitching.toggle()
        }
        rowMixedAllowNfcTagWriting.setOnClickListener {
            if (AutomationModeStore.isNfcSupported(ctx) && canEditActiveAccess()) {
                switchMixedAllowNfcTagWriting.toggle()
            }
        }
        rowLockSwitchlyAppAccess.setOnClickListener {
            if (canEditActiveAccess()) switchLockSwitchlyAppAccess.toggle()
        }
        rowEnablePairedUids.setOnClickListener {
            switchEnablePairedUids.toggle()
        }
        rowAutoPairOnWrite.setOnClickListener {
            switchAutoPairOnWrite.toggle()
        }

        fun markTileAddedIfAccepted(key: String, result: Int) {
            if (result == android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED ||
                result == android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED
            ) {
                sp.edit { putBoolean(key, true) }
                refreshQuickSettingsTileRows()
            }
        }

        // Quick Settings tile rows: tap opens Android add-tile flow directly.
        // Once Android reports the tile as added/already added, the row turns into a disabled
        // "Already added" state so it does not look like a normal on/off setting.
        val addQuickTile: () -> Unit = {
            val requested = QuickTileHelper.requestAddTileIfAvailable(this) { result ->
                markTileAddedIfAccepted(KEY_QS_TILE_REQUESTED, result)
            }
            if (!requested) {
                Snackbar.make(
                    findViewById(android.R.id.content),
                    getString(R.string.qs_tile_add_hint),
                    Snackbar.LENGTH_LONG
                ).show()
            }
        }
        val requestQrQuickTile: () -> Unit = {
            val requested = QuickTileHelper.requestAddQrScanTileIfAvailable(this) { result ->
                markTileAddedIfAccepted(KEY_QR_QS_TILE_REQUESTED, result)
            }
            if (!requested) {
                Snackbar.make(
                    findViewById(android.R.id.content),
                    getString(R.string.qr_qs_tile_add_hint),
                    Snackbar.LENGTH_LONG
                ).show()
            }
        }

        val requestBarcodeQuickTile: () -> Unit = {
            val requested = QuickTileHelper.requestAddBarcodeScanTileIfAvailable(this) { result ->
                markTileAddedIfAccepted(KEY_BARCODE_QS_TILE_REQUESTED, result)
            }
            if (!requested) {
                Snackbar.make(
                    findViewById(android.R.id.content),
                    getString(R.string.barcode_qs_tile_add_hint),
                    Snackbar.LENGTH_LONG
                ).show()
            }
        }

        fun requestPinWidget(providerClass: Class<*>) {
            val manager = getSystemService(APPWIDGET_SERVICE) as? AppWidgetManager
            if (manager?.isRequestPinAppWidgetSupported != true) {
                Snackbar.make(
                    findViewById(android.R.id.content),
                    getString(R.string.widget_pin_not_supported),
                    Snackbar.LENGTH_LONG
                ).show()
                return
            }

            val provider = ComponentName(this, providerClass)
            manager.requestPinAppWidget(provider, null, null)
            Snackbar.make(
                findViewById(android.R.id.content),
                getString(R.string.widget_pin_requested),
                Snackbar.LENGTH_SHORT
            ).show()
        }

        rowQuickTile.setOnClickListener {
            if (!isQuickSettingsTileMarkedAdded(KEY_QS_TILE_REQUESTED)) addQuickTile()
        }
        rowQrQuickTile.setOnClickListener {
            if (!isQuickSettingsTileMarkedAdded(KEY_QR_QS_TILE_REQUESTED)) requestQrQuickTile()
        }
        rowBarcodeQuickTile.setOnClickListener {
            if (!isQuickSettingsTileMarkedAdded(KEY_BARCODE_QS_TILE_REQUESTED)) requestBarcodeQuickTile()
        }
        refreshQuickSettingsTileRows()

        rowWidgetBarcode.setOnClickListener { requestPinWidget(BarcodeScanWidgetProvider::class.java) }
        rowWidgetQr.setOnClickListener { requestPinWidget(QrScanWidgetProvider::class.java) }
        rowWidgetNextSchedule.setOnClickListener { requestPinWidget(NextScheduleWidgetProvider::class.java) }
        rowWidgetActiveTimer.setOnClickListener { requestPinWidget(ActiveTimerWidgetProvider::class.java) }
        rowWidgetBlockedNotifications.setOnClickListener { requestPinWidget(BlockedNotificationsWidgetProvider::class.java) }

        rowBlockNotifs.setOnClickListener { switchBlockNotifications.toggle() }
        rowAutostart.setOnClickListener {
            if (canEditActiveAccess()) switchAutostart.toggle()
        }
        rowEmergency.setOnClickListener {
            if (canEditActiveAccess()) switchEmergency.toggle()
        }
        rowShowQuickActions.setOnClickListener { switchShowQuickActions.toggle() }
        rowShowTemporaryMode.setOnClickListener {
            if (canEditActiveAccess()) switchShowTemporaryMode.toggle()
        }
        findViewById<View>(R.id.rowShowActiveDuration).setOnClickListener {
            if (canEditActiveAccess()) switchShowActiveDuration.toggle()
        }
        rowShowEmergencyUnlock.setOnClickListener {
            if (canEditActiveAccess()) switchShowEmergencyUnlock.toggle()
        }
        rowHomeLayoutDetailed.setOnClickListener {
            showHomeLayoutModeDialog()
        }
        rowHomeLayoutCustom.setOnClickListener {
            showCustomizeHomeDialog()
        }
        if (intent.getBooleanExtra(EXTRA_OPEN_HOME_MODE_DIALOG, false)) {
            rowHomeLayoutDetailed.post { showHomeLayoutModeDialog() }
        }

        switchAllowButtonEnable.setOnCheckedChangeListener { buttonView, isChecked ->
            if (ignoreMixedChannelListener) return@setOnCheckedChangeListener
            if (!canEditMixedChannels()) {
                ignoreMixedChannelListener = true
                buttonView.isChecked = !isChecked
                ignoreMixedChannelListener = false
                return@setOnCheckedChangeListener
            }
            AutomationModeStore.setButtonEnableAllowed(ctx, isChecked)
        }

        // Mixed mode channel toggles
        switchMixedAllowSchedule.setOnCheckedChangeListener { buttonView, isChecked ->
            if (ignoreMixedChannelListener) return@setOnCheckedChangeListener
            if (!canEditMixedChannels()) {
                ignoreMixedChannelListener = true
                buttonView.isChecked = !isChecked
                ignoreMixedChannelListener = false
                return@setOnCheckedChangeListener
            }
            AutomationModeStore.setMixedAllowSchedule(ctx, isChecked)
            refreshMixedChannelInteractivity()
        }

        switchMixedAllowNfc.setOnCheckedChangeListener { buttonView, isChecked ->
            if (ignoreMixedChannelListener) return@setOnCheckedChangeListener
            if (!canEditMixedChannels()) {
                ignoreMixedChannelListener = true
                buttonView.isChecked = !isChecked
                ignoreMixedChannelListener = false
                return@setOnCheckedChangeListener
            }
            AutomationModeStore.setMixedAllowNfc(ctx, isChecked)
            refreshNfcLockedHint()
            refreshMixedChannelInteractivity()
        }

        switchMixedAllowQr.setOnCheckedChangeListener { buttonView, isChecked ->
            if (ignoreMixedChannelListener) return@setOnCheckedChangeListener
            if (!canEditMixedChannels()) {
                ignoreMixedChannelListener = true
                buttonView.isChecked = !isChecked
                ignoreMixedChannelListener = false
                return@setOnCheckedChangeListener
            }
            AutomationModeStore.setMixedAllowQr(ctx, isChecked)
            invalidateOptionsMenu()
        }

        switchMixedAllowBarcode.setOnCheckedChangeListener { buttonView, isChecked ->
            if (ignoreMixedChannelListener) return@setOnCheckedChangeListener
            if (!canEditMixedChannels()) {
                ignoreMixedChannelListener = true
                buttonView.isChecked = !isChecked
                ignoreMixedChannelListener = false
                return@setOnCheckedChangeListener
            }
            AutomationModeStore.setMixedAllowBarcode(ctx, isChecked)
            invalidateOptionsMenu()
        }

        switchMixedAllowButton.setOnCheckedChangeListener { buttonView, isChecked ->
            if (ignoreMixedChannelListener) return@setOnCheckedChangeListener
            if (!canEditMixedChannels()) {
                ignoreMixedChannelListener = true
                buttonView.isChecked = !isChecked
                ignoreMixedChannelListener = false
                return@setOnCheckedChangeListener
            }
            AutomationModeStore.setMixedAllowButton(ctx, isChecked)
            refreshMixedChannelInteractivity()
        }

        switchMixedAllowAppPicking.setOnCheckedChangeListener { buttonView, isChecked ->
            if (ignoreMixedChannelListener) return@setOnCheckedChangeListener
            if (!canEditMixedChannels()) {
                ignoreMixedChannelListener = true
                buttonView.isChecked = !isChecked
                ignoreMixedChannelListener = false
                return@setOnCheckedChangeListener
            }
            AutomationModeStore.setMixedAllowAppPicking(ctx, isChecked)
        }

        switchMixedAllowProfileSwitching.setOnCheckedChangeListener { buttonView, isChecked ->
            if (ignoreMixedChannelListener) return@setOnCheckedChangeListener
            if (!canEditMixedChannels()) {
                ignoreMixedChannelListener = true
                buttonView.isChecked = !isChecked
                ignoreMixedChannelListener = false
                return@setOnCheckedChangeListener
            }
            AutomationModeStore.setMixedAllowProfileSwitching(ctx, isChecked)
        }

        switchMixedAllowNfcTagWriting.setOnCheckedChangeListener { buttonView, isChecked ->
            if (ignoreMixedChannelListener) return@setOnCheckedChangeListener
            if (!canEditMixedChannels()) {
                ignoreMixedChannelListener = true
                buttonView.isChecked = !isChecked
                ignoreMixedChannelListener = false
                return@setOnCheckedChangeListener
            }
            AutomationModeStore.setMixedAllowNfcTagWriting(ctx, isChecked)
        }

        switchLockSwitchlyAppAccess.setOnCheckedChangeListener { buttonView, isChecked ->
            if (updatingUi) return@setOnCheckedChangeListener
            if (!canEditActiveAccess()) {
                updatingUi = true
                buttonView.isChecked = !isChecked
                updatingUi = false
                return@setOnCheckedChangeListener
            }
            AutomationModeStore.setSwitchlyAppAccessLockEnabled(ctx, isChecked)
        }

        // Autostart
        switchAutostart.setOnCheckedChangeListener { buttonView, isChecked ->
            if (updatingUi) return@setOnCheckedChangeListener
            if (!canEditActiveAccess()) {
                updatingUi = true
                buttonView.isChecked = !isChecked
                updatingUi = false
                return@setOnCheckedChangeListener
            }
            AutostartStore.setEnabled(ctx, isChecked)
        }

        // Block notifications
        switchBlockNotifications.setOnCheckedChangeListener { _, isChecked ->
            NotificationBlockStore.setEnabled(ctx, isChecked)

            if (isChecked && !NotificationBlockStore.hasListenerAccess(ctx)) {
                Snackbar.make(
                    findViewById(android.R.id.content),
                    getString(R.string.toast_notification_listener_required),
                    Snackbar.LENGTH_LONG
                )
                    .setAction(R.string.permissions_btn_permissions) {
                        runCatching {
                            startActivity(Intent(ctx, PermissionsActivity::class.java))
                        }.onFailure {
                            runCatching {
                                startActivity(Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                            }
                        }
                    }
                    .show()
            }
        }

        // Emergency unlock
        switchEmergency.setOnCheckedChangeListener { buttonView, isChecked ->
            if (updatingUi) return@setOnCheckedChangeListener
            if (!canEditActiveAccess()) {
                updatingUi = true
                buttonView.isChecked = !isChecked
                updatingUi = false
                return@setOnCheckedChangeListener
            }
            EmergencyBypassStore.setFeatureEnabled(ctx, isChecked)
        }

        // Blocking master toggles
        switchEnablePairedUids.setOnCheckedChangeListener { buttonView, isChecked ->
            val supported = AutomationModeStore.isNfcSupported(ctx)
            if (isChecked && !supported) {
                buttonView.isChecked = false
                return@setOnCheckedChangeListener
            }
            sp.edit { putBoolean(BlockingToggleKeys.KEY_ENABLE_PAIRED_UIDS, isChecked && supported) }
        }
        switchAutoPairOnWrite.setOnCheckedChangeListener { buttonView, isChecked ->
            val supported = AutomationModeStore.isNfcSupported(ctx)
            if (isChecked && !supported) {
                buttonView.isChecked = false
                return@setOnCheckedChangeListener
            }
            sp.edit { putBoolean(BlockingToggleKeys.KEY_AUTO_PAIR_ON_WRITE, isChecked && supported) }
        }
        switchShowQuickActions.setOnCheckedChangeListener { _, isChecked ->
            sp.edit { putBoolean(KEY_SHOW_QUICK_ACTIONS, isChecked) }
        }
        switchShowTemporaryMode.setOnCheckedChangeListener { buttonView, isChecked ->
            if (!canEditActiveAccess()) {
                buttonView.isChecked = sp.getBoolean(KEY_SHOW_TEMPORARY_MODE, true)
                return@setOnCheckedChangeListener
            }
            sp.edit { putBoolean(KEY_SHOW_TEMPORARY_MODE, isChecked) }
        }
        switchShowActiveDuration.setOnCheckedChangeListener { buttonView, isChecked ->
            if (!canEditActiveAccess()) {
                buttonView.isChecked = sp.getBoolean(KEY_SHOW_ACTIVE_DURATION, true)
                return@setOnCheckedChangeListener
            }
            sp.edit { putBoolean(KEY_SHOW_ACTIVE_DURATION, isChecked) }
        }
        switchShowEmergencyUnlock.setOnCheckedChangeListener { buttonView, isChecked ->
            if (!canEditActiveAccess()) {
                buttonView.isChecked = sp.getBoolean(KEY_SHOW_EMERGENCY_UNLOCK, true)
                refreshEmergencyFeatureVisibility()
                return@setOnCheckedChangeListener
            }
            sp.edit { putBoolean(KEY_SHOW_EMERGENCY_UNLOCK, isChecked) }
            refreshEmergencyFeatureVisibility()
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                SwitchModeStore.enabledFlow.collect {
                    runOnUiThread {
                        refreshLiveLockUi()
                    }
                }
            }
        }

        if (!requestedSection.isNullOrBlank()) {
            if (intent?.hasExtra(EXTRA_VIEW_SECTION) == true) {
                applySectionFilter(requestedSection)
            } else {
                scrollToRequestedSection(requestedSection)
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    private fun shouldShowScheduleEditing(): Boolean {
        return when (AutomationModeStore.getMode(this)) {
            AutomationModeStore.Mode.SCHEDULE -> true
            AutomationModeStore.Mode.MIXED -> AutomationModeStore.isMixedAllowSchedule(this)
            else -> false
        }
    }

    private fun shouldShowNfcTagWriting(): Boolean {
        return when (AutomationModeStore.getMode(this)) {
            AutomationModeStore.Mode.NFC -> true
            AutomationModeStore.Mode.MIXED -> AutomationModeStore.isMixedAllowNfc(this)
            else -> false
        }
    }

    private fun refreshLiveLockUi() {
        if (isFinishing || isDestroyed) return
        refreshNfcLockedHint()
        runCatching {
            val mode = AutomationModeStore.getMode(this)
            val rowMap = mapOf(
                AutomationModeStore.Mode.SCHEDULE to findViewById<View>(R.id.rowModeSchedule),
                AutomationModeStore.Mode.NFC to findViewById<View>(R.id.rowModeNfc),
                AutomationModeStore.Mode.QR to findViewById<View>(R.id.rowModeQr),
                AutomationModeStore.Mode.BARCODE to findViewById<View>(R.id.rowModeBarcode),
                AutomationModeStore.Mode.MIXED to findViewById<View>(R.id.rowModeMixed),
            )
            val switchMap = mapOf(
                AutomationModeStore.Mode.SCHEDULE to findViewById<SwitchMaterial>(R.id.switchModeSchedule),
                AutomationModeStore.Mode.NFC to findViewById<SwitchMaterial>(R.id.switchModeNfc),
                AutomationModeStore.Mode.QR to findViewById<SwitchMaterial>(R.id.switchModeQr),
                AutomationModeStore.Mode.BARCODE to findViewById<SwitchMaterial>(R.id.switchModeBarcode),
                AutomationModeStore.Mode.MIXED to findViewById<SwitchMaterial>(R.id.switchModeMixed),
            )
            val modeSwitchingAllowed = !SwitchModeStore.isEnabled(this)
            rowMap.forEach { (key, row) ->
                val selected = key == mode
                val supported = AutomationModeStore.isModeSupported(this, key)
                val baseAlpha = if (selected) 1f else 0.82f
                row.isEnabled = modeSwitchingAllowed && supported
                row.isClickable = modeSwitchingAllowed && supported
                row.alpha = when {
                    !supported -> 0.45f
                    modeSwitchingAllowed -> baseAlpha
                    else -> baseAlpha * 0.86f
                }
            }
            switchMap.forEach { (key, sw) ->
                val selected = key == mode
                val supported = AutomationModeStore.isModeSupported(this, key)
                sw.isEnabled = modeSwitchingAllowed && supported
                sw.alpha = when {
                    !supported -> 0.45f
                    selected && modeSwitchingAllowed -> 1f
                    !selected && modeSwitchingAllowed -> 0.8f
                    selected -> 0.58f
                    else -> 0.45f
                }
            }
        }
        refreshMixedChannelInteractivity()
        invalidateOptionsMenu()
        applySwitchAccentTints()
        applyDetailButtonTint()
    }

    override fun onResume() {
        super.onResume()
        if (SwitchlyAppAccessGuard.blockIfLocked(this)) return
        refreshLiveLockUi()
        CustomAccentApplier.applyIfNeeded(this)
    }

    private fun applySwitchAccentTints() {
        val accent = AccentColor.getAccentColorInt(this)
        val onSurface = MaterialColors.getColor(
            this,
            com.google.android.material.R.attr.colorOnSurface,
            Color.DKGRAY
        )

        val thumbTint = ColorStateList(
            arrayOf(
                intArrayOf(android.R.attr.state_checked),
                intArrayOf()
            ),
            intArrayOf(
                accent,
                Color.WHITE
            )
        )

        val trackTint = ColorStateList(
            arrayOf(
                intArrayOf(android.R.attr.state_checked),
                intArrayOf()
            ),
            intArrayOf(
                ColorUtils.setAlphaComponent(accent, 0x66),
                ColorUtils.setAlphaComponent(onSurface, 0x3D)
            )
        )

        accentSwitches.forEach { sw ->
            sw.thumbTintList = thumbTint
            sw.trackTintList = trackTint
        }
    }

    private fun applyDetailButtonTint() {
        detailButtons.forEach {
            btn -> tintInfoIcon(btn)
        }
    }

    private fun addInlineDetailsAction(
        row: View,
        switchView: SwitchMaterial,
        titleRes: Int,
        summaryRes: Int,
        detailsRes: Int? = null
    ) {
        val container = row as? ViewGroup ?: return

        val detailsBtn = ImageButton(this).apply {
            setImageResource(R.drawable.info_24)
            contentDescription = getString(R.string.info)
            setBackgroundResource(android.R.color.transparent)
            this@ToggleOptionsActivity.withStyledAttributes(
                attrs = intArrayOf(android.R.attr.selectableItemBackgroundBorderless)
            ) {
                this@apply.background = getDrawable(0)
            }
            setPadding(dp(7), dp(7), dp(7), dp(7))
            layoutParams = LinearLayout.LayoutParams(dp(34), dp(34)).apply {
                bottomMargin = dp(0)
            }
            tintInfoIcon(this)
            setOnClickListener {
                showDetailDialog(
                    title = getString(titleRes),
                    summary = getString(summaryRes),
                    detail = detailsRes?.let { resId -> getString(resId) }
                )
            }
        }

        val switchIndex = container.indexOfChild(switchView)
        if (switchIndex >= 0) {
            container.removeViewAt(switchIndex)

            val trailingStack = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginStart = dp(0)
                }
            }

            switchView.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = android.view.Gravity.CENTER_HORIZONTAL
            }
            switchView.translationX = 0f

            trailingStack.addView(detailsBtn)
            trailingStack.addView(switchView)
            container.addView(trailingStack, switchIndex)

            detailButtons += detailsBtn
            applyDetailButtonTint()
        }
    }

    private fun showDetailDialog(title: String, summary: String, detail: String? = null) {
        val message = buildString {
            append(summary)
            if (!detail.isNullOrBlank()) {
                append("\n\n")
                append(detail)
            }
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(R.string.ok, null)
            .create()
        dialog.setOnShowListener { dialog.styleSwitchlyDialogButtons() }
        dialog.show()
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private fun refreshNfcLockedHint() {
        val locked = AutomationModeStore.isNfcAllowed(this) &&
            SwitchModeStore.isEnabled(this) &&
            SwitchModeStore.isNfcRequiredForDisable(this)
        cardNfcLockedHint.visibility = if (locked) View.VISIBLE else View.GONE
    }

    private fun isMixedChannelEditingLocked(): Boolean {
        return SwitchModeStore.isEnabled(this)
    }

    private fun isActiveAccessEditingLocked(): Boolean {
        return SwitchModeStore.isEnabled(this)
    }

    private fun canEditMixedChannels(showToast: Boolean = true): Boolean {
        val allowed = !isMixedChannelEditingLocked()
        if (!allowed && showToast) {
            Toast.makeText(
                this,
                getString(R.string.mixed_channels_locked_while_switchly_enabled),
                Toast.LENGTH_SHORT
            ).show()
        }
        return allowed
    }

    private fun canEditActiveAccess(showToast: Boolean = true): Boolean {
        val allowed = !isActiveAccessEditingLocked()
        if (!allowed && showToast) {
            Toast.makeText(
                this,
                getString(R.string.mixed_channels_locked_while_switchly_enabled),
                Toast.LENGTH_SHORT
            ).show()
        }
        return allowed
    }

    private fun isQuickSettingsTileMarkedAdded(key: String): Boolean =
        PreferenceManager.getDefaultSharedPreferences(this).getBoolean(key, false)

    private fun refreshQuickSettingsTileRows() {
        updateQuickSettingsTileRow(
            rowId = R.id.rowQuickTile,
            summaryId = R.id.tvQuickTileSummary,
            chevronId = R.id.ivQuickTileChevron,
            addedKey = KEY_QS_TILE_REQUESTED,
            defaultSummary = R.string.pref_qs_tile_summary,
            supported = true
        )
        updateQuickSettingsTileRow(
            rowId = R.id.rowQrQuickTile,
            summaryId = R.id.tvQrQuickTileSummary,
            chevronId = R.id.ivQrQuickTileChevron,
            addedKey = KEY_QR_QS_TILE_REQUESTED,
            defaultSummary = R.string.pref_qr_qs_tile_summary,
            supported = AutomationModeStore.isCameraSupported(this)
        )
        updateQuickSettingsTileRow(
            rowId = R.id.rowBarcodeQuickTile,
            summaryId = R.id.tvBarcodeQuickTileSummary,
            chevronId = R.id.ivBarcodeQuickTileChevron,
            addedKey = KEY_BARCODE_QS_TILE_REQUESTED,
            defaultSummary = R.string.pref_barcode_qs_tile_summary,
            supported = AutomationModeStore.isCameraSupported(this)
        )
    }

    private fun updateQuickSettingsTileRow(
        rowId: Int,
        summaryId: Int,
        chevronId: Int,
        addedKey: String,
        defaultSummary: Int,
        supported: Boolean
    ) {
        val added = isQuickSettingsTileMarkedAdded(addedKey)
        findViewById<View>(rowId)?.apply {
            isEnabled = supported && !added
            isClickable = supported && !added
            alpha = if (!supported || added) 0.52f else 1f
        }
        findViewById<TextView>(summaryId)?.setText(
            when {
                !supported -> R.string.mode_not_supported_on_device
                added -> R.string.pref_qs_tile_already_added
                else -> defaultSummary
            }
        )
        findViewById<View>(chevronId)?.visibility =
            if (!supported || added) View.GONE else View.VISIBLE
    }

    private fun refreshMixedChannelInteractivity() {
        val mode = AutomationModeStore.getMode(this)
        val mixedLocked = isMixedChannelEditingLocked()

        val isMixed = mode == AutomationModeStore.Mode.MIXED
        val isNfc = mode == AutomationModeStore.Mode.NFC
        val showControlMethods = activeSectionFilter == null || activeSectionFilter == SECTION_BLOCKING
        val showAccessWhileActive = activeSectionFilter == null || activeSectionFilter == SECTION_OTHER

        val scheduleEditingVisible = false
        val nfcTagWritingVisible = showAccessWhileActive && (isNfc || isMixed)
        val hideButtonEnableRow = AutomationModeStore.isMixedAllowButton(this)

        // apply visibility again (safety sync)
        findViewById<View>(R.id.rowMixedAllowSchedule)?.visibility =
            if (showControlMethods && isMixed) View.VISIBLE else View.GONE
        findViewById<View>(R.id.rowMixedAllowNfc)?.visibility =
            if (showControlMethods && isMixed) View.VISIBLE else View.GONE
        findViewById<View>(R.id.rowMixedAllowQr)?.visibility =
            if (showControlMethods && isMixed) View.VISIBLE else View.GONE
        findViewById<View>(R.id.rowMixedAllowBarcode)?.visibility =
            if (showControlMethods && isMixed) View.VISIBLE else View.GONE
        findViewById<View>(R.id.rowMixedAllowButton)?.visibility =
            if (showControlMethods) View.VISIBLE else View.GONE
        findViewById<View>(R.id.rowMixedAllowAppPicking)?.visibility =
            if (showAccessWhileActive) View.VISIBLE else View.GONE
        findViewById<View>(R.id.rowMixedAllowProfileSwitching)?.visibility =
            if (showAccessWhileActive) View.VISIBLE else View.GONE
        findViewById<View>(R.id.dividerAfterMixedAllowProfileSwitching)?.visibility =
            if (showAccessWhileActive) View.VISIBLE else View.GONE
        findViewById<View>(R.id.rowMixedAllowScheduleEditing)?.visibility =
            if (scheduleEditingVisible) View.VISIBLE else View.GONE

        findViewById<View>(R.id.rowMixedAllowNfcTagWriting)?.visibility =
            if (nfcTagWritingVisible) View.VISIBLE else View.GONE

        findViewById<View>(R.id.rowAllowButtonEnable)?.visibility =
            if (showControlMethods && !hideButtonEnableRow) View.VISIBLE else View.GONE

        val activeAccessLocked = isActiveAccessEditingLocked()
        val mixedRowAlpha = if (mixedLocked) 0.68f else 1f
        val mixedSwitchAlpha = if (mixedLocked) 0.58f else 1f
        val activeAccessRowAlpha = if (activeAccessLocked) 0.68f else 1f
        val activeAccessSwitchAlpha = if (activeAccessLocked) 0.58f else 1f

        listOf(
            R.id.rowMixedAllowSchedule,
            R.id.rowMixedAllowNfc,
            R.id.rowMixedAllowQr,
            R.id.rowMixedAllowBarcode,
            R.id.rowMixedAllowButton,
            R.id.rowAllowButtonEnable
        ).forEach {
            findViewById<View>(it)?.alpha = mixedRowAlpha
        }

        listOf(
            R.id.switchMixedAllowSchedule,
            R.id.switchMixedAllowNfc,
            R.id.switchMixedAllowQr,
            R.id.switchMixedAllowBarcode,
            R.id.switchMixedAllowButton,
            R.id.switchAllowButtonEnable
        ).forEach {
            findViewById<SwitchMaterial>(it)?.apply {
                isEnabled = !mixedLocked
                alpha = mixedSwitchAlpha
            }
        }

        listOf(
            R.id.rowMixedAllowAppPicking,
            R.id.rowMixedAllowProfileSwitching,
            R.id.rowMixedAllowScheduleEditing,
            R.id.rowMixedAllowNfcTagWriting,
            R.id.rowLockSwitchlyAppAccess,
            R.id.rowAutostart,
            R.id.rowEmergency,
            R.id.rowShowTemporaryMode,
            R.id.rowShowActiveDuration,
            R.id.rowShowEmergencyUnlock
        ).forEach {
            findViewById<View>(it)?.alpha = activeAccessRowAlpha
        }

        listOf(
            R.id.switchMixedAllowAppPicking,
            R.id.switchMixedAllowProfileSwitching,
            R.id.switchMixedAllowScheduleEditing,
            R.id.switchMixedAllowNfcTagWriting,
            R.id.switchLockSwitchlyAppAccess,
            R.id.switchAutostart,
            R.id.switchEmergency,
            R.id.switchShowTemporaryMode,
            R.id.switchShowActiveDuration,
            R.id.switchShowEmergencyUnlock
        ).forEach {
            findViewById<SwitchMaterial>(it)?.apply {
                isEnabled = !activeAccessLocked
                alpha = activeAccessSwitchAlpha
            }
        }

        applyHardwareCapability(
            rowId = R.id.rowMixedAllowNfc,
            switchId = R.id.switchMixedAllowNfc,
            supported = AutomationModeStore.isNfcSupported(this)
        )
        applyHardwareCapability(
            rowId = R.id.rowMixedAllowQr,
            switchId = R.id.switchMixedAllowQr,
            supported = AutomationModeStore.isCameraSupported(this)
        )
        applyHardwareCapability(
            rowId = R.id.rowMixedAllowBarcode,
            switchId = R.id.switchMixedAllowBarcode,
            supported = AutomationModeStore.isCameraSupported(this)
        )
        applyHardwareCapability(
            rowId = R.id.rowMixedAllowNfcTagWriting,
            switchId = R.id.switchMixedAllowNfcTagWriting,
            supported = AutomationModeStore.isNfcSupported(this)
        )
        applyHardwareCapability(
            rowId = R.id.rowEnablePairedUids,
            switchId = R.id.switchEnablePairedUids,
            supported = AutomationModeStore.isNfcSupported(this)
        )
        applyHardwareCapability(
            rowId = R.id.rowAutoPairOnWrite,
            switchId = R.id.switchAutoPairOnWrite,
            supported = AutomationModeStore.isNfcSupported(this)
        )

        // Quick Settings tile setup rows are safe to change while Switchly is active.
        // They only add/remove Android tiles and do not weaken the current blocking state.
        listOf(
            R.id.rowQrQuickTile,
            R.id.rowBarcodeQuickTile
        ).forEach {
            findViewById<View>(it)?.alpha = 1f
        }
        refreshQuickSettingsTileRows()

        if (activeSectionFilter != null && activeSectionFilter != SECTION_BLOCKING && activeSectionFilter != SECTION_OTHER) {
            findViewById<View>(R.id.cardMixedChannels)?.visibility = View.GONE
            findViewById<View>(R.id.tvMixedChannelsSection)?.visibility = View.GONE
            findViewById<View>(R.id.tvMixedChannelsSectionSummary)?.visibility = View.GONE
            return
        }

        // Card visibility fallback (never empty)
        val hasVisible =
            listOf(
                R.id.rowMixedAllowSchedule,
                R.id.rowMixedAllowNfc,
                R.id.rowMixedAllowQr,
                R.id.rowMixedAllowBarcode,
                R.id.rowMixedAllowButton,
                R.id.rowMixedAllowAppPicking,
                R.id.rowMixedAllowProfileSwitching,
                R.id.rowQrQuickTile,
                R.id.rowBarcodeQuickTile,
                R.id.rowAllowButtonEnable,
                R.id.rowMixedAllowScheduleEditing,
                R.id.rowMixedAllowNfcTagWriting
            ).any { findViewById<View>(it)?.visibility == View.VISIBLE }

        findViewById<View>(R.id.cardMixedChannels)?.visibility =
            if (hasVisible) View.VISIBLE else View.GONE

        findViewById<View>(R.id.tvMixedChannelsSection)?.visibility =
            if (hasVisible) View.VISIBLE else View.GONE
        findViewById<View>(R.id.tvMixedChannelsSectionSummary)?.visibility =
            if (hasVisible) View.VISIBLE else View.GONE

        refreshRowDividers()
    }

    private fun applyHardwareCapability(rowId: Int, switchId: Int, supported: Boolean) {
        if (supported) return

        findViewById<View>(rowId)?.apply {
            isEnabled = false
            isClickable = false
            alpha = 0.45f
        }
        val wasIgnoring = ignoreMixedChannelListener
        ignoreMixedChannelListener = true
        findViewById<SwitchMaterial>(switchId)?.apply {
            isChecked = false
            isEnabled = false
            alpha = 0.45f
        }
        ignoreMixedChannelListener = wasIgnoring
    }

    private fun isRowVisible(viewId: Int): Boolean {
        return findViewById<View>(viewId)?.visibility == View.VISIBLE
    }

    private fun setDividerAfter(dividerId: Int, rowId: Int, vararg followingRowIds: Int) {
        val divider = findViewById<View>(dividerId) ?: return
        val shouldShow = isRowVisible(rowId) && followingRowIds.any { isRowVisible(it) }
        divider.visibility = if (shouldShow) View.VISIBLE else View.GONE
    }

    private fun refreshRowDividers() {
        // Keep section dividers tied to actually visible neighbor rows.
        // This avoids thick/double separators and orphan separators when rows are hidden dynamically.
        setDividerAfter(
            R.id.dividerAfterMixedAllowSchedule,
            R.id.rowMixedAllowSchedule,
            R.id.rowMixedAllowNfc,
            R.id.rowMixedAllowQr,
            R.id.rowMixedAllowBarcode,
            R.id.rowMixedAllowButton,
            R.id.rowMixedAllowAppPicking,
            R.id.rowMixedAllowProfileSwitching,
            R.id.rowMixedAllowScheduleEditing,
            R.id.rowAllowButtonEnable,
            R.id.rowMixedAllowNfcTagWriting
        )
        setDividerAfter(
            R.id.dividerAfterMixedAllowNfc,
            R.id.rowMixedAllowNfc,
            R.id.rowMixedAllowQr,
            R.id.rowMixedAllowBarcode,
            R.id.rowMixedAllowButton,
            R.id.rowMixedAllowAppPicking,
            R.id.rowMixedAllowProfileSwitching,
            R.id.rowMixedAllowScheduleEditing,
            R.id.rowAllowButtonEnable,
            R.id.rowMixedAllowNfcTagWriting
        )
        setDividerAfter(
            R.id.dividerAfterMixedAllowQr,
            R.id.rowMixedAllowQr,
            R.id.rowMixedAllowBarcode,
            R.id.rowMixedAllowButton,
            R.id.rowMixedAllowAppPicking,
            R.id.rowMixedAllowProfileSwitching,
            R.id.rowMixedAllowScheduleEditing,
            R.id.rowAllowButtonEnable,
            R.id.rowMixedAllowNfcTagWriting
        )
        setDividerAfter(
            R.id.dividerAfterMixedAllowBarcode,
            R.id.rowMixedAllowBarcode,
            R.id.rowMixedAllowButton,
            R.id.rowMixedAllowAppPicking,
            R.id.rowMixedAllowProfileSwitching,
            R.id.rowMixedAllowScheduleEditing,
            R.id.rowAllowButtonEnable,
            R.id.rowMixedAllowNfcTagWriting
        )
        setDividerAfter(
            R.id.dividerAfterMixedAllowButton,
            R.id.rowMixedAllowButton,
            R.id.rowMixedAllowAppPicking,
            R.id.rowMixedAllowProfileSwitching,
            R.id.rowMixedAllowScheduleEditing,
            R.id.rowAllowButtonEnable,
            R.id.rowMixedAllowNfcTagWriting
        )
        setDividerAfter(
            R.id.dividerAfterMixedAllowAppPicking,
            R.id.rowMixedAllowAppPicking,
            R.id.rowMixedAllowProfileSwitching,
            R.id.rowMixedAllowScheduleEditing,
            R.id.rowAllowButtonEnable,
            R.id.rowMixedAllowNfcTagWriting
        )
        setDividerAfter(
            R.id.dividerAfterMixedAllowProfileSwitching,
            R.id.rowMixedAllowProfileSwitching,
            R.id.rowMixedAllowScheduleEditing,
            R.id.rowAllowButtonEnable,
            R.id.rowMixedAllowNfcTagWriting
        )
        setDividerAfter(
            R.id.dividerAfterMixedAllowScheduleEditing,
            R.id.rowMixedAllowScheduleEditing,
            R.id.rowAllowButtonEnable,
            R.id.rowMixedAllowNfcTagWriting
        )
        setDividerAfter(
            R.id.dividerAfterAllowButtonEnable,
            R.id.rowAllowButtonEnable,
            R.id.rowMixedAllowNfcTagWriting
        )

        setDividerAfter(
            R.id.dividerAfterLockSwitchlyAppAccess,
            R.id.rowLockSwitchlyAppAccess,
            R.id.rowRequireNfcUnlock,
            R.id.rowEnablePairedUids,
            R.id.rowAutoPairOnWrite,
            R.id.rowQuickTile,
            R.id.rowQrQuickTile,
            R.id.rowBarcodeQuickTile
        )
        setDividerAfter(
            R.id.dividerRequireNfcUnlock,
            R.id.rowRequireNfcUnlock,
            R.id.rowEnablePairedUids,
            R.id.rowAutoPairOnWrite,
            R.id.rowQuickTile,
            R.id.rowQrQuickTile,
            R.id.rowBarcodeQuickTile
        )
        setDividerAfter(
            R.id.dividerAfterEnablePairedUids,
            R.id.rowEnablePairedUids,
            R.id.rowAutoPairOnWrite,
            R.id.rowQuickTile,
            R.id.rowQrQuickTile,
            R.id.rowBarcodeQuickTile
        )
        setDividerAfter(
            R.id.dividerAfterQuickTile,
            R.id.rowQuickTile,
            R.id.rowQrQuickTile,
            R.id.rowBarcodeQuickTile
        )
        setDividerAfter(
            R.id.dividerAfterQrQuickTile,
            R.id.rowQrQuickTile,
            R.id.rowBarcodeQuickTile
        )
        setDividerAfter(
            R.id.dividerAfterBarcodeQuickTile,
            R.id.rowBarcodeQuickTile
        )
    }

    private fun normalizeSection(raw: String?): String? {
        return when (raw?.trim()?.lowercase()) {
            SECTION_BLOCKING -> SECTION_BLOCKING
            SECTION_FEATURES -> SECTION_FEATURES
            SECTION_SAFETY -> SECTION_SAFETY
            SECTION_DISPLAY -> SECTION_DISPLAY
            SECTION_OTHER -> SECTION_OTHER
            else -> null
        }
    }

    private fun applySectionFilter(section: String) {
        val normalized = normalizeSection(section) ?: return
        activeSectionFilter = normalized

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        val titleRes = when (normalized) {
            SECTION_BLOCKING -> R.string.toggle_options_blocking_title
            SECTION_FEATURES -> R.string.toggle_options_features_title
            SECTION_SAFETY -> R.string.toggle_options_safety_title
            SECTION_DISPLAY -> R.string.toggle_options_display_title
            SECTION_OTHER -> R.string.toggle_group_manage_other_blocking_features
            else -> R.string.toggle_options_title
        }
        val title = getString(titleRes)
        toolbar.title = title
        supportActionBar?.title = title

        val allIds = listOf(
            R.id.cardNfcLockedHint,
            R.id.cardGuidedSetupHint,
            R.id.tvManageBlockingModesTitle,
            R.id.tvManageOtherBlockingFeaturesTitle,
            R.id.tvControlModeSection,
            R.id.tvControlModeSectionSummary,
            R.id.cardControlMode,
            R.id.tvMixedChannelsSection,
            R.id.tvMixedChannelsSectionSummary,
            R.id.cardMixedChannels,
            R.id.tvAdditionalFeaturesSection,
            R.id.tvAdditionalFeaturesSectionSummary,
            R.id.cardAdditionalFeatures,
            R.id.tvSafetyControlsSection,
            R.id.tvProtectionSectionSummary,
            R.id.cardSafetyControls,
            R.id.tvDisplayControlsSection,
            R.id.tvUiSectionSummary,
            R.id.tvDisplayHomeSection,
            R.id.tvDisplayHomeSectionSummary,
            R.id.cardDisplayHomeControls,
            R.id.tvDisplayTilesSection,
            R.id.tvDisplayTilesSectionSummary,
            R.id.cardDisplayTileControls,
            R.id.tvDisplayWidgetsSection,
            R.id.tvDisplayWidgetsSectionSummary,
            R.id.cardDisplayWidgetControls
        )

        val showIds = when (normalized) {
            SECTION_BLOCKING -> buildSet {
                add(R.id.cardNfcLockedHint)
                add(R.id.tvControlModeSection)
                add(R.id.tvControlModeSectionSummary)
                add(R.id.cardControlMode)
                add(R.id.tvMixedChannelsSection)
                add(R.id.tvMixedChannelsSectionSummary)
                add(R.id.cardMixedChannels)
            }
            SECTION_FEATURES -> setOf(
                R.id.tvAdditionalFeaturesSection,
                R.id.tvAdditionalFeaturesSectionSummary,
                R.id.cardAdditionalFeatures
            )
            SECTION_SAFETY -> setOf(
                R.id.tvSafetyControlsSection,
                R.id.tvProtectionSectionSummary,
                R.id.cardSafetyControls
            )
            SECTION_DISPLAY -> setOf(
                R.id.tvDisplayTilesSection,
                R.id.tvDisplayTilesSectionSummary,
                R.id.cardDisplayTileControls,
                R.id.tvDisplayWidgetsSection,
                R.id.tvDisplayWidgetsSectionSummary,
                R.id.cardDisplayWidgetControls
            )
            SECTION_OTHER -> setOf(
                R.id.tvMixedChannelsSection,
                R.id.tvMixedChannelsSectionSummary,
                R.id.cardMixedChannels,
                R.id.tvAdditionalFeaturesSection,
                R.id.tvAdditionalFeaturesSectionSummary,
                R.id.cardAdditionalFeatures,
                R.id.tvSafetyControlsSection,
                R.id.tvProtectionSectionSummary,
                R.id.cardSafetyControls
            )
            else -> emptySet()
        }

        allIds.forEach { viewId ->
            findViewById<View>(viewId)?.visibility =
                if (showIds.contains(viewId)) View.VISIBLE else View.GONE
        }

        val topHeaderId = when (normalized) {
            SECTION_BLOCKING -> R.id.tvControlModeSection
            SECTION_FEATURES -> R.id.tvAdditionalFeaturesSection
            SECTION_SAFETY -> R.id.tvSafetyControlsSection
            SECTION_DISPLAY -> R.id.tvDisplayTilesSection
            SECTION_OTHER -> R.id.tvMixedChannelsSection
            else -> null
        }
        topHeaderId?.let { headerId ->
            val header = findViewById<View>(headerId)
            val params = header?.layoutParams as? ViewGroup.MarginLayoutParams
            if (params != null) {
                params.topMargin = dp(0)
                header.layoutParams = params
            }
        }

        val scrollView = findViewById<ScrollView>(R.id.scrollToggleOptions)
        scrollView?.post {
            scrollView.scrollTo(0, 0)
        }
    }

    private fun scrollToRequestedSection(section: String) {
        val anchorId = when (section) {
            SECTION_FEATURES -> R.id.tvAdditionalFeaturesSection
            SECTION_SAFETY -> R.id.tvSafetyControlsSection
            SECTION_DISPLAY -> R.id.tvDisplayTilesSection
            SECTION_BLOCKING -> R.id.tvControlModeSection
            SECTION_OTHER -> R.id.tvMixedChannelsSection
            else -> null
        } ?: return

        val scroll = findViewById<ScrollView>(R.id.scrollToggleOptions) ?: return
        val anchor = findViewById<View>(anchorId) ?: return

        scroll.post {
            val y = (anchor.top - dp(12)).coerceAtLeast(0)
            scroll.smoothScrollTo(0, y)
        }
    }

    private fun tintOutlinedActionButton(button: MaterialButton) {
        val accent = AccentColor.getAccentColorInt(this)
        button.strokeColor = ColorStateList.valueOf(accent)
        button.setTextColor(accent)
        button.iconTint = ColorStateList.valueOf(accent)
        button.rippleColor = ColorStateList.valueOf(ColorUtils.setAlphaComponent(accent, 0x26))
    }

    private fun tintInfoIcon(button: ImageButton) {
        val accent = AccentColor.getAccentColorInt(this)
        ImageViewCompat.setImageTintList(button, ColorStateList.valueOf(accent))
    }

    private fun tintLeadingIcon(row: View, color: Int) {
        val group = row as? ViewGroup ?: return
        fun tintFirstImage(viewGroup: ViewGroup): Boolean {
            for (index in 0 until viewGroup.childCount) {
                val child = viewGroup.getChildAt(index)
                when (child) {
                    is ImageButton -> Unit
                    is ImageView -> {
                        ImageViewCompat.setImageTintList(child, ColorStateList.valueOf(color))
                        return true
                    }
                    is ViewGroup -> if (tintFirstImage(child)) return true
                }
            }
            return false
        }
        tintFirstImage(group)
    }

    companion object {
        const val EXTRA_SCROLL_TO_SECTION = "extra_scroll_to_section"
        const val EXTRA_OPEN_HOME_MODE_DIALOG = "extra_open_home_mode_dialog"
        const val EXTRA_VIEW_SECTION = "extra_view_section"
        const val SECTION_BLOCKING = "blocking"
        const val SECTION_FEATURES = "features"
        const val SECTION_SAFETY = "safety"
        const val SECTION_DISPLAY = "display"
        const val SECTION_OTHER = "other"

        const val KEY_SHOW_NEXT_SCHEDULE = "pref_show_next_schedule"
        const val KEY_SHOW_QUICK_ACTIONS = "pref_show_quick_actions"
        const val KEY_SHOW_TEMPORARY_MODE = "pref_show_temporary_mode"
        const val KEY_SHOW_ACTIVE_DURATION = "pref_show_active_duration"
        const val KEY_SHOW_EMERGENCY_UNLOCK = "pref_show_emergency_unlock"
        const val KEY_HOME_LAYOUT_DETAILED = "home_layout_detailed"
        const val KEY_HOME_LAYOUT_MODE = "home_layout_mode"
        const val HOME_MODE_DEFAULT = "default"
        const val HOME_MODE_SIMPLE = "simple"
        const val HOME_MODE_ADVANCED = "advanced"
        const val HOME_MODE_FOCUS = "focus"
        const val HOME_MODE_CUSTOM = "custom"
        const val KEY_HOME_CUSTOM_ACTIVE_TIMER = "home_custom_active_timer"
        const val KEY_HOME_CUSTOM_MAIN_BUTTON = "home_custom_main_button"
        const val KEY_HOME_CUSTOM_ACTIVE_PROFILE = "home_custom_active_profile"
        const val KEY_HOME_CUSTOM_CONTROL_MODE = "home_custom_control_mode"
        const val KEY_HOME_CUSTOM_PICK_APPS = "home_custom_pick_apps"
        const val KEY_HOME_CUSTOM_TEMPORARY = "home_custom_temporary"
        const val KEY_HOME_CUSTOM_EMERGENCY = "home_custom_emergency"
        const val KEY_HOME_CUSTOM_QUICK_ACTIONS = "home_custom_quick_actions"
        const val KEY_HOME_CUSTOM_NEXT_SCHEDULE = "home_custom_next_schedule"
        const val KEY_HOME_CUSTOM_BLOCKED_NOW = "home_custom_blocked_now"
        const val KEY_HOME_CUSTOM_BLOCKED_APPS = "home_custom_blocked_apps"
        const val KEY_HOME_CUSTOM_PROFILE_DROPDOWN = "home_custom_profile_dropdown"
        const val KEY_HOME_CUSTOM_ORDER = "home_custom_order"
        const val KEY_QS_TILE_REQUESTED = "pref_qs_tile_requested"
        const val KEY_QR_QS_TILE_REQUESTED = "pref_qr_qs_tile_requested"
        const val KEY_BARCODE_QS_TILE_REQUESTED = "pref_barcode_qs_tile_requested"

        fun start(context: Context) {
            context.startActivity(Intent(context, ToggleOptionsActivity::class.java))
        }
    }
}
