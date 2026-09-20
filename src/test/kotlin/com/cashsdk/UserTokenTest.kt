package com.cashsdk

import com.cashsdk.net.requireFreshUserToken
import com.cashsdk.model.Entitlements
import java.util.Base64
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class UserTokenTest {
    private fun token(body: String) = "header.${Base64.getUrlEncoder().withoutPadding().encodeToString(body.toByteArray())}.signature"

    @Test fun freshSubjectMayProceed() { requireFreshUserToken("A", token("""{"sub":"A","exp":2000}"""), 1000) }
    @Test fun missingMalformedForeignAndExpiringTokensCannotOpenPaymentSheet() {
        for (value in listOf(null, "malformed", token("{}"), token("""{"sub":"B","exp":2000}"""),
            token("""{"sub":"A","exp":1001}"""), token("""{"sub":"A","exp":1030}"""))) {
            assertThrows(CashSDKError.Server::class.java) { requireFreshUserToken("A", value, 1000) }
        }
    }
    @Test fun confirmedPurchaseCarriesServerIdentity() {
        val snapshot = Json.decodeFromString<Entitlements>("""{"userId":"A","purchaseOutcomeConfirmed":true,"environment":"Sandbox"}""")
        assertEquals("A", snapshot.userId)
        assertEquals(true, snapshot.purchaseOutcomeConfirmed)
        assertNull(Json.decodeFromString<Entitlements>("{}").purchaseOutcomeConfirmed)
    }
}
