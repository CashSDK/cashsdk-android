package com.cashsdk.billing

import com.android.billingclient.api.Purchase
import com.cashsdk.CashSDKError
import com.cashsdk.EntitlementManager
import com.cashsdk.model.Entitlements
import com.cashsdk.model.PurchaseClaim
import com.cashsdk.model.PurchaseKind

/** What the SDK needs from a Play `Purchase`, so the verify-and-settle rules run without Play. */
internal data class PlayPurchase(
    val products: List<String>,
    val purchaseToken: String,
    /** The account token stamped on the flow (`obfuscatedAccountId`), or null for none. */
    val accountToken: String?,
    val isAcknowledged: Boolean,
)

internal fun Purchase.toPlayPurchase(): PlayPurchase =
    PlayPurchase(products, purchaseToken, accountIdentifiers?.obfuscatedAccountId, isAcknowledged)

/**
 * Verifies a Play purchase with the server, then settles it with Google. Every verify
 * [BillingManager] makes goes through one of the three entry points below, and each decides its
 * own `X-CashSDK-Claim`, so no caller (and no wiring) can hand it a different one:
 *  - [completeSheetPurchase]: what the payment sheet returned. `purchase` when it carries the
 *    buyer's own account token, `sync` otherwise (see [claimForSheetPurchase]).
 *  - [confirmOwnership]: a purchase this Google account already holds (before a plan change, or
 *    after Play's ITEM_ALREADY_OWNED). Always `sync`.
 *  - [completeSync]: automatic recovery. Always `sync`.
 */
internal class PurchaseCompleter(
    private val access: EntitlementManager,
    /** Consume or acknowledge with Google (`BillingManager.settle`). */
    private val settle: suspend (purchaseToken: String, productType: String?, isAcknowledged: Boolean) -> SettleOutcome,
    /** Stop tracking a purchase that is settled, or that never can be. */
    private val forget: (purchaseToken: String) -> Unit,
) {
    suspend fun completeSheetPurchase(productId: String, buyer: String, purchase: PlayPurchase, kind: PurchaseKind): Entitlements =
        complete(productId, purchase, kind, claimForSheetPurchase(buyer, purchase.accountToken))

    /**
     * Verify a purchase this Google account already holds and settle it only when the server
     * grants it to [buyer]. Claim `sync` may credit a purchase nobody owns but never moves one
     * that has an owner. One the server keeps with another app account throws
     * [CashSDKError.PurchaseNotAttributed], which hosts read as "already owned: restore". A
     * purchase this user only shares (`sharedFromAnotherAccount`) comes back like any grant;
     * callers decide what that means for them.
     */
    suspend fun confirmOwnership(productId: String, purchase: PlayPurchase, kind: PurchaseKind, buyer: String): Entitlements {
        val entitlements = try {
            complete(productId, purchase, kind, PurchaseClaim.SYNC, requireOwner = buyer)
        } catch (error: CashSDKError.Server) {
            throw if (error == CashSDKError.PurchaseBelongsToAnotherAccount) CashSDKError.PurchaseNotAttributed else error
        }
        if (entitlements.pending) throw CashSDKError.PurchasePending
        return entitlements
    }

    suspend fun completeSync(productId: String, purchase: PlayPurchase, kind: PurchaseKind, requireOwner: String?): Entitlements =
        complete(productId, purchase, kind, PurchaseClaim.SYNC, requireOwner)

    /**
     * Verify, then consume/acknowledge.
     *
     * What protects a purchase that is not this user's is the verify itself: an answer that keeps
     * it with another account (`belongsToAnotherAccount`) or credits nobody throws before anything
     * here settles it. [requireOwner] is a second check that the answer describes that user; the
     * server always answers for the caller, so it only matters for an answer with no `userId`.
     */
    private suspend fun complete(
        productId: String,
        purchase: PlayPurchase,
        kind: PurchaseKind,
        claim: PurchaseClaim,
        requireOwner: String? = null,
    ): Entitlements {
        val entitlements = access.verifyPurchase(productId, purchase.purchaseToken, kind, claim) // server grant
        // The server says Google has not settled the payment. Consuming or acknowledging now
        // would finalize a purchase that may still fail: leave it open and re-check later.
        if (entitlements.pending) return entitlements
        if (requireOwner != null && entitlements.userId != requireOwner) throw CashSDKError.PurchaseNotAttributed
        // Whether Play already has an acknowledgement, preferring the SERVER's answer. The flag on
        // the local `Purchase` was read before this verify, and the server acknowledges during it,
        // so the local copy says `false` for a purchase Google now considers settled. Acting on the
        // stale copy sent a second acknowledge through `settleWithRetries`, which is up to three
        // 15-second attempts with backoff between them, after every single purchase.
        val settled = entitlements.acknowledged ?: purchase.isAcknowledged
        val outcome = settle(purchase.purchaseToken, entitlements.productType, settled)
        if (outcome.settled) {
            forget(purchase.purchaseToken)
        } else if (outcome is SettleOutcome.Failed && !outcome.retryable) {
            // Terminal (e.g. DEVELOPER_ERROR, ITEM_NOT_OWNED on a subscription): retrying every
            // launch would never succeed, so stop tracking it, but surface it to the caller.
            forget(purchase.purchaseToken)
            throw CashSDKError.Billing(outcome.responseCode, outcome.debugMessage)
        }
        return entitlements
    }
}
