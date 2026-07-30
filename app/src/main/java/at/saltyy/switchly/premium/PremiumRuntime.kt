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

    @Volatile
    private var lastAutomaticRefreshAttemptAt: Long = 0L

    private data class ConnectionCallback(
        val onReady: () -> Unit,
        val onError: (String) -> Unit,
    )

    private val connectionLock = Any()
    private val pendingConnectionCallbacks = mutableListOf<ConnectionCallback>()
    private var connectingClient: BillingClient? = null

    private var purchaseLaunchStartedAt: Long = 0L
    private var purchaseLaunchGeneration: Long = 0L

    private const val AUTO_REFRESH_MIN_INTERVAL_MS = 15 * 60_000L
    private const val BILLING_FAILURE_LOG_WINDOW_MS = 6 * 60 * 60_000L
    private const val PURCHASE_LAUNCH_STALE_AFTER_MS = 30_000L

    // Setup & connection
    private fun ensureClient(context: Context, onReady: () -> Unit, onError: (String) -> Unit = {}) {
        appContext = context.applicationContext

        var readyImmediately = false
        var clientToConnect: BillingClient? = null

        synchronized(connectionLock) {
            val existing = billingClient
            if (existing != null && existing.isReady) {
                readyImmediately = true
            } else {
                pendingConnectionCallbacks += ConnectionCallback(onReady, onError)
                if (!isConnecting) {
                    isConnecting = true
                    clientToConnect = BillingClient.newBuilder(context.applicationContext)
                        .setListener(this)
                        .enablePendingPurchases(
                            PendingPurchasesParams.newBuilder()
                                .enableOneTimeProducts()
                                .build()
                        )
                        .build()
                    billingClient = clientToConnect
                    connectingClient = clientToConnect
                }
            }
        }

        if (readyImmediately) {
            onReady()
            return
        }

        val client = clientToConnect ?: return
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                val callbacks = synchronized(connectionLock) {
                    if (connectingClient !== client) {
                        emptyList()
                    } else {
                        connectingClient = null
                        isConnecting = false
                        pendingConnectionCallbacks.toList().also { pendingConnectionCallbacks.clear() }
                    }
                }
                if (callbacks.isEmpty() && billingClient !== client) {
                    return
                }

                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    if (BuildConfig.DEBUG) Log.d(TAG, "Billing service connected")
                    callbacks.forEach { callback -> callback.onReady() }
                } else {
                    val message = "Google Play Billing setup failed: ${result.debugMessage.ifBlank { result.responseCode.toString() }}"
                    Log.e(TAG, message)
                    synchronized(connectionLock) {
                        if (billingClient === client) {
                            billingClient = null
                        }
                    }
                    runCatching { client.endConnection() }
                    callbacks.forEach { callback -> callback.onError(message) }
                }
            }

            override fun onBillingServiceDisconnected() {
                var handledCurrentClient = false
                val callbacks = synchronized(connectionLock) {
                    if (billingClient === client || connectingClient === client) {
                        handledCurrentClient = true
                        billingClient = null
                        connectingClient = null
                        isConnecting = false
                        pendingConnectionCallbacks.toList().also { pendingConnectionCallbacks.clear() }
                    } else {
                        emptyList()
                    }
                }
                if (!handledCurrentClient) {
                    return
                }
                val message = "Google Play Billing service disconnected."
                Log.w(TAG, message)
                callbacks.forEach { callback -> callback.onError(message) }
            }
        })
    }

    private fun resetClient() {
        val old = synchronized(connectionLock) {
            val current = billingClient
            billingClient = null
            connectingClient = null
            isConnecting = false
            pendingConnectionCallbacks.clear()
            current
        }
        runCatching { old?.endConnection() }
    }

    @Synchronized
    private fun beginPurchaseLaunch(): Long? {
        val now = System.currentTimeMillis()
        val launchStillActive = purchaseLaunchInProgress &&
            now - purchaseLaunchStartedAt < PURCHASE_LAUNCH_STALE_AFTER_MS
        if (launchStillActive) {
            return null
        }

        purchaseLaunchGeneration += 1L
        purchaseLaunchInProgress = true
        purchaseLaunchStartedAt = now
        return purchaseLaunchGeneration
    }

    @Synchronized
    private fun isCurrentPurchaseLaunch(generation: Long): Boolean {
        return purchaseLaunchInProgress && purchaseLaunchGeneration == generation
    }

    @Synchronized
    private fun clearPurchaseLaunch(generation: Long? = null) {
        if (generation != null && generation != purchaseLaunchGeneration) {
            return
        }
        purchaseLaunchInProgress = false
        purchaseLaunchStartedAt = 0L
        if (generation == null) {
            purchaseLaunchGeneration += 1L
        }
    }

    fun cancelPendingPurchaseLaunch() {
        clearPurchaseLaunch()
    }

    // Refreshes the premium state by querying existing purchases. Typically called on app start.
    fun refreshFromPlay(context: Context, force: Boolean = false) {
        if (!BuildConfig.SWITCHLY_PLAY_BILLING_ENABLED) {
            if (BuildConfig.DEBUG) Log.d(TAG, "Play Billing disabled for this build; skipping refresh")
            return
        }

        val now = System.currentTimeMillis()
        if (!force && now - lastAutomaticRefreshAttemptAt < AUTO_REFRESH_MIN_INTERVAL_MS) {
            return
        }
        if (!force) {
            lastAutomaticRefreshAttemptAt = now
        }

        PlayIntegrityRuntime.requestSoftCheck(context, "play_purchase_restore")
        val applicationContext = context.applicationContext
        ensureClient(
            context,
            onReady = {
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
                            AppLogStore.appendRateLimited(
                                applicationContext,
                                "Billing",
                                "Premium purchase check unavailable code=${result.responseCode}",
                                windowMs = BILLING_FAILURE_LOG_WINDOW_MS,
                            )
                            return
                        }

                        val hasPremium = purchasesList.any { purchase ->
                            purchase.products.contains(PRODUCT_ID) && purchase.purchaseState == Purchase.PurchaseState.PURCHASED
                        }

                        PremiumManager.setPremiumFromPlay(applicationContext, hasPremium)
                    }
                })
            },
            onError = { message ->
                AppLogStore.appendRateLimited(
                    applicationContext,
                    "Billing",
                    "Premium purchase check unavailable",
                    IllegalStateException(message),
                    BILLING_FAILURE_LOG_WINDOW_MS,
                )
            },
        )
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
            AppLogStore.appendRateLimited(
                context.applicationContext,
                "Billing",
                message,
                windowMs = BILLING_FAILURE_LOG_WINDOW_MS,
            )
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

        val launchGeneration = beginPurchaseLaunch()
        if (launchGeneration == null) {
            val message = "Google Play purchase is already opening. Please wait a moment."
            AppLogStore.append(activity.applicationContext, "Billing", "Purchase ignored reason=launch_already_in_progress")
            activity.runOnUiThread { onResult?.invoke(false, message) }
            return
        }

        fun fail(message: String) {
            clearPurchaseLaunch(launchGeneration)
            Log.e(TAG, message)
            AppLogStore.append(activity.applicationContext, "Billing", message)
            activity.runOnUiThread { onResult?.invoke(false, message) }
        }

        fun restoreOwnedPurchase(message: String) {
            clearPurchaseLaunch(launchGeneration)
            AppLogStore.append(activity.applicationContext, "Billing", message)
            refreshFromPlay(activity.applicationContext, force = true)
            activity.runOnUiThread { onResult?.invoke(false, message) }
        }

        fun isDuplicateQuickAttempt(result: BillingResult): Boolean {
            return result.responseCode == BillingClient.BillingResponseCode.DEVELOPER_ERROR &&
                result.debugMessage.contains("duplicate", ignoreCase = true)
        }

        ensureClient(
            activity,
            onReady = {
                if (!isCurrentPurchaseLaunch(launchGeneration)) {
                    return@ensureClient
                }

                val client = billingClient
                if (client == null || !client.isReady) {
                    fail("Google Play Billing was not ready when opening the purchase.")
                    return@ensureClient
                }

                queryOwnedPremium(client) { alreadyOwned ->
                    if (!isCurrentPurchaseLaunch(launchGeneration)) {
                        return@queryOwnedPremium
                    }
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
                                if (!isCurrentPurchaseLaunch(launchGeneration)) {
                                    return
                                }
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

                                activity.runOnUiThread {
                                    if (!isCurrentPurchaseLaunch(launchGeneration)) {
                                        return@runOnUiThread
                                    }
                                    if (activity.isFinishing || activity.isDestroyed) {
                                        fail("Google Play purchase flow could not open because the Premium screen was closing.")
                                        return@runOnUiThread
                                    }

                                    runCatching {
                                        val response = client.launchBillingFlow(activity, flowParams)
                                        if (BuildConfig.DEBUG) {
                                            Log.d(TAG, "launchBillingFlow result: ${response.responseCode} ${response.debugMessage}")
                                        }
                                        when (response.responseCode) {
                                            BillingClient.BillingResponseCode.OK -> {
                                                // Keep the guard active until Play Billing calls onPurchasesUpdated
                                                // or the Premium screen resumes after the billing UI closes.
                                                onResult?.invoke(true, null)
                                            }

                                            BillingClient.BillingResponseCode.SERVICE_DISCONNECTED -> {
                                                if (retryAfterDisconnect) {
                                                    AppLogStore.append(
                                                        activity.applicationContext,
                                                        "Billing",
                                                        "Purchase flow disconnected; retrying once"
                                                    )
                                                    resetClient()
                                                    clearPurchaseLaunch(launchGeneration)
                                                    launchPurchaseInternal(
                                                        activity,
                                                        productId,
                                                        retryAfterDisconnect = false,
                                                        onResult = onResult,
                                                    )
                                                } else {
                                                    fail("Google Play purchase flow did not open: ${response.responseCode} ${response.debugMessage}")
                                                }
                                            }

                                            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> {
                                                restoreOwnedPurchase("Google Play reports Premium is already owned; restoring purchase.")
                                            }

                                            else -> {
                                                if (isDuplicateQuickAttempt(response)) {
                                                    restoreOwnedPurchase("Google Play is still processing the previous purchase attempt. Checking existing purchases.")
                                                } else {
                                                    fail("Google Play purchase flow did not open: ${response.responseCode} ${response.debugMessage}")
                                                }
                                            }
                                        }
                                    }.onFailure { throwable ->
                                        fail("Google Play purchase flow crashed: ${throwable.message ?: throwable::class.java.simpleName}")
                                    }
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
        clearPurchaseLaunch()
        if (billingResult.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
            handlePurchases(purchases)
        } else if (billingResult.responseCode == BillingClient.BillingResponseCode.USER_CANCELED) {
            if (BuildConfig.DEBUG) Log.d(TAG, "Purchase canceled by user")
        } else if (billingResult.responseCode == BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED) {
            appContext?.let { refreshFromPlay(it, force = true) }
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
