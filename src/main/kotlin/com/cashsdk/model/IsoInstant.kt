package com.cashsdk.model

/**
 * Epoch milliseconds for an ISO-8601 instant such as `2026-09-23T10:15:30.123Z`, or null when
 * the text is not one.
 *
 * The API writes `Date.toISOString()`. Zone offsets, missing seconds and extended years are
 * accepted too; a time without a zone is read as UTC. Written by hand because `java.time` needs
 * API 26 and this library supports API 24 without requiring desugaring from the host app.
 */
internal fun parseIsoInstantMillis(text: String): Long? {
    val match = ISO_INSTANT.matchEntire(text.trim()) ?: return null
    val parts = match.groupValues
    val year = parts[1].toLongOrNull() ?: return null
    val month = parts[2].toInt()
    val day = parts[3].toInt()
    val hour = parts[4].toInt()
    val minute = parts[5].toInt()
    val second = parts[6].ifEmpty { "0" }.toInt()
    if (month !in 1..12 || day !in 1..daysInMonth(year, month)) return null
    if (hour !in 0..23 || minute !in 0..59 || second !in 0..59) return null
    val millisOfSecond = parts[7].padEnd(3, '0').take(3).toInt()
    val offsetMinutes = zoneOffsetMinutes(parts[8]) ?: return null
    val minutes = (daysFromCivil(year, month, day) * 24 + hour) * 60 + minute - offsetMinutes
    return minutes * 60_000L + second * 1_000L + millisOfSecond
}

private val ISO_INSTANT = Regex(
    """([+-]?\d{4,6})-(\d{2})-(\d{2})[Tt](\d{2}):(\d{2})(?::(\d{2})(?:[.,](\d{1,9}))?)?([Zz]|[+-]\d{2}(?::?\d{2})?)?""",
)

/** `Z`, `+05:30`, `-0800` or `+05`, in minutes east of UTC. An empty zone means UTC. */
private fun zoneOffsetMinutes(zone: String): Int? {
    if (zone.isEmpty() || zone == "Z" || zone == "z") return 0
    val sign = if (zone[0] == '-') -1 else 1
    val digits = zone.substring(1).replace(":", "")
    val hours = digits.take(2).toInt()
    val minutes = digits.drop(2).ifEmpty { "0" }.toInt()
    if (hours > 23 || minutes > 59) return null
    return sign * (hours * 60 + minutes)
}

private fun isLeapYear(year: Long): Boolean = (year % 4 == 0L && year % 100 != 0L) || year % 400 == 0L

private fun daysInMonth(year: Long, month: Int): Int = when (month) {
    2 -> if (isLeapYear(year)) 29 else 28
    4, 6, 9, 11 -> 30
    else -> 31
}

/** Days since 1970-01-01 in the proleptic Gregorian calendar (H. Hinnant's `days_from_civil`). */
private fun daysFromCivil(year: Long, month: Int, day: Int): Long {
    val y = if (month <= 2) year - 1 else year
    val era = (if (y >= 0) y else y - 399) / 400
    val yearOfEra = y - era * 400
    val shiftedMonth = (month + 9) % 12 // March = 0
    val dayOfYear = (153 * shiftedMonth + 2) / 5 + day - 1
    val dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
    return era * 146_097 + dayOfEra - 719_468
}
