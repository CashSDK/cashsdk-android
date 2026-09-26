package com.cashsdk

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import com.cashsdk.billing.BillingManager
import com.cashsdk.billing.ProductPrice
import com.cashsdk.entitlements.EntitlementStore
import com.cashsdk.entitlements.PrefsSnapshotStorage
import com.cashsdk.events.EventQueue
import com.cashsdk.events.PrefsEventStorage
import com.cashsdk.model.ConsumableSpendResult
import com.cashsdk.model.Entitlements
import com.cashsdk.model.Offering
import com.cashsdk.model.EventInput
import com.cashsdk.model.PurchaseClaim
import com.cashsdk.model.PurchaseKind
import com.cashsdk.model.toJsonObject
import com.cashsdk.net.ApiClient
import com.cashsdk.net.hostAppVersion
import com.cashsdk.paywall.PaywallActivity
import com.cashsdk.paywall.PaywallPresentation
import kotlinx.coroutines.Dispatchers
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
     * @param publishableKey the app's `csk_pk_…`. One key per app, and it works in every
     *   environment: an internal-testing install runs the same APK as the Play listing, so
     *   there is no build-time split to get right. Safe to ship in the APK.
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
 * The stateful SDK client behind [CashSDK.shared]. Coordinates the collaborators:
 * [ApiClient] (REST), [EntitlementStore] (snapshot + offline cache), [EntitlementManager]
 * (verify, restore, reads and access deadlines), [BillingManager] (SDK-driven Play purchases),
 * and [PaywallActivity] (Compose renderer).
 */
class CashSDKClient internal constructor(
    private val appContext: Context,
    config: Configuration,
) {
    // Long-lived SDK scope for fire-and-forget work (events, register, background refresh). An
    // uncaught failure in it is logged, never thrown into the host app.
    private val scope = sdkBackgroundScope(Dispatchers.Main.immediate)

    /** A host-pinned environment is authoritative — never overridden by what the server says. */
    private val pinnedEnvironment: String? = config.environment

    /**
     * Where the environment learned from a verify response survives a relaunch.
     *
     * It has to survive: a consumed consumable never comes back from `queryPurchasesAsync`,
     * so a licence tester whose only purchases are coin packs re-verifies nothing on the next
     * launch and the SDK would forget it is in Sandbox. Balances are per environment
     * server-side, so that forgetting reads back as "my coins are gone". The environment of
     * an install never changes, so remembering it is always right.
     */
    private val environmentPrefs = appContext.applicationContext
        .getSharedPreferences("cashsdk_environment", Context.MODE_PRIVATE)

    private val rememberedEnvironment: String? = pinnedEnvironment
        ?: environmentPrefs.getString(KEY_OBSERVED_ENVIRONMENT, null)
            ?.takeIf { it == "Sandbox" || it == "Production" }

    private val store = EntitlementStore(PrefsSnapshotStorage(appContext), rememberedEnvironment)
    private val api = ApiClient(config, appVersion = hostAppVersion(appContext), entitlementEtag = { store.etag })

    /** Whether the user can see the app; fed by [observeForeground]. */
    private val foreground = ForegroundState()

    /** The money path: verify, restore, entitlement reads and access deadlines. */
    private val access = EntitlementManager(
        api = api,
        store = store,
        scope = scope,
        monotonicClock = SystemClock::elapsedRealtime,
        pinnedEnvironment = pinnedEnvironment,
        isForeground = { foreground.isForeground },
        rememberEnvironment = { environment ->
            runCatching { environmentPrefs.edit().putString(KEY_OBSERVED_ENVIRONMENT, environment).apply() }
        },
        onNetworkSuccess = { onNetworkSuccess() },
        emit = { event, product, properties -> emit(event, product = product, properties = properties) },
    )

    private val billing = BillingManager(
        appContext,
        // The current app user id, read at purchase time so BillingManager can stamp the
        // canonical obfuscatedAccountId on the flow (attribution for RTDN/voided-purchase events).
        userIdProvider = { api.userId },
        // Server verification goes through the one money path. The claim each verify sends is
        // decided inside it (PurchaseCompleter), so this wiring carries none.
        access = access,
    )

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
        // The environment a previous run learned, so the first request of this run carries it
        // and the cache is keyed to the right one before it is hydrated.
        if (pinnedEnvironment == null && rememberedEnvironment != null) api.environment = rememberedEnvironment
        // Restore identity + hydrate cached entitlements so gating is correct offline on launch.
        val persistedUserId = identityStore.getString(KEY_USER_ID)
        api.setIdentity(persistedUserId, identityStore.getString(KEY_USER_TOKEN))
        store.hydrate(persistedUserId)
        // Drain whatever a previous run left on disk, and keep draining on every foreground.
        observeForeground()
        flushEvents()
        // Telemetry ping (flips first-install detection server-side).
        emit("sdk_configured")
        if (persistedUserId != null) access.refreshInBackground(force = true)
        // Finish anything left open by a previous run: a pending purchase that settled while the
        // app was gone, a verify that failed offline, a consume Play refused. Google auto-refunds
        // an unacknowledged purchase after 3 days, so this cannot wait for a manual restore().
        scope.launch { runCatching { billing.syncPurchases() } }
        // Around the earliest access deadline, ask the server whether the access continues and
        // stop reporting it when nothing does. The cache used to keep it until a restart.
        scope.launch { access.watchDeadlines() }
    }

    // ── Entitlements (public read surface) ───────────────────────────────────────

    /**
     * The current cached snapshot: synchronous and offline-valid. Entitlements whose
     * `expiresAt` has passed are left out (and the tier recomputed), whether or not the device
     * has been online since.
     */
    val entitlements: Entitlements get() = store.current

    /**
     * Hot flow of entitlement snapshots; emits the current value immediately on collect. Also
     * emits when an entitlement reaches its `expiresAt`, and after the refresh that follows.
     */
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
        // Hosts identify on every launch, after every token refresh and often on resume, and the
        // overwhelming majority of those calls change nothing. Doing the full re-key each time
        // cost a Keystore write and a blocking disk read on the caller's thread, threw away the
        // refresh window, and sent one server verify per purchase the Google account owns.
        val previous = api.identitySnapshot()
        val unchanged = previous.userId == userId && previous.userToken == userToken
        if (!unchanged) {
            identityStore.putString(KEY_USER_ID, userId)
            identityStore.putString(KEY_USER_TOKEN, userToken)
            api.setIdentity(userId, userToken)
        }
        emit("identify")
        // Re-key the cache to this user and read their snapshot. Unchanged identity keeps the
        // cache it already has and reads through the ordinary five-minute gate.
        access.onIdentityChanged(userId, sameUser = previous.userId == userId)
        // A purchase made before identify() verifies unattributed and is left unsettled on
        // purpose; now that we know who the buyer is, re-verify it. Throttled, because a pass a
        // few seconds after the last one answers nothing new.
        scope.launch { runCatching { billing.syncPurchasesIfStale() } }
    }

    /** Clear identity and cached entitlements (call on sign-out). */
    fun logout() {
        emit("logout")
        identityStore.remove(KEY_USER_ID, KEY_USER_TOKEN)
        api.setIdentity(null, null)
        // Drops the snapshot and the ETag that describes it together: they are one record, so
        // there is no way to leave an ETag behind that would 304 the next sign-in into EMPTY.
        access.onLogout()
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
     * [claim] is sent as `X-CashSDK-Claim` and decides what the server may do about ownership.
     * The default, [PurchaseClaim.PURCHASE], is right for a purchase your listener just received
     * for the signed-in user. Pass [PurchaseClaim.RESTORE] from a restore button and
     * [PurchaseClaim.SYNC] from anything automatic (launch, resume, your own retry loop): only
     * `PURCHASE` and `RESTORE` can move a purchase from another app account.
     *
     * A `503`, `429` or other `5xx` answer is retried a couple of times inside this call,
     * honouring `Retry-After`. Check the returned snapshot's `purchaseOutcomeConfirmed`: `false`
     * means the purchase was recorded but granted no access. `transferredFromAnotherAccount`
     * means it was just moved to this user from another app account.
     *
     * @throws CashSDKError.Server on a rejected verification, [CashSDKError.Network] offline.
     * @throws CashSDKError.PurchaseNotAttributed when the server credited the purchase to no one
     *   (identify with a `userToken` first) — the purchase is kept, not settled, and retried.
     */
    @JvmOverloads
    suspend fun verifyPurchase(
        productId: String,
        purchaseToken: String,
        kind: PurchaseKind,
        claim: PurchaseClaim = PurchaseClaim.PURCHASE,
    ): Entitlements = access.verifyPurchase(productId, purchaseToken, kind, claim)

    /** What happened to one owned purchase during [restoreDetailed]. */
    data class RestoreOutcome @JvmOverloads constructor(
        val productId: String,
        val purchaseToken: String,
        /** The server accepted and credited it. */
        val verified: Boolean,
        /** It is consumed/acknowledged with Google (so it can be re-bought / won't auto-refund). */
        val settled: Boolean,
        val error: Throwable? = null,
        /**
         * This restore moved the purchase to the signed-in user from another app account (restore
         * policy `transfer`). Tell the user; the other account no longer has that access.
         */
        val transferredFromAnotherAccount: Boolean = false,
        /**
         * The server's `purchaseOutcomeConfirmed` for this purchase. `false`: verified and
         * settled, but the server did not confirm any access for it. Not a failure of the
         * restore; decide what to tell the user. `null`: not verified, or an older server.
         */
        val purchaseOutcomeConfirmed: Boolean? = null,
    )

    /** Per-purchase results of a restore, plus the resulting snapshot. */
    data class RestoreResult(
        val entitlements: Entitlements,
        val outcomes: List<RestoreOutcome>,
    ) {
        val failures: List<RestoreOutcome> get() = outcomes.filter { !it.verified || !it.settled }

        /** Purchases this restore moved over from another app account. */
        val transferred: List<RestoreOutcome> get() = outcomes.filter { it.transferredFromAnotherAccount }

        /** Verified and settled purchases whose access the server did not confirm. */
        val unconfirmed: List<RestoreOutcome> get() = outcomes.filter { it.purchaseOutcomeConfirmed == false }
    }

    /**
     * Restore purchases and report what happened to each one.
     *
     * [restore] used to wrap every purchase in `runCatching` and then return as if it had
     * succeeded, so "Restore purchases" showed a success state even when the server rejected
     * every single verify. Use this when you need to tell the user the truth.
     *
     * Every verify here sends claim `restore`: this is the explicit action that may move a
     * purchase from another app account, when the app's restore policy allows it. Outcomes
     * report such moves in [RestoreOutcome.transferredFromAnotherAccount], and purchases whose
     * access the server did not confirm in [RestoreOutcome.purchaseOutcomeConfirmed]; neither
     * counts as a failure.
     */
    suspend fun restoreDetailed(): RestoreResult = access.restoreDetailed(
        queryOwned = { billing.queryOwnedPurchases() },
        settle = { owned, verified -> billing.settle(owned.purchaseToken, verified.productType, owned.isAcknowledged) },
    )

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
     * a verify that failed offline, a consume Play refused, a resubscribe made in the Play Store.
     * Idempotent and cheap: call it from `onResume` so a deferred (pending) purchase is credited
     * as soon as the user comes back. Sends claim `sync`, so it never moves a purchase between
     * app accounts.
     */
    suspend fun syncPurchases() {
        billing.syncPurchases()
    }

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
    ): Entitlements = purchase(activity, productId, PurchaseOptions(), kind)

    /**
     * Buy a specific base plan/offer, or replace an explicitly selected owned subscription.
     *
     * Check `purchaseOutcomeConfirmed` on the result before reporting success. `false` means
     * the purchase was paid, verified and settled, but the server did not confirm any access for
     * it: do not offer the same purchase again, run your recovery instead. `null` (an older
     * server) counts as confirmed.
     *
     * Other outcomes, all through the existing error types:
     *  - The payment sheet got no answer within five minutes: [CashSDKError.PurchasePending].
     *    Play may still complete it; the SDK verifies it when it does.
     *  - This Google account already owns the product: nothing is charged, and the owned
     *    purchase is returned with `alreadyOwned = true` when the server confirms it is this
     *    user's. When another app account has it: [CashSDKError.PurchaseNotAttributed] ("already
     *    owned: restore, or sign in to the account that owns it").
     *
     * The host may refresh the signed-in user's token (`identify(sameUser, newToken)`) while the
     * sheet is open; only a different user signing in ends the purchase with `NotIdentified`.
     */
    @JvmOverloads
    suspend fun purchase(
        activity: Activity,
        productId: String,
        options: PurchaseOptions,
        kind: PurchaseKind = PurchaseKind.SUBSCRIPTION,
    ): Entitlements {
        val identity = api.identitySnapshot()
        api.requireValidUserToken(identity)
        val buyer = identity.userId!!
        val result = billing.purchase(activity, productId, kind, options) { api.requireValidUserTokenFor(buyer) }
        // Compare users, not tokens: a settled purchase is still this user's after a token refresh.
        api.requireSameUser(buyer)
        return result
    }

    /** Live Play prices and offers for custom paywalls; no second BillingClient is required. */
    suspend fun products(ids: List<String>): List<com.cashsdk.billing.StoreProduct> = billing.products(ids)

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

    internal suspend fun paywallPurchase(activity: Activity, price: ProductPrice): Entitlements =
        purchase(activity, price.productId, PurchaseOptions(basePlanId = price.basePlanId, offerToken = price.offerToken), price.kind)

    internal fun track(
        event: String,
        placement: String? = null,
        paywall: String? = null,
        product: String? = null,
        properties: Map<String, Any>? = null,
        variant: String? = null,
    ) = emit(event, placement, paywall, product, properties, variant)

    // ── Internals ────────────────────────────────────────────────────────────────

    /** Await a server-confirmed snapshot for the current identity. Throws if identity
     * changes (including A → B → A) while the request is in flight. Entitlements past their
     * `expiresAt` are left out of the result. */
    suspend fun refreshEntitlements(): Entitlements = access.refresh()

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
     * Flush when the app comes back to the foreground, and check entitlements again.
     *
     * This is the trigger that actually recovers an offline session: Android freezes background
     * processes, so a timer scheduled before the user left may never fire, and a queue that only
     * drains on new activity strands the last events of every session. Uses plain
     * `ActivityLifecycleCallbacks` rather than `androidx.lifecycle-process` — this module ships
     * no such dependency.
     *
     * The same goes for access: an expiry timer may not have fired while the app was away, and
     * a refund or cancellation made while it was away is only visible to the server. So the
     * snapshot is judged again at once and re-read (at most every five minutes).
     */
    private fun observeForeground() {
        val application = appContext as? Application ?: return
        val observer = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                if (foreground.activityStarted()) {
                    // A fresh foreground is a fresh chance — don't make the user wait out a
                    // backoff computed while the device had no connectivity.
                    eventFlushFailures.set(0)
                    flushEvents()
                    access.onForeground()
                }
            }

            override fun onActivityStopped(activity: Activity) {
                foreground.activityStopped()
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
        /** `cashsdk_environment` pref: the store environment a previous run learned. */
        const val KEY_OBSERVED_ENVIRONMENT = "observed_environment"
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
