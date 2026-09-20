package com.cashsdk.billing

import com.cashsdk.CashSDKError
import com.cashsdk.PurchaseOptions
import com.cashsdk.SubscriptionReplacementMode

/** Store-localized price plus the complete billing phase, not just its first price. */
data class PricingPhase(
    val formattedPrice: String,
    val priceAmountMicros: Long,
    val currencyCode: String,
    val billingPeriod: String,
    val billingCycleCount: Int,
    val recurrenceMode: Int,
)

data class StoreOffer(
    val basePlanId: String,
    val offerId: String?,
    val offerToken: String,
    val phases: List<PricingPhase>,
    val tags: List<String> = emptyList(),
) {
    // Play's INFINITE_RECURRING = 1. Prepaid plans use their final non-recurring phase.
    val regularPhase: PricingPhase? get() = phases.lastOrNull { it.recurrenceMode == 1 } ?: phases.lastOrNull()
}

internal fun selectOffer(offers: List<StoreOffer>, options: PurchaseOptions): StoreOffer {
    val candidates = offers.filter {
        (options.basePlanId == null || it.basePlanId == options.basePlanId) &&
            (options.offerId == null || it.offerId == options.offerId) &&
            (options.offerToken == null || it.offerToken == options.offerToken)
    }.filter { options.offerId != null || options.offerToken != null || it.offerId == null }
    if (candidates.isEmpty()) throw CashSDKError.InvalidPurchaseOptions("The requested Play base plan or offer is unavailable")
    if (candidates.size != 1) {
        throw CashSDKError.InvalidPurchaseOptions("This offer selector is ambiguous; also supply basePlanId")
    }
    return candidates.single()
}

internal data class ReplacementCandidate(
    val productIds: List<String>, val purchaseToken: String, val accountToken: String?, val purchased: Boolean,
)

internal class SelectedReplacement(
    val productId: String, val purchaseToken: String, val mode: SubscriptionReplacementMode,
)

internal fun selectReplacement(
    productId: String,
    offer: StoreOffer,
    owned: List<ReplacementCandidate>,
    userId: String,
    options: PurchaseOptions,
): SelectedReplacement? {
    val candidates = owned.filter {
        it.purchased && if (options.oldPurchaseToken != null) it.purchaseToken == options.oldPurchaseToken else productId in it.productIds
    }
    if (candidates.isEmpty()) {
        if (options.oldPurchaseToken != null) throw CashSDKError.InvalidPurchaseOptions("The subscription to replace is not currently owned")
        return null
    }
    if (candidates.size != 1 || candidates.single().productIds.size != 1) {
        throw CashSDKError.InvalidPurchaseOptions("The subscription replacement is ambiguous; multi-item replacements are not supported")
    }
    val old = candidates.single()
    // Explicit migration may use a legacy token; automatic account switching must not do so.
    if (options.oldPurchaseToken == null && !canAutomaticallySyncPurchase(userId, old.accountToken)) {
        throw CashSDKError.PurchaseNotAttributed
    }
    val oldProduct = old.productIds.single()
    val prepaid = offer.regularPhase?.recurrenceMode == 3
    val mode = options.replacementMode ?: when {
        prepaid -> SubscriptionReplacementMode.CHARGE_FULL_PRICE
        oldProduct == productId -> SubscriptionReplacementMode.WITHOUT_PRORATION
        else -> SubscriptionReplacementMode.WITH_TIME_PRORATION
    }
    if (prepaid && mode != SubscriptionReplacementMode.CHARGE_FULL_PRICE) {
        throw CashSDKError.InvalidPurchaseOptions("A prepaid replacement requires CHARGE_FULL_PRICE")
    }
    if (oldProduct == productId && !prepaid && mode !in listOf(SubscriptionReplacementMode.CHARGE_FULL_PRICE, SubscriptionReplacementMode.WITHOUT_PRORATION)) {
        throw CashSDKError.InvalidPurchaseOptions("A same-product auto-renewing plan change requires WITHOUT_PRORATION or CHARGE_FULL_PRICE")
    }
    return SelectedReplacement(oldProduct, old.purchaseToken, mode)
}
