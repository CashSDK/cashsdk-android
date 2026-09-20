package com.cashsdk.billing

import com.cashsdk.AppAccountToken

/** Automatic recovery is not an explicit restore claim on another account's purchases. */
internal fun canAutomaticallySyncPurchase(userId: String?, accountToken: String?): Boolean =
    !userId.isNullOrBlank() && accountToken != null &&
        accountToken.equals(AppAccountToken.derive(userId), ignoreCase = true)
