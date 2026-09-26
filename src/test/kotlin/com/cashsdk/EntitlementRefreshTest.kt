package com.cashsdk

import com.cashsdk.entitlements.DeadlineRead
import com.cashsdk.entitlements.IntervalGate
import com.cashsdk.entitlements.RefreshGate
import com.cashsdk.entitlements.watchExpiry
import com.cashsdk.model.Entitlement
import com.cashsdk.model.Entitlements
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class EntitlementRefreshTest {

    // ── Foreground throttle ─────────────────────────────────────────────────────

    private var clock = 0L
    private val gate = RefreshGate(minIntervalMs = 300_000, clock = { clock })

    @Test fun firstForegroundRefreshesThenWaitsFiveMinutes() {
        assertTrue(gate.begin(force = false))
        gate.end(succeeded = true)
        clock += 299_999
        assertFalse("switching apps must not cost a request each time", gate.begin(force = false))
        clock += 1
        assertTrue(gate.begin(force = false))
    }

    @Test fun aFailedReadDoesNotCountAsFresh() {
        assertTrue(gate.begin(force = false))
        gate.end(succeeded = false)
        clock += 1_000
        assertTrue("offline a minute ago says nothing about now", gate.begin(force = false))
    }

    @Test fun oneReadAtATimeUnlessForced() {
        assertTrue(gate.begin(force = true)) // the launch refresh
        assertFalse("the first foreground must not duplicate the launch read", gate.begin(force = false))
        assertTrue("identify and a passed deadline always read", gate.begin(force = true))
        gate.end(succeeded = false)
        gate.end(succeeded = true)
        assertFalse(gate.begin(force = false))
    }

    @Test fun readsFromElsewhereCountAndANewUserStartsStale() {
        gate.noteFresh() // e.g. a verify just returned this user's snapshot
        assertFalse(gate.begin(force = false))
        gate.reset() // identify(anotherUser)
        assertTrue(gate.begin(force = false))
    }

    // ── Deadline timer ──────────────────────────────────────────────────────────

    private val base = Instant.parse("2026-09-23T12:00:00Z").toEpochMilli()
    private fun until(millisAfterBase: Long) = Entitlements(
        listOf(Entitlement("pro", "Pro", 1, "subscription", Instant.ofEpochMilli(base + millisAfterBase).toString())),
    )

    @Test fun firesAtTheEarliestDeadlineAndAgainForANewerSnapshot() = runTest {
        val snapshots = MutableStateFlow(until(60_000))
        val fired = mutableListOf<Long>()
        val job = launch {
            watchExpiry(snapshots, clock = { base + testScheduler.currentTime }, leadMs = 0, answerWaitMs = 0, beforeDeadline = { _ -> null }) {
                fired += testScheduler.currentTime
            }
        }

        advanceTimeBy(59_999)
        runCurrent()
        assertTrue(fired.isEmpty())
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf(60_000L), fired)

        // The refresh brought a renewed deadline: the wait restarts from it.
        snapshots.value = until(3_600_000)
        runCurrent()
        advanceTimeBy(3_600_000 - 60_000 - 1)
        runCurrent()
        assertEquals(1, fired.size)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf(60_000L, 3_600_000L), fired)
        job.cancel()
    }

    @Test fun perpetualAccessSchedulesNothing() = runTest {
        val snapshots = MutableStateFlow(Entitlements(listOf(Entitlement("lifetime", expiresAt = null))))
        var fired = 0
        val job = launch {
            watchExpiry(snapshots, clock = { base + testScheduler.currentTime }, leadMs = 0, answerWaitMs = 0, beforeDeadline = { _ -> null }) { fired++ }
        }
        advanceTimeBy(365L * 24 * 3_600_000)
        runCurrent()
        assertEquals(0, fired)
        job.cancel()
    }

    @Test fun theServerIsAskedLeadMsEarlyAndItsAnswerReachesTheDeadline() = runTest {
        val snapshots = MutableStateFlow(until(60_000))
        val askedAt = mutableListOf<Long>()
        val answers = mutableListOf<Boolean>()
        val job = launch {
            watchExpiry(
                snapshots,
                clock = { base + testScheduler.currentTime },
                leadMs = 10_000,
                answerWaitMs = 5_000,
                beforeDeadline = { _ -> askedAt += testScheduler.currentTime; DeadlineRead(CompletableDeferred(true), base + testScheduler.currentTime) },
            ) { answers += it }
        }
        advanceTimeBy(50_001)
        runCurrent()
        assertEquals(listOf(50_000L), askedAt)
        assertTrue("nothing acted on before the deadline", answers.isEmpty())
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(listOf(true), answers)
        job.cancel()
    }

    @Test fun aReadStillInFlightGetsAShortGraceThenCountsAsUnanswered() = runTest {
        val snapshots = MutableStateFlow(until(60_000))
        val never = CompletableDeferred<Boolean>()
        val decided = mutableListOf<Pair<Long, Boolean>>()
        val job = launch {
            watchExpiry(snapshots, clock = { base + testScheduler.currentTime }, leadMs = 10_000, answerWaitMs = 5_000, beforeDeadline = { _ -> DeadlineRead(never, base + 50_000) }) {
                decided += testScheduler.currentTime to it
            }
        }
        advanceTimeBy(64_999)
        runCurrent()
        assertTrue(decided.isEmpty())
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf(65_000L to false), decided)
        job.cancel()
    }

    @Test fun aReadThatStartsAfterTheDeadlineGetsItsOwnAnswerWindow() = runTest {
        // The one-a-minute gate made this read start 3 s after the deadline; it still gets 5 s.
        val snapshots = MutableStateFlow(until(60_000))
        val answer = CompletableDeferred<Boolean>()
        val decided = mutableListOf<Pair<Long, Boolean>>()
        val job = launch {
            watchExpiry(snapshots, clock = { base + testScheduler.currentTime }, leadMs = 10_000, answerWaitMs = 5_000, beforeDeadline = { _ -> DeadlineRead(answer, base + 63_000) }) {
                decided += testScheduler.currentTime to it
            }
        }
        advanceTimeBy(66_000)
        runCurrent()
        assertTrue("65 s would have cut it off 2 s after it started", decided.isEmpty())
        answer.complete(true)
        runCurrent()
        assertEquals(listOf(66_000L to true), decided)
        job.cancel()
    }

    @Test fun aFinishedReadIsNeverReportedAsUnanswered() = runTest {
        val snapshots = MutableStateFlow(until(60_000))
        val decided = mutableListOf<Boolean>()
        val job = launch {
            // Started long ago and already answered: no timeout may turn it into "unanswered".
            watchExpiry(snapshots, clock = { base + testScheduler.currentTime }, leadMs = 10_000, answerWaitMs = 0, beforeDeadline = { _ -> DeadlineRead(CompletableDeferred(true), base) }) {
                decided += it
            }
        }
        advanceTimeBy(60_001)
        runCurrent()
        assertEquals(listOf(true), decided)
        job.cancel()
    }

    // ── Deadline read gate ───────────────────────────────────────────────────────

    @Test fun deadlineReadsAreSpacedAMinuteApartWhateverTheOutcome() {
        var now = 0L
        val deadlineGate = IntervalGate(60_000, clock = { now })
        assertTrue(deadlineGate.tryPass())
        now += 59_999
        assertFalse(deadlineGate.tryPass())
        assertEquals(1L, deadlineGate.msUntilOpen())
        now += 1
        assertEquals(0L, deadlineGate.msUntilOpen())
        assertTrue(deadlineGate.tryPass())
        deadlineGate.reset()
        assertTrue(deadlineGate.tryPass())
    }
}
