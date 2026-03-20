package at.saltyy.switchly.security

import android.content.Context
import androidx.core.content.edit

object AppLockStore {
    private const val PREFS = "switchly_prefs"
    private const val KEY_ENABLED = "pref_app_lock_enabled"
    private const val KEY_PIN = "pref_app_lock_pin"
    private const val KEY_BIOMETRIC = "pref_app_lock_biometric"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun hasPin(ctx: Context): Boolean = !prefs(ctx).getString(KEY_PIN, null).isNullOrBlank()

    fun isEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_ENABLED, false) && hasPin(ctx)

    fun setEnabled(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit(commit = true) {
            putBoolean(KEY_ENABLED, enabled && hasPin(ctx))
        }
    }

    fun setPin(ctx: Context, pin: String) {
        prefs(ctx).edit(commit = true) {
            putString(KEY_PIN, pin.trim())
        }
    }

    fun matchesPin(ctx: Context, enteredPin: String): Boolean {
        val expected = prefs(ctx).getString(KEY_PIN, null)?.trim().orEmpty()
        return expected.isNotBlank() && expected == enteredPin.trim()
    }

    fun isBiometricEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_BIOMETRIC, false)

    fun setBiometricEnabled(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit(commit = true) { putBoolean(KEY_BIOMETRIC, enabled) }
    }
}
