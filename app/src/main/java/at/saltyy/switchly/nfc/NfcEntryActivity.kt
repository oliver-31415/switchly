package at.saltyy.switchly.nfc

import android.app.Activity
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.preference.PreferenceManager
import at.saltyy.switchly.R
import at.saltyy.switchly.blocking.BlockingRuntime
import at.saltyy.switchly.data.prefs.AutomationModeStore
import at.saltyy.switchly.data.prefs.BlockingToggleKeys
import at.saltyy.switchly.data.prefs.NfcTempDisableLimiterStore
import at.saltyy.switchly.data.prefs.NfcScanCountStore
import at.saltyy.switchly.data.prefs.NfcUidPairingStore
import at.saltyy.switchly.data.prefs.ProfileStore
import at.saltyy.switchly.data.prefs.SwitchModeStore
import at.saltyy.switchly.data.prefs.TempEnableCountStore
import at.saltyy.switchly.ui.ThemeUtils

/**
 * NFC/deep-link entry point.
 *
 * Supported formats:
 *   switchly://switch/<action>
 *   switchly://profile/<Profile>/<action>
 */
class NfcEntryActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeUtils.applyAccentTheme(this)
        super.onCreate(savedInstanceState)
        handleIncomingIntent(intent)
    }

    override fun onNewIntent(intent: android.content.Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: android.content.Intent?) {
        val tag = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(android.nfc.NfcAdapter.EXTRA_TAG, android.nfc.Tag::class.java)
        } else {
            intent?.getParcelableExtra(android.nfc.NfcAdapter.EXTRA_TAG) as? android.nfc.Tag
        }

        // If an NFC tag parcelable exists, this came from NFC.
        // QR scanner routes here via deep-link without EXTRA_TAG.
        val fromNfc = tag != null
        val fromQr = !fromNfc

        // Respect user-selected control mode.
        if (fromNfc && !AutomationModeStore.isNfcAllowed(this)) {
            toast(getString(R.string.mode_blocked_nfc_action))
            finish()
            return
        }
        if (fromQr) {
            if (!AutomationModeStore.isQrChannelAllowed(this)) {
                toast(getString(R.string.mode_blocked_qr_action))
                finish()
                return
            }
            // Mixed mode allows QR only when QR feature toggle is enabled.
            if (!AutomationModeStore.isQrAllowed(this)) {
                toast(getString(R.string.mode_blocked_qr_mixed_enable_toggle))
                finish()
                return
            }
        }

        // Optional: UID-only paired tag support for NFC source.
        // If one or more tag UIDs are paired, only those exact NFC tags are allowed.
        // QR flow is intentionally not gated by paired UID.
        if (fromNfc) {
            val sp = PreferenceManager.getDefaultSharedPreferences(this)
            val pairedUidsEnabled = sp.getBoolean(BlockingToggleKeys.KEY_ENABLE_PAIRED_UIDS, false)
            if (pairedUidsEnabled) {
                val pairedUids = NfcUidPairingStore.getPairedUidsHex(this)
                if (pairedUids.isNotEmpty()) {
                    val seenUid = NfcTagUid.normalizeUidHex(NfcTagUid.uidHex(tag))
                    if (seenUid.isBlank() || pairedUids.none { it.equals(seenUid, ignoreCase = true) }) {
                        toast(getString(R.string.nfc_action_error_fmt, getString(R.string.nfc_wrong_tag_paired_uid_required)))
                        finish()
                        return
                    }
                }
            }
        }

        val data: Uri? = intent?.data
        if (data == null || !NfcSchema.isKnownHost(data.host)) {
            toast(getString(R.string.nfc_action_error_fmt, getString(R.string.nfc_error_invalid_or_missing_uri)))
            finish()
            return
        }

        NfcScanCountStore.incrementToday(this)

        when (data.host?.lowercase()) {
            NfcSchema.HOST_SWITCH -> handleGlobalAction(data, tag)
            NfcSchema.HOST_PROFILE -> handleProfileAction(data, tag)
            else -> toast(getString(R.string.nfc_action_error_fmt, getString(R.string.nfc_error_unknown_host)))
        }

        finish()
    }

    // -------- GLOBAL ACTIONS --------

    private fun handleGlobalAction(data: Uri, tag: android.nfc.Tag?) {
        val action = data.lastPathSegment?.lowercase() ?: return

        when {
            action == "enable" -> {
                SwitchModeStore.setEnabled(this, true)
                BlockingRuntime.ensureRunning(this)
                toast(getString(R.string.nfc_feedback_started, getString(R.string.app_name)))
            }

            action == "disable" -> {
                SwitchModeStore.setEnabled(this, false, allowNfcBypass = true)
                BlockingRuntime.stop(this)
                toast(getString(R.string.nfc_feedback_stopped, getString(R.string.app_name)))
            }

            action == "toggle" -> {
                val enabled = SwitchModeStore.isEnabled(this)
                if (enabled) {
                    SwitchModeStore.setEnabled(this, false, allowNfcBypass = true)
                    BlockingRuntime.stop(this)
                    toast(getString(R.string.nfc_feedback_stopped, getString(R.string.app_name)))
                } else {
                    SwitchModeStore.setEnabled(this, true)
                    BlockingRuntime.ensureRunning(this)
                    toast(getString(R.string.nfc_feedback_started, getString(R.string.app_name)))
                }
            }

            action.startsWith("temp_enable") -> {
                val durationMs = resolveTempDurationMs(action, prefix = "temp_enable")
                SwitchModeStore.setTemporarilyEnabled(this, durationMs)
                BlockingRuntime.ensureRunning(this)
                TempEnableCountStore.incrementToday(this)
                toast(getString(R.string.nfc_feedback_started, getString(R.string.app_name)))
            }

            action.startsWith("temp_disable") -> {
                if (!consumeTempDisableQuotaIfNeeded(tag)) return
                val durationMs = resolveTempDurationMs(action, prefix = "temp_disable")
                SwitchModeStore.setTemporarilyDisabled(this, durationMs)
                BlockingRuntime.ensureRunning(this)
                toast(getString(R.string.nfc_feedback_stopped, getString(R.string.app_name)))
            }

            action.startsWith("reentry") -> {
                if (!consumeTempDisableQuotaIfNeeded(tag)) return
                val durationMs = resolveTempDurationMs(action, prefix = "reentry")
                SwitchModeStore.setTemporarilyDisabled(this, durationMs)
                BlockingRuntime.ensureRunning(this)
                toast(getString(R.string.nfc_feedback_stopped, getString(R.string.app_name)))
            }

            action.startsWith("emergency_disable") -> {
                toast(getString(R.string.nfc_action_emergency_tag_removed))
            }

            else -> toast(getString(R.string.nfc_action_error_fmt, getString(R.string.nfc_error_unknown_action)))
        }
    }

    // -------- PROFILE ACTIONS --------

    private fun handleProfileAction(data: Uri, tag: android.nfc.Tag?) {
        val segs = data.pathSegments ?: emptyList()
        val profile = segs.getOrNull(0)?.trim().orEmpty()
        val action = segs.getOrNull(1)?.lowercase() ?: "toggle"

        if (profile.isBlank()) {
            toast(getString(R.string.nfc_action_error_fmt, getString(R.string.nfc_error_missing_profile)))
            return
        }

        val allProfiles = ProfileStore.getProfiles(this)
        if (!allProfiles.contains(profile)) {
            toast(getString(R.string.nfc_action_error_fmt, getString(R.string.nfc_error_unknown_profile_fmt, profile)))
            return
        }

        when {
            action in listOf("start", "enable", "on", "activate") -> {
                ProfileStore.setCurrent(this, profile)
                SwitchModeStore.setEnabled(this, true)
                BlockingRuntime.ensureRunning(this)
                toast(getString(R.string.nfc_feedback_started, profile))
            }

            // Profile disable should NOT disable Switchly globally unless that profile is currently active.
            action in listOf("stop", "disable", "off") -> {
                val current = ProfileStore.getCurrent(this)
                if (current == profile) {
                    SwitchModeStore.setEnabled(this, false, allowNfcBypass = true)
                    BlockingRuntime.stop(this)
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
                    toast(getString(R.string.nfc_feedback_started, profile))
                    return
                }

                if (current == profile) {
                    SwitchModeStore.setEnabled(this, false, allowNfcBypass = true)
                    BlockingRuntime.stop(this)
                    toast(getString(R.string.nfc_feedback_stopped, profile))
                } else {
                    ProfileStore.setCurrent(this, profile)
                    BlockingRuntime.ensureRunning(this)
                    toast(getString(R.string.nfc_feedback_started, profile))
                }
            }

            action.startsWith("temp_enable") -> {
                ProfileStore.setCurrent(this, profile)
                val durationMs = resolveTempDurationMs(action, prefix = "temp_enable")
                SwitchModeStore.setTemporarilyEnabled(this, durationMs)
                BlockingRuntime.ensureRunning(this)
                TempEnableCountStore.incrementToday(this)
                toast(getString(R.string.nfc_feedback_started, profile))
            }

            action.startsWith("temp_disable") -> {
                val current = ProfileStore.getCurrent(this)
                if (current == profile) {
                    if (!consumeTempDisableQuotaIfNeeded(tag)) return
                    val durationMs = resolveTempDurationMs(action, prefix = "temp_disable")
                    SwitchModeStore.setTemporarilyDisabled(this, durationMs)
                    BlockingRuntime.ensureRunning(this)
                    toast(getString(R.string.nfc_feedback_stopped, profile))
                } else {
                    toast(getString(R.string.nfc_error_profile_not_active_nothing_to_disable_fmt, profile))
                }
            }

            action.startsWith("reentry") -> {
                val current = ProfileStore.getCurrent(this)
                if (current == profile) {
                    if (!consumeTempDisableQuotaIfNeeded(tag)) return
                    val durationMs = resolveTempDurationMs(action, prefix = "reentry")
                    SwitchModeStore.setTemporarilyDisabled(this, durationMs)
                    BlockingRuntime.ensureRunning(this)
                    toast(getString(R.string.nfc_feedback_stopped, profile))
                } else {
                    toast(getString(R.string.nfc_error_profile_not_active_nothing_to_disable_fmt, profile))
                }
            }

            action.startsWith("emergency_disable") -> {
                toast(getString(R.string.nfc_action_emergency_tag_removed))
            }

            else -> toast(getString(R.string.nfc_action_error_fmt, getString(R.string.nfc_error_unknown_action)))
        }
    }

    private fun consumeTempDisableQuotaIfNeeded(tag: android.nfc.Tag?): Boolean {
        if (tag == null) return true
        if (!NfcTempDisableLimiterStore.isEnabled(this)) return true

        val uidHex = NfcTagUid.normalizeUidHex(NfcTagUid.uidHex(tag))
        val bucket = NfcTempDisableLimiterStore.bucketForUid(uidHex)

        return when (val result = NfcTempDisableLimiterStore.check(bucket, this)) {
            NfcTempDisableLimiterStore.CheckResult.Allowed -> {
                NfcTempDisableLimiterStore.consume(bucket, this)
                true
            }

            is NfcTempDisableLimiterStore.CheckResult.Cooldown -> {
                toast(getString(R.string.nfc_temp_disable_limiter_cooldown, result.minutesRemaining))
                false
            }

            is NfcTempDisableLimiterStore.CheckResult.DailyLimitReached -> {
                toast(resources.getQuantityString(R.plurals.nfc_temp_disable_limiter_daily_limit, result.limit, result.limit))
                false
            }
        }
    }

    /**
     * Parses:
     *  - temp_disable     -> default
     *  - temp_disable10   -> 10 min
     *  - temp_enable      -> default
     *  - temp_enable10    -> 10 min
     *  - reentry          -> default
     *  - reentry10        -> 10 min
     */
    private fun resolveTempDurationMs(action: String, prefix: String): Long {
        val base = NfcSchema.DEFAULT_TEMP_DISABLE_MS
        if (action == prefix) return base

        val suffix = action.removePrefix(prefix).removePrefix("_")
        val minutes = suffix.toLongOrNull() ?: return base
        val clampedMinutes = minutes.coerceIn(1L, 120L)
        return clampedMinutes * 60_000L
    }

    private fun toast(msg: String) {
        Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show()
    }
}
