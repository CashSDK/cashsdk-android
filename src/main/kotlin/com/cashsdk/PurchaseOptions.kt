package com.cashsdk

/** Explicit store choices. Omit offer selectors to buy a base plan, never an arbitrary trial. */
data class PurchaseOptions(
    val basePlanId: String? = null,
    val offerId: String? = null,
    val offerToken: String? = null,
    val oldPurchaseToken: String? = null,
    val replacementMode: SubscriptionReplacementMode? = null,
    val isOfferPersonalized: Boolean = false,
) {
    init {
        require(listOf(basePlanId, offerId, offerToken, oldPurchaseToken).all { it == null || it.isNotBlank() }) {
            "Purchase selectors must be nonblank when supplied"
        }
        require(replacementMode == null || oldPurchaseToken != null) {
            "replacementMode requires oldPurchaseToken"
        }
    }

    // Purchase tokens are sensitive; do not include them in crash logs or debug strings.
    override fun toString(): String = "PurchaseOptions(basePlanId=$basePlanId, offerId=$offerId, hasOfferToken=${offerToken != null}, hasReplacement=${oldPurchaseToken != null}, replacementMode=$replacementMode, isOfferPersonalized=$isOfferPersonalized)"
}

/** CashSDK-owned enum; the Billing 9 mapping is private to the adapter. */
enum class SubscriptionReplacementMode {
    WITH_TIME_PRORATION, CHARGE_PRORATED_PRICE, WITHOUT_PRORATION, CHARGE_FULL_PRICE, DEFERRED,
}
