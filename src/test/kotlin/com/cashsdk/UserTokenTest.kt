package com.cashsdk

import com.cashsdk.net.decodeBase64Url
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
    @Test fun tokenPayloadsDecodeWithoutJavaUtilBase64() {
        // java.util.Base64 is API 26; the decoder that replaces it must agree with it exactly.
        val random = java.util.Random(7)
        for (size in 0..64) {
            val bytes = ByteArray(size).also(random::nextBytes)
            val padded = Base64.getUrlEncoder().encodeToString(bytes)
            val unpadded = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
            assertArrayEquals(bytes, decodeBase64Url(padded))
            assertArrayEquals(bytes, decodeBase64Url(unpadded))
        }
        for (bad in listOf("a", "ab+c", "ab/c", "a b", "é")) assertNull(bad, decodeBase64Url(bad))
    }
    @Test fun confirmedPurchaseCarriesServerIdentity() {
        val snapshot = Json.decodeFromString<Entitlements>("""{"userId":"A","purchaseOutcomeConfirmed":true,"environment":"Sandbox"}""")
        assertEquals("A", snapshot.userId)
        assertEquals(true, snapshot.purchaseOutcomeConfirmed)
        assertNull(Json.decodeFromString<Entitlements>("{}").purchaseOutcomeConfirmed)
    }
}
