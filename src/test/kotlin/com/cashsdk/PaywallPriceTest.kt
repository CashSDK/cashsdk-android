package com.cashsdk

import com.cashsdk.billing.*
import com.cashsdk.model.PurchaseKind
import com.cashsdk.paywall.introText
import com.cashsdk.paywall.priceText
import org.junit.Assert.*
import org.junit.Test

class PaywallPriceTest {
    private val recurring = PricingPhase("€9,99", 9_990_000, "EUR", "P1M", 0, 1)
    private fun price(vararg phases: PricingPhase) = ProductPrice("pro", "€9,99", "offer", "Pro", PurchaseKind.SUBSCRIPTION, phases = phases.toList())
    @Test fun recurringPriceIncludesPeriodAndKeepsStoreFormatting() {
        assertEquals("€9,99 / month", price(recurring).priceText())
        assertNull(price(recurring).introText())
    }
    @Test fun trialIsSecondaryNotTheHeadlinePrice() {
        val price = price(recurring.copy(formattedPrice = "Free", priceAmountMicros = 0, billingPeriod = "P1W", billingCycleCount = 1, recurrenceMode = 2), recurring)
        assertEquals("€9,99 / month", price.priceText())
        assertEquals("Free for 1 week, then €9,99 / month", price.introText())
    }
    @Test fun introductoryCyclesAndPrepaidAreDisclosed() {
        val intro = recurring.copy(formattedPrice = "€1,99", billingCycleCount = 3, recurrenceMode = 2)
        assertEquals("€1,99 / month for 3 billing cycle(s), then €9,99 / month", price(intro, recurring).introText())
        assertEquals("€9,99 for 1 month", price(recurring.copy(recurrenceMode = 3)).priceText())
    }
    @Test fun oneTimePriceDoesNotImplyRecurringBilling() {
        assertEquals("€9,99", price().priceText())
        assertNull(price().introText())
    }
}
