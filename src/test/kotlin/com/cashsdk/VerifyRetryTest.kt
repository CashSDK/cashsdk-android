package com.cashsdk

import com.cashsdk.net.VerifyRetryPolicy
import com.cashsdk.net.parseHttpDateMillis
import com.cashsdk.net.parseRetryAfterMillis
import com.cashsdk.net.serverErrorCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class VerifyRetryTest {

    // ── Error bodies ────────────────────────────────────────────────────────────

    @Test fun bothErrorShapesTheApiSendsAreRead() {
        // Handlers: a string. Rate limiters: an object. Only the first used to be read.
        assertEquals("invalid_purchase", serverErrorCode("""{"error":"invalid_purchase"}"""))
        assertEquals("store_unavailable", serverErrorCode("""{"error":"store_unavailable"}"""))
        assertEquals("rate_limited", serverErrorCode("""{"error":{"code":"rate_limited","message":"too many requests"}}"""))
    }

    @Test fun anythingElseHasNoCode() {
        for (body in listOf("", "<html>502</html>", "[]", """{"error":null}""", """{"error":{"message":"x"}}""", """{"error":42}""", """{"statusCode":500}""")) {
            assertNull(body, serverErrorCode(body))
        }
    }

    // ── Headers ────────────────────────────────────────────────────────────────

    @Test fun retryAfterIsSecondsOrAnHttpDate() {
        val now = Instant.parse("2026-09-23T10:15:00Z").toEpochMilli()
        assertEquals(30_000L, parseRetryAfterMillis("30", now))
        assertEquals(0L, parseRetryAfterMillis(" 0 ", now))
        assertEquals(45_000L, parseRetryAfterMillis("Wed, 23 Sep 2026 10:15:45 GMT", now))
        assertEquals("a date in the past means now", 0L, parseRetryAfterMillis("Wed, 23 Sep 2026 10:00:00 GMT", now))
        for (bad in listOf(null, "", "-5", "soon", "1.5")) assertNull(bad, parseRetryAfterMillis(bad, now))
    }

    @Test fun theDateHeaderIsParsed() {
        assertEquals(Instant.parse("2026-09-23T10:15:30Z").toEpochMilli(), parseHttpDateMillis("Wed, 23 Sep 2026 10:15:30 GMT"))
        assertNull(parseHttpDateMillis("yesterday"))
    }

    // ── Policy ───────────────────────────────────────────────────────────────────

    private val policy = VerifyRetryPolicy(random = { 0.0 })
    private val jittery = VerifyRetryPolicy(random = { 0.999 })

    @Test fun onlyTimeoutsRateLimitsAndServerErrorsAreRetried() {
        for (status in listOf(408, 429, 500, 502, 503, 504)) assertTrue("$status", policy.isRetryable(status))
        for (status in listOf(400, 401, 403, 404, 409, 422)) {
            assertNull("$status is the server's answer", policy.delayBeforeNextAttempt(1, status, null, 0))
        }
    }

    @Test fun backoffDoublesWithJitterAndStopsAfterThreeAttempts() {
        assertEquals(1_000L, policy.delayBeforeNextAttempt(1, 500, null, 0))
        assertEquals(2_000L, policy.delayBeforeNextAttempt(2, 500, null, 1_000))
        val high = jittery.delayBeforeNextAttempt(1, 500, null, 0)!!
        assertTrue("at least a second of spread: $high", high in 1_900L until 2_000L)
        assertNull("three requests in total", policy.delayBeforeNextAttempt(3, 500, null, 3_000))
    }

    @Test fun jitterOnTopOfRetryAfterIsAFifthOfTheWaitAndStaysInTheBudget() {
        // Every device told Retry-After: 30 used to come back within the same half second.
        val spread = jittery.delayBeforeNextAttempt(1, 503, 30_000, 0)!!
        assertTrue("up to 6 s extra: $spread", spread in 35_900L until 36_000L)
        // With 10 s already spent only 5 s of room is left in the 45 s budget.
        val late = jittery.delayBeforeNextAttempt(1, 503, 30_000, 10_000)!!
        assertTrue("inside the budget: $late", late in 34_900L..35_000L)
        // Short waits still spread by at least a second.
        assertTrue(jittery.delayBeforeNextAttempt(1, 429, 2_000, 0)!! in 2_900L until 3_000L)
    }

    @Test fun retryAfterIsHonouredWithinLimits() {
        // 503 store_unavailable says Retry-After: 30. Wait it once; a second 30 s would exceed the budget.
        assertEquals(30_000L, policy.delayBeforeNextAttempt(1, 503, 30_000, 0))
        assertNull(policy.delayBeforeNextAttempt(2, 503, 30_000, 30_000))
        assertEquals(5_000L, policy.delayBeforeNextAttempt(1, 429, 5_000, 0))
        assertTrue("jitter on top, never below what the server asked", jittery.delayBeforeNextAttempt(1, 429, 5_000, 0)!! >= 5_000)
        assertNull("longer than we hold a purchase open: leave it for the next sync", policy.delayBeforeNextAttempt(1, 429, 42_000, 0))
    }
}
