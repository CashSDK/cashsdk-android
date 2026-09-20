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
