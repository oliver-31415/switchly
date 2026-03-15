package at.saltyy.switchly.nfc

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.widget.Toast
import androidx.preference.PreferenceManager
import at.saltyy.switchly.R
import at.saltyy.switchly.app.Switchly
import at.saltyy.switchly.data.prefs.AutomationModeStore
import at.saltyy.switchly.data.prefs.BlockingToggleKeys
import at.saltyy.switchly.data.prefs.NfcUidPairingStore
import at.saltyy.switchly.data.prefs.SwitchModeStore

class NfcReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (
            action == NfcAdapter.ACTION_TAG_DISCOVERED ||
            action == NfcAdapter.ACTION_NDEF_DISCOVERED ||
            action == Switchly.ACTION_TOGGLE
        ) {
            val isNfcIntent =
                action == NfcAdapter.ACTION_TAG_DISCOVERED || action == NfcAdapter.ACTION_NDEF_DISCOVERED

            if (isNfcIntent && !AutomationModeStore.isNfcAllowed(context)) {
                Toast.makeText(context, context.getString(R.string.mode_blocked_nfc_action), Toast.LENGTH_SHORT).show()
                return
            }

            // Optional: UID-only pairing
            // If one or more UIDs are paired, only those exact NFC tags can toggle Switchly.
            // Do not apply this check to non-NFC fallback intents.
            if (isNfcIntent) {
                val sp = PreferenceManager.getDefaultSharedPreferences(context)
                val pairedUidsEnabled = sp.getBoolean(BlockingToggleKeys.KEY_ENABLE_PAIRED_UIDS, false)
                if (pairedUidsEnabled) {
                    val pairedUids = NfcUidPairingStore.getPairedUidsHex(context)
                    if (pairedUids.isNotEmpty()) {
                        val tag: Tag? = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
                        val seenUid = NfcTagUid.normalizeUidHex(NfcTagUid.uidHex(tag))

                        if (seenUid.isBlank() || pairedUids.none { it.equals(seenUid, ignoreCase = true) }) {
                            Toast.makeText(
                                context,
                                context.getString(R.string.nfc_wrong_tag_paired_uid_required),
                                Toast.LENGTH_SHORT
                            ).show()
                            return
                        }
                    }
                }
            }

            // Toggle the real global Switchly state (same as the UI toggle).
            // Use the *base* flag so NFC toggles are not affected by temporary (schedule) overrides.
            val newValue = !SwitchModeStore.isBaseEnabled(context)
            SwitchModeStore.setEnabled(context, newValue)

            val state = context.getString(
                if (newValue) R.string.nfc_state_on else R.string.nfc_state_off
            )

            Toast.makeText(
                context,
                context.getString(R.string.nfc_toggle_via_nfc_fmt, state),
                Toast.LENGTH_SHORT
            ).show()

            // let UI/Service refresh
            context.sendBroadcast(
                Intent(Switchly.ACTION_REFRESH).setPackage(context.packageName)
            )
        }
    }
}
