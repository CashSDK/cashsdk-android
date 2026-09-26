package com.cashsdk.billing

import com.cashsdk.CashSDKError
import com.cashsdk.PurchaseOptions
import com.cashsdk.SubscriptionReplacementMode
import com.cashsdk.model.Entitlements

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

    /**
     * The free first phase of a free-trial offer (its `billingPeriod` is the trial length), or
     * null when this is a base plan or its first phase costs money (a paid introductory price).
     */
    val freeTrialPhase: PricingPhase?
        get() = phases.firstOrNull()?.takeIf { offerId != null && phases.size > 1 && it.priceAmountMicros == 0L }

    /**
     * True when this offer starts with a free trial. Google Play only lists an offer to a user
     * who is eligible for it, so a trial offer in a fresh `products()` result is one this user can
     * start now. Show trial copy only for such an offer, and buy it by passing its [offerToken].
     */
    val isFreeTrial: Boolean get() = freeTrialPhase != null
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

/** What a subscription purchase has to do about the subscriptions this Google account already holds. */
internal sealed interface ReplacementPlan {
    /** Nothing to replace: a plain purchase. */
    data object None : ReplacementPlan

    data class Replace(val replacement: SelectedReplacement) : ReplacementPlan

    /**
     * The account holds [candidate] under a token that is not this user's: bought in the Play
     * Store or with a promo code (no token), before CashSDK, or by another app account. The
     * server has to say whose it is before anything is charged.
     */
    data class ConfirmOwnership(val candidate: ReplacementCandidate) : ReplacementPlan
}

/**
 * Decide, before the payment sheet opens, whether this purchase replaces an owned subscription.
 *
 *  - An explicit [PurchaseOptions.oldPurchaseToken] replaces exactly that purchase. It may carry
 *    a legacy account token: that is the documented migration path.
 *  - An owned subscription to the same product is replaced (a plan change) when it is this
 *    user's. Otherwise its owner has to be confirmed first; this used to throw
 *    `PurchaseNotAttributed` ("call identify"), which identify can never fix.
 *  - With [PurchaseOptions.subscriptionFamily], an owned subscription to another product in the
 *    family is replaced the same way, so the user does not end up paying for two.
 *
 * [confirmedTokens] are purchases the server has just confirmed as this user's.
 */
internal fun planReplacement(
    productId: String,
    offer: StoreOffer,
    owned: List<ReplacementCandidate>,
    userId: String,
    options: PurchaseOptions,
    confirmedTokens: Set<String> = emptySet(),
): ReplacementPlan {
    val active = owned.filter { it.purchased }
    options.oldPurchaseToken?.let { token ->
        val named = active.filter { it.purchaseToken == token }
        if (named.isEmpty()) throw CashSDKError.InvalidPurchaseOptions("The subscription to replace is not currently owned")
        return ReplacementPlan.Replace(replacementFor(productId, offer, single(named), options))
    }
    fun ownedByUser(candidate: ReplacementCandidate) =
        canAutomaticallySyncPurchase(userId, candidate.accountToken) || candidate.purchaseToken in confirmedTokens

    val sameProduct = active.filter { productId in it.productIds }
    if (sameProduct.isNotEmpty()) {
        val old = single(sameProduct)
        return if (ownedByUser(old)) {
            ReplacementPlan.Replace(replacementFor(productId, offer, old, options))
        } else {
            ReplacementPlan.ConfirmOwnership(old)
        }
    }
    if (options.subscriptionFamily.isEmpty()) return ReplacementPlan.None
    val family = active.filter { candidate -> candidate.productIds.any { it in options.subscriptionFamily } }
    if (family.isEmpty()) return ReplacementPlan.None
    val old = single(family, "The user holds more than one subscription in this family; pass oldPurchaseToken")
    return if (ownedByUser(old)) {
        ReplacementPlan.Replace(replacementFor(productId, offer, old, options))
    } else {
        ReplacementPlan.ConfirmOwnership(old)
    }
}

/** How a subscription purchase goes ahead once the owned subscriptions are accounted for. */
internal sealed interface ReplacementResolution {
    /** Open Play: a plain purchase ([replacement] null) or a replacement of an owned subscription. */
    data class Buy(val replacement: SelectedReplacement?) : ReplacementResolution

    /**
     * The owned subscription is another app account's, shared with this user (restore policy
     * `share`). The user already has the access, and the subscription is not theirs to change:
     * nothing is bought or replaced.
     */
    data class Shared(val entitlements: Entitlements) : ReplacementResolution
}

/**
 * [planReplacement], asking the server first when an owned subscription it would replace is held
 * under a token that is not this user's. [confirmOwnership] verifies that purchase (claim
 * `sync`, which never moves a purchase between accounts) and throws when it is another
 * account's, so nothing is charged for it. Confirmed as this user's own, the purchase goes ahead
 * as a replacement of it, for the same product too: Play then makes the plan change, or answers
 * ITEM_ALREADY_OWNED for the very plan already held, which the already-owned recovery turns into
 * the user's entitlements. Confirmed as only shared with this user, it is [ReplacementResolution.Shared]:
 * opening Play there would change the owner's subscription.
 */
internal suspend fun resolveReplacement(
    productId: String,
    offer: StoreOffer,
    owned: List<ReplacementCandidate>,
    userId: String,
    options: PurchaseOptions,
    confirmOwnership: suspend (ReplacementCandidate) -> Entitlements,
): ReplacementResolution {
    var plan = planReplacement(productId, offer, owned, userId, options)
    if (plan is ReplacementPlan.ConfirmOwnership) {
        val held = plan.candidate
        val confirmed = confirmOwnership(held)
        if (confirmed.sharedFromAnotherAccount) return ReplacementResolution.Shared(confirmed)
        plan = planReplacement(productId, offer, owned, userId, options, confirmedTokens = setOf(held.purchaseToken))
    }
    return when (plan) {
        ReplacementPlan.None -> ReplacementResolution.Buy(null)
        is ReplacementPlan.Replace -> ReplacementResolution.Buy(plan.replacement)
        is ReplacementPlan.ConfirmOwnership ->
            throw CashSDKError.InvalidPurchaseOptions("The owned subscription to replace could not be confirmed; pass oldPurchaseToken")
    }
}

private fun single(
    candidates: List<ReplacementCandidate>,
    ambiguous: String = "The subscription replacement is ambiguous; multi-item replacements are not supported",
): ReplacementCandidate {
    if (candidates.size != 1 || candidates.single().productIds.size != 1) {
        throw CashSDKError.InvalidPurchaseOptions(ambiguous)
    }
    return candidates.single()
}

private fun replacementFor(
    productId: String,
    offer: StoreOffer,
    old: ReplacementCandidate,
    options: PurchaseOptions,
): SelectedReplacement {
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
