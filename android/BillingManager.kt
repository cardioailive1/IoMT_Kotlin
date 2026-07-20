package com.cardioai.iomt.data.billing

// BillingManager.kt
//
// Android equivalent of SubscriptionManager.swift — gates the entire app
// behind a $12.99/month subscription using Google Play Billing.
//
// SECURITY NOTE — same caveat as iOS, read before relying on this in
// production: this checks entitlement via Play Billing's purchase query,
// which is sufficient to gate the APP UI. It does NOT gate your BACKEND
// API. To close that gap, you'd need to:
//   1. Send Google Play Real-time Developer Notifications (RTDN) to a new
//      backend webhook endpoint whenever a subscription starts/renews/expires
//   2. Store subscription status in the `users` table
//   3. Add a check in your auth middleware / route handlers that refuses
//      access for patients without an active subscription
// Not built in this pass — same gap as iOS, flagged explicitly rather than
// implying the paywall is airtight.
//
// TWO REAL PLATFORM LIMITATIONS, DIFFERENT FROM iOS — read before
// assuming Android can do everything StoreKit 2 does:
//   1. The client-side Billing Library does NOT expose a subscription's
//      renewal/expiry date — that only exists via Google's server-side
//      Play Developer API (Purchases.subscriptions.get), which requires
//      backend work not built here. There is no client SDK call that
//      returns it, unlike iOS's Transaction.expirationDate.
//   2. There is no in-app API for a third-party app to trigger Google's
//      refund flow — nothing analogous to StoreKit 2's
//      beginRefundRequest(). Refunds go through Google Play's own UI
//      entirely; this app can only deep-link the user there.

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.android.billingclient.api.*
import com.cardioai.iomt.data.network.ApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val TAG = "BillingManager"

object SubscriptionProductID {
    const val MONTHLY = "cardioai_live_premium_monthly"
}

class BillingManager(
    private val appContext: Context,
    private val apiClient: ApiClient,
) : PurchasesUpdatedListener {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _isSubscribed = MutableStateFlow(false)
    val isSubscribed: StateFlow<Boolean> = _isSubscribed.asStateFlow()

    private val _productDetails = MutableStateFlow<ProductDetails?>(null)
    val productDetails: StateFlow<ProductDetails?> = _productDetails.asStateFlow()

    /** The offer actually used for purchase — preferring one with a free
     * trial phase if the base plan has multiple offers configured (e.g.
     * one for new subscribers with a trial, one without for renewals).
     * Exposed so the paywall UI can describe exactly what will happen. */
    private val _selectedOffer = MutableStateFlow<ProductDetails.SubscriptionOfferDetails?>(null)
    val selectedOffer: StateFlow<ProductDetails.SubscriptionOfferDetails?> = _selectedOffer.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _purchaseError = MutableStateFlow<String?>(null)
    val purchaseError: StateFlow<String?> = _purchaseError.asStateFlow()

    private val billingClient: BillingClient = BillingClient.newBuilder(appContext)
        .setListener(this)
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder().enableOneTimeProducts().build()
        )
        .build()

    init {
        connect()
    }

    private fun connect() {
        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    queryProductDetails()
                    refreshEntitlementStatus()
                } else {
                    _purchaseError.value = "Billing setup failed: ${result.debugMessage}"
                    _isLoading.value = false
                }
            }

            override fun onBillingServiceDisconnected() {
                Log.w(TAG, "Billing service disconnected — will retry on next explicit call")
            }
        })
    }

    private fun queryProductDetails() {
        val product = QueryProductDetailsParams.Product.newBuilder()
            .setProductId(SubscriptionProductID.MONTHLY)
            .setProductType(BillingClient.ProductType.SUBS)
            .build()

        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(listOf(product))
            .build()

        billingClient.queryProductDetailsAsync(params) { result, productDetailsResult ->
            _isLoading.value = false
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                val details = productDetailsResult.productDetailsList.firstOrNull()
                _productDetails.value = details
                _selectedOffer.value = selectBestOffer(details)
            } else {
                _purchaseError.value = "Could not load subscription info: ${result.debugMessage}"
            }
        }
    }

    /**
     * A base plan can have multiple offers configured in Play Console —
     * e.g. one offer with a free-trial phase for first-time subscribers,
     * and a plain recurring-price offer for everyone else (renewals,
     * previous subscribers who already used their trial). This picks the
     * trial offer when one exists, since that's what we want new
     * subscribers to see; Google Play itself is still the source of
     * truth for whether a given account is actually trial-eligible —
     * if they're not, Play silently skips the trial phase at checkout
     * even if we offer this token, so this is safe either way.
     */
    private fun selectBestOffer(details: ProductDetails?): ProductDetails.SubscriptionOfferDetails? {
        val offers = details?.subscriptionOfferDetails ?: return null
        val trialOffer = offers.firstOrNull { offer ->
            offer.pricingPhases.pricingPhaseList.any { it.priceAmountMicros == 0L }
        }
        return trialOffer ?: offers.firstOrNull()
    }

    /**
     * Human-readable trial description for the paywall, e.g. "7 days
     * free, then $12.99/month" — built from whatever free-trial phase is
     * actually configured in Play Console, rather than a hardcoded
     * string, so this stays correct if the offer terms change there
     * without a code update. Returns null if the selected offer has no
     * free trial phase.
     */
    fun trialOfferDescription(): String? {
        val phases = _selectedOffer.value?.pricingPhases?.pricingPhaseList ?: return null
        val trialPhase = phases.firstOrNull { it.priceAmountMicros == 0L } ?: return null
        val recurringPhase = phases.firstOrNull { it.priceAmountMicros > 0L }

        val trialLength = describeIso8601Period(trialPhase.billingPeriod)
        val recurringPrice = recurringPhase?.formattedPrice ?: "12.99"
        return "$trialLength free, then $recurringPrice/month"
    }

    /**
     * Play Billing's `billingPeriod` is a raw ISO 8601 duration string
     * (e.g. "P7D" = 7 days, "P1W" = 1 week, "P1M" = 1 month) — this parses
     * just the subset Play actually uses (single-unit periods) into a
     * human-readable label like "7 days".
     */
    private fun describeIso8601Period(period: String): String {
        val match = Regex("""P(\d+)([DWMY])""").find(period) ?: return period
        val (valueStr, unitCode) = match.destructured
        val value = valueStr.toIntOrNull() ?: return period
        val unitName = when (unitCode) {
            "D" -> if (value == 1) "day" else "days"
            "W" -> if (value == 1) "week" else "weeks"
            "M" -> if (value == 1) "month" else "months"
            "Y" -> if (value == 1) "year" else "years"
            else -> "period"
        }
        return "$value $unitName"
    }

    fun hasTrialOffer(): Boolean =
        _selectedOffer.value?.pricingPhases?.pricingPhaseList?.any { it.priceAmountMicros == 0L } == true

    /** Call from the composable/activity that hosts the paywall button. */
    fun launchPurchaseFlow(activity: Activity) {
        val details = _productDetails.value
        if (details == null) {
            _purchaseError.value = "Subscription product not loaded yet — try again in a moment."
            return
        }
        _purchaseError.value = null

        val offerToken = _selectedOffer.value?.offerToken
            ?: details.subscriptionOfferDetails?.firstOrNull()?.offerToken
        if (offerToken == null) {
            _purchaseError.value = "No subscription offer available for this product."
            return
        }

        val productDetailsParamsList = listOf(
            BillingFlowParams.ProductDetailsParams.newBuilder()
                .setProductDetails(details)
                .setOfferToken(offerToken)
                .build()
        )

        val billingFlowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(productDetailsParamsList)
            .build()

        val result = billingClient.launchBillingFlow(activity, billingFlowParams)
        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            _purchaseError.value = "Could not start purchase: ${result.debugMessage}"
        }
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                purchases?.forEach { handlePurchase(it) }
            }
            BillingClient.BillingResponseCode.USER_CANCELED -> {
                // no-op, user backed out of the purchase sheet
            }
            else -> {
                _purchaseError.value = "Purchase failed: ${result.debugMessage}"
            }
        }
    }

    private fun handlePurchase(purchase: Purchase) {
        if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) return

        if (!purchase.isAcknowledged) {
            val ackParams = AcknowledgePurchaseParams.newBuilder()
                .setPurchaseToken(purchase.purchaseToken)
                .build()
            billingClient.acknowledgePurchase(ackParams) { ackResult ->
                if (ackResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    refreshEntitlementStatus()
                    linkSubscriptionToBackend(purchase)
                }
            }
        } else {
            refreshEntitlementStatus()
            linkSubscriptionToBackend(purchase)
        }
    }

    /**
     * Tells the backend which store purchase belongs to the currently
     * signed-in user — without this call, Google's RTDN webhook
     * notifications (which identify purchases by purchase token, not by
     * our internal user_id) have no way to know whose subscription
     * status to update. Best-effort: if this fails (e.g. no network
     * right after purchase), the app UI is still unlocked via the local
     * Play Billing entitlement check — this only affects server-side
     * enforcement, which will simply retry linking next time
     * refreshEntitlementStatus() runs and a purchase is found again.
     */
    private fun linkSubscriptionToBackend(purchase: Purchase) {
        val productId = purchase.products.firstOrNull() ?: SubscriptionProductID.MONTHLY
        scope.launch {
            try {
                apiClient.linkSubscription(
                    platform = "google",
                    transactionId = purchase.purchaseToken,
                    productId = productId,
                )
            } catch (e: Exception) {
                Log.w(TAG, "failed to link subscription to backend: ${e.message}")
            }
        }
    }

    fun refreshEntitlementStatus() {
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.SUBS)
            .build()

        billingClient.queryPurchasesAsync(params) { result, purchases ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                val activePurchase = purchases.firstOrNull { purchase ->
                    purchase.products.contains(SubscriptionProductID.MONTHLY) &&
                        purchase.purchaseState == Purchase.PurchaseState.PURCHASED
                }
                _isSubscribed.value = activePurchase != null
                // Covers fresh installs / re-logins where an active
                // subscription already exists on this Google account but
                // this specific backend user_id has never been linked to
                // it before (e.g. reinstalled the app, or restored on a
                // new device).
                activePurchase?.let { linkSubscriptionToBackend(it) }
            }
        }
    }

    fun disconnect() {
        billingClient.endConnection()
    }

    /**
     * Opens Google Play's own subscription management page for this
     * exact subscription — this is where the real renewal/expiry date
     * lives (not available via the client Billing Library at all), and
     * where the user cancels or requests a refund through Google's own
     * flow. There is no custom in-app equivalent to build here; Google
     * Play policy expects cancellation to go through this official page,
     * mirroring Apple's requirement on iOS.
     */
    fun openManageSubscriptions(activity: Activity) {
        val packageName = appContext.packageName
        val uri = Uri.parse(
            "https://play.google.com/store/account/subscriptions" +
                "?sku=${SubscriptionProductID.MONTHLY}&package=$packageName"
        )
        val intent = Intent(Intent.ACTION_VIEW, uri)
        activity.startActivity(intent)
    }
}
