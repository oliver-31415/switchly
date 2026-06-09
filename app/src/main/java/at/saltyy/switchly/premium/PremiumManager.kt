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
 * Firebase email/password APKs can use an external checkout/portal URL and Switchly redeem codes.
 * Offline builds can unlock Premium with a local offline code allowlist, but cannot restore online purchases.
 */
object PremiumManager {

    private const val TAG = "PremiumManager"
    private const val PREFS = "switchly_prefs"

    private const val KEY_PREMIUM_FROM_PLAY = "premium_from_play"
    private const val KEY_PREMIUM_FROM_EXTERNAL = "premium_from_external"
    private const val KEY_PREMIUM_FROM_REDEEM_CODE = "premium_from_redeem_code"
    private const val KEY_PREMIUM_FROM_OFFLINE_CODE = "premium_from_offline_code"
    private const val KEY_PREMIUM_SOURCE = "premium_source"
    private const val KEY_PREMIUM_REDEEMED_AT = "premium_redeemed_at"
    private const val KEY_PREMIUM_CODE_LAST4 = "premium_code_last4"

    const val SOURCE_NONE = "none"
    const val SOURCE_GOOGLE_PLAY_BILLING = "google_play_billing"
    const val SOURCE_STRIPE_DIRECT = "stripe_direct"
    const val SOURCE_SWITCHLY_REDEEM_CODE = "switchly_redeem_code"
    const val SOURCE_OFFLINE_CODE = "offline_code"

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isPremiumSupportedBuild(): Boolean =
        BuildConfig.SWITCHLY_PLAY_BILLING_ENABLED ||
            BuildConfig.SWITCHLY_EXTERNAL_PAYMENTS_ENABLED ||
            BuildConfig.SWITCHLY_REDEEM_CODES_ENABLED

    fun isPremium(ctx: Context): Boolean {
        if (!isPremiumSupportedBuild()) return false

        val p = prefs(ctx)
        return when {
            BuildConfig.SWITCHLY_PLAY_BILLING_ENABLED -> p.getBoolean(KEY_PREMIUM_FROM_PLAY, false)
            BuildConfig.SWITCHLY_EXTERNAL_PAYMENTS_ENABLED -> {
                p.getBoolean(KEY_PREMIUM_FROM_EXTERNAL, false) ||
                    p.getBoolean(KEY_PREMIUM_FROM_REDEEM_CODE, false)
            }
            BuildConfig.SWITCHLY_OFFLINE_REDEEM_CODES_ENABLED -> p.getBoolean(KEY_PREMIUM_FROM_OFFLINE_CODE, false)
            else -> false
        }
    }

    fun isExternalPaymentBuild(): Boolean =
        BuildConfig.SWITCHLY_EXTERNAL_PAYMENTS_ENABLED && !BuildConfig.SWITCHLY_PLAY_BILLING_ENABLED

    fun externalPaymentProviderName(): String = ExternalPaymentRuntime.providerName()

    fun premiumSource(ctx: Context): String {
        if (!isPremium(ctx)) return SOURCE_NONE

        val p = prefs(ctx)
        val stored = p.getString(KEY_PREMIUM_SOURCE, null)
        if (!stored.isNullOrBlank()) return stored

        return when {
            p.getBoolean(KEY_PREMIUM_FROM_PLAY, false) -> SOURCE_GOOGLE_PLAY_BILLING
            p.getBoolean(KEY_PREMIUM_FROM_REDEEM_CODE, false) -> SOURCE_SWITCHLY_REDEEM_CODE
            p.getBoolean(KEY_PREMIUM_FROM_OFFLINE_CODE, false) -> SOURCE_OFFLINE_CODE
            p.getBoolean(KEY_PREMIUM_FROM_EXTERNAL, false) -> SOURCE_STRIPE_DIRECT
            else -> SOURCE_NONE
        }
    }

    fun premiumSourceLabel(ctx: Context): String = when (premiumSource(ctx)) {
        SOURCE_GOOGLE_PLAY_BILLING -> ctx.getString(R.string.premium_source_google_play_billing)
        SOURCE_SWITCHLY_REDEEM_CODE -> ctx.getString(R.string.premium_source_switchly_redeem_code)
        SOURCE_OFFLINE_CODE -> ctx.getString(R.string.premium_source_offline_code)
        SOURCE_STRIPE_DIRECT -> ctx.getString(R.string.premium_source_stripe_direct)
        else -> ctx.getString(R.string.premium_source_none)
    }

    fun redeemedCodeLast4(ctx: Context): String = prefs(ctx).getString(KEY_PREMIUM_CODE_LAST4, "").orEmpty()

    // Used by PremiumRuntime when Billing finds a valid purchase.
    fun setPremiumFromPlay(ctx: Context, active: Boolean) {
        if (!BuildConfig.SWITCHLY_PLAY_BILLING_ENABLED) {
            if (BuildConfig.DEBUG) Log.d(TAG, "Ignoring Play premium state in non-Play build: $active")
            return
        }

        if (BuildConfig.DEBUG) Log.d(TAG, "setPremiumFromPlay: $active")
        prefs(ctx).edit {
            putBoolean(KEY_PREMIUM_FROM_PLAY, active)
            if (active) {
                putBoolean(KEY_PREMIUM_FROM_EXTERNAL, false)
                putBoolean(KEY_PREMIUM_FROM_REDEEM_CODE, false)
                putBoolean(KEY_PREMIUM_FROM_OFFLINE_CODE, false)
                putString(KEY_PREMIUM_SOURCE, SOURCE_GOOGLE_PLAY_BILLING)
                putLong(KEY_PREMIUM_REDEEMED_AT, System.currentTimeMillis())
                remove(KEY_PREMIUM_CODE_LAST4)
            }
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
            if (active) {
                putBoolean(KEY_PREMIUM_FROM_PLAY, false)
                putBoolean(KEY_PREMIUM_FROM_OFFLINE_CODE, false)
                putString(KEY_PREMIUM_SOURCE, SOURCE_STRIPE_DIRECT)
                putLong(KEY_PREMIUM_REDEEMED_AT, System.currentTimeMillis())
                remove(KEY_PREMIUM_CODE_LAST4)
            }
        }

        // Mirror flag in Firestore (if logged in and Firebase is available).
        PremiumCloudRuntime.syncPremiumFlag(ctx)
    }

    fun setPremiumFromSwitchlyRedeemCode(ctx: Context, code: String) {
        if (!BuildConfig.SWITCHLY_ONLINE_REDEEM_CODES_ENABLED) {
            if (BuildConfig.DEBUG) Log.d(TAG, "Ignoring Switchly redeem code in unsupported build")
            return
        }

        prefs(ctx).edit {
            putBoolean(KEY_PREMIUM_FROM_REDEEM_CODE, true)
            putBoolean(KEY_PREMIUM_FROM_PLAY, false)
            putBoolean(KEY_PREMIUM_FROM_OFFLINE_CODE, false)
            putString(KEY_PREMIUM_SOURCE, SOURCE_SWITCHLY_REDEEM_CODE)
            putLong(KEY_PREMIUM_REDEEMED_AT, System.currentTimeMillis())
            putString(KEY_PREMIUM_CODE_LAST4, code.takeLast(4))
        }

        PremiumCloudRuntime.syncPremiumFlag(ctx)
    }

    fun setPremiumFromOfflineCode(ctx: Context, code: String) {
        if (!BuildConfig.SWITCHLY_OFFLINE_REDEEM_CODES_ENABLED) {
            if (BuildConfig.DEBUG) Log.d(TAG, "Ignoring offline Premium code in unsupported build")
            return
        }

        prefs(ctx).edit {
            putBoolean(KEY_PREMIUM_FROM_OFFLINE_CODE, true)
            putBoolean(KEY_PREMIUM_FROM_PLAY, false)
            putBoolean(KEY_PREMIUM_FROM_EXTERNAL, false)
            putBoolean(KEY_PREMIUM_FROM_REDEEM_CODE, false)
            putString(KEY_PREMIUM_SOURCE, SOURCE_OFFLINE_CODE)
            putLong(KEY_PREMIUM_REDEEMED_AT, System.currentTimeMillis())
            putString(KEY_PREMIUM_CODE_LAST4, code.takeLast(4))
        }
    }

    // Call this at app startup to check for existing Play Billing purchases.
    // For external/offline builds this is intentionally a no-op to avoid opening browser checkout/portal during app startup.
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
