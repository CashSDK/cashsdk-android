package com.cashsdk

import com.cashsdk.billing.OwnedCandidate
import com.cashsdk.billing.resolveAlreadyOwned
import com.cashsdk.model.Entitlements
import com.cashsdk.net.accessUnconfirmed
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** The exhaustive shape used by Simarik's existing CashSdkPurchaseCoordinator. */
class ConsumerErrorCompatibilityTest {
    private fun existingConsumer(error: CashSDKError): String = when (error) {
        CashSDKError.PurchaseCancelled -> "cancelled"
        CashSDKError.PurchasePending -> "pending"
        CashSDKError.PurchaseNotAttributed -> "unattributed"
        is CashSDKError.ProductNotFound, CashSDKError.NotConfigured -> "unavailable"
        CashSDKError.NotIdentified -> "identity"
        is CashSDKError.Billing -> "billing"
        is CashSDKError.Server -> if (error.status >= 500) "recover" else "rejected"
        is CashSDKError.Network, is CashSDKError.Decoding -> "recover"
    }

    /** Midgame's `purchaseFailureOf` in CashSdkPurchaseGateway. */
    private fun midgameFailure(e: CashSDKError): String = when (e) {
        is CashSDKError.Network -> "NETWORK"
        is CashSDKError.ProductNotFound -> "PRODUCT_UNAVAILABLE"
        is CashSDKError.Server -> if (e.code == "purchase_belongs_to_another_account") "RECEIPT_IN_USE" else "UNKNOWN"
        CashSDKError.PurchaseNotAttributed -> "ALREADY_OWNED"
        is CashSDKError.Billing -> if (e.responseCode == 7) "ALREADY_OWNED" else "UNKNOWN"
        else -> "UNKNOWN"
    }

    @Test fun existingExhaustiveConsumerStillCompilesAndTimeoutRemainsRecoverable() {
        assertEquals("recover", existingConsumer(CashSDKError.BillingTimeout("purchase")))
        assertEquals("recover", existingConsumer(CashSDKError.ProductTypeUnknown))
        assertEquals("rejected", existingConsumer(CashSDKError.PurchaseBelongsToAnotherAccount))
        assertEquals("billing", existingConsumer(CashSDKError.InvalidPurchaseOptions("missing offer")))
        assertEquals("billing", existingConsumer(CashSDKError.PurchaseInProgress))
    }

    @Test fun alreadyOwnedOnAnotherAccountIsTheErrorHostsReadAsAlreadyOwned() = runTest {
        // Play said ITEM_ALREADY_OWNED and the server keeps the purchase with another app account.
        val cause = CashSDKError.Billing(7, "Item is already owned")
        val thrown = try {
            resolveAlreadyOwned("A", listOf(OwnedCandidate("t1", AppAccountToken.derive("B"), true)), cause, { "A" }) { _ ->
                throw CashSDKError.PurchaseBelongsToAnotherAccount
            }
            fail("expected the ownership error")
            null
        } catch (error: CashSDKError) {
            error
        }
        assertEquals(CashSDKError.PurchaseNotAttributed, thrown)
        assertEquals("ALREADY_OWNED", midgameFailure(thrown!!))
        assertEquals("unattributed", existingConsumer(thrown))
        // Play's own code for the same situation maps the same way.
        assertEquals("ALREADY_OWNED", midgameFailure(cause))
    }

    @Test fun unconfirmedAccessIsAResultTheHostChecksNotAnError() {
        // purchase() returns this snapshot; Midgame turns `purchaseOutcomeConfirmed != true`
        // into Unconfirmed(userId) and runs its recovery. Throwing instead made it UNKNOWN.
        val result = Entitlements(userId = "A", purchaseOutcomeConfirmed = false, productType = "auto_renewable")
        assertTrue(result.accessUnconfirmed)
        assertNotEquals(true, result.purchaseOutcomeConfirmed)
    }

    @Test fun rateLimitsAndOutagesKeepTheirMeaning() {
        assertEquals("pending", existingConsumer(CashSDKError.PurchasePending))
        assertEquals("rejected", existingConsumer(CashSDKError.Server(429, "rate_limited")))
        assertEquals("recover", existingConsumer(CashSDKError.Server(503, "store_unavailable")))
        assertEquals("RECEIPT_IN_USE", midgameFailure(CashSDKError.PurchaseBelongsToAnotherAccount))
    }
}
