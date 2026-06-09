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
import android.os.Handler
import android.os.Looper
import android.util.Log
import at.saltyy.switchly.BuildConfig
import at.saltyy.switchly.data.prefs.AppLogStore
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetailsResponseListener
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesResponseListener
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryProductDetailsResult
import com.android.billingclient.api.QueryPurchasesParams

/**
 * Billing integration used by [PremiumManager].
 * Typical usage via PremiumManager:
 *  - PremiumManager.refreshFromPlay(context)
 *  - PremiumManager.launchPurchase(activity, "premium_upgrade")
 */
object PremiumRuntime : PurchasesUpdatedListener {

    private const val TAG = "PremiumRuntime"
    private const val PRODUCT_ID = "premium_upgrade"

    @Volatile
    private var billingClient: BillingClient? = null

    @Volatile
    private var isConnecting: Boolean = false

    @Volatile
    private var appContext: Context? = null

    private var pendingLaunchRequest: (() -> Unit)? = null

    // Setup & connection
    private fun ensureClient(context: Context, onReady: () -> Unit) {
        appContext = context.applicationContext

        val existing = billingClient
        if (existing != null && existing.isReady) {
            onReady()
            return
        }

        if (isConnecting) {
            // If a connection is already in progress, just store the callback
            pendingLaunchRequest = onReady
            return
        }

        isConnecting = true

        val client = BillingClient.newBuilder(context)
            .setListener(this)
            .enablePendingPurchases(
                PendingPurchasesParams.newBuilder()
                    .enableOneTimeProducts()
                    .build()
            )
            .build()

        billingClient = client

        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                isConnecting = false
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    if (BuildConfig.DEBUG) Log.d(TAG, "Billing service connected")
                    val req = pendingLaunchRequest
                    pendingLaunchRequest = null
                    if (req != null) {
                        req()
                    } else {
                        // No pending callback – still refresh premium state from Play
                        appContext?.let { refreshFromPlay(it) }
                    }
                } else {
                    Log.e(TAG, "Billing setup failed: ${result.debugMessage}")
                    appContext?.let { AppLogStore.append(it, "Billing", "Restore failed reason=billing_setup_failed") }
                }
            }

            override fun onBillingServiceDisconnected() {
                Log.w(TAG, "Billing service disconnected")
            }
        })
    }

    // Refreshes the premium state by querying existing purchases. Typically called on app start.
    fun refreshFromPlay(context: Context) {
        if (!BuildConfig.SWITCHLY_PLAY_BILLING_ENABLED) {
            if (BuildConfig.DEBUG) Log.d(TAG, "Play Billing disabled for this build; skipping refresh")
            return
        }

        ensureClient(context) {
            val client = billingClient ?: return@ensureClient

            val params = QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.INAPP)
                .build()

            client.queryPurchasesAsync(params, object : PurchasesResponseListener {
                override fun onQueryPurchasesResponse(
                    result: BillingResult,
                    purchasesList: MutableList<Purchase>
                ) {
                    if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                        Log.e(TAG, "queryPurchasesAsync failed: ${result.debugMessage}")
                        return
                    }

                    val hasPremium = purchasesList.any { purchase ->
                        purchase.products.contains(PRODUCT_ID) && purchase.purchaseState == Purchase.PurchaseState.PURCHASED
                    }

                    appContext?.let { PremiumManager.setPremiumFromPlay(it, hasPremium) }
                }
            })
        }
    }

    // Starts the purchase flow for the given product.
    fun launchPurchase(activity: Activity, productId: String) {
        if (!BuildConfig.SWITCHLY_PLAY_BILLING_ENABLED) {
            if (BuildConfig.DEBUG) Log.d(TAG, "Play Billing disabled for this build; purchase ignored")
            return
        }

        ensureClient(activity) {
            val client = billingClient ?: return@ensureClient

            val productList = listOf(
                QueryProductDetailsParams.Product.newBuilder()
                    .setProductId(productId)
                    .setProductType(BillingClient.ProductType.INAPP)
                    .build()
            )

            val params = QueryProductDetailsParams.newBuilder()
                .setProductList(productList)
                .build()

            client.queryProductDetailsAsync(
                params,
                object : ProductDetailsResponseListener {
                    override fun onProductDetailsResponse(
                        billingResult: BillingResult,
                        result: QueryProductDetailsResult
                    ) {
                        if (billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
                            Log.e(TAG, "queryProductDetailsAsync failed: " + "${billingResult.responseCode} ${billingResult.debugMessage}")
                            return
                        }

                        val details = result.productDetailsList.firstOrNull()
                        if (details == null) {
                            Log.e(TAG, "No ProductDetails for $productId")
                            return
                        }

                        val productDetailsParams = listOf(
                            BillingFlowParams.ProductDetailsParams.newBuilder()
                                .setProductDetails(details)
                                .build()
                        )

                        val flowParams = BillingFlowParams.newBuilder()
                            .setProductDetailsParamsList(productDetailsParams)
                            .build()

                        // Workaround for rare crashes where ProxyBillingActivity is started without required extras.
                        // Keep it disabled by default and enable only during the purchase flow.
                        BillingProxyActivityGate.enable(activity.applicationContext)

                        val disableLater = Runnable {
                            appContext?.let { BillingProxyActivityGate.disable(it) }
                        }

                        // Hard timeout as a safety net in case we never get onPurchasesUpdated.
                        Handler(Looper.getMainLooper()).postDelayed(disableLater, 60_000L)

                        runCatching {
                            val res = client.launchBillingFlow(activity, flowParams)
                            if (BuildConfig.DEBUG) Log.d(TAG, "launchBillingFlow result: ${res.responseCode} ${res.debugMessage}")
                        }.onFailure { t ->
                            Log.e(TAG, "launchBillingFlow threw", t)
                            disableLater.run()
                        }
                    }
                }
            )
        }
    }

    // PurchasesUpdatedListener
    override fun onPurchasesUpdated(
        billingResult: BillingResult,
        purchases: MutableList<Purchase>?
    ) {
        // Once the billing flow returns, we no longer need ProxyBillingActivity enabled.
        appContext?.let { BillingProxyActivityGate.disable(it) }

        if (billingResult.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
            handlePurchases(purchases)
        } else if (billingResult.responseCode == BillingClient.BillingResponseCode.USER_CANCELED) {
            if (BuildConfig.DEBUG) Log.d(TAG, "Purchase canceled by user")
        } else {
            Log.e(TAG, "onPurchasesUpdated error: ${billingResult.responseCode} ${billingResult.debugMessage}")
        }
    }

    private fun handlePurchases(purchases: List<Purchase>) {
        val client = billingClient ?: return
        val ctx = appContext

        for (purchase in purchases) {
            if (!purchase.products.contains(PRODUCT_ID)) continue

            if (purchase.purchaseState == Purchase.PurchaseState.PURCHASED) {
                if (BuildConfig.DEBUG) Log.d(TAG, "handlePurchases: premium purchase detected")
                ctx?.let { AppLogStore.append(it, "Billing", "Purchase success product=$PRODUCT_ID") }

                // Immediately update local + cloud premium state
                if (ctx != null) {
                    PremiumManager.setPremiumFromPlay(ctx, true)
                }

                // Acknowledge purchase if required
                if (!purchase.isAcknowledged) {
                    val params = AcknowledgePurchaseParams.newBuilder()
                        .setPurchaseToken(purchase.purchaseToken)
                        .build()
                    client.acknowledgePurchase(params) { result ->
                        if (BuildConfig.DEBUG) Log.d(TAG, "acknowledgePurchase: ${result.responseCode} ${result.debugMessage}")
                    }
                }
            }
        }
    }
}
