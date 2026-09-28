package com.cashsdk

import com.cashsdk.billing.*
import com.cashsdk.model.Entitlements
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class BillingManagerPurchaseTest {
    private val phase = PricingPhase("$9.99", 9_990_000, "USD", "P1M", 0, 1)
    private fun offer(plan: String = "monthly", id: String? = null) = StoreOffer(plan, id, "$plan:${id ?: "base"}", listOf(phase))
    private fun owned(id: String = "pro", token: String = "owned", user: String? = "A", purchased: Boolean = true) =
        ReplacementCandidate(listOf(id), token, user?.let { AppAccountToken.derive(it) }, purchased)

    /** The replacement a plan resolves to, or null for a plain purchase. */
    private fun replacement(productId: String, offer: StoreOffer, owned: List<ReplacementCandidate>, user: String, options: PurchaseOptions) =
        when (val plan = planReplacement(productId, offer, owned, user, options)) {
            ReplacementPlan.None -> null
            is ReplacementPlan.Replace -> plan.replacement
            is ReplacementPlan.ConfirmOwnership -> throw AssertionError("unexpected ownership check for ${plan.candidate.purchaseToken}")
        }

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
        assertThrows(IllegalArgumentException::class.java) { PurchaseOptions(subscriptionFamily = setOf(" ")) }
    }
    @Test fun sameProductCarriesOldTokenWithSupportedMode() {
        val result = replacement("pro", offer(), listOf(owned()), "A", PurchaseOptions())!!
        assertEquals("owned", result.purchaseToken)
        assertEquals("pro", result.productId)
        assertEquals(SubscriptionReplacementMode.WITHOUT_PRORATION, result.mode)
    }
    @Test fun unrelatedAndPendingPurchasesAreNotAutomaticallyReplaced() {
        assertNull(replacement("pro", offer(), listOf(owned("other"), owned(purchased = false)), "A", PurchaseOptions()))
    }
    @Test fun explicitCrossProductReplacementCanBeDeferred() {
        val result = replacement("star", offer(), listOf(owned("plus"), owned("other", "another")), "A",
            PurchaseOptions(oldPurchaseToken = "owned", replacementMode = SubscriptionReplacementMode.DEFERRED))!!
        assertEquals("plus", result.productId)
        assertEquals(SubscriptionReplacementMode.DEFERRED, result.mode)
    }
    @Test fun explicitTokenMustBeOwnedAndMultiItemPurchasesAreRejected() {
        assertThrows(CashSDKError.Billing::class.java) {
            planReplacement("pro", offer(), emptyList(), "A", PurchaseOptions(oldPurchaseToken = "absent"))
        }
        assertThrows(CashSDKError.Billing::class.java) {
            planReplacement("pro", offer(), listOf(owned().copy(productIds = listOf("pro", "addon"))), "A", PurchaseOptions())
        }
    }
    @Test fun explicitTokenMayMigrateALegacyToken() {
        val legacy = owned(user = null).copy(accountToken = "legacy-sdk-token")
        val result = replacement("pro", offer(), listOf(legacy), "A", PurchaseOptions(oldPurchaseToken = "owned"))!!
        assertEquals("owned", result.purchaseToken)
    }
    @Test fun sameProductHeldUnderAnotherTokenIsCheckedWithTheServerNotBlamedOnIdentify() {
        // Used to throw PurchaseNotAttributed ("call identify"), which identify can never fix.
        for (holder in listOf(owned(user = "B"), owned(user = null))) {
            val plan = planReplacement("pro", offer(), listOf(holder), "A", PurchaseOptions())
            assertEquals(ReplacementPlan.ConfirmOwnership(holder), plan)
        }
    }
    @Test fun aConfirmedOwnerGetsTheReplacement() {
        val result = planReplacement("pro", offer(), listOf(owned(user = null)), "A", PurchaseOptions(), confirmedTokens = setOf("owned"))
        assertEquals("owned", (result as ReplacementPlan.Replace).replacement.purchaseToken)
    }
    @Test fun prepaidAndSameProductRestrictionsAreCheckedBeforeCharging() {
        val prepaid = offer().copy(phases = listOf(phase.copy(recurrenceMode = 3)))
        assertEquals(SubscriptionReplacementMode.CHARGE_FULL_PRICE, replacement("pro", prepaid, listOf(owned()), "A", PurchaseOptions())!!.mode)
        for (selected in listOf(prepaid, offer())) {
            assertThrows(CashSDKError.Billing::class.java) {
                planReplacement("pro", selected, listOf(owned()), "A", PurchaseOptions(oldPurchaseToken = "owned", replacementMode = SubscriptionReplacementMode.WITH_TIME_PRORATION))
            }
        }
    }
    @Test fun optionsDoNotLogBearerTokens() {
        val text = PurchaseOptions(offerToken = "sensitive-offer", oldPurchaseToken = "sensitive-purchase").toString()
        assertFalse(text.contains("sensitive"))
    }

    // ── Subscription families (item: no second concurrent subscription) ──────────

    private val family = setOf("pro", "premium")

    @Test fun withoutAFamilyADifferentProductStillStartsAPlainPurchase() {
        // Play has no subscription groups, so the SDK cannot guess; the default is unchanged.
        assertNull(replacement("premium", offer(), listOf(owned("pro")), "A", PurchaseOptions()))
    }
    @Test fun aFamilyTurnsTheSecondSubscriptionIntoAReplacement() {
        val result = replacement("premium", offer(), listOf(owned("pro")), "A", PurchaseOptions(subscriptionFamily = family))!!
        assertEquals("pro", result.productId)
        assertEquals("owned", result.purchaseToken)
        assertEquals(SubscriptionReplacementMode.WITH_TIME_PRORATION, result.mode)
    }
    @Test fun aFamilyReplacementHonoursTheChosenMode() {
        val options = PurchaseOptions(subscriptionFamily = family, replacementMode = SubscriptionReplacementMode.CHARGE_PRORATED_PRICE)
        assertEquals(SubscriptionReplacementMode.CHARGE_PRORATED_PRICE, replacement("premium", offer(), listOf(owned("pro")), "A", options)!!.mode)
    }
    @Test fun productsOutsideTheFamilyAreLeftRunning() {
        assertNull(replacement("premium", offer(), listOf(owned("storage_addon")), "A", PurchaseOptions(subscriptionFamily = family)))
    }
    @Test fun twoOwnedFamilyMembersAreAmbiguous() {
        val owned = listOf(owned("pro", "t1"), owned("plus", "t2"))
        assertThrows(CashSDKError.Billing::class.java) {
            planReplacement("premium", offer(), owned, "A", PurchaseOptions(subscriptionFamily = setOf("pro", "plus", "premium")))
        }
    }
    @Test fun aFamilyMemberHeldUnderAnotherTokenIsConfirmedBeforeCharging() {
        val holder = owned("pro", user = null)
        val plan = planReplacement("premium", offer(), listOf(holder), "A", PurchaseOptions(subscriptionFamily = family))
        assertEquals(ReplacementPlan.ConfirmOwnership(holder), plan)
    }
    @Test fun sameProductRepurchaseIsUnchangedByAFamily() {
        val result = replacement("pro", offer(), listOf(owned("pro")), "A", PurchaseOptions(subscriptionFamily = family))!!
        assertEquals(SubscriptionReplacementMode.WITHOUT_PRORATION, result.mode)
    }
    @Test fun familyIsReadableInDebugStrings() {
        assertTrue(PurchaseOptions(subscriptionFamily = family).toString().contains("premium"))
    }

    // ── Ownership confirmed, then Play (M4), or not at all when shared ──────────

    private val annual = StoreOffer("annual", null, "annual:base", listOf(phase.copy(billingPeriod = "P1Y")))
    private val own = Entitlements(userId = "A")
    private val shared = Entitlements(userId = "A", sharedFromAnotherAccount = true)

    /** The replacement Play is opened with; fails when Play would not be opened at all. */
    private fun ReplacementResolution.opensPlayWith(): SelectedReplacement? = when (this) {
        is ReplacementResolution.Buy -> replacement
        is ReplacementResolution.Shared -> throw AssertionError("Play would not be opened")
    }

    @Test fun aConfirmedSameProductPlanChangeStillGoesThroughPlay() = runTest {
        // A subscription bought in the Play Store (no token), confirmed as this user's own, then
        // a different base plan asked for. It used to come back as "already owned" without Play
        // ever being opened, so the plan change silently never happened.
        val confirmed = mutableListOf<String>()
        val replacement = resolveReplacement("pro", annual, listOf(owned(user = null)), "A", PurchaseOptions(basePlanId = "annual")) {
            confirmed += it.purchaseToken
            own
        }.opensPlayWith()
        assertEquals(listOf("owned"), confirmed)
        assertEquals("the flow replaces the owned purchase", "owned", replacement!!.purchaseToken)
        assertEquals(SubscriptionReplacementMode.WITHOUT_PRORATION, replacement.mode)
    }

    @Test fun aSharedSubscriptionIsNeverChangedFromThisAccount() = runTest {
        // Restore policy `share`: this user rides another account's subscription. A plan change
        // here would change the owner's subscription, so Play is not opened at all.
        for ((product, options) in listOf(
            "pro" to PurchaseOptions(basePlanId = "annual"),
            "premium" to PurchaseOptions(subscriptionFamily = family),
        )) {
            val resolution = resolveReplacement(product, annual, listOf(owned("pro", user = null)), "A", options) { shared }
            assertEquals(ReplacementResolution.Shared(shared), resolution)
        }
    }

    @Test fun anotherAccountsSubscriptionStopsBeforeCharging() = runTest {
        try {
            resolveReplacement("pro", offer(), listOf(owned(user = "B")), "A", PurchaseOptions()) {
                throw CashSDKError.PurchaseNotAttributed
            }
            fail("expected the ownership refusal")
        } catch (error: CashSDKError) {
            assertEquals(CashSDKError.PurchaseNotAttributed, error)
        }
    }

    @Test fun theUsersOwnSubscriptionNeedsNoServerCheck() = runTest {
        var asked = false
        val replacement = resolveReplacement("pro", annual, listOf(owned()), "A", PurchaseOptions()) { asked = true; own }.opensPlayWith()
        assertFalse(asked)
        assertEquals("owned", replacement!!.purchaseToken)
    }

    @Test fun aConfirmedFamilyMemberIsReplaced() = runTest {
        val replacement = resolveReplacement("premium", offer(), listOf(owned("pro", user = null)), "A", PurchaseOptions(subscriptionFamily = family)) { own }
            .opensPlayWith()
        assertEquals("pro", replacement!!.productId)
        assertEquals(SubscriptionReplacementMode.WITH_TIME_PRORATION, replacement.mode)
    }

    @Test fun nothingOwnedIsAPlainPurchase() = runTest {
        assertEquals(ReplacementResolution.Buy(null), resolveReplacement("pro", offer(), emptyList(), "A", PurchaseOptions()) { own })
    }
}
