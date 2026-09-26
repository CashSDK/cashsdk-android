package com.cashsdk.paywall

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.graphics.Color
import java.util.Locale

// ─────────────────────────────────────────────────────────────────────────────
// Pure helpers for the paywall renderer (same package → module-internal).
// ─────────────────────────────────────────────────────────────────────────────

internal const val MAX_FEATURES = 8

/** Copy fallback chain (docs 08 §4): exact locale tag → language → `en` → skip (null). */
internal fun resolveCopy(copy: Map<String, Map<String, String>>, key: String, locale: Locale): String? {
    val byLocale = copy[key] ?: return null
    return byLocale[locale.toLanguageTag()] ?: byLocale[locale.language] ?: byLocale["en"]
}

/** A badge is either a `copy.<key>` reference or a literal string. */
internal fun resolveBadge(badge: String?, copy: Map<String, Map<String, String>>, locale: Locale): String? {
    if (badge == null) return null
    return if (badge.startsWith("copy.")) resolveCopy(copy, badge.removePrefix("copy."), locale) else badge
}

/** Parse `#RRGGBB` or `#AARRGGBB` to a Compose [Color]; null on anything unparseable. */
internal fun parseColor(hex: String?): Color? {
    if (hex.isNullOrBlank()) return null
    return try {
        val cleaned = hex.trim().removePrefix("#")
        val argb = when (cleaned.length) {
            6 -> 0xFF000000L or cleaned.toLong(16)
            8 -> cleaned.toLong(16)
            else -> return null
        }
        Color(argb.toInt())
    } catch (e: Exception) {
        null
    }
}

internal fun openUrl(context: android.content.Context, url: String) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
