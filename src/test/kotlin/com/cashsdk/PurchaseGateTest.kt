package com.cashsdk

import com.cashsdk.billing.PurchaseGate
import com.cashsdk.billing.billingDeadline
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class PurchaseGateTest {
    @Test fun secondTapCannotQueueASecondStoreSheet() = runTest {
        val gate = PurchaseGate()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val first = async { gate.run { started.complete(Unit); release.await() } }
        started.await()
        try { gate.run { fail("a second purchase must not start") }; fail("expected PurchaseInProgress") }
        catch (error: CashSDKError.Billing) { assertEquals(CashSDKError.PurchaseInProgress, error) }
        release.complete(Unit)
        first.await()
        assertEquals(42, gate.run { 42 })
    }

    @Test fun deadlineReleasesGateWithoutPretendingThePurchaseWasCancelled() = runTest {
        val gate = PurchaseGate()
        try { gate.run { billingDeadline("purchase", 100) { awaitCancellation() } }; fail("expected timeout") }
        catch (error: CashSDKError.Network) {
            assertTrue(error.underlying is java.util.concurrent.TimeoutException)
            assertTrue(error.underlying.message.orEmpty().contains("purchase"))
        }
        assertEquals("usable", gate.run { "usable" })
    }

    @Test fun callerCancellationIsNotConvertedToABillingFailure() = runTest {
        try {
            billingDeadline("lookup", 100) { throw CancellationException("caller cancelled") }
            fail("expected cancellation")
        } catch (error: CancellationException) { assertEquals("caller cancelled", error.message) }
    }
}
