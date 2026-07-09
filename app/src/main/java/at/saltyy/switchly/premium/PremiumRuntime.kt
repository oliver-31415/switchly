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
import at.saltyy.switchly.BuildConfig
import at.saltyy.switchly.data.prefs.AppLogStore
import at.saltyy.switchly.security.PlayIntegrityRuntime
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
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

    @Volatile
    private var cachedPremiumPrice: String? = null

    @Volatile
    private var purchaseLaunchInProgress: Boolean = false

    private var pendingLaunchRequest: (() -> Unit)? = null

    private var pendingLaunchError: ((String) -> Unit)? = null

    // Setup & connection
    private fun ensureClient(context: Context, onReady: () -> Unit, onError: (String) -> Unit = {}) {
        appContext = context.applicationContext

        val existing = billingClient
        if (existing != null && existing.isReady) {
            onReady()
            return
        }

        if (isConnecting) {
            // If a connection is already in progress, just store the callback
            pendingLaunchRequest = onReady
            pendingLaunchError = onError
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
                    pendingLaunchError = null
                    if (req != null) {
                        req()
                    } else {
                        // No pending callback – still refresh premium state from Play
                        appContext?.let { refreshFromPlay(it) }
                    }
                } else {
                    val message = "Google Play Billing setup failed: ${result.debugMessage.ifBlank { result.responseCode.toString() }}"
                    Log.e(TAG, message)
                    val err = pendingLaunchError
                    pendingLaunchRequest = null
                    pendingLaunchError = null
                    err?.invoke(message)
                    appContext?.let { AppLogStore.append(it, "Billing", "Restore failed reason=billing_setup_failed code=${result.responseCode}") }
                }
            }

            override fun onBillingServiceDisconnected() {
                isConnecting = false
                billingClient = null
                Log.w(TAG, "Billing service disconnected")
            }
        })
    }

    private fun resetClient() {
        val old = billingClient
        billingClient = null
        isConnecting = false
        pendingLaunchRequest = null
        pendingLaunchError = null
        runCatching { old?.endConnection() }
    }

    // Refreshes the premium state by querying existing purchases. Typically called on app start.
    fun refreshFromPlay(context: Context) {
        PlayIntegrityRuntime.requestSoftCheck(context, "play_purchase_restore")

        if (!BuildConfig.SWITCHLY_PLAY_BILLING_ENABLED) {
            if (BuildConfig.DEBUG) Log.d(TAG, "Play Billing disabled for this build; skipping refresh")
            return
        }

        ensureClient(context, onReady = {
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
        })
    }

    fun queryPremiumPrice(
        context: Context,
        productId: String = PRODUCT_ID,
        onResult: (String?) -> Unit,
    ) {
        if (!BuildConfig.SWITCHLY_PLAY_BILLING_ENABLED) {
            onResult(null)
            return
        }

        val cached = cachedPremiumPrice
        if (!cached.isNullOrBlank() && productId == PRODUCT_ID) {
            onResult(cached)
            return
        }

        fun fail(message: String) {
            Log.w(TAG, message)
            AppLogStore.append(context.applicationContext, "Billing", message)
            onResult(null)
        }

        ensureClient(
            context,
            onReady = {
                val client = billingClient ?: run {
                    onResult(null)
                    return@ensureClient
                }

                val params = QueryProductDetailsParams.newBuilder()
                    .setProductList(
                        listOf(
                            QueryProductDetailsParams.Product.newBuilder()
                                .setProductId(productId)
                                .setProductType(BillingClient.ProductType.INAPP)
                                .build()
                        )
                    )
                    .build()

                client.queryProductDetailsAsync(params) { billingResult, result ->
                    if (billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
                        fail("Google Play price query failed: ${billingResult.responseCode} ${billingResult.debugMessage}")
                        return@queryProductDetailsAsync
                    }

                    val details = result.productDetailsList.firstOrNull()
                    val price = details?.localizedOneTimePrice()
                    if (!price.isNullOrBlank() && productId == PRODUCT_ID) {
                        cachedPremiumPrice = price
                    }
                    onResult(price)
                }
            },
            onError = { message -> fail(message) }
        )
    }

    private fun ProductDetails.localizedOneTimePrice(): String? =
        oneTimePurchaseOfferDetails?.formattedPrice?.takeIf { it.isNotBlank() }

    // Starts the purchase flow for the given product.
    fun launchPurchase(activity: Activity, productId: String, onResult: ((started: Boolean, message: String?) -> Unit)? = null) {
        launchPurchaseInternal(activity, productId, retryAfterDisconnect = true, onResult = onResult)
    }

    private fun launchPurchaseInternal(
        activity: Activity,
        productId: String,
        retryAfterDisconnect: Boolean,
        onResult: ((started: Boolean, message: String?) -> Unit)? = null,
    ) {
        AppLogStore.append(
            activity.applicationContext,
            "Billing",
            "Purchase requested product=$productId playBilling=${BuildConfig.SWITCHLY_PLAY_BILLING_ENABLED} variant=${BuildConfig.SWITCHLY_APK_VARIANT}"
        )

        PlayIntegrityRuntime.requestSoftCheck(activity, "play_purchase_requested", force = true)

        if (!BuildConfig.SWITCHLY_PLAY_BILLING_ENABLED) {
            val message = "Google Play Billing is disabled for this build. Variant=${BuildConfig.SWITCHLY_APK_VARIANT}."
            if (BuildConfig.DEBUG) Log.d(TAG, "Play Billing disabled for this build; purchase ignored")
            onResult?.invoke(false, message)
            return
        }

        fun fail(message: String) {
            purchaseLaunchInProgress = false
            Log.e(TAG, message)
            AppLogStore.append(activity.applicationContext, "Billing", message)
            activity.runOnUiThread { onResult?.invoke(false, message) }
        }

        fun restoreOwnedPurchase(message: String) {
            purchaseLaunchInProgress = false
            AppLogStore.append(activity.applicationContext, "Billing", message)
            refreshFromPlay(activity.applicationContext)
            activity.runOnUiThread { onResult?.invoke(false, message) }
        }

        fun isDuplicateQuickAttempt(result: BillingResult): Boolean =
            result.responseCode == BillingClient.BillingResponseCode.DEVELOPER_ERROR &&
                result.debugMessage.contains("duplicate", ignoreCase = true)

        if (purchaseLaunchInProgress) {
            val message = "Google Play purchase is already opening. Please wait a moment."
            AppLogStore.append(activity.applicationContext, "Billing", "Purchase ignored reason=launch_already_in_progress")
            activity.runOnUiThread { onResult?.invoke(false, message) }
            return
        }
        purchaseLaunchInProgress = true

        ensureClient(
            activity,
            onReady = {
            val client = billingClient ?: run {
                purchaseLaunchInProgress = false
                return@ensureClient
            }

            queryOwnedPremium(client) { alreadyOwned ->
                if (alreadyOwned) {
                    restoreOwnedPurchase("Google Play purchase already exists; restoring Premium.")
                    return@queryOwnedPremium
                }

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
                                fail("Google Play product query failed: ${billingResult.responseCode} ${billingResult.debugMessage}")
                                return
                            }

                            val details = result.productDetailsList.firstOrNull()
                            if (details == null) {
                                fail("Google Play returned no product details for '$productId'. Check the in-app product ID and Play Console activation.")
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

                            runCatching {
                                val res = client.launchBillingFlow(activity, flowParams)
                                if (BuildConfig.DEBUG) Log.d(TAG, "launchBillingFlow result: ${res.responseCode} ${res.debugMessage}")
                                when (res.responseCode) {
                                    BillingClient.BillingResponseCode.OK -> {
                                        // Keep the guard active until Play Billing calls onPurchasesUpdated.
                                        activity.runOnUiThread { onResult?.invoke(true, null) }
                                    }
                                    BillingClient.BillingResponseCode.SERVICE_DISCONNECTED -> {
                                        if (retryAfterDisconnect) {
                                            AppLogStore.append(activity.applicationContext, "Billing", "Purchase flow disconnected; retrying once")
                                            resetClient()
                                            purchaseLaunchInProgress = false
                                            launchPurchaseInternal(activity, productId, retryAfterDisconnect = false, onResult = onResult)
                                        } else {
                                            fail("Google Play purchase flow did not open: ${res.responseCode} ${res.debugMessage}")
                                        }
                                    }
                                    BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> {
                                        restoreOwnedPurchase("Google Play reports Premium is already owned; restoring purchase.")
                                    }
                                    else -> {
                                        if (isDuplicateQuickAttempt(res)) {
                                            restoreOwnedPurchase("Google Play is still processing the previous purchase attempt. Checking existing purchases.")
                                        } else {
                                            fail("Google Play purchase flow did not open: ${res.responseCode} ${res.debugMessage}")
                                        }
                                    }
                                }
                            }.onFailure { t ->
                                fail("Google Play purchase flow crashed: ${t.message ?: t::class.java.simpleName}")
                            }
                        }
                    }
                )
            }
            },
            onError = { message -> fail(message) }
        )
    }

    private fun queryOwnedPremium(client: BillingClient, onResult: (Boolean) -> Unit) {
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.INAPP)
            .build()
        client.queryPurchasesAsync(params) { result, purchasesList ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                onResult(false)
                return@queryPurchasesAsync
            }
            onResult(
                purchasesList.any { purchase ->
                    purchase.products.contains(PRODUCT_ID) &&
                        purchase.purchaseState == Purchase.PurchaseState.PURCHASED
                }
            )
        }
    }

    // PurchasesUpdatedListener
    override fun onPurchasesUpdated(
        billingResult: BillingResult,
        purchases: MutableList<Purchase>?
    ) {
        purchaseLaunchInProgress = false
        if (billingResult.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
            handlePurchases(purchases)
        } else if (billingResult.responseCode == BillingClient.BillingResponseCode.USER_CANCELED) {
            if (BuildConfig.DEBUG) Log.d(TAG, "Purchase canceled by user")
        } else if (billingResult.responseCode == BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED) {
            appContext?.let { refreshFromPlay(it) }
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
                    PlayIntegrityRuntime.requestSoftCheck(ctx, "play_purchase_success", force = true)
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
