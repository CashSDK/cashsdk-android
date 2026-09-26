package com.cashsdk.billing

import com.cashsdk.AppAccountToken

/** Automatic recovery is not an explicit restore claim on another account's purchases. */
internal fun canAutomaticallySyncPurchase(userId: String?, accountToken: String?): Boolean =
    !userId.isNullOrBlank() && accountToken != null &&
        accountToken.equals(AppAccountToken.derive(userId), ignoreCase = true)

/** What a Play purchase's account token says about who bought it. Ordered from most to least certain. */
internal enum class PurchaseOwnership {
    /** Stamped with this user's canonical account token. */
    OWN,

    /**
     * No account token at all: a resubscribe started from the Play Store, a promo code, a
     * purchase made outside any app flow. Nothing on the device says whose it is.
     */
    NO_TOKEN,

    /** Another app account's token, or a legacy token from before CashSDK. */
    OTHER_TOKEN,
}

internal fun purchaseOwnership(userId: String?, accountToken: String?): PurchaseOwnership = when {
    accountToken.isNullOrBlank() -> PurchaseOwnership.NO_TOKEN
    canAutomaticallySyncPurchase(userId, accountToken) -> PurchaseOwnership.OWN
    else -> PurchaseOwnership.OTHER_TOKEN
}
