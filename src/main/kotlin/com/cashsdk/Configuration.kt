package com.cashsdk

/**
 * Immutable configuration captured at `CashSDK.configure(...)`.
 *
 * There is ONE publishable key per app and it serves both store environments. That is
 * deliberate: an internal-testing or license-tester install runs the same APK as the Play
 * listing, and its purchases are recorded as Sandbox while real ones are Production, so a key
 * pinned to one environment would lock out every user of the other. Purchases stay separate
 * by a stronger mechanism than a key prefix: each is recorded under the environment Google's
 * own verified purchase reports, and the SDK pins its reads to whatever the server resolved.
 *
 * @property publishableKey the app's device key (`csk_pk_…`), sent as
 *   `Authorization: Bearer <publishableKey>` on every device call. Copy it from Dashboard →
 *   your app → Developers → API Keys.
 * @property apiBase REST base URL. Defaults to production; override for dev/staging.
 * @property environment optional store-environment override (`Sandbox` | `Production`),
 *   forwarded as `X-CashSDK-Environment`. **Leave it null.** Play Billing tells the client
 *   nothing about whether a purchase was a license-tester one, so the SDK adopts the
 *   environment the server reports on the verify response. Pin it only for a QA build that
 *   must read one environment before making any purchase; a pinned value always wins, so
 *   pinning the wrong one reads an empty entitlement list. A `csk_pk_test_…`/`csk_pk_live_…`
 *   from before keys were unified still clamps, and disagreeing with one is a
 *   `400 environment_mismatch`; roll it in the dashboard to get a key that works in both.
 */
data class Configuration(
    val publishableKey: String,
    val apiBase: String = DEFAULT_API_BASE,
    val environment: String? = null,
) {
    init {
        require(publishableKey.isNotBlank()) { "publishableKey must not be blank" }
    }

    /** apiBase without a trailing slash, so path joins are unambiguous. */
    val normalizedBase: String get() = apiBase.trimEnd('/')

    companion object {
        const val DEFAULT_API_BASE = "https://api.cashsdk.com"
    }
}

/** SDK version string, reported on telemetry/events. Mirrors `BuildConfig.CASHSDK_VERSION`. */
const val CASHSDK_VERSION: String = BuildConfig.CASHSDK_VERSION
