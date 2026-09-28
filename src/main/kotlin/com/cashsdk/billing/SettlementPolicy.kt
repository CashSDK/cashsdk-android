package com.cashsdk.billing

import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.cashsdk.CashSDKError
import kotlinx.coroutines.delay

/** Only the verified catalog type can authorize consumption or acknowledgement. */
internal fun requiresConsumption(productType: String?): Boolean = when (productType) {
    "consumable" -> true
    "non_consumable", "auto_renewable", "non_renewing", "subscription" -> false
    else -> throw CashSDKError.ProductTypeUnknown
}

/** What Play answered to one consume or acknowledge call. */
internal class SettleAttempt(val responseCode: Int, val debugMessage: String?)

internal const val SETTLE_ATTEMPTS = 3
internal const val SETTLE_RETRY_BASE_MS = 500L

/**
 * Consume or acknowledge, retrying transient failures with backoff.
 *
 * [attempt] returns null when Play did not answer in time. That is transient too: it used to
 * escape as a timeout error from a purchase the server had already verified and credited, so the
 * user was told the purchase failed while the entitlement was already theirs. Now the purchase
 * stays tracked and the next sync settles it.
 */
internal suspend fun settleWithRetries(
    attempts: Int = SETTLE_ATTEMPTS,
    baseDelayMs: Long = SETTLE_RETRY_BASE_MS,
    attempt: suspend () -> SettleAttempt?,
): SettleOutcome {
    var delayMs = baseDelayMs
    var last: SettleOutcome = SettleOutcome.Settled
    for (index in 0 until attempts) {
        last = classifySettle(attempt())
        if (last.settled) return last
        val failed = last as SettleOutcome.Failed
        if (!failed.retryable || index == attempts - 1) return last
        delay(delayMs)
        delayMs *= 2
    }
    return last
}

/** Play's old SERVICE_TIMEOUT code (deprecated in the client, still what a timeout means). */
private const val PLAY_TIMED_OUT = -3

internal fun classifySettle(result: SettleAttempt?): SettleOutcome = when (result?.responseCode) {
    null -> SettleOutcome.Failed(PLAY_TIMED_OUT, "Google Play did not answer in time", retryable = true)
    BillingResponseCode.OK -> SettleOutcome.Settled
    // Already gone from Play's point of view: consumed by an earlier attempt whose response
    // we never saw, or refunded. Either way there is nothing left to settle.
    BillingResponseCode.ITEM_NOT_OWNED -> SettleOutcome.Settled
    BillingResponseCode.SERVICE_DISCONNECTED,
    BillingResponseCode.SERVICE_UNAVAILABLE,
    BillingResponseCode.NETWORK_ERROR,
    BillingResponseCode.ERROR,
    -> SettleOutcome.Failed(result.responseCode, result.debugMessage, retryable = true)
    // DEVELOPER_ERROR, BILLING_UNAVAILABLE, FEATURE_NOT_SUPPORTED, ITEM_UNAVAILABLE: retrying
    // cannot change the answer.
    else -> SettleOutcome.Failed(result.responseCode, result.debugMessage, retryable = false)
}
