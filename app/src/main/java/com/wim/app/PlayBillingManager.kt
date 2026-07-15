package com.wim.app

import android.app.Activity
import androidx.lifecycle.LifecycleCoroutineScope
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import kotlinx.coroutines.launch
import java.security.MessageDigest

data class CloudUnlockState(
    val unlocked: Boolean = false,
    val price: String? = null,
    val message: String = "Checking access…",
    val canBuy: Boolean = false,
)

/** Owns the Play Billing connection; the server remains the entitlement authority. */
class PlayBillingManager(
    private val activity: Activity,
    private val scope: LifecycleCoroutineScope,
    private val onState: (CloudUnlockState) -> Unit,
) : PurchasesUpdatedListener {

    private var productDetails: ProductDetails? = null
    private var state = CloudUnlockState(unlocked = WimApp.cloudUnlocked())

    private val billingClient = BillingClient.newBuilder(activity)
        .setListener(this)
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder().enableOneTimeProducts().build()
        )
        .enableAutoServiceReconnection()
        .build()

    fun start() {
        refreshBackendStatus()
        if (billingClient.isReady) {
            queryProduct()
            restorePurchases()
            return
        }
        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    queryProduct()
                    restorePurchases()
                } else {
                    publish(message = "Google Play billing unavailable", canBuy = false)
                }
            }

            override fun onBillingServiceDisconnected() {
                publish(message = "Reconnecting to Google Play…", canBuy = false)
            }
        })
    }

    fun refresh() {
        refreshBackendStatus()
        if (billingClient.isReady) restorePurchases() else start()
    }

    fun launchPurchase() {
        if (state.unlocked) return
        val user = AuthManager.currentUser
        if (user == null) {
            publish(message = "Sign in first, then unlock", canBuy = productDetails != null)
            return
        }
        val details = productDetails
        if (details == null || !billingClient.isReady) {
            publish(message = "Connecting to Google Play…", canBuy = false)
            start()
            return
        }
        val productParams = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(details)
            .build()
        val flowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(productParams))
            .setObfuscatedAccountId(accountHash(user.uid))
            .build()
        val result = billingClient.launchBillingFlow(activity, flowParams)
        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            publish(message = result.debugMessage.ifBlank { "Could not open purchase" })
        }
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> purchases.orEmpty().forEach(::processPurchase)
            BillingClient.BillingResponseCode.USER_CANCELED -> publish(message = "Purchase canceled")
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> restorePurchases()
            else -> publish(message = result.debugMessage.ifBlank { "Purchase did not complete" })
        }
    }

    fun close() {
        billingClient.endConnection()
    }

    private fun queryProduct() {
        val product = QueryProductDetailsParams.Product.newBuilder()
            .setProductId(BillingBackendClient.PRODUCT_ID)
            .setProductType(BillingClient.ProductType.INAPP)
            .build()
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(listOf(product))
            .build()
        billingClient.queryProductDetailsAsync(params) { result, detailsResult ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                publish(message = "Could not load Play Store price", canBuy = false)
                return@queryProductDetailsAsync
            }
            productDetails = detailsResult.productDetailsList.firstOrNull()
            val price = productDetails?.oneTimePurchaseOfferDetails?.formattedPrice
            publish(
                price = price,
                message = if (state.unlocked) "Permanent cloud access is active"
                    else if (productDetails == null) "Cloud Unlock is not configured in Play Console"
                    else "One purchase. Permanent cloud access.",
                canBuy = productDetails != null && !state.unlocked,
            )
        }
    }

    private fun restorePurchases() {
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.INAPP)
            .build()
        billingClient.queryPurchasesAsync(params) { result, purchases ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                purchases.filter { BillingBackendClient.PRODUCT_ID in it.products }
                    .forEach(::processPurchase)
            }
        }
    }

    private fun processPurchase(purchase: Purchase) {
        when (purchase.purchaseState) {
            Purchase.PurchaseState.PENDING -> publish(message = "Purchase pending — access starts after payment completes")
            Purchase.PurchaseState.PURCHASED -> verifyOnServer(purchase.purchaseToken)
            else -> Unit
        }
    }

    private fun verifyOnServer(token: String) {
        val uid = AuthManager.currentUser?.uid ?: return
        publish(message = "Verifying purchase…", canBuy = false)
        scope.launch {
            val entitlement = BillingBackendClient.verify(token)
            if (entitlement?.unlocked == true) {
                WimApp.setCloudUnlocked(uid, true)
                publish(unlocked = true, message = "Permanent cloud access is active", canBuy = false)
            } else {
                publish(message = "Could not verify purchase — try Restore", canBuy = productDetails != null)
            }
        }
    }

    private fun refreshBackendStatus() {
        val uid = AuthManager.currentUser?.uid
        if (uid == null) {
            WimApp.clearCloudUnlock()
            publish(unlocked = false, message = "Sign in to unlock cloud speech", canBuy = productDetails != null)
            return
        }
        scope.launch {
            val entitlement = BillingBackendClient.status()
            if (entitlement != null) {
                WimApp.setCloudUnlocked(uid, entitlement.unlocked)
                publish(
                    unlocked = entitlement.unlocked,
                    message = if (entitlement.unlocked) "Permanent cloud access is active"
                        else "One purchase. Permanent cloud access.",
                    canBuy = !entitlement.unlocked && productDetails != null,
                )
            }
        }
    }

    private fun publish(
        unlocked: Boolean = state.unlocked,
        price: String? = state.price,
        message: String = state.message,
        canBuy: Boolean = state.canBuy,
    ) {
        state = CloudUnlockState(unlocked, price, message, canBuy)
        activity.runOnUiThread { onState(state) }
    }

    companion object {
        internal fun accountHash(uid: String): String = MessageDigest.getInstance("SHA-256")
            .digest(uid.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
