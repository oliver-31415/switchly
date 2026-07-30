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

package at.saltyy.switchly.nfc

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.content.Intent
import android.net.Uri
import android.nfc.NdefMessage
import android.nfc.NfcAdapter
import android.nfc.tech.Ndef
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.IntentCompat
import androidx.core.net.toUri
import androidx.preference.PreferenceManager
import at.saltyy.switchly.R
import at.saltyy.switchly.blocking.BlockingRuntime
import at.saltyy.switchly.data.prefs.AppLogStore
import at.saltyy.switchly.data.prefs.AutomationModeStore
import at.saltyy.switchly.data.prefs.BlockingToggleKeys
import at.saltyy.switchly.data.prefs.NfcDiagnosticsStore
import at.saltyy.switchly.data.prefs.NfcScanCountStore
import at.saltyy.switchly.data.prefs.NfcTempDisableLimiterStore
import at.saltyy.switchly.data.prefs.NfcUidPairingStore
import at.saltyy.switchly.data.prefs.QrTempActionLimiterStore
import at.saltyy.switchly.data.prefs.ScanCodeStore
import at.saltyy.switchly.data.prefs.ProfileStore
import at.saltyy.switchly.data.prefs.SwitchModeStore
import at.saltyy.switchly.data.prefs.TempEnableCountStore
import at.saltyy.switchly.feature.qr.QrScanActivity
import at.saltyy.switchly.theme.AccentColor
import at.saltyy.switchly.ui.ThemeUtils
import at.saltyy.switchly.ui.dialog.Dialogs
import at.saltyy.switchly.ui.dialog.showAccented
import java.util.Locale

/**
 * NFC/deep-link entry point.
 * Supported formats:
 *   Canonical: switchly://action?type=<action>[&duration=<minutes|ask>][&profile=<Profile>]
 *   Legacy: switchly://switch/<action> and switchly://profile/<Profile>/<action>
 */
class NfcEntryActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeUtils.applyAccentTheme(this)
        super.onCreate(savedInstanceState)
        handleIncomingIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        val tag = intent?.let {
            IntentCompat.getParcelableExtra(it, NfcAdapter.EXTRA_TAG, android.nfc.Tag::class.java)
        }

        // If an NFC tag parcelable exists, this came from NFC.
        // QR scanner routes here via deep-link without EXTRA_TAG.
        val fromNfc = tag != null
        val rawScanSource = intent?.getStringExtra(QrScanActivity.EXTRA_SCAN_SOURCE)
        val trustedScanSource = if (!fromNfc && isTrustedInternalScanDispatch(intent, rawScanSource)) {
            rawScanSource
        } else {
            null
        }
        val fromBarcode = trustedScanSource == "barcode"
        val fromQr = trustedScanSource == "qr"

        if (fromNfc) {
            NfcDiagnosticsStore.recordIntentReceived(
                context = this,
                intentAction = intent.action,
                uidHex = NfcTagUid.uidHex(tag),
                techList = tag?.techList?.toList().orEmpty(),
                uri = intent.data?.toString(),
            )
        }

        // NfcEntryActivity is exported for Android's NFC dispatch system.
        // Do not treat arbitrary non-NFC ACTION_VIEW launches as trusted QR/barcode scans.
        // In-app QR/barcode dispatches must carry a one-time token created by Switchly.
        if (!fromNfc && !fromQr && !fromBarcode) {
            toast(
                getString(
                    R.string.nfc_action_error_fmt,
                    getString(R.string.nfc_error_invalid_or_missing_uri)
                )
            )
            finish()
            return
        }

        // Respect user-selected control mode.
        if (fromNfc && !AutomationModeStore.isNfcAllowed(this)) {
            NfcDiagnosticsStore.recordFailure(this, "nfc_channel_disabled")
            toast(getString(R.string.mode_blocked_nfc_action))
            finish()
            return
        }
        if (fromBarcode && !AutomationModeStore.isBarcodeAllowed(this)) {
            toast(getString(R.string.mode_blocked_barcode_action))
            finish()
            return
        }
        if (fromQr && !AutomationModeStore.isQrAllowed(this)) {
            toast(getString(R.string.mode_blocked_qr_action))
            finish()
            return
        }

        val writtenData = extractSwitchlyUri(intent)
        val pairedOverrideData = if (fromNfc) resolvePairedWritableTagAction(tag) else null
        val data: Uri? = pairedOverrideData ?: writtenData
        if (fromNfc && data != null) {
            NfcDiagnosticsStore.recordResolvedUri(this, data.toString())
            if (pairedOverrideData != null) {
                AppLogStore.append(
                    this,
                    "NFC",
                    "Paired writable tag action override uri=$pairedOverrideData",
                )
            }
        }
        val command = NfcSchema.parseCommandUri(data)
        if (data == null || command == null) {
            if (fromNfc) {
                // UID-only paired tags are only used as a fallback for tags without a written Switchly action.
                // Written switchly:// NFC action tags work without pairing as long as NFC control is allowed.
                val sp = PreferenceManager.getDefaultSharedPreferences(this)
                val pairedUidsEnabled = sp.getBoolean(BlockingToggleKeys.KEY_ENABLE_PAIRED_UIDS, false)
                val pairedUids = if (pairedUidsEnabled) NfcUidPairingStore.getEnabledPairedUidsHex(this) else emptySet()
                val seenUid = NfcTagUid.normalizeUidHex(NfcTagUid.uidHex(tag))
                if (pairedUidsEnabled && pairedUids.isNotEmpty() && seenUid.isNotBlank() &&
                    pairedUids.any { it.equals(seenUid, ignoreCase = true) } &&
                    NfcUidPairingStore.supportsUidOnlyAction(this, seenUid)) {
                    NfcScanCountStore.incrementToday(this)
                    val finishAfterHandling = handlePairedUidAction(tag, seenUid)
                    if (finishAfterHandling) {
                        finish()
                    }
                    return
                }

                val reason = when {
                    !pairedUidsEnabled -> "no_switchly_action_paired_tags_disabled"
                    pairedUids.isEmpty() -> "no_switchly_action_no_paired_tags"
                    seenUid.isBlank() -> "no_switchly_action_no_uid"
                    pairedUids.none { it.equals(seenUid, ignoreCase = true) } -> "no_switchly_action_uid_not_paired"
                    else -> "no_switchly_action_uid_action_disabled"
                }
                NfcDiagnosticsStore.recordFailure(this, reason)

                // Ignore unrelated/unknown NFC tags by default.
                finish()
                return
            }

            toast(
                getString(
                    R.string.nfc_action_error_fmt,
                    getString(R.string.nfc_error_invalid_or_missing_uri)
                )
            )
            finish()
            return
        }

        if (fromNfc) {
            NfcScanCountStore.incrementToday(this)
        }

        when (command) {
            is NfcSchema.GlobalCommand -> NfcDiagnosticsStore.recordResolvedAction(this, command.action)
            is NfcSchema.ProfileCommand -> NfcDiagnosticsStore.recordResolvedAction(this, command.action, command.profile)
        }

        val finishAfterHandling = when (command) {
            is NfcSchema.GlobalCommand -> handleGlobalAction(command.action, tag, fromNfc, fromBarcode, data.toString())
            is NfcSchema.ProfileCommand -> handleProfileAction(command.profile, command.action, tag, fromNfc, fromBarcode, data.toString())
        }

        if (finishAfterHandling) finish()
    }

    private fun resolvePairedWritableTagAction(tag: android.nfc.Tag?): Uri? {
        tag ?: return null
        val sp = PreferenceManager.getDefaultSharedPreferences(this)
        if (!sp.getBoolean(BlockingToggleKeys.KEY_ENABLE_PAIRED_UIDS, false)) {
            return null
        }

        val uidHex = NfcTagUid.normalizeUidHex(NfcTagUid.uidHex(tag))
        if (uidHex.isBlank() || uidHex !in NfcUidPairingStore.getEnabledPairedUidsHex(this)) {
            return null
        }

        val meta = NfcUidPairingStore.getTagMeta(this, uidHex)
        if (meta.tagKind != NfcUidPairingStore.TagKind.WRITABLE) {
            return null
        }

        return buildPairedTagActionUri(meta)
    }

    private fun isTrustedInternalScanDispatch(intent: Intent?, source: String?): Boolean {
        if (source != "qr" && source != "barcode") {
            return false
        }
        val token = intent?.getStringExtra(InternalScanDispatchGuard.EXTRA_TOKEN)
        return InternalScanDispatchGuard.consume(this, source, token)
    }

    private fun extractSwitchlyUri(intent: Intent?): Uri? {
        val direct = intent?.data
        if (
            NfcSchema.isSupportedCommandUri(direct)
        ) {
            return direct
        }

        val rawMessages: Array<NdefMessage>? = intent?.let { src ->
            IntentCompat.getParcelableArrayExtra(
                src,
                NfcAdapter.EXTRA_NDEF_MESSAGES,
                NdefMessage::class.java
            )?.filterIsInstance<NdefMessage>()?.toTypedArray()
        }

        rawMessages
            ?.asSequence()
            ?.flatMap { message -> message.records.asSequence() }
            ?.mapNotNull { record ->
                try {
                    record.toUri()
                } catch (_: Throwable) {
                    null
                }
            }
            ?.firstOrNull { uri ->
                NfcSchema.isSupportedCommandUri(uri)
            }
            ?.let { return it }

        val tag = intent?.let {
            IntentCompat.getParcelableExtra(it, NfcAdapter.EXTRA_TAG, android.nfc.Tag::class.java)
        }

        val ndef = tag?.let { Ndef.get(it) } ?: return null
        return try {
            ndef.connect()
            val message = ndef.cachedNdefMessage ?: ndef.ndefMessage
            message
                ?.records
                ?.asSequence()
                ?.mapNotNull { record ->
                    try {
                        record.toUri()
                    } catch (_: Throwable) {
                        null
                    }
                }
                ?.firstOrNull { uri ->
                    NfcSchema.isSupportedCommandUri(uri)
                }
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { ndef.close() }
        }
    }

    private fun buildPairedTagActionUri(meta: NfcUidPairingStore.TagMeta): Uri? {
        val action = when (meta.action) {
            NfcUidPairingStore.PairedTagAction.USE_WRITTEN -> return null
            NfcUidPairingStore.PairedTagAction.TOGGLE -> "toggle"
            NfcUidPairingStore.PairedTagAction.DISABLE -> "disable"
            NfcUidPairingStore.PairedTagAction.ENABLE -> "enable"
            NfcUidPairingStore.PairedTagAction.TEMP_DISABLE -> {
                if (meta.askDurationWhenScanned) {
                    "temp_disable"
                } else {
                    "temp_disable${meta.durationMinutes}"
                }
            }
            NfcUidPairingStore.PairedTagAction.TEMP_ENABLE -> {
                if (meta.askDurationWhenScanned) {
                    "temp_enable"
                } else {
                    "temp_enable${meta.durationMinutes}"
                }
            }
        }
        val rawUri = if (meta.actionProfile.isNullOrBlank()) {
            NfcSchema.uriForGlobalAction(action)
        } else {
            NfcSchema.uriForProfileAction(meta.actionProfile, action)
        }
        return rawUri.toUri()
    }

    private fun handlePairedUidAction(
        tag: android.nfc.Tag?,
        uidHex: String,
    ): Boolean {
        val data = buildPairedTagActionUri(NfcUidPairingStore.getTagMeta(this, uidHex))
            ?: return true
        val command = NfcSchema.parseCommandUri(data) ?: return true

        when (command) {
            is NfcSchema.GlobalCommand -> {
                NfcDiagnosticsStore.recordResolvedAction(this, command.action)
            }
            is NfcSchema.ProfileCommand -> {
                NfcDiagnosticsStore.recordResolvedAction(this, command.action, command.profile)
            }
        }

        return when (command) {
            is NfcSchema.GlobalCommand -> {
                handleGlobalAction(
                    action = command.action,
                    tag = tag,
                    fromNfc = true,
                    fromBarcode = false,
                    rawActionUri = data.toString(),
                )
            }
            is NfcSchema.ProfileCommand -> {
                handleProfileAction(
                    profile = command.profile,
                    action = command.action,
                    tag = tag,
                    fromNfc = true,
                    fromBarcode = false,
                    rawActionUri = data.toString(),
                )
            }
        }
    }

    private fun handleGlobalAction(
        action: String,
        tag: android.nfc.Tag?,
        fromNfc: Boolean,
        fromBarcode: Boolean,
        rawActionUri: String,
    ): Boolean {
        val sourceLogTag = scanSourceLogTag(fromNfc, fromBarcode)
        AppLogStore.append(this, sourceLogTag, "Tag scanned")
        AppLogStore.append(this, sourceLogTag, "Tag resolved action=$action profile=-")

        when {
            action in listOf("start", "enable", "on", "activate") -> {
                SwitchModeStore.setEnabled(this, true)
                BlockingRuntime.ensureRunning(this)
                appendScanActionApplied(sourceLogTag, "enable")
                toast(getString(R.string.nfc_feedback_started, getString(R.string.app_name)))
            }

            action in listOf("stop", "disable", "off") -> {
                if (!SwitchModeStore.isEnabled(this)) {
                    toast(getString(R.string.nfc_feedback_already_stopped, getString(R.string.app_name)))
                    return true
                }
                if (!consumeScanUnlockQuotaIfNeeded(tag, fromNfc, fromBarcode, rawActionUri)) {
                    return true
                }
                SwitchModeStore.setEnabled(this, false, allowNfcBypass = true)
                BlockingRuntime.stop(this)
                appendScanActionApplied(sourceLogTag, "disable")
                toast(getString(R.string.nfc_feedback_stopped, getString(R.string.app_name)))
            }

            action == "toggle" -> {
                val enabled = SwitchModeStore.isEnabled(this)
                if (enabled) {
                    if (!consumeScanUnlockQuotaIfNeeded(tag, fromNfc, fromBarcode, rawActionUri)) {
                        return true
                    }
                    SwitchModeStore.setEnabled(this, false, allowNfcBypass = true)
                    BlockingRuntime.stop(this)
                    appendScanActionApplied(sourceLogTag, "disable", request = "toggle")
                    toast(getString(R.string.nfc_feedback_stopped, getString(R.string.app_name)))
                } else {
                    SwitchModeStore.setEnabled(this, true)
                    BlockingRuntime.ensureRunning(this)
                    appendScanActionApplied(sourceLogTag, "enable", request = "toggle")
                    toast(getString(R.string.nfc_feedback_started, getString(R.string.app_name)))
                }
            }

            action.startsWith("temp_enable") -> {
                if (action == "temp_enable") {
                    showTempEnableDurationDialog(getString(R.string.app_name)) { durationMs ->
                        SwitchModeStore.setTemporarilyEnabled(this, durationMs)
                        BlockingRuntime.ensureRunning(this)
                        TempEnableCountStore.incrementToday(this)
                        appendScanActionApplied(sourceLogTag, "temp_enable", durationMs = durationMs, request = "ask")
                        toast(getString(R.string.nfc_feedback_started, getString(R.string.app_name)))
                    }
                    return false
                }

                val durationMs = resolveTempDurationMs(action, prefix = "temp_enable")
                SwitchModeStore.setTemporarilyEnabled(this, durationMs)
                BlockingRuntime.ensureRunning(this)
                TempEnableCountStore.incrementToday(this)
                appendScanActionApplied(sourceLogTag, "temp_enable", durationMs = durationMs)
                toast(getString(R.string.nfc_feedback_started, getString(R.string.app_name)))
            }

            action.startsWith("temp_disable") -> {
                if (action == "temp_disable") {
                    showTempDurationDialog(
                        label = getString(R.string.app_name),
                        messageRes = R.string.temp_disable_duration_message
                    ) { durationMs ->
                        if (!consumeScanUnlockQuotaIfNeeded(tag, fromNfc, fromBarcode, rawActionUri)) return@showTempDurationDialog
                        SwitchModeStore.setTemporarilyDisabled(this, durationMs)
                        BlockingRuntime.ensureRunning(this)
                        appendScanActionApplied(sourceLogTag, "temp_disable", durationMs = durationMs, request = "ask")
                        toast(getString(R.string.nfc_feedback_stopped, getString(R.string.app_name)))
                    }
                    return false
                }
                if (!consumeScanUnlockQuotaIfNeeded(tag, fromNfc, fromBarcode, rawActionUri)) {
                    return true
                }
                val durationMs = resolveTempDurationMs(action, prefix = "temp_disable")
                SwitchModeStore.setTemporarilyDisabled(this, durationMs)
                BlockingRuntime.ensureRunning(this)
                appendScanActionApplied(sourceLogTag, "temp_disable", durationMs = durationMs)
                toast(getString(R.string.nfc_feedback_stopped, getString(R.string.app_name)))
            }

            action.startsWith("reentry") -> {
                if (!consumeScanUnlockQuotaIfNeeded(tag, fromNfc, fromBarcode, rawActionUri)) {
                    return true
                }
                val durationMs = resolveTempDurationMs(action, prefix = "reentry")
                SwitchModeStore.setTemporarilyDisabled(this, durationMs)
                BlockingRuntime.ensureRunning(this)
                appendScanActionApplied(sourceLogTag, "temp_disable", durationMs = durationMs, request = action)
                toast(getString(R.string.nfc_feedback_stopped, getString(R.string.app_name)))
            }

            action.startsWith("emergency_disable") -> {
                appendScanActionApplied(sourceLogTag, "emergency_disable")
                toast(getString(R.string.nfc_action_emergency_tag_removed))
            }

            else -> {
                if (fromNfc) {
                    NfcDiagnosticsStore.recordFailure(this, "unknown_action")
                }
                AppLogStore.append(this, scanSourceLogTag(fromNfc, fromBarcode), "Action failed reason=unknown_action")
                toast(getString(R.string.nfc_action_error_fmt, getString(R.string.nfc_error_unknown_action)))
            }
        }
        return true
    }

    private fun handleProfileAction(
        profile: String,
        action: String,
        tag: android.nfc.Tag?,
        fromNfc: Boolean,
        fromBarcode: Boolean,
        rawActionUri: String,
    ): Boolean {
        val sourceLogTag = scanSourceLogTag(fromNfc, fromBarcode)
        AppLogStore.append(this, sourceLogTag, "Tag scanned")

        if (profile.isBlank()) {
            if (fromNfc) {
                NfcDiagnosticsStore.recordFailure(this, "missing_profile")
            }
            AppLogStore.append(this, sourceLogTag, "Action failed reason=missing_profile")
            toast(getString(R.string.nfc_action_error_fmt, getString(R.string.nfc_error_missing_profile)))
            return true
        }

        val allProfiles = ProfileStore.getProfiles(this)
        if (!allProfiles.contains(profile)) {
            if (fromNfc) {
                NfcDiagnosticsStore.recordFailure(this, "unknown_profile")
            }
            AppLogStore.append(this, sourceLogTag, "Action failed reason=unknown_profile")
            toast(getString(R.string.nfc_action_error_fmt, getString(R.string.nfc_error_unknown_profile_fmt, profile)))
            return true
        }

        AppLogStore.append(this, sourceLogTag, "Tag resolved action=$action profile=$profile")

        when {
            action in listOf("start", "enable", "on", "activate") -> {
                ProfileStore.setCurrent(this, profile)
                SwitchModeStore.setEnabled(this, true)
                BlockingRuntime.ensureRunning(this)
                appendScanActionApplied(sourceLogTag, action, profile)
                toast(getString(R.string.nfc_feedback_started, profile))
            }

            // Profile disable should NOT disable Switchly globally unless that profile is currently active.
            action in listOf("stop", "disable", "off") -> {
                val current = ProfileStore.getCurrent(this)
                if (current == profile) {
                    if (SwitchModeStore.isEnabled(this) && !consumeScanUnlockQuotaIfNeeded(tag, fromNfc, fromBarcode, rawActionUri)) {
                        return true
                    }
                    SwitchModeStore.setEnabled(this, false, allowNfcBypass = true)
                    BlockingRuntime.stop(this)
                    appendScanActionApplied(sourceLogTag, action, profile)
                    toast(getString(R.string.nfc_feedback_stopped, profile))
                } else {
                    toast(getString(R.string.nfc_error_profile_not_active_nothing_to_disable_fmt, profile))
                }
            }

            /**
             * Improved toggle semantics:
             * - If Switchly is off -> enable and select this profile
             * - If Switchly is on and this profile is active -> disable
             * - If Switchly is on and another profile is active -> switch profile (keep enabled)
             */
            action == "toggle" -> {
                val enabled = SwitchModeStore.isEnabled(this)
                val current = ProfileStore.getCurrent(this)

                if (!enabled) {
                    ProfileStore.setCurrent(this, profile)
                    SwitchModeStore.setEnabled(this, true)
                    BlockingRuntime.ensureRunning(this)
                    appendScanActionApplied(sourceLogTag, "enable", profile, request = "toggle")
                    toast(getString(R.string.nfc_feedback_started, profile))
                    return true
                }

                if (current == profile) {
                    if (!consumeScanUnlockQuotaIfNeeded(tag, fromNfc, fromBarcode, rawActionUri)) {
                        return true
                    }
                    SwitchModeStore.setEnabled(this, false, allowNfcBypass = true)
                    BlockingRuntime.stop(this)
                    appendScanActionApplied(sourceLogTag, "disable", profile, request = "toggle")
                    toast(getString(R.string.nfc_feedback_stopped, profile))
                } else {
                    ProfileStore.setCurrent(this, profile)
                    BlockingRuntime.ensureRunning(this)
                    appendScanActionApplied(sourceLogTag, "toggle", profile, request = "switch_profile")
                    toast(getString(R.string.nfc_feedback_started, profile))
                }
            }

            action.startsWith("temp_enable") -> {
                val previousProfile = ProfileStore.getCurrent(this)
                if (action == "temp_enable") {
                    showTempEnableDurationDialog(profile) { durationMs ->
                        SwitchModeStore.setTemporarilyEnabled(
                            this,
                            durationMs,
                            previousProfileOverride = previousProfile,
                            targetProfileForLog = profile
                        )
                        ProfileStore.setCurrent(this, profile)
                        BlockingRuntime.ensureRunning(this)
                        TempEnableCountStore.incrementToday(this)
                        appendScanActionApplied(sourceLogTag, action, profile, durationMs = durationMs, request = "ask")
                        toast(getString(R.string.nfc_feedback_started, profile))
                    }
                    return false
                }

                val durationMs = resolveTempDurationMs(action, prefix = "temp_enable")
                SwitchModeStore.setTemporarilyEnabled(
                    this,
                    durationMs,
                    previousProfileOverride = previousProfile,
                    targetProfileForLog = profile
                )
                ProfileStore.setCurrent(this, profile)
                BlockingRuntime.ensureRunning(this)
                TempEnableCountStore.incrementToday(this)
                appendScanActionApplied(sourceLogTag, action, profile, durationMs = durationMs)
                toast(getString(R.string.nfc_feedback_started, profile))
            }

            action.startsWith("temp_disable") -> {
                val current = ProfileStore.getCurrent(this)
                if (current == profile) {
                    if (action == "temp_disable") {
                        showTempDurationDialog(
                            label = profile,
                            messageRes = R.string.temp_disable_duration_message
                        ) { durationMs ->
                            if (!consumeScanUnlockQuotaIfNeeded(tag, fromNfc, fromBarcode, rawActionUri)) return@showTempDurationDialog
                            SwitchModeStore.setTemporarilyDisabled(this, durationMs)
                            BlockingRuntime.ensureRunning(this)
                            appendScanActionApplied(sourceLogTag, action, profile, durationMs = durationMs, request = "ask")
                            toast(getString(R.string.nfc_feedback_stopped, profile))
                        }
                        return false
                    }
                    if (!consumeScanUnlockQuotaIfNeeded(tag, fromNfc, fromBarcode, rawActionUri)) {
                        return true
                    }
                    val durationMs = resolveTempDurationMs(action, prefix = "temp_disable")
                    SwitchModeStore.setTemporarilyDisabled(this, durationMs)
                    BlockingRuntime.ensureRunning(this)
                    appendScanActionApplied(sourceLogTag, "temp_disable", profile, durationMs = durationMs)
                    toast(getString(R.string.nfc_feedback_stopped, profile))
                } else {
                    toast(getString(R.string.nfc_error_profile_not_active_nothing_to_disable_fmt, profile))
                }
            }

            action.startsWith("reentry") -> {
                val current = ProfileStore.getCurrent(this)
                if (current == profile) {
                    if (!consumeScanUnlockQuotaIfNeeded(tag, fromNfc, fromBarcode, rawActionUri)) {
                        return true
                    }
                    val durationMs = resolveTempDurationMs(action, prefix = "reentry")
                    SwitchModeStore.setTemporarilyDisabled(this, durationMs)
                    BlockingRuntime.ensureRunning(this)
                    appendScanActionApplied(sourceLogTag, "temp_disable", profile, durationMs = durationMs, request = action)
                    toast(getString(R.string.nfc_feedback_stopped, profile))
                } else {
                    toast(getString(R.string.nfc_error_profile_not_active_nothing_to_disable_fmt, profile))
                }
            }

            action.startsWith("emergency_disable") -> {
                appendScanActionApplied(sourceLogTag, "emergency_disable", profile)
                toast(getString(R.string.nfc_action_emergency_tag_removed))
            }

            else -> toast(getString(R.string.nfc_action_error_fmt, getString(R.string.nfc_error_unknown_action)))
        }
        return true
    }

    private fun showTempEnableDurationDialog(label: String, applyDuration: (Long) -> Unit) {
        showTempDurationDialog(
            label = label,
            messageRes = R.string.temp_enable_duration_message,
            applyDuration = applyDuration
        )
    }

    private fun showTempDurationDialog(
        label: String,
        messageRes: Int,
        applyDuration: (Long) -> Unit
    ) {
        // Universal temporary actions are meant to be flexible.
        // Fixed durations already exist as explicit actions such as temp_enable15/temp_disable15, so bare temp_enable/temp_disable opens the custom duration input directly.
        showCustomTempDurationDialog(label, messageRes, applyDuration)
    }

    private fun showCustomTempDurationDialog(
        label: String,
        messageRes: Int,
        applyDuration: (Long) -> Unit
    ) {
        val accent = AccentColor.getAccentColorInt(this)
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = getString(R.string.temp_enable_duration_custom_hint)
            isSingleLine = true
            backgroundTintList = ColorStateList.valueOf(accent)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        fun presetChip(minutes: Long): TextView {
            return TextView(this).apply {
                text = getString(R.string.temp_duration_preset_minutes, minutes)
                textSize = 14f
                gravity = Gravity.CENTER
                setTextColor(accent)
                setPadding(dp(9), dp(6), dp(9), dp(6))
                background = GradientDrawable().apply {
                    cornerRadius = dp(18).toFloat()
                    setColor(Color.TRANSPARENT)
                    setStroke(dp(1), accent)
                }
                setOnClickListener {
                    input.setText(String.format(Locale.getDefault(), "%d", minutes))
                    input.setSelection(input.text?.length ?: 0)
                }
            }
        }

        val presetRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        listOf(5L, 10L, 15L, 30L, 60L).forEachIndexed { index, minutes ->
            presetRow.addView(
                presetChip(minutes),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (index > 0) marginStart = dp(4)
                }
            )
        }

        val inputContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val horizontalPadding = dp(24)
            setPadding(horizontalPadding, 0, horizontalPadding, 0)
            addView(TextView(this@NfcEntryActivity).apply {
                text = getString(messageRes, label)
                textSize = 14f
                setLineSpacing(0f, 1.15f)
            })
            addView(TextView(this@NfcEntryActivity).apply {
                text = getString(R.string.temp_duration_quick_presets)
                textSize = 12.5f
                alpha = 0.74f
                setPadding(0, dp(9), 0, dp(4))
            })
            addView(presetRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8)
            })
        }

        val dialog = Dialogs.builder(this)
            .setTitle(getString(R.string.temp_enable_duration_custom_title))
            .setView(inputContainer)
            .setNegativeButton(R.string.cancel) { _, _ -> finish() }
            .setPositiveButton(R.string.ok, null)
            .setOnCancelListener { finish() }
            .showAccented()

        centerTempDurationDialog(dialog)

        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val selectedMinutes = input.text?.toString()?.trim()?.toLongOrNull()
            if (selectedMinutes == null || selectedMinutes !in 1L..1440L) {
                toast(getString(R.string.temp_enable_duration_invalid))
                return@setOnClickListener
            }

            applyDuration(selectedMinutes * 60_000L)
            dialog.dismiss()
            finish()
        }
    }

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value.toFloat(),
        resources.displayMetrics
    ).toInt()

    private fun centerTempDurationDialog(dialog: androidx.appcompat.app.AlertDialog) {
        dialog.window?.let { window ->
            window.setGravity(Gravity.CENTER)
            val attrs = window.attributes
            attrs.gravity = Gravity.CENTER
            attrs.y = 0
            window.attributes = attrs
        }
    }

    private fun appendScanActionApplied(
        sourceLogTag: String,
        action: String,
        profile: String? = null,
        durationMs: Long? = null,
        request: String? = null
    ) {
        val parts = mutableListOf("Action applied", "action=$action")
        if (!profile.isNullOrBlank()) parts += "profile=$profile"
        durationMs?.let { parts += "duration=${it}ms" }
        if (!request.isNullOrBlank()) parts += "request=$request"
        AppLogStore.append(this, sourceLogTag, parts.joinToString(" "))
    }

    private fun scanSourceLogTag(fromNfc: Boolean, fromBarcode: Boolean): String {
        return when {
            fromNfc -> "NFC"
            fromBarcode -> "Barcode"
            else -> "QR"
        }
    }

    private fun consumeScanUnlockQuotaIfNeeded(
        tag: android.nfc.Tag?,
        fromNfc: Boolean,
        fromBarcode: Boolean,
        rawActionUri: String,
    ): Boolean {
        if (!SwitchModeStore.isEnabled(this)) {
            return true
        }

        if (fromNfc) {
            return consumeWrittenNfcTagQuotaIfNeeded(tag, rawActionUri)
        }

        val kind = if (fromBarcode) ScanCodeStore.Kind.BARCODE else ScanCodeStore.Kind.QR
        val managedRawValue = intent?.getStringExtra(EXTRA_MANAGED_SCAN_RAW_VALUE)
        val managedEntry = managedRawValue
            ?.takeIf { it.isNotBlank() }
            ?.let { ScanCodeStore.findEntry(this, kind, it) }
            ?.takeIf { it.dailyLimit != null || it.cooldownMinutes != null }

        val sourceLogTag = if (fromBarcode) "Barcode" else "QR"
        val managedLimitError = managedEntry?.let { ScanCodeStore.checkLimits(this, it) }
        if (managedLimitError != null) {
            AppLogStore.append(this, sourceLogTag, "Unlock limit blocked type=managed reason=$managedLimitError")
            toast(managedLimitError)
            return false
        }

        val usesQrTemporaryLimit = !fromBarcode &&
            QrTempActionLimiterStore.isEnabled(this) &&
            QrTempActionLimiterStore.isLimitedTemporaryAction(rawActionUri)
        val qrLimitResult = if (usesQrTemporaryLimit) {
            QrTempActionLimiterStore.check(rawActionUri, this)
        } else {
            null
        }
        when (qrLimitResult) {
            is QrTempActionLimiterStore.CheckResult.Cooldown -> {
                AppLogStore.append(
                    this,
                    sourceLogTag,
                    "Unlock limit blocked type=qr_temp reason=cooldown remainingMin=${qrLimitResult.minutesRemaining}",
                )
                toast(getString(R.string.qr_temp_limiter_cooldown, qrLimitResult.minutesRemaining))
                return false
            }

            is QrTempActionLimiterStore.CheckResult.DailyLimitReached -> {
                AppLogStore.append(
                    this,
                    sourceLogTag,
                    "Unlock limit blocked type=qr_temp reason=daily used=${qrLimitResult.usedToday} limit=${qrLimitResult.limit}",
                )
                toast(
                    resources.getQuantityString(
                        R.plurals.qr_temp_limiter_daily_limit,
                        qrLimitResult.limit,
                        qrLimitResult.limit,
                    )
                )
                return false
            }

            QrTempActionLimiterStore.CheckResult.Allowed,
            null -> Unit
        }

        managedEntry?.let { ScanCodeStore.consume(this, it) }
        if (usesQrTemporaryLimit) {
            QrTempActionLimiterStore.consume(rawActionUri, this)
        }
        if (managedEntry != null || usesQrTemporaryLimit) {
            AppLogStore.append(
                this,
                sourceLogTag,
                "Unlock limit consumed managed=${managedEntry != null} qrTemp=$usesQrTemporaryLimit",
            )
        }
        return true
    }

    private fun consumeWrittenNfcTagQuotaIfNeeded(
        tag: android.nfc.Tag?,
        rawActionUri: String,
    ): Boolean {
        if (tag == null) {
            return true
        }

        val sp = PreferenceManager.getDefaultSharedPreferences(this)
        val pairedUidsEnabled = sp.getBoolean(BlockingToggleKeys.KEY_ENABLE_PAIRED_UIDS, false)
        if (!pairedUidsEnabled) {
            return true
        }

        val uidHex = NfcTagUid.normalizeUidHex(NfcTagUid.uidHex(tag))
        if (uidHex.isBlank()) {
            return true
        }
        val pairedTagEnabled = uidHex in NfcUidPairingStore.getEnabledPairedUidsHex(this)
        val bucket = NfcTempDisableLimiterStore.bucketForUid(uidHex)
        val action = runCatching {
            NfcSchema.parseCommandUri(rawActionUri.toUri())?.action
        }.getOrNull().orEmpty()
        val globalLimiterEnabled =
            sp.getBoolean(BlockingToggleKeys.KEY_LIMIT_TEMP_DISABLE_TAGS, false) &&
                (action.startsWith("temp_disable") || action.startsWith("reentry"))
        val hasTagConfig = pairedTagEnabled && NfcTempDisableLimiterStore.hasTagConfig(bucket, this)
        if (!globalLimiterEnabled && !hasTagConfig) {
            return true
        }

        return consumeTempActionQuotaBucket(bucket, configuredOnly = !globalLimiterEnabled)
    }

    private fun consumeTempActionQuotaBucket(bucket: String, configuredOnly: Boolean = false): Boolean {
        return when (val result = NfcTempDisableLimiterStore.check(bucket, this, configuredOnly)) {
            NfcTempDisableLimiterStore.CheckResult.Allowed -> {
                NfcTempDisableLimiterStore.consume(bucket, this)
                AppLogStore.append(this, "NFC", "Unlock limit consumed uid=$bucket")
                true
            }

            is NfcTempDisableLimiterStore.CheckResult.Cooldown -> {
                AppLogStore.append(
                    this,
                    "NFC",
                    "Unlock limit blocked uid=$bucket reason=cooldown remainingMin=${result.minutesRemaining}",
                )
                toast(getString(R.string.nfc_temp_disable_limiter_cooldown, result.minutesRemaining))
                false
            }

            is NfcTempDisableLimiterStore.CheckResult.DailyLimitReached -> {
                AppLogStore.append(
                    this,
                    "NFC",
                    "Unlock limit blocked uid=$bucket reason=daily used=${result.usedToday} limit=${result.limit}",
                )
                toast(resources.getQuantityString(R.plurals.nfc_temp_disable_limiter_daily_limit, result.limit, result.limit))
                false
            }
        }
    }

    /**
     * Parses:
     *  - temp_disable     -> ask user when scanned
     *  - temp_disable10   -> 10 min
     *  - temp_enable      -> ask user when scanned
     *  - temp_enable10    -> 10 min
     *  - reentry          -> default
     *  - reentry10        -> 10 min
     */
    private fun resolveTempDurationMs(action: String, prefix: String): Long {
        val base = NfcSchema.DEFAULT_TEMP_DISABLE_MS
        if (action == prefix) {
            return base
        }

        val suffix = action.removePrefix(prefix).removePrefix("_")
        val minutes = suffix.toLongOrNull() ?: return base
        val clampedMinutes = minutes.coerceIn(1L, 1440L)
        return clampedMinutes * 60_000L
    }

    private fun toast(msg: String) {
        Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val EXTRA_MANAGED_SCAN_RAW_VALUE = "extra_managed_scan_raw_value"
    }

}
