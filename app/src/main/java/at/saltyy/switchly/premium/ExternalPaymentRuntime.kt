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
import android.content.Intent
import androidx.core.net.toUri
import android.widget.Toast
import at.saltyy.switchly.BuildConfig
import at.saltyy.switchly.R
import at.saltyy.switchly.auth.Auth
import at.saltyy.switchly.data.prefs.AppLogStore

/**
 * External checkout entry point for APKs that are not distributed through Google Play.
 *
 * This intentionally does not contain Stripe/Adyen secret keys. 
 * The app opens a configured checkout/portal URL and your backend or payment provider handles the payment.
 * Premium activation must be verified server-side, via a webhook, account entitlement, or a signed license file.

 */
object ExternalPaymentRuntime {

    private const val TAG = "ExternalPayment"

    fun providerName(): String {
        val configured = BuildConfig.SWITCHLY_EXTERNAL_PAYMENT_PROVIDER.trim()
        return configured.ifBlank { "external" }
    }

    private fun checkoutUrl(): String = BuildConfig.SWITCHLY_EXTERNAL_CHECKOUT_URL.trim()

    private fun customerPortalUrl(): String = BuildConfig.SWITCHLY_EXTERNAL_CUSTOMER_PORTAL_URL.trim()

    fun hasCheckoutUrl(): Boolean = checkoutUrl().isNotEmpty()

    fun hasCustomerPortalUrl(): Boolean = customerPortalUrl().isNotEmpty()

    fun launchCheckout(activity: Activity) {
        if (!BuildConfig.SWITCHLY_EXTERNAL_PAYMENTS_ENABLED) {
            Toast.makeText(activity, R.string.premium_external_payments_disabled, Toast.LENGTH_SHORT).show()
            return
        }

        if (BuildConfig.SWITCHLY_FIREBASE_ENABLED && Auth.uid().isNullOrBlank()) {
            Toast.makeText(activity, R.string.premium_external_sign_in_to_buy, Toast.LENGTH_LONG).show()
            AppLogStore.append(activity, TAG, "Checkout needs signed-in Firebase user")
            return
        }

        val url = checkoutUrl()
        if (url.isEmpty()) {
            Toast.makeText(activity, R.string.premium_external_payments_not_configured, Toast.LENGTH_LONG).show()
            AppLogStore.append(
                activity,
                TAG,
                "Checkout URL missing. variant=${BuildConfig.SWITCHLY_APK_VARIANT}, provider=${providerName()}"
            )
            return
        }

        openUrl(
            context = activity,
            rawUrl = url,
            action = "checkout"
        )
    }

    fun openCustomerPortal(context: Context) {
        if (!BuildConfig.SWITCHLY_EXTERNAL_PAYMENTS_ENABLED) {
            Toast.makeText(context, R.string.premium_external_payments_disabled, Toast.LENGTH_SHORT).show()
            return
        }

        val portalUrl = customerPortalUrl()
        if (portalUrl.isEmpty()) {
            Toast.makeText(context, R.string.premium_external_portal_not_configured, Toast.LENGTH_LONG).show()
            AppLogStore.append(
                context,
                TAG,
                "Customer portal URL missing. variant=${BuildConfig.SWITCHLY_APK_VARIANT}, provider=${providerName()}"
            )
            return
        }

        openUrl(
            context = context,
            rawUrl = portalUrl,
            action = "portal"
        )
    }

    private fun openUrl(context: Context, rawUrl: String, action: String) {
        val uri = runCatching {
            rawUrl.toUri().buildUpon()
                .appendQueryParameter("app", BuildConfig.APPLICATION_ID)
                .appendQueryParameter("variant", BuildConfig.SWITCHLY_APK_VARIANT)
                .appendQueryParameter("version", BuildConfig.VERSION_NAME)
                .appendQueryParameter("provider", providerName())
                .appendQueryParameter("action", action)
                .apply {
                    Auth.uid()?.let { uid -> appendQueryParameter("uid", uid) }
                    Auth.email()?.let { email -> appendQueryParameter("email", email) }
                }
                .build()
        }.getOrElse {
            Toast.makeText(context, R.string.premium_external_invalid_url, Toast.LENGTH_LONG).show()
            AppLogStore.append(context, TAG, "Invalid external payment URL", it)
            return
        }

        val intent = Intent(Intent.ACTION_VIEW, uri)
        if (context !is Activity) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        runCatching {
            context.startActivity(intent)
            AppLogStore.append(context, TAG, "Opened $action URL via ${providerName()}")
        }.onFailure {
            Toast.makeText(context, R.string.premium_external_no_browser, Toast.LENGTH_LONG).show()
            AppLogStore.append(context, TAG, "Failed to open $action URL", it)
        }
    }
}
