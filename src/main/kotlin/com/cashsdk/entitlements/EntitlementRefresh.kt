package com.cashsdk.entitlements

import com.cashsdk.model.Entitlements
import com.cashsdk.model.nextExpiryMillis
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withTimeoutOrNull

/** How often coming back to the foreground may re-read entitlements. */
internal const val FOREGROUND_REFRESH_INTERVAL_MS = 5 * 60_000L

/**
 * Decides when the SDK re-reads entitlements on its own.
 *
 * Forced refreshes (launch, `identify`) always run. A foreground refresh runs only when none is
 * in flight and the last successful read is at least [minIntervalMs] old, so switching between
 * apps does not cost a request each time. A failed read does not count as fresh, and a passed
 * deadline makes the snapshot stale ([reset]).
 *
 * [clock] should be monotonic (`SystemClock.elapsedRealtime` in production), so a user changing
 * the device time cannot stall or force refreshes.
 */
internal class RefreshGate(
    private val minIntervalMs: Long = FOREGROUND_REFRESH_INTERVAL_MS,
    private val clock: () -> Long,
) {
    private var inFlight = 0
    private var lastSuccessAt: Long? = null

    /** Whether to start a refresh now. Every `true` must be matched by one [end]. */
    @Synchronized
    fun begin(force: Boolean): Boolean {
        if (!force) {
            if (inFlight > 0) return false
            val last = lastSuccessAt
            val now = clock()
            if (last != null && now >= last && now - last < minIntervalMs) return false
        }
        inFlight++
        return true
    }

    @Synchronized
    fun end(succeeded: Boolean) {
        if (inFlight > 0) inFlight--
        if (succeeded) lastSuccessAt = clock()
    }

    /** A fresh snapshot arrived some other way (a host-called refresh, a verify). */
    @Synchronized
    fun noteFresh() {
        lastSuccessAt = clock()
    }

    /** Another user signed in or out, or access just ended: whatever was fresh no longer holds. */
    @Synchronized
    fun reset() {
        lastSuccessAt = null
    }
}

/**
 * Lets one action through per [intervalMs], counting every attempt, successful or not. Keeps the
 * reads made for access deadlines to about one a minute, however close together the server's
 * deadlines are. [clock] is monotonic.
 */
internal class IntervalGate(private val intervalMs: Long, private val clock: () -> Long) {
    private var lastPassAt: Long? = null

    /** True, and counted, when the last pass is at least [intervalMs] ago. */
    @Synchronized
    fun tryPass(): Boolean {
        val now = clock()
        val last = lastPassAt
        if (last != null && now >= last && now - last < intervalMs) return false
        lastPassAt = now
        return true
    }

    /** How long until [tryPass] can succeed; 0 when it can now. */
    @Synchronized
    fun msUntilOpen(): Long {
        val last = lastPassAt ?: return 0
        return (last + intervalMs - clock()).coerceIn(0, intervalMs)
    }

    @Synchronized
    fun reset() {
        lastPassAt = null
    }
}

/**
 * A read made for an access deadline: whether the server answered (false when the read failed),
 * and when the read starts, in the watcher's clock. It starts later than asked when the
 * one-read-a-minute gate is still closed.
 */
internal class DeadlineRead(val answer: Deferred<Boolean>, val startsAt: Long)

/**
 * Watch the earliest future access deadline in the latest snapshot and ask the server before
 * acting on it.
 *
 * [leadMs] before the deadline, [beforeDeadline] may start a read of the server's snapshot. A
 * renewal in that answer replaces the snapshot, which restarts the watch on the new deadline,
 * so the old one is never acted on and the access is never shown as ended. If the deadline
 * arrives with the snapshot unchanged, the read gets [answerWaitMs] past the deadline, or past
 * its own start if it started later, and then [atDeadline] runs with whether the server
 * answered: it stops reporting the access (fail closed) and, when nothing answered, arranges
 * retries. A newer snapshot restarts the wait at any point. Runs until cancelled.
 *
 * The wait can end late (the device slept, the process was frozen); readers never depend on it,
 * because the store judges expiry again on every read.
 */
internal suspend fun watchExpiry(
    snapshots: Flow<Entitlements>,
    clock: () -> Long,
    leadMs: Long,
    answerWaitMs: Long,
    beforeDeadline: (deadline: Long) -> DeadlineRead?,
    atDeadline: (answered: Boolean) -> Unit,
) {
    snapshots.collectLatest { snapshot ->
        while (true) {
            val now = clock()
            val deadline = snapshot.nextExpiryMillis(now) ?: break
            if (deadline - leadMs > now) delay(deadline - leadMs - now)
            val read = beforeDeadline(deadline)
            // A wall clock that runs behind the timer can wake this a moment early; wait the rest.
            while (clock() < deadline) delay(deadline - clock())
            val answered = read?.let {
                val until = maxOf(deadline, it.startsAt) + answerWaitMs
                // At least 1 ms: a zero timeout would report even a finished read as unanswered.
                withTimeoutOrNull((until - clock()).coerceAtLeast(1)) { it.answer.await() }
            } ?: false
            atDeadline(answered)
        }
    }
}
