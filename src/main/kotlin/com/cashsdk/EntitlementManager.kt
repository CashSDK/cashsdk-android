package com.cashsdk

import com.cashsdk.billing.OwnedPurchase
import com.cashsdk.billing.SettleOutcome
import com.cashsdk.entitlements.DeadlineRead
import com.cashsdk.entitlements.EntitlementStore
import com.cashsdk.entitlements.IntervalGate
import com.cashsdk.entitlements.RefreshGate
import com.cashsdk.entitlements.watchExpiry
import com.cashsdk.model.Entitlements
import com.cashsdk.model.PurchaseClaim
import com.cashsdk.model.PurchaseKind
import com.cashsdk.model.VerifyRequest
import com.cashsdk.model.withoutExpired
import com.cashsdk.net.ApiClient
import com.cashsdk.net.VerifyDecision
import com.cashsdk.net.accessUnconfirmed
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The money path behind [CashSDKClient], with no Android in it: verifying purchases, reading
 * entitlements, restoring, and keeping cached access honest around its deadlines. The client
 * wires it to SharedPreferences, the Keystore, the activity lifecycle and Play Billing; the rules
 * live here so JVM tests can run them against a scripted server.
 */
internal class EntitlementManager(
    private val api: ApiClient,
    private val store: EntitlementStore,
    /** Where background reads, deadline reads and their retries run. */
    private val scope: CoroutineScope,
    /** Monotonic clock for throttling, so a changed device time cannot stall or force reads. */
    private val monotonicClock: () -> Long,
    /** A host-pinned environment is authoritative, never overridden by what the server says. */
    private val pinnedEnvironment: String? = null,
    /** Whether the user can see the app (see [ForegroundState]). */
    private val isForeground: () -> Boolean = { true },
    /** Server-corrected wall clock, for deadlines (see [ServerClock]). */
    private val wallClock: () -> Long = ServerClock::nowMillis,
    /** Persist an environment learned from a verify, so the next launch starts in it. */
    private val rememberEnvironment: (String) -> Unit = {},
    /** Called after any good round trip: connectivity is back, so telemetry may drain now. */
    private val onNetworkSuccess: () -> Unit = {},
    private val emit: (event: String, product: String?, properties: Map<String, Any>?) -> Unit = { _, _, _ -> },
) {
    /** Foreground reads: at most one every five minutes. */
    private val refreshGate = RefreshGate(clock = monotonicClock)

    /** Reads made because a deadline is due, and their retries: about one a minute at most. */
    private val deadlineGate = IntervalGate(DEADLINE_READ_INTERVAL_MS, monotonicClock)

    /** [monotonicClock] at the last snapshot the server confirmed (a read or a granted verify). */
    @Volatile
    private var lastFreshAt: Long? = null

    private val retryLock = Any()
    private var deadlineRetry: Job? = null

    // ── Verify ──────────────────────────────────────────────────────────────────

    /**
     * See [CashSDKClient.verifyPurchase]. Play purchases reach this through
     * [com.cashsdk.billing.PurchaseCompleter], which decides their claim.
     */
    suspend fun verifyPurchase(productId: String, purchaseToken: String, kind: PurchaseKind, claim: PurchaseClaim): Entitlements {
        val owner = api.userId
        val outcome = api.verifyPurchase(VerifyRequest(productId, purchaseToken, kind), claim)
        // Another user signed in while the request was out: this answer describes the previous
        // one. The same user with a refreshed token is fine; the answer is still theirs.
        if (api.userId != owner) throw CashSDKError.NotIdentified
        onNetworkSuccess() // connectivity is provably back: drain any telemetry backlog
        // Adopt the environment the server resolved this purchase into, BEFORE any of the
        // early returns below: a deferred purchase is still a Sandbox-or-Production fact, and
        // the follow-up read that eventually credits it has to be scoped to the same one.
        // Google Play tells the client nothing about this; the server is the only source.
        adoptEnvironment(outcome.entitlements.environment)
        // The order of these three cases is load-bearing, so it lives in a pure, unit-tested
        // function rather than as inline `if`s that can be reshuffled by accident. See
        // [VerifyDecision.of] for why PENDING must be decided before UNATTRIBUTED.
        when (VerifyDecision.of(outcome.attributed, outcome.entitlements)) {
            // Google hasn't settled the payment, so the server granted nothing and the snapshot
            // beside `pending` is EMPTY. Caching it would drop a paying user to the free tier
            // until the next successful read, for a slow deferred payment days.
            VerifyDecision.PENDING -> {
                emit("purchase_pending", productId, null)
                return outcome.entitlements
            }
            // 200 + a snapshot credited to NOBODY. Persisting it would wipe this user's real
            // entitlements, and settling the purchase would finalize a sale nobody owns.
            VerifyDecision.UNATTRIBUTED -> {
                emit("purchase_unattributed", productId, null)
                throw CashSDKError.PurchaseNotAttributed
            }
            VerifyDecision.GRANTED -> Unit
            VerifyDecision.OWNED_ELSEWHERE -> throw CashSDKError.PurchaseBelongsToAnotherAccount
        }
        // The purchase IS recorded server-side (so this still counts as success), but the snapshot
        // describes whoever was signed in when the request went out: don't cache it under a user
        // who signed in meanwhile.
        store.update(owner, outcome.entitlements, outcome.etag)
        noteFresh()
        if (outcome.entitlements.transferredFromAnotherAccount) emit("purchase_transferred", productId, null)
        if (outcome.entitlements.accessUnconfirmed) emit("purchase_access_unconfirmed", productId, null)
        emit("purchase_verified", productId, null)
        return outcome.entitlements.withoutExpired(wallClock())
    }

    /**
     * Adopt the store environment the server reported on a verify response.
     *
     * An explicit `configure(environment = …)` always wins: a host that pinned the environment
     * has said something we must not second-guess. Otherwise this is the ONLY way the Android
     * SDK can learn it: `Purchase` carries no equivalent of StoreKit's
     * `Transaction.environment`, so a license-tester purchase is indistinguishable from a real
     * one on device. Re-keying the cache drops any snapshot (and ETag) belonging to the other
     * environment, which would otherwise 304 the wrong entitlements into place.
     */
    private fun adoptEnvironment(environment: String?) {
        if (environment == null || pinnedEnvironment != null) return
        if (api.environment == environment) return
        api.environment = environment
        store.setEnvironment(environment, api.userId)
        rememberEnvironment(environment)
    }

    // ── Restore ────────────────────────────────────────────────────────────────

    /** See [CashSDKClient.restoreDetailed]. */
    suspend fun restoreDetailed(
        queryOwned: suspend () -> List<OwnedPurchase>,
        settle: suspend (OwnedPurchase, Entitlements) -> SettleOutcome,
    ): CashSDKClient.RestoreResult {
        val identity = api.identitySnapshot()
        api.requireIdentity(identity)
        val owner = identity.userId
        // Same user throughout; a refreshed token for that user does not abort the restore.
        val outcomes = restoreOwnedPurchases(
            owned = queryOwned(),
            requireSameUser = { api.requireSameUser(owner) },
            verify = { owned, claim -> verifyPurchase(owned.productId, owned.purchaseToken, owned.kind, claim) },
            settle = settle,
        )
        // Restore must not report success from an old cache after an offline/auth failure.
        val snapshot = refresh()
        api.requireSameUser(owner)
        emit(
            "restore",
            null,
            mapOf(
                "restored" to outcomes.size,
                "failed" to outcomes.count { it.error != null },
                "transferred" to outcomes.count { it.transferredFromAnotherAccount },
                "unconfirmed" to outcomes.count { it.purchaseOutcomeConfirmed == false },
            ),
        )
        return CashSDKClient.RestoreResult(snapshot, outcomes)
    }

    // ── Reads ───────────────────────────────────────────────────────────────────

    /**
     * Await a server-confirmed snapshot for the current identity (the public
     * `refreshEntitlements()`). Throws if identity changes (including A → B → A) while the
     * request is in flight. Entitlements past their `expiresAt` are left out of the result.
     * With [revalidate] false no ETag is sent, so the answer is a full body.
     */
    suspend fun refresh(revalidate: Boolean = true): Entitlements {
        val identity = api.identitySnapshot()
        api.requireIdentity(identity)
        val owner = identity.userId
        val result = api.getEntitlements(revalidate)
        onNetworkSuccess()
        // Identity changed while the read was in flight: this body describes the PREVIOUS user.
        // Caching it under the new id would hand user B user A's entitlements.
        api.requireIdentity(identity)
        noteFresh()
        return when (result) {
            is ApiClient.EntitlementsResult.Modified -> {
                store.update(owner, result.entitlements, result.etag)
                result.entitlements.withoutExpired(wallClock())
            }
            // ETag hit: the cache is provably fresh, so don't rewrite it (a failed rewrite would
            // drop the very ETag that just proved it good). The response may have corrected the
            // clock, so judge the held snapshot again.
            ApiClient.EntitlementsResult.NotModified -> {
                store.republish()
                store.current
            }
        }
    }

    /**
     * Re-read entitlements without making the caller wait. [force] is for launch and `identify`;
     * without it the read is skipped when one is running or the last one is under five minutes
     * old (see [RefreshGate]).
     */
    fun refreshInBackground(force: Boolean) {
        if (api.userId == null) return
        if (!refreshGate.begin(force)) return
        scope.launch {
            var succeeded = false
            try {
                refresh()
                succeeded = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // Offline or refused: the cache stays, and still ends at each entitlement's deadline.
            } finally {
                refreshGate.end(succeeded)
            }
        }
    }

    private fun noteFresh() {
        refreshGate.noteFresh()
        lastFreshAt = monotonicClock()
    }

    // ── Lifecycle ───────────────────────────────────────────────────────────────

    /**
     * `identify(userId)`: re-key the cache to this user and read their snapshot.
     *
     * [sameUser] is the common case, because hosts call `identify` on every launch and after
     * every token refresh. There the cache in memory already belongs to this user, so re-reading
     * it from disk only costs a blocking read on the caller's thread, and throwing away the
     * freshness window only costs a request. The read still happens, just through the same
     * five-minute gate as a foreground refresh.
     */
    fun onIdentityChanged(userId: String, sameUser: Boolean = false) {
        if (!sameUser) {
            // The persisted record carries its owner AND the ETag that describes it, so a cache
            // belonging to anyone else yields EMPTY *and* no ETag: there is no way to end up
            // revalidating someone else's snapshot into a 304.
            store.hydrate(userId)
            forgetReads()
        }
        refreshInBackground(force = !sameUser)
    }

    /** `logout()`: drop the snapshot and the ETag that describes it together. */
    fun onLogout() {
        forgetReads()
        store.clear()
    }

    /** The app came to the foreground: judge the snapshot now, then read it again (throttled). */
    fun onForeground() {
        store.republish()
        refreshInBackground(force = false)
    }

    private fun forgetReads() {
        refreshGate.reset()
        deadlineGate.reset()
        lastFreshAt = null
        synchronized(retryLock) {
            deadlineRetry?.cancel()
            deadlineRetry = null
        }
    }

    // ── Deadlines ───────────────────────────────────────────────────────────────

    /**
     * Keep cached access honest around its deadlines. Runs until cancelled.
     *
     * Shortly before the earliest deadline, in the foreground, the server is asked first (no
     * ETag, with the user's current token). A renewal in the answer replaces the snapshot, so a
     * paying subscriber is never shown as lapsed at a renewal. Whatever the answer, it is applied.
     * Only when the read fails (or cannot be made in time) does the deadline end the access
     * locally (fail closed), with bounded retries while the app stays in the foreground. In the
     * background nothing is sent: the access ends at its deadline and the next foreground reads.
     */
    suspend fun watchDeadlines() {
        watchExpiry(
            snapshots = store.rawSnapshot,
            clock = wallClock,
            leadMs = DEADLINE_READ_LEAD_MS,
            answerWaitMs = DEADLINE_ANSWER_WAIT_MS,
            beforeDeadline = ::startDeadlineRead,
            atDeadline = ::onDeadlinePassed,
        )
    }

    /**
     * The read made for [deadline], or null when none can be made in time. At most one deadline
     * read a minute: when the gate is still closed but opens before the deadline plus the answer
     * wait, the read waits for it (with deadlines a minute apart, it lands at the deadline).
     * Only a gate that opens later than that ends the access at the deadline.
     */
    private fun startDeadlineRead(deadline: Long): DeadlineRead? {
        if (api.userId == null || !isForeground()) return null
        val wait = deadlineGate.msUntilOpen()
        val startsAt = wallClock() + wait
        if (startsAt >= deadline + DEADLINE_ANSWER_WAIT_MS) return null
        val scheduledAt = monotonicClock()
        // Its own job in [scope]: a renewal restarting the watch must not cancel the read half way.
        val answer = scope.async {
            if (wait > 0) delay(wait)
            // Something else (identify, a foreground read, a verify) brought a fresh snapshot
            // while this waited: that is the server's answer.
            if ((lastFreshAt ?: Long.MIN_VALUE) > scheduledAt) return@async true
            if (!isForeground() || !deadlineGate.tryPass()) return@async false
            readForDeadline()
        }
        return DeadlineRead(answer, startsAt)
    }

    /** One deadline read. True when the server answered and the answer is applied. */
    private suspend fun readForDeadline(): Boolean = try {
        // An expired token would only earn a 401. The host refreshing it calls identify(), which
        // reads by itself.
        if (api.hasUsableToken()) {
            refresh(revalidate = false)
            true
        } else {
            false
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }

    /** The deadline passed and nothing moved it: stop reporting that access. */
    private fun onDeadlinePassed(answered: Boolean) {
        store.republish()
        // Whatever was fresh described access that has now ended: the next foreground reads.
        refreshGate.reset()
        if (!answered && api.userId != null && isForeground()) retryAfterDeadline()
    }

    /** Bounded retries after a deadline no read could answer, while the app stays in the foreground. */
    private fun retryAfterDeadline() {
        val since = monotonicClock()
        val job = scope.launch(start = CoroutineStart.LAZY) {
            for (backoff in DEADLINE_RETRY_DELAYS_MS) {
                delay(maxOf(backoff, deadlineGate.msUntilOpen()))
                // Gone to the background, or something else (identify, a foreground read, a
                // verify) already brought a fresh snapshot.
                if (!isForeground() || (lastFreshAt ?: Long.MIN_VALUE) >= since) return@launch
                if (!deadlineGate.tryPass()) continue
                if (readForDeadline()) return@launch
            }
        }
        synchronized(retryLock) {
            deadlineRetry?.cancel()
            deadlineRetry = job
        }
        job.start()
    }

    internal companion object {
        /** How long before a deadline its read is made, so a renewal lands before the old deadline passes. */
        const val DEADLINE_READ_LEAD_MS = 10_000L

        /** How long past the deadline a read still in flight may take before the access ends anyway. */
        const val DEADLINE_ANSWER_WAIT_MS = 5_000L

        /** At most one deadline read (or retry) a minute, whatever the deadlines do. */
        const val DEADLINE_READ_INTERVAL_MS = 60_000L

        /** Waits between retries after an unanswered deadline; the gate spaces them a minute apart at least. */
        val DEADLINE_RETRY_DELAYS_MS = listOf(15_000L, 30_000L, 60_000L, 120_000L, 240_000L)
    }
}
