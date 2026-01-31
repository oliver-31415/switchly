package at.saltyy.switchly.util

import android.content.SharedPreferences

/**
 * Defensive SharedPreferences accessors.
 *
 * Firestore/JSON/etc. can occasionally rehydrate integral values as Long (or String).
 * SharedPreferences is type-strict and will throw ClassCastException if you call getInt()
 * when the stored type is not Int.
 *
 * These helpers avoid crashes and "heal" the stored value back to the expected type.
 */
fun SharedPreferences.getIntCompat(key: String, def: Int = 0): Int {
    return try {
        getInt(key, def)
    } catch (_: ClassCastException) {
        val v = when (val any = all[key]) {
            is Long -> any.toInt()
            is Int -> any
            is String -> any.toIntOrNull() ?: def
            else -> def
        }
        edit().putInt(key, v).apply()
        v
    }
}
