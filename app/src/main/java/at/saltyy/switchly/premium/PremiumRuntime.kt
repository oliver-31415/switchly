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
import at.saltyy.switchly.R
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

    private data class BillingConnectionFailure(
        val responseCode: Int,
        val debugMessage: String,
    )

    private data class ConnectionCallback(
        val onReady: () -> Unit,
        val onError: (BillingConnectionFailure) -> Unit,
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
    private fun ensureClient(
        context: Context,
        onReady: () -> Unit,
        onError: (BillingConnectionFailure) -> Unit = {},
    ) {
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
                        // Keep the Play service connection recoverable if it drops while the purchase sheet is being opened.
                        .enableAutoServiceReconnection()
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
                AppLogStore.append(
                    context.applicationContext,
                    "Billing",
                    "Setup finished code=${result.responseCode} message=${result.debugMessage.ifBlank { "-" }}"
                )
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
                    val failure = BillingConnectionFailure(
                        responseCode = result.responseCode,
                        debugMessage = result.debugMessage,
                    )
                    val diagnosticMessage =
                        "Google Play Billing setup failed code=${result.responseCode} " +
                            "message=${result.debugMessage.ifBlank { "-" }}"
                    Log.e(TAG, diagnosticMessage)
                    synchronized(connectionLock) {
                        if (billingClient === client) {
                            billingClient = null
                        }
                    }
                    runCatching { client.endConnection() }
                    callbacks.forEach { callback -> callback.onError(failure) }
                }
            }

            override fun onBillingServiceDisconnected() {
                val message = "Google Play Billing service disconnected."
                Log.w(TAG, message)
                AppLogStore.append(context.applicationContext, "Billing", message)
                // Automatic service reconnection is enabled on this client.
                // Keep the client and pending callbacks intact so the next Billing API call can reconnect internally.
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
            onError = { failure ->
                AppLogStore.appendRateLimited(
                    applicationContext,
                    "Billing",
                    "Premium purchase check unavailable",
                    IllegalStateException(
                        "Billing setup code=${failure.responseCode} " +
                            "message=${failure.debugMessage.ifBlank { "-" }}"
                    ),
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
            onError = { failure ->
                fail(
                    "Google Play price connection failed code=${failure.responseCode} " +
                        "message=${failure.debugMessage.ifBlank { "-" }}"
                )
            }
        )
    }

    private fun ProductDetails.localizedOneTimePrice(): String? =
        oneTimePurchaseOfferDetails?.formattedPrice?.takeIf { it.isNotBlank() }

    // Starts the purchase flow for the given product.
    fun launchPurchase(
        activity: Activity,
        productId: String,
        onResult: ((started: Boolean, message: String?) -> Unit)? = null,
    ) {
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
            val diagnostic =
                "Google Play Billing is disabled for this build. Variant=${BuildConfig.SWITCHLY_APK_VARIANT}."
            if (BuildConfig.DEBUG) Log.d(TAG, diagnostic)
            AppLogStore.append(activity.applicationContext, "Billing", diagnostic)
            onResult?.invoke(false, activity.getString(R.string.premium_payments_unavailable))
            return
        }

        val launchGeneration = beginPurchaseLaunch()
        if (launchGeneration == null) {
            AppLogStore.append(
                activity.applicationContext,
                "Billing",
                "Purchase ignored reason=launch_already_in_progress"
            )
            activity.runOnUiThread {
                onResult?.invoke(false, activity.getString(R.string.premium_purchase_already_opening))
            }
            return
        }

        fun failWithUserMessage(userMessage: String, diagnosticMessage: String) {
            clearPurchaseLaunch(launchGeneration)
            Log.e(TAG, diagnosticMessage)
            AppLogStore.append(activity.applicationContext, "Billing", diagnosticMessage)
            activity.runOnUiThread { onResult?.invoke(false, userMessage) }
        }

        fun failGeneric(diagnosticMessage: String) {
            failWithUserMessage(
                activity.getString(R.string.premium_purchase_error_generic),
                diagnosticMessage,
            )
        }

        fun failConnection(diagnosticMessage: String) {
            failWithUserMessage(
                activity.getString(R.string.premium_purchase_google_play_unavailable),
                diagnosticMessage,
            )
        }

        fun retryDisconnectedStage(stage: String, diagnosticMessage: String) {
            if (!retryAfterDisconnect) {
                failConnection(diagnosticMessage)
                return
            }

            AppLogStore.append(
                activity.applicationContext,
                "Billing",
                "Purchase stage=$stage disconnected; resetting Billing client and retrying once"
            )
            resetClient()
            clearPurchaseLaunch(launchGeneration)
            launchPurchaseInternal(
                activity,
                productId,
                retryAfterDisconnect = false,
                onResult = onResult,
            )
        }

        fun restoreOwnedPurchase(diagnosticMessage: String) {
            clearPurchaseLaunch(launchGeneration)
            AppLogStore.append(activity.applicationContext, "Billing", diagnosticMessage)
            refreshFromPlay(activity.applicationContext, force = true)
            activity.runOnUiThread {
                onResult?.invoke(false, activity.getString(R.string.premium_already_owned))
            }
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
                    failConnection("Google Play Billing was not ready when opening the purchase.")
                    return@ensureClient
                }

                queryOwnedPremium(client) { ownedResult, alreadyOwned ->
                    if (!isCurrentPurchaseLaunch(launchGeneration)) {
                        return@queryOwnedPremium
                    }

                    if (ownedResult.responseCode != BillingClient.BillingResponseCode.OK) {
                        val diagnostic =
                            "Owned purchase query failed code=${ownedResult.responseCode} " +
                                "message=${ownedResult.debugMessage.ifBlank { "-" }}"
                        if (ownedResult.responseCode == BillingClient.BillingResponseCode.SERVICE_DISCONNECTED) {
                            retryDisconnectedStage("owned_purchase_query", diagnostic)
                        } else {
                            failGeneric(diagnostic)
                        }
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
                                    val diagnostic =
                                        "Google Play product query failed code=${billingResult.responseCode} " +
                                            "message=${billingResult.debugMessage.ifBlank { "-" }}"
                                    if (billingResult.responseCode == BillingClient.BillingResponseCode.SERVICE_DISCONNECTED) {
                                        retryDisconnectedStage("product_details", diagnostic)
                                    } else {
                                        failGeneric(diagnostic)
                                    }
                                    return
                                }

                                AppLogStore.append(
                                    activity.applicationContext,
                                    "Billing",
                                    "Product query code=${billingResult.responseCode} products=${result.productDetailsList.size}"
                                )
                                val details = result.productDetailsList.firstOrNull()
                                if (details == null) {
                                    failGeneric(
                                        "Google Play returned no product details for '$productId'. " +
                                            "Check the in-app product ID and Play Console activation."
                                    )
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
                                        failGeneric(
                                            "Google Play purchase flow could not open because the Premium screen was closing."
                                        )
                                        return@runOnUiThread
                                    }

                                    runCatching {
                                        val response = client.launchBillingFlow(activity, flowParams)
                                        AppLogStore.append(
                                            activity.applicationContext,
                                            "Billing",
                                            "Purchase flow launch code=${response.responseCode} " +
                                                "message=${response.debugMessage.ifBlank { "-" }}"
                                        )
                                        if (BuildConfig.DEBUG) {
                                            Log.d(
                                                TAG,
                                                "launchBillingFlow result: ${response.responseCode} ${response.debugMessage}"
                                            )
                                        }
                                        when (response.responseCode) {
                                            BillingClient.BillingResponseCode.OK -> {
                                                // Keep the guard active until Play Billing calls onPurchasesUpdated
                                                // or the Premium screen resumes after the billing UI closes.
                                                onResult?.invoke(true, null)
                                            }

                                            BillingClient.BillingResponseCode.SERVICE_DISCONNECTED -> {
                                                val diagnostic =
                                                    "Google Play purchase flow did not open code=${response.responseCode} " +
                                                        "message=${response.debugMessage.ifBlank { "-" }}"
                                                retryDisconnectedStage("launch_billing_flow", diagnostic)
                                            }

                                            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> {
                                                restoreOwnedPurchase(
                                                    "Google Play reports Premium is already owned; restoring purchase."
                                                )
                                            }

                                            else -> {
                                                if (isDuplicateQuickAttempt(response)) {
                                                    restoreOwnedPurchase(
                                                        "Google Play is still processing the previous purchase attempt. " +
                                                            "Checking existing purchases."
                                                    )
                                                } else {
                                                    failGeneric(
                                                        "Google Play purchase flow did not open " +
                                                            "code=${response.responseCode} " +
                                                            "message=${response.debugMessage.ifBlank { "-" }}"
                                                    )
                                                }
                                            }
                                        }
                                    }.onFailure { throwable ->
                                        failGeneric(
                                            "Google Play purchase flow crashed: " +
                                                (throwable.message ?: throwable::class.java.simpleName)
                                        )
                                    }
                                }
                            }
                        }
                    )
                }
            },
            onError = { failure ->
                val diagnostic =
                    "Google Play Billing setup failed code=${failure.responseCode} " +
                        "message=${failure.debugMessage.ifBlank { "-" }}"
                if (failure.responseCode == BillingClient.BillingResponseCode.SERVICE_DISCONNECTED) {
                    retryDisconnectedStage("billing_setup", diagnostic)
                } else {
                    failConnection(diagnostic)
                }
            }
        )
    }

    private fun queryOwnedPremium(
        client: BillingClient,
        onResult: (BillingResult, Boolean) -> Unit,
    ) {
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.INAPP)
            .build()
        client.queryPurchasesAsync(params) { result, purchasesList ->
            appContext?.let { context ->
                AppLogStore.append(
                    context,
                    "Billing",
                    "Owned purchase query code=${result.responseCode} purchases=${purchasesList.size} " +
                        "message=${result.debugMessage.ifBlank { "-" }}"
                )
            }
            val alreadyOwned = if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                purchasesList.any { purchase ->
                    purchase.products.contains(PRODUCT_ID) &&
                        purchase.purchaseState == Purchase.PurchaseState.PURCHASED
                }
            } else {
                false
            }
            onResult(result, alreadyOwned)
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
