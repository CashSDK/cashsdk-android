package com.cashsdk.billing

import com.cashsdk.CashSDKError
import com.cashsdk.model.Entitlements
import com.cashsdk.model.PurchaseClaim
import kotlinx.coroutines.CancellationException

// The decisions behind automatic sync and ITEM_ALREADY_OWNED recovery. They take the Play
// purchase as an opaque [P] and the verify/settle step as a function, so the rules (which
// purchases are touched, in what order, when a pass stops) run in plain JVM tests without a
// BillingClient. The claim is not theirs to pick: the step they call is a [PurchaseCompleter]
// entry point, and those send `sync` for everything here.

/** One purchase Play reports, reduced to what the sync pass decides on. */
internal class SyncCandidate<P>(
    val handle: P,
    val purchaseToken: String,
    val productId: String?,
    val accountToken: String?,
    val purchased: Boolean,
)

/**
 * The automatic recovery pass over what Play reports as owned. [complete] is
 * [PurchaseCompleter.completeSync], so every verify here carries claim `sync`, which never moves
 * a purchase between app accounts.
 *
 *  - This user's purchases are verified and settled as before.
 *  - Purchases with no account token (a Play Store resubscribe, a promo code) are verified too:
 *    with claim `sync` the server credits one that nobody owns and never takes one from someone
 *    who does. They are settled only when the answer grants them to this user: an answer that
 *    keeps the purchase with another account (`belongsToAnotherAccount`) or credits nobody
 *    throws before anything is settled. [complete] also gets the user id as `requireOwner`, a
 *    second check that the answer is about this user (the server always answers for the caller,
 *    so today it never fires).
 *  - Another account's token is left alone; moving it takes an explicit restore.
 *
 * A failure the rest of the pass would hit too (offline, rate limited, server or Google down)
 * ends the pass; everything left stays owned by Play and is picked up by the next sync.
 */
internal suspend fun <P> syncOwnedPurchases(
    userId: String,
    live: List<SyncCandidate<P>>,
    currentUser: () -> String?,
    remember: (purchaseToken: String) -> Unit,
    /** Purchases the server has already told THIS user are not theirs; see [deny]. */
    denied: Set<String> = emptySet(),
    /**
     * Record that the server keeps this purchase with another app account, so later passes stop
     * asking. Without it a purchase carrying no account token (a Play Store resubscribe, a promo
     * code) was re-verified on every sync, for the life of the install, always to be told the
     * same thing. The answer is recorded per user, so signing in as the owner still verifies it.
     */
    deny: (purchaseToken: String) -> Unit = {},
    complete: suspend (handle: P, productId: String, requireOwner: String?) -> Unit,
) {
    for (candidate in live) {
        if (!candidate.purchased) {
            // Still pending: keep tracking it so we notice when it settles.
            remember(candidate.purchaseToken)
            continue
        }
        if (currentUser() != userId) return
        val ownership = purchaseOwnership(userId, candidate.accountToken)
        if (ownership == PurchaseOwnership.OTHER_TOKEN) continue
        // Already asked, already answered: the server keeps this one with another app account.
        if (candidate.purchaseToken in denied) continue
        val productId = candidate.productId ?: continue
        // Track it BEFORE trying: the server acknowledges Play purchases itself, so a consumable
        // whose local consume fails would otherwise look settled on the next pass and stay owned
        // and unbuyable. completePurchase forgets it once it is genuinely settled.
        remember(candidate.purchaseToken)
        try {
            val requireOwner = if (ownership == PurchaseOwnership.NO_TOKEN) userId else null
            complete(candidate.handle, productId, requireOwner)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (stopsSync(error)) return
            // "Another app account owns this" is final until the identity changes, so record it
            // rather than asking again on every sync. Deliberately NOT PurchaseNotAttributed:
            // that means the purchase is credited to nobody yet, which is exactly the case
            // identify() exists to resolve, so it must stay retryable. Anything else (a decode
            // failure, a refused settle) may differ next time and is left alone.
            if (error == CashSDKError.PurchaseBelongsToAnotherAccount) deny(candidate.purchaseToken)
            // Otherwise one failed purchase must not hide the rest.
        }
    }
}

/**
 * A failure the next purchase in the same pass would hit too: no connectivity, a rate limit, the
 * API or Google being down, or the signed-in user changing. Anything else is about one purchase.
 */
internal fun stopsSync(error: Throwable): Boolean = when (error) {
    is CashSDKError.Network -> true
    is CashSDKError.Server -> error.status == 408 || error.status == 429 || error.status >= 500
    CashSDKError.NotIdentified -> true
    else -> false
}

/** A purchase of the product Play says is already owned. */
internal class OwnedCandidate<P>(val purchase: P, val accountToken: String?, val purchased: Boolean)

/**
 * Play answered ITEM_ALREADY_OWNED, or the account already holds the subscription being bought,
 * so nothing was charged. Find out whose the owned purchase is instead of failing with a bare
 * billing code.
 *
 * [confirm] is [PurchaseCompleter.confirmOwnership]: it verifies with claim `sync`, which may
 * credit a purchase nobody owns but never moves one that someone else owns, settles only a
 * purchase the server grants to [buyer], and throws `PurchaseNotAttributed` (or
 * `PurchaseBelongsToAnotherAccount`) for one it keeps elsewhere. The user's own purchase comes
 * back as their entitlements. One the server keeps with another app account ends as
 * [CashSDKError.PurchaseNotAttributed], the error hosts already read as "already owned: restore,
 * don't buy". This user's token is tried first, then purchases with no token, then everything
 * else.
 */
internal suspend fun <P> resolveAlreadyOwned(
    buyer: String,
    owned: List<OwnedCandidate<P>>,
    cause: CashSDKError,
    currentUser: () -> String?,
    confirm: suspend (purchase: P) -> Entitlements,
): Entitlements {
    val purchased = owned.filter { it.purchased }
    if (purchased.isEmpty()) {
        // Only a payment Google has not settled yet. That is the purchase the user is waiting
        // for; the SDK verifies it when it completes.
        if (owned.isNotEmpty()) throw CashSDKError.PurchasePending
        throw cause
    }
    var ownedElsewhere = false
    var lastError: Throwable? = null
    for (candidate in purchased.sortedBy { purchaseOwnership(buyer, it.accountToken).ordinal }) {
        if (currentUser() != buyer) throw CashSDKError.NotIdentified
        try {
            return confirm(candidate.purchase)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: CashSDKError) {
            // Offline or a server outage: the next candidate would fail the same way.
            if (stopsSync(error)) throw error
            if (error.refusesOwnership) ownedElsewhere = true else lastError = error
        }
    }
    if (ownedElsewhere) throw CashSDKError.PurchaseNotAttributed
    throw lastError ?: cause
}

/** The server kept the purchase with another app account, or credited it to nobody. */
private val CashSDKError.refusesOwnership: Boolean
    get() = this == CashSDKError.PurchaseNotAttributed || this == CashSDKError.PurchaseBelongsToAnotherAccount

/**
 * The claim for the purchase Play hands back to the open payment sheet. Only one stamped with
 * the buyer's own account token was bought in this sheet. One with another account's token (or
 * none) reached the sheet some other way, since updates are matched by product id, and is
 * verified with `sync`, which cannot move it from its owner.
 */
internal fun claimForSheetPurchase(buyer: String, accountToken: String?): PurchaseClaim =
    if (purchaseOwnership(buyer, accountToken) == PurchaseOwnership.OWN) PurchaseClaim.PURCHASE else PurchaseClaim.SYNC
