package com.cashsdk

import com.cashsdk.billing.*
import org.junit.Assert.*
import org.junit.Test

class BillingManagerPurchaseTest {
    private val phase = PricingPhase("$9.99", 9_990_000, "USD", "P1M", 0, 1)
    private fun offer(plan: String = "monthly", id: String? = null) = StoreOffer(plan, id, "$plan:${id ?: "base"}", listOf(phase))
    private fun owned(id: String = "pro", token: String = "owned", user: String = "A", purchased: Boolean = true) =
        ReplacementCandidate(listOf(id), token, AppAccountToken.derive(user), purchased)

    @Test fun trialFirstDoesNotMeanTrialByDefault() {
        assertNull(selectOffer(listOf(offer(id = "trial"), offer()), PurchaseOptions()).offerId)
    }
    @Test fun ambiguousBasePlansRequireExplicitSelection() {
        val offers = listOf(offer("monthly"), offer("annual"), offer("annual", "trial"))
        assertThrows(CashSDKError.Billing::class.java) { selectOffer(offers, PurchaseOptions()) }
        assertThrows(CashSDKError.Billing::class.java) { selectOffer(offers.reversed(), PurchaseOptions()) }
        assertEquals("monthly", selectOffer(offers, PurchaseOptions(basePlanId = "monthly")).basePlanId)
    }
    @Test fun namedPlanAndOfferAreExact() {
        val offers = listOf(offer(), offer(id = "trial"), offer("annual", "trial"))
        assertEquals("monthly:trial", selectOffer(offers, PurchaseOptions(basePlanId = "monthly", offerId = "trial")).offerToken)
        assertThrows(CashSDKError.Billing::class.java) { selectOffer(offers, PurchaseOptions(offerId = "trial")) }
    }
    @Test fun missingOrConflictingSelectorsNeverFallBack() {
        val offers = listOf(offer(), offer(id = "trial"))
        for (options in listOf(PurchaseOptions(basePlanId = "absent"), PurchaseOptions(offerToken = "expired"), PurchaseOptions(offerId = "trial", offerToken = "monthly:base"))) {
            assertThrows(CashSDKError.Billing::class.java) { selectOffer(offers, options) }
        }
    }
    @Test fun capturedPriceTokenSelectsSameOfferAfterListReorder() {
        val offers = listOf(offer(), offer(id = "trial"))
        val shown = selectOffer(offers, PurchaseOptions())
        assertEquals(shown, selectOffer(offers.reversed(), PurchaseOptions(offerToken = shown.offerToken)))
    }
    @Test fun modeWithoutTokenAndBlankSelectorsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { PurchaseOptions(replacementMode = SubscriptionReplacementMode.DEFERRED) }
        assertThrows(IllegalArgumentException::class.java) { PurchaseOptions(offerToken = " ") }
    }
    @Test fun sameProductCarriesOldTokenWithSupportedMode() {
        val result = selectReplacement("pro", offer(), listOf(owned()), "A", PurchaseOptions())!!
        assertEquals("owned", result.purchaseToken)
        assertEquals("pro", result.productId)
        assertEquals(SubscriptionReplacementMode.WITHOUT_PRORATION, result.mode)
    }
    @Test fun unrelatedAndPendingPurchasesAreNotAutomaticallyReplaced() {
        assertNull(selectReplacement("pro", offer(), listOf(owned("other"), owned(purchased = false)), "A", PurchaseOptions()))
    }
    @Test fun explicitCrossProductReplacementCanBeDeferred() {
        val result = selectReplacement("star", offer(), listOf(owned("plus"), owned("other", "another")), "A",
            PurchaseOptions(oldPurchaseToken = "owned", replacementMode = SubscriptionReplacementMode.DEFERRED))!!
        assertEquals("plus", result.productId)
        assertEquals(SubscriptionReplacementMode.DEFERRED, result.mode)
    }
    @Test fun explicitTokenMustBeOwnedAndMultiItemPurchasesAreRejected() {
        assertThrows(CashSDKError.Billing::class.java) {
            selectReplacement("pro", offer(), emptyList(), "A", PurchaseOptions(oldPurchaseToken = "absent"))
        }
        assertThrows(CashSDKError.Billing::class.java) {
            selectReplacement("pro", offer(), listOf(owned().copy(productIds = listOf("pro", "addon"))), "A", PurchaseOptions())
        }
    }
    @Test fun automaticReplacementCannotClaimOtherAccount() {
        assertThrows(CashSDKError.PurchaseNotAttributed::class.java) {
            selectReplacement("pro", offer(), listOf(owned(user = "B")), "A", PurchaseOptions())
        }
    }
    @Test fun prepaidAndSameProductRestrictionsAreCheckedBeforeCharging() {
        val prepaid = offer().copy(phases = listOf(phase.copy(recurrenceMode = 3)))
        assertEquals(SubscriptionReplacementMode.CHARGE_FULL_PRICE, selectReplacement("pro", prepaid, listOf(owned()), "A", PurchaseOptions())!!.mode)
        for (selected in listOf(prepaid, offer())) {
            assertThrows(CashSDKError.Billing::class.java) {
                selectReplacement("pro", selected, listOf(owned()), "A", PurchaseOptions(oldPurchaseToken = "owned", replacementMode = SubscriptionReplacementMode.WITH_TIME_PRORATION))
            }
        }
    }
    @Test fun optionsDoNotLogBearerTokens() {
        val text = PurchaseOptions(offerToken = "sensitive-offer", oldPurchaseToken = "sensitive-purchase").toString()
        assertFalse(text.contains("sensitive"))
    }
}
