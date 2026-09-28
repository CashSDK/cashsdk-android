package com.cashsdk

import com.cashsdk.billing.PricingPhase
import com.cashsdk.billing.ProductPrice
import com.cashsdk.billing.StoreOffer
import com.cashsdk.billing.StoreProduct
import com.cashsdk.model.PurchaseKind
import com.cashsdk.paywall.introText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Google Play returns an offer only to users eligible for it, and a purchase without an offer
 * buys the base plan. So "may this user start a free trial" is answered by the offers in a fresh
 * `products()` result, and trial copy must follow that answer.
 */
class TrialAvailabilityTest {
    private val monthly = PricingPhase("$9.99", 9_990_000, "USD", "P1M", 0, 1)
    private val freeWeek = PricingPhase("Free", 0, "USD", "P1W", 1, 2)
    private val cheapMonth = PricingPhase("$0.99", 990_000, "USD", "P1M", 1, 2)

    private val base = StoreOffer("monthly", null, "monthly:base", listOf(monthly))
    private val trial = StoreOffer("monthly", "free-week", "monthly:trial", listOf(freeWeek, monthly))
    private val intro = StoreOffer("monthly", "intro", "monthly:intro", listOf(cheapMonth, monthly))
    private val annualTrial = StoreOffer("annual", "free-week", "annual:trial", listOf(freeWeek, monthly.copy(billingPeriod = "P1Y")))

    private fun product(vararg offers: StoreOffer) = StoreProduct("pro", PurchaseKind.SUBSCRIPTION, "Pro", "", offers.toList(), null)

    @Test fun aFreeFirstPhaseIsATrialAndAPaidIntroIsNot() {
        assertTrue(trial.isFreeTrial)
        assertEquals(freeWeek, trial.freeTrialPhase)
        assertFalse("a paid introductory price is not a free trial", intro.isFreeTrial)
        assertFalse("a base plan never starts a trial", base.isFreeTrial)
        assertNull(base.freeTrialPhase)
    }

    @Test fun trialAvailabilityIsPerBasePlan() {
        val eligible = product(base, trial, intro, annualTrial)
        assertTrue(eligible.hasFreeTrial())
        assertTrue(eligible.hasFreeTrial("monthly"))
        assertEquals(listOf(trial), eligible.freeTrialOffers("monthly"))
        assertEquals(listOf(annualTrial), eligible.freeTrialOffers("annual"))
        assertFalse(eligible.hasFreeTrial("weekly"))
    }

    @Test fun someoneWhoAlreadyHadTheTrialGetsNoTrialOffer() {
        // Play simply leaves the offer out for an ineligible user.
        val ineligible = product(base, intro)
        assertFalse(ineligible.hasFreeTrial())
        assertTrue(ineligible.freeTrialOffers().isEmpty())
    }

    /// Every trial length and billing cadence a merchant can configure in the Play Console. A
    /// free first phase is a trial whatever its length, and the wording is generated from the
    /// phase, so a 1-day trial on a two-week plan reads correctly with no change here.
    @Test fun everyTrialLengthAndCadenceReadsCorrectly() {
        val cases = listOf(
            Triple("P1D", "P1W", "Free for 1 day, then $4.99 / week"),
            Triple("P3D", "P1M", "Free for 3 days, then $4.99 / month"),
            Triple("P7D", "P2W", "Free for 7 days, then $4.99 / 2 weeks"),
            Triple("P1W", "P2W", "Free for 1 week, then $4.99 / 2 weeks"),
            Triple("P2W", "P1M", "Free for 2 weeks, then $4.99 / month"),
            Triple("P1M", "P3M", "Free for 1 month, then $4.99 / 3 months"),
            Triple("P2M", "P6M", "Free for 2 months, then $4.99 / 6 months"),
            Triple("P1M", "P1Y", "Free for 1 month, then $4.99 / year"),
        )
        for ((trialPeriod, regularPeriod, want) in cases) {
            val free = PricingPhase("Free", 0, "USD", trialPeriod, 1, 2)
            val regular = PricingPhase("$4.99", 4_990_000, "USD", regularPeriod, 0, 1)
            val offer = StoreOffer("plan", "trial", "plan:trial", listOf(free, regular))
            assertTrue("$trialPeriod on $regularPeriod is a trial", offer.isFreeTrial)
            assertEquals(free, offer.freeTrialPhase)
            val price = ProductPrice("pro", "$4.99", offer.offerToken, "Pro", PurchaseKind.SUBSCRIPTION, "plan", "trial", offer.phases)
            assertEquals(want, price.introText())
        }
    }

    /// Play states a trial as one period repeated `billingCycleCount` times, so the length the
    /// merchant configured is the product of the two.
    @Test fun billingCycleCountMultipliesTheTrialLength() {
        val free = PricingPhase("Free", 0, "USD", "P1W", 2, 2)
        val offer = StoreOffer("monthly", "trial", "monthly:trial", listOf(free, monthly))
        val price = ProductPrice("pro", "$9.99", offer.offerToken, "Pro", PurchaseKind.SUBSCRIPTION, "monthly", "trial", offer.phases)
        assertEquals("Free for 2 weeks, then $9.99 / month", price.introText())
    }

    @Test fun paywallTrialCopyFollowsTheOfferItBuys() {
        // The paywall buys the base plan by default: no trial wording for it.
        val basePrice = ProductPrice("pro", "$9.99", base.offerToken, "Pro", PurchaseKind.SUBSCRIPTION, "monthly", null, base.phases)
        assertNull(basePrice.introText())
        val trialPrice = ProductPrice("pro", "$9.99", trial.offerToken, "Pro", PurchaseKind.SUBSCRIPTION, "monthly", "free-week", trial.phases)
        assertEquals("Free for 1 week, then $9.99 / month", trialPrice.introText())
    }
}
