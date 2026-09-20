package com.cashsdk

import com.cashsdk.billing.requiresConsumption
import org.junit.Assert.*
import org.junit.Test

class SettlementPolicyTest {
    @Test fun onlyConsumablesAreConsumed() {
        assertTrue(requiresConsumption("consumable"))
        for (type in listOf("non_consumable", "auto_renewable", "non_renewing", "subscription")) {
            assertFalse(requiresConsumption(type))
        }
    }

    @Test fun missingOrUnknownCatalogNeverAuthorizesSettlement() {
        for (type in listOf(null, "", "new_type", "CONSUMABLE")) {
            val error = assertThrows(CashSDKError.Decoding::class.java) { requiresConsumption(type) }
            assertTrue(error.underlying.message.orEmpty().contains("catalog product type"))
        }
    }
}
