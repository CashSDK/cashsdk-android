package com.cashsdk.paywall

import com.cashsdk.CashSDKError

/** Never show store debug strings or a promise that a failed verify means no charge. */
internal fun purchaseErrorText(error: Throwable): String = when (error) {
    CashSDKError.NotIdentified -> "Please sign in again before purchasing."
    CashSDKError.PurchaseBelongsToAnotherAccount -> "This purchase belongs to another app account. Sign in to the account that owns it."
    CashSDKError.PurchaseInProgress -> "A purchase is already in progress."
    is CashSDKError.ProductNotFound -> "This plan is unavailable right now. Please choose another plan."
    CashSDKError.PurchasePending -> "Your purchase is pending approval."
    else -> "Your purchase could not be confirmed yet. Check your connection and restore purchases before buying again."
}
