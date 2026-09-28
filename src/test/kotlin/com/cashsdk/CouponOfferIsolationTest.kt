package com.cashsdk

import com.cashsdk.billing.PricingPhase
import com.cashsdk.billing.StoreOffer
import com.cashsdk.billing.StoreProduct
import com.cashsdk.billing.selectOffer
import com.cashsdk.billing.withoutCouponOffers
import com.cashsdk.model.PurchaseKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A coupon's Play offer (tag `cashsdk-coupon`) has no targeting, so Play lists it to every user.
 * A free coupon offer looks exactly like a free trial. Outside `redeemCoupon` it must never be
 * shown, counted as a trial or bought.
 */
class CouponOfferIsolationTest {
    private val monthly = PricingPhase("$9.99", 9_990_000, "USD", "P1M", 0, 1)
    private val freeMonth = PricingPhase("Free", 0, "USD", "P1M", 1, 2)

    private val base = StoreOffer("monthly", null, "monthly:base", listOf(monthly))
    private val couponFree = StoreOffer("monthly", "cpn-abc", "monthly:cpn", listOf(freeMonth, monthly), listOf(StoreOffer.COUPON_OFFER_TAG))
    private val trial = StoreOffer("monthly", "free-week", "monthly:trial", listOf(freeMonth.copy(billingPeriod = "P1W"), monthly))

    @Test fun aCouponOfferIsNeverATrial() {
        assertTrue(couponFree.isCouponOffer)
        assertFalse(couponFree.isFreeTrial)
        assertNull(couponFree.freeTrialPhase)
        assertFalse(trial.isCouponOffer)
        assertTrue(trial.isFreeTrial)
    }

    @Test fun trialChecksIgnoreCouponOffers() {
        val onlyCoupon = StoreProduct("pro", PurchaseKind.SUBSCRIPTION, "Pro", "", listOf(base, couponFree), null)
        assertFalse(onlyCoupon.hasFreeTrial())
        assertTrue(onlyCoupon.freeTrialOffers("monthly").isEmpty())
        val both = StoreProduct("pro", PurchaseKind.SUBSCRIPTION, "Pro", "", listOf(base, couponFree, trial), null)
        assertEquals(listOf(trial), both.freeTrialOffers())
    }

    @Test fun catalogueSurfacesDropCouponOffers() {
        assertEquals(listOf(base, trial), listOf(base, couponFree, trial).withoutCouponOffers())
    }

    @Test fun purchaseCannotSelectACouponOfferByAnySelector() {
        val offers = listOf(base, couponFree)
        assertEquals(base, selectOffer(offers, PurchaseOptions()))
        assertThrows(CashSDKError.Billing::class.java) { selectOffer(offers, PurchaseOptions(offerId = "cpn-abc")) }
        assertThrows(CashSDKError.Billing::class.java) { selectOffer(offers, PurchaseOptions(offerToken = "monthly:cpn")) }
        assertThrows(CashSDKError.Billing::class.java) {
            selectOffer(offers, PurchaseOptions(basePlanId = "monthly", offerId = "cpn-abc", offerToken = "monthly:cpn"))
        }
    }

    @Test fun onlyCouponRedemptionMaySelectIt() {
        val chosen = selectOffer(listOf(base, couponFree), PurchaseOptions(basePlanId = "monthly", offerId = "cpn-abc", offerToken = "monthly:cpn"), allowCouponOffers = true)
        assertEquals(couponFree, chosen)
        // Allowed, the default choice is still the base plan.
        assertEquals(base, selectOffer(listOf(base, couponFree), PurchaseOptions(), allowCouponOffers = true))
    }
}
