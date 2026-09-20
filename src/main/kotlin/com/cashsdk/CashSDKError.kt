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

    // Do not add subclasses without a source-breaking release: merchants exhaustively
    // match this sealed hierarchy. New conditions use the existing structured variants.
    internal companion object {
        fun InvalidPurchaseOptions(reason: String) = Billing(5, reason)
        val PurchaseBelongsToAnotherAccount = Server(200, "purchase_belongs_to_another_account")
        val PurchaseInProgress = Billing(5, "A purchase is already in progress")
        val ProductTypeUnknown = Decoding(IllegalStateException("The verified response has no supported catalog product type; settlement remains pending"))
        fun BillingTimeout(operation: String) = Network(java.util.concurrent.TimeoutException(
            "Google Play timed out during $operation. Payment may still complete; check purchases before buying again.",
        ))
    }

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
        CashSDKError("Purchase could not be attributed to a user. Call identify(userId, userToken) and retry")

    /**
     * A Google Play Billing call failed. [responseCode] is a `BillingClient.BillingResponseCode`.
     *
     * The message explains the code rather than repeating it. `Billing error (code=4)` is what a
     * developer used to get for the single most common first-run failure on Android — and every
     * cause of it is a setup step, not a bug in their code. Handing back the number alone sent
     * people to search engines during their first hour with the SDK.
     *
     * [responseCode] and [debug] are unchanged, so `when (e.responseCode)` keeps working.
     * Billing 9's optional [subResponseCode] preserves the actionable reason returned by
     * `onPurchasesUpdated` (for example insufficient funds or offer ineligibility).
     */
    data class Billing(
        val responseCode: Int,
        val debug: String? = null,
        val subResponseCode: Int = 0,
    ) :
        CashSDKError(
            "${explainBillingCode(responseCode)} (Play Billing code $responseCode)" +
                explainBillingSubResponseCode(subResponseCode) +
                (debug?.takeIf { it.isNotBlank() }?.let { ". Play says: $it" } ?: ""),
        )

    /** Transport failure (no connectivity, timeout, TLS). Reads degrade to cache; writes queue. */
    data class Network(val underlying: Throwable) :
        CashSDKError("Network error: ${underlying.message}", underlying)

    /**
     * A server refusal. Usually non-2xx; status 200 with code
     * `purchase_belongs_to_another_account` represents a recorded receipt that granted no
     * ownership to this caller. [status] retains the actual HTTP status, not an invented one.
     */
    data class Server(val status: Int, val code: String? = null, val body: String? = null) :
        CashSDKError("Server error $status${code?.let { " ($it)" } ?: ""}")

    /** Response body could not be decoded into the expected model. */
    data class Decoding(val underlying: Throwable) :
        CashSDKError("Failed to decode response: ${underlying.message}", underlying)
}

/**
 * Turn a `BillingClient.BillingResponseCode` into something a developer can act on.
 *
 * Every one of these is reachable during a first integration, and for most of them the cause is
 * Play Console configuration rather than code — which is precisely why the bare number was such
 * a poor answer. Text is deliberately blunt about the likely cause and names the fix.
 *
 * Values are hardcoded rather than referenced from `BillingResponseCode` so this file stays
 * dependency-free and usable from tests that do not pull in the billing client.
 */
internal fun explainBillingCode(code: Int): String = when (code) {
    -3 -> "Google Play did not respond in time. Transient, so retry."
    -2 -> "This device's Google Play Billing version does not support the feature requested. " +
        "Subscriptions with base plans and offers need Play Billing 5+ on a current Play Store."
    -1 -> "The connection to Google Play was lost. The SDK reconnects automatically, so retry the call."
    1 -> "The user cancelled the purchase."
    2 -> "Google Play Billing is temporarily unavailable (usually a network problem on the device)."
    3 -> "Google Play Billing is unavailable on this device or for this account. Common causes: " +
        "the app was not installed from Google Play (sideloaded or a debug build not on a Play " +
        "track), the Play Store is signed out or out of date, or the device has no Play Services."
    4 -> "That product is not available to this account. This is almost always setup, not code: " +
        "the product must be ACTIVE in Play Console, the app must be published to a track " +
        "(internal testing counts), the installed build must be signed with the same key as the " +
        "uploaded one, and the signed-in Google account must be a licence tester. It can also " +
        "take a few hours after a first upload for products to become purchasable."
    5 -> "Play rejected the request as a developer error. Check the product id spelling, that " +
        "the package name matches the one in Play Console, and that BillingClient is connected " +
        "before launching the flow."
    6 -> "Google Play returned a fatal error for this request. Retry once; if it persists, check " +
        "the Play Console for account or app status warnings."
    7 -> "This account already owns that product. Call CashSDK.shared.syncPurchases() to pick the " +
        "existing purchase up and grant the entitlement, rather than starting a new purchase."
    8 -> "This account does not own that product, so it cannot be consumed or acknowledged. " +
        "Usually means the purchase was already settled."
    12 -> "Google Play could not read the network. Transient, so retry."
    else -> "Google Play Billing returned an unexpected result."
}

/** Billing 9 supplies these alongside the top-level response code for purchase-flow failures. */
internal fun explainBillingSubResponseCode(code: Int): String = when (code) {
    0 -> ""
    1 -> ". Payment was declined because the account has insufficient funds. Ask the user " +
        "to update or choose another Play payment method."
    2 -> ". This account is not eligible for the selected subscription offer. Refresh the " +
        "product details and present an offer returned for this account."
    else -> ". Play Billing sub-response code $code."
}
