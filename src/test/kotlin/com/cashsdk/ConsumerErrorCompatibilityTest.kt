package com.cashsdk

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

    @Test fun existingExhaustiveConsumerStillCompilesAndTimeoutRemainsRecoverable() {
        assertEquals("recover", existingConsumer(CashSDKError.BillingTimeout("purchase")))
        assertEquals("recover", existingConsumer(CashSDKError.ProductTypeUnknown))
        assertEquals("rejected", existingConsumer(CashSDKError.PurchaseBelongsToAnotherAccount))
        assertEquals("billing", existingConsumer(CashSDKError.PurchaseInProgress))
        assertEquals("billing", existingConsumer(CashSDKError.InvalidPurchaseOptions("missing offer")))
    }
}
