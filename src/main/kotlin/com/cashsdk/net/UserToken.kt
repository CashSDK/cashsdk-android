package com.cashsdk.net

import com.cashsdk.CashSDKError
import java.io.ByteArrayOutputStream
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
        val payload = requireNotNull(decodeBase64Url(parts[1]))
        Json.parseToJsonElement(String(payload, Charsets.UTF_8)).jsonObject
    }.getOrNull() ?: throw CashSDKError.Server(401, "user_token_required")
    if (claims["sub"]?.jsonPrimitive?.content != userId) throw CashSDKError.Server(401, "user_token_identity_mismatch")
    val expiry = claims["exp"]?.jsonPrimitive?.longOrNull
    if (expiry == null || expiry <= nowSeconds + 30) throw CashSDKError.Server(401, "user_token_expired")
}

/**
 * RFC 4648 base64url (the JWT alphabet), padding optional; null when the text is not base64url.
 *
 * `java.util.Base64` needs API 26 and this library supports 24. On Android 7 the call failed
 * inside the `runCatching` above, so every purchase was refused as `user_token_required`
 * before the payment sheet opened.
 */
internal fun decodeBase64Url(text: String): ByteArray? {
    val clean = text.trimEnd('=')
    if (clean.length % 4 == 1) return null
    val out = ByteArrayOutputStream(clean.length * 3 / 4)
    var buffer = 0
    var bits = 0
    for (char in clean) {
        val value = when (char) {
            in 'A'..'Z' -> char - 'A'
            in 'a'..'z' -> char - 'a' + 26
            in '0'..'9' -> char - '0' + 52
            '-' -> 62
            '_' -> 63
            else -> return null
        }
        buffer = (buffer shl 6) or value
        bits += 6
        if (bits >= 8) {
            bits -= 8
            out.write((buffer shr bits) and 0xFF)
            buffer = buffer and ((1 shl bits) - 1)
        }
    }
    return out.toByteArray()
}
