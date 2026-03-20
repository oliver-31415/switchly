package at.saltyy.switchly.data.prefs

import android.content.Context
import androidx.core.content.edit

/**
 * Selects which control channel is allowed to change Switchly automatically.
 *
 * Modes:
 * - SCHEDULE: only schedules can auto-change state
 * - NFC: only NFC can auto-change state
 * - QR: only QR can auto-change state
 * - MIXED: channel-specific toggles decide what is allowed
 */
object AutomationModeStore {

    private const val PREFS = "switchly_prefs"
    private const val KEY_AUTOMATION_MODE = "automation_mode"

    // Mixed-mode channel toggles
    private const val KEY_MIXED_ALLOW_NFC = "automation_mixed_allow_nfc"
    private const val KEY_MIXED_ALLOW_QR = "automation_mixed_allow_qr"
    private const val KEY_MIXED_ALLOW_BARCODE = "automation_mixed_allow_barcode"
    private const val KEY_MIXED_ALLOW_SCHEDULE = "automation_mixed_allow_schedule"
    private const val KEY_MIXED_ALLOW_BUTTON = "automation_mixed_allow_button"
    private const val KEY_MIXED_ALLOW_APP_PICKING = "automation_mixed_allow_app_picking"
    private const val KEY_MIXED_ALLOW_PROFILE_SWITCHING = "automation_mixed_allow_profile_switching"
    private const val KEY_MIXED_ALLOW_SCHEDULE_EDITING = "automation_mixed_allow_schedule_editing"
    private const val KEY_MIXED_ALLOW_NFC_TAG_WRITING = "automation_mixed_allow_nfc_tag_writing"
    private const val KEY_LOCK_SWITCHLY_APP_ACCESS = "pref_lock_switchly_app_access"

    enum class Mode(val raw: String) {
        SCHEDULE("schedule"),
        NFC("nfc"),
        QR("qr"),
        BARCODE("barcode"),
        MIXED("mixed");

        companion object {
            fun fromRaw(raw: String?): Mode {
                return entries.firstOrNull { it.raw.equals(raw, ignoreCase = true) } ?: MIXED
            }
        }
    }

    fun getMode(ctx: Context): Mode {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Mode.fromRaw(sp.getString(KEY_AUTOMATION_MODE, Mode.MIXED.raw))
    }

    fun setMode(ctx: Context, mode: Mode) {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        sp.edit(commit = true) {
            putString(KEY_AUTOMATION_MODE, mode.raw)
        }
    }

    fun isMixedMode(ctx: Context): Boolean = getMode(ctx) == Mode.MIXED

    fun isMixedAllowNfc(ctx: Context): Boolean =
        getBool(ctx, KEY_MIXED_ALLOW_NFC, true)

    fun setMixedAllowNfc(ctx: Context, enabled: Boolean) {
        putBool(ctx, KEY_MIXED_ALLOW_NFC, enabled)
    }

    fun isMixedAllowQr(ctx: Context): Boolean =
        getBool(ctx, KEY_MIXED_ALLOW_QR, true)

    fun setMixedAllowQr(ctx: Context, enabled: Boolean) {
        putBool(ctx, KEY_MIXED_ALLOW_QR, enabled)
    }

    fun isMixedAllowBarcode(ctx: Context): Boolean =
        getBool(ctx, KEY_MIXED_ALLOW_BARCODE, true)

    fun setMixedAllowBarcode(ctx: Context, enabled: Boolean) {
        putBool(ctx, KEY_MIXED_ALLOW_BARCODE, enabled)
    }

    fun isMixedAllowSchedule(ctx: Context): Boolean =
        getBool(ctx, KEY_MIXED_ALLOW_SCHEDULE, true)

    fun setMixedAllowSchedule(ctx: Context, enabled: Boolean) {
        putBool(ctx, KEY_MIXED_ALLOW_SCHEDULE, enabled)
    }

    fun isMixedAllowTile(ctx: Context): Boolean =
        isMixedAllowButton(ctx)

    fun setMixedAllowTile(ctx: Context, enabled: Boolean) {
        setMixedAllowButton(ctx, enabled)
    }

    fun isMixedAllowButton(ctx: Context): Boolean =
        getBool(ctx, KEY_MIXED_ALLOW_BUTTON, true)

    fun setMixedAllowButton(ctx: Context, enabled: Boolean) {
        putBool(ctx, KEY_MIXED_ALLOW_BUTTON, enabled)
    }

    fun isMixedAllowAppPicking(ctx: Context): Boolean =
        getBool(ctx, KEY_MIXED_ALLOW_APP_PICKING, false)

    fun setMixedAllowAppPicking(ctx: Context, enabled: Boolean) {
        putBool(ctx, KEY_MIXED_ALLOW_APP_PICKING, enabled)
    }

    fun isMixedAllowProfileSwitching(ctx: Context): Boolean =
        getBool(ctx, KEY_MIXED_ALLOW_PROFILE_SWITCHING, false)

    fun setMixedAllowProfileSwitching(ctx: Context, enabled: Boolean) {
        putBool(ctx, KEY_MIXED_ALLOW_PROFILE_SWITCHING, enabled)
    }

    fun isMixedAllowScheduleEditing(ctx: Context): Boolean =
        getBool(ctx, KEY_MIXED_ALLOW_SCHEDULE_EDITING, false)

    fun setMixedAllowScheduleEditing(ctx: Context, enabled: Boolean) {
        putBool(ctx, KEY_MIXED_ALLOW_SCHEDULE_EDITING, enabled)
    }

    fun isMixedAllowNfcTagWriting(ctx: Context): Boolean =
        getBool(ctx, KEY_MIXED_ALLOW_NFC_TAG_WRITING, false)

    fun setMixedAllowNfcTagWriting(ctx: Context, enabled: Boolean) {
        putBool(ctx, KEY_MIXED_ALLOW_NFC_TAG_WRITING, enabled)
    }

    fun isScheduleAllowed(ctx: Context): Boolean {
        return when (getMode(ctx)) {
            Mode.SCHEDULE -> true
            Mode.MIXED -> isMixedAllowSchedule(ctx)
            Mode.NFC, Mode.QR, Mode.BARCODE -> false
        }
    }

    fun isNfcAllowed(ctx: Context): Boolean {
        return when (getMode(ctx)) {
            Mode.NFC -> true
            Mode.MIXED -> isMixedAllowNfc(ctx)
            Mode.SCHEDULE, Mode.QR, Mode.BARCODE -> false
        }
    }

    fun isNfcExclusiveControlActive(ctx: Context): Boolean {
        return when (getMode(ctx)) {
            Mode.NFC -> true
            Mode.MIXED ->
                isMixedAllowNfc(ctx) &&
                    !isMixedAllowSchedule(ctx) &&
                    !isMixedAllowQr(ctx) &&
                    !isMixedAllowBarcode(ctx) &&
                    !isMixedAllowButton(ctx)
            Mode.SCHEDULE, Mode.QR, Mode.BARCODE -> false
        }
    }

    /**
     * Pure capability check for QR based only on the selected control mode.
     */
    fun isQrChannelAllowed(ctx: Context): Boolean {
        return when (getMode(ctx)) {
            Mode.QR -> true
            Mode.MIXED -> isMixedAllowQr(ctx)
            Mode.SCHEDULE, Mode.NFC, Mode.BARCODE -> false
        }
    }

    fun isQrAllowed(ctx: Context): Boolean {
        return isQrChannelAllowed(ctx)
    }

    fun shouldShowQrTools(ctx: Context): Boolean {
        return isQrChannelAllowed(ctx)
    }

    fun isBarcodeChannelAllowed(ctx: Context): Boolean {
        return when (getMode(ctx)) {
            Mode.BARCODE -> true
            Mode.MIXED -> isMixedAllowBarcode(ctx)
            Mode.SCHEDULE, Mode.NFC, Mode.QR -> false
        }
    }

    fun isBarcodeAllowed(ctx: Context): Boolean {
        return isBarcodeChannelAllowed(ctx)
    }

    fun shouldShowBarcodeTools(ctx: Context): Boolean {
        return isBarcodeChannelAllowed(ctx)
    }

    fun isAnyScanFeatureEnabled(ctx: Context): Boolean =
        isQrChannelAllowed(ctx) || isBarcodeChannelAllowed(ctx)

    /**
     * Tile control follows the same Mixed-mode channel as the manual button.
     */
    fun isTileAllowed(ctx: Context): Boolean {
        return isButtonAllowed(ctx)
    }

    /**
     * Manual dashboard button control channel:
     * Only available in Mixed mode and controlled by the dedicated toggle.
     */
    fun isButtonAllowed(ctx: Context): Boolean {
        return getMode(ctx) == Mode.MIXED && isMixedAllowButton(ctx)
    }

    /**
     * Optional exception while Switchly is enabled.
     * Locked by default; can be enabled only via Mixed mode channel toggle.
     */
    fun isAppPickerAllowedWhileEnabled(ctx: Context): Boolean {
        return getMode(ctx) == Mode.MIXED && isMixedAllowAppPicking(ctx)
    }

    /**
     * Optional exception while Switchly is enabled.
     * Locked by default; can be enabled only via Mixed mode channel toggle.
     */
    fun isProfileSwitchingAllowedWhileEnabled(ctx: Context): Boolean {
        return getMode(ctx) == Mode.MIXED && isMixedAllowProfileSwitching(ctx)
    }

    /**
     * Optional exception while Switchly is enabled.
     * Locked by default; can be enabled only via Mixed mode channel toggle.
     */
    fun isScheduleEditingAllowedWhileEnabled(ctx: Context): Boolean {
        return getMode(ctx) == Mode.MIXED && isMixedAllowScheduleEditing(ctx)
    }

    /**
     * Optional exception while Switchly is enabled.
     * Locked by default; can be enabled only via Mixed mode channel toggle.
     */
    fun isNfcTagWritingAllowedWhileEnabled(ctx: Context): Boolean {
        return getMode(ctx) == Mode.MIXED && isMixedAllowNfcTagWriting(ctx)
    }

    /**
     * Optional hard lock for Switchly control surfaces while protection is active.
     * When enabled, settings/control screens are blocked until Switchly is disabled.
     */
    fun isSwitchlyAppAccessLockEnabled(ctx: Context): Boolean {
        return getBool(ctx, KEY_LOCK_SWITCHLY_APP_ACCESS, false)
    }

    fun setSwitchlyAppAccessLockEnabled(ctx: Context, enabled: Boolean) {
        putBool(ctx, KEY_LOCK_SWITCHLY_APP_ACCESS, enabled)
    }

    private fun getBool(ctx: Context, key: String, def: Boolean): Boolean {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return sp.getBoolean(key, def)
    }

    private fun putBool(ctx: Context, key: String, value: Boolean) {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        sp.edit(commit = true) { putBoolean(key, value) }
    }
}
