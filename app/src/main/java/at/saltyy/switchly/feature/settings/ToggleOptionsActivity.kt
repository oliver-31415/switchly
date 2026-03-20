package at.saltyy.switchly.feature.settings

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.core.content.withStyledAttributes
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.PreferenceManager
import androidx.core.graphics.ColorUtils
import androidx.core.widget.ImageViewCompat
import at.saltyy.switchly.R
import at.saltyy.switchly.blocking.BlockingRuntime
import at.saltyy.switchly.data.prefs.AutostartStore
import at.saltyy.switchly.data.prefs.AutomationModeStore
import at.saltyy.switchly.data.prefs.EmergencyBypassStore
import at.saltyy.switchly.data.prefs.NotificationBlockStore
import at.saltyy.switchly.data.prefs.SwitchModeStore
import at.saltyy.switchly.theme.AccentColor
import at.saltyy.switchly.theme.CustomAccentApplier
import at.saltyy.switchly.ui.dialog.styleSwitchlyDialogButtons
import at.saltyy.switchly.ui.EdgeToEdgeUtils
import at.saltyy.switchly.ui.ThemeUtils
import at.saltyy.switchly.util.LocaleHelper
import at.saltyy.switchly.util.SwitchlyAppAccessGuard
import at.saltyy.switchly.feature.onboarding.QuickTileHelper
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.card.MaterialCardView
import com.google.android.material.button.MaterialButton
import at.saltyy.switchly.data.prefs.BlockingToggleKeys
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class ToggleOptionsActivity : AppCompatActivity() {

    private lateinit var cardNfcLockedHint: MaterialCardView
    private lateinit var btnNfcLockedHowTo: ImageButton
    private lateinit var btnInfoEnablePairedUids: ImageButton
    private lateinit var btnInfoLockSwitchlyAppAccess: ImageButton
    private lateinit var btnInfoLimitTempDisableTags: ImageButton
    private lateinit var tvControlModeLockHint: TextView
    private val accentSwitches = mutableListOf<SwitchMaterial>()
    private val detailButtons = mutableListOf<ImageButton>()
    private var ignoreControlModeListener = false
    private var ignoreMixedChannelListener = false
    private var updatingUi = false

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
        tvControlModeLockHint = findViewById(R.id.tvControlModeLockHint)

        btnInfoEnablePairedUids = findViewById(R.id.btnInfoEnablePairedUids)
        btnInfoLockSwitchlyAppAccess = findViewById(R.id.btnInfoLockSwitchlyAppAccess)
        btnInfoLimitTempDisableTags = findViewById(R.id.btnInfoLimitTempDisableTags)

        // Info icon should follow the selected accent color.
        tintInfoIcon(btnNfcLockedHowTo)
        tintInfoIcon(btnInfoEnablePairedUids)
        tintInfoIcon(btnInfoLockSwitchlyAppAccess)
        tintInfoIcon(btnInfoLimitTempDisableTags)

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
                .setMessage(if (PreferenceManager.getDefaultSharedPreferences(this).getBoolean(BlockingToggleKeys.KEY_ENABLE_PAIRED_UIDS, false)) R.string.toggle_info_paired_uids_body_enabled else R.string.toggle_info_paired_uids_body_disabled)
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
        btnInfoLimitTempDisableTags.setOnClickListener { showLockAndTempInfo() }

        // --- Switches (Control mode)
        val switchModeSchedule = findViewById<SwitchMaterial>(R.id.switchModeSchedule)
        val switchModeNfc = findViewById<SwitchMaterial>(R.id.switchModeNfc)
        val switchModeQr = findViewById<SwitchMaterial>(R.id.switchModeQr)
        val switchModeBarcode = findViewById<SwitchMaterial>(R.id.switchModeBarcode)
        val switchModeMixed = findViewById<SwitchMaterial>(R.id.switchModeMixed)

        // --- Switches (Mixed mode channels)
        val cardMixedChannels = findViewById<MaterialCardView>(R.id.cardMixedChannels)
        val tvMixedChannelsSection = findViewById<View>(R.id.tvMixedChannelsSection)
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
        val switchLimitTempDisableTags = findViewById<SwitchMaterial>(R.id.switchLimitTempDisableTags)

        // --- Switches (Additional features)
        val switchRequireNfcUnlock = findViewById<SwitchMaterial>(R.id.switchRequireNfcUnlock)

        // --- Switches (Protection)
        val switchBlockNotifications = findViewById<SwitchMaterial>(R.id.switchBlockNotifications)
        val switchAutostart = findViewById<SwitchMaterial>(R.id.switchAutostart)

        // --- Switches (UI & Info)
        val switchEmergency = findViewById<SwitchMaterial>(R.id.switchEmergency)
        val switchShowQuickActions = findViewById<SwitchMaterial>(R.id.switchShowQuickActions)

        // --- Switches (In-app)
        val switchEnablePairedUids = findViewById<SwitchMaterial>(R.id.switchEnablePairedUids)

        // Rows clickable
        val rowModeSchedule = findViewById<View>(R.id.rowModeSchedule)
        val rowModeNfc = findViewById<View>(R.id.rowModeNfc)
        val rowModeQr = findViewById<View>(R.id.rowModeQr)
        val rowModeBarcode = findViewById<View>(R.id.rowModeBarcode)
        val rowModeMixed = findViewById<View>(R.id.rowModeMixed)

        val rowMixedAllowSchedule = findViewById<View>(R.id.rowMixedAllowSchedule)
        val rowMixedAllowNfc = findViewById<View>(R.id.rowMixedAllowNfc)
        val rowMixedAllowQr = findViewById<View>(R.id.rowMixedAllowQr)
        val rowMixedAllowBarcode = findViewById<View>(R.id.rowMixedAllowBarcode)
        val rowMixedAllowButton = findViewById<View>(R.id.rowMixedAllowButton)
        val rowMixedAllowAppPicking = findViewById<View>(R.id.rowMixedAllowAppPicking)
        val rowMixedAllowProfileSwitching = findViewById<View>(R.id.rowMixedAllowProfileSwitching)
        val rowMixedAllowScheduleEditing = findViewById<View>(R.id.rowMixedAllowScheduleEditing)
        val rowMixedAllowNfcTagWriting = findViewById<View>(R.id.rowMixedAllowNfcTagWriting)
        val rowLockSwitchlyAppAccess = findViewById<View>(R.id.rowLockSwitchlyAppAccess)
        val rowLimitTempDisableTags = findViewById<View>(R.id.rowLimitTempDisableTags)

        val rowRequireNfcUnlock = findViewById<View>(R.id.rowRequireNfcUnlock)
        val rowQuickTile = findViewById<View>(R.id.rowQuickTile)
        val switchQuickTile = findViewById<SwitchMaterial>(R.id.switchQuickTile)

        val rowBlockNotifs = findViewById<View>(R.id.rowBlockNotifications)
        val rowAutostart = findViewById<View>(R.id.rowAutostart)

        val rowEmergency = findViewById<View>(R.id.rowEmergency)
        val rowShowQuickActions = findViewById<View>(R.id.rowShowQuickActions)
        val rowEnablePairedUids = findViewById<View>(R.id.rowEnablePairedUids)

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
            row = rowMixedAllowScheduleEditing,
            switchView = switchMixedAllowScheduleEditing,
            titleRes = R.string.pref_mixed_allow_schedule_editing_title,
            summaryRes = R.string.pref_mixed_allow_schedule_editing_summary,
            detailsRes = R.string.toggle_detail_mixed_allow_schedule_editing
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
            row = rowLimitTempDisableTags,
            switchView = switchLimitTempDisableTags,
            titleRes = R.string.pref_limit_temp_disable_tags_title,
            summaryRes = R.string.pref_limit_temp_disable_tags_summary,
            detailsRes = R.string.toggle_detail_limit_temp_disable_tags
        )


        addInlineDetailsAction(
            row = rowQuickTile,
            switchView = switchQuickTile,
            titleRes = R.string.pref_qs_tile_title,
            summaryRes = R.string.pref_qs_tile_summary,
            detailsRes = R.string.toggle_detail_quick_tile
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
        accentSwitches.clear()
        accentSwitches += listOf(
            switchModeSchedule,
            switchModeNfc,
            switchModeQr,
            switchModeBarcode,
            switchModeMixed,
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
            switchLimitTempDisableTags,
            switchQuickTile,
            switchBlockNotifications,
            switchAutostart,
            switchEmergency,
            switchShowQuickActions,
        )
        applySwitchAccentTints()

        // -------------------------
        // Initial state
        // -------------------------
        // Deprecated in UI (replaced by granular mixed-channel controls).
        // Keep backend value disabled to avoid stale hard-lock surprises after updates.
        SwitchModeStore.setNfcRequiredForDisable(ctx, false)
        rowRequireNfcUnlock.visibility = View.GONE
        findViewById<View>(R.id.dividerRequireNfcUnlock)?.visibility = View.GONE
        switchRequireNfcUnlock.isChecked = false

        switchMixedAllowSchedule.isChecked = AutomationModeStore.isMixedAllowSchedule(ctx)
        switchMixedAllowNfc.isChecked = AutomationModeStore.isMixedAllowNfc(ctx)
        switchMixedAllowQr.isChecked = AutomationModeStore.isMixedAllowQr(ctx)
        switchMixedAllowBarcode.isChecked = AutomationModeStore.isMixedAllowBarcode(ctx)
        switchMixedAllowButton.isChecked = AutomationModeStore.isMixedAllowButton(ctx)
        switchMixedAllowAppPicking.isChecked = AutomationModeStore.isMixedAllowAppPicking(ctx)
        switchMixedAllowProfileSwitching.isChecked = AutomationModeStore.isMixedAllowProfileSwitching(ctx)
        switchMixedAllowScheduleEditing.isChecked = AutomationModeStore.isMixedAllowScheduleEditing(ctx)
        switchMixedAllowNfcTagWriting.isChecked = AutomationModeStore.isMixedAllowNfcTagWriting(ctx)
        switchLockSwitchlyAppAccess.isChecked = AutomationModeStore.isSwitchlyAppAccessLockEnabled(ctx)
        switchLimitTempDisableTags.isChecked = sp.getBoolean(BlockingToggleKeys.KEY_LIMIT_TEMP_DISABLE_TAGS, false)

        switchBlockNotifications.isChecked = NotificationBlockStore.isEnabled(ctx)
        switchAutostart.isChecked = AutostartStore.isEnabled(ctx)

        switchEmergency.isChecked = EmergencyBypassStore.isFeatureEnabled(ctx)
        switchShowQuickActions.isChecked = sp.getBoolean(KEY_SHOW_QUICK_ACTIONS, true)
        switchEnablePairedUids.isChecked = sp.getBoolean(BlockingToggleKeys.KEY_ENABLE_PAIRED_UIDS, false)

        // Quick tile is not a "real" enable/disable setting (Android doesn't allow removing tiles programmatically).
        // We store whether the user wants the tile/has requested it.
        switchQuickTile.isChecked = sp.getBoolean(KEY_QS_TILE_REQUESTED, false)

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

            ignoreControlModeListener = true
            switchModeSchedule.isChecked = mode == AutomationModeStore.Mode.SCHEDULE
            switchModeNfc.isChecked = mode == AutomationModeStore.Mode.NFC
            switchModeQr.isChecked = mode == AutomationModeStore.Mode.QR
            switchModeBarcode.isChecked = mode == AutomationModeStore.Mode.BARCODE
            switchModeMixed.isChecked = mode == AutomationModeStore.Mode.MIXED
            ignoreControlModeListener = false

            val rowMap = mapOf(
                AutomationModeStore.Mode.SCHEDULE to rowModeSchedule,
                AutomationModeStore.Mode.NFC to rowModeNfc,
                AutomationModeStore.Mode.QR to rowModeQr,
                AutomationModeStore.Mode.BARCODE to rowModeBarcode,
                AutomationModeStore.Mode.MIXED to rowModeMixed,
            )
            val switchMap = mapOf(
                AutomationModeStore.Mode.SCHEDULE to switchModeSchedule,
                AutomationModeStore.Mode.NFC to switchModeNfc,
                AutomationModeStore.Mode.QR to switchModeQr,
                AutomationModeStore.Mode.BARCODE to switchModeBarcode,
                AutomationModeStore.Mode.MIXED to switchModeMixed,
            )

            val modeSwitchingAllowed = canChangeControlMode(showToast = false)

            rowMap.forEach { (key, row) ->
                val selected = key == mode
                val baseAlpha = if (selected) 1f else 0.82f
                row.alpha = if (modeSwitchingAllowed) baseAlpha else (baseAlpha * 0.86f)
            }
            switchMap.forEach { (key, sw) ->
                val selected = key == mode
                sw.isEnabled = modeSwitchingAllowed
                sw.alpha = when {
                    selected && modeSwitchingAllowed -> 1f
                    !selected && modeSwitchingAllowed -> 0.8f
                    selected -> 0.58f
                    else -> 0.45f
                }
            }

            // Channel controls are relevant only for Mixed mode. Hide them otherwise to avoid confusing users.
            val showChannels = mode == AutomationModeStore.Mode.MIXED
            cardMixedChannels.visibility = if (showChannels) View.VISIBLE else View.GONE
            tvMixedChannelsSection.visibility = if (showChannels) View.VISIBLE else View.GONE
            if (showChannels) refreshMixedChannelInteractivity()

            if (userInitiated) {
                Toast.makeText(
                    ctx,
                    getString(R.string.mode_selected_toast_fmt, modeLabel(mode)),
                    Toast.LENGTH_SHORT
                ).show()
            }

            refreshNfcLockedHint()
            refreshControlModeLockHint()
            invalidateOptionsMenu()
        }

        fun onControlModeRowClicked(target: AutomationModeStore.Mode) {
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
        rowMixedAllowSchedule.setOnClickListener {
            if (canEditMixedChannels()) switchMixedAllowSchedule.toggle()
        }
        rowMixedAllowNfc.setOnClickListener {
            if (canEditMixedChannels()) switchMixedAllowNfc.toggle()
        }
        rowMixedAllowQr.setOnClickListener {
            if (canEditMixedChannels()) switchMixedAllowQr.toggle()
        }
        rowMixedAllowBarcode.setOnClickListener {
            if (canEditMixedChannels()) switchMixedAllowBarcode.toggle()
        }
        rowMixedAllowButton.setOnClickListener {
            if (canEditMixedChannels()) switchMixedAllowButton.toggle()
        }
        rowMixedAllowAppPicking.setOnClickListener {
            if (canEditMixedChannels()) switchMixedAllowAppPicking.toggle()
        }
        rowMixedAllowProfileSwitching.setOnClickListener {
            if (canEditMixedChannels()) switchMixedAllowProfileSwitching.toggle()
        }
        rowMixedAllowScheduleEditing.setOnClickListener {
            if (canEditMixedChannels()) switchMixedAllowScheduleEditing.toggle()
        }
        rowMixedAllowNfcTagWriting.setOnClickListener {
            if (canEditMixedChannels()) switchMixedAllowNfcTagWriting.toggle()
        }
        rowLockSwitchlyAppAccess.setOnClickListener { switchLockSwitchlyAppAccess.toggle() }
        rowLimitTempDisableTags.setOnClickListener { switchLimitTempDisableTags.toggle() }
        rowEnablePairedUids.setOnClickListener { switchEnablePairedUids.toggle() }

        // Quick Tile: switch triggers add flow, disabling shows how-to-remove hint
        val addQuickTile: () -> Unit = {
            val requested = QuickTileHelper.requestAddTileIfAvailable(this)
            if (!requested) {
                Snackbar.make(
                    findViewById(android.R.id.content),
                    getString(R.string.qs_tile_add_hint),
                    Snackbar.LENGTH_LONG
                ).show()
            }
        }
        rowQuickTile.setOnClickListener { switchQuickTile.toggle() }

        // Switch behavior
        switchQuickTile.setOnCheckedChangeListener { _, isChecked ->
            sp.edit { putBoolean(KEY_QS_TILE_REQUESTED, isChecked) }

            if (isChecked) {
                addQuickTile()
            } else {
                // Can't remove tiles programmatically -> guide user
                Snackbar.make(
                    findViewById(android.R.id.content),
                    getString(R.string.qs_tile_remove_hint),
                    Snackbar.LENGTH_LONG
                ).show()
            }
        }

        rowBlockNotifs.setOnClickListener { switchBlockNotifications.toggle() }
        rowAutostart.setOnClickListener { switchAutostart.toggle() }

        rowEmergency.setOnClickListener { switchEmergency.toggle() }
        rowShowQuickActions.setOnClickListener { switchShowQuickActions.toggle() }

        // -------------------------
        // Listeners
        // -------------------------

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
        switchMixedAllowScheduleEditing.setOnCheckedChangeListener { buttonView, isChecked ->
            if (ignoreMixedChannelListener) return@setOnCheckedChangeListener
            if (!canEditMixedChannels()) {
                ignoreMixedChannelListener = true
                buttonView.isChecked = !isChecked
                ignoreMixedChannelListener = false
                return@setOnCheckedChangeListener
            }
            AutomationModeStore.setMixedAllowScheduleEditing(ctx, isChecked)
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

        switchLockSwitchlyAppAccess.setOnCheckedChangeListener { _, isChecked ->
            if (updatingUi) return@setOnCheckedChangeListener
            AutomationModeStore.setSwitchlyAppAccessLockEnabled(ctx, isChecked)
        }

        switchLimitTempDisableTags.setOnCheckedChangeListener { _, isChecked ->
            sp.edit { putBoolean(BlockingToggleKeys.KEY_LIMIT_TEMP_DISABLE_TAGS, isChecked) }
        }


        // Autostart
        switchAutostart.setOnCheckedChangeListener { _, isChecked ->
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
        switchEmergency.setOnCheckedChangeListener { _, isChecked ->
            EmergencyBypassStore.setFeatureEnabled(ctx, isChecked)
        }

        // Blocking master toggles
        switchEnablePairedUids.setOnCheckedChangeListener { _, isChecked ->
            sp.edit { putBoolean(BlockingToggleKeys.KEY_ENABLE_PAIRED_UIDS, isChecked) }
        }
        switchShowQuickActions.setOnCheckedChangeListener { _, isChecked ->
            sp.edit { putBoolean(KEY_SHOW_QUICK_ACTIONS, isChecked) }
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
            scrollToRequestedSection(requestedSection)
        }

    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
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
                val baseAlpha = if (selected) 1f else 0.82f
                row.alpha = if (modeSwitchingAllowed) baseAlpha else (baseAlpha * 0.86f)
            }
            switchMap.forEach { (key, sw) ->
                val selected = key == mode
                sw.isEnabled = modeSwitchingAllowed
                sw.alpha = when {
                    selected && modeSwitchingAllowed -> 1f
                    !selected && modeSwitchingAllowed -> 0.8f
                    selected -> 0.58f
                    else -> 0.45f
                }
            }
        }
        refreshControlModeLockHint()
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
                ColorUtils.blendARGB(onSurface, Color.WHITE, 0.65f)
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
        // Keep the inline info icons in the selected accent color.
        detailButtons.forEach { btn ->
            tintInfoIcon(btn)
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
            // No "box" around it, just a ripple.
            setBackgroundResource(android.R.color.transparent)
            this@ToggleOptionsActivity.withStyledAttributes(
                attrs = intArrayOf(android.R.attr.selectableItemBackgroundBorderless)
            ) {
                this@apply.background = getDrawable(0)
            }
            setPadding(dp(10), dp(10), dp(10), dp(10))
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply {
                bottomMargin = dp(2)
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
                    marginStart = dp(2)
                }
            }

            switchView.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = android.view.Gravity.CENTER_HORIZONTAL
            }
            // Optical alignment: Material switch track has slight right-weight visually.
            // Nudge a touch left so it appears centered under the info icon.
            switchView.translationX = -dp(2).toFloat()

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

    private fun refreshControlModeLockHint() {
        val locked = SwitchModeStore.isEnabled(this)
        tvControlModeLockHint.visibility = if (locked) View.VISIBLE else View.GONE
        if (locked) {
            tvControlModeLockHint.text = getString(R.string.control_mode_lock_inline_hint)
        }
    }

    private fun isMixedChannelEditingLocked(): Boolean {
        return SwitchModeStore.isEnabled(this) &&
            AutomationModeStore.getMode(this) == AutomationModeStore.Mode.MIXED
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

    private fun refreshMixedChannelInteractivity() {
        val locked = isMixedChannelEditingLocked()
        val rowAlpha = if (locked) 0.68f else 1f
        val switchAlpha = if (locked) 0.58f else 1f

        listOf(
            R.id.rowMixedAllowSchedule,
            R.id.rowMixedAllowNfc,
            R.id.rowMixedAllowQr,
            R.id.rowMixedAllowBarcode,
            R.id.rowMixedAllowButton,
            R.id.rowMixedAllowAppPicking,
            R.id.rowMixedAllowProfileSwitching,
            R.id.rowMixedAllowScheduleEditing,
            R.id.rowMixedAllowNfcTagWriting
        ).forEach { id ->
            findViewById<View>(id)?.alpha = rowAlpha
        }

        listOf(
            R.id.switchMixedAllowSchedule,
            R.id.switchMixedAllowNfc,
            R.id.switchMixedAllowQr,
            R.id.switchMixedAllowBarcode,
            R.id.switchMixedAllowButton,
            R.id.switchMixedAllowAppPicking,
            R.id.switchMixedAllowProfileSwitching,
            R.id.switchMixedAllowScheduleEditing,
            R.id.switchMixedAllowNfcTagWriting
        ).forEach { id ->
            findViewById<SwitchMaterial>(id)?.let { sw ->
                sw.isEnabled = !locked
                sw.alpha = switchAlpha
            }
        }
    }

    private fun normalizeSection(raw: String?): String? {
        return when (raw?.trim()?.lowercase()) {
            SECTION_BLOCKING -> SECTION_BLOCKING
            SECTION_FEATURES -> SECTION_FEATURES
            SECTION_SAFETY -> SECTION_SAFETY
            SECTION_DISPLAY -> SECTION_DISPLAY
            else -> null
        }
    }

    private fun applySectionFilter(section: String) {
        val normalized = normalizeSection(section) ?: return

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        val titleRes = when (normalized) {
            SECTION_BLOCKING -> R.string.toggle_options_blocking_title
            SECTION_FEATURES -> R.string.toggle_options_features_title
            SECTION_SAFETY -> R.string.toggle_options_safety_title
            SECTION_DISPLAY -> R.string.toggle_options_display_title
            else -> R.string.toggle_options_title
        }
        val title = getString(titleRes)
        toolbar.title = title
        supportActionBar?.title = title

        val allIds = listOf(
            R.id.cardNfcLockedHint,
            R.id.tvControlModeSection,
            R.id.tvControlModeLockHint,
            R.id.cardControlMode,
            R.id.tvMixedChannelsSection,
            R.id.cardMixedChannels,
            R.id.tvAdditionalFeaturesSection,
            R.id.cardAdditionalFeatures,
            R.id.tvSafetyControlsSection,
            R.id.cardSafetyControls,
            R.id.tvDisplayControlsSection,
            R.id.cardDisplayControls
        )

        AutomationModeStore.getMode(this) == AutomationModeStore.Mode.MIXED
        val showIds = when (normalized) {
            SECTION_BLOCKING -> buildSet {
                add(R.id.cardNfcLockedHint)
                add(R.id.tvControlModeSection)
                add(R.id.tvControlModeLockHint)
                add(R.id.cardControlMode)
                add(R.id.tvMixedChannelsSection)
                add(R.id.cardMixedChannels)
            }

            SECTION_FEATURES -> setOf(
                R.id.tvAdditionalFeaturesSection,
                R.id.cardAdditionalFeatures
            )

            SECTION_SAFETY -> setOf(
                R.id.tvSafetyControlsSection,
                R.id.cardSafetyControls
            )

            SECTION_DISPLAY -> setOf(
                R.id.tvDisplayControlsSection,
                R.id.cardDisplayControls
            )

            else -> emptySet()
        }

        allIds.forEach { viewId ->
            findViewById<View>(viewId)?.visibility = if (showIds.contains(viewId)) View.VISIBLE else View.GONE
        }

        val topHeaderId = when (normalized) {
            SECTION_BLOCKING -> R.id.tvControlModeSection
            SECTION_FEATURES -> R.id.tvAdditionalFeaturesSection
            SECTION_SAFETY -> R.id.tvSafetyControlsSection
            SECTION_DISPLAY -> R.id.tvDisplayControlsSection
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

        val scrollView = findViewById<android.widget.ScrollView>(R.id.scrollToggleOptions)
        scrollView?.post {
            scrollView.scrollTo(0, 0)
        }
    }

    private fun scrollToRequestedSection(section: String) {
        val anchorId = when (section) {
            SECTION_FEATURES -> R.id.tvAdditionalFeaturesSection
            SECTION_SAFETY -> R.id.tvSafetyControlsSection
            SECTION_DISPLAY -> R.id.tvDisplayControlsSection
            SECTION_BLOCKING -> R.id.tvControlModeSection
            else -> null
        } ?: return

        val scroll = findViewById<android.widget.ScrollView>(R.id.scrollToggleOptions) ?: return
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

    companion object {
        const val EXTRA_SCROLL_TO_SECTION = "extra_scroll_to_section"
        const val EXTRA_VIEW_SECTION = "extra_view_section"
        const val SECTION_BLOCKING = "blocking"
        const val SECTION_FEATURES = "features"
        const val SECTION_SAFETY = "safety"
        const val SECTION_DISPLAY = "display"

        const val KEY_SHOW_NEXT_SCHEDULE = "pref_show_next_schedule"
        const val KEY_SHOW_QUICK_ACTIONS = "pref_show_quick_actions"
        const val KEY_QS_TILE_REQUESTED = "pref_qs_tile_requested"

        fun start(context: Context) {
            context.startActivity(Intent(context, ToggleOptionsActivity::class.java))
        }
    }
}
