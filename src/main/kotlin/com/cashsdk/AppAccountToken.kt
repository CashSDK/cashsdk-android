package com.cashsdk

/**
 * Canonical `appAccountToken` derivation (docs/13 §9.2, FR-2.2).
 *
 * The value is passed to Play as `BillingFlowParams.setObfuscatedAccountId`, which the store
 * stamps onto the purchase as `obfuscatedExternalAccountId`; the server maps it back to the app
 * user. It MUST be byte-identical to the server (`deriveAppAccountToken`) and the iOS SDK
 * (`CashSDK.appAccountToken`) or attribution silently misses. Golden vectors on all three guard it.
 *
 * Scheme: `00000000-0000-4000-8000-<12 lowercase hex>`.
 *   • Numeric id (1 … 2^48-1): the 12 hex ARE the id — reversible, matches the backend/seed.
 *   • Any other id: FNV-1a-64 over the UTF-8 bytes, folded to the low 48 bits.
 *
 * ## Integration constraint: do not pass zero-padded numeric user ids
 *
 * The numeric branch embeds the VALUE of the id, so `"7"`, `"07"` and `"007"` all derive the
 * SAME token — identically on the server, on iOS and on Android. If your backend emits
 * zero-padded ids, two different users can share one `appAccountToken`, and their purchases,
 * entitlements and refunds merge onto whichever account the server resolves first.
 *
 * Pass the un-padded id to `identify(userId)`, or use an opaque (non-numeric) id — those take
 * the FNV branch, which is sensitive to every byte including leading zeros. This is a property
 * of the shared derivation, not something a single SDK can correct: changing it here would break
 * byte-parity with the server and silently drop attribution for every existing purchase.
 */
internal object AppAccountToken {
    fun derive(userId: String): String = "00000000-0000-4000-8000-${hex12(userId)}"

    private fun hex12(userId: String): String {
        // PURE ASCII digits only, matching the server's /^[0-9]+$/ — a leading +/- must fall to FNV,
        // else "+15551234567".toULongOrNull() would embed the number while the server FNV-hashes it,
        // and a phone-number user id would silently miss attribution.
        if (userId.isNotEmpty() && userId.all { it in '0'..'9' }) {
            val n = userId.toULongOrNull()
            if (n != null && n > 0uL && n <= 0xFFFFFFFFFFFFuL) {
                return n.toString(16).padStart(12, '0')
            }
        }
        return fnv1a48Hex(userId)
    }

    private fun fnv1a48Hex(s: String): String {
        var hash = 0xcbf29ce484222325uL
        for (b in s.toByteArray(Charsets.UTF_8)) {
            hash = hash xor b.toUByte().toULong()
            hash *= 0x100000001b3uL // ULong multiplication wraps mod 2^64, matching Swift's &*
        }
        return (hash and 0xFFFFFFFFFFFFuL).toString(16).padStart(12, '0')
    }
}
