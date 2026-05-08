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
import android.widget.Toast
import androidx.core.content.edit
import at.saltyy.switchly.BuildConfig
import at.saltyy.switchly.R

/**
 * Central place for Premium status and purchase routing.
 *
 * Play Store builds use Google Play Billing only.
 * Firebase email/password APKs can use an external checkout/portal URL (Stripe, Adyen, etc.) when configured in signing.properties.
 *
 * Offline builds intentionally have no Premium support at all.
 * They must not unlock Premium from stale local flags, cannot buy Premium, and cannot restore account entitlements because they do not have a stable online account identity.
 */
object PremiumManager {

    private const val TAG = "PremiumManager"
    private const val PREFS = "switchly_prefs"
    private const val KEY_PREMIUM_FROM_PLAY = "premium_from_play"
    private const val KEY_PREMIUM_FROM_EXTERNAL = "premium_from_external"

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isPremiumSupportedBuild(): Boolean =
        BuildConfig.SWITCHLY_PLAY_BILLING_ENABLED || BuildConfig.SWITCHLY_EXTERNAL_PAYMENTS_ENABLED

    fun isPremium(ctx: Context): Boolean {
        if (!isPremiumSupportedBuild()) return false

        val p = prefs(ctx)
        return when {
            BuildConfig.SWITCHLY_PLAY_BILLING_ENABLED -> p.getBoolean(KEY_PREMIUM_FROM_PLAY, false)
            BuildConfig.SWITCHLY_EXTERNAL_PAYMENTS_ENABLED -> p.getBoolean(KEY_PREMIUM_FROM_EXTERNAL, false)
            else -> false
        }
    }

    fun isExternalPaymentBuild(): Boolean =
        BuildConfig.SWITCHLY_EXTERNAL_PAYMENTS_ENABLED && !BuildConfig.SWITCHLY_PLAY_BILLING_ENABLED

    fun externalPaymentProviderName(): String = ExternalPaymentRuntime.providerName()

    // Used by PremiumRuntime when Billing finds a valid purchase.
    fun setPremiumFromPlay(ctx: Context, active: Boolean) {
        if (!BuildConfig.SWITCHLY_PLAY_BILLING_ENABLED) {
            if (BuildConfig.DEBUG) Log.d(TAG, "Ignoring Play premium state in non-Play build: $active")
            return
        }

        if (BuildConfig.DEBUG) Log.d(TAG, "setPremiumFromPlay: $active")
        prefs(ctx).edit {
            putBoolean(KEY_PREMIUM_FROM_PLAY, active)
            if (active) putBoolean(KEY_PREMIUM_FROM_EXTERNAL, false)
        }

        // Mirror flag in Firestore (if logged in and Firebase is available).
        PremiumCloudRuntime.syncPremiumFlag(ctx)
    }

    /**
     * Call this only after your backend/webhook/license verification confirms an external purchase. 
     * Never set this directly after merely opening checkout.
     */
    fun setPremiumFromExternalVerified(ctx: Context, active: Boolean) {
        if (!BuildConfig.SWITCHLY_EXTERNAL_PAYMENTS_ENABLED) {
            if (BuildConfig.DEBUG) Log.d(TAG, "Ignoring external premium state in build without external payments: $active")
            return
        }

        if (BuildConfig.DEBUG) Log.d(TAG, "setPremiumFromExternalVerified: $active")
        prefs(ctx).edit {
            putBoolean(KEY_PREMIUM_FROM_EXTERNAL, active)
            if (active) putBoolean(KEY_PREMIUM_FROM_PLAY, false)
        }

        // Mirror flag in Firestore (if logged in and Firebase is available).
        PremiumCloudRuntime.syncPremiumFlag(ctx)
    }

    // Call this at app startup to check for existing Play Billing purchases.
    // For external/offline builds this is intentionally a no-op to avoid opening browser checkout/portal during app startup and to keep offline builds free.
    fun refreshFromPlay(context: Context) {
        if (BuildConfig.SWITCHLY_PLAY_BILLING_ENABLED) {
            PremiumRuntime.refreshFromPlay(context)
        }
    }

    // Called from UI to initiate a purchase.
    fun launchPurchase(activity: Activity, productId: String) {
        when {
            BuildConfig.SWITCHLY_PLAY_BILLING_ENABLED -> {
                PremiumRuntime.launchPurchase(activity, productId)
            }

            BuildConfig.SWITCHLY_EXTERNAL_PAYMENTS_ENABLED -> {
                ExternalPaymentRuntime.launchCheckout(activity)
            }

            else -> {
                Toast.makeText(activity, R.string.premium_unavailable_offline_build, Toast.LENGTH_LONG).show()
            }
        }
    }

    // Called from UI to restore/manage purchases.
    fun restorePurchases(context: Context) {
        when {
            BuildConfig.SWITCHLY_PLAY_BILLING_ENABLED -> {
                PremiumRuntime.refreshFromPlay(context)
                Toast.makeText(context, R.string.premium_checking_purchases, Toast.LENGTH_SHORT).show()
            }

            BuildConfig.SWITCHLY_EXTERNAL_PAYMENTS_ENABLED -> {
                PremiumCloudRuntime.refreshExternalEntitlement(context) { active, error ->
                    val message = when {
                        active -> R.string.premium_external_entitlement_active
                        error != null -> R.string.premium_external_sign_in_to_restore
                        else -> R.string.premium_external_entitlement_missing
                    }
                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                }
            }

            else -> {
                Toast.makeText(context, R.string.premium_unavailable_offline_build, Toast.LENGTH_LONG).show()
            }
        }
    }
}
