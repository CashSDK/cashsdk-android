package com.cashsdk.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// ─────────────────────────────────────────────────────────────────────────────
// Wire + public models. All server-facing shapes mirror the CashSDK REST contract
// (apps/api): the entitlement snapshot, the purchase-verify body, the events body,
// and the paywall-resolve response. Kept in one focused file so the contract is
// reviewable at a glance.
// ─────────────────────────────────────────────────────────────────────────────

/**
 * A single active entitlement in the resolved snapshot. Mirrors the server's
 * `EntitlementSnapshot.entitlements[]` (identifier, name, rank, source).
 * `rank` powers tier comparison (higher wins); `source` is how it was granted
 * (`subscription` | `purchase` | `manual_grant`).
 */
@Serializable
data class Entitlement(
    val identifier: String,
    val name: String = identifier,
    val rank: Int? = null,
    val source: String = "unknown",
)

/**
 * The current entitlement snapshot for the identified user — the offline-valid
 * gating truth. Returned by `verifyPurchase`/`restore` and cached by
 * [com.cashsdk.entitlements.EntitlementStore].
 *
 * Wire shape (GET /v1/entitlements, POST /v1/purchases:verify):
 * `{ "entitlements": [...], "tier": <int rank>, "tierIdentifier": <string|null> }`.
 * The JSON key is `entitlements`; the Kotlin property is [active] to read naturally
 * at call sites (`CashSDK.shared.entitlements.active`).
 */
@Serializable
data class Entitlements(
    @SerialName("entitlements") val active: List<Entitlement> = emptyList(),
    val tier: Int = 0,
    val tierIdentifier: String? = null,
    /** Spendable one-time-purchase balances (coin packs, credits). */
    val consumables: List<ConsumableBalance> = emptyList(),
    /**
     * Catalog product type of the transaction just verified:
     * `auto_renewable | non_renewing | consumable | non_consumable`.
     * Present only on a verify response — the SDK needs it to finish the purchase
     * correctly (consumables must be CONSUMED, everything else ACKNOWLEDGED).
     */
    val productType: String? = null,
    /** Units bought in the verified transaction. */
    val quantity: Int = 1,
    /**
     * `true` when Google has NOT settled the payment yet (deferred / pending purchase:
     * cash-at-a-convenience-store, parental approval, SCA). The server grants nothing for a
     * pending purchase, so the snapshot beside this flag is EMPTY — which is exactly why the
     * flag has to exist. Without it "pending" and "settled, holds nothing" are the same wire
     * shape, and the SDK overwrote a paying user's cached entitlements with an empty snapshot
     * every time a pending purchase came through.
     *
     * A pending purchase must NOT be acknowledged or consumed; wait for Google to settle it.
     */
    val pending: Boolean = false,
    /** Whether the server already acknowledged this purchase with Google (verify responses). */
    val acknowledged: Boolean? = null,
    /**
     * `true` when the receipt is real but registered to a DIFFERENT app user — a purchase
     * restored from another Google account. Informational, **not** an error: the purchase was
     * accepted and `attributed` is still true (the caller was identified), there is simply
     * nothing for this user to grant.
     *
     * Deliberately false under `restorePolicy = "share"`: there the claimant legitimately rides
     * the owner's receipt and the snapshot beside this flag DOES carry the entitlement, so
     * treating it as an error would make the family-sharing flow look broken.
     *
     * Surface it as "already used on another Google account" rather than an unexplained blank.
     */
    val belongsToAnotherAccount: Boolean = false,
    /**
     * The store environment (`Sandbox` | `Production`) this purchase resolved into, as reported
     * by the server on a verify response.
     *
     * This has to come from the server: Google Play Billing tells the client NOTHING about
     * whether a purchase was a license-tester one, so — unlike StoreKit, where iOS reads
     * `Transaction.environment` — the SDK cannot work it out locally. Entitlements are resolved
     * PER ENVIRONMENT server-side, so without adopting this the SDK reads the app default
     * (normally Production) and a license-tester purchase's entitlements come back empty on the
     * very next read. [CashSDKClient] adopts it and stamps `X-CashSDK-Environment` thereafter.
     */
    val environment: String? = null,
) {
    /** Fast gating check used by host apps: is this entitlement currently active? */
    fun isActive(identifier: String): Boolean = active.any { it.identifier == identifier }

    /** True when the user holds any paid entitlement. */
    val hasActiveEntitlement: Boolean get() = active.isNotEmpty()

    /** Spendable balance of a consumable product (0 when never bought). */
    fun balanceOf(productIdentifier: String): Int =
        consumables.firstOrNull { it.productIdentifier == productIdentifier }?.balance ?: 0

    /**
     * Drop the per-transaction fields before caching. `productType`, `quantity`, `pending`,
     * `acknowledged`, `belongsToAnotherAccount` and `environment` describe the transaction that
     * was just verified, not the user's standing access — persisting them would resurrect e.g.
     * `pending = true` on the next launch's hydrated gating snapshot.
     */
    internal fun gatingSnapshot(): Entitlements =
        copy(
            productType = null,
            quantity = 1,
            pending = false,
            acknowledged = null,
            belongsToAnotherAccount = false,
            environment = null,
        )

    companion object {
        /** The "no entitlements / free tier" snapshot — never null, so gating is branch-free. */
        val EMPTY = Entitlements()
    }
}

/**
 * Purchase category sent to the server so it hits the right Google Play Developer API
 * (subscriptionsv2 vs products). The idiomatic Kotlin constants stay UPPERCASE (the public
 * API the docs snippet shows: `PurchaseKind.SUBSCRIPTION`), but they serialize to the
 * LOWERCASE wire values the verify endpoint matches on (`apps/api` PlayController:
 * `body.kind === "product" ? "product" : "subscription"`).
 */
@Serializable
enum class PurchaseKind {
    @SerialName("subscription")
    SUBSCRIPTION,

    @SerialName("product")
    PRODUCT,
}

// ── Request bodies ────────────────────────────────────────────────────────────

/** Body for `POST /v1/purchases:verify`. */
@Serializable
internal data class VerifyRequest(
    val productId: String,
    val purchaseToken: String,
    val kind: PurchaseKind,
)

/**
 * One analytics event. The public API exposes `properties: Map<String, Any>` for ergonomics;
 * on the wire the field is `props` (server-side `EventInput` shape). Posted inside an
 * [EventBatch] and persisted verbatim by [com.cashsdk.events.EventQueue], which is why this is
 * fully `@Serializable` in both directions.
 *
 * [id] is the **client event id**, generated once at RECORD time — never at send time. The
 * server dedupes on `[appId, clientEventId]`, so a batch retried after a dropped response
 * (routine on mobile) is idempotent instead of double-counting. Minting a fresh id per attempt
 * would defeat exactly that.
 *
 * [userId] is likewise stamped at RECORD time, not by the transport: a durable queue can be
 * flushed long after the fact, and attributing a backlog to whoever happens to be signed in at
 * flush time would credit user A's paywall impressions to user B.
 */
@Serializable
internal data class EventInput(
    val event: String,
    val id: String = java.util.UUID.randomUUID().toString(),
    val userId: String? = null,
    val placement: String? = null,
    val paywall: String? = null,
    /**
     * The experiment VARIANT this event belongs to. A first-class column server-side
     * (`Event.variant`), not a `props` entry — carrying it inside `props` (as this SDK used to)
     * left the column NULL for every Android event, so A/B results were computed from iOS
     * traffic alone while Android buyers silently contributed nothing. iOS sends it here; so
     * must we, or the same campaign reports two different populations per platform.
     */
    val variant: String? = null,
    val product: String? = null,
    val props: JsonObject? = null,
    /**
     * Epoch MILLISECONDS at which the event was recorded — stamped at record time, like [id]
     * and [userId], never at send time.
     *
     * The queue is durable: a batch can leave minutes, or a relaunch, after the fact. The server
     * defaults a missing `ts` to its own ingest clock, so omitting this (as this SDK used to)
     * re-dated every offline event to whenever connectivity happened to return — an event
     * recorded on Monday and flushed on Wednesday was reported as Wednesday's.
     */
    val ts: Long? = null,
)

/** Body for `POST /v1/events` — the batch envelope the server ingests. */
@Serializable
internal data class EventBatch(
    val events: List<EventInput>,
    val platform: String = "android",
    val appVersion: String? = null,
)

// ── Paywall resolve response ──────────────────────────────────────────────────

/** Response of `GET /v1/paywalls:resolve?placement=<p>`. `paywall` is null on skip. */
@Serializable
data class ResolveResponse(
    val paywall: ResolvedPaywall? = null,
    val variantId: String? = null,
    val experimentId: String? = null,
)

@Serializable
data class ResolvedPaywall(
    val config: PaywallConfig,
)

// ── Paywall config (docs/09-PAYWALLS.md §2) ───────────────────────────────────

/**
 * The validated paywall config stored in `PaywallVersion.config` and rendered by
 * [com.cashsdk.paywall.PaywallScreen]. `copy` is a key → locale → string map with the
 * renderer's fallback chain (exact locale → language → `en` → skip). Unknown fields are
 * ignored so additive server changes never break an older SDK (FR-6.7 graceful degrade).
 */
@Serializable
data class PaywallConfig(
    @SerialName("schema_version") val schemaVersion: Int = 1,
    val template: String = "centered_hero_v1",
    val presentation: String = "fullscreen", // fullscreen | modal | sheet
    /**
     * `gated` (run the feature only once entitled) | `non_gated` (run it on dismiss regardless).
     *
     * Defaults to **non_gated**, matching the iOS SDK. A misconfigured or partially-migrated
     * paywall must never permanently lock a user out of their own app — and the skip paths
     * (holdout, no paywall, resolver error) all run the feature too, so this keeps the
     * behaviour consistent. Diverging from iOS here would mean the SAME server config granted
     * access on one platform and withheld it on the other.
     */
    @SerialName("feature_gating") val featureGating: String = "non_gated",
    val products: List<PaywallProduct> = emptyList(),
    val copy: Map<String, Map<String, String>> = emptyMap(),
    val style: PaywallStyle = PaywallStyle(),
    val settings: PaywallSettings = PaywallSettings(),
    /**
     * The `schema_version: 2` COMPONENT TREE, captured but **not yet rendered** by this SDK.
     *
     * `apps/api/src/paywalls/config-schema.ts` calls v2 "the source of truth", and every seed
     * template plus the AI builder emit it: `{ theme, products, root: { type: "stack", children
     * […] } }`. This renderer only implements the v1 `template` + `copy` + `style` shape, and
     * every field of that shape has a DEFAULT — so a v2 config decoded without error into an
     * empty `centered_hero_v1` with no products and no copy, and the user was shown a blank
     * paywall with an unbuyable CTA. Silent, and worse than showing nothing.
     *
     * Kept as an opaque [JsonElement] purely so [isRenderable] can detect the case and
     * `register()` can degrade gracefully (advance the host) until the v2 renderer is ported.
     */
    val root: JsonElement? = null,
    val theme: JsonElement? = null,
) {
    val isGated: Boolean get() = featureGating == "gated"

    /**
     * Can THIS renderer actually draw this config?
     *
     * False for a v2 component tree (see [root]). Presenting one anyway yields an empty shell
     * with no purchasable product, so `register()` treats it exactly like "no paywall configured"
     * — the host advances (FR-6.7 graceful degrade) instead of the user hitting a dead end.
     */
    val isRenderable: Boolean
        get() = root == null && (schemaVersion < 2) && products.isNotEmpty()
}

@Serializable
data class PaywallProduct(
    val role: String, // template-defined slot: primary | secondary | ...
    @SerialName("product_id") val productId: String,
    /** Reference into `copy` (e.g. "copy.badge_popular") or a literal string, or null. */
    val badge: String? = null,
)

@Serializable
data class PaywallStyle(
    @SerialName("accent_color") val accentColor: String? = null,
    val background: String? = null,
    @SerialName("corner_radius") val cornerRadius: Int = 16,
    @SerialName("dark_mode") val darkMode: String = "auto", // auto | light | dark
)

@Serializable
data class PaywallSettings(
    @SerialName("show_restore") val showRestore: Boolean = true,
    @SerialName("show_close_button") val showCloseButton: Boolean = true,
    val legal: PaywallLegal = PaywallLegal(),
)

@Serializable
data class PaywallLegal(
    @SerialName("terms_url") val termsUrl: String? = null,
    @SerialName("privacy_url") val privacyUrl: String? = null,
)

// ── JSON helpers ──────────────────────────────────────────────────────────────

/**
 * Convert a loosely-typed `Map<String, Any?>` (the public `params`/`properties` API)
 * into a [JsonObject] without reflection — kotlinx.serialization can't serialize `Any`.
 * Supports the JSON-representable primitives plus nested maps/lists; anything else is
 * coerced to its `toString()` so a bad property never fails the whole call.
 */
internal fun Map<String, Any?>.toJsonObject(): JsonObject =
    JsonObject(mapValues { (_, v) -> v.toJsonElement() })

private fun Any?.toJsonElement(): JsonElement = when (this) {
    null -> JsonNull
    is JsonElement -> this
    is String -> JsonPrimitive(this)
    is Boolean -> JsonPrimitive(this)
    is Number -> JsonPrimitive(this)
    is Map<*, *> -> JsonObject(entries.associate { (k, v) -> k.toString() to v.toJsonElement() })
    is Iterable<*> -> JsonArray(map { it.toJsonElement() })
    else -> JsonPrimitive(toString())
}


/** A spendable consumable balance. May be negative after a refund of already-spent units. */
@Serializable
data class ConsumableBalance(
    val productIdentifier: String,
    val balance: Int,
)


/** `POST /v1/consumables:spend` request body. */
@Serializable
data class SpendRequest(
    val productIdentifier: String,
    val units: Int,
    val idempotencyKey: String,
    val note: String? = null,
)

/** `POST /v1/consumables:spend` response. */
@Serializable
data class ConsumableSpendResult(
    /** The balance AFTER the spend. */
    val balance: Int,
    /**
     * `false` when this exact `idempotencyKey` had already been applied — the balance is
     * authoritative either way, and the caller must NOT retry as a new spend.
     */
    val applied: Boolean,
)
