package com.cashsdk.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * The machine-readable code in an API error body, or null.
 *
 * The API uses two shapes: `{"error":"invalid_purchase"}` from the handlers, and
 * `{"error":{"code":"rate_limited","message":"too many requests"}}` from the rate limiters.
 * Only the first was read, so every 429 reached the host as a code-less server error.
 */
internal fun serverErrorCode(body: String): String? = runCatching {
    when (val error = (Json.parseToJsonElement(body) as? JsonObject)?.get("error")) {
        is JsonObject -> (error["code"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        is JsonPrimitive -> error.takeIf { it.isString }?.content
        else -> null
    }
}.getOrNull()

/**
 * A `Retry-After` header as milliseconds from [nowMillis]: either delta-seconds (what the API
 * sends) or an HTTP date. Null when absent or unreadable.
 */
internal fun parseRetryAfterMillis(value: String?, nowMillis: Long): Long? {
    val text = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    if (text.all { it in '0'..'9' }) {
        val seconds = text.toLongOrNull() ?: return null
        return if (seconds > Long.MAX_VALUE / 1_000) null else seconds * 1_000
    }
    val date = parseHttpDateMillis(text) ?: return null
    return (date - nowMillis).coerceAtLeast(0)
}

/** An RFC 1123 HTTP date (`Tue, 23 Sep 2026 10:15:30 GMT`) as epoch milliseconds, or null. */
internal fun parseHttpDateMillis(value: String?): Long? {
    val text = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return runCatching {
        SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
            .apply {
                timeZone = TimeZone.getTimeZone("GMT")
                isLenient = false
            }
            .parse(text)
            ?.time
    }.getOrNull()
}
