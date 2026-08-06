package com.cashsdk

/**
 * Immutable configuration captured at `CashSDK.configure(...)`.
 *
 * @property publishableKey the app's device key (`csk_pk_…`), sent as
 *   `Authorization: Bearer <publishableKey>` on every device call.
 * @property apiBase REST base URL. Defaults to production; override for dev/staging.
 * @property environment optional store-environment override (`Sandbox` | `Production`),
 *   forwarded as `X-CashSDK-Environment`. When null the server uses the app default.
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
const val CASHSDK_VERSION: String = "0.1.0-scaffold"
