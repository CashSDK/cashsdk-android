package com.cashsdk

import com.cashsdk.billing.OwnedPurchase
import com.cashsdk.billing.SettleOutcome
import com.cashsdk.entitlements.EntitlementStore
import com.cashsdk.entitlements.SnapshotStorage
import com.cashsdk.model.Entitlement
import com.cashsdk.model.Entitlements
import com.cashsdk.model.PurchaseClaim
import com.cashsdk.model.PurchaseKind
import com.cashsdk.net.ApiClient
import com.cashsdk.net.HttpRequest
import com.cashsdk.net.HttpResponse
import com.cashsdk.net.HttpTransport
import com.cashsdk.net.UrlConnectionTransport
import com.cashsdk.net.VerifyRetryPolicy
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.Instant
import java.util.Base64

/**
 * The money path with a scripted server and virtual time: what happens around an access
 * deadline, and whether the claim chosen by a caller reaches the request header.
 *
 * Every deadline here is judged with the injected clock (`base` plus virtual time), never the
 * real one, so the suite does not depend on the day it runs.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EntitlementManagerTest {
    private val base = Instant.parse("2026-09-23T12:00:00Z").toEpochMilli()

    /** Answers by path and records every request. */
    private class FakeServer : HttpTransport {
        val requests = mutableListOf<HttpRequest>()
        var entitlements: (HttpRequest) -> HttpResponse = { error("no entitlements reply scripted") }
        var verify: (HttpRequest) -> HttpResponse = { error("no verify reply scripted") }
        override fun send(request: HttpRequest): HttpResponse {
            requests += request
            return when {
                request.url.endsWith("/v1/entitlements") -> entitlements(request)
                request.url.endsWith("/v1/purchases:verify") -> verify(request)
                else -> error("unexpected ${request.url}")
            }
        }
        val reads get() = requests.filter { it.url.endsWith("/v1/entitlements") }
        val verifies get() = requests.filter { it.url.endsWith("/v1/purchases:verify") }
    }

    private class MemoryStorage : SnapshotStorage {
        var value: String? = null
        override fun read(): String? = value
        override fun write(value: String): Boolean {
            this.value = value
            return true
        }
        override fun remove() {
            value = null
        }
    }

    private inner class Harness(
        val scope: TestScope,
        var foreground: Boolean = true,
        config: Configuration = Configuration("csk_pk_test"),
        realTransport: Boolean = false,
    ) {
        val server = FakeServer()
        val wall = { base + scope.testScheduler.currentTime }
        val store = EntitlementStore(
            MemoryStorage(), environment = "Production", clock = wall,
            // Keep the snapshot write on the test's scheduler; in production it hops to IO so a
            // `commit()` never runs on the main thread.
            io = StandardTestDispatcher(scope.testScheduler),
        )
        val api = ApiClient(
            config,
            entitlementEtag = { store.etag },
            transport = if (realTransport) UrlConnectionTransport else server,
            verifyRetry = VerifyRetryPolicy(random = { 0.0 }),
            ioDispatcher = StandardTestDispatcher(scope.testScheduler),
        )
        val manager = EntitlementManager(
            api = api,
            store = store,
            scope = scope.backgroundScope,
            monotonicClock = { scope.testScheduler.currentTime },
            pinnedEnvironment = "Production",
            isForeground = { foreground },
            wallClock = wall,
        )
        /** Every snapshot published to hosts, in order. */
        val published = mutableListOf<Entitlements>()

        init {
            api.setIdentity("A", token("A"))
            // The store writes on `io`, which this harness pins to the test scheduler, so the
            // seed runs to completion before the collectors below start.
            scope.backgroundScope.launch { store.update("A", snapshot(until = base + 60_000), "W/\"v1\"") }
            scope.runCurrent()
            scope.backgroundScope.launch { store.snapshot.collect { published += it } }
            scope.backgroundScope.launch { manager.watchDeadlines() }
            scope.runCurrent()
        }
    }

    private fun token(sub: String, expSeconds: Long = System.currentTimeMillis() / 1000 + 10L * 365 * 86_400) =
        "h.${Base64.getUrlEncoder().withoutPadding().encodeToString("""{"sub":"$sub","exp":$expSeconds}""".toByteArray())}.s"

    private fun snapshot(until: Long?) = Entitlements(
        listOf(Entitlement("pro", "Pro", 3, "subscription", until?.let { Instant.ofEpochMilli(it).toString() })),
        tier = 3,
        tierIdentifier = "pro",
        userId = "A",
    )

    private fun body(until: Long?) = HttpResponse(
        200,
        """{"entitlements":[{"identifier":"pro","name":"Pro","rank":3,"source":"subscription",""" +
            """"expiresAt":${until?.let { "\"${Instant.ofEpochMilli(it)}\"" } ?: "null"}}],"tier":3,"tierIdentifier":"pro","userId":"A"}""",
        mapOf("ETag" to "W/\"v$until\""),
    )

    private fun Entitlements.ids() = active.map { it.identifier }

    @After fun resetClock() = ServerClock.reset()

    // ── At a deadline ───────────────────────────────────────────────────────────

    @Test fun aRenewalFoundBeforeTheDeadlineIsNeverPublishedAsLapsed() = runTest {
        val h = Harness(this)
        h.server.entitlements = { body(until = base + 3_600_000) }
        advanceTimeBy(50_001)
        runCurrent()
        assertEquals("read 10 s before the deadline", 1, h.server.reads.size)
        assertNull("no ETag: the server's current snapshot, not a 304", h.server.reads.single().headers["If-None-Match"])
        advanceTimeBy(20_000)
        runCurrent()
        assertTrue("never published without pro: ${h.published}", h.published.all { "pro" in it.ids() })
        assertEquals(listOf("pro"), h.store.current.ids())
        assertEquals("no retry after an answer", 1, h.server.reads.size)
    }

    @Test fun aFailedReadEndsTheAccessAtTheDeadlineAndRetries() = runTest {
        val h = Harness(this)
        var offline = true
        h.server.entitlements = { if (offline) throw IOException("offline") else body(until = base + 3_600_000) }
        advanceTimeBy(59_999)
        runCurrent()
        assertEquals(1, h.server.reads.size)
        assertEquals("still before the deadline", listOf("pro"), h.store.snapshot.value.ids())
        advanceTimeBy(2)
        runCurrent()
        assertEquals("fail closed at the deadline", emptyList<String>(), h.store.snapshot.value.ids())

        offline = false
        // The retry waits for the one-a-minute gate: 60 s after the read made at 50 s.
        advanceTimeBy(49_998)
        runCurrent()
        assertEquals(1, h.server.reads.size)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(2, h.server.reads.size)
        assertEquals("the retry's renewal restores access", listOf("pro"), h.store.snapshot.value.ids())
    }

    @Test fun aNotModifiedAnswerConfirmsTheDeadlineWithoutRetries() = runTest {
        val h = Harness(this)
        h.server.entitlements = { HttpResponse(304, "") }
        advanceTimeBy(60_001)
        runCurrent()
        assertEquals(1, h.server.reads.size)
        assertEquals("the server confirmed the snapshot that ends now", emptyList<String>(), h.store.snapshot.value.ids())
        advanceTimeBy(10 * 60_000)
        runCurrent()
        assertEquals("an answer is not retried", 1, h.server.reads.size)
    }

    @Test fun aLapsedSubscriptionIsRemovedByTheServersAnswer() = runTest {
        val h = Harness(this)
        h.server.entitlements = { HttpResponse(200, """{"entitlements":[],"tier":0,"tierIdentifier":null,"userId":"A"}""") }
        advanceTimeBy(50_001)
        runCurrent()
        assertEquals(emptyList<String>(), h.store.snapshot.value.ids())
        advanceTimeBy(10 * 60_000)
        runCurrent()
        assertEquals(1, h.server.reads.size)
    }

    @Test fun inTheBackgroundNothingIsSentAndTheAccessStillEnds() = runTest {
        val h = Harness(this, foreground = false)
        h.server.entitlements = { error("no request in the background") }
        advanceTimeBy(60_001)
        runCurrent()
        assertTrue(h.server.reads.isEmpty())
        assertEquals(emptyList<String>(), h.store.snapshot.value.ids())
        advanceTimeBy(10 * 60_000)
        runCurrent()
        assertTrue(h.server.reads.isEmpty())
    }

    @Test fun retriesStopWhenTheAppGoesToTheBackground() = runTest {
        val h = Harness(this)
        h.server.entitlements = { throw IOException("offline") }
        advanceTimeBy(60_001)
        runCurrent()
        assertEquals(1, h.server.reads.size)
        h.foreground = false
        advanceTimeBy(30 * 60_000)
        runCurrent()
        assertEquals(1, h.server.reads.size)
    }

    @Test fun retriesAreBounded() = runTest {
        val h = Harness(this)
        h.server.entitlements = { HttpResponse(503, """{"error":"store_unavailable"}""") }
        advanceTimeBy(60 * 60_000)
        runCurrent()
        assertEquals("one read before the deadline, then five retries", 1 + EntitlementManager.DEADLINE_RETRY_DELAYS_MS.size, h.server.reads.size)
    }

    @Test fun aRollingDeadlineKeepsAccessPublishedAtAboutOneReadAMinute() = runTest {
        val h = Harness(this)
        // A server whose every answer ends 60 s later (a grace period with no dates, as servers
        // before API commit 1f4f1d9 sent it). The one-a-minute gate opens right at each deadline:
        // the read waits for it instead of giving up, and the access never goes out.
        h.server.entitlements = { body(until = base + testScheduler.currentTime + 60_000) }
        advanceTimeBy(10 * 60_000)
        runCurrent()
        assertTrue("reads: ${h.server.reads.size}", h.server.reads.size in 9..11)
        assertTrue("access went out: ${h.published.filter { "pro" !in it.ids() }}", h.published.all { "pro" in it.ids() })
        assertEquals(listOf("pro"), h.store.snapshot.value.ids())
    }

    @Test fun aServerRollingFasterThanTheGateStillCostsAtMostOneReadAMinute() = runTest {
        val h = Harness(this)
        // Deadlines 20 s apart: the gate cannot open in time, so each one fails closed and the
        // retry reads when it opens. Access flaps, but the request rate stays bounded.
        h.server.entitlements = { body(until = base + testScheduler.currentTime + 20_000) }
        advanceTimeBy(10 * 60_000)
        runCurrent()
        assertTrue("reads: ${h.server.reads.size}", h.server.reads.size in 9..11)
    }

    @Test fun aReadWaitsForTheGateWhenItOpensWithinTheAnswerWait() = runTest {
        val h = Harness(this)
        val readAt = mutableListOf<Long>()
        // The read at 50 s answers with a deadline at 106 s; the gate reopens at 110 s, inside
        // the 5 s answer wait after 106 s, so the next read waits for it instead of giving up.
        h.server.entitlements = {
            readAt += testScheduler.currentTime
            body(until = base + if (readAt.size == 1) 106_000 else 3_600_000)
        }
        advanceTimeBy(120_000)
        runCurrent()
        assertEquals(listOf(50_000L, 110_000L), readAt)
        assertTrue("access went out: ${h.published.filter { "pro" !in it.ids() }}", h.published.all { "pro" in it.ids() })
    }

    @Test fun aGateThatOpensTooLateFailsClosedAndRetries() = runTest {
        val h = Harness(this)
        val readAt = mutableListOf<Long>()
        // Deadline 105 s: the gate reopens at 110 s, not before 105 + 5 s. The access ends at the
        // deadline, and the first retry reads 15 s later.
        h.server.entitlements = {
            readAt += testScheduler.currentTime
            body(until = base + if (readAt.size == 1) 105_000 else 3_600_000)
        }
        advanceTimeBy(105_001)
        runCurrent()
        assertEquals(emptyList<String>(), h.store.snapshot.value.ids())
        advanceTimeBy(15_000)
        runCurrent()
        assertEquals(listOf(50_000L, 120_000L), readAt)
        assertEquals(listOf("pro"), h.store.snapshot.value.ids())
    }

    @Test fun theReadUsesTheTokenTheUserHasWhenItIsSent() = runTest {
        val h = Harness(this)
        h.server.entitlements = { body(until = base + 3_600_000) }
        val refreshed = token("A", System.currentTimeMillis() / 1000 + 20L * 365 * 86_400)
        advanceTimeBy(30_000)
        h.api.setIdentity("A", refreshed)
        advanceTimeBy(20_001)
        runCurrent()
        assertEquals(refreshed, h.server.reads.single().headers["X-CashSDK-User-Token"])
    }

    @Test fun anExpiredTokenIsNotSentAndAFreshIdentifyReads() = runTest {
        val h = Harness(this)
        h.api.setIdentity("A", token("A", expSeconds = 1_000))
        h.server.entitlements = { body(until = base + 3_600_000) }
        advanceTimeBy(5 * 60_000)
        runCurrent()
        assertTrue("an expired token would only earn a 401", h.server.reads.isEmpty())
        assertEquals(emptyList<String>(), h.store.snapshot.value.ids())
        // The host signs the user in again with a fresh token.
        h.api.setIdentity("A", token("A"))
        h.manager.onIdentityChanged("A")
        runCurrent()
        assertEquals(1, h.server.reads.size)
        assertEquals(listOf("pro"), h.store.snapshot.value.ids())
    }

    /**
     * Hosts identify on every launch, after every token refresh and often on resume. Each of
     * those used to force a read, however recently the snapshot had been fetched, so a screen
     * that re-identified on resume sent one request per resume.
     */
    @Test fun reIdentifyingTheSameUserReadsThroughTheOrdinaryGate() = runTest {
        val h = Harness(this)
        h.server.entitlements = { body(until = base + 3_600_000) }
        h.manager.refreshInBackground(force = true)
        runCurrent()
        assertEquals("the launch read", 1, h.server.reads.size)

        h.manager.onIdentityChanged("A", sameUser = true)
        runCurrent()
        assertEquals("a re-identify seconds later adds nothing", 1, h.server.reads.size)
        assertEquals("and the snapshot is untouched", listOf("pro"), h.store.snapshot.value.ids())

        // Past the foreground gate, it reads again like any other refresh.
        advanceTimeBy(5 * 60_000 + 1)
        h.manager.onIdentityChanged("A", sameUser = true)
        runCurrent()
        assertEquals(2, h.server.reads.size)
    }

    /** A different user still gets the full re-key: their cache, their ETag, their read, now. */
    @Test fun adifferentUserStillForcesTheRead() = runTest {
        val h = Harness(this)
        h.server.entitlements = { body(until = base + 3_600_000) }
        h.manager.refreshInBackground(force = true)
        runCurrent()
        assertEquals(1, h.server.reads.size)
        h.api.setIdentity("B", token("B"))
        h.manager.onIdentityChanged("B")
        runCurrent()
        assertEquals("B is read immediately, not in five minutes", 2, h.server.reads.size)
        assertEquals("B", h.server.reads[1].headers["X-CashSDK-User-Id"])
    }

    @Test fun signingInAnotherUserDropsTheRetryForTheFirstUsersDeadline() = runTest {
        val h = Harness(this)
        h.server.entitlements = { throw IOException("offline") }
        advanceTimeBy(60_001)
        runCurrent()
        assertEquals("A's deadline read failed and a retry is pending", 1, h.server.reads.size)
        h.api.setIdentity("B", token("B"))
        h.manager.onIdentityChanged("B")
        runCurrent()
        assertEquals(2, h.server.reads.size)
        assertEquals("B's own first read", "B", h.server.reads[1].headers["X-CashSDK-User-Id"])
        advanceTimeBy(30 * 60_000)
        runCurrent()
        assertEquals("no read for A's deadline goes out with B's token", 2, h.server.reads.size)
    }

    @Test fun aRetryIsDroppedWhenSomethingElseAlreadyReadTheSnapshot() = runTest {
        val h = Harness(this)
        var offline = true
        h.server.entitlements = { if (offline) throw IOException("offline") else body(until = null) }
        advanceTimeBy(60_001)
        runCurrent()
        assertEquals(emptyList<String>(), h.store.snapshot.value.ids())
        // The app comes back to the foreground before the retry is due (110 s) and reads.
        offline = false
        h.manager.onForeground()
        runCurrent()
        assertEquals(2, h.server.reads.size)
        assertEquals(listOf("pro"), h.store.snapshot.value.ids())
        advanceTimeBy(30 * 60_000)
        runCurrent()
        assertEquals("the retry saw the fresh snapshot and sent nothing", 2, h.server.reads.size)
    }

    @Test fun aMalformedApiBaseFailsTheReadInsteadOfCrashing() = runTest {
        // Every request fails as a network error; nothing may throw out of the deadline watch.
        val h = Harness(this, config = Configuration("csk_pk_test", apiBase = "not a url"), realTransport = true)
        h.api.requireValidUserToken(h.api.identitySnapshot())
        assertTrue(h.api.hasUsableToken())
        advanceTimeBy(30 * 60_000)
        runCurrent()
        assertEquals("fail closed at the deadline", emptyList<String>(), h.store.snapshot.value.ids())
        // The watch is still alive: a new deadline is handled like any other.
        h.store.update("A", snapshot(until = base + testScheduler.currentTime + 60_000), null)
        advanceTimeBy(61_000)
        runCurrent()
        assertEquals(emptyList<String>(), h.store.snapshot.value.ids())
    }

    @Test fun anotherUserSigningInCancelsTheRetries() = runTest {
        val h = Harness(this)
        h.server.entitlements = { throw IOException("offline") }
        advanceTimeBy(60_001)
        runCurrent()
        h.api.setIdentity(null, null)
        h.manager.onLogout()
        advanceTimeBy(30 * 60_000)
        runCurrent()
        assertEquals(1, h.server.reads.size)
    }

    @Test fun foregroundReadsAreThrottledToOneInFiveMinutes() = runTest {
        val h = Harness(this)
        h.store.update("A", snapshot(until = null), null)
        runCurrent()
        h.server.entitlements = { body(until = null) }
        h.manager.onForeground()
        runCurrent()
        h.manager.onForeground()
        runCurrent()
        assertEquals(1, h.server.reads.size)
        advanceTimeBy(5 * 60_000)
        h.manager.onForeground()
        runCurrent()
        assertEquals(2, h.server.reads.size)
    }

    // ── Claims (the hops a dropped argument would break) ────────────────────────

    private fun verified() = HttpResponse(
        200,
        """{"entitlements":[{"identifier":"pro","name":"Pro","rank":3,"source":"subscription","expiresAt":"2999-01-01T00:00:00.000Z"}],""" +
            """"tier":3,"tierIdentifier":"pro","consumables":[],"productType":"auto_renewable","quantity":1,"acknowledged":true,""" +
            """"attributed":true,"purchaseOutcomeConfirmed":true,"belongsToAnotherAccount":false,""" +
            """"transferredFromAnotherAccount":false,"environment":"Production","userId":"A"}""",
    )

    @Test fun restoreSendsTheRestoreClaimForEveryPurchase() = runTest {
        val h = Harness(this)
        h.server.verify = { verified() }
        h.server.entitlements = { body(until = base + 3_600_000) }
        val owned = listOf(
            OwnedPurchase("pro", "t1", PurchaseKind.SUBSCRIPTION, isAcknowledged = true),
            OwnedPurchase("coins", "t2", PurchaseKind.PRODUCT),
        )
        val result = h.manager.restoreDetailed(queryOwned = { owned }, settle = { _, _ -> SettleOutcome.Settled })
        assertEquals(listOf("restore", "restore"), h.server.verifies.map { it.headers["X-CashSDK-Claim"] })
        assertTrue(result.failures.isEmpty())
        assertEquals("the restore ends with a read of the snapshot", 1, h.server.reads.size)
    }

    @Test fun anUnconfirmedPurchaseIsReturnedWithItsFlagNotThrown() = runTest {
        val h = Harness(this)
        h.server.verify = { HttpResponse(200, verified().body.replace(""""purchaseOutcomeConfirmed":true""", """"purchaseOutcomeConfirmed":false""")) }
        val result = h.manager.verifyPurchase("pro", "t1", PurchaseKind.SUBSCRIPTION, PurchaseClaim.PURCHASE)
        assertEquals(false, result.purchaseOutcomeConfirmed)
        assertEquals("A", result.userId)
    }

    @Test fun anotherAccountsPurchaseIsNotCached() = runTest {
        val h = Harness(this)
        h.server.verify = { HttpResponse(200, """{"entitlements":[],"tier":0,"consumables":[],"attributed":true,"belongsToAnotherAccount":true,"userId":"A"}""") }
        try {
            h.manager.verifyPurchase("pro", "t1", PurchaseKind.SUBSCRIPTION, PurchaseClaim.SYNC)
            throw AssertionError("expected the ownership refusal")
        } catch (error: CashSDKError) {
            assertEquals(CashSDKError.PurchaseBelongsToAnotherAccount, error)
        }
        assertEquals(listOf("pro"), h.store.current.ids())
        assertFalse(h.store.current.belongsToAnotherAccount)
    }
}
