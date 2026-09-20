package com.cashsdk.billing

import com.cashsdk.CashSDKError

/** Only the verified catalog type can authorize consumption or acknowledgement. */
internal fun requiresConsumption(productType: String?): Boolean = when (productType) {
    "consumable" -> true
    "non_consumable", "auto_renewable", "non_renewing", "subscription" -> false
    else -> throw CashSDKError.ProductTypeUnknown
}
