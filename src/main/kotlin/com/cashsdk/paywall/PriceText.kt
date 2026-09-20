package com.cashsdk.paywall

import com.cashsdk.billing.ProductPrice
import com.cashsdk.billing.PricingPhase

/** Currency text always comes from Play. Periods accompany prices on every paywall template. */
internal fun ProductPrice.priceText(): String {
    val phase = phases.lastOrNull { it.recurrenceMode == 1 } ?: phases.lastOrNull() ?: return formattedPrice
    return phasePriceText(phase)
}

internal fun ProductPrice.introText(): String? {
    if (phases.size < 2) return null
    return phases.dropLast(1).joinToString(", then ") { phase ->
        if (phase.priceAmountMicros == 0L) {
            val cycles = phase.billingCycleCount.coerceAtLeast(1)
            "Free for ${periodText(phase.billingPeriod, cycles)}"
        } else {
            "${phasePriceText(phase)} for ${phase.billingCycleCount.coerceAtLeast(1)} billing cycle(s)"
        }
    } + ", then ${priceText()}"
}

private fun phasePriceText(phase: PricingPhase): String = if (phase.recurrenceMode == 3) {
    "${phase.formattedPrice} for ${periodText(phase.billingPeriod)}"
} else "${phase.formattedPrice} / ${periodText(phase.billingPeriod, omitOne = true)}"

private fun periodText(period: String, cycles: Int = 1, omitOne: Boolean = false): String {
    val match = Regex("P([0-9]+)([DWMY])").matchEntire(period) ?: return period
    val count = match.groupValues[1].toLongOrNull()?.times(cycles) ?: return period
    val unit = when (match.groupValues[2]) { "D" -> "day"; "W" -> "week"; "M" -> "month"; else -> "year" }
    return if (count == 1L && omitOne) unit else "$count $unit${if (count == 1L) "" else "s"}"
}
