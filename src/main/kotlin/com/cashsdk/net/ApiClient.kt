package com.cashsdk.net

import android.content.Context
import com.cashsdk.CASHSDK_VERSION
import com.cashsdk.CashSDKError
import com.cashsdk.Configuration
import com.cashsdk.ServerClock
import com.cashsdk.model.ConsumableSpendResult
import com.cashsdk.model.Entitlements
import com.cashsdk.model.EventBatch
import com.cashsdk.model.EventInput
import com.cashsdk.model.Offering
import com.cashsdk.model.OfferingsResponse
import com.cashsdk.model.PurchaseClaim
import com.cashsdk.model.ResolveResponse
import com.cashsdk.model.SpendRequest
import com.cashsdk.model.VerifyRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.URL
import java.net.URLEncoder

/** The host app's version name, reported at the event-batch level. */
internal fun hostAppVersion(context: Context): String? = runCatching {
    val ctx = context.applicationContext
    ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
}.getOrNull()

/**
 * The device REST client. Dependency-minimal: `HttpURLConnection` (see [UrlConnectionTransport])
 * executed on [Dispatchers.IO] rather than OkHttp/Retrofit, JSON via kotlinx.serialization.
 *
 * Every request carries `Authorization: Bearer <publishableKey>` and, once the host calls
 * `identify(...)`, `X-CashSDK-User-Id` and `X-CashSDK-User-Token`. Every verify also carries
 * `X-CashSDK-Claim` (see [PurchaseClaim]). The ETag for conditional entitlement reads
 * (`If-None-Match`, then `304`) comes from [entitlementEtag].
 *
 * Endpoints (apps/api):
 * - `POST /v1/purchases:verify`  → fresh [Entitlements]
 * - `GET  /v1/entitlements`      → [Entitlements] or `304 Not Modified`
 * - `POST /v1/events`            → best-effort telemetry (never throws to host)
 * - `GET  /v1/paywalls:resolve`  → [ResolveResponse]
 */
internal class ApiClient(
    private val config: Configuration,
    /** Host app version, reported at the event-batch level. */
    private val appVersion: String? = null,
    /**
     * Supplies the `If-None-Match` value for entitlement reads. The ETag is owned by
     * `EntitlementStore`, which persists it INSIDE the snapshot record it describes: an ETag
     * kept in a separate file can outlive its body and pin the client to `304 Not Modified`
     * against an empty cache forever.
     */
    private val entitlementEtag: () -> String? = { null },
    private val transport: HttpTransport = UrlConnectionTransport,
    private val verifyRetry: VerifyRetryPolicy = VerifyRetryPolicy(),
    /** Where the blocking transport runs. Tests pass their own dispatcher to keep virtual time. */
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    /** Set/cleared by the facade on identify/logout; sent as `X-CashSDK-User-Id`. */
    private val identity = RequestIdentity()
    val userId: String? get() = identity.current.userId

    /**
     * The backend-minted signed user token, sent as `X-CashSDK-User-Token`. Production TRUSTS
     * only this (the raw id header is honoured only outside production), so without it every
     * identified verify/entitlement/consumable call is rejected live. Set via `identify(...)`.
     */
    val userToken: String? get() = identity.current.userToken

    fun setIdentity(userId: String?, userToken: String?) = identity.set(userId, userToken)

    fun identitySnapshot(): RequestIdentity.Snapshot = identity.current

    fun isCurrentIdentity(snapshot: RequestIdentity.Snapshot): Boolean = identity.isCurrent(snapshot)

    fun requireIdentity(snapshot: RequestIdentity.Snapshot) {
        if (snapshot.userId == null || !identity.isCurrent(snapshot)) throw CashSDKError.NotIdentified
    }

    fun requireValidUserToken(snapshot: RequestIdentity.Snapshot) {
        requireIdentity(snapshot)
        if (!isLocalServer) {
            // The lenient clock: a wrong server-clock offset must not refuse a token the server
            // itself would still accept.
            requireFreshUserToken(snapshot.userId!!, snapshot.userToken, ServerClock.lenientNowMillis() / 1000)
        }
    }

    /**
     * Whether a read sent now with the current credentials can succeed: no token at all (the
     * server decides; development servers accept the raw id), or one that is for this user and
     * not about to expire. A background read with an expired token would only earn a `401`.
     */
    fun hasUsableToken(): Boolean {
        val current = identity.current
        val userId = current.userId ?: return false
        if (current.userToken == null || isLocalServer) return true
        return runCatching { requireFreshUserToken(userId, current.userToken, ServerClock.lenientNowMillis() / 1000) }.isSuccess
    }

    /**
     * The API host, parsed once. Null when `apiBase` is not a URL: then every request fails as a
     * network error, and nothing here may throw a `MalformedURLException` into the host app from
     * a background read.
     */
    private val apiHost: String? = runCatching { URL(config.normalizedBase).host }.getOrNull()

    private val isLocalServer: Boolean
        get() = apiHost in setOf("localhost", "127.0.0.1", "10.0.2.2", "[::1]")

    /**
     * The same user is still signed in. Unlike [requireIdentity] this survives the host
     * refreshing that user's token with `identify(sameUser, newToken)`: a purchase started with
     * the old token belongs to the same person, and turning it into `NotIdentified` after the
     * payment went through told a paying user their purchase failed.
     */
    fun requireSameUser(userId: String?) {
        if (userId == null || identity.current.userId != userId) throw CashSDKError.NotIdentified
    }

    /** [requireValidUserToken] for the token [userId] has now, which may be newer than the one the purchase started with. */
    fun requireValidUserTokenFor(userId: String) {
        val current = identity.current
        if (current.userId != userId) throw CashSDKError.NotIdentified
        requireValidUserToken(current)
    }

    /**
     * The store environment sent as `X-CashSDK-Environment` (`Sandbox` | `Production`).
     *
     * Seeded from [Configuration.environment] when the host pinned one, otherwise learned from
     * the server's verify response — Google Play Billing gives the client no way to tell a
     * license-tester purchase from a real one, so the server is the only source. It matters
     * because entitlements are resolved PER ENVIRONMENT: with no header the API falls back to
     * the app default (normally `Production`), so a Sandbox purchase is verified into `Sandbox`
     * and then "disappears" from the very next entitlements read.
     */
    @Volatile
    var environment: String? = config.environment

    /** At most this many verify requests in flight at once, however many callers there are. */
    private val verifyPermits = Semaphore(MAX_CONCURRENT_VERIFIES)

    private val json = Json {
        ignoreUnknownKeys = true // additive server changes never break an older SDK
        explicitNulls = false // omit null optionals from request bodies
        encodeDefaults = true // ...but DO send non-null defaults, e.g. platform="android"
    }

    /** Result of a conditional entitlement read. The ETag travels with the body it describes. */
    sealed interface EntitlementsResult {
        data class Modified(val entitlements: Entitlements, val etag: String?) : EntitlementsResult
        data object NotModified : EntitlementsResult
    }

    /**
     * The outcome of `purchases:verify`.
     *
     * [attributed] is the load-bearing field. The API answers `200` even when it could not map
     * the purchase to a user (no trusted `X-CashSDK-User-Token`, no `obfuscatedAccountId`), and
     * the body it returns then is a well-formed EMPTY snapshot. Treating that as success wiped
     * the user's cached entitlements and let the SDK consume/acknowledge a purchase that was
     * credited to nobody.
     */
    data class VerifyOutcome(
        val entitlements: Entitlements,
        val etag: String?,
        val attributed: Boolean,
    )

    // ── Public calls ──────────────────────────────────────────────────────────

    /**
     * `POST /v1/purchases:verify` with `X-CashSDK-Claim: <claim>`, retried per [verifyRetry].
     *
     * The verify belongs to the user who was signed in when it started. Every attempt sends the
     * credentials that user has at that moment (a token refreshed during a backoff is used, not
     * the expired one), and a different user signing in stops the call with `NotIdentified`
     * before another request goes out, so a retry can never claim the purchase for them.
     */
    suspend fun verifyPurchase(request: VerifyRequest, claim: PurchaseClaim): VerifyOutcome {
        val owner = identity.current.userId
        val body = json.encodeToString(request)
        var attempts = 0
        var waitedMs = 0L
        while (true) {
            val attemptIdentity = identity.current
            if (attemptIdentity.userId != owner) throw CashSDKError.NotIdentified
            val res = verifyPermits.withPermit {
                execute(
                    "POST",
                    VERIFY_PATH,
                    body,
                    ifNoneMatch = null,
                    requestIdentity = attemptIdentity,
                    sameUserSuffices = true,
                    extraHeaders = mapOf(CLAIM_HEADER to claim.wireValue),
                )
            }
            attempts++
            if (res.isSuccess) {
                val entitlements = decode<Entitlements>(res.body)
                val attributed = VerifyAttribution.isAttributed(res.body, entitlements)
                // Only an attributed body describes THIS user's entitlements, so only that one
                // may become the cached ETag.
                return VerifyOutcome(entitlements, if (attributed) res.etag else null, attributed)
            }
            val waitMs = verifyRetry.delayBeforeNextAttempt(attempts, res.status, res.retryAfterMs, waitedMs)
                ?: throw res.asServerError()
            delay(waitMs)
            waitedMs += waitMs
        }
    }

    /**
     * `POST /v1/consumables:spend` — debit a consumable balance.
     *
     * `idempotencyKey` is REQUIRED by the server: a dropped response on mobile is routine, and
     * an unkeyed retry would double-spend the user's balance.
     */
    suspend fun spendConsumable(
        productIdentifier: String,
        units: Int,
        idempotencyKey: String,
        note: String?,
    ): ConsumableSpendResult {
        userId ?: throw CashSDKError.NotIdentified
        val body = json.encodeToString(
            SpendRequest(productIdentifier, units, idempotencyKey, note),
        )
        val res = execute("POST", "/v1/consumables:spend", body, ifNoneMatch = null)
        if (!res.isSuccess) throw res.asServerError()
        return decode(res.body)
    }

    /**
     * `GET /v1/entitlements`. With [revalidate] false no `If-None-Match` is sent, so the answer is
     * always a full body; the read made at an access deadline wants the server's current snapshot,
     * not a confirmation of the one that is about to end.
     */
    suspend fun getEntitlements(revalidate: Boolean = true): EntitlementsResult {
        userId ?: throw CashSDKError.NotIdentified
        val res = execute("GET", "/v1/entitlements", body = null, ifNoneMatch = if (revalidate) entitlementEtag() else null)
        return when {
            res.status == HTTP_NOT_MODIFIED -> EntitlementsResult.NotModified
            res.isSuccess -> EntitlementsResult.Modified(decode(res.body), res.etag)
            else -> throw res.asServerError()
        }
    }

    /**
     * `POST /v1/events` — send one batch of already-queued events.
     *
     * Unlike every other call here this one is driven by [com.cashsdk.events.EventQueue], which
     * owns durability and retry. It therefore **throws** on failure (transport error or non-2xx)
     * so the flusher can put the batch back and back off; swallowing the error here is what made
     * the old fire-and-forget `logEvent` lose every offline event. Events carry their own
     * client-generated `id` and `userId` from record time — the transport adds nothing per-event.
     */
    suspend fun sendEvents(events: List<EventInput>) {
        if (events.isEmpty()) return
        val batch = EventBatch(events = events, platform = "android", appVersion = appVersion)
        val res = execute("POST", "/v1/events", json.encodeToString(batch), ifNoneMatch = null)
        if (!res.isSuccess) throw res.asServerError()
    }

    /**
     * The current offering (GET /v1/offerings/current).
     *
     * The device endpoint, deliberately: the `/v1/offerings` on the public API authenticates
     * with a SECRET key, which reads the whole revenue ledger and must never ship in an app.
     */
    suspend fun currentOffering(): Offering? {
        val res = execute("GET", "/v1/offerings/current", body = null, ifNoneMatch = null)
        if (!res.isSuccess) throw res.asServerError()
        return decode<OfferingsResponse>(res.body).current
    }

    suspend fun resolvePaywall(placement: String): ResolveResponse {
        val query = URLEncoder.encode(placement, "UTF-8")
        val res = execute("GET", "/v1/paywalls:resolve?placement=$query", body = null, ifNoneMatch = null)
        if (!res.isSuccess) throw res.asServerError()
        return decode(res.body)
    }

    // ── Transport ───────────────────────────────────────────────────────────────

    private data class RawResponse(val status: Int, val body: String, val etag: String?, val retryAfterMs: Long?) {
        val isSuccess: Boolean get() = status in 200..299
    }

    /**
     * Send one request as [requestIdentity].
     *
     * By default the response is discarded (`NotIdentified`) when ANY identify call happened
     * while it was in flight, including A → B → A and a token refresh: a snapshot read under old
     * credentials must not overwrite one read under new ones. [sameUserSuffices] relaxes that to
     * "the same user is still signed in", which is what a verify needs: its answer is about that
     * user's purchase, and dropping it only because the token was refreshed left a paid purchase
     * unsettled.
     */
    private suspend fun execute(
        method: String,
        path: String,
        body: String?,
        ifNoneMatch: String?,
        requestIdentity: RequestIdentity.Snapshot = identity.current,
        sameUserSuffices: Boolean = false,
        extraHeaders: Map<String, String> = emptyMap(),
    ): RawResponse {
        val requestEnvironment = environment
        fun stillCurrent(): Boolean =
            if (sameUserSuffices) identity.current.userId == requestIdentity.userId else identity.isCurrent(requestIdentity)
        return withContext(ioDispatcher) {
            if (!stillCurrent()) throw CashSDKError.NotIdentified
            val headers = buildMap {
                put("Authorization", "Bearer ${config.publishableKey}")
                put("Accept", "application/json")
                put("X-CashSDK-Platform", "android")
                put("X-CashSDK-Sdk-Version", CASHSDK_VERSION)
                requestIdentity.userId?.let { put("X-CashSDK-User-Id", it) }
                requestIdentity.userToken?.let { put("X-CashSDK-User-Token", it) }
                requestEnvironment?.let { put("X-CashSDK-Environment", it) }
                ifNoneMatch?.let { put("If-None-Match", it) }
                if (body != null) put("Content-Type", "application/json; charset=utf-8")
                putAll(extraHeaders)
            }
            val response = try {
                transport.send(HttpRequest(method, config.normalizedBase + path, headers, body))
            } catch (e: CashSDKError) {
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // No connectivity / timeout / TLS: a transport failure, not a server verdict.
                throw CashSDKError.Network(e)
            }
            val receivedAt = System.currentTimeMillis()
            val serverDate = parseHttpDateMillis(response.header("Date"))
            if (serverDate != null && (response.status in 200..299 || response.status == HTTP_NOT_MODIFIED)) {
                ServerClock.observe(serverDate, receivedAt)
            }
            if (!stillCurrent() || requestEnvironment != environment) {
                throw CashSDKError.NotIdentified
            }
            RawResponse(
                response.status,
                response.body,
                response.header("ETag"),
                // An HTTP-date Retry-After is measured from the server's own Date, not the device clock.
                parseRetryAfterMillis(response.header("Retry-After"), serverDate ?: receivedAt),
            )
        }
    }

    private inline fun <reified T> decode(body: String): T =
        try {
            json.decodeFromString<T>(body)
        } catch (e: Exception) {
            throw CashSDKError.Decoding(e)
        }

    /** Map a non-2xx response to a typed [CashSDKError.Server], lifting the error code from either body shape. */
    private fun RawResponse.asServerError(): CashSDKError.Server =
        CashSDKError.Server(status, serverErrorCode(body), body.take(ERROR_BODY_LIMIT))

    private companion object {
        const val ERROR_BODY_LIMIT = 500
        const val HTTP_NOT_MODIFIED = 304
        const val VERIFY_PATH = "/v1/purchases:verify"
        const val CLAIM_HEADER = "X-CashSDK-Claim"
        const val MAX_CONCURRENT_VERIFIES = 3
    }
}
