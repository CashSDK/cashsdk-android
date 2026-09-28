package com.cashsdk

import com.cashsdk.billing.PlayPurchase
import com.cashsdk.billing.PurchaseCompleter
import com.cashsdk.billing.ReplacementCandidate
import com.cashsdk.billing.ReplacementResolution
import com.cashsdk.billing.SettleOutcome
import com.cashsdk.billing.StoreOffer
import com.cashsdk.billing.PricingPhase
import com.cashsdk.billing.resolveReplacement
import com.cashsdk.entitlements.EntitlementStore
import com.cashsdk.entitlements.SnapshotStorage
import com.cashsdk.model.PurchaseKind
import com.cashsdk.net.ApiClient
import com.cashsdk.net.HttpRequest
import com.cashsdk.net.HttpResponse
import com.cashsdk.net.HttpTransport
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Base64

/**
 * The claim on the wire for every Play purchase BillingManager verifies. BillingManager hands the
 * purchase to one of PurchaseCompleter's entry points and names no claim itself, and the client
 * hands BillingManager the EntitlementManager itself, so these are the hops a hard-coded
 * `PurchaseClaim.PURCHASE` would break. They run through the real EntitlementManager and ApiClient
 * against a scripted server.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PurchaseCompleterTest {
    private val mine = AppAccountToken.derive("A")
    private val theirs = AppAccountToken.derive("B")

    private class Server(var verifyBody: String) : HttpTransport {
        val claims = mutableListOf<String?>()
        override fun send(request: HttpRequest): HttpResponse {
            if (!request.url.endsWith("/v1/purchases:verify")) error("unexpected ${request.url}")
            claims += request.headers["X-CashSDK-Claim"]
            return HttpResponse(200, verifyBody)
        }
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

    private fun body(
        confirmed: Boolean = true,
        belongsElsewhere: Boolean = false,
        shared: Boolean = false,
        pending: Boolean = false,
    ) = """{"entitlements":[{"identifier":"pro","name":"Pro","rank":3,"source":"subscription","expiresAt":"2999-01-01T00:00:00.000Z"}],""" +
        """"tier":3,"tierIdentifier":"pro","consumables":[],"productType":"auto_renewable","quantity":1,"acknowledged":true,""" +
        """"attributed":${!pending},"pending":$pending,"purchaseOutcomeConfirmed":$confirmed,"belongsToAnotherAccount":$belongsElsewhere,""" +
        """"transferredFromAnotherAccount":false,"sharedFromAnotherAccount":$shared,"environment":"Production","userId":"A"}"""

    private val token = "h.${Base64.getUrlEncoder().withoutPadding().encodeToString(
        """{"sub":"A","exp":${System.currentTimeMillis() / 1000 + 3_600}}""".toByteArray(),
    )}.s"

    private inner class Setup(scope: TestScope, verifyBody: String = body()) {
        val server = Server(verifyBody)
        val settled = mutableListOf<String>()
        /** What `settle` was told about Play's acknowledgement, per call. */
        val settleSawAcknowledged = mutableListOf<Boolean>()
        val completer: PurchaseCompleter

        init {
            val store = EntitlementStore(MemoryStorage(), environment = "Production", clock = { 0L })
            val api = ApiClient(
                Configuration("csk_pk_test"),
                entitlementEtag = { store.etag },
                transport = server,
                ioDispatcher = StandardTestDispatcher(scope.testScheduler),
            )
            api.setIdentity("A", token)
            val access = EntitlementManager(
                api = api,
                store = store,
                scope = scope.backgroundScope,
                monotonicClock = { scope.testScheduler.currentTime },
                pinnedEnvironment = "Production",
            )
            // The same construction BillingManager uses, with Google's side replaced.
            completer = PurchaseCompleter(
                access,
                settle = { purchaseToken, _, isAcknowledged ->
                    settled += purchaseToken
                    settleSawAcknowledged += isAcknowledged
                    SettleOutcome.Settled
                },
                forget = {},
            )
        }
    }

    private fun purchase(accountToken: String?, token: String = "t1") = PlayPurchase(listOf("pro"), token, accountToken, isAcknowledged = false)

    /**
     * The server acknowledges a Play purchase DURING the verify, so the flag on the local
     * `Purchase` (read before that verify) says `false` for a purchase Google now considers
     * settled. Acting on the stale copy sent a second acknowledge after every purchase, through
     * `settleWithRetries`: up to three 15-second attempts with backoff between them.
     */
    @Test fun settleTrustsTheServersAcknowledgementOverTheStaleLocalFlag() = runTest {
        val s = Setup(this)
        // `body()` answers acknowledged = true; the Play purchase still says false.
        s.completer.completeSheetPurchase("pro", "A", purchase(mine), PurchaseKind.SUBSCRIPTION)
        assertEquals(listOf(true), s.settleSawAcknowledged)
    }

    @Test fun settleFallsBackToPlayWhenTheServerSaysNothing() = runTest {
        val s = Setup(this, body().replace(""""acknowledged":true""", """"acknowledged":null"""))
        s.completer.completeSheetPurchase("pro", "A", purchase(mine), PurchaseKind.SUBSCRIPTION)
        assertEquals(listOf(false), s.settleSawAcknowledged)
    }

    @Test fun theSheetPurchaseStampedForTheBuyerIsClaimedAsAPurchase() = runTest {
        val s = Setup(this)
        s.completer.completeSheetPurchase("pro", "A", purchase(mine), PurchaseKind.SUBSCRIPTION)
        assertEquals(listOf<String?>("purchase"), s.server.claims)
        assertEquals(listOf("t1"), s.settled)
    }

    @Test fun aSheetPurchaseWithAnotherTokenOrNoneIsClaimedAsSync() = runTest {
        val s = Setup(this)
        s.completer.completeSheetPurchase("pro", "A", purchase(theirs, "t1"), PurchaseKind.SUBSCRIPTION)
        s.completer.completeSheetPurchase("pro", "A", purchase(null, "t2"), PurchaseKind.SUBSCRIPTION)
        assertEquals(listOf<String?>("sync", "sync"), s.server.claims)
    }

    @Test fun anOwnershipCheckIsAlwaysClaimedAsSyncEvenForTheBuyersOwnToken() = runTest {
        val s = Setup(this)
        s.completer.confirmOwnership("pro", purchase(mine), PurchaseKind.SUBSCRIPTION, "A")
        s.completer.confirmOwnership("pro", purchase(null, "t2"), PurchaseKind.SUBSCRIPTION, "A")
        assertEquals(listOf<String?>("sync", "sync"), s.server.claims)
    }

    @Test fun automaticRecoveryIsAlwaysClaimedAsSync() = runTest {
        val s = Setup(this)
        s.completer.completeSync("pro", purchase(mine), PurchaseKind.SUBSCRIPTION, requireOwner = null)
        s.completer.completeSync("pro", purchase(null, "t2"), PurchaseKind.SUBSCRIPTION, requireOwner = "A")
        assertEquals(listOf<String?>("sync", "sync"), s.server.claims)
        assertEquals(listOf("t1", "t2"), s.settled)
    }

    @Test fun aPurchaseKeptWithAnotherAccountIsReportedAsAlreadyOwnedAndNotSettled() = runTest {
        val s = Setup(this, body(belongsElsewhere = true))
        try {
            s.completer.confirmOwnership("pro", purchase(null), PurchaseKind.SUBSCRIPTION, "A")
            fail("expected the ownership refusal")
        } catch (error: CashSDKError) {
            assertEquals(CashSDKError.PurchaseNotAttributed, error)
        }
        assertTrue(s.settled.isEmpty())
    }

    @Test fun aSharedSubscriptionIsConfirmedButNeverOpensAPlanChange() = runTest {
        val s = Setup(this, body(shared = true))
        val offer = StoreOffer("annual", null, "annual:base", listOf(PricingPhase("$1", 1_000_000, "USD", "P1Y", 0, 1)))
        val held = ReplacementCandidate(listOf("pro"), "t1", null, purchased = true)
        val resolution = resolveReplacement("pro", offer, listOf(held), "A", PurchaseOptions(basePlanId = "annual")) {
            s.completer.confirmOwnership("pro", purchase(null), PurchaseKind.SUBSCRIPTION, "A")
        }
        assertTrue(resolution is ReplacementResolution.Shared)
        assertTrue((resolution as ReplacementResolution.Shared).entitlements.sharedFromAnotherAccount)
        assertEquals(listOf<String?>("sync"), s.server.claims)
    }

    @Test fun anOwnSubscriptionConfirmedByTheServerGoesToThePlanChange() = runTest {
        val s = Setup(this, body(shared = false))
        val offer = StoreOffer("annual", null, "annual:base", listOf(PricingPhase("$1", 1_000_000, "USD", "P1Y", 0, 1)))
        val held = ReplacementCandidate(listOf("pro"), "t1", null, purchased = true)
        val resolution = resolveReplacement("pro", offer, listOf(held), "A", PurchaseOptions(basePlanId = "annual")) {
            s.completer.confirmOwnership("pro", purchase(null), PurchaseKind.SUBSCRIPTION, "A")
        }
        assertEquals("t1", (resolution as ReplacementResolution.Buy).replacement!!.purchaseToken)
    }

    @Test fun anUnconfirmedPurchaseIsStillSettledAndReturnedWithItsFlag() = runTest {
        val s = Setup(this, body(confirmed = false))
        val result = s.completer.completeSheetPurchase("pro", "A", purchase(mine), PurchaseKind.SUBSCRIPTION)
        assertEquals(false, result.purchaseOutcomeConfirmed)
        assertEquals("Google refunds what nobody acknowledges", listOf("t1"), s.settled)
    }

    @Test fun aPendingPaymentIsNeverSettled() = runTest {
        val s = Setup(this, body(pending = true))
        val result = s.completer.completeSheetPurchase("pro", "A", purchase(mine), PurchaseKind.SUBSCRIPTION)
        assertTrue(result.pending)
        assertTrue(s.settled.isEmpty())
        try {
            s.completer.confirmOwnership("pro", purchase(mine), PurchaseKind.SUBSCRIPTION, "A")
            fail("expected pending")
        } catch (error: CashSDKError) {
            assertEquals(CashSDKError.PurchasePending, error)
        }
        assertFalse(s.settled.contains("t1"))
    }
}
