package com.cashsdk

import kotlin.math.abs

/**
 * Wall-clock time as the CashSDK API sees it.
 *
 * Access deadlines (`Entitlement.expiresAt`) are server times. Judging them with a device clock
 * that is wrong by hours would take access away from a paying user the moment it is cached, or
 * keep it after it ended. Every API response carries a `Date` header, so the SDK keeps the
 * offset between that header and the device clock and judges expiry with the corrected time.
 *
 * One header is never enough. A new offset is adopted only when two responses agree on it
 * within [AGREEMENT_MS], so a single bad `Date` (a proxy, a captive portal) cannot end every
 * cached entitlement. Agreement is the protection, so there is no cap on the size: a device
 * clock a week or a year off is exactly the one that most needs correcting (a weekly
 * subscription would otherwise vanish, or refunded access linger). Token checks use
 * [lenientNowMillis], so a wrong offset can never refuse a token the server would accept.
 *
 * The offset adopted by a previous run is restored with the cached snapshot, so an offline
 * launch with a wrong clock still reads the cache correctly. An offset this run agreed on wins.
 */
internal object ServerClock {
    /** Offsets below this are the header's one-second resolution plus network latency. */
    private const val NOISE_MS = 5_000L

    /** Two observations within this of each other describe the same clock. */
    private const val AGREEMENT_MS = 60_000L

    @Volatile
    var offsetMs: Long = 0L
        private set

    /** The last observation that differed from [offsetMs], waiting for a second one to agree. */
    private var candidate: Long? = null

    /** Whether this run has agreed on an offset with the server (then [restore] no longer applies). */
    private var agreed = false

    fun nowMillis(): Long = System.currentTimeMillis() + offsetMs

    /**
     * The earlier of the device clock and the corrected one. For token checks, which must never
     * refuse what the server would accept: whichever clock is wrong, this one is not ahead of both.
     */
    fun lenientNowMillis(): Long = minOf(System.currentTimeMillis(), nowMillis())

    /** Record the server's `Date` for a response the device received at [deviceMillis]. */
    @Synchronized
    fun observe(serverDateMillis: Long, deviceMillis: Long) {
        val seen = serverDateMillis - deviceMillis
        val proposal = if (abs(seen) < NOISE_MS) 0L else seen
        if (abs(proposal - offsetMs) < NOISE_MS) {
            // Confirms what is already in use.
            candidate = null
            agreed = true
            return
        }
        val previous = candidate
        if (previous != null && abs(proposal - previous) <= AGREEMENT_MS) {
            offsetMs = proposal
            candidate = null
            agreed = true
        } else {
            candidate = proposal
        }
    }

    /** Adopt the offset a previous run persisted, unless this run has already agreed on one. */
    @Synchronized
    fun restore(persistedOffsetMs: Long) {
        if (!agreed) offsetMs = persistedOffsetMs
    }

    /** Forget everything learned. Tests only. */
    @Synchronized
    internal fun reset() {
        offsetMs = 0L
        candidate = null
        agreed = false
    }
}
