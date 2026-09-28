package com.cashsdk

import com.cashsdk.billing.OwnedPurchase
import com.cashsdk.billing.SettleOutcome
import com.cashsdk.billing.stopsSync
import com.cashsdk.model.Entitlements
import com.cashsdk.model.PurchaseClaim
import kotlinx.coroutines.CancellationException

/**
 * The explicit restore pass behind `restoreDetailed()`, over plain functions so its rules run in
 * JVM tests.
 *
 * Every verify sends claim `restore`: the user asked for this, so it is the one pass (besides a
 * fresh purchase) that may take a purchase over from another app account under the app's
 * restore policy. Each purchase is re-verified and then FINISHED: a purchase whose app died
 * before the acknowledge/consume call is still open, Play auto-refunds anything left
 * unacknowledged for 3 days, and an unconsumed consumable can never be bought again. The verify
 * response says which of the two it needs.
 *
 * Once a failure shows the rest would fail the same way (offline, rate limited, the API or
 * Google down), the remaining purchases are reported with that error instead of each waiting
 * out its own retries.
 *
 * A purchase the server recorded without confirming its access (`purchaseOutcomeConfirmed:
 * false`) is still a verified, settled outcome, not a failure: it is reported in
 * [CashSDKClient.RestoreOutcome.purchaseOutcomeConfirmed] for the host to act on.
 */
internal suspend fun restoreOwnedPurchases(
    owned: List<OwnedPurchase>,
    requireSameUser: () -> Unit,
    verify: suspend (OwnedPurchase, PurchaseClaim) -> Entitlements,
    settle: suspend (OwnedPurchase, Entitlements) -> SettleOutcome,
): List<CashSDKClient.RestoreOutcome> {
    val outcomes = mutableListOf<CashSDKClient.RestoreOutcome>()
    var stopped: Throwable? = null
    for (purchase in owned) {
        requireSameUser()
        stopped?.let { error ->
            outcomes += CashSDKClient.RestoreOutcome(purchase.productId, purchase.purchaseToken, verified = false, settled = false, error = error)
            continue
        }
        outcomes += try {
            val verified = verify(purchase, PurchaseClaim.RESTORE)
            requireSameUser()
            if (verified.pending) {
                CashSDKClient.RestoreOutcome(purchase.productId, purchase.purchaseToken, verified = false, settled = false)
            } else {
                val settled = settle(purchase, verified)
                val settleError = (settled as? SettleOutcome.Failed)?.let { CashSDKError.Billing(it.responseCode, it.debugMessage) }
                CashSDKClient.RestoreOutcome(
                    purchase.productId,
                    purchase.purchaseToken,
                    verified = true,
                    settled = settled.settled,
                    error = settleError,
                    transferredFromAnotherAccount = verified.transferredFromAnotherAccount,
                    purchaseOutcomeConfirmed = verified.purchaseOutcomeConfirmed,
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            if (stopsSync(error)) stopped = error
            CashSDKClient.RestoreOutcome(purchase.productId, purchase.purchaseToken, verified = false, settled = false, error = error)
        }
    }
    requireSameUser()
    return outcomes
}
