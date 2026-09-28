package com.cashsdk

import com.cashsdk.billing.StoreOffer
import com.cashsdk.model.Entitlements
import com.cashsdk.net.ApiClient
import kotlinx.serialization.Serializable
import java.math.BigDecimal
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

// ─────────────────────────────────────────────────────────────────────────────
// Coupons: merchant-created codes applied THROUGH Google Play, as a developer-determined offer
// on the product's base plan. Contract: AGENTS/COUPONS.md in the CashSDK source tree.
// ─────────────────────────────────────────────────────────────────────────────

/**
 * The answer of `CashSDK.shared.validateCoupon(code)`.
 *
 * A code the server refused is not an exception: [valid] is false and [reason] says why.
 */
data class CouponValidation(
    /** Whether the signed-in user can redeem this code on Android right now. */
    val valid: Boolean,
    /** Why the code cannot be redeemed. Null when [valid] is true. */
    val reason: CouponInvalidReason?,
    /** The reason as the server sent it (`not_found`, ...), also for [CouponInvalidReason.UNKNOWN]. */
    val rawReason: String?,
    /** What the coupon gives. Null when [valid] is false. */
    val coupon: Coupon?,
    /** The products the coupon can be redeemed on, on Android. */
    val products: List<CouponProduct>,
) {
    /** The Play product ids to pass to `redeemCoupon`, each once. */
    val eligibleProductIds: List<String> get() = products.map { it.productId }.distinct()

    /**
     * The base plans the coupon covers for [productId], in the server's order. More than one
     * means `redeemCoupon` needs a `basePlanId`; pick with the user (monthly or annual, say).
     */
    fun basePlanIds(productId: String): List<String> =
        products.filter { it.productId == productId }.mapNotNull { it.basePlanId }.distinct()
}

/** Why a coupon code cannot be redeemed. Reasons added by a newer server arrive as [UNKNOWN]. */
enum class CouponInvalidReason(val wireValue: String) {
    /** No coupon has this code in this app. */
    NOT_FOUND("not_found"),

    /** The coupon's start date is still in the future. */
    NOT_STARTED("not_started"),

    /** The coupon's end date has passed, or it was expired by hand. */
    EXPIRED("expired"),

    /** The merchant turned the coupon off. */
    DISABLED("disabled"),

    /** Every use the coupon allows has been taken. */
    EXHAUSTED("exhausted"),

    /** This user has already used the coupon as many times as it allows. */
    ALREADY_REDEEMED("already_redeemed"),

    /** This user cannot use it, for example a coupon for new customers only. */
    NOT_ELIGIBLE("not_eligible"),

    /** The coupon covers no product sold on Android. */
    NOT_AVAILABLE_ON_PLATFORM("not_available_on_platform"),

    /** The Play offer behind the coupon is still being set up. Try again later. */
    NOT_READY("not_ready"),

    /**
     * The coupon covers several base plans of this product, and `redeemCoupon` was called
     * without a `basePlanId`. Pass one of [CouponValidation.basePlanIds].
     */
    BASE_PLAN_REQUIRED("base_plan_required"),

    /** A reason this version of the SDK does not know; see [CouponValidation.rawReason]. */
    UNKNOWN("unknown"),
    ;

    /** Plain text for a refused code, safe to show to a user. */
    val message: String
        get() = when (this) {
            NOT_FOUND -> "This code is not valid."
            NOT_STARTED -> "This code is not active yet."
            EXPIRED -> "This code has expired."
            DISABLED -> "This code is no longer available."
            EXHAUSTED -> "This code has been fully redeemed."
            ALREADY_REDEEMED -> "You have already used this code."
            NOT_ELIGIBLE -> "Your account cannot use this code."
            NOT_AVAILABLE_ON_PLATFORM -> "This code cannot be used on this device."
            NOT_READY -> "This code is not ready yet. Try again in a little while."
            BASE_PLAN_REQUIRED -> "Choose a plan to use this code with."
            UNKNOWN -> "This code cannot be used right now."
        }

    companion object {
        /** The case for a wire value; [UNKNOWN] for anything else. */
        @JvmStatic
        fun fromWire(value: String?): CouponInvalidReason =
            entries.firstOrNull { it != UNKNOWN && it.wireValue == value } ?: UNKNOWN
    }
}

/** What kind of discount a coupon gives. */
enum class CouponKind(val wireValue: String) {
    /** A percentage off, for [Coupon.periodCount] periods. */
    PERCENT_OFF("percent_off"),

    /** A fixed amount off, for [Coupon.periodCount] periods. */
    AMOUNT_OFF("amount_off"),

    /** A free period of [Coupon.duration]. */
    FREE_TRIAL("free_trial"),

    /** A kind this version of the SDK does not know. */
    UNKNOWN("unknown"),
    ;

    companion object {
        @JvmStatic
        fun fromWire(value: String?): CouponKind = entries.firstOrNull { it != UNKNOWN && it.wireValue == value } ?: UNKNOWN
    }
}

/**
 * What a coupon gives, for the copy next to the code field. For the price the user will pay,
 * trust the Play sheet: amount-off prices are set per region.
 */
data class Coupon(
    /** The code, upper-case, as the merchant created it. */
    val code: String,
    val name: String,
    val kind: CouponKind,
    /** 1 to 99 for [CouponKind.PERCENT_OFF]; null otherwise. */
    val percentOff: Int?,
    /** The amount off in minor units of [currency] (cents for USD, yen for JPY); null unless [CouponKind.AMOUNT_OFF]. */
    val amountOffMinor: Long?,
    /** ISO 4217 code of [amountOffMinor]. */
    val currency: String?,
    /** ISO 8601 period of one discounted period, or of the free period (`P1M`, `P1W`, `P3D`). */
    val duration: String,
    /** How many discounted periods. Always 1 for a free period. */
    val periodCount: Int,
) {
    /** The amount off in major units (`499` USD minor units is `4.99`), or null. */
    val amountOff: BigDecimal?
        get() {
            val minor = amountOffMinor ?: return null
            val code = currency ?: return null
            return BigDecimal.valueOf(minor, CouponMoney.decimals(code))
        }

    /** The amount off formatted for display (`$4.99`, `¥500`), or null when not an amount off. */
    @JvmOverloads
    fun formattedAmountOff(locale: Locale = Locale.getDefault()): String? {
        val minor = amountOffMinor ?: return null
        val code = currency ?: return null
        return CouponMoney.format(minor, code, locale)
    }
}

/**
 * A product the coupon can be redeemed on, with the Play offer that applies it. A product whose
 * coupon covers several base plans is listed once per base plan.
 */
data class CouponProduct(
    /** The Play product id. */
    val productId: String,
    /** The base plan the coupon offer lives on. */
    val basePlanId: String?,
    /** The Play offer id (`cpn-...`). */
    val offerId: String?,
)

/**
 * Coupon failures that are not a refused code in [CouponValidation] or one of the
 * [CashSDKError] cases.
 *
 * A separate type because [CashSDKError] is exhaustively matched by apps and must not grow
 * without a source-breaking release. `redeemCoupon` and `validateCoupon` also throw
 * [CashSDKError]: `NotIdentified` for a guest, the purchase errors from the Play flow, and
 * `Network` / `Server` / `Decoding`.
 */
sealed class CouponException(message: String) : Exception(message) {
    /**
     * The server re-checked the code while reserving it and refused it. Nothing was reserved or
     * charged.
     */
    data class Rejected(val reason: CouponInvalidReason, val rawReason: String?) : CouponException(reason.message)

    /**
     * One use is reserved, but Google Play does not list the coupon's offer for this product yet.
     * A new Play offer can take a while to reach devices. Nothing was charged; try again later.
     * Calling `redeemCoupon` again returns the same reservation.
     */
    data class OfferNotAvailableYet(
        val productId: String,
        val basePlanId: String?,
        val offerId: String,
    ) : CouponException("This coupon is not available in Google Play yet. Try again in a little while.")
}

/** Minor units with the server's exponents (`@cashsdk/money`, ISO 4217), not the JDK's. */
internal object CouponMoney {
    private val ZERO_DECIMAL = setOf(
        "BIF", "CLP", "DJF", "GNF", "ISK", "JPY", "KMF", "KRW", "PYG", "RWF",
        "UGX", "VND", "VUV", "XAF", "XOF", "XPF",
    )
    private val THREE_DECIMAL = setOf("BHD", "IQD", "JOD", "KWD", "LYD", "OMR", "TND")

    fun decimals(currency: String): Int {
        val code = currency.uppercase(Locale.ROOT)
        return when {
            code in ZERO_DECIMAL -> 0
            code in THREE_DECIMAL -> 3
            else -> 2
        }
    }

    fun format(minor: Long, currency: String, locale: Locale): String {
        val code = currency.uppercase(Locale.ROOT)
        val digits = decimals(code)
        val amount = BigDecimal.valueOf(minor, digits)
        return runCatching {
            NumberFormat.getCurrencyInstance(locale).apply {
                this.currency = Currency.getInstance(code)
                minimumFractionDigits = digits
                maximumFractionDigits = digits
            }.format(amount)
        }.getOrElse { "${amount.toPlainString()} $code" }
    }
}

/**
 * The Play offer for a coupon, among a product's offers: [offerId] on [basePlanId] (any base
 * plan when null). Null when Play does not list it for this user yet. Only offers tagged
 * `cashsdk-coupon` qualify, so a merchant's own offer that happens to share the id is never
 * bought in its place.
 */
internal fun selectCouponOffer(offers: List<StoreOffer>, basePlanId: String?, offerId: String): StoreOffer? {
    val matches = offers.filter {
        it.isCouponOffer && it.offerId == offerId && (basePlanId == null || it.basePlanId == basePlanId)
    }
    return matches.singleOrNull()
}

/**
 * The analytics event for a finished `redeemCoupon`. An already-owned result charged nothing and
 * used no coupon, so it is not a successful redemption.
 */
internal fun couponOutcomeEvent(result: Entitlements): String =
    if (result.alreadyOwned) "coupon_redeem_already_owned" else "coupon_redeem_success"

// ── Wire DTOs ────────────────────────────────────────────────────────────────

@Serializable
internal data class CouponValidateRequest(val code: String, val appUserId: String, val platform: String = "android")

@Serializable
internal data class CouponRedeemRequest(
    val code: String,
    val appUserId: String,
    val productIdentifier: String,
    val platform: String = "android",
    /** Required by the server when the coupon covers several base plans of the product. */
    val basePlanId: String? = null,
)

@Serializable
internal data class CouponAndroidOfferWire(val basePlanId: String? = null, val offerId: String? = null)

@Serializable
internal data class CouponWire(
    val code: String,
    val name: String? = null,
    val kind: String,
    val percentOff: Int? = null,
    val amountOffMinor: Long? = null,
    val currency: String? = null,
    val duration: String? = null,
    val periodCount: Int? = null,
) {
    fun toModel() = Coupon(code, name ?: code, CouponKind.fromWire(kind), percentOff, amountOffMinor, currency, duration.orEmpty(), periodCount ?: 1)
}

@Serializable
internal data class CouponProductWire(val productIdentifier: String, val android: CouponAndroidOfferWire? = null)

@Serializable
internal data class CouponValidateResponse(
    val valid: Boolean,
    val reason: String? = null,
    val coupon: CouponWire? = null,
    val products: List<CouponProductWire> = emptyList(),
) {
    /** Only products with an Android offer are listed: the others cannot be redeemed here. */
    fun toModel(): CouponValidation = if (!valid) {
        refused(reason)
    } else {
        CouponValidation(
            valid = true,
            reason = null,
            rawReason = null,
            coupon = coupon?.toModel(),
            products = products.mapNotNull { product ->
                product.android?.let { CouponProduct(product.productIdentifier, it.basePlanId, it.offerId) }
            },
        )
    }

    companion object {
        fun refused(reason: String?) = CouponValidation(false, CouponInvalidReason.fromWire(reason), reason, null, emptyList())
    }
}

/** `POST /v1/coupons:redeem`. A server that refuses may answer `valid: false` with a `reason`. */
@Serializable
internal data class CouponRedeemResponse(
    val redemptionId: String? = null,
    val android: CouponAndroidOfferWire? = null,
    val valid: Boolean? = null,
    val reason: String? = null,
)

// ── Redemption flow ──────────────────────────────────────────────────────────

/**
 * Validate and redeem, without Android framework types so it runs in plain JVM tests.
 *
 * [subscriptionOffers] reads the product's offers fresh from Play (null when Play does not know
 * the product). The purchase itself goes through the ordinary purchase path the caller passes
 * to [redeem]: `obfuscatedAccountId`, verify, acknowledge and recovery are unchanged.
 */
internal class CouponRedeemer(
    private val api: ApiClient,
    private val subscriptionOffers: suspend (productId: String) -> List<StoreOffer>?,
) {
    suspend fun validate(code: String): CouponValidation {
        val userId = signedInUser()
        return api.validateCoupon(CouponValidateRequest(normalizedCode(code), userId))
    }

    suspend fun redeem(
        code: String,
        productId: String,
        basePlanId: String? = null,
        purchase: suspend (PurchaseOptions) -> Entitlements,
    ): Entitlements {
        require(productId.isNotBlank()) { "productId must not be blank" }
        require(basePlanId == null || basePlanId.isNotBlank()) { "basePlanId must not be blank when supplied" }
        val userId = signedInUser()
        val reservation = api.redeemCoupon(CouponRedeemRequest(normalizedCode(code), userId, productId, basePlanId = basePlanId))
        val offerId = reservation.android?.offerId?.takeIf { it.isNotBlank() }
            ?: throw CashSDKError.Decoding(IllegalStateException("The coupon reservation has no Play offer for $productId"))
        val reservedPlan = reservation.android.basePlanId?.takeIf { it.isNotBlank() } ?: basePlanId
        if (basePlanId != null && reservedPlan != basePlanId) {
            throw CashSDKError.Decoding(IllegalStateException("The coupon reservation is for base plan $reservedPlan, not $basePlanId"))
        }
        val offers = subscriptionOffers(productId) ?: throw CashSDKError.ProductNotFound(listOf(productId))
        val offer = selectCouponOffer(offers, reservedPlan, offerId)
            ?: throw CouponException.OfferNotAvailableYet(productId, reservedPlan, offerId)
        // Still the same user: the purchase is theirs, and the reservation is theirs.
        api.requireSameUser(userId)
        return purchase(PurchaseOptions(basePlanId = offer.basePlanId, offerId = offer.offerId, offerToken = offer.offerToken))
    }

    /** The signed-in user. A guest is refused before anything is sent. */
    private fun signedInUser(): String {
        val identity = api.identitySnapshot()
        api.requireValidUserToken(identity)
        return identity.userId ?: throw CashSDKError.NotIdentified
    }

    internal companion object {
        /** Trimmed and upper-cased, as the server matches it. */
        fun normalizedCode(code: String): String = code.trim().uppercase(Locale.ROOT)
    }
}
