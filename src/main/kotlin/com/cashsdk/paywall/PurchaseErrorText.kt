package com.cashsdk.paywall

import com.cashsdk.CashSDKError

internal const val PENDING_PURCHASE_TEXT = "Your purchase is pending. It unlocks automatically once Google Play confirms it."

/** Paid and settled, but the server did not confirm access (`purchaseOutcomeConfirmed == false`). */
internal const val UNCONFIRMED_PURCHASE_TEXT =
    "Your payment went through, but access is not confirmed yet. Please contact support rather than buying again."

/** Never show store debug strings or a promise that a failed verify means no charge. */
internal fun purchaseErrorText(error: Throwable): String = when (error) {
    CashSDKError.NotIdentified -> "Please sign in again before purchasing."
    CashSDKError.PurchaseBelongsToAnotherAccount -> "This purchase belongs to another app account. Sign in to the account that owns it."
    // Thrown after a payment credited to nobody, when the account changed during the purchase,
    // and when this Google account already owns the product on another app account. The copy
    // has to fit all three.
    CashSDKError.PurchaseNotAttributed -> "We could not confirm this purchase for your account yet. Restore purchases before buying again."
    CashSDKError.PurchaseInProgress -> "A purchase is already in progress."
    is CashSDKError.ProductNotFound -> "This plan is unavailable right now. Please choose another plan."
    CashSDKError.PurchasePending -> PENDING_PURCHASE_TEXT
    else -> "Your purchase could not be confirmed yet. Check your connection and restore purchases before buying again."
}
