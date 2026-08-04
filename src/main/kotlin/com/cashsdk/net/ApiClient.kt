package com.cashsdk.net

import android.content.Context
import com.cashsdk.CASHSDK_VERSION
import com.cashsdk.CashSDKError
import com.cashsdk.Configuration
import com.cashsdk.model.ConsumableSpendResult
import com.cashsdk.model.Entitlements
import com.cashsdk.model.EventBatch
import com.cashsdk.model.EventInput
import com.cashsdk.model.ResolveResponse
import com.cashsdk.model.SpendRequest
import com.cashsdk.model.VerifyRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * The device REST client. Dependency-minimal: [HttpURLConnection] executed on
 * [Dispatchers.IO] rather than OkHttp/Retrofit, JSON via kotlinx.serialization.
 *
 * Every request carries `Authorization: Bearer <publishableKey>` and, once the host
 * calls `identify(...)`, `X-CashSDK-User-Id`. A small [ETag store][etagPrefs] (backed by
 * SharedPreferences) drives conditional entitlement reads (`If-None-Match` → `304`).
 *
 * Endpoints (apps/api):
 * - `POST /v1/purchases:verify`  → fresh [Entitlements]
 * - `GET  /v1/entitlements`      → [Entitlements] or `304 Not Modified`
 * - `POST /v1/events`            → best-effort telemetry (never throws to host)
 * - `GET  /v1/paywalls:resolve`  → [ResolveResponse]
 */
internal class ApiClient(
    context: Context,
    private val config: Configuration,
    /**
     * Supplies the `If-None-Match` value for entitlement reads. The ETag is owned by
     * `EntitlementStore`, which persists it INSIDE the snapshot record it describes — an ETag
     * kept in a separate file can outlive its body and pin the client to `304 Not Modified`
     * against an empty cache forever.
     */
    private val entitlementEtag: () -> String? = { null },
) {
    /** Set/cleared by the facade on identify/logout; sent as `X-CashSDK-User-Id`. */
    @Volatile
    var userId: String? = null

    /**
     * The backend-minted signed user token, sent as `X-CashSDK-User-Token`. Production TRUSTS
     * only this (the raw id header is honoured only outside production), so without it every
     * identified verify/entitlement/consumable call is rejected live. Set via `identify(...)`.
     */
    @Volatile
    var userToken: String? = null

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

    // Host app version, reported at the event-batch level.
    private val appVersion: String? = runCatching {
        val ctx = context.applicationContext
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
    }.getOrNull()

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

    suspend fun verifyPurchase(request: VerifyRequest): VerifyOutcome {
        val res = execute("POST", "/v1/purchases:verify", json.encodeToString(request), ifNoneMatch = null)
        if (!res.isSuccess) throw res.asServerError()
        val entitlements = decode<Entitlements>(res.body)
        val attributed = VerifyAttribution.isAttributed(res.body, entitlements)
        // Only an attributed body describes THIS user's entitlements, so only that one may
        // become the cached ETag.
        return VerifyOutcome(entitlements, if (attributed) res.etag else null, attributed)
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

    suspend fun getEntitlements(): EntitlementsResult {
        userId ?: throw CashSDKError.NotIdentified
        val res = execute("GET", "/v1/entitlements", body = null, ifNoneMatch = entitlementEtag())
        return when {
            res.status == HttpURLConnection.HTTP_NOT_MODIFIED -> EntitlementsResult.NotModified
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

    suspend fun resolvePaywall(placement: String): ResolveResponse {
        val query = URLEncoder.encode(placement, "UTF-8")
        val res = execute("GET", "/v1/paywalls:resolve?placement=$query", body = null, ifNoneMatch = null)
        if (!res.isSuccess) throw res.asServerError()
        return decode(res.body)
    }

    // ── Transport ───────────────────────────────────────────────────────────────

    private data class RawResponse(val status: Int, val body: String, val etag: String?) {
        val isSuccess: Boolean get() = status in 200..299
    }

    private suspend fun execute(
        method: String,
        path: String,
        body: String?,
        ifNoneMatch: String?,
    ): RawResponse = withContext(Dispatchers.IO) {
        val conn = (URL(config.normalizedBase + path).openConnection() as HttpURLConnection)
        try {
            conn.requestMethod = method
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("Authorization", "Bearer ${config.publishableKey}")
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("X-CashSDK-Platform", "android")
            conn.setRequestProperty("X-CashSDK-Sdk-Version", CASHSDK_VERSION)
            userId?.let { conn.setRequestProperty("X-CashSDK-User-Id", it) }
            userToken?.let { conn.setRequestProperty("X-CashSDK-User-Token", it) }
            environment?.let { conn.setRequestProperty("X-CashSDK-Environment", it) }
            ifNoneMatch?.let { conn.setRequestProperty("If-None-Match", it) }

            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }

            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            RawResponse(status, text, conn.getHeaderField("ETag"))
        } catch (e: CashSDKError) {
            throw e
        } catch (e: Exception) {
            // No connectivity / timeout / TLS — a transport failure, not a server verdict.
            throw CashSDKError.Network(e)
        } finally {
            conn.disconnect()
        }
    }

    private inline fun <reified T> decode(body: String): T =
        try {
            json.decodeFromString<T>(body)
        } catch (e: Exception) {
            throw CashSDKError.Decoding(e)
        }

    /** Map a non-2xx response to a typed [CashSDKError.Server], lifting the `error` code if present. */
    private fun RawResponse.asServerError(): CashSDKError.Server {
        val code = runCatching {
            (json.parseToJsonElement(body) as? JsonObject)?.get("error")
                ?.let { (it as? JsonPrimitive)?.contentOrNull }
        }.getOrNull()
        return CashSDKError.Server(status, code, body.take(ERROR_BODY_LIMIT))
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 20_000
        const val ERROR_BODY_LIMIT = 500
    }
}
