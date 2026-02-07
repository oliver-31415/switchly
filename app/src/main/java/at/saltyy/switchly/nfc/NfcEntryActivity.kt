package at.saltyy.switchly.nfc

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import at.saltyy.switchly.R
import at.saltyy.switchly.blocking.BlockingRuntime
import at.saltyy.switchly.data.prefs.NfcScanCountStore
import at.saltyy.switchly.data.prefs.NfcUidPairingStore
import at.saltyy.switchly.data.prefs.ProfileStore
import at.saltyy.switchly.data.prefs.SwitchModeStore
import at.saltyy.switchly.ui.ThemeUtils

/**
 * NFC / deep-link entry point.
 *
 * Supported formats:
 *   switchly://switch/<action>
 *   switchly://profile/<Profile>/<action>
 */
class NfcEntryActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeUtils.applyAccentTheme(this)
        super.onCreate(savedInstanceState)

        // Optional: UID-only paired tag support
        // If a tag UID is paired, only that exact tag is allowed to trigger NFC actions.
        val pairedUid = NfcUidPairingStore.getPairedUidHex(this)
        if (pairedUid != null) {
            val tag = if (android.os.Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(android.nfc.NfcAdapter.EXTRA_TAG, android.nfc.Tag::class.java)
            } else {
                intent.getParcelableExtra(android.nfc.NfcAdapter.EXTRA_TAG) as? android.nfc.Tag
            }

            val seenUid = NfcTagUid.uidHex(tag)
            if (seenUid == null || !seenUid.equals(pairedUid, ignoreCase = true)) {
                toast(getString(R.string.nfc_action_error_fmt, getString(R.string.nfc_wrong_tag_paired_uid_required)))
                finish()
                return
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
            NfcSchema.HOST_SWITCH -> handleGlobalAction(data)
            NfcSchema.HOST_PROFILE -> handleProfileAction(data)
            else -> toast(getString(R.string.nfc_action_error_fmt, getString(R.string.nfc_error_unknown_host)))
        }

        finish()
    }

    // -------- GLOBAL ACTIONS --------

    private fun handleGlobalAction(data: Uri) {
        val action = data.lastPathSegment?.lowercase() ?: return

        when {
            action == "enable" -> {
                SwitchModeStore.setEnabled(this, true, markManualOverrideWhenRangeActive = false)
                BlockingRuntime.ensureRunning(this)
                toast(getString(R.string.nfc_feedback_started, getString(R.string.app_name)))
            }

            action == "disable" -> {
                SwitchModeStore.setEnabled(this, false, markManualOverrideWhenRangeActive = false)
                BlockingRuntime.stop(this)
                toast(getString(R.string.nfc_feedback_stopped, getString(R.string.app_name)))
            }

            action == "toggle" -> {
                val enabled = SwitchModeStore.isEnabled(this)
                if (enabled) {
                    SwitchModeStore.setEnabled(this, false, markManualOverrideWhenRangeActive = false)
                    BlockingRuntime.stop(this)
                    toast(getString(R.string.nfc_feedback_stopped, getString(R.string.app_name)))
                } else {
                    SwitchModeStore.setEnabled(this, true, markManualOverrideWhenRangeActive = false)
                    BlockingRuntime.ensureRunning(this)
                    toast(getString(R.string.nfc_feedback_started, getString(R.string.app_name)))
                }
            }

            action.startsWith("temp_enable") -> {
                val durationMs = resolveTempDurationMs(action, prefix = "temp_enable")
                SwitchModeStore.setTemporarilyEnabled(this, durationMs)
                BlockingRuntime.ensureRunning(this)
                toast(getString(R.string.nfc_feedback_started, getString(R.string.app_name)))
            }

            action.startsWith("temp_disable") -> {
                val durationMs = resolveTempDurationMs(action, prefix = "temp_disable")
                SwitchModeStore.setTemporarilyDisabled(this, durationMs)
                BlockingRuntime.ensureRunning(this)
                toast(getString(R.string.nfc_feedback_stopped, getString(R.string.app_name)))
            }

            else -> toast(getString(R.string.nfc_action_error_fmt, getString(R.string.nfc_error_unknown_action)))
        }
    }

    // -------- PROFILE ACTIONS --------

    private fun handleProfileAction(data: Uri) {
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
                SwitchModeStore.setEnabled(this, true, markManualOverrideWhenRangeActive = false)
                BlockingRuntime.ensureRunning(this)
                toast(getString(R.string.nfc_feedback_started, profile))
            }

            // Profile disable should NOT disable Switchly globally unless that profile is currently active.
            action in listOf("stop", "disable", "off") -> {
                val current = ProfileStore.getCurrent(this)
                if (current == profile) {
                    SwitchModeStore.setEnabled(this, false, markManualOverrideWhenRangeActive = false)
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
                    SwitchModeStore.setEnabled(this, true, markManualOverrideWhenRangeActive = false)
                    BlockingRuntime.ensureRunning(this)
                    toast(getString(R.string.nfc_feedback_started, profile))
                    return
                }

                if (current == profile) {
                    SwitchModeStore.setEnabled(this, false, markManualOverrideWhenRangeActive = false)
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
                toast(getString(R.string.nfc_feedback_started, profile))
            }

            action.startsWith("temp_disable") -> {
                ProfileStore.setCurrent(this, profile)
                val durationMs = resolveTempDurationMs(action, prefix = "temp_disable")
                SwitchModeStore.setTemporarilyDisabled(this, durationMs)
                BlockingRuntime.ensureRunning(this)
                toast(getString(R.string.nfc_feedback_stopped, profile))
            }

            else -> toast(getString(R.string.nfc_action_error_fmt, getString(R.string.nfc_error_unknown_action)))
        }
    }

    /**
     * Parses:
     *  - temp_disable     -> default
     *  - temp_disable10   -> 10 min
     *  - temp_enable      -> default
     *  - temp_enable10    -> 10 min
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
