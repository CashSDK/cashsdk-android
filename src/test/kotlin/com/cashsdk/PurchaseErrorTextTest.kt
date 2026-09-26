package com.cashsdk

import com.cashsdk.paywall.UNCONFIRMED_PURCHASE_TEXT
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
    @Test fun notAttributedCopyFitsEveryCaseThatThrowsIt() {
        // Thrown after a payment credited to nobody, after the account changed mid-purchase, and
        // for a product owned on another app account. The copy must be true for all three.
        val message = purchaseErrorText(CashSDKError.PurchaseNotAttributed)
        assertEquals("We could not confirm this purchase for your account yet. Restore purchases before buying again.", message)
        assertFalse(message.contains("sign in"))
    }
    @Test fun paidWithoutAccessNeverInvitesASecondPurchase() {
        assertTrue(UNCONFIRMED_PURCHASE_TEXT.contains("payment went through"))
        assertTrue(UNCONFIRMED_PURCHASE_TEXT.contains("rather than buying again"))
    }
    @Test fun pendingIsNotAFailure() {
        assertTrue(purchaseErrorText(CashSDKError.PurchasePending).contains("unlocks automatically"))
    }
}
