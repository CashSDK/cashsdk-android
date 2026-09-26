package com.cashsdk

import com.cashsdk.model.PackageProduct
import com.cashsdk.model.Entitlements
import com.cashsdk.model.EventBatch
import com.cashsdk.model.EventInput
import com.cashsdk.net.VerifyAttribution
import com.cashsdk.net.VerifyDecision
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cross-side wire-contract pins.
 *
 * Every response body below was CAPTURED VERBATIM from a real `apps/api` instance booted against
 * the dev Postgres + Redis (`ALLOW_UNVERIFIED_JWS=1`, `ALLOW_UNVERIFIED_PLAY=1`) and driven over
 * HTTP — they are not hand-written approximations of what the server "should" send. The point of
 * this file is that both sides of the contract were rewritten independently and each suite
 * previously tested only its own assumption; these assertions fail if the server's shape and this
 * SDK's parser ever drift apart again.
 *
 * Companion on the server side: `apps/api/test/sdk-wire-contract.test.ts`, which asserts the
 * same fields are actually emitted.
 */
class WireContractTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test fun candidateOfferingKeepsZeroTrialAndUnknownEligibility() {
        for (plan in listOf("weekly", "monthly", "yearly")) {
            val trial = if (plan == "weekly") "null" else "0"
            val body = """{"id":"$plan","identifier":"bet.midgame.$plan","store":"app-store","type":"auto_renewable","trialPrice":$trial,"eligibility":"unknown","offerSyncStatus":"verified"}"""
            val product = json.decodeFromString<PackageProduct>(body)
            assertEquals(if (plan == "weekly") null else 0, product.trialPrice)
            assertEquals("unknown", product.eligibility)
        }
    }

    // ── Captured bodies: POST /v1/purchases:verify ──────────────────────────────

    /** Deferred payment. NOTE `attributed:false` sitting beside `pending:true`. */
    private val playPending =
        """{"entitlements":[],"tier":0,"tierIdentifier":null,"pending":true,"attributed":false,""" +
            """"environment":"Sandbox"}"""

    /** Could not map the purchase to a user — a CONSUMABLE, so productType/quantity matter. */
    private val playUnattributedConsumable =
        """{"entitlements":[],"tier":0,"tierIdentifier":null,"productType":"consumable","quantity":3,""" +
            """"acknowledged":false,"attributed":false,"belongsToAnotherAccount":false,"environment":"Sandbox"}"""

    /** Credited. Consumable balances carry expiry fields this SDK does not model. */
    private val playAttributedConsumable =
        """{"entitlements":[{"identifier":"pro","name":"Pro","rank":3,"source":"subscription"}],""" +
            """"tier":3,"tierIdentifier":"pro","consumables":[{"productIdentifier":"play.ctr.coins100",""" +
            """"balance":2,"validityDays":null,"expiresAt":null,"expiringUnits":null}],""" +
            """"productType":"consumable","quantity":2,"acknowledged":false,"attributed":true,""" +
            """"belongsToAnotherAccount":false,"environment":"Sandbox"}"""

    /** Real receipt, registered to a different app user (restorePolicy = keep_with_original). */
    private val belongsToAnother =
        """{"entitlements":[],"tier":0,"tierIdentifier":null,"consumables":[],"attributed":true,""" +
            """"belongsToAnotherAccount":true,"environment":"Sandbox"}"""

    /** Same receipt under restorePolicy = "share": the claimant DOES hold the entitlement. */
    private val sharedWithClaimant =
        """{"entitlements":[{"identifier":"pro","name":"Pro","rank":3,"source":"subscription"}],""" +
            """"tier":3,"tierIdentifier":"pro","consumables":[],"attributed":true,""" +
            """"belongsToAnotherAccount":false,"environment":"Sandbox"}"""

    /** GET /v1/entitlements — no transaction-scoped fields at all. */
    private val entitlementsRead =
        """{"entitlements":[{"identifier":"pro","name":"Pro","rank":3,"source":"subscription"}],""" +
            """"tier":3,"tierIdentifier":"pro","consumables":[{"productIdentifier":"com.test.ctr.coins100",""" +
            """"balance":2,"validityDays":null,"expiresAt":null,"expiringUnits":null}]}"""

    // ── 1. `attributed` is explicit on every branch ─────────────────────────────

    @Test
    fun everyVerifyBranchCarriesAnExplicitAttributedFlag() {
        for (body in listOf(playPending, playUnattributedConsumable, playAttributedConsumable, belongsToAnother, sharedWithClaimant)) {
            val obj = json.parseToJsonElement(body) as JsonObject
            assertNotNull(
                "the server must send an explicit `attributed` on EVERY branch — the SDK must " +
                    "never fall back to guessing from response shape: $body",
                obj["attributed"],
            )
        }
        assertFalse(VerifyAttribution.isAttributed(playPending, json.decodeFromString(playPending)))
        assertFalse(
            VerifyAttribution.isAttributed(playUnattributedConsumable, json.decodeFromString(playUnattributedConsumable)),
        )
        assertTrue(
            VerifyAttribution.isAttributed(playAttributedConsumable, json.decodeFromString(playAttributedConsumable)),
        )
    }

    @Test
    fun explicitAttributedBeatsTheShapeHeuristic() {
        // The attributed branch of `belongsToAnotherAccount` has an EMPTY entitlement list and an
        // empty `consumables` array. Shape alone cannot tell it from an unattributed body; the
        // explicit flag can, and must win.
        assertTrue(
            "a real user who holds nothing is still ATTRIBUTED — refusing it would strand the purchase",
            VerifyAttribution.isAttributed(belongsToAnother, json.decodeFromString(belongsToAnother)),
        )
    }

    // ── 2. productType / quantity survive the unattributed branch ───────────────

    @Test
    fun unattributedBranchStillReportsProductTypeAndQuantity() {
        val decoded = json.decodeFromString<Entitlements>(playUnattributedConsumable)
        // Without these the SDK falls back to ACKNOWLEDGE, which leaves a consumable permanently
        // "owned" so the SKU can never be bought again by that buyer. Settling a Play purchase is
        // orthogonal to attributing it.
        assertEquals("consumable", decoded.productType)
        assertEquals(3, decoded.quantity)
    }

    @Test
    fun consumableProductTypeSelectsConsumeNotAcknowledge() {
        // BillingManager.settle keys off exactly this string. A rename server-side would silently
        // downgrade every consumable to acknowledge.
        assertEquals("consumable", json.decodeFromString<Entitlements>(playAttributedConsumable).productType)
    }

    // ── 3. pending is parsed, surfaced, and decided BEFORE attribution ──────────

    @Test
    fun pendingIsParsedFromTheRealServerBody() {
        val decoded = json.decodeFromString<Entitlements>(playPending)
        assertTrue(decoded.pending)
        assertTrue("a pending snapshot is empty by construction", decoded.active.isEmpty())
    }

    @Test
    fun pendingOutranksUnattributed() {
        val decoded = json.decodeFromString<Entitlements>(playPending)
        val attributed = VerifyAttribution.isAttributed(playPending, decoded)
        assertFalse("the server really does say attributed:false on a pending purchase", attributed)
        assertEquals(
            "a deferred purchase must report PENDING, not an attribution failure — the identity " +
                "was never the problem, and telling the host to identify() is a dead end",
            VerifyDecision.PENDING,
            VerifyDecision.of(attributed, decoded),
        )
    }

    @Test
    fun genuinelyUnattributedStillRaises() {
        val decoded = json.decodeFromString<Entitlements>(playUnattributedConsumable)
        assertEquals(
            VerifyDecision.UNATTRIBUTED,
            VerifyDecision.of(VerifyAttribution.isAttributed(playUnattributedConsumable, decoded), decoded),
        )
    }

    @Test
    fun creditedPurchaseIsGranted() {
        val decoded = json.decodeFromString<Entitlements>(playAttributedConsumable)
        assertEquals(
            VerifyDecision.GRANTED,
            VerifyDecision.of(VerifyAttribution.isAttributed(playAttributedConsumable, decoded), decoded),
        )
    }

    @Test
    fun pendingNeverReachesTheCache() {
        val cached = json.decodeFromString<Entitlements>(playPending).gatingSnapshot()
        assertFalse("pending must not be resurrected by the next launch's hydration", cached.pending)
    }

    // ── 4. belongsToAnotherAccount / share semantics ────────────────────────────

    @Test
    fun anotherAccountsReceiptMustNotLookLikeASuccessfulGrant() {
        val decoded = json.decodeFromString<Entitlements>(belongsToAnother)
        assertTrue("the transaction was accepted", decoded.belongsToAnotherAccount)
        assertEquals(
            "identified does not mean the restore policy granted the purchase to this caller",
            VerifyDecision.OWNED_ELSEWHERE,
            VerifyDecision.of(VerifyAttribution.isAttributed(belongsToAnother, decoded), decoded),
        )
    }

    @Test
    fun sharedWithClaimantDoesNotReadAsAnError() {
        // restorePolicy = "share": the server clears belongsToAnotherAccount precisely so the
        // family-sharing flow doesn't surface as "this receipt belongs to somebody else" in the
        // same response that grants the entitlement.
        val decoded = json.decodeFromString<Entitlements>(sharedWithClaimant)
        assertFalse(
            "a sharing claimant legitimately holds the entitlement — flagging it would break " +
                "family sharing",
            decoded.belongsToAnotherAccount,
        )
        assertTrue(decoded.isActive("pro"))
    }

    @Test
    fun belongsToAnotherAccountNeverReachesTheCache() {
        val cached = json.decodeFromString<Entitlements>(belongsToAnother).gatingSnapshot()
        assertFalse(
            "persisting it would make the app claim 'already used on another account' forever",
            cached.belongsToAnotherAccount,
        )
    }

    // ── 5. environment (the Play-only blind spot) ───────────────────────────────

    @Test
    fun verifyReportsTheEnvironmentOnEveryBranch() {
        // Play Billing gives the client NO way to tell a license-tester purchase from a real one,
        // so this is the only source. Entitlements resolve per environment server-side: read the
        // wrong one and a Sandbox purchase's entitlements come back empty.
        for (body in listOf(playPending, playUnattributedConsumable, playAttributedConsumable)) {
            assertEquals("Sandbox", json.decodeFromString<Entitlements>(body).environment)
        }
    }

    @Test
    fun entitlementsReadCarriesNoTransactionScopedFields() {
        val decoded = json.decodeFromString<Entitlements>(entitlementsRead)
        assertNull(decoded.productType)
        assertNull(decoded.acknowledged)
        assertNull(decoded.environment)
        assertFalse(decoded.pending)
        assertFalse(decoded.belongsToAnotherAccount)
        assertTrue(decoded.isActive("pro"))
        assertEquals(2, decoded.balanceOf("com.test.ctr.coins100"))
    }

    @Test
    fun unmodelledConsumableFieldsAreIgnored() {
        // The server ships validityDays/expiresAt/expiringUnits; this SDK models only
        // productIdentifier + balance. Additive server fields must never break decoding.
        assertEquals(2, json.decodeFromString<Entitlements>(entitlementsRead).balanceOf("com.test.ctr.coins100"))
    }

    // ── 6. POST /v1/events request shape ───────────────────────────────────────

    @Test
    fun eventBatchMatchesWhatTheServerIngests() {
        val encoder = Json { encodeDefaults = true; explicitNulls = false }
        val batch = EventBatch(
            events = listOf(
                EventInput(
                    event = "paywall_open",
                    id = "evt-1",
                    userId = "user_1",
                    placement = "onboarding_finished",
                    variant = "var_b",
                    product = "sku",
                    ts = 1_700_000_000_000L,
                ),
            ),
            platform = "android",
            appVersion = "1.2.3",
        )
        val obj = encoder.encodeToString(EventBatch.serializer(), batch)
            .let { json.parseToJsonElement(it) as JsonObject }

        assertEquals("android", (obj["platform"] as JsonPrimitive).content)
        assertNotNull("the server reads batch.appVersion", obj["appVersion"])
        val event = (obj["events"] as kotlinx.serialization.json.JsonArray)[0] as JsonObject

        // `id` is the server's dedupe key (`[appId, clientEventId]`); without it a retried batch
        // double-counts. The queue is durable, so retries are routine rather than exceptional.
        assertEquals("evt-1", (event["id"] as JsonPrimitive).content)
        // `variant` is a first-class column server-side (Event.variant) and the only thing
        // experiment reporting reads. Burying it in `props` left it NULL for all Android traffic.
        assertEquals("var_b", (event["variant"] as JsonPrimitive).content)
        // `ts` is stamped at RECORD time. Omitting it let the server date the row at ingest, so a
        // durable queue flushed after a relaunch re-timed every offline event.
        assertEquals(1_700_000_000_000L, (event["ts"] as JsonPrimitive).long)
    }

    @Test
    fun eventIdIsStableAcrossRetries() {
        val e = EventInput(event = "x", id = "fixed-id")
        assertEquals("fixed-id", e.copy().id)
        assertEquals(
            "re-serializing a queued event must not mint a new id — that would defeat the " +
                "server's dedupe and double-count every retry",
            "fixed-id",
            json.decodeFromString<EventInput>(json.encodeToString(EventInput.serializer(), e)).id,
        )
    }

    // ── 7. GET /v1/paywalls:resolve ─────────────────────────────────────────────

    /** A v1 config: the `template` + `copy` + `style` shape this renderer implements. */
    private val resolveBodyV1 =
        """{"paywall":{"id":"pw_1","identifier":"m","config":{"copy":{"title":{"en":"Go Pro"}},""" +
            """"style":{"accent_color":"#24A47F"},"products":[{"role":"primary","product_id":"com.dr.pro.monthly"}],""" +
            """"settings":{"legal":{},"show_restore":true},"template":"centered_hero_v1",""" +
            """"presentation":"fullscreen","feature_gating":"gated","schema_version":1}},""" +
            """"offering":{"id":"off_1","identifier":"default","displayName":"Default","isCurrent":true,""" +
            """"packages":[{"id":"pkg_1","identifier":"${'$'}monthly","position":0,""" +
            """"product":{"id":"prod_1","identifier":"com.dr.pro.monthly","basePlanId":null,"store":"app-store",""" +
            """"type":"auto_renewable","duration":"P1M","price":9990,"currency":"USD","displayName":"Pro Monthly"}}]},""" +
            """"offers":[],"trialEligibility":[{"productId":"prod_1","eligible":true}],""" +
            """"variantId":"var_1","experimentId":"camp_1"}"""

    /**
     * A `schema_version: 2` COMPONENT TREE — what the server actually ships. Every seed template
     * in `apps/api/prisma/seed-templates.ts` and every AI-builder output is this shape, and
     * `config-schema.ts` calls v2 "the source of truth".
     */
    private val resolveBodyV2 =
        """{"paywall":{"id":"pw_1","identifier":"m","config":{""" +
            """"schema_version":2,"presentation":"fullscreen","feature_gating":"non_gated",""" +
            """"theme":{"accent":"#6366F1","corner_radius":16,"dark_mode":"auto"},""" +
            """"products":[{"role":"primary","product_id":"com.dr.pro.yearly"}],""" +
            """"root":{"type":"stack","children":[""" +
            """{"type":"text","role":"title","text":"Unlock everything"},""" +
            """{"type":"product_selector","layout":"vertical_cards","default":"primary"},""" +
            """{"type":"button","text":"Continue","action":"purchase_selected"}]}}},""" +
            """"offers":[],"variantId":"var_1","experimentId":"camp_1"}"""

    @Test
    fun realV1ResolveBodyDecodesAndIsRenderable() {
        val decoded = json.decodeFromString<com.cashsdk.model.ResolveResponse>(resolveBodyV1)
        assertNotNull("no config means register() shows nothing", decoded.paywall?.config)
        assertEquals("var_1", decoded.variantId)
        assertEquals("camp_1", decoded.experimentId)
        val config = decoded.paywall!!.config
        // Server config keys are snake_case; the model maps them via @SerialName.
        assertEquals("gated", config.featureGating)
        assertTrue(config.isGated)
        assertEquals("com.dr.pro.monthly", config.products.first().productId)
        assertEquals("#24A47F", config.style.accentColor)
        assertEquals("Go Pro", config.copy["title"]?.get("en"))
        // `offering` / `offers` / `trialEligibility` are not modelled here — they must be ignored,
        // not fatal.
        assertTrue(config.settings.showRestore)
        assertTrue("a v1 config with products is renderable", config.isRenderable)
    }

    @Test
    fun v2ComponentTreeIsDetectedAsUnrenderable() {
        // THE POINT: every v1 field has a default, so a v2 config decodes WITHOUT ERROR into an
        // empty `centered_hero_v1` with no products and no copy. Presenting that shows a blank
        // paywall with an unbuyable CTA. `register()` must recognise it and advance the host
        // instead (FR-6.7), which is strictly better than a dead end.
        val decoded = json.decodeFromString<com.cashsdk.model.ResolveResponse>(resolveBodyV2)
        val config = assertNotNull(decoded.paywall?.config).let { decoded.paywall!!.config }
        assertEquals(2, config.schemaVersion)
        assertNotNull("the v2 tree must be captured so it can be detected", config.root)
        assertTrue("nothing to render from a v1 renderer's point of view", config.copy.isEmpty())
        assertFalse(
            "a v2 component tree must NOT be presented by the v1 renderer — it would be blank",
            config.isRenderable,
        )
    }

    @Test
    fun aV1ConfigWithNoProductsIsAlsoUnrenderable() {
        // Nothing to buy means the CTA cannot work; skipping beats a dead end.
        val config = json.decodeFromString<com.cashsdk.model.PaywallConfig>(
            """{"schema_version":1,"template":"centered_hero_v1","products":[]}""",
        )
        assertFalse(config.isRenderable)
    }

    @Test
    fun resolveSkipShapesDecode() {
        val noPaywall = """{"paywall":null,"offers":[],"trialEligibility":[]}"""
        assertNull(json.decodeFromString<com.cashsdk.model.ResolveResponse>(noPaywall).paywall)
        val holdout = """{"paywall":null,"offers":[],"holdout":true,"experimentId":"camp_1"}"""
        assertEquals("camp_1", json.decodeFromString<com.cashsdk.model.ResolveResponse>(holdout).experimentId)
    }

    @Test
    fun resolveMustNotBeTheOfflineFallbackBundle() {
        // `@Get("paywalls:fallback")` compiles to "paywalls" + a route PARAMETER, so it once
        // matched /v1/paywalls<anything> and swallowed the resolve endpoint. The bundle decodes to
        // an all-null response, so the failure was completely silent — every register() advanced
        // as "no paywall". These assertions are what would catch a regression.
        val bundle = """{"generatedAt":"2026-07-29T19:39:26.171Z","note":"Offline fallback","placements":[]}"""
        val decoded = json.decodeFromString<com.cashsdk.model.ResolveResponse>(bundle)
        assertNull("the fallback bundle is NOT a resolve response", decoded.paywall)
        assertNull(decoded.variantId)
    }

    // ── 8. appAccountToken parity (golden vectors — DO NOT change the derivation) ─

    @Test
    fun appAccountTokenMatchesTheServerGoldenVectors() {
        // Produced by `deriveAppAccountToken` in apps/api/src/common/app-account-token.ts.
        // Byte-parity across server / iOS / Android is what makes async store notifications
        // attributable at all; these are pinned, not to be "fixed".
        val golden = mapOf(
            "1" to "00000000-0000-4000-8000-000000000001",
            "42" to "00000000-0000-4000-8000-00000000002a",
            "281474976710655" to "00000000-0000-4000-8000-ffffffffffff",
        )
        for ((userId, expected) in golden) {
            assertEquals("numeric ids embed their VALUE", expected, AppAccountToken.derive(userId))
        }
        // Zero-padding collapses on the numeric branch — identically on all three sides.
        assertEquals(AppAccountToken.derive("7"), AppAccountToken.derive("007"))
        // Out-of-range and non-digit ids take the FNV branch (stable, byte-sensitive).
        for (id in listOf("0", "281474976710656", "+15551234567", "user_abc")) {
            val t = AppAccountToken.derive(id)
            assertTrue("FNV branch keeps the UUIDv4 shape: $t", t.startsWith("00000000-0000-4000-8000-"))
            assertEquals("stable across calls", t, AppAccountToken.derive(id))
        }
    }

    @Test
    fun buildJsonObjectHelperStaysAvailable() {
        // Guards the props path used by `emit` (Map<String, Any> -> JsonObject).
        assertNotNull(buildJsonObject { })
    }
}
