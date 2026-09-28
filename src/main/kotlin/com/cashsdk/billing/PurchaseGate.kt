package com.cashsdk.billing

import com.cashsdk.CashSDKError
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull

/** At most one store sheet. A second tap fails immediately, never queues a second charge. */
internal class PurchaseGate {
    private val lock = Mutex()
    suspend fun <T> run(block: suspend () -> T): T {
        if (!lock.tryLock()) throw CashSDKError.PurchaseInProgress
        return try { block() } finally { lock.unlock() }
    }
}

/** Only this operation's own timeout is mapped; caller cancellation still propagates. */
internal suspend fun <T> billingDeadline(operation: String, timeoutMs: Long, block: suspend () -> T): T {
    return withTimeoutOrNull(timeoutMs) { Result.success(block()) }?.getOrThrow()
        ?: throw CashSDKError.BillingTimeout(operation)
}

/**
 * Wait for Play's answer to an open payment sheet. Running out of time is reported as
 * [CashSDKError.PurchasePending], not as a failure: Play can still complete the payment after we
 * stop waiting, and that late purchase is verified and settled by the next sync. A timeout error
 * here used to tell the user a purchase had failed that then went through.
 */
internal suspend fun <T : Any> awaitPurchaseUpdate(timeoutMs: Long, block: suspend () -> T): T =
    withTimeoutOrNull(timeoutMs) { block() } ?: throw CashSDKError.PurchasePending
