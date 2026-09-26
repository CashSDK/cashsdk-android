package com.cashsdk.net

import kotlin.random.Random

/**
 * When a failed `POST /v1/purchases:verify` is worth sending again inside the same call.
 *
 * A verify used to be sent once. A `503 store_unavailable` (Google down, the server says
 * `Retry-After: 30`), a rate limit or a deploy blip turned a paid purchase into an error the
 * user saw, even though a few seconds later the same request would have succeeded. Verify is
 * idempotent server-side, so sending it again is safe.
 *
 * Bounded on purpose: the user may be looking at a spinner, and a purchase that still fails is
 * not lost. It stays unsettled with Google and the next sync (launch, `identify`,
 * `syncPurchases`) verifies it. So:
 *  - only `408`, `429` and `5xx` are retried; any other status is the server's answer;
 *  - at most [maxAttempts] requests in total;
 *  - `Retry-After` is honoured, but a request to wait longer than [maxRetryAfterMs], or waits
 *    adding up to more than [maxTotalWaitMs], ends the retries instead;
 *  - without `Retry-After` the wait is exponential ([baseDelayMs], doubling);
 *  - either way a random extra of up to a fifth of the wait (at least [minJitterMs]) is added,
 *    kept inside the budget, so devices that were all told `Retry-After: 30` do not all come
 *    back in the same second.
 *
 * Transport failures (offline, timeout) are not retried here; they go to the next sync as well.
 */
internal class VerifyRetryPolicy(
    val maxAttempts: Int = 3,
    private val baseDelayMs: Long = 1_000,
    private val maxRetryAfterMs: Long = 30_000,
    private val maxTotalWaitMs: Long = 45_000,
    private val minJitterMs: Long = 1_000,
    private val random: () -> Double = { Random.nextDouble() },
) {
    fun isRetryable(status: Int): Boolean = status == 408 || status == 429 || status in 500..599

    /**
     * How long to wait before the next attempt after [attemptsMade] requests, the last of which
     * answered [status]; null means stop and report the failure. [waitedMs] is the time already
     * spent waiting in this call.
     */
    fun delayBeforeNextAttempt(attemptsMade: Int, status: Int, retryAfterMs: Long?, waitedMs: Long): Long? {
        if (!isRetryable(status) || attemptsMade >= maxAttempts) return null
        if (retryAfterMs != null && retryAfterMs > maxRetryAfterMs) return null
        val wait = retryAfterMs ?: (baseDelayMs shl (attemptsMade - 1).coerceIn(0, 16))
        val room = maxTotalWaitMs - waitedMs - wait
        if (room < 0) return null
        val spread = maxOf(minJitterMs, wait / 5).coerceAtMost(room)
        return wait + (random() * spread).toLong()
    }
}
