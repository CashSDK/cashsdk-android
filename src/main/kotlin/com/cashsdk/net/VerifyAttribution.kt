package com.cashsdk.net

import com.cashsdk.model.Entitlements
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Decides whether a `200` from `purchases:verify` was actually credited to a user.
 *
 * The API answers `200` in both cases. When it cannot map the purchase to a user (no trusted
 * `X-CashSDK-User-Token`, no matching account id) it returns a well-formed but EMPTY snapshot,
 * which the SDK previously treated as a successful sale: it cached the empty snapshot over the
 * user's real entitlements and consumed/acknowledged a purchase belonging to nobody.
 *
 * Preference order:
 *  1. The explicit `attributed` boolean. The server sends it on EVERY branch of
 *     `PlayController.verifyPurchase` — the unattributed early return, the deferred `pending`
 *     return, and the success path — so this is authoritative and no heuristic runs at all.
 *  2. A `userId`, if a future response carries one.
 *  3. Only as a last resort, the response *shape*: the attributed branch always returns a
 *     `consumables` array alongside the snapshot, while the unattributed early-return has no
 *     `consumables` key. This exists solely so an OLDER server (deployed before `attributed`)
 *     still fails safe; against the current server it is unreachable.
 *
 * Deliberately biased towards "attributed" whenever the body cannot be inspected: a false
 * negative strands a legitimate purchase, which is worse than the separately-handled case of
 * caching a genuinely empty snapshot.
 *
 * Kept as a standalone object (rather than a private method on `ApiClient`) so it is unit
 * testable without an Android `Context`.
 */
internal object VerifyAttribution {

    private val json = Json { ignoreUnknownKeys = true }

    fun isAttributed(body: String, decoded: Entitlements): Boolean {
        val obj = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return true
        (obj["attributed"] as? JsonPrimitive)?.let {
            return it.content.toBooleanStrictOrNull() ?: true
        }
        (obj["userId"] as? JsonPrimitive)?.contentOrNull?.let { return it.isNotEmpty() }
        if (obj.containsKey("consumables")) return true
        return decoded.active.isNotEmpty()
    }
}
