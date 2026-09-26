package com.cashsdk.billing

import android.app.Activity
import android.content.Context
import android.os.SystemClock
import com.cashsdk.AppAccountToken
import com.cashsdk.PurchaseOptions
import com.cashsdk.SubscriptionReplacementMode
import android.util.Log
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.ConsumeParams
import com.android.billingclient.api.acknowledgePurchase
import com.android.billingclient.api.consumePurchase
import com.android.billingclient.api.queryProductDetails
import com.android.billingclient.api.queryPurchasesAsync
import com.cashsdk.CashSDKError
import com.cashsdk.EntitlementManager
import com.cashsdk.model.Entitlements
import com.cashsdk.model.PurchaseKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A resolved Play price for a paywall product row (formatted in the store's locale/currency). */
data class ProductPrice(
    val productId: String,
    val formattedPrice: String,
    val offerToken: String?,
    val title: String,
    /** Discovered from Play (which product type knows this id) — the paywall passes it back to purchase. */
    val kind: PurchaseKind,
    val basePlanId: String? = null,
    val offerId: String? = null,
    val phases: List<PricingPhase> = emptyList(),
)

/** Live store catalog for a custom paywall; prices and available offers come from Play. */
data class StoreProduct(
    val id: String,
    val kind: PurchaseKind,
    val title: String,
    val description: String,
    val offers: List<StoreOffer>,
    /** Null when multiple base plans require the host to choose from offers. */
    val defaultPrice: ProductPrice?,
) {
    /**
     * The free-trial offers Play returned for this user, for [basePlanId] or for every base plan
     * when it is null. Play leaves out offers the user is not eligible for (for example someone
     * who already had the trial), so an empty list means: do not show trial copy. Buying the base
     * plan never starts a trial; pass the chosen offer's `offerToken` to `purchase` for that.
     */
    fun freeTrialOffers(basePlanId: String? = null): List<StoreOffer> =
        offers.filter { it.isFreeTrial && (basePlanId == null || it.basePlanId == basePlanId) }

    /** Whether this user can start a free trial of [basePlanId] (any base plan when null) right now. */
    fun hasFreeTrial(basePlanId: String? = null): Boolean = freeTrialOffers(basePlanId).isNotEmpty()
}

/** A currently-owned Play purchase, normalized for the restore → re-verify loop. */
internal data class OwnedPurchase(
    val productId: String,
    val purchaseToken: String,
    val kind: PurchaseKind,
    val isAcknowledged: Boolean = false,
)

/**
 * The outcome of settling (consuming / acknowledging) a purchase with Google.
 *
 * This used to be discarded. A `BillingResult` that says `SERVICE_DISCONNECTED` means the SKU is
 * still owned: for a consumable the user can never buy that coin pack again, and the server-side
 * sweep does NOT rescue it (the sweep acknowledges, and acknowledging a consumable is precisely
 * the wrong action). Callers now see the failure and can retry or surface it.
 */
internal sealed interface SettleOutcome {
    /** Consumed or acknowledged successfully — or already in that state. */
    data object Settled : SettleOutcome

    /** Google refused. [retryable] distinguishes a transient outage from a terminal verdict. */
    data class Failed(val responseCode: Int, val debugMessage: String?, val retryable: Boolean) : SettleOutcome

    val settled: Boolean get() = this is Settled
}

/**
 * Owns a [BillingClient] for the **SDK-driven** purchase path (the CashSDK paywall's CTA)
 * and the restore query. The **app-driven** path — where the host owns billing and calls
 * `CashSDK.shared.verifyPurchase(...)` from its own `PurchasesUpdatedListener` — does not go
 * through here at all; that's the RevenueCat-style "you drive billing, we verify" model.
 *
 * Server verification goes through [PurchaseCompleter] to [EntitlementManager], so the money
 * path stays in one place (ApiClient + EntitlementStore) and the claim each verify sends is
 * decided there, never here. After a server grant we consume (consumables) or acknowledge
 * (everything else): Play auto-refunds anything left unsettled for 3 days.
 *
 * Two structural rules here, both learned the hard way:
 *  1. **Purchase updates are matched to the request that asked for them**, by product id. A
 *     single-slot bridge let a second `purchase()` orphan the first (which then hung forever),
 *     and let an unrelated out-of-band update complete a waiter — which then verified and
 *     settled the WRONG purchase token.
 *  2. **Every purchase Play tells us about is remembered until it is settled**, and processed
 *     even when nobody is awaiting it. A PENDING purchase that later completes arrives with no
 *     in-flight `purchase()` call; dropping it meant the purchase was never verified and never
 *     acknowledged, so Google auto-refunded it three days later.
 */
internal class BillingManager(
    context: Context,
    /** The current app user id (or null), read at purchase time to attribute the flow. */
    private val userIdProvider: () -> String? = { null },
    /** The money path: server verification and the store. Explicit restore lives there too. */
    access: EntitlementManager,
    /** Monotonic clock for the product cache, so a changed device time cannot extend an entry. */
    monotonicClock: () -> Long = SystemClock::elapsedRealtime,
) {
    /** What Play last said about a product, for the seconds between rendering and the tap. */
    private val productCache = ProductCache<ProductDetails>(monotonicClock)
    private val connectMutex = Mutex()
    private val syncMutex = Mutex()
    private val purchaseGate = PurchaseGate()
    private val openTokensLock = Any()

    /** Verify-then-settle for every Play purchase; it picks each verify's claim. */
    private val completer = PurchaseCompleter(
        access,
        settle = { purchaseToken, productType, isAcknowledged -> settle(purchaseToken, productType, isAcknowledged) },
        forget = { forgetOpenPurchase(it) },
    )

    /** Background work that outlives a single `purchase()` call: out-of-band settlement. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Purchases Play has told us about that are not yet settled (verified + consumed/acknowledged).
     * Persisted, because a PENDING purchase can take days to complete and the process will not
     * survive that.
     */
    private val openPurchases = context.applicationContext
        .getSharedPreferences("cashsdk_open_purchases", Context.MODE_PRIVATE)

    /** One in-flight `purchase()` call, keyed by the product it asked for. */
    private class Waiter(val productId: String, val deferred: CompletableDeferred<Purchase>)

    private val waitersLock = Any()
    private val waiters = mutableListOf<Waiter>()

    private val purchasesListener = PurchasesUpdatedListener { result, purchases ->
        when (result.responseCode) {
            BillingResponseCode.OK -> if (purchases.isNullOrEmpty()) {
                failWaiters(CashSDKError.Billing(BillingResponseCode.ERROR, "Play returned an empty purchase update"))
            } else onPurchasesUpdated(purchases)
            BillingResponseCode.USER_CANCELED -> failWaiters(CashSDKError.PurchaseCancelled)
            else -> failWaiters(
                CashSDKError.Billing(
                    result.responseCode,
                    result.debugMessage,
                    result.onPurchasesUpdatedSubResponseCode,
                ),
            )
        }
    }

    private val billingClient: BillingClient = BillingClient.newBuilder(context.applicationContext)
        .setListener(purchasesListener)
        // Billing 8+ reconnects automatically before API calls after Play disconnects. The
        // explicit connection path remains for first use and older device-side Play services.
        .enableAutoServiceReconnection()
        .enablePendingPurchases(
            // Billing 9 requires an explicit PendingPurchasesParams.
            PendingPurchasesParams.newBuilder().enableOneTimeProducts().build(),
        )
        .build()

    // ── Connection ────────────────────────────────────────────────────────────

    /** Idempotent connect; lazily reconnects if the service dropped. Serialized by a mutex. */
    private suspend fun ensureConnected() = billingDeadline("connection", 15_000) { connect() }

    private suspend fun connect() {
        if (billingClient.isReady) return
        connectMutex.withLock {
            if (billingClient.isReady) return
            suspendCancellableCoroutine<Unit> { cont ->
                billingClient.startConnection(object : BillingClientStateListener {
                    override fun onBillingSetupFinished(result: BillingResult) {
                        if (!cont.isActive) return
                        if (result.responseCode == BillingResponseCode.OK) {
                            cont.resume(Unit)
                        } else {
                            cont.resumeWithException(CashSDKError.Billing(result.responseCode, result.debugMessage))
                        }
                    }

                    override fun onBillingServiceDisconnected() {
                        // The next ensureConnected() call reconnects; don't fail an already-resumed
                        // continuation here. Do drop the product cache: a reconnect can come back
                        // with a different catalog (a price change, another storefront, a different
                        // Google account), and an offer token from the old one is refused.
                        productCache.clear()
                    }
                })
            }
        }
    }

    // ── Prices (for the paywall renderer) ───────────────────────────────────────

    /**
     * Resolve the live, localized Play price for a paywall product without the caller
     * knowing its type: try subscriptions first (most paywalls), then one-time products.
     * The paywall config carries only a `product_id`, so we discover the [PurchaseKind] here.
     */
    suspend fun resolvePrice(productId: String): ProductPrice? {
        ensureConnected()
        return priceFor(productId, PurchaseKind.SUBSCRIPTION) ?: priceFor(productId, PurchaseKind.PRODUCT)
    }

    /** Resolve the live, localized Play price for a paywall product of a known kind; null if unavailable. */
    suspend fun priceFor(productId: String, kind: PurchaseKind): ProductPrice? {
        ensureConnected()
        // The paywall shows this price, so it takes Play's current answer rather than a cached
        // one. It also primes the cache that the Buy tap then reads.
        val details = queryProductDetails(productId, kind, fresh = true) ?: return null
        return when (kind) {
            PurchaseKind.SUBSCRIPTION -> {
                val offer = chooseOffer(details, PurchaseOptions())
                offerPrice(details, offer)
            }
            PurchaseKind.PRODUCT ->
                ProductPrice(
                    productId,
                    details.oneTimePurchaseOfferDetails?.formattedPrice.orEmpty(),
                    null,
                    details.name,
                    kind,
                )
        }
    }

    suspend fun products(ids: List<String>): List<StoreProduct> {
        require(ids.all { it.isNotBlank() }) { "Product ids must be nonblank" }
        ensureConnected()
        return ids.distinct().mapNotNull { id ->
            val subscription = queryProductDetails(id, PurchaseKind.SUBSCRIPTION, fresh = true)
            val details = subscription ?: queryProductDetails(id, PurchaseKind.PRODUCT, fresh = true) ?: return@mapNotNull null
            val kind = if (subscription != null) PurchaseKind.SUBSCRIPTION else PurchaseKind.PRODUCT
            val offers = storeOffers(details)
            val price = if (subscription != null) {
                val basePlans = offers.filter { it.offerId == null }
                if (basePlans.size == 1) offerPrice(details, basePlans.single()) else null
            } else
                ProductPrice(id, details.oneTimePurchaseOfferDetails?.formattedPrice.orEmpty(), null, details.name, kind)
            StoreProduct(id, kind, details.title, details.description, offers, price)
        }
    }

    private fun storeOffers(details: ProductDetails): List<StoreOffer> = details.subscriptionOfferDetails.orEmpty().map { offer ->
        StoreOffer(offer.basePlanId, offer.offerId, offer.offerToken, offer.pricingPhases.pricingPhaseList.map { phase ->
            PricingPhase(phase.formattedPrice, phase.priceAmountMicros, phase.priceCurrencyCode, phase.billingPeriod, phase.billingCycleCount, phase.recurrenceMode)
        }, offer.offerTags)
    }

    private fun chooseOffer(details: ProductDetails, options: PurchaseOptions): StoreOffer {
        val offers = storeOffers(details)
        return selectOffer(offers, options)
    }

    private fun offerPrice(details: ProductDetails, offer: StoreOffer): ProductPrice = ProductPrice(
        details.productId, offer.regularPhase?.formattedPrice.orEmpty(), offer.offerToken, details.name,
        PurchaseKind.SUBSCRIPTION, offer.basePlanId, offer.offerId, offer.phases,
    )

    // ── Purchase (SDK-driven paywall CTA) ────────────────────────────────────────

    /**
     * Launch the Play purchase flow for [productId], verify the resulting token server-side,
     * settle it, and return the fresh snapshot. Throws [CashSDKError.PurchaseCancelled] /
     * [CashSDKError.PurchasePending] / [CashSDKError.Billing] as appropriate.
     *
     * A [CashSDKError.PurchasePending] is NOT a lost purchase: the token is persisted, and when
     * Google settles it the SDK verifies and settles it out of band and the host sees the new
     * entitlements on `entitlementUpdates`. Call [syncPurchases] from `onResume` to pick that up
     * promptly. The same goes when Play has not answered within five minutes: that is reported
     * as pending, because the payment may still complete.
     *
     * When this Google account already owns the product, nothing is charged: the owned purchase
     * is verified for this user and returned with `alreadyOwned = true`, or
     * [CashSDKError.PurchaseNotAttributed] is thrown when another app account has it.
     */
    suspend fun purchase(activity: Activity, productId: String, kind: PurchaseKind, options: PurchaseOptions = PurchaseOptions(), validateIdentity: () -> Unit = {}): Entitlements {
        return purchaseGate.run {
            try {
                purchaseInner(activity, productId, kind, options, validateIdentity)
            } catch (stale: StaleOffer) {
                // Play refused the offer token before opening the sheet, so nothing was charged.
                // The token came from details we cached when the paywall rendered; Play has since
                // regenerated it. Drop the entry and go once more with a fresh lookup, which is
                // what the customer expects from the tap they already made.
                productCache.invalidate(productId, kind)
                purchaseInner(activity, productId, kind, options, validateIdentity)
            }
        }
    }

    /**
     * `launchBillingFlow` was refused with `DEVELOPER_ERROR`, the answer Play gives for an offer
     * token it no longer recognises. Private to this class: it never reaches a caller, because
     * the one retry above either succeeds or throws the real error from the second attempt.
     */
    private class StaleOffer(val billing: CashSDKError.Billing) : Exception()

    private suspend fun purchaseInner(activity: Activity, productId: String, kind: PurchaseKind, options: PurchaseOptions, validateIdentity: () -> Unit): Entitlements {
        validateIdentity()
        val buyer = userIdProvider() ?: throw CashSDKError.NotIdentified
        if (kind != PurchaseKind.SUBSCRIPTION && (options.basePlanId != null || options.offerId != null || options.offerToken != null || options.oldPurchaseToken != null || options.subscriptionFamily.isNotEmpty())) {
            throw CashSDKError.InvalidPurchaseOptions("Subscription selectors cannot be used for a one-time product")
        }
        ensureConnected()
        // Whether the offer token about to be used came from the cache, which decides whether a
        // refused launch is worth one retry with fresh details.
        val servedFromCache = productCache.get(productId, kind) != null
        val details = queryProductDetails(productId, kind)
            ?: throw CashSDKError.ProductNotFound(listOf(productId))

        val offer = if (kind == PurchaseKind.SUBSCRIPTION) chooseOffer(details, options) else null
        val replacement = if (offer != null) {
            val owned = queryPurchases(BillingClient.ProductType.SUBS)
            val candidates = owned.map {
                ReplacementCandidate(it.products, it.purchaseToken, it.accountIdentifiers?.obfuscatedAccountId, it.purchaseState == Purchase.PurchaseState.PURCHASED)
            }
            val resolved = resolveReplacement(productId, offer, candidates, buyer, options) { held ->
                // The account holds this subscription (or one in its family) under a token that
                // is not this user's. Ask the server whose it is before anything is charged.
                val purchase = owned.first { it.purchaseToken == held.purchaseToken }
                syncMutex.withLock {
                    completer.confirmOwnership(purchase.products.single(), purchase.toPlayPurchase(), PurchaseKind.SUBSCRIPTION, buyer)
                }
            }
            when (resolved) {
                // Shared with this user from another app account: they have the access, and the
                // subscription is not theirs to change. Nothing is bought.
                is ReplacementResolution.Shared -> return resolved.entitlements.copy(alreadyOwned = true)
                is ReplacementResolution.Buy -> resolved.replacement
            }
        } else null

        val waiter = Waiter(productId, CompletableDeferred())
        synchronized(waitersLock) { waiters += waiter }

        var alreadyOwned: CashSDKError.Billing? = null
        val purchase = try {
            // launchBillingFlow must run on the main thread.
            val launch = withContext(Dispatchers.Main) {
                validateIdentity()
                if (userIdProvider() != buyer) throw CashSDKError.NotIdentified
                if (activity.isFinishing || activity.isDestroyed) throw CashSDKError.InvalidPurchaseOptions("A foreground Activity is required to open Google Play")
                billingClient.launchBillingFlow(activity, buildFlowParams(details, offer?.offerToken, replacement, options, buyer))
            }
            if (launch.responseCode != BillingResponseCode.OK) {
                val billing = CashSDKError.Billing(
                    launch.responseCode,
                    launch.debugMessage,
                    launch.onPurchasesUpdatedSubResponseCode,
                )
                // Nothing is charged by a refused launch, so one retry with fresh details is safe.
                // Only on the FIRST attempt: `purchase` invalidates the entry before retrying, so
                // a second refusal arrives with details straight from Play and is the real answer.
                if (launch.responseCode == BillingResponseCode.DEVELOPER_ERROR && servedFromCache) throw StaleOffer(billing)
                throw billing
            }
            awaitPurchaseUpdate(PURCHASE_CONFIRMATION_TIMEOUT_MS) { waiter.deferred.await() }
        } catch (error: CashSDKError.Billing) {
            if (error.responseCode != BillingResponseCode.ITEM_ALREADY_OWNED) throw error
            alreadyOwned = error
            null
        } finally {
            synchronized(waitersLock) { waiters -= waiter }
        }
        if (purchase == null) return recoverAlreadyOwned(productId, kind, buyer, alreadyOwned ?: CashSDKError.Billing(BillingResponseCode.ITEM_ALREADY_OWNED))

        if (purchase.purchaseState == Purchase.PurchaseState.PENDING) throw CashSDKError.PurchasePending
        if (userIdProvider() != buyer) throw CashSDKError.PurchaseNotAttributed
        return completer.completeSheetPurchase(productId, buyer, purchase.toPlayPurchase(), kind)
    }

    /**
     * Play answered ITEM_ALREADY_OWNED, so nothing was charged. Verify what this Google account
     * owns for [productId] with claim `sync` (see [resolveAlreadyOwned]): the user's own purchase
     * comes back as their entitlements, another account's as [CashSDKError.PurchaseNotAttributed].
     * It used to surface as a bare `Billing(7)` with nothing recovered.
     *
     * For a consumable this finishes the earlier purchase that was never consumed, so the user
     * gets those units and can buy again; the result says `alreadyOwned` because this attempt
     * charged nothing.
     */
    private suspend fun recoverAlreadyOwned(productId: String, kind: PurchaseKind, buyer: String, cause: CashSDKError): Entitlements =
        syncMutex.withLock {
            val owned = queryPurchases(kind.toPlayType()).filter { productId in it.products }
            val candidates = owned.map {
                OwnedCandidate(it, it.accountIdentifiers?.obfuscatedAccountId, it.purchaseState == Purchase.PurchaseState.PURCHASED)
            }
            resolveAlreadyOwned(buyer, candidates, cause, userIdProvider) { purchase ->
                // Only a purchase that may be this user's is tracked for later settlement;
                // another account's is theirs to settle.
                if (purchaseOwnership(buyer, purchase.accountIdentifiers?.obfuscatedAccountId) != PurchaseOwnership.OTHER_TOKEN) {
                    rememberOpenPurchase(purchase.purchaseToken)
                }
                completer.confirmOwnership(productId, purchase.toPlayPurchase(), kind, buyer)
            }.copy(alreadyOwned = true)
        }

    // ── Out-of-band purchase updates ────────────────────────────────────────────

    private fun onPurchasesUpdated(purchases: List<Purchase>) {
        var orphaned = false
        for (purchase in purchases) {
            rememberOpenPurchase(purchase.purchaseToken)
            val waiter = takeWaiterFor(purchase)
            if (waiter != null) waiter.deferred.complete(purchase) else orphaned = true
        }
        // A purchase nobody is awaiting — a PENDING purchase that just settled, a purchase made
        // on another device, a promoted Play Store purchase, or one whose `purchase()` call died
        // with the process. It must still be verified and settled or Google auto-refunds it.
        // Routed through the drain so the product TYPE (subs vs in-app) is resolved from Play
        // rather than guessed.
        if (orphaned) scope.launch { runCatching { syncPurchases() } }
    }

    private fun takeWaiterFor(purchase: Purchase): Waiter? = synchronized(waitersLock) {
        // Match on product, never "whichever waiter is around" — completing a waiter with an
        // unrelated purchase made it verify and settle a token it never asked for.
        val index = waiters.indexOfFirst { it.productId in purchase.products }
        if (index < 0) null else waiters.removeAt(index)
    }

    private fun failWaiters(error: Throwable) {
        val snapshot = synchronized(waitersLock) {
            val copy = waiters.toList()
            waiters.clear()
            copy
        }
        snapshot.forEach { it.deferred.completeExceptionally(error) }
    }

    /**
     * Re-check every purchase Play knows about and finish anything unsettled: a pending purchase
     * that completed, a purchase whose verify failed, one whose consume was refused by a
     * transient Play outage, one bought outside the app with no account token. Idempotent and
     * safe to call often; call it from `onResume`. Every verify sends claim `sync`, so this never
     * moves a purchase between app accounts (see [syncOwnedPurchases]).
     */
    /**
     * [syncPurchases], unless one finished within [minIntervalMs].
     *
     * What `identify` calls. Hosts identify on every launch, on every token refresh and often on
     * resume, and each pass queries Play twice and then sends one server verify per purchase the
     * Google account owns. Doing that again seconds later answers nothing new. The public
     * `syncPurchases()` is never throttled: a host calling it is asking on purpose.
     */
    suspend fun syncPurchasesIfStale(minIntervalMs: Long = SYNC_MIN_INTERVAL_MS) {
        val last = lastSyncFinishedAt
        val now = SystemClock.elapsedRealtime()
        if (last != null && now >= last && now - last < minIntervalMs) return
        syncPurchases()
    }

    /** [SystemClock.elapsedRealtime] when a sync last finished, successfully or not. */
    @Volatile
    private var lastSyncFinishedAt: Long? = null

    suspend fun syncPurchases() {
        try {
            syncPurchasesInner()
        } finally {
            // Recorded even on failure: a pass that could not reach Play or the API will not do
            // better if it is retried a second later, and the next foreground or identify tries
            // again anyway.
            lastSyncFinishedAt = SystemClock.elapsedRealtime()
        }
    }

    private suspend fun syncPurchasesInner() {
        syncMutex.withLock {
            val userId = userIdProvider() ?: return@withLock
            ensureConnected()
            val trackedBeforeQuery = openPurchaseTokens().toSet()
            val live = queryAllPurchases()
            val liveTokens = live.mapTo(mutableSetOf()) { it.first.purchaseToken }
            // Forget what Play no longer reports: consumed, refunded, or a pending purchase whose
            // 3-day window lapsed. Otherwise the open set grows without bound.
            // A callback received during the query may describe a newer purchase than its result.
            trackedBeforeQuery.filterNot { it in liveTokens }.forEach { forgetOpenPurchase(it) }

            // Acknowledgement only settles with Google; it does not prove this install has
            // reported the purchase, so owned purchases are re-verified even after the server
            // acked them.
            syncOwnedPurchases(
                userId = userId,
                live = live.map { (purchase, kind) ->
                    SyncCandidate(
                        handle = purchase to kind,
                        purchaseToken = purchase.purchaseToken,
                        productId = purchase.products.firstOrNull(),
                        accountToken = purchase.accountIdentifiers?.obfuscatedAccountId,
                        purchased = purchase.purchaseState == Purchase.PurchaseState.PURCHASED,
                    )
                },
                currentUser = userIdProvider,
                remember = ::rememberOpenPurchase,
                denied = deniedTokens(userId),
                deny = { token -> rememberDenied(userId, token) },
            ) { (purchase, kind), productId, requireOwner ->
                completer.completeSync(productId, purchase.toPlayPurchase(), kind, requireOwner)
            }
        }
    }

    // ── Restore ─────────────────────────────────────────────────────────────────

    /** Re-query owned subscriptions + one-time products for the restore → re-verify loop. */
    suspend fun queryOwnedPurchases(): List<OwnedPurchase> {
        ensureConnected()
        val subs = ownedOf(BillingClient.ProductType.SUBS, PurchaseKind.SUBSCRIPTION)
        val inApp = ownedOf(BillingClient.ProductType.INAPP, PurchaseKind.PRODUCT)
        return subs + inApp
    }

    private suspend fun ownedOf(playType: String, kind: PurchaseKind): List<OwnedPurchase> {
        return queryPurchases(playType)
            .filter { it.purchaseState == Purchase.PurchaseState.PURCHASED }
            .flatMap { p -> p.products.map { OwnedPurchase(it, p.purchaseToken, kind, p.isAcknowledged) } }
    }

    /** Every purchase Play knows about, in any state, tagged with the product type it came from. */
    private suspend fun queryAllPurchases(): List<Pair<Purchase, PurchaseKind>> {
        suspend fun of(playType: String, kind: PurchaseKind): List<Pair<Purchase, PurchaseKind>> {
            return queryPurchases(playType).map { it to kind }
        }
        return of(BillingClient.ProductType.SUBS, PurchaseKind.SUBSCRIPTION) +
            of(BillingClient.ProductType.INAPP, PurchaseKind.PRODUCT)
    }

    private suspend fun queryPurchases(playType: String): List<Purchase> {
        val result = billingDeadline("owned purchases", 15_000) {
            billingClient.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(playType).build())
        }
        requireQuerySuccess(result.billingResult)
        return result.purchasesList
    }

    // ── Open-purchase bookkeeping ───────────────────────────────────────────────

    private fun openPurchaseTokens(): Set<String> =
        openPurchases.getStringSet(KEY_OPEN, emptySet()).orEmpty()

    private fun rememberOpenPurchase(token: String): Unit = synchronized(openTokensLock) {
        val next = openPurchaseTokens().toMutableSet()
        if (!next.add(token)) return
        openPurchases.edit().putStringSet(KEY_OPEN, next).apply()
    }

    private fun forgetOpenPurchase(token: String): Unit = synchronized(openTokensLock) {
        val next = openPurchaseTokens().toMutableSet()
        if (!next.remove(token)) return
        openPurchases.edit().putStringSet(KEY_OPEN, next).apply()
    }

    /**
     * Purchases the server has told THIS user belong to another app account.
     *
     * Recorded per user, so signing in as the owner still verifies the purchase. Without this a
     * purchase carrying no account token (a Play Store resubscribe, a promo code) was re-verified
     * on every sync, for the life of the install, always to be told the same thing.
     */
    private fun deniedTokens(userId: String): Set<String> = synchronized(openTokensLock) {
        openPurchases.getStringSet(KEY_DENIED, emptySet()).orEmpty()
            .mapNotNullTo(mutableSetOf()) { entry ->
                entry.substringBefore(DENIED_SEPARATOR).takeIf { it == userId }
                    ?.let { entry.substringAfter(DENIED_SEPARATOR) }
            }
    }

    private fun rememberDenied(userId: String, token: String): Unit = synchronized(openTokensLock) {
        val next = openPurchases.getStringSet(KEY_DENIED, emptySet()).orEmpty().toMutableSet()
        // Bounded: a device that keeps meeting other people's purchases must not grow this set
        // without end. The oldest entries go first, and a dropped one only costs one verify.
        if (next.size >= MAX_DENIED) next.remove(next.first())
        if (!next.add("$userId$DENIED_SEPARATOR$token")) return
        openPurchases.edit().putStringSet(KEY_DENIED, next).apply()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Play's `ProductDetails` for one product, from the cache when it is fresh.
     *
     * [fresh] forces a round trip: the paywall's own price read wants Play's current answer, and
     * a purchase retrying after a refused offer token must not be handed the entry that was just
     * refused.
     */
    private suspend fun queryProductDetails(productId: String, kind: PurchaseKind, fresh: Boolean = false): ProductDetails? {
        if (!fresh) productCache.get(productId, kind)?.let { return it }
        val product = QueryProductDetailsParams.Product.newBuilder()
            .setProductId(productId)
            .setProductType(kind.toPlayType())
            .build()
        val params = QueryProductDetailsParams.newBuilder().setProductList(listOf(product)).build()
        val result = billingDeadline("product lookup", 15_000) { billingClient.queryProductDetails(params) }
        requireQuerySuccess(result.billingResult)
        return result.productDetailsList?.firstOrNull()?.also { productCache.put(productId, kind, it) }
    }

    private fun requireQuerySuccess(result: BillingResult) {
        if (result.responseCode != BillingResponseCode.OK) {
            throw CashSDKError.Billing(result.responseCode, result.debugMessage)
        }
    }

    private fun buildFlowParams(details: ProductDetails, offerToken: String?, replacement: SelectedReplacement?, options: PurchaseOptions, buyer: String): BillingFlowParams {
        val productParams = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(details)
            .apply { offerToken?.let { setOfferToken(it) } }
            .apply {
                replacement?.let {
                    setSubscriptionProductReplacementParams(
                        BillingFlowParams.ProductDetailsParams.SubscriptionProductReplacementParams.newBuilder()
                            .setOldProductId(it.productId)
                            .setReplacementMode(it.mode.playValue())
                            .build(),
                    )
                }
            }
            .build()
        return BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(productParams))
            .setIsOfferPersonalized(options.isOfferPersonalized)
            .apply {
                replacement?.let {
                    setSubscriptionUpdateParams(BillingFlowParams.SubscriptionUpdateParams.newBuilder().setOldPurchaseToken(it.purchaseToken).build())
                }
                // Stamp the canonical account token so Play returns it as
                // obfuscatedExternalAccountId on the purchase and every async RTDN / voided-
                // purchase event attributes to this user server-side. Without it, Play purchases
                // arrived with no account id and could not be mapped back to a user.
                setObfuscatedAccountId(AppAccountToken.derive(buyer))
            }
            .build()
    }

    private fun SubscriptionReplacementMode.playValue(): Int = when (this) {
        SubscriptionReplacementMode.WITH_TIME_PRORATION -> BillingFlowParams.ProductDetailsParams.SubscriptionProductReplacementParams.ReplacementMode.WITH_TIME_PRORATION
        SubscriptionReplacementMode.CHARGE_PRORATED_PRICE -> BillingFlowParams.ProductDetailsParams.SubscriptionProductReplacementParams.ReplacementMode.CHARGE_PRORATED_PRICE
        SubscriptionReplacementMode.WITHOUT_PRORATION -> BillingFlowParams.ProductDetailsParams.SubscriptionProductReplacementParams.ReplacementMode.WITHOUT_PRORATION
        SubscriptionReplacementMode.CHARGE_FULL_PRICE -> BillingFlowParams.ProductDetailsParams.SubscriptionProductReplacementParams.ReplacementMode.CHARGE_FULL_PRICE
        SubscriptionReplacementMode.DEFERRED -> BillingFlowParams.ProductDetailsParams.SubscriptionProductReplacementParams.ReplacementMode.DEFERRED
    }

    /**
     * Settle a purchase — the terminal step Google requires after a grant.
     *
     * Picking the wrong action is a real failure, not a nicety:
     *  - **CONSUME** (`consumable`) releases the entitlement so the user can buy it
     *    AGAIN. Merely acknowledging a consumable leaves Play believing it is still
     *    owned, and every repeat purchase of that coin pack fails forever.
     *  - **ACKNOWLEDGE** (everything else) for subscriptions and non-consumables. Play
     *    AUTO-REFUNDS anything neither consumed nor acknowledged within 3 days.
     *
     * Consuming implicitly acknowledges, so a consumable needs only the consume call.
     * [productType] comes from OUR catalog via the verify response — Play's own API
     * cannot tell consumable from non-consumable. Missing/unknown types remain unsettled
     * and retryable: acknowledgement is not reversible and must not guess the catalog.
     *
     * Transient failures are retried with backoff before giving up: a `SERVICE_DISCONNECTED`
     * from Play used to be swallowed silently, leaving a consumable owned-and-unbuyable with
     * nothing anywhere that would ever retry it (the server-side sweep only acknowledges).
     * A call Play does not answer in time is one of those transient failures (see
     * [settleWithRetries]); it no longer fails a purchase the server already credited.
     *
     * Also called from `restore()`, because a purchase whose app died between the store
     * charge and this call is still open and would otherwise be auto-refunded.
     */
    internal suspend fun settle(
        purchaseToken: String,
        productType: String?,
        isAcknowledged: Boolean,
    ): SettleOutcome {
        val consumable = requiresConsumption(productType)
        // A non-consumable Play already knows about needs nothing further.
        if (!consumable && isAcknowledged) return SettleOutcome.Settled

        return settleWithRetries {
            withTimeoutOrNull(SETTLE_TIMEOUT_MS) {
                val result = if (consumable) {
                    billingClient.consumePurchase(
                        ConsumeParams.newBuilder().setPurchaseToken(purchaseToken).build(),
                    ).billingResult
                } else {
                    billingClient.acknowledgePurchase(
                        AcknowledgePurchaseParams.newBuilder().setPurchaseToken(purchaseToken).build(),
                    )
                }
                SettleAttempt(result.responseCode, result.debugMessage)
            }
        }
    }

    private fun PurchaseKind.toPlayType(): String = when (this) {
        PurchaseKind.SUBSCRIPTION -> BillingClient.ProductType.SUBS
        PurchaseKind.PRODUCT -> BillingClient.ProductType.INAPP
    }

    fun close() {
        failWaiters(CashSDKError.Billing(BillingResponseCode.SERVICE_DISCONNECTED, "SDK shut down"))
        scope.cancel()
        runCatching { billingClient.endConnection() }
    }

    private companion object {
        /** How recently a sync must have run for `identify` to skip another one. */
        const val SYNC_MIN_INTERVAL_MS = 60_000L

        const val KEY_OPEN = "open_tokens"
        const val KEY_DENIED = "denied_tokens"

        /** A purchase token cannot contain this, so it is safe as a key separator. */
        const val DENIED_SEPARATOR = "|"

        /** How many ownership verdicts are remembered before the oldest is dropped. */
        const val MAX_DENIED = 64
        const val SETTLE_TIMEOUT_MS = 15_000L

        /** How long an open payment sheet is awaited before the purchase is reported as pending. */
        const val PURCHASE_CONFIRMATION_TIMEOUT_MS = 300_000L
    }
}
