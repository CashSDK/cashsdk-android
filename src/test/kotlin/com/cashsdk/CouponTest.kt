package com.cashsdk

import com.cashsdk.billing.PricingPhase
import com.cashsdk.billing.StoreOffer
import com.cashsdk.model.Entitlements
import com.cashsdk.net.ApiClient
import com.cashsdk.net.HttpRequest
import com.cashsdk.net.HttpResponse
import com.cashsdk.net.HttpTransport
import com.cashsdk.net.VerifyRetryPolicy
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.math.BigDecimal
import java.util.Base64
import java.util.Locale

/**
 * Coupon validation and redemption against a scripted API, in the shapes of
 * `AGENTS/COUPONS.md`. The real [ApiClient] and [CouponRedeemer] run; Play is stood in for by
 * the offer list and the purchase lambda.
 */
class CouponTest {

    private class Transport(private val reply: (HttpRequest) -> HttpResponse) : HttpTransport {
        val requests = mutableListOf<HttpRequest>()
        override fun send(request: HttpRequest): HttpResponse {
            requests += request
            return reply(request)
        }
    }

    private fun token(sub: String, expSeconds: Long = System.currentTimeMillis() / 1000 + 3_600) =
        "h.${Base64.getUrlEncoder().withoutPadding().encodeToString("""{"sub":"$sub","exp":$expSeconds}""".toByteArray())}.s"

    private fun signedIn(transport: HttpTransport, user: String? = "A"): ApiClient =
        ApiClient(Configuration("csk_pk_test"), transport = transport, verifyRetry = VerifyRetryPolicy(random = { 0.0 }))
            .also { api -> user?.let { api.setIdentity(it, token(it)) } }

    private fun body(request: HttpRequest): JsonObject = Json.parseToJsonElement(request.body!!).jsonObject

    private val validBody = """
        {"valid":true,
         "coupon":{"code":"SPRING","name":"Spring sale","kind":"percent_off","percentOff":20,"amountOffMinor":null,
                   "currency":null,"duration":"P1M","periodCount":3},
         "products":[
           {"productIdentifier":"pro_monthly","android":{"basePlanId":"monthly","offerId":"cpn-abc"}},
           {"productIdentifier":"pro_ios_only","ios":{"appleCode":"SPRING","redeemUrl":"https://apps.apple.com/redeem?ctx=offercodes&id=1&code=SPRING"}}
         ]}
    """.trimIndent()

    private val reservation = HttpResponse(200, """{"redemptionId":"red_1","android":{"basePlanId":"monthly","offerId":"cpn-abc"}}""")

    private val phase = PricingPhase("$9.99", 9_990_000, "USD", "P1M", 0, 1)
    private val offers = listOf(
        StoreOffer("monthly", null, "monthly:base", listOf(phase)),
        StoreOffer("monthly", "trial", "monthly:trial", listOf(phase)),
        StoreOffer("monthly", "cpn-abc", "monthly:cpn-abc", listOf(phase), listOf("cashsdk-coupon")),
        StoreOffer("annual", "cpn-abc", "annual:cpn-abc", listOf(phase), listOf("cashsdk-coupon")),
    )

    @After fun resetClock() = ServerClock.reset()

    // ── Validate ────────────────────────────────────────────────────────────────

    @Test fun validateSendsTheContractBodyAndDecodesTheCoupon() = runTest {
        val transport = Transport { HttpResponse(200, validBody) }
        val validation = CouponRedeemer(signedIn(transport)) { offers }.validate("  spring ")

        val sent = transport.requests.single()
        assertEquals("POST", sent.method)
        assertEquals("https://api.cashsdk.com/v1/coupons:validate", sent.url)
        assertEquals("SPRING", body(sent)["code"]!!.jsonPrimitive.content)
        assertEquals("A", body(sent)["appUserId"]!!.jsonPrimitive.content)
        assertEquals("android", body(sent)["platform"]!!.jsonPrimitive.content)
        assertEquals("Bearer csk_pk_test", sent.headers["Authorization"])

        assertTrue(validation.valid)
        assertNull(validation.reason)
        val coupon = validation.coupon!!
        assertEquals(CouponKind.PERCENT_OFF, coupon.kind)
        assertEquals(20, coupon.percentOff)
        assertNull(coupon.amountOff)
        assertEquals("P1M", coupon.duration)
        assertEquals(3, coupon.periodCount)
        // Only products with a Play offer can be redeemed here.
        assertEquals(listOf("pro_monthly"), validation.eligibleProductIds)
        assertEquals(CouponProduct("pro_monthly", "monthly", "cpn-abc"), validation.products.single())
    }

    @Test fun everyReasonCodeDecodes() = runTest {
        val known = CouponInvalidReason.entries.filter { it != CouponInvalidReason.UNKNOWN }
        assertEquals(10, known.size)
        assertEquals(CouponInvalidReason.BASE_PLAN_REQUIRED, CouponInvalidReason.fromWire("base_plan_required"))
        for (reason in known + CouponInvalidReason.UNKNOWN) {
            val wire = if (reason == CouponInvalidReason.UNKNOWN) "brand_new_reason" else reason.wireValue
            val transport = Transport { HttpResponse(200, """{"valid":false,"reason":"$wire"}""") }
            val validation = CouponRedeemer(signedIn(transport)) { offers }.validate("SPRING")
            assertFalse(wire, validation.valid)
            assertEquals(wire, reason, validation.reason)
            assertEquals(wire, validation.rawReason)
            assertNull(validation.coupon)
            assertTrue(validation.products.isEmpty())
            assertTrue(reason.message.isNotBlank())
        }
    }

    @Test fun aRefusalSentAsAnErrorStatusIsStillARefusal() = runTest {
        val transport = Transport { HttpResponse(404, """{"error":{"code":"coupon_not_found","message":"no such code"}}""") }
        assertEquals(CouponInvalidReason.NOT_FOUND, CouponRedeemer(signedIn(transport)) { offers }.validate("NOPE").reason)

        val other = Transport { HttpResponse(400, """{"error":"invalid_body"}""") }
        try {
            CouponRedeemer(signedIn(other)) { offers }.validate("NOPE")
            fail("a 400 without a coupon reason is an error")
        } catch (e: CashSDKError.Server) {
            assertEquals(400, e.status)
            assertEquals("invalid_body", e.code)
        }
    }

    @Test fun throttledValidateHonoursRetryAfterThenGivesUp() = runTest {
        val transport = Transport { HttpResponse(429, """{"error":{"code":"rate_limited"}}""", mapOf("Retry-After" to "2")) }
        try {
            CouponRedeemer(signedIn(transport)) { offers }.validate("SPRING")
            fail("throttled every time")
        } catch (e: CashSDKError.Server) {
            assertEquals(429, e.status)
            assertEquals("rate_limited", e.code)
        }
        assertEquals(3, transport.requests.size)
    }

    // ── Guests ──────────────────────────────────────────────────────────────────

    @Test fun guestsAreRefusedBeforeAnythingIsSent() = runTest {
        val transport = Transport { HttpResponse(200, validBody) }
        val coupons = CouponRedeemer(signedIn(transport, user = null)) { offers }
        try {
            coupons.validate("SPRING"); fail("a guest cannot validate")
        } catch (_: CashSDKError.NotIdentified) {}
        try {
            coupons.redeem("SPRING", "pro_monthly") { fail("no purchase for a guest"); Entitlements() }
            fail("a guest cannot redeem")
        } catch (_: CashSDKError.NotIdentified) {}
        assertTrue(transport.requests.isEmpty())
    }

    // ── Redeem ──────────────────────────────────────────────────────────────────

    @Test fun redeemReservesThenBuysTheCouponOfferThroughThePurchasePath() = runTest {
        val transport = Transport { reservation }
        val bought = mutableListOf<PurchaseOptions>()
        val granted = Entitlements(tier = 2, tierIdentifier = "pro")
        val result = CouponRedeemer(signedIn(transport)) { id -> offers.takeIf { id == "pro_monthly" } }
            .redeem(" spring", "pro_monthly") { options -> bought += options; granted }

        val sent = transport.requests.single()
        assertEquals("https://api.cashsdk.com/v1/coupons:redeem", sent.url)
        assertEquals("SPRING", body(sent)["code"]!!.jsonPrimitive.content)
        assertEquals("A", body(sent)["appUserId"]!!.jsonPrimitive.content)
        assertEquals("android", body(sent)["platform"]!!.jsonPrimitive.content)
        assertEquals("pro_monthly", body(sent)["productIdentifier"]!!.jsonPrimitive.content)

        assertEquals(granted, result)
        assertEquals(listOf(PurchaseOptions(basePlanId = "monthly", offerId = "cpn-abc", offerToken = "monthly:cpn-abc")), bought)
    }

    @Test fun redeemIsIdempotentAndSafeToRetry() = runTest {
        var calls = 0
        val transport = Transport {
            calls++
            // A deploy blip first; the retry and a second tap get the same reservation.
            if (calls == 1) HttpResponse(503, "", mapOf("Retry-After" to "1")) else reservation
        }
        val coupons = CouponRedeemer(signedIn(transport)) { offers }
        val bought = mutableListOf<String?>()
        repeat(2) { coupons.redeem("SPRING", "pro_monthly") { bought += it.offerToken; Entitlements() } }
        assertEquals(3, transport.requests.size)
        assertEquals(1, transport.requests.map { it.body }.toSet().size)
        assertEquals(listOf("monthly:cpn-abc", "monthly:cpn-abc"), bought)
    }

    @Test fun redeemRefusalsAreTypedForEveryReason() = runTest {
        for (reason in CouponInvalidReason.entries.filter { it != CouponInvalidReason.UNKNOWN }) {
            val replies = listOf(
                HttpResponse(200, """{"valid":false,"reason":"${reason.wireValue}"}"""),
                HttpResponse(409, """{"error":{"code":"coupon_${reason.wireValue}"}}"""),
                HttpResponse(422, """{"error":"${reason.wireValue}"}"""),
            )
            for (reply in replies) {
                val coupons = CouponRedeemer(signedIn(Transport { reply })) { offers }
                try {
                    coupons.redeem("SPRING", "pro_monthly") { fail("a refused code never reaches Play"); Entitlements() }
                    fail("$reason refused")
                } catch (e: CouponException.Rejected) {
                    assertEquals(reason, e.reason)
                    assertEquals(reason.wireValue, e.rawReason)
                }
            }
        }
    }

    @Test fun anOfferPlayDoesNotListYetIsTyped() = runTest {
        val coupons = CouponRedeemer(signedIn(Transport { reservation })) { offers.filter { it.offerId != "cpn-abc" } }
        try {
            coupons.redeem("SPRING", "pro_monthly") { fail("nothing to buy yet"); Entitlements() }
            fail("the offer has not reached Play on this device")
        } catch (e: CouponException.OfferNotAvailableYet) {
            assertEquals("pro_monthly", e.productId)
            assertEquals("monthly", e.basePlanId)
            assertEquals("cpn-abc", e.offerId)
        }
    }

    @Test fun anUnknownProductIsProductNotFound() = runTest {
        val coupons = CouponRedeemer(signedIn(Transport { reservation })) { null }
        try {
            coupons.redeem("SPRING", "pro_monthly") { fail("nothing to buy"); Entitlements() }
            fail("Play does not know the product")
        } catch (e: CashSDKError.ProductNotFound) {
            assertEquals(listOf("pro_monthly"), e.productIds)
        }
    }

    @Test fun aReservationWithoutAPlayOfferIsADecodingError() = runTest {
        val coupons = CouponRedeemer(signedIn(Transport { HttpResponse(200, """{"redemptionId":"red_1"}""") })) { offers }
        try {
            coupons.redeem("SPRING", "pro_monthly") { fail("nothing to buy"); Entitlements() }
            fail("no offer to buy")
        } catch (_: CashSDKError.Decoding) {}
    }

    // ── Offer selection ─────────────────────────────────────────────────────────

    @Test fun offerTokenSelection() {
        assertEquals("monthly:cpn-abc", selectCouponOffer(offers, "monthly", "cpn-abc")?.offerToken)
        assertEquals("annual:cpn-abc", selectCouponOffer(offers, "annual", "cpn-abc")?.offerToken)
        // Never the base plan or another offer in its place.
        assertNull(selectCouponOffer(offers, "monthly", "cpn-missing"))
        assertNull(selectCouponOffer(offers, "weekly", "cpn-abc"))
        // Without a base plan, only an unambiguous match.
        assertNull(selectCouponOffer(offers, null, "cpn-abc"))
        // Only offers tagged as coupon offers: never the merchant's own offer of that id.
        assertNull(selectCouponOffer(offers, null, "trial"))
        assertNull(selectCouponOffer(listOf(StoreOffer("monthly", "cpn-abc", "untagged", listOf(phase))), "monthly", "cpn-abc"))
    }

    // ── Several base plans ──────────────────────────────────────────────────────

    private val twoPlansBody = """
        {"valid":true,
         "coupon":{"code":"SPRING","name":"Spring sale","kind":"free_trial","duration":"P1M","periodCount":1},
         "products":[
           {"productIdentifier":"pro","android":{"basePlanId":"monthly","offerId":"cpn-abc"}},
           {"productIdentifier":"pro","android":{"basePlanId":"annual","offerId":"cpn-abc"}},
           {"productIdentifier":"plus","android":{"basePlanId":"monthly","offerId":"cpn-abc"}}
         ]}
    """.trimIndent()

    @Test fun eligibleProductsAreDistinctAndExposeTheirBasePlans() = runTest {
        val validation = CouponRedeemer(signedIn(Transport { HttpResponse(200, twoPlansBody) })) { offers }.validate("SPRING")
        assertEquals(listOf("pro", "plus"), validation.eligibleProductIds)
        assertEquals(listOf("monthly", "annual"), validation.basePlanIds("pro"))
        assertEquals(listOf("monthly"), validation.basePlanIds("plus"))
        assertTrue(validation.basePlanIds("other").isEmpty())
        assertEquals(3, validation.products.size)
    }

    @Test fun redeemSendsTheChosenBasePlanAndBuysThatPlansOffer() = runTest {
        val transport = Transport { HttpResponse(200, """{"valid":true,"redemptionId":"red_1","android":{"basePlanId":"annual","offerId":"cpn-abc"}}""") }
        val bought = mutableListOf<PurchaseOptions>()
        CouponRedeemer(signedIn(transport)) { offers }.redeem("SPRING", "pro_monthly", basePlanId = "annual") { bought += it; Entitlements() }
        assertEquals("annual", body(transport.requests.single())["basePlanId"]!!.jsonPrimitive.content)
        assertEquals("annual:cpn-abc", bought.single().offerToken)
    }

    @Test fun redeemWithoutABasePlanSendsNone() = runTest {
        val transport = Transport { reservation }
        CouponRedeemer(signedIn(transport)) { offers }.redeem("SPRING", "pro_monthly") { Entitlements() }
        assertFalse(body(transport.requests.single()).containsKey("basePlanId"))
    }

    @Test fun basePlanRequiredIsATypedRefusal() = runTest {
        val transport = Transport { HttpResponse(200, """{"valid":false,"reason":"base_plan_required"}""") }
        try {
            CouponRedeemer(signedIn(transport)) { offers }.redeem("SPRING", "pro_monthly") { fail("nothing to buy"); Entitlements() }
            fail("the coupon covers two base plans")
        } catch (e: CouponException.Rejected) {
            assertEquals(CouponInvalidReason.BASE_PLAN_REQUIRED, e.reason)
            assertTrue(e.message!!.isNotBlank())
        }
    }

    @Test fun aReservationForAnotherBasePlanIsNeverBought() = runTest {
        // The server reserved the monthly plan while the user chose annual: do not charge them.
        val coupons = CouponRedeemer(signedIn(Transport { reservation })) { offers }
        try {
            coupons.redeem("SPRING", "pro_monthly", basePlanId = "annual") { fail("wrong plan"); Entitlements() }
            fail("mismatched base plan")
        } catch (_: CashSDKError.Decoding) {}
    }

    // ── Analytics ───────────────────────────────────────────────────────────────

    @Test fun anAlreadyOwnedResultIsNotARedemption() {
        assertEquals("coupon_redeem_success", couponOutcomeEvent(Entitlements()))
        assertEquals("coupon_redeem_already_owned", couponOutcomeEvent(Entitlements().copy(alreadyOwned = true)))
    }

    // ── Money ───────────────────────────────────────────────────────────────────

    @Test fun amountOffUsesTheServersMinorUnits() {
        fun coupon(minor: Long, currency: String) = Coupon("C", "C", CouponKind.AMOUNT_OFF, null, minor, currency, "P1M", 1)
        assertEquals(BigDecimal("4.99"), coupon(499, "USD").amountOff)
        assertEquals(BigDecimal("500"), coupon(500, "JPY").amountOff)
        assertEquals(BigDecimal("1.500"), coupon(1500, "KWD").amountOff)
        assertEquals("$4.99", coupon(499, "USD").formattedAmountOff(Locale.US))
        assertEquals("¥500", coupon(500, "JPY").formattedAmountOff(Locale.JAPAN)?.replace('￥', '¥'))
        // A code the JDK does not know still shows the amount.
        assertEquals("15.00 QQQ", coupon(1500, "qqq").formattedAmountOff(Locale.US))
        assertNull(Coupon("C", "C", CouponKind.PERCENT_OFF, 20, null, null, "P1M", 1).formattedAmountOff(Locale.US))
        assertEquals(CouponKind.FREE_TRIAL, CouponKind.fromWire("free_trial"))
        assertEquals(CouponKind.UNKNOWN, CouponKind.fromWire("bogo"))
    }
}
