package at.saltyy.switchly.premium

import android.app.Activity
import android.content.Context
import android.util.Log
import androidx.core.content.edit

/**
 * Central place for:
 * - Premium status (from Play Billing only)
 * - Delegation to Billing (PremiumRuntime)
 */
object PremiumManager {

    private const val TAG = "PremiumManager"
    private const val PREFS = "switchly_prefs"
    private const val KEY_PREMIUM_FROM_PLAY = "premium_from_play"

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isPremium(ctx: Context): Boolean {
        val p = prefs(ctx)
        return p.getBoolean(KEY_PREMIUM_FROM_PLAY, false)
    }

    /**
     * Used by PremiumRuntime when Billing finds a valid purchase.
     */
    fun setPremiumFromPlay(ctx: Context, active: Boolean) {
        Log.d(TAG, "setPremiumFromPlay: $active")
        prefs(ctx).edit {
            putBoolean(KEY_PREMIUM_FROM_PLAY, active)
        }

        // Mirror flag in Firestore (if logged in)
        PremiumCloudRuntime.syncPremiumFlag(ctx)
    }

    /**
     * Call this at app startup to check for existing purchases.
     */
    fun refreshFromPlay(context: Context) {
        PremiumRuntime.refreshFromPlay(context)
    }

    /**
     * Called from UI (Premium info screen / Settings) to initiate a purchase.
     */
    fun launchPurchase(activity: Activity, productId: String) {
        PremiumRuntime.launchPurchase(activity, productId)
    }
}
