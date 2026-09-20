package com.cashsdk.billing

import android.app.Activity
import android.content.Context
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
import com.cashsdk.model.Entitlements
import com.cashsdk.model.PurchaseKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
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
)

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
 * Server verification is delegated back to the facade via [verify] so the money path stays
 * in one place (ApiClient + EntitlementStore). After a server grant we consume (consumables) or
 * acknowledge (everything else) — Play auto-refunds anything left unsettled for 3 days.
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
    /** Delegates to `CashSDK.shared.verifyPurchase` — verify server-side + update the store. */
    private val verify: suspend (productId: String, purchaseToken: String, kind: PurchaseKind) -> Entitlements,
) {
    private val connectMutex = Mutex()
    private val syncMutex = Mutex()
    private val purchaseGate = PurchaseGate()
    private val openTokensLock = Any()

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
                        // No-op: the next ensureConnected() call reconnects. Don't fail an
                        // already-resumed continuation here.
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
        val details = queryProductDetails(productId, kind) ?: return null
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
            val subscription = queryProductDetails(id, PurchaseKind.SUBSCRIPTION)
            val details = subscription ?: queryProductDetails(id, PurchaseKind.PRODUCT) ?: return@mapNotNull null
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
     * promptly.
     */
    suspend fun purchase(activity: Activity, productId: String, kind: PurchaseKind, options: PurchaseOptions = PurchaseOptions(), validateIdentity: () -> Unit = {}): Entitlements {
        return purchaseGate.run { purchaseInner(activity, productId, kind, options, validateIdentity) }
    }

    private suspend fun purchaseInner(activity: Activity, productId: String, kind: PurchaseKind, options: PurchaseOptions, validateIdentity: () -> Unit): Entitlements {
        validateIdentity()
        val buyer = userIdProvider() ?: throw CashSDKError.NotIdentified
        if (kind != PurchaseKind.SUBSCRIPTION && (options.basePlanId != null || options.offerId != null || options.offerToken != null || options.oldPurchaseToken != null)) {
            throw CashSDKError.InvalidPurchaseOptions("Subscription selectors cannot be used for a one-time product")
        }
        ensureConnected()
        val details = queryProductDetails(productId, kind)
            ?: throw CashSDKError.ProductNotFound(listOf(productId))

        val offer = if (kind == PurchaseKind.SUBSCRIPTION) chooseOffer(details, options) else null
        val replacement = if (offer != null) {
            val owned = queryPurchases(BillingClient.ProductType.SUBS)
            selectReplacement(productId, offer, owned.map {
                ReplacementCandidate(it.products, it.purchaseToken, it.accountIdentifiers?.obfuscatedAccountId, it.purchaseState == Purchase.PurchaseState.PURCHASED)
            }, buyer, options)
        } else null

        val waiter = Waiter(productId, CompletableDeferred())
        synchronized(waitersLock) { waiters += waiter }

        val purchase = try {
            // launchBillingFlow must run on the main thread.
            val launch = withContext(Dispatchers.Main) {
                validateIdentity()
                if (userIdProvider() != buyer) throw CashSDKError.NotIdentified
                if (activity.isFinishing || activity.isDestroyed) throw CashSDKError.InvalidPurchaseOptions("A foreground Activity is required to open Google Play")
                billingClient.launchBillingFlow(activity, buildFlowParams(details, offer?.offerToken, replacement, options, buyer))
            }
            if (launch.responseCode != BillingResponseCode.OK) {
                throw CashSDKError.Billing(
                    launch.responseCode,
                    launch.debugMessage,
                    launch.onPurchasesUpdatedSubResponseCode,
                )
            }
            billingDeadline("purchase confirmation", 300_000) { waiter.deferred.await() }
        } finally {
            synchronized(waitersLock) { waiters -= waiter }
        }

        if (purchase.purchaseState == Purchase.PurchaseState.PENDING) throw CashSDKError.PurchasePending
        if (userIdProvider() != buyer) throw CashSDKError.PurchaseNotAttributed
        return completePurchase(productId, purchase, kind)
    }

    /** Verify a settled purchase server-side, then consume/acknowledge it with Google. */
    private suspend fun completePurchase(
        productId: String,
        purchase: Purchase,
        kind: PurchaseKind,
    ): Entitlements {
        val entitlements = verify(productId, purchase.purchaseToken, kind) // server grant
        // The server says Google has not settled the payment. Consuming or acknowledging now
        // would finalize a purchase that may still fail — leave it open and re-check later.
        if (entitlements.pending) return entitlements
        val outcome = settle(purchase.purchaseToken, entitlements.productType, purchase.isAcknowledged)
        if (outcome.settled) forgetOpenPurchase(purchase.purchaseToken)
        else if (outcome is SettleOutcome.Failed && !outcome.retryable) {
            // Terminal (e.g. DEVELOPER_ERROR, ITEM_NOT_OWNED on a subscription): retrying every
            // launch would never succeed, so stop tracking it — but surface it to the caller.
            forgetOpenPurchase(purchase.purchaseToken)
            throw CashSDKError.Billing(outcome.responseCode, outcome.debugMessage)
        }
        return entitlements
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
     * transient Play outage. Idempotent and safe to call often — call it from `onResume`.
     */
    suspend fun syncPurchases() = syncMutex.withLock {
        val userId = userIdProvider() ?: return@withLock
        ensureConnected()
        val trackedBeforeQuery = openPurchaseTokens().toSet()
        val live = queryAllPurchases()
        val liveTokens = live.mapTo(mutableSetOf()) { it.first.purchaseToken }
        // Forget what Play no longer reports: consumed, refunded, or a pending purchase whose
        // 3-day window lapsed. Otherwise the open set grows without bound.
        // A callback received during the query may describe a newer purchase than its result.
        trackedBeforeQuery.filterNot { it in liveTokens }.forEach { forgetOpenPurchase(it) }

        for ((purchase, kind) in live) {
            if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) {
                // Still pending — keep tracking it so we notice when it settles.
                rememberOpenPurchase(purchase.purchaseToken)
                continue
            }
            if (userIdProvider() != userId) return@withLock
            // Acknowledgement only settles with Google; it does not prove this install has
            // reported the purchase. Re-verify owned purchases even after the server acked.
            // Foreign/legacy account tokens require the host's explicit restore action.
            if (!canAutomaticallySyncPurchase(userId, purchase.accountIdentifiers?.obfuscatedAccountId)) continue
            val productId = purchase.products.firstOrNull() ?: continue
            // Track it BEFORE trying: the server acknowledges Play purchases itself, so a
            // consumable whose local consume fails would otherwise look "acknowledged, nothing
            // to do" on the next pass and stay owned-and-unbuyable forever. completePurchase
            // forgets it once it is genuinely settled (or terminally unsettleable).
            rememberOpenPurchase(purchase.purchaseToken)
            try {
                completePurchase(productId, purchase, kind)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Leave tracked for the next sync; one failed purchase must not hide the rest.
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

    // ── Helpers ───────────────────────────────────────────────────────────────

    private suspend fun queryProductDetails(productId: String, kind: PurchaseKind): ProductDetails? {
        val product = QueryProductDetailsParams.Product.newBuilder()
            .setProductId(productId)
            .setProductType(kind.toPlayType())
            .build()
        val params = QueryProductDetailsParams.newBuilder().setProductList(listOf(product)).build()
        val result = billingDeadline("product lookup", 15_000) { billingClient.queryProductDetails(params) }
        requireQuerySuccess(result.billingResult)
        return result.productDetailsList?.firstOrNull()
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

        var delayMs = SETTLE_RETRY_BASE_MS
        var last: SettleOutcome = SettleOutcome.Settled
        repeat(SETTLE_ATTEMPTS) { attempt ->
            val result = billingDeadline("purchase settlement", 15_000) {
                if (consumable) {
                    billingClient.consumePurchase(
                        ConsumeParams.newBuilder().setPurchaseToken(purchaseToken).build(),
                    ).billingResult
                } else {
                    billingClient.acknowledgePurchase(
                        AcknowledgePurchaseParams.newBuilder().setPurchaseToken(purchaseToken).build(),
                    )
                }
            }
            last = classify(result)
            if (last.settled) return last
            val failed = last as SettleOutcome.Failed
            if (!failed.retryable || attempt == SETTLE_ATTEMPTS - 1) return last
            delay(delayMs)
            delayMs *= 2
        }
        return last
    }

    private fun classify(result: BillingResult): SettleOutcome = when (result.responseCode) {
        BillingResponseCode.OK -> SettleOutcome.Settled
        // Already gone from Play's point of view: consumed by an earlier attempt whose response
        // we never saw, or refunded. Either way there is nothing left to settle.
        BillingResponseCode.ITEM_NOT_OWNED -> SettleOutcome.Settled
        BillingResponseCode.SERVICE_DISCONNECTED,
        BillingResponseCode.SERVICE_UNAVAILABLE,
        BillingResponseCode.NETWORK_ERROR,
        BillingResponseCode.ERROR,
        -> SettleOutcome.Failed(result.responseCode, result.debugMessage, retryable = true)
        // DEVELOPER_ERROR, BILLING_UNAVAILABLE, FEATURE_NOT_SUPPORTED, ITEM_UNAVAILABLE… —
        // retrying cannot change the answer.
        else -> SettleOutcome.Failed(result.responseCode, result.debugMessage, retryable = false)
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
        const val KEY_OPEN = "open_tokens"
        const val SETTLE_ATTEMPTS = 3
        const val SETTLE_RETRY_BASE_MS = 500L
    }
}
