package com.cashsdk

import com.cashsdk.model.PurchaseClaim
import com.cashsdk.model.PurchaseKind
import com.cashsdk.model.VerifyRequest
import com.cashsdk.net.ApiClient
import com.cashsdk.net.HttpRequest
import com.cashsdk.net.HttpResponse
import com.cashsdk.net.HttpTransport
import com.cashsdk.net.VerifyRetryPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The verify request as it leaves the device. Runs the real [ApiClient] against a scripted
 * transport, so the headers, retries and identity rules are the shipped ones.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ApiClientVerifyTest {

    private class ScriptedTransport(vararg replies: HttpResponse) : HttpTransport {
        private val replies = ArrayDeque(replies.toList())
        val requests = mutableListOf<HttpRequest>()
        var onSend: (Int) -> Unit = {}
        override fun send(request: HttpRequest): HttpResponse {
            requests += request
            onSend(requests.size)
            return replies.removeFirstOrNull() ?: error("no scripted reply for request ${requests.size}")
        }
    }

    private val granted = HttpResponse(
        200,
        """{"entitlements":[{"identifier":"pro","name":"Pro","rank":3,"source":"subscription","expiresAt":"2999-01-01T00:00:00.000Z"}],""" +
            """"tier":3,"tierIdentifier":"pro","consumables":[],"productType":"auto_renewable","quantity":1,"acknowledged":true,""" +
            """"attributed":true,"purchaseOutcomeConfirmed":true,"belongsToAnotherAccount":false,""" +
            """"transferredFromAnotherAccount":true,"environment":"Production","userId":"A"}""",
        mapOf("ETag" to "W/\"v1\""),
    )

    private fun token(sub: String, expSeconds: Long = System.currentTimeMillis() / 1000 + 3_600) =
        "h.${Base64.getUrlEncoder().withoutPadding().encodeToString("""{"sub":"$sub","exp":$expSeconds}""".toByteArray())}.s"

    private fun client(transport: HttpTransport) =
        ApiClient(Configuration("csk_pk_test"), transport = transport, verifyRetry = VerifyRetryPolicy(random = { 0.0 }))

    private val request = VerifyRequest("pro", "purchase-token", PurchaseKind.SUBSCRIPTION)

    @After fun resetClock() = ServerClock.reset()

    // ── X-CashSDK-Claim ─────────────────────────────────────────────────────────

    @Test fun everyVerifyCarriesItsClaim() = runTest {
        val wire = mapOf(PurchaseClaim.PURCHASE to "purchase", PurchaseClaim.RESTORE to "restore", PurchaseClaim.SYNC to "sync")
        for ((claim, value) in wire) {
            val transport = ScriptedTransport(granted)
            val api = client(transport)
            api.setIdentity("A", token("A"))
            api.verifyPurchase(request, claim)
            val sent = transport.requests.single()
            assertEquals(value, sent.headers["X-CashSDK-Claim"])
            assertEquals("https://api.cashsdk.com/v1/purchases:verify", sent.url)
            assertEquals("A", sent.headers["X-CashSDK-User-Id"])
        }
    }

    @Test fun onlyVerifyCarriesAClaim() = runTest {
        val transport = ScriptedTransport(HttpResponse(200, """{"entitlements":[],"tier":0}"""))
        val api = client(transport)
        api.setIdentity("A", token("A"))
        api.getEntitlements()
        assertNull(transport.requests.single().headers["X-CashSDK-Claim"])
    }

    @Test fun theVerifyResponseKeepsTheNewFields() = runTest {
        val api = client(ScriptedTransport(granted))
        api.setIdentity("A", token("A"))
        val outcome = api.verifyPurchase(request, PurchaseClaim.RESTORE)
        assertTrue(outcome.entitlements.transferredFromAnotherAccount)
        assertEquals(true, outcome.entitlements.purchaseOutcomeConfirmed)
        assertEquals("2999-01-01T00:00:00.000Z", outcome.entitlements.active.single().expiresAt)
        assertEquals("W/\"v1\"", outcome.etag)
    }

    // ── Retries ─────────────────────────────────────────────────────────────────

    @Test fun googleOutageIsRetriedAfterRetryAfter() = runTest {
        val transport = ScriptedTransport(
            HttpResponse(503, """{"error":"store_unavailable"}""", mapOf("Retry-After" to "30")),
            granted,
        )
        val api = client(transport)
        api.setIdentity("A", token("A"))
        val outcome = api.verifyPurchase(request, PurchaseClaim.PURCHASE)
        assertTrue(outcome.attributed)
        assertEquals(2, transport.requests.size)
        assertTrue("waited out Retry-After", currentTime >= 30_000)
        assertEquals("the retry is the same claim", "purchase", transport.requests[1].headers["X-CashSDK-Claim"])
    }

    @Test fun aRateLimitThatPersistsSurfacesWithItsCode() = runTest {
        val limited = HttpResponse(429, """{"error":{"code":"rate_limited","message":"too many requests"}}""", mapOf("Retry-After" to "2"))
        val transport = ScriptedTransport(limited, limited, limited)
        val api = client(transport)
        api.setIdentity("A", token("A"))
        try {
            api.verifyPurchase(request, PurchaseClaim.SYNC)
            fail("expected the rate limit")
        } catch (error: CashSDKError.Server) {
            assertEquals(429, error.status)
            assertEquals("rate_limited", error.code)
        }
        assertEquals("bounded: three requests, then the next sync", 3, transport.requests.size)
    }

    @Test fun aVerdictIsNotRetried() = runTest {
        val transport = ScriptedTransport(HttpResponse(401, """{"error":"invalid_purchase"}"""))
        val api = client(transport)
        api.setIdentity("A", token("A"))
        try {
            api.verifyPurchase(request, PurchaseClaim.PURCHASE)
            fail("expected invalid_purchase")
        } catch (error: CashSDKError.Server) {
            assertEquals("invalid_purchase", error.code)
        }
        assertEquals(1, transport.requests.size)
    }

    @Test fun aRetryAfterLongerThanWeHoldAPurchaseOpenIsLeftForTheNextSync() = runTest {
        val transport = ScriptedTransport(HttpResponse(429, """{"error":{"code":"rate_limited"}}""", mapOf("Retry-After" to "55")))
        val api = client(transport)
        api.setIdentity("A", token("A"))
        try {
            api.verifyPurchase(request, PurchaseClaim.PURCHASE)
            fail("expected the rate limit")
        } catch (error: CashSDKError.Server) {
            assertEquals(429, error.status)
        }
        assertEquals(1, transport.requests.size)
    }

    // ── Identity during a verify ───────────────────────────────────────────────

    @Test fun aTokenRefreshedDuringTheBackoffIsUsedByTheRetry() = runTest {
        val transport = ScriptedTransport(HttpResponse(503, """{"error":"store_unavailable"}"""), granted)
        val api = client(transport)
        val first = token("A")
        val refreshed = token("A", System.currentTimeMillis() / 1000 + 7_200)
        api.setIdentity("A", first)
        transport.onSend = { n -> if (n == 1) api.setIdentity("A", refreshed) }
        val outcome = api.verifyPurchase(request, PurchaseClaim.PURCHASE)
        assertTrue("same user: the answer is still theirs", outcome.attributed)
        assertEquals(first, transport.requests[0].headers["X-CashSDK-User-Token"])
        assertEquals(refreshed, transport.requests[1].headers["X-CashSDK-User-Token"])
    }

    @Test fun anotherUserSigningInStopsTheVerifyBeforeARetryCouldClaimForThem() = runTest {
        val transport = ScriptedTransport(HttpResponse(503, """{"error":"store_unavailable"}"""), granted)
        val api = client(transport)
        api.setIdentity("A", token("A"))
        transport.onSend = { n -> if (n == 1) api.setIdentity("B", token("B")) }
        try {
            api.verifyPurchase(request, PurchaseClaim.PURCHASE)
            fail("expected NotIdentified")
        } catch (error: CashSDKError) {
            assertEquals(CashSDKError.NotIdentified, error)
        }
        assertEquals(1, transport.requests.size)
    }

    @Test fun entitlementReadsStillDropAnswersFromOlderCredentials() = runTest {
        // Unchanged on purpose: a snapshot read under an old token must not overwrite a newer one.
        val transport = ScriptedTransport(HttpResponse(200, """{"entitlements":[],"tier":0}"""))
        val api = client(transport)
        api.setIdentity("A", token("A"))
        transport.onSend = { api.setIdentity("A", token("A", System.currentTimeMillis() / 1000 + 9_000)) }
        try {
            api.getEntitlements()
            fail("expected NotIdentified")
        } catch (error: CashSDKError) {
            assertEquals(CashSDKError.NotIdentified, error)
        }
    }

    @Test fun purchaseChecksCompareUsersNotTokens() {
        val api = client(ScriptedTransport())
        val expiring = token("A", System.currentTimeMillis() / 1000 + 10)
        api.setIdentity("A", expiring)
        val atStart = api.identitySnapshot()
        // The host refreshes the token while the Play sheet is open.
        api.setIdentity("A", token("A"))
        api.requireSameUser(atStart.userId)
        api.requireValidUserTokenFor("A") // judged on the new token, not the expiring one
        try {
            api.requireIdentity(atStart)
            fail("the exact-snapshot check is what used to fail a settled purchase")
        } catch (_: CashSDKError) {
        }
        api.setIdentity("B", token("B"))
        for (check in listOf({ api.requireSameUser("A") }, { api.requireValidUserTokenFor("A") })) {
            try {
                check()
                fail("a different user must not pass")
            } catch (error: CashSDKError) {
                assertEquals(CashSDKError.NotIdentified, error)
            }
        }
    }

    // ── Server clock ────────────────────────────────────────────────────────────

    private fun httpDate(millis: Long): String = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", java.util.Locale.US)
        .apply { timeZone = java.util.TimeZone.getTimeZone("GMT") }
        .format(java.util.Date(millis))

    @Test fun twoResponsesDatedAlikeCorrectAWrongDeviceClock() = runTest {
        val empty = """{"entitlements":[],"tier":0}"""
        val serverNow = System.currentTimeMillis() + 3 * 3_600_000 // device three hours behind
        val api = client(
            ScriptedTransport(
                HttpResponse(200, empty, mapOf("Date" to httpDate(serverNow))),
                HttpResponse(200, empty, mapOf("Date" to httpDate(serverNow + 2_000))),
            ),
        )
        api.setIdentity("A", token("A"))
        api.getEntitlements()
        assertEquals("one Date alone is not trusted", 0L, ServerClock.offsetMs)
        api.getEntitlements()
        val offset = ServerClock.offsetMs
        assertTrue("offset $offset", offset in (3 * 3_600_000L - 5_000)..(3 * 3_600_000L + 3_000))
    }

    @Test fun anHttpDateRetryAfterIsMeasuredFromTheServersDate() = runTest {
        // The server's clock says 10:15:00 and asks for 10:15:20. The device clock is whatever it
        // is; the wait is the 20 s between the server's two times.
        val transport = ScriptedTransport(
            HttpResponse(
                503,
                """{"error":"store_unavailable"}""",
                mapOf("Date" to "Wed, 23 Sep 2026 10:15:00 GMT", "Retry-After" to "Wed, 23 Sep 2026 10:15:20 GMT"),
            ),
            granted,
        )
        val api = client(transport)
        api.setIdentity("A", token("A"))
        api.verifyPurchase(request, PurchaseClaim.PURCHASE)
        assertEquals(2, transport.requests.size)
        assertEquals(20_000L, currentTime)
    }

    @Test fun atMostThreeVerifiesAreInFlightAtOnce() {
        val inFlight = AtomicInteger()
        val peak = AtomicInteger()
        val release = CountDownLatch(1)
        val transport = HttpTransport {
            val now = inFlight.incrementAndGet()
            peak.accumulateAndGet(now) { a, b -> maxOf(a, b) }
            try {
                release.await(10, TimeUnit.SECONDS)
            } finally {
                inFlight.decrementAndGet()
            }
            granted
        }
        val api = ApiClient(Configuration("csk_pk_test"), transport = transport)
        api.setIdentity("A", token("A"))
        runBlocking {
            val calls = (1..6).map { i ->
                async(Dispatchers.Default) { api.verifyPurchase(VerifyRequest("pro", "t$i", PurchaseKind.SUBSCRIPTION), PurchaseClaim.SYNC) }
            }
            val giveUpAt = System.currentTimeMillis() + 10_000
            while (inFlight.get() < 3 && System.currentTimeMillis() < giveUpAt) Thread.sleep(5)
            Thread.sleep(300) // room for a fourth to slip through, if the cap were broken
            assertEquals(3, inFlight.get())
            release.countDown()
            assertTrue(calls.awaitAll().all { it.attributed })
        }
        assertEquals(3, peak.get())
    }

    @Test fun aReadWithoutRevalidationSendsNoETag() = runTest {
        val transport = ScriptedTransport(
            HttpResponse(200, """{"entitlements":[],"tier":0}""", mapOf("ETag" to "W/\"v1\"")),
            HttpResponse(200, """{"entitlements":[],"tier":0}"""),
            HttpResponse(200, """{"entitlements":[],"tier":0}"""),
        )
        val api = ApiClient(Configuration("csk_pk_test"), entitlementEtag = { "W/\"v1\"" }, transport = transport)
        api.setIdentity("A", token("A"))
        api.getEntitlements()
        api.getEntitlements(revalidate = false)
        assertEquals("W/\"v1\"", transport.requests[0].headers["If-None-Match"])
        assertNull(transport.requests[1].headers["If-None-Match"])
    }
}
