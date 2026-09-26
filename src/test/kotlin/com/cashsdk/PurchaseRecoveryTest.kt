package com.cashsdk

import com.cashsdk.billing.OwnedCandidate
import com.cashsdk.billing.PurchaseOwnership
import com.cashsdk.billing.SettleAttempt
import com.cashsdk.billing.SettleOutcome
import com.cashsdk.billing.SyncCandidate
import com.cashsdk.billing.awaitPurchaseUpdate
import com.cashsdk.billing.claimForSheetPurchase
import com.cashsdk.billing.purchaseOwnership
import com.cashsdk.billing.resolveAlreadyOwned
import com.cashsdk.billing.settleWithRetries
import com.cashsdk.billing.stopsSync
import com.cashsdk.billing.syncOwnedPurchases
import com.cashsdk.model.Entitlements
import com.cashsdk.model.PurchaseClaim
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PurchaseRecoveryTest {
    private val mine = AppAccountToken.derive("A")
    private val theirs = AppAccountToken.derive("B")

    // ── Automatic sync ──────────────────────────────────────────────────────────

    // Which purchases the pass verifies, and for whom. The claim is PurchaseCompleter's to pick
    // (always `sync` on this path); PurchaseCompleterTest pins it.
    private data class Completed(val token: String, val requireOwner: String?)

    private fun candidate(token: String, accountToken: String?, purchased: Boolean = true, product: String? = "pro") =
        SyncCandidate(token, token, product, accountToken, purchased)

    private suspend fun sync(
        live: List<SyncCandidate<String>>,
        currentUser: () -> String? = { "A" },
        failWith: (String) -> Exception? = { null },
        denied: Set<String> = emptySet(),
        denials: MutableList<String> = mutableListOf(),
    ): Pair<List<Completed>, List<String>> {
        val completed = mutableListOf<Completed>()
        val remembered = mutableListOf<String>()
        syncOwnedPurchases(
            "A", live, currentUser,
            remember = { remembered += it },
            denied = denied,
            deny = { denials += it },
        ) { handle, _, owner ->
            completed += Completed(handle, owner)
            failWith(handle)?.let { throw it }
        }
        return completed to remembered
    }

    /**
     * A purchase with no account token (a Play Store resubscribe, a promo code) that the server
     * keeps with another app account was re-verified on every sync, for the life of the install,
     * always to be told the same thing. One identify per launch, one wasted verify each, forever.
     */
    @Test fun anOwnershipVerdictIsRecordedAndThenHonoured() = runTest {
        val denials = mutableListOf<String>()
        val live = listOf(candidate("t1", null), candidate("t2", mine))
        val (first, _) = sync(
            live,
            failWith = { if (it == "t1") CashSDKError.PurchaseBelongsToAnotherAccount else null },
            denials = denials,
        )
        assertEquals("both are tried the first time", listOf("t1", "t2"), first.map { it.token })
        assertEquals("and the verdict about t1 is recorded", listOf("t1"), denials)

        val (second, _) = sync(live, denied = denials.toSet())
        assertEquals("the next pass skips it and does the rest", listOf("t2"), second.map { it.token })
    }

    /**
     * "Credited to nobody" is the case identify() exists to resolve, so it must stay retryable.
     * Recording it would strand a purchase made before sign-in.
     */
    @Test fun anUnattributedPurchaseIsNeverDenied() = runTest {
        val denials = mutableListOf<String>()
        sync(
            listOf(candidate("t1", null)),
            failWith = { CashSDKError.PurchaseNotAttributed },
            denials = denials,
        )
        assertTrue("an unattributed purchase stays retryable", denials.isEmpty())
    }

    /** A transport failure says nothing about ownership; it must not be mistaken for a verdict. */
    @Test fun aTransientFailureIsNotAVerdict() = runTest {
        val denials = mutableListOf<String>()
        sync(
            listOf(candidate("t1", null)),
            failWith = { CashSDKError.Server(500, "boom") },
            denials = denials,
        )
        assertTrue(denials.isEmpty())
    }

    @Test fun ownPurchasesAreSettledAsBefore() = runTest {
        val (completed, remembered) = sync(listOf(candidate("own", mine)))
        assertEquals(listOf(Completed("own", null)), completed)
        assertEquals(listOf("own"), remembered)
    }

    @Test fun tokenlessPurchasesAreSyncedButSettledOnlyForAConfirmedOwner() = runTest {
        // A Play Store resubscribe or a promo code carries no account token. It used to be skipped
        // until the user found Restore; with claim `sync` the server may credit it but never move it.
        val (completed, _) = sync(listOf(candidate("resubscribe", null), candidate("blank", "")))
        assertEquals(
            listOf(Completed("resubscribe", "A"), Completed("blank", "A")),
            completed,
        )
    }

    @Test fun anotherAccountsPurchaseIsLeftForAnExplicitRestore() = runTest {
        val (completed, remembered) = sync(listOf(candidate("theirs", theirs), candidate("legacy", "legacy-sdk-token")))
        assertTrue(completed.isEmpty())
        assertTrue(remembered.isEmpty())
    }

    @Test fun pendingPurchasesAreTrackedNotVerified() = runTest {
        val (completed, remembered) = sync(listOf(candidate("pending", mine, purchased = false)))
        assertTrue(completed.isEmpty())
        assertEquals(listOf("pending"), remembered)
    }

    @Test fun anOutageEndsThePassAndOneBadPurchaseDoesNot() = runTest {
        val live = listOf(candidate("bad", mine), candidate("down", mine), candidate("later", mine))
        val (completed, _) = sync(live, failWith = {
            when (it) {
                "bad" -> CashSDKError.Server(401, "invalid_purchase")
                "down" -> CashSDKError.Server(503, "store_unavailable")
                else -> null
            }
        })
        assertEquals("a 401 is about one purchase; a 503 would hit the rest too", listOf("bad", "down"), completed.map { it.token })
    }

    @Test fun aUserSwitchStopsThePass() = runTest {
        var user = "A"
        val live = listOf(candidate("first", mine), candidate("second", mine))
        val completed = mutableListOf<String>()
        syncOwnedPurchases("A", live, { user }, remember = {}) { handle, _, _ ->
            completed += handle
            user = "B"
        }
        assertEquals(listOf("first"), completed)
    }

    @Test fun whatStopsAPass() {
        assertTrue(stopsSync(CashSDKError.Network(java.io.IOException("offline"))))
        assertTrue(stopsSync(CashSDKError.Server(429, "rate_limited")))
        assertTrue(stopsSync(CashSDKError.Server(503, "store_unavailable")))
        assertTrue(stopsSync(CashSDKError.Server(408)))
        assertTrue(stopsSync(CashSDKError.NotIdentified))
        assertFalse(stopsSync(CashSDKError.Server(401, "invalid_purchase")))
        assertFalse(stopsSync(CashSDKError.PurchaseBelongsToAnotherAccount))
        assertFalse(stopsSync(CashSDKError.PurchaseNotAttributed))
    }

    @Test fun ownershipIsReadFromTheAccountToken() {
        assertEquals(PurchaseOwnership.OWN, purchaseOwnership("A", mine))
        assertEquals(PurchaseOwnership.OWN, purchaseOwnership("A", mine.uppercase()))
        assertEquals(PurchaseOwnership.NO_TOKEN, purchaseOwnership("A", null))
        assertEquals(PurchaseOwnership.NO_TOKEN, purchaseOwnership("A", " "))
        assertEquals(PurchaseOwnership.OTHER_TOKEN, purchaseOwnership("A", theirs))
        assertEquals(PurchaseOwnership.OTHER_TOKEN, purchaseOwnership("A", "legacy-sdk-token"))
    }

    // ── ITEM_ALREADY_OWNED ────────────────────────────────────────────────────

    private val alreadyOwned = CashSDKError.Billing(7, "Item is already owned")
    private val snapshotForA = Entitlements(userId = "A")

    private fun owned(token: String, accountToken: String?, purchased: Boolean = true) = OwnedCandidate(token, accountToken, purchased)

    @Test fun theUsersOwnPurchaseComesBackInsteadOfABillingError() = runTest {
        // The check itself is PurchaseCompleter.confirmOwnership, which always claims `sync`
        // (pinned in PurchaseCompleterTest).
        val checked = mutableListOf<String>()
        val result = resolveAlreadyOwned("A", listOf(owned("t1", mine)), alreadyOwned, { "A" }) { token ->
            checked += token
            snapshotForA
        }
        assertSame(snapshotForA, result)
        assertEquals(listOf("t1"), checked)
    }

    @Test fun aPurchaseOnAnotherAppAccountEndsAsPurchaseNotAttributed() = runTest {
        // What hosts already map to "already owned: restore, don't buy".
        for (refusal in listOf<CashSDKError>(CashSDKError.PurchaseBelongsToAnotherAccount, CashSDKError.PurchaseNotAttributed)) {
            try {
                resolveAlreadyOwned("A", listOf(owned("t1", theirs)), alreadyOwned, { "A" }) { _ -> throw refusal }
                fail("expected the ownership error")
            } catch (error: CashSDKError) {
                assertEquals(CashSDKError.PurchaseNotAttributed, error)
            }
        }
    }

    @Test fun theUsersTokenIsTriedFirstThenNoTokenThenOthers() = runTest {
        val tried = mutableListOf<String>()
        try {
            resolveAlreadyOwned("A", listOf(owned("other", theirs), owned("none", null), owned("own", mine)), alreadyOwned, { "A" }) { token ->
                tried += token
                throw CashSDKError.PurchaseNotAttributed
            }
            fail("expected the ownership error")
        } catch (error: CashSDKError) {
            assertEquals(CashSDKError.PurchaseNotAttributed, error)
        }
        assertEquals(listOf("own", "none", "other"), tried)
    }

    @Test fun anOwnershipRefusalOutranksAnotherCandidatesFailure() = runTest {
        try {
            resolveAlreadyOwned("A", listOf(owned("own", mine), owned("other", theirs)), alreadyOwned, { "A" }) { token ->
                throw if (token == "own") CashSDKError.Server(401, "invalid_purchase") else CashSDKError.PurchaseBelongsToAnotherAccount
            }
            fail("expected the ownership error")
        } catch (error: CashSDKError) {
            assertEquals(CashSDKError.PurchaseNotAttributed, error)
        }
    }

    // ── The purchase handed back to the open sheet (L6) ─────────────────────────

    @Test fun onlyAPurchaseStampedForTheBuyerIsClaimedAsAPurchase() {
        assertEquals(PurchaseClaim.PURCHASE, claimForSheetPurchase("A", mine))
        assertEquals(PurchaseClaim.PURCHASE, claimForSheetPurchase("A", mine.uppercase()))
        // Updates are matched by product id, so another account's purchase (or one with no
        // token) can reach the sheet; `sync` cannot move it from its owner.
        assertEquals(PurchaseClaim.SYNC, claimForSheetPurchase("A", theirs))
        assertEquals(PurchaseClaim.SYNC, claimForSheetPurchase("A", "legacy-sdk-token"))
        assertEquals(PurchaseClaim.SYNC, claimForSheetPurchase("A", null))
    }

    @Test fun onlyAPendingPaymentMeansPending() = runTest {
        try {
            resolveAlreadyOwned("A", listOf(owned("t1", mine, purchased = false)), alreadyOwned, { "A" }) { _ -> fail(); snapshotForA }
            fail("expected pending")
        } catch (error: CashSDKError) {
            assertEquals(CashSDKError.PurchasePending, error)
        }
    }

    @Test fun nothingFoundKeepsTheOriginalError() = runTest {
        try {
            resolveAlreadyOwned("A", emptyList<OwnedCandidate<String>>(), alreadyOwned, { "A" }) { _ -> snapshotForA }
            fail("expected the billing error")
        } catch (error: CashSDKError) {
            assertEquals(alreadyOwned, error)
        }
    }

    @Test fun anOutageDuringTheCheckIsReportedAsSuch() = runTest {
        val offline = CashSDKError.Network(java.io.IOException("offline"))
        val tried = mutableListOf<String>()
        try {
            resolveAlreadyOwned("A", listOf(owned("own", mine), owned("none", null)), alreadyOwned, { "A" }) { token ->
                tried += token
                throw offline
            }
            fail("expected the network error")
        } catch (error: CashSDKError) {
            assertEquals(offline, error)
        }
        assertEquals("the next candidate would fail the same way", listOf("own"), tried)
    }

    @Test fun aUserSwitchStopsTheCheck() = runTest {
        try {
            resolveAlreadyOwned("A", listOf(owned("t1", mine)), alreadyOwned, { "B" }) { _ -> snapshotForA }
            fail("expected NotIdentified")
        } catch (error: CashSDKError) {
            assertEquals(CashSDKError.NotIdentified, error)
        }
    }

    // ── Deadlines ───────────────────────────────────────────────────────────────

    @Test fun anUnansweredPaymentSheetIsPendingNotFailed() = runTest {
        try {
            awaitPurchaseUpdate(300_000) { awaitCancellation() }
            fail("expected pending")
        } catch (error: CashSDKError) {
            assertEquals("Play may still complete it; the next sync verifies it", CashSDKError.PurchasePending, error)
        }
        assertEquals(300_000L, currentTime)
        assertEquals("answer", awaitPurchaseUpdate(300_000) { "answer" })
    }

    @Test fun callerCancellationIsStillCancellation() = runTest {
        try {
            awaitPurchaseUpdate(300_000) { throw CancellationException("caller left") }
            fail("expected cancellation")
        } catch (error: CancellationException) {
            assertEquals("caller left", error.message)
        }
    }

    @Test fun aSettlementTimeoutIsRetriedAndThenLeftForTheNextSync() = runTest {
        var calls = 0
        val outcome = settleWithRetries { calls++; null }
        assertEquals(3, calls)
        assertTrue(outcome is SettleOutcome.Failed && outcome.retryable)
    }

    @Test fun settlementRetriesTransientFailuresOnly() = runTest {
        var calls = 0
        assertEquals(SettleOutcome.Settled, settleWithRetries { if (calls++ == 0) null else SettleAttempt(0, null) })
        assertEquals(2, calls)
        calls = 0
        val refused = settleWithRetries { calls++; SettleAttempt(5, "developer error") }
        assertEquals(1, calls)
        assertFalse((refused as SettleOutcome.Failed).retryable)
        assertEquals("already consumed counts as settled", SettleOutcome.Settled, settleWithRetries { SettleAttempt(8, null) })
    }
}
