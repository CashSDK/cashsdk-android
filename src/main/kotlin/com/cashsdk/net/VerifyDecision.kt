package com.cashsdk.net

import com.cashsdk.model.Entitlements

/**
 * What the SDK must DO with a `200` from `purchases:verify`.
 *
 * The three outcomes look almost identical on the wire — all three can carry an empty
 * entitlement list — but the correct handling of each is different, and getting the order of the
 * checks wrong silently converts one into another. This is a pure function of the parsed
 * response precisely so that ordering is pinned by a unit test instead of living inside
 * `CashSDKClient` where it can be reshuffled by accident.
 */
internal enum class VerifyDecision {
    /**
     * Google has not settled the payment yet (deferred: cash at a convenience store, carrier
     * billing, parental approval, SCA). Report it to the caller, cache NOTHING, and above all do
     * not consume or acknowledge — settling a purchase that may still fail is unrecoverable.
     */
    PENDING,

    /**
     * The server credited the purchase to nobody (no trusted `X-CashSDK-User-Token`, no matching
     * account id). Raise `PurchaseNotAttributed`: keep the purchase, leave it unsettled, and let
     * the post-`identify()` re-verify pick it up.
     */
    UNATTRIBUTED,

    /** Accepted receipt, but the restore policy withheld it from this app account. */
    OWNED_ELSEWHERE,

    /** Credited to this user. Cache the snapshot and settle the purchase with Google. */
    GRANTED,
    ;

    companion object {
        /**
         * PENDING IS TESTED FIRST, AND MUST STAY THAT WAY.
         *
         * The server reports a deferred purchase as `{ pending: true, attributed: false }` — and
         * `attributed: false` is honest there, because nothing has been credited yet. So a check
         * on `attributed` first swallows every pending purchase and reports it as an attribution
         * failure: the host is told to `identify()` (when the identity was never the problem),
         * the pending outcome becomes unreachable, telemetry is mislabelled, and a normal
         * deferred payment surfaces as an error in `restoreDetailed()`.
         *
         * Both branches agree on the part that protects the money — do not cache, do not settle —
         * so this ordering decides only which truth the host is told.
         */
        fun of(attributed: Boolean, entitlements: Entitlements): VerifyDecision = when {
            entitlements.pending -> PENDING
            !attributed -> UNATTRIBUTED
            entitlements.belongsToAnotherAccount -> OWNED_ELSEWHERE
            else -> GRANTED
        }
    }
}
