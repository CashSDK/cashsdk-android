package com.cashsdk

import com.cashsdk.billing.OwnedPurchase
import com.cashsdk.billing.SettleOutcome
import com.cashsdk.model.Entitlements
import com.cashsdk.model.PurchaseClaim
import com.cashsdk.model.PurchaseKind
import com.cashsdk.net.VerifyAttribution
import com.cashsdk.net.VerifyDecision
import com.cashsdk.net.accessUnconfirmed
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RestoreAndOutcomeTest {
    private val json = Json { ignoreUnknownKeys = true }

    // ── purchaseOutcomeConfirmed ───────────────────────────────────────────────

    private fun verifyBody(confirmed: String?) =
        """{"entitlements":[],"tier":0,"tierIdentifier":null,"consumables":[],"productType":"auto_renewable",""" +
            """"quantity":1,"acknowledged":true,"attributed":true,"belongsToAnotherAccount":false,""" +
            """"transferredFromAnotherAccount":false,"environment":"Production","userId":"A"""" +
            (confirmed?.let { ""","purchaseOutcomeConfirmed":$it""" } ?: "") + "}"

    @Test fun onlyAnExplicitFalseIsUnconfirmedAccess() {
        assertTrue(json.decodeFromString<Entitlements>(verifyBody("false")).accessUnconfirmed)
        assertFalse(json.decodeFromString<Entitlements>(verifyBody("true")).accessUnconfirmed)
        // null or absent: an older server, or evidence it could not read. Stays compatible.
        assertFalse(json.decodeFromString<Entitlements>(verifyBody("null")).accessUnconfirmed)
        assertFalse(json.decodeFromString<Entitlements>(verifyBody(null)).accessUnconfirmed)
    }

    @Test fun unconfirmedAccessIsStillGrantedForCachingAndSettling() {
        // Google refunds what nobody acknowledges; the purchase is real and recorded.
        val body = verifyBody("false")
        val decoded = json.decodeFromString<Entitlements>(body)
        assertEquals(VerifyDecision.GRANTED, VerifyDecision.of(VerifyAttribution.isAttributed(body, decoded), decoded))
    }

    @Test fun sharedFromAnotherAccountIsDecodedDefaultsToFalseAndIsNeverCached() {
        val shared = verifyBody("true").replace(""""transferredFromAnotherAccount":false""", """"transferredFromAnotherAccount":false,"sharedFromAnotherAccount":true""")
        assertTrue(json.decodeFromString<Entitlements>(shared).sharedFromAnotherAccount)
        assertFalse("absent on older servers", json.decodeFromString<Entitlements>(verifyBody("true")).sharedFromAnotherAccount)
        assertFalse("about one verify, not standing access", json.decodeFromString<Entitlements>(shared).gatingSnapshot().sharedFromAnotherAccount)
    }

    @Test fun transferredFromAnotherAccountIsDecodedAndDefaultsToFalse() {
        val moved = verifyBody("true").replace(""""transferredFromAnotherAccount":false""", """"transferredFromAnotherAccount":true""")
        assertTrue(json.decodeFromString<Entitlements>(moved).transferredFromAnotherAccount)
        assertFalse(json.decodeFromString<Entitlements>("""{"entitlements":[],"tier":0}""").transferredFromAnotherAccount)
        assertFalse("a notice, never cached", json.decodeFromString<Entitlements>(moved).gatingSnapshot().transferredFromAnotherAccount)
    }

    // ── Restore ─────────────────────────────────────────────────────────────────

    private val pro = OwnedPurchase("pro", "t-pro", PurchaseKind.SUBSCRIPTION, isAcknowledged = true)
    private val coins = OwnedPurchase("coins", "t-coins", PurchaseKind.PRODUCT)
    private val lifetime = OwnedPurchase("lifetime", "t-life", PurchaseKind.PRODUCT)

    private fun granted(
        confirmed: Boolean? = true,
        transferred: Boolean = false,
        pending: Boolean = false,
    ) = Entitlements(
        productType = "auto_renewable",
        purchaseOutcomeConfirmed = confirmed,
        transferredFromAnotherAccount = transferred,
        pending = pending,
        userId = "A",
    )

    @Test fun restoreSendsTheRestoreClaimAndSurfacesTransfers() = runTest {
        val claims = mutableListOf<PurchaseClaim>()
        val outcomes = restoreOwnedPurchases(
            owned = listOf(pro),
            requireSameUser = {},
            verify = { _, claim -> claims += claim; granted(transferred = true) },
            settle = { _, _ -> SettleOutcome.Settled },
        )
        assertEquals(listOf(PurchaseClaim.RESTORE), claims)
        val outcome = outcomes.single()
        assertTrue(outcome.verified && outcome.settled && outcome.transferredFromAnotherAccount)
        assertNull(outcome.error)
        val result = CashSDKClient.RestoreResult(Entitlements.EMPTY, outcomes)
        assertEquals(listOf(outcome), result.transferred)
        assertTrue(result.failures.isEmpty())
    }

    @Test fun restoredButUnconfirmedAccessIsReportedNotFailed() = runTest {
        // Midgame's restore requires `failures.isEmpty()`: unconfirmed access must not fail it.
        var settled = 0
        val outcomes = restoreOwnedPurchases(listOf(pro), {}, { _, _ -> granted(confirmed = false) }, { _, _ -> settled++; SettleOutcome.Settled })
        assertEquals(1, settled)
        val outcome = outcomes.single()
        assertTrue(outcome.verified && outcome.settled)
        assertNull(outcome.error)
        assertEquals(false, outcome.purchaseOutcomeConfirmed)
        val result = CashSDKClient.RestoreResult(Entitlements.EMPTY, outcomes)
        assertTrue(result.failures.isEmpty())
        assertEquals(listOf(outcome), result.unconfirmed)
    }

    @Test fun confirmedAndOlderServerOutcomesAreNotUnconfirmed() = runTest {
        val outcomes = restoreOwnedPurchases(listOf(pro, lifetime), {}, { purchase, _ ->
            granted(confirmed = if (purchase === pro) true else null)
        }, { _, _ -> SettleOutcome.Settled })
        assertEquals(listOf(true, null), outcomes.map { it.purchaseOutcomeConfirmed })
        assertTrue(CashSDKClient.RestoreResult(Entitlements.EMPTY, outcomes).unconfirmed.isEmpty())
    }

    @Test fun pendingIsNeitherVerifiedNorSettled() = runTest {
        var settled = 0
        val outcome = restoreOwnedPurchases(listOf(pro), {}, { _, _ -> granted(pending = true) }, { _, _ -> settled++; SettleOutcome.Settled }).single()
        assertFalse(outcome.verified || outcome.settled)
        assertEquals(0, settled)
    }

    @Test fun anOutageMarksTheRestWithoutWaitingOutEachRetry() = runTest {
        val down = CashSDKError.Server(503, "store_unavailable")
        val verified = mutableListOf<String>()
        val outcomes = restoreOwnedPurchases(
            owned = listOf(coins, pro, lifetime),
            requireSameUser = {},
            verify = { purchase, _ ->
                verified += purchase.productId
                if (purchase === coins) throw CashSDKError.Server(401, "invalid_purchase")
                throw down
            },
            settle = { _, _ -> SettleOutcome.Settled },
        )
        assertEquals("a 401 is about one purchase; after the 503 nothing else is sent", listOf("coins", "pro"), verified)
        assertEquals(listOf("invalid_purchase", "store_unavailable", "store_unavailable"), outcomes.map { (it.error as CashSDKError.Server).code })
    }

    @Test fun aSettleFailureIsReported() = runTest {
        val outcome = restoreOwnedPurchases(listOf(coins), {}, { _, _ -> granted() }, { _, _ -> SettleOutcome.Failed(-1, "disconnected", retryable = true) }).single()
        assertTrue(outcome.verified)
        assertFalse(outcome.settled)
        assertEquals(-1, (outcome.error as CashSDKError.Billing).responseCode)
    }

    @Test fun aDifferentUserEndsTheRestore() = runTest {
        var user = "A"
        try {
            restoreOwnedPurchases(
                owned = listOf(pro, coins),
                requireSameUser = { if (user != "A") throw CashSDKError.NotIdentified },
                verify = { _, _ -> user = "B"; granted() },
                settle = { _, _ -> SettleOutcome.Settled },
            )
            fail("expected NotIdentified")
        } catch (error: CashSDKError) {
            assertEquals(CashSDKError.NotIdentified, error)
        }
    }
}
