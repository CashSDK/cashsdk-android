package com.cashsdk

/** Explicit store choices. Omit offer selectors to buy a base plan, never an arbitrary trial. */
data class PurchaseOptions @JvmOverloads constructor(
    val basePlanId: String? = null,
    val offerId: String? = null,
    val offerToken: String? = null,
    val oldPurchaseToken: String? = null,
    val replacementMode: SubscriptionReplacementMode? = null,
    val isOfferPersonalized: Boolean = false,
    /**
     * Subscription product ids that are alternatives to each other: tiers or durations of the
     * same service, where a user should never pay for two at once.
     *
     * Google Play has no subscription groups, so the SDK cannot tell on its own whether two
     * products belong together; an app can sell unrelated subscriptions that are meant to run
     * side by side. When this set is not empty and the user already holds an active subscription
     * to a DIFFERENT product in it, the purchase becomes a replacement of that subscription
     * ([replacementMode], default `WITH_TIME_PRORATION`; prepaid plans use `CHARGE_FULL_PRICE`)
     * instead of a second subscription. If the owned one is not provably this user's (bought
     * outside the app, or on another app account), the server is asked first and nothing is
     * charged until it confirms. Empty, the default, keeps a plain purchase.
     *
     * [oldPurchaseToken] still wins when both are given.
     */
    val subscriptionFamily: Set<String> = emptySet(),
) {
    init {
        require(listOf(basePlanId, offerId, offerToken, oldPurchaseToken).all { it == null || it.isNotBlank() }) {
            "Purchase selectors must be nonblank when supplied"
        }
        require(subscriptionFamily.all { it.isNotBlank() }) {
            "subscriptionFamily product ids must be nonblank"
        }
        require(replacementMode == null || oldPurchaseToken != null || subscriptionFamily.isNotEmpty()) {
            "replacementMode requires oldPurchaseToken or subscriptionFamily"
        }
    }

    // Purchase tokens are sensitive; do not include them in crash logs or debug strings.
    override fun toString(): String = "PurchaseOptions(basePlanId=$basePlanId, offerId=$offerId, hasOfferToken=${offerToken != null}, hasReplacement=${oldPurchaseToken != null}, replacementMode=$replacementMode, isOfferPersonalized=$isOfferPersonalized, subscriptionFamily=$subscriptionFamily)"
}

/** CashSDK-owned enum; the Billing 9 mapping is private to the adapter. */
enum class SubscriptionReplacementMode {
    WITH_TIME_PRORATION, CHARGE_PRORATED_PRICE, WITHOUT_PRORATION, CHARGE_FULL_PRICE, DEFERRED,
}
