package at.saltyy.switchly.nfc

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.widget.Toast
import at.saltyy.switchly.R
import at.saltyy.switchly.app.Switchly
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
            // Optional: UID-only pairing
            // If a UID is paired, only that exact tag can toggle Switchly.
            val pairedUid = NfcUidPairingStore.getPairedUidHex(context)
            if (pairedUid != null) {
                val tag: Tag? = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
                val seenUid = NfcTagUid.uidHex(tag)

                if (seenUid == null || !seenUid.equals(pairedUid, ignoreCase = true)) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.nfc_wrong_tag_paired_uid_required),
                        Toast.LENGTH_SHORT
                    ).show()
                    return
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
