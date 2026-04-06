/*
 * Switchly
 * Copyright (C) 2025-2026 Saltyy
 * Copyright (C) 2026 Switchly Contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

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

    // Used by PremiumRuntime when Billing finds a valid purchase.
    fun setPremiumFromPlay(ctx: Context, active: Boolean) {
        Log.d(TAG, "setPremiumFromPlay: $active")
        prefs(ctx).edit {
            putBoolean(KEY_PREMIUM_FROM_PLAY, active)
        }

        // Mirror flag in Firestore (if logged in)
        PremiumCloudRuntime.syncPremiumFlag(ctx)
    }

    // Call this at app startup to check for existing purchases.
    fun refreshFromPlay(context: Context) {
        PremiumRuntime.refreshFromPlay(context)
    }

    // Called from UI (Premium info screen/Settings) to initiate a purchase.
    fun launchPurchase(activity: Activity, productId: String) {
        PremiumRuntime.launchPurchase(activity, productId)
    }
}
