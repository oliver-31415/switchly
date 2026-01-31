package at.saltyy.switchly.data.prefs

import android.content.Context
import androidx.core.content.edit

/**
 * Stores a paired NFC tag UID (serial number) so Switchly can support:
 *  - read-only / non-NDEF tags
 *  - UID-only pairing (only the paired tag can toggle Switchly)
 */
object NfcUidPairingStore {

    private const val PREFS = "switchly_prefs"

    private const val KEY_PAIRED_UID_HEX = "nfc_paired_uid_hex"

    fun getPairedUidHex(ctx: Context): String? {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val v = sp.getString(KEY_PAIRED_UID_HEX, null)?.trim().orEmpty()
        return v.ifBlank { null }
    }

    fun isPaired(ctx: Context): Boolean = getPairedUidHex(ctx) != null

    fun setPairedUidHex(ctx: Context, uidHex: String) {
        val clean = uidHex.trim().uppercase()
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        sp.edit {
            putString(KEY_PAIRED_UID_HEX, clean)
        }
    }

    fun clear(ctx: Context) {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        sp.edit { remove(KEY_PAIRED_UID_HEX) }
    }
}
