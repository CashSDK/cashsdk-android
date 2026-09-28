package com.cashsdk

import com.cashsdk.model.Entitlement
import com.cashsdk.model.Entitlements
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Java callers (and code compiled against 1.3.0-rc.1 that passed every argument) call these
 * constructors by their exact JVM signatures. New parameters go last and the constructors are
 * `@JvmOverloads`, so every earlier signature still exists.
 */
class JavaCallerCompatibilityTest {
    private val string = String::class.java
    private val boolean = Boolean::class.javaPrimitiveType!!
    private val int = Int::class.javaPrimitiveType!!

    @Test fun purchaseOptionsKeepsItsRc1Signatures() {
        PurchaseOptions::class.java.getConstructor()
        PurchaseOptions::class.java.getConstructor(string)
        val rc1 = PurchaseOptions::class.java.getConstructor(
            string, string, string, string, SubscriptionReplacementMode::class.java, boolean,
        )
        assertEquals("monthly", rc1.newInstance("monthly", null, null, null, null, false).basePlanId)
    }

    @Test fun entitlementKeepsItsRc1Signatures() {
        Entitlement::class.java.getConstructor(string)
        val rc1 = Entitlement::class.java.getConstructor(string, string, Int::class.javaObjectType, string)
        val made = rc1.newInstance("pro", "Pro", 3, "subscription")
        assertEquals(null, made.expiresAt)
    }

    @Test fun entitlementsKeepsItsRc1Signatures() {
        Entitlements::class.java.getConstructor()
        Entitlements::class.java.getConstructor(List::class.java, int, string)
        // The full rc.1 constructor: active … purchaseOutcomeConfirmed.
        Entitlements::class.java.getConstructor(
            List::class.java, int, string, List::class.java, string, int, boolean, Boolean::class.javaObjectType,
            boolean, string, string, Boolean::class.javaObjectType,
        )
    }

    @Test fun restoreOutcomeKeepsItsRc1Signatures() {
        CashSDKClient.RestoreOutcome::class.java.getConstructor(string, string, boolean, boolean)
        val rc1 = CashSDKClient.RestoreOutcome::class.java.getConstructor(string, string, boolean, boolean, Throwable::class.java)
        val made = rc1.newInstance("pro", "t1", true, true, null)
        assertEquals(null, made.purchaseOutcomeConfirmed)
    }
}
