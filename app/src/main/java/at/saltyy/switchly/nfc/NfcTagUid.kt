package at.saltyy.switchly.nfc

import android.nfc.Tag

/**
 * Helper for getting a stable human-readable UID (tag.id) string.
 * Note: Not every NFC technology guarantees a stable UID across all devices, but for most common tags it works fine.
 */
object NfcTagUid {

    fun uidHex(tag: Tag?): String? = uidHex(tag?.id)

    fun uidHex(id: ByteArray?): String? {
        if (id == null || id.isEmpty()) return null
        return id.joinToString(separator = "") { b ->
            "%02X".format(b)
        }
    }
}
