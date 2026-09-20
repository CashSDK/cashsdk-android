package com.cashsdk

import com.cashsdk.paywall.purchaseErrorText
import org.junit.Assert.*
import org.junit.Test

class PurchaseErrorTextTest {
    @Test fun storeDebugAndDeveloperSecretsNeverReachThePaywall() {
        val message = purchaseErrorText(CashSDKError.Billing(6, "sensitive token=secret"))
        assertFalse(message.contains("secret"))
        assertTrue(message.contains("before buying again"))
    }
    @Test fun wrongAccountIsNotPresentedAsSuccessfulRestore() {
        assertTrue(purchaseErrorText(CashSDKError.PurchaseBelongsToAnotherAccount).contains("another app account"))
    }
}
