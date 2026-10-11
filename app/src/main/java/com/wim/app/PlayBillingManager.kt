package com.wim.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
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
    val tier: String = "invite",
    val maxLayer: Int = 1,
    /** productId -> "$4.99 / month", as Google Play formats it for this user. */
    val prices: Map<String, String> = emptyMap(),
    val message: String = "Checking access…",
    val canBuy: Boolean = false,
)

/** Owns the Play Billing connection; the server remains the entitlement authority. */
class PlayBillingManager(
    private val activity: Activity,
    private val scope: LifecycleCoroutineScope,
    private val onState: (CloudUnlockState) -> Unit,
) : PurchasesUpdatedListener {

    private val productDetails = mutableMapOf<String, ProductDetails>()
    private val offerTokens = mutableMapOf<String, String>()
    /** The Play purchase this account currently holds, if any — needed to upgrade or downgrade in place. */
    private var ownedToken: String? = null
    private var ownedProduct: String? = null
    private var state = CloudUnlockState(
        unlocked = WimApp.cloudUnlocked(),
        tier = WimApp.cloudTier(),
        maxLayer = WimApp.cloudMaxLayer(),
    )

    private val billingClient = BillingClient.newBuilder(activity)
        .setListener(this)
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder().enableOneTimeProducts().build()
        )
        .enableAutoServiceReconnection()
        .build()

    fun start() {
        refreshBackendStatus()
        drainPendingVerifications()
        if (billingClient.isReady) {
            queryProducts()
            restorePurchases()
            return
        }
        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    queryProducts()
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
        drainPendingVerifications()
        if (billingClient.isReady) restorePurchases() else start()
    }

    /** Open Google Play's subscription page for the plan this account holds. */
    fun manageSubscription() {
        val sku = ownedProduct ?: BillingBackendClient.PRODUCT_ID
        val url = "https://play.google.com/store/account/subscriptions" +
            "?sku=$sku&package=${activity.packageName}"
        activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    /**
     * Buy [productId], or switch to it when the account already holds the other
     * plan. Google handles the switch inside one subscription: an upgrade is
     * charged the prorated difference now, a downgrade waits for the renewal
     * date. Tapping the plan already held opens the manage page instead.
     */
    fun launchPurchase(productId: String = BillingBackendClient.PRODUCT_ID) {
        if (state.unlocked && (ownedProduct == productId || state.tier == "pro")) {
            manageSubscription()
            return
        }
        val user = AuthManager.currentUser
        if (user == null) {
            publish(message = "Sign in first, then subscribe", canBuy = productDetails.isNotEmpty())
            return
        }
        val details = productDetails[productId]
        if (details == null || !billingClient.isReady) {
            publish(message = "Connecting to Google Play…", canBuy = false)
            start()
            return
        }
        val selectedOffer = offerTokens[productId]
        if (selectedOffer == null) {
            publish(message = "This plan is not configured in Google Play", canBuy = false)
            return
        }
        val productParams = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(details)
            .setOfferToken(selectedOffer)
            .build()
        val flow = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(productParams))
            .setObfuscatedAccountId(accountHash(user.uid))
        val oldToken = ownedToken
        if (oldToken != null && ownedProduct != null && ownedProduct != productId) {
            val upgrade = productId == BillingBackendClient.PLUS_PRODUCT_ID
            flow.setSubscriptionUpdateParams(
                BillingFlowParams.SubscriptionUpdateParams.newBuilder()
                    .setOldPurchaseToken(oldToken)
                    .setSubscriptionReplacementMode(
                        if (upgrade) BillingFlowParams.SubscriptionUpdateParams.ReplacementMode.CHARGE_PRORATED_PRICE
                        else BillingFlowParams.SubscriptionUpdateParams.ReplacementMode.DEFERRED
                    )
                    .build()
            )
        }
        val result = billingClient.launchBillingFlow(activity, flow.build())
        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            publish(message = result.debugMessage.ifBlank { "Could not open subscription" })
        }
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> purchases.orEmpty().forEach(::processPurchase)
            BillingClient.BillingResponseCode.USER_CANCELED -> publish(message = "Subscription not started")
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> restorePurchases()
            else -> publish(message = result.debugMessage.ifBlank { "Subscription did not complete" })
        }
    }

    fun close() {
        billingClient.endConnection()
    }

    private fun queryProducts() {
        val products = BillingBackendClient.PRODUCT_IDS.map { id ->
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(id)
                .setProductType(BillingClient.ProductType.SUBS)
                .build()
        }
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(products)
            .build()
        billingClient.queryProductDetailsAsync(params) { result, detailsResult ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                publish(message = "Could not load Play Store prices", canBuy = false)
                return@queryProductDetailsAsync
            }
            val prices = mutableMapOf<String, String>()
            productDetails.clear()
            offerTokens.clear()
            detailsResult.productDetailsList.forEach { details ->
                productDetails[details.productId] = details
                val offers = details.subscriptionOfferDetails.orEmpty()
                val selected = offers.firstOrNull {
                    it.basePlanId == BillingBackendClient.BASE_PLAN_ID && it.offerId == null
                } ?: offers.firstOrNull { it.basePlanId == BillingBackendClient.BASE_PLAN_ID }
                val token = selected?.offerToken ?: return@forEach
                offerTokens[details.productId] = token
                selected.pricingPhases.pricingPhaseList.lastOrNull()?.formattedPrice?.let {
                    prices[details.productId] = activity.getString(R.string.cloud_unlock_monthly_price, it)
                }
            }
            publish(
                prices = prices,
                message = when {
                    state.unlocked -> activity.getString(R.string.cloud_unlock_active, WimApp.tierLabel(state.tier))
                    offerTokens.isEmpty() -> "Plans are not configured in Play Console"
                    else -> activity.getString(R.string.cloud_unlock_plans_line)
                },
                canBuy = offerTokens.isNotEmpty(),
            )
        }
    }

    private fun restorePurchases() {
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.SUBS)
            .build()
        billingClient.queryPurchasesAsync(params) { result, purchases ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                purchases.filter { p -> p.products.any { it in BillingBackendClient.PRODUCT_IDS } }
                    .forEach(::processPurchase)
            }
        }
    }

    private fun processPurchase(purchase: Purchase) {
        val productId = purchase.products.firstOrNull { it in BillingBackendClient.PRODUCT_IDS } ?: return
        when (purchase.purchaseState) {
            Purchase.PurchaseState.PENDING -> publish(message = "Subscription pending — access starts after payment completes")
            Purchase.PurchaseState.PURCHASED -> {
                ownedToken = purchase.purchaseToken
                ownedProduct = productId
                verifyOnServer(purchase.purchaseToken, productId)
            }
            else -> Unit
        }
    }

    private fun verifyOnServer(token: String, productId: String) {
        val uid = AuthManager.currentUser?.uid
        if (uid == null) {
            // Do NOT drop this on the floor. The server acknowledges the
            // purchase to Google inside verify(), and Play auto-refunds and
            // revokes any purchase left unacknowledged past its window. This
            // path used to `return` silently, and it is reached on a race that
            // runs at every launch: restorePurchases() fires from start() while
            // Firebase is still restoring the session, so currentUser is null
            // for the first moments of the process. A paid subscriber could be
            // refunded for opening the app at the wrong speed.
            android.util.Log.w(
                "WiM-Billing",
                "verify deferred — purchase seen before sign-in restored; queued for retry",
            )
            pendingTokens[token] = productId
            publish(message = "Sign in to activate your subscription", canBuy = false)
            return
        }
        pendingTokens.remove(token)
        publish(message = "Verifying subscription…", canBuy = false)
        scope.launch {
            val entitlement = BillingBackendClient.verify(token, productId)
            if (entitlement?.unlocked == true) {
                WimApp.setCloudUnlocked(uid, true, entitlement.tier, entitlement.maxLayer)
                publish(
                    unlocked = true, tier = entitlement.tier, maxLayer = entitlement.maxLayer,
                    message = activity.getString(R.string.cloud_unlock_active, WimApp.tierLabel(entitlement.tier)),
                    canBuy = offerTokens.isNotEmpty(),
                )
            } else {
                // Network failure also means Google was never acknowledged, so
                // keep the token and try again on the next start/refresh rather
                // than relying on the user reading a toast and tapping Restore
                // inside the acknowledgement window.
                pendingTokens[token] = productId
                android.util.Log.w("WiM-Billing", "verify failed — token requeued for retry")
                publish(message = "Could not verify subscription — try Restore", canBuy = offerTokens.isNotEmpty())
            }
        }
    }

    /**
     * Purchase tokens seen but not yet confirmed with the backend, because the
     * user wasn't signed in yet or the call failed. Drained on every start and
     * refresh. Acknowledgement is time-critical — an unacknowledged purchase is
     * refunded by Google — so this must not depend on the user noticing a
     * message and pressing something.
     */
    private val pendingTokens = mutableMapOf<String, String>()

    private fun drainPendingVerifications() {
        if (pendingTokens.isEmpty() || AuthManager.currentUser == null) return
        android.util.Log.i("WiM-Billing", "retrying ${pendingTokens.size} deferred verification(s)")
        pendingTokens.toMap().forEach { (token, productId) -> verifyOnServer(token, productId) }
    }

    private fun refreshBackendStatus() {
        val uid = AuthManager.currentUser?.uid
        if (uid == null) {
            WimApp.clearCloudUnlock()
            publish(unlocked = false, tier = "invite", maxLayer = 1,
                message = "Sign in to use WiM Cloud", canBuy = offerTokens.isNotEmpty())
            return
        }
        scope.launch {
            // A uid exists now — anything queued while signed out can go.
            drainPendingVerifications()
            val entitlement = BillingBackendClient.status()
            if (entitlement != null) {
                WimApp.setCloudUnlocked(uid, entitlement.unlocked, entitlement.tier, entitlement.maxLayer)
                publish(
                    unlocked = entitlement.unlocked,
                    tier = entitlement.tier,
                    maxLayer = entitlement.maxLayer,
                    message = if (entitlement.unlocked)
                        activity.getString(R.string.cloud_unlock_active, WimApp.tierLabel(entitlement.tier))
                        else activity.getString(R.string.cloud_unlock_plans_line),
                    canBuy = offerTokens.isNotEmpty(),
                )
            }
        }
    }

    private fun publish(
        unlocked: Boolean = state.unlocked,
        tier: String = state.tier,
        maxLayer: Int = state.maxLayer,
        prices: Map<String, String> = state.prices,
        message: String = state.message,
        canBuy: Boolean = state.canBuy,
    ) {
        state = CloudUnlockState(unlocked, tier, maxLayer, prices, message, canBuy)
        activity.runOnUiThread { onState(state) }
    }

    companion object {
        internal fun accountHash(uid: String): String = MessageDigest.getInstance("SHA-256")
            .digest(uid.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
