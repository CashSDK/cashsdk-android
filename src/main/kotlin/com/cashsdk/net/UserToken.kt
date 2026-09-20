package com.cashsdk.net

import com.cashsdk.CashSDKError
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Preflight only. The server verifies the signature; the client must avoid opening
 * a payment sheet with missing, expired, or another account's credentials. */
internal fun requireFreshUserToken(userId: String, token: String?, nowSeconds: Long = System.currentTimeMillis() / 1000) {
    val claims = runCatching {
        val parts = requireNotNull(token).split('.')
        require(parts.size == 3 && parts.all { it.isNotBlank() })
        Json.parseToJsonElement(String(Base64.getUrlDecoder().decode(parts[1]), Charsets.UTF_8)).jsonObject
    }.getOrNull() ?: throw CashSDKError.Server(401, "user_token_required")
    if (claims["sub"]?.jsonPrimitive?.content != userId) throw CashSDKError.Server(401, "user_token_identity_mismatch")
    val expiry = claims["exp"]?.jsonPrimitive?.longOrNull
    if (expiry == null || expiry <= nowSeconds + 30) throw CashSDKError.Server(401, "user_token_expired")
}
