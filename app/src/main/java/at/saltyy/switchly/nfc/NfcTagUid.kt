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
        }.let { normalizeUidHex(it) }
    }

    /**
     * Normalizes UID text to uppercase HEX without separators.
     * Accepts formats like "04:AB-11 22" and converts to "04AB1122".
     */
    fun normalizeUidHex(value: String?): String {
        if (value == null) return ""
        return value
            .trim()
            .uppercase()
            .replace(Regex("[^0-9A-F]"), "")
    }
}
