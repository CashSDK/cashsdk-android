package com.cashsdk

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import com.cashsdk.billing.BillingManager
import com.cashsdk.billing.ProductPrice
import com.cashsdk.billing.SettleOutcome
import com.cashsdk.entitlements.EntitlementStore
import com.cashsdk.events.EventQueue
import com.cashsdk.events.PrefsEventStorage
import com.cashsdk.model.ConsumableSpendResult
import com.cashsdk.model.Entitlements
import com.cashsdk.model.Offering
import com.cashsdk.model.EventInput
import com.cashsdk.model.PurchaseKind
import com.cashsdk.model.VerifyRequest
import com.cashsdk.model.toJsonObject
import com.cashsdk.net.ApiClient
import com.cashsdk.net.VerifyDecision
import com.cashsdk.paywall.PaywallActivity
import com.cashsdk.paywall.PaywallPresentation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min
import kotlin.math.pow

/**
 * Public entry point. Configure once at app start, then use [shared].
 *
 * ```kotlin
 * // Application.onCreate()
 * CashSDK.configure(this, publishableKey = "csk_pk_…")
 * CashSDK.shared.identify(userId = "user_123")
 *
 * // Gating (offline-valid, synchronous):
 * if (CashSDK.shared.entitlements.isActive("pro")) showProFeature()
 *
 * // React to changes:
 * lifecycleScope.launch { CashSDK.shared.entitlementUpdates.collect { render(it) } }
 *
 * // Present a paywall for a placement:
 * CashSDK.shared.register("onboarding_finished")
 * ```
 */
object CashSDK {

    @Volatile
    private var instance: CashSDKClient? = null

    /**
     * Initialize the SDK. Safe to call once (typically from `Application.onCreate`).
     * @param apiBase override the REST base (default `https://api.cashsdk.com`) for dev/staging.
     * @param environment pin the store environment (`"Sandbox"` / `"Production"`), mirroring
     *   `CashSDK.configure(publishableKey:apiBase:environment:)` on iOS. Leave null to let the
     *   SDK learn it from the server's verify response — Play Billing cannot tell the client
     *   whether a purchase was a license-tester one, so unlike StoreKit there is nothing to
     *   read locally. Entitlements are resolved per environment server-side, so getting this
     *   wrong is what makes a license-tester purchase vanish from the next entitlements read.
     */
    @JvmStatic
    @JvmOverloads
    fun configure(
        context: Context,
        publishableKey: String,
        apiBase: String? = null,
        environment: String? = null,
    ) {
        instance?.shutdown()
        val config = Configuration(
            publishableKey = publishableKey,
            apiBase = apiBase ?: Configuration.DEFAULT_API_BASE,
            environment = environment,
        )
        instance = CashSDKClient(context.applicationContext, config)
    }

    /** The configured client. Throws [CashSDKError.NotConfigured] if [configure] wasn't called. */
    @JvmStatic
    val shared: CashSDKClient
        get() = instance ?: throw CashSDKError.NotConfigured

    /** Whether [configure] has run — for host code that wants to avoid the throwing [shared]. */
    @JvmStatic
    val isConfigured: Boolean
        get() = instance != null
}

/**
 * The stateful SDK client behind [CashSDK.shared]. Coordinates the four collaborators:
 * [ApiClient] (REST), [EntitlementStore] (snapshot + offline cache), [BillingManager]
 * (SDK-driven Play purchases), and [PaywallActivity] (Compose renderer).
 */
class CashSDKClient internal constructor(
    private val appContext: Context,
    config: Configuration,
) {
    // Long-lived SDK scope for fire-and-forget work (events, register, background refresh).
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** A host-pinned environment is authoritative — never overridden by what the server says. */
    private val pinnedEnvironment: String? = config.environment

    private val store = EntitlementStore(appContext, config.environment)
    private val api = ApiClient(appContext, config, entitlementEtag = { store.etag })
    private val billing = BillingManager(
        appContext,
        // The current app user id, read at purchase time so BillingManager can stamp the
        // canonical obfuscatedAccountId on the flow (attribution for RTDN/voided-purchase events).
        userIdProvider = { api.userId },
    ) { productId, token, kind ->
        // BillingManager delegates server verification back here so the money path is centralized.
        verifyPurchase(productId, token, kind)
    }

    /**
     * Identity survives a relaunch. `userToken` is a bearer credential, so it is encrypted with
     * an Android Keystore key rather than sitting in plaintext preferences — but it MUST be
     * persisted: restoring `userId` alone meant every identified call after a restart 401'd
     * silently, so entitlements never refreshed and purchases verified unattributed.
     */
    private val identityStore = SecureStore(appContext, "cashsdk_identity")

    /**
     * Durable, bounded telemetry queue. Events used to be posted straight from [emit] inside a
     * `runCatching`, so anything recorded offline, in the background, or just before the process
     * died was lost outright — and nothing identified an event, so a retried batch double-counted.
     */
    private val events = EventQueue(PrefsEventStorage(appContext))
    private val eventFlushLock = Mutex()
    private val eventFlushFailures = AtomicInteger(0)
    private val eventFlushScheduled = AtomicBoolean(false)
    private var foregroundObserver: Application.ActivityLifecycleCallbacks? = null

    init {
        // Restore identity + hydrate cached entitlements so gating is correct offline on launch.
        val persistedUserId = identityStore.getString(KEY_USER_ID)
        api.userId = persistedUserId
        api.userToken = identityStore.getString(KEY_USER_TOKEN)
        store.hydrate(persistedUserId)
        // Drain whatever a previous run left on disk, and keep draining on every foreground.
        observeForeground()
        flushEvents()
        // Telemetry ping (flips first-install detection server-side).
        emit("sdk_configured")
        if (persistedUserId != null) {
            scope.launch { runCatching { refreshEntitlements() } }
        }
        // Finish anything left open by a previous run: a pending purchase that settled while the
        // app was gone, a verify that failed offline, a consume Play refused. Google auto-refunds
        // an unacknowledged purchase after 3 days, so this cannot wait for a manual restore().
        scope.launch { runCatching { billing.syncPurchases() } }
    }

    // ── Entitlements (public read surface) ───────────────────────────────────────

    /** The current cached snapshot — synchronous and offline-valid. */
    val entitlements: Entitlements get() = store.current

    /** Hot flow of entitlement snapshots; emits the current value immediately on collect. */
    val entitlementUpdates: Flow<Entitlements> get() = store.snapshot

    // ── Identity ────────────────────────────────────────────────────────────────

    /**
     * Associate subsequent calls with [userId] (sent as `X-CashSDK-User-Id`).
     *
     * @param userToken the signed user token (`X-CashSDK-User-Token`) minted by YOUR backend
     *   (HS256 over the per-app secret; never on the client). Production trusts only this —
     *   without it, identified verify/entitlement/consumable calls are rejected live. Omit it
     *   only for local/dev, where the server accepts the raw id.
     */
    @JvmOverloads
    fun identify(userId: String, userToken: String? = null) {
        require(userId.isNotBlank()) { "userId must not be blank" }
        identityStore.putString(KEY_USER_ID, userId)
        identityStore.putString(KEY_USER_TOKEN, userToken)
        api.userId = userId
        api.userToken = userToken
        // Re-key the cache to this user. The persisted record carries its owner AND the ETag that
        // describes it, so a cache belonging to anyone else yields EMPTY *and* no ETag — there is
        // no way to end up revalidating someone else's snapshot into a 304.
        store.hydrate(userId)
        emit("identify")
        scope.launch { runCatching { refreshEntitlements() } }
        // A purchase made before identify() verifies unattributed and is left unsettled on
        // purpose; now that we know who the buyer is, re-verify it.
        scope.launch { runCatching { billing.syncPurchases() } }
    }

    /** Clear identity and cached entitlements (call on sign-out). */
    fun logout() {
        emit("logout")
        identityStore.remove(KEY_USER_ID, KEY_USER_TOKEN)
        api.userId = null
        api.userToken = null
        // Drops the snapshot and the ETag that describes it together — they are one record, so
        // there is no way to leave an ETag behind that would 304 the next sign-in into EMPTY.
        store.clear()
    }

    // ── Purchases ────────────────────────────────────────────────────────────────

    /**
     * Verify a Google Play purchase server-side and return the fresh entitlement snapshot.
     * Call this from your app's `PurchasesUpdatedListener` after `launchBillingFlow`
     * (app-driven billing), passing the [purchaseToken] and product id from the [Purchase].
     *
     * In app-driven billing the host is responsible for `acknowledgePurchase`/`consumePurchase`
     * after this returns (in the SDK-driven paywall flow the SDK acknowledges for you).
     *
     * @throws CashSDKError.Server on a rejected verification, [CashSDKError.Network] offline.
     * @throws CashSDKError.PurchaseNotAttributed when the server credited the purchase to no one
     *   (identify with a `userToken` first) — the purchase is kept, not settled, and retried.
     */
    suspend fun verifyPurchase(productId: String, purchaseToken: String, kind: PurchaseKind): Entitlements {
        val owner = api.userId
        val outcome = api.verifyPurchase(VerifyRequest(productId, purchaseToken, kind))
        onNetworkSuccess() // connectivity is provably back — drain any telemetry backlog
        // Adopt the environment the server resolved this purchase into, BEFORE any of the
        // early returns below — a deferred purchase is still a Sandbox-or-Production fact, and
        // the follow-up read that eventually credits it has to be scoped to the same one.
        // Google Play tells the client nothing about this; the server is the only source.
        adoptEnvironment(outcome.entitlements.environment)
        // The order of these three cases is load-bearing, so it lives in a pure, unit-tested
        // function rather than as inline `if`s that can be reshuffled by accident. See
        // [VerifyDecision.of] for why PENDING must be decided before UNATTRIBUTED.
        when (VerifyDecision.of(outcome.attributed, outcome.entitlements)) {
            // Google hasn't settled the payment, so the server granted nothing and the snapshot
            // beside `pending` is EMPTY. Caching it would drop a paying user to the free tier
            // until the next successful read — for a slow deferred payment, days.
            VerifyDecision.PENDING -> {
                emit("purchase_pending", product = productId)
                return outcome.entitlements
            }
            // 200 + a snapshot credited to NOBODY. Persisting it would wipe this user's real
            // entitlements, and settling the purchase would finalize a sale nobody owns.
            VerifyDecision.UNATTRIBUTED -> {
                emit("purchase_unattributed", product = productId)
                throw CashSDKError.PurchaseNotAttributed
            }
            VerifyDecision.GRANTED -> Unit
        }
        // The purchase IS recorded server-side (so this still counts as success), but the snapshot
        // describes whoever was signed in when the request went out — don't cache it under a user
        // who signed in meanwhile.
        if (api.userId == owner) store.update(owner, outcome.entitlements, outcome.etag)
        emit("purchase_verified", product = productId)
        return outcome.entitlements
    }

    /** What happened to one owned purchase during [restoreDetailed]. */
    data class RestoreOutcome(
        val productId: String,
        val purchaseToken: String,
        /** The server accepted and credited it. */
        val verified: Boolean,
        /** It is consumed/acknowledged with Google (so it can be re-bought / won't auto-refund). */
        val settled: Boolean,
        val error: Throwable? = null,
    )

    /** Per-purchase results of a restore, plus the resulting snapshot. */
    data class RestoreResult(
        val entitlements: Entitlements,
        val outcomes: List<RestoreOutcome>,
    ) {
        val failures: List<RestoreOutcome> get() = outcomes.filter { !it.verified || !it.settled }
    }

    /**
     * Restore purchases and report what happened to each one.
     *
     * [restore] used to wrap every purchase in `runCatching` and then return as if it had
     * succeeded, so "Restore purchases" showed a success state even when the server rejected
     * every single verify. Use this when you need to tell the user the truth.
     */
    suspend fun restoreDetailed(): RestoreResult {
        api.userId ?: throw CashSDKError.NotIdentified
        val outcomes = mutableListOf<RestoreOutcome>()
        for (owned in billing.queryOwnedPurchases()) {
            // Re-verify, then FINISH the purchase. A purchase whose app died before the
            // acknowledge/consume call is still open — Play auto-refunds anything left
            // unacknowledged for 3 days, and an unconsumed consumable can never be
            // re-bought. The verify response tells us which of the two it needs.
            outcomes += runCatching {
                val verified = verifyPurchase(owned.productId, owned.purchaseToken, owned.kind)
                if (verified.pending) {
                    RestoreOutcome(owned.productId, owned.purchaseToken, verified = false, settled = false)
                } else {
                    val settle = billing.settle(owned.purchaseToken, verified.productType, owned.isAcknowledged)
                    RestoreOutcome(
                        owned.productId,
                        owned.purchaseToken,
                        verified = true,
                        settled = settle.settled,
                        error = (settle as? SettleOutcome.Failed)
                            ?.let { CashSDKError.Billing(it.responseCode, it.debugMessage) },
                    )
                }
            }.getOrElse { error ->
                RestoreOutcome(owned.productId, owned.purchaseToken, verified = false, settled = false, error = error)
            }
        }
        val snapshot = runCatching { refreshEntitlements() }.getOrElse { store.current }
        emit("restore", properties = mapOf("restored" to outcomes.size, "failed" to outcomes.count { it.error != null }))
        return RestoreResult(snapshot, outcomes)
    }

    /**
     * Restore purchases: re-query owned Play purchases, re-verify each server-side, then read
     * the authoritative snapshot. Requires [identify] first.
     *
     * Throws the first failure when there were purchases to restore and NONE of them succeeded —
     * reporting success in that case is what made a broken restore look like "nothing to
     * restore". Use [restoreDetailed] for per-purchase results.
     */
    suspend fun restore(): Entitlements {
        val result = restoreDetailed()
        val firstError = result.outcomes.firstNotNullOfOrNull { it.error }
        if (result.outcomes.isNotEmpty() && result.outcomes.none { it.verified } && firstError != null) {
            throw firstError
        }
        return result.entitlements
    }

    /**
     * Re-check Play for purchases the SDK hasn't finished with: a pending purchase that settled,
     * a verify that failed offline, a consume Play refused. Idempotent and cheap — call it from
     * `onResume` so a deferred (pending) purchase is credited as soon as the user comes back.
     */
    suspend fun syncPurchases() = billing.syncPurchases()

    // ── Offerings ────────────────────────────────────────────────────────────────

    /**
     * The offering this app would present right now, expanded to packages and products.
     *
     * Returns `null` when no offering is configured — a normal state before catalog setup,
     * not an error. Use it to build a custom paywall without hardcoding product ids:
     *
     * ```kotlin
     * val annual = CashSDK.shared.offerings()?.annual
     * if (annual != null) CashSDK.shared.purchase(activity, annual.product.identifier)
     * ```
     *
     * The `price` on each product is the CATALOG price the server last synced from Play. For
     * the exact localized string to display, read Play Billing's own `ProductDetails` — this
     * tells you *which* products to show and how they group, not what text to render.
     */
    suspend fun offerings(): Offering? = api.currentOffering()

    // ── Paywalls ─────────────────────────────────────────────────────────────────

    /**
     * Resolve the paywall for [placement] and present it (Compose). Fire-and-forget: on skip,
     * an unknown placement, resolver error, or offline-with-no-config, nothing is shown and the
     * host simply proceeds (FR-6.7 graceful advance). [params] are attached to paywall events.
     *
     * Optionally gates a feature behind that paywall.
     *
     * ```kotlin
     * CashSDK.shared.register("start_workout") {
     *     startWorkout()          // runs per the paywall's server-side gating
     * }
     * ```
     *
     * The DASHBOARD decides — not the app — whether the placement is **gated** ([feature] runs
     * only once the user holds an entitlement) or **non-gated** ([feature] runs on dismiss
     * regardless), so the same call site can be a hard gate in one campaign and a soft nudge in
     * another with no code change. Already-entitled users skip the paywall and run [feature]
     * immediately. Holdout / no-paywall / resolver-error also run it — an unreachable resolver
     * must never lock a user out of their own app. [feature] runs at most once, on the main thread.
     *
     * Mirrors `register(placement:params:handler:feature:)` in the iOS SDK.
     */
    @JvmOverloads
    fun register(
        placement: String,
        params: Map<String, Any>? = null,
        feature: (() -> Unit)? = null,
    ) {
        scope.launch {
            emit("paywall_register", placement = placement, properties = params)

            // Already entitled → never show a paywall, just run the feature.
            if (feature != null && entitlements.hasActiveEntitlement) {
                runFeature(feature)
                return@launch
            }

            val resolved = runCatching { api.resolvePaywall(placement) }.getOrNull()
            val config = resolved?.paywall?.config
            if (config == null) {
                emit("paywall_skip", placement = placement, properties = mapOf("reason" to "no_paywall"))
                runFeature(feature)
                return@launch
            }
            // A config this renderer cannot draw (a `schema_version: 2` component tree — see
            // PaywallConfig.isRenderable). Every v1 field has a default, so presenting it would
            // decode "successfully" into a blank paywall with no purchasable product: a dead end
            // for the user and an impression logged against a paywall nobody could convert on.
            // Advance the host instead, and label the skip so the gap is visible in analytics
            // rather than looking like a 0%-converting paywall.
            if (!config.isRenderable) {
                emit(
                    "paywall_skip",
                    placement = placement,
                    properties = mapOf("reason" to "unsupported_schema", "schemaVersion" to config.schemaVersion),
                    variant = resolved.variantId,
                )
                runFeature(feature)
                return@launch
            }

            val gated = config.isGated
            PaywallActivity.present(
                appContext,
                PaywallPresentation(
                    config = config,
                    placement = placement,
                    variantId = resolved.variantId,
                    experimentId = resolved.experimentId,
                ),
                onFinish = {
                    // Gated: the user must actually hold an entitlement now.
                    // Non-gated: run regardless of how the paywall was dismissed.
                    if (!gated || entitlements.hasActiveEntitlement) runFeature(feature)
                },
            )
        }
    }

    /** Run a gating closure on the main thread, at most once. */
    private fun runFeature(feature: (() -> Unit)?) {
        val f = feature ?: return
        android.os.Handler(android.os.Looper.getMainLooper()).post { f() }
    }

    // ── Purchase ──────────────────────────────────────────────────────────────────

    /**
     * Buy a product and verify it server-side, returning the fresh entitlement snapshot.
     *
     * The iOS counterpart is `purchase(_ productId:)`. Android additionally requires an
     * [activity]: Play Billing launches its flow from a foreground Activity, which is a
     * platform constraint rather than an API asymmetry — there is no way to start the flow
     * from application context.
     *
     * [kind] tells Play whether to query the SUBS or INAPP product type. After the server
     * grants entitlement the purchase is settled automatically (consumables CONSUMED so they
     * can be re-bought, everything else ACKNOWLEDGED so Play doesn't auto-refund it).
     */
    @JvmOverloads
    suspend fun purchase(
        activity: Activity,
        productId: String,
        kind: PurchaseKind = PurchaseKind.SUBSCRIPTION,
    ): Entitlements = billing.purchase(activity, productId, kind)

    // ── Consumables (one-time IAP) ────────────────────────────────────────────────

    /**
     * Spendable balance of a consumable product, from the cached snapshot (synchronous and
     * offline-valid, like [entitlements]). May be NEGATIVE after a refund of already-spent units.
     */
    fun consumableBalance(productIdentifier: String): Int =
        entitlements.balanceOf(productIdentifier)

    /**
     * Spend units of a consumable (e.g. deduct 10 coins).
     *
     * [idempotencyKey] must be STABLE for a given logical spend — reuse the same key when
     * retrying, or a dropped response will debit the user twice. Use the id of whatever the
     * spend buys, not a fresh UUID per attempt. Throws if the balance is insufficient.
     */
    @JvmOverloads
    suspend fun spendConsumable(
        productIdentifier: String,
        units: Int,
        idempotencyKey: String,
        note: String? = null,
    ): ConsumableSpendResult {
        val result = api.spendConsumable(productIdentifier, units, idempotencyKey, note)
        // Reflect the new balance locally without waiting for the next entitlements poll.
        runCatching { refreshEntitlements() }
        return result
    }

    // ── Events ────────────────────────────────────────────────────────────────────

    /** Log a custom analytics event. Best-effort — never throws into the host. */
    @JvmOverloads
    fun logEvent(name: String, properties: Map<String, Any>? = null) = emit(name, properties = properties)

    // ── Internal (used by the paywall renderer) ─────────────────────────────────────

    internal suspend fun paywallPrice(productId: String): ProductPrice? = billing.resolvePrice(productId)

    internal suspend fun paywallPurchase(activity: Activity, productId: String, kind: PurchaseKind): Entitlements =
        billing.purchase(activity, productId, kind)

    internal fun track(
        event: String,
        placement: String? = null,
        paywall: String? = null,
        product: String? = null,
        properties: Map<String, Any>? = null,
        variant: String? = null,
    ) = emit(event, placement, paywall, product, properties, variant)

    // ── Internals ────────────────────────────────────────────────────────────────

    /**
     * Adopt the store environment the server reported on a verify response.
     *
     * An explicit `configure(environment = …)` always wins — a host that pinned the environment
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
    }

    private suspend fun refreshEntitlements(): Entitlements {
        val owner = api.userId
        val result = api.getEntitlements()
        onNetworkSuccess() // see verifyPurchase — a good round trip is the cheapest "we're online" signal
        // Identity changed while the read was in flight: this body describes the PREVIOUS user.
        // Caching it under the new id would hand user B user A's entitlements.
        if (api.userId != owner) return store.current
        return when (result) {
            is ApiClient.EntitlementsResult.Modified -> {
                store.update(owner, result.entitlements, result.etag)
                result.entitlements
            }
            // ETag hit — the cache is provably fresh, so don't rewrite it (a failed rewrite would
            // drop the very ETag that just proved it good).
            ApiClient.EntitlementsResult.NotModified -> store.current
        }
    }

    /**
     * Record one telemetry event. Never blocks and never throws into the host.
     *
     * The client event id and the user id are stamped HERE, at record time — not at send time.
     * The queue is durable, so a batch can leave minutes or a relaunch later; a send-time id
     * would change on every retry (defeating the server's `[appId, clientEventId]` dedupe) and a
     * send-time user id would credit one user's events to whoever signed in meanwhile.
     */
    private fun emit(
        event: String,
        placement: String? = null,
        paywall: String? = null,
        product: String? = null,
        properties: Map<String, Any>? = null,
        variant: String? = null,
    ) {
        val input = EventInput(
            event = event,
            id = UUID.randomUUID().toString(),
            userId = api.userId,
            placement = placement,
            paywall = paywall,
            variant = variant,
            product = product,
            props = properties?.toJsonObject(),
            // Stamped HERE, with id and userId, for the same reason: the queue is durable, so
            // letting the server date the row at ingest re-times every offline event to
            // whenever connectivity returned. Mirrors `recordEvent` in the iOS SDK.
            ts = System.currentTimeMillis(),
        )
        scope.launch(Dispatchers.IO) {
            val depth = runCatching { events.append(input) }.getOrDefault(0)
            if (depth >= EVENT_FLUSH_THRESHOLD) flushEvents() else scheduleFlush(EVENT_DEBOUNCE_MS)
        }
    }

    /** Ask for a flush. Returns immediately; [eventFlushLock] keeps two triggers from racing. */
    private fun flushEvents() {
        scope.launch(Dispatchers.IO) { flushOnce() }
    }

    /**
     * Send one batch.
     *
     * The batch leaves the durable queue before the request and is handed BACK on a retryable
     * failure, so a dropped connection costs nothing. A permanent rejection (a `4xx` that will
     * answer identically forever — bad publishable key, malformed body) drops the batch instead:
     * keeping it would wedge every later event behind it for the life of the install.
     */
    private suspend fun flushOnce() {
        eventFlushLock.withLock {
            val batch = runCatching { events.take(EVENT_BATCH_SIZE) }.getOrDefault(emptyList())
            if (batch.isEmpty()) return
            try {
                api.sendEvents(batch)
                eventFlushFailures.set(0)
                // A long offline backlog: keep draining rather than waiting for the next event.
                if (runCatching { events.count() }.getOrDefault(0) > 0) flushEvents()
            } catch (e: Throwable) {
                if (!isRetryable(e)) return
                runCatching { events.restore(batch) }
                scheduleRetry()
            }
        }
    }

    /** Exponential backoff: 4s, 8s, 16s … capped at [EVENT_RETRY_MAX_MS]. */
    private fun scheduleRetry() {
        val attempt = eventFlushFailures.updateAndGet { min(it + 1, 8) }
        scheduleFlush(min(2.0.pow(attempt).toLong() * 2_000L, EVENT_RETRY_MAX_MS))
    }

    /**
     * Schedule a single deferred flush. Coalesced — a burst of events schedules one timer, and
     * ordinary traffic never shortens a retry backoff already in flight.
     */
    private fun scheduleFlush(delayMs: Long) {
        if (!eventFlushScheduled.compareAndSet(false, true)) return
        scope.launch(Dispatchers.IO) {
            delay(delayMs)
            eventFlushScheduled.set(false)
            flushOnce()
        }
    }

    /**
     * Flush when the app comes back to the foreground.
     *
     * This is the trigger that actually recovers an offline session: Android freezes background
     * processes, so a timer scheduled before the user left may never fire, and a queue that only
     * drains on new activity strands the last events of every session. Uses plain
     * `ActivityLifecycleCallbacks` rather than `androidx.lifecycle-process` — this module ships
     * no such dependency.
     */
    private fun observeForeground() {
        val application = appContext as? Application ?: return
        val observer = object : Application.ActivityLifecycleCallbacks {
            private var started = 0
            override fun onActivityStarted(activity: Activity) {
                if (started++ == 0) {
                    // A fresh foreground is a fresh chance — don't make the user wait out a
                    // backoff computed while the device had no connectivity.
                    eventFlushFailures.set(0)
                    flushEvents()
                }
            }

            override fun onActivityStopped(activity: Activity) {
                if (started > 0) started--
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        }
        foregroundObserver = observer
        runCatching { application.registerActivityLifecycleCallbacks(observer) }
    }

    /**
     * Called after any successful API round trip: connectivity is provably back, so this is the
     * cheapest possible signal to drain a backlog that is otherwise sitting out a backoff.
     */
    private fun onNetworkSuccess() {
        if (eventFlushFailures.getAndSet(0) > 0) flushEvents()
    }

    /**
     * Retry, or give up on this batch? Transport failures and `408/429/5xx` will plausibly
     * succeed later; any other `4xx` is a verdict about the request itself and will be identical
     * next time. Mirrors `CashSDK.isRetryable` in the iOS SDK.
     */
    private fun isRetryable(e: Throwable): Boolean = when (e) {
        is CashSDKError.Server -> e.status == 408 || e.status == 429 || e.status >= 500
        else -> true // Network / timeout / anything unclassified
    }

    internal fun shutdown() {
        (appContext as? Application)?.let { app ->
            foregroundObserver?.let { runCatching { app.unregisterActivityLifecycleCallbacks(it) } }
        }
        foregroundObserver = null
        billing.close()
        scope.cancel()
    }

    private companion object {
        const val KEY_USER_ID = "user_id"
        const val KEY_USER_TOKEN = "user_token"

        /** Events per request. The server caps a batch at 500; stay well under it. */
        const val EVENT_BATCH_SIZE = 50

        /** Flush immediately at this depth instead of waiting out the debounce. */
        const val EVENT_FLUSH_THRESHOLD = 20

        /** Debounce for an ordinary event — batches a burst into one request. */
        const val EVENT_DEBOUNCE_MS = 5_000L

        /** Backoff ceiling. The foreground hook gets a queue out sooner than this in practice. */
        const val EVENT_RETRY_MAX_MS = 300_000L
    }
}
