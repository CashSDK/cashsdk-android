package com.cashsdk

/**
 * The SDK's typed error surface (Android port of iOS `CashSDKError`, `08-IOS-SDK.md` §6).
 * A sealed [Exception] hierarchy so callers can `catch (e: CashSDKError)` and branch on
 * the concrete type, while still interoperating with Kotlin's `Result`/`runCatching`.
 *
 * Suspend entry points (`verifyPurchase`, `restore`) throw these. Paywall resolution
 * (`register`) never throws to the host — it degrades to "advance" (FR-6.7).
 */
sealed class CashSDKError(
    message: String? = null,
    cause: Throwable? = null,
) : Exception(message, cause) {

    /** `configure(...)` was never called. */
    data object NotConfigured :
        CashSDKError("CashSDK.configure(context, publishableKey) must be called before use")

    /** An operation that needs a user (e.g. entitlement read) ran before `identify(...)`. */
    data object NotIdentified :
        CashSDKError("identify(userId) is required before this operation")

    /** Google Play returned no ProductDetails for the requested id(s). */
    data class ProductNotFound(val productIds: List<String>) :
        CashSDKError("No Play product details for: ${productIds.joinToString()}")

    /** User dismissed the Google purchase sheet — not an error to surface as failure UI. */
    data object PurchaseCancelled : CashSDKError("Purchase cancelled by user")

    /**
     * Deferred purchase (pending/parental approval/SCA). Resolution arrives later.
     *
     * NOT a lost purchase: the SDK persists the token and verifies + settles it as soon as
     * Google settles the payment. Call `CashSDK.shared.syncPurchases()` from `onResume` so that
     * happens promptly, and watch `entitlementUpdates` for the grant.
     */
    data object PurchasePending : CashSDKError("Purchase is pending")

    /**
     * The server accepted the purchase (`200`) but credited it to NO user — it arrived with no
     * trusted `X-CashSDK-User-Token` and no matching account id.
     *
     * The purchase is deliberately left unsettled (not consumed, not acknowledged locally) so it
     * can be re-verified and credited once `identify(...)` has run. Do not treat it as a
     * completed sale.
     */
    data object PurchaseNotAttributed :
        CashSDKError("Purchase could not be attributed to a user — call identify(userId, userToken) and retry")

    /** A Google Play Billing call failed. [responseCode] is a `BillingClient.BillingResponseCode`. */
    data class Billing(val responseCode: Int, val debug: String? = null) :
        CashSDKError("Billing error (code=$responseCode)${debug?.let { ": $it" } ?: ""}")

    /** Transport failure (no connectivity, timeout, TLS). Reads degrade to cache; writes queue. */
    data class Network(val underlying: Throwable) :
        CashSDKError("Network error: ${underlying.message}", underlying)

    /** Non-2xx HTTP response. [code] is the server error code from the JSON body when present. */
    data class Server(val status: Int, val code: String? = null, val body: String? = null) :
        CashSDKError("Server error $status${code?.let { " ($it)" } ?: ""}")

    /** Response body could not be decoded into the expected model. */
    data class Decoding(val underlying: Throwable) :
        CashSDKError("Failed to decode response: ${underlying.message}", underlying)
}
