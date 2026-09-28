package com.cashsdk

import com.cashsdk.billing.canAutomaticallySyncPurchase
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PurchaseIdentityTest {
    @Test fun `automatic recovery includes the identified buyers purchases`() {
        assertTrue(canAutomaticallySyncPurchase("buyer", AppAccountToken.derive("buyer")))
        assertTrue(canAutomaticallySyncPurchase("buyer", AppAccountToken.derive("buyer").uppercase()))
    }

    @Test fun `automatic recovery cannot claim another accounts purchase`() {
        assertFalse(canAutomaticallySyncPurchase("B", AppAccountToken.derive("A")))
        assertFalse(canAutomaticallySyncPurchase(null, AppAccountToken.derive("A")))
        assertFalse(canAutomaticallySyncPurchase("", AppAccountToken.derive("A")))
    }

    @Test fun `legacy and foreign purchases require an explicit restore`() {
        assertFalse(canAutomaticallySyncPurchase("buyer", null))
        assertFalse(canAutomaticallySyncPurchase("buyer", "another-sdk-token"))
    }
}
