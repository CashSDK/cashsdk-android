package com.cashsdk.billing

import com.cashsdk.model.PurchaseKind

/**
 * The `ProductDetails` Play last returned, for the seconds between a paywall rendering and the
 * customer tapping Buy.
 *
 * `purchase()` cannot open the payment sheet without `ProductDetails`, and it used to ask Play
 * for them again on every tap even though the paywall had just fetched the same product to show
 * its price. That lookup runs under a 15-second deadline, and a subscription purchase follows it
 * with an owned-purchases query under another one, so a slow Play connection alone could put tens
 * of seconds between the tap and the sheet. A customer reads that as the app being broken and
 * taps again.
 *
 * Kept deliberately short. The entry carries the `offerToken` the purchase is made with, and Play
 * regenerates those; a stale one is refused at the sheet. [TTL_MS] is well inside the window Play
 * keeps them valid for, and any purchase that is refused for a stale token invalidates its entry
 * so the retry fetches fresh details (see `BillingManager.purchaseInner`).
 *
 * Cleared whenever the billing connection drops, because a reconnect can bring a different
 * catalog (a price change, a region change, a different signed-in Google account).
 */
internal class ProductCache<T : Any>(private val clock: () -> Long) {

    private data class Entry<T>(val details: T, val storedAt: Long)

    private val lock = Any()
    private val entries = HashMap<String, Entry<T>>()

    /** The cached details for this product, or null when absent or older than [TTL_MS]. */
    fun get(productId: String, kind: PurchaseKind): T? = synchronized(lock) {
        val key = key(productId, kind)
        val entry = entries[key] ?: return null
        val age = clock() - entry.storedAt
        // A clock that moved backwards makes `age` negative; treat that as stale rather than
        // trusting an entry we cannot date.
        if (age < 0 || age > TTL_MS) {
            entries.remove(key)
            return null
        }
        return entry.details
    }

    fun put(productId: String, kind: PurchaseKind, details: T): Unit = synchronized(lock) {
        entries[key(productId, kind)] = Entry(details, clock())
    }

    /** Forget one product: its offer token was refused, or its price is being re-read. */
    fun invalidate(productId: String, kind: PurchaseKind): Unit = synchronized(lock) {
        entries.remove(key(productId, kind))
    }

    /** Forget everything: the connection dropped, or the Google account may have changed. */
    fun clear(): Unit = synchronized(lock) { entries.clear() }

    fun size(): Int = synchronized(lock) { entries.size }

    private fun key(productId: String, kind: PurchaseKind) = "$productId|${kind.name}"

    internal companion object {
        /** How long a cached `ProductDetails` may be used for. */
        const val TTL_MS = 5 * 60_000L
    }
}
