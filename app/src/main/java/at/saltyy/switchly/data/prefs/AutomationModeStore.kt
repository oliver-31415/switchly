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
    private const val KEY_MIXED_ALLOW_NFC = "automation_mixed_allow_nfc"
    private const val KEY_MIXED_ALLOW_QR = "automation_mixed_allow_qr"
    private const val KEY_MIXED_ALLOW_BARCODE = "automation_mixed_allow_barcode"
    private const val KEY_MIXED_ALLOW_SCHEDULE = "automation_mixed_allow_schedule"
    private const val KEY_MIXED_ALLOW_BUTTON = "automation_mixed_allow_button"
    private const val KEY_MIXED_ALLOW_APP_PICKING = "automation_mixed_allow_app_picking"
    private const val KEY_MIXED_ALLOW_PROFILE_SWITCHING = "automation_mixed_allow_profile_switching"
    private const val KEY_ALLOW_BUTTON_ENABLE = "automation_allow_button_enable"
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

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getMode(context: Context): Mode {
        return Mode.fromRaw(prefs(context).getString(KEY_AUTOMATION_MODE, Mode.MIXED.raw))
    }

    fun setMode(context: Context, mode: Mode) {
        prefs(context).edit(commit = true) { putString(KEY_AUTOMATION_MODE, mode.raw) }
    }

    fun isMixedMode(context: Context): Boolean = getMode(context) == Mode.MIXED

    fun isMixedAllowNfc(context: Context): Boolean =
        getBool(context, KEY_MIXED_ALLOW_NFC, true)

    fun setMixedAllowNfc(context: Context, enabled: Boolean) {
        putBool(context, KEY_MIXED_ALLOW_NFC, enabled)
    }

    fun isMixedAllowQr(context: Context): Boolean =
        getBool(context, KEY_MIXED_ALLOW_QR, true)

    fun setMixedAllowQr(context: Context, enabled: Boolean) {
        putBool(context, KEY_MIXED_ALLOW_QR, enabled)
    }

    fun isMixedAllowBarcode(context: Context): Boolean =
        getBool(context, KEY_MIXED_ALLOW_BARCODE, true)

    fun setMixedAllowBarcode(context: Context, enabled: Boolean) {
        putBool(context, KEY_MIXED_ALLOW_BARCODE, enabled)
    }

    fun isMixedAllowSchedule(context: Context): Boolean =
        getBool(context, KEY_MIXED_ALLOW_SCHEDULE, true)

    fun setMixedAllowSchedule(context: Context, enabled: Boolean) {
        putBool(context, KEY_MIXED_ALLOW_SCHEDULE, enabled)
    }

    fun isMixedAllowTile(context: Context): Boolean = isMixedAllowButton(context)

    fun setMixedAllowTile(context: Context, enabled: Boolean) {
        setMixedAllowButton(context, enabled)
    }

    fun isMixedAllowButton(context: Context): Boolean =
        getBool(context, KEY_MIXED_ALLOW_BUTTON, true)

    fun setMixedAllowButton(context: Context, enabled: Boolean) {
        putBool(context, KEY_MIXED_ALLOW_BUTTON, enabled)
    }

    fun isButtonEnableAllowed(context: Context): Boolean =
        getBool(context, KEY_ALLOW_BUTTON_ENABLE, false)

    fun setButtonEnableAllowed(context: Context, enabled: Boolean) {
        putBool(context, KEY_ALLOW_BUTTON_ENABLE, enabled)
    }

    fun isMixedAllowAppPicking(context: Context): Boolean =
        getBool(context, KEY_MIXED_ALLOW_APP_PICKING, false)

    fun setMixedAllowAppPicking(context: Context, enabled: Boolean) {
        putBool(context, KEY_MIXED_ALLOW_APP_PICKING, enabled)
    }

    fun isMixedAllowProfileSwitching(context: Context): Boolean =
        getBool(context, KEY_MIXED_ALLOW_PROFILE_SWITCHING, false)

    fun setMixedAllowProfileSwitching(context: Context, enabled: Boolean) {
        putBool(context, KEY_MIXED_ALLOW_PROFILE_SWITCHING, enabled)
    }

    fun isMixedAllowScheduleEditing(context: Context): Boolean =
        getBool(context, KEY_MIXED_ALLOW_SCHEDULE_EDITING, false)

    fun setMixedAllowScheduleEditing(context: Context, enabled: Boolean) {
        putBool(context, KEY_MIXED_ALLOW_SCHEDULE_EDITING, enabled)
    }

    fun isMixedAllowNfcTagWriting(context: Context): Boolean =
        getBool(context, KEY_MIXED_ALLOW_NFC_TAG_WRITING, false)

    fun setMixedAllowNfcTagWriting(context: Context, enabled: Boolean) {
        putBool(context, KEY_MIXED_ALLOW_NFC_TAG_WRITING, enabled)
    }

    fun isScheduleAllowed(context: Context): Boolean {
        return when (getMode(context)) {
            Mode.SCHEDULE -> true
            Mode.MIXED -> isMixedAllowSchedule(context)
            Mode.NFC, Mode.QR, Mode.BARCODE -> false
        }
    }

    fun isNfcAllowed(context: Context): Boolean {
        return when (getMode(context)) {
            Mode.NFC -> true
            Mode.MIXED -> isMixedAllowNfc(context)
            Mode.SCHEDULE, Mode.QR, Mode.BARCODE -> false
        }
    }

    /**
     * Pure capability check for QR based only on the selected control mode.
     */
    fun isQrChannelAllowed(context: Context): Boolean {
        return when (getMode(context)) {
            Mode.QR -> true
            Mode.MIXED -> isMixedAllowQr(context)
            Mode.SCHEDULE, Mode.NFC, Mode.BARCODE -> false
        }
    }

    fun isQrAllowed(context: Context): Boolean = isQrChannelAllowed(context)

    fun shouldShowQrTools(context: Context): Boolean = isQrChannelAllowed(context)

    fun isBarcodeChannelAllowed(context: Context): Boolean {
        return when (getMode(context)) {
            Mode.BARCODE -> true
            Mode.MIXED -> isMixedAllowBarcode(context)
            Mode.SCHEDULE, Mode.NFC, Mode.QR -> false
        }
    }

    fun isBarcodeAllowed(context: Context): Boolean = isBarcodeChannelAllowed(context)

    fun shouldShowBarcodeTools(context: Context): Boolean = isBarcodeChannelAllowed(context)

    fun isAnyScanFeatureEnabled(context: Context): Boolean =
        isQrChannelAllowed(context) || isBarcodeChannelAllowed(context)

    /**
     * Tile control follows the same Mixed-mode channel as the manual button.
     */
    fun isTileAllowed(context: Context): Boolean = isButtonAllowed(context)

    /**
     * Manual dashboard button control channel:
     * Only available in Mixed mode and controlled by the dedicated toggle.
     */
    fun isButtonAllowed(context: Context): Boolean {
        return getMode(context) == Mode.MIXED && isMixedAllowButton(context)
    }

    fun canButtonEnable(context: Context): Boolean {
        return isButtonAllowed(context) || isButtonEnableAllowed(context)
    }

    fun isNfcExclusiveControlActive(context: Context): Boolean {
        return when (getMode(context)) {
            Mode.NFC -> true
            Mode.MIXED ->
                isMixedAllowNfc(context) &&
                    !isMixedAllowSchedule(context) &&
                    !isMixedAllowQr(context) &&
                    !isMixedAllowBarcode(context) &&
                    !isMixedAllowButton(context)
            Mode.SCHEDULE, Mode.QR, Mode.BARCODE -> false
        }
    }

    /**
     * Optional exception while Switchly is enabled.
     * Locked by default; can be enabled regardless of the active control mode.
     */
    fun isAppPickerAllowedWhileEnabled(context: Context): Boolean =
        isMixedAllowAppPicking(context)

    /**
     * Optional exception while Switchly is enabled.
     * Locked by default; can be enabled regardless of the active control mode.
     */
    fun isProfileSwitchingAllowedWhileEnabled(context: Context): Boolean =
        isMixedAllowProfileSwitching(context)

    /**
     * Optional exception while Switchly is enabled.
     * Locked by default; can be enabled regardless of the active control mode.
     */
    fun isScheduleEditingAllowedWhileEnabled(context: Context): Boolean =
        isMixedAllowScheduleEditing(context)

    /**
     * Optional exception while Switchly is enabled.
     * Locked by default; can be enabled regardless of the active control mode.
     */
    fun isNfcTagWritingAllowedWhileEnabled(context: Context): Boolean =
        isMixedAllowNfcTagWriting(context)

    /**
     * Optional hard lock for Switchly control surfaces while protection is active.
     * When enabled, settings/control screens are blocked until Switchly is disabled.
     */
    fun isSwitchlyAppAccessLockEnabled(context: Context): Boolean =
        getBool(context, KEY_LOCK_SWITCHLY_APP_ACCESS, false)

    fun setSwitchlyAppAccessLockEnabled(context: Context, enabled: Boolean) {
        putBool(context, KEY_LOCK_SWITCHLY_APP_ACCESS, enabled)
    }

    private fun getBool(context: Context, key: String, defaultValue: Boolean): Boolean {
        return prefs(context).getBoolean(key, defaultValue)
    }

    private fun putBool(context: Context, key: String, value: Boolean) {
        prefs(context).edit(commit = true) { putBoolean(key, value) }
    }
}
