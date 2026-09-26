package com.cashsdk

import com.cashsdk.billing.ProductCache
import com.cashsdk.model.PurchaseKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A paywall renders, the customer reads it, then taps Buy. `purchase()` used to ask Play for the
 * same `ProductDetails` again on that tap, under a 15-second deadline, with an owned-purchases
 * query under another one behind it, so a slow Play connection alone put tens of seconds between
 * the tap and the sheet.
 *
 * `ProductDetails` carries the `offerToken` a purchase is made with and Play regenerates those,
 * so this cache is short-lived and gives an entry up at the first sign it is wrong. The rules
 * live here, away from Play, which is why the cache is generic.
 */
class ProductCacheTest {
    private var now = 0L
    private fun cache() = ProductCache<String>({ now })

    @Test fun aStoredEntryComesBack() {
        val cache = cache()
        assertNull(cache.get("pro", PurchaseKind.SUBSCRIPTION))
        cache.put("pro", PurchaseKind.SUBSCRIPTION, "details")
        assertEquals("details", cache.get("pro", PurchaseKind.SUBSCRIPTION))
        assertEquals(1, cache.size())
    }

    @Test fun anEntryIsUsableUpToItsTtlAndNotAfter() {
        val cache = cache()
        cache.put("pro", PurchaseKind.SUBSCRIPTION, "details")
        now = ProductCache.TTL_MS
        assertEquals("still inside the window", "details", cache.get("pro", PurchaseKind.SUBSCRIPTION))
        now = ProductCache.TTL_MS + 1
        assertNull("past the window", cache.get("pro", PurchaseKind.SUBSCRIPTION))
        assertEquals("and the expired entry is dropped, not left to grow", 0, cache.size())
    }

    /** A device clock that moved backwards must not make an entry look newer than it is. */
    @Test fun anEntryFromTheFutureIsTreatedAsStale() {
        val cache = cache()
        now = 10_000
        cache.put("pro", PurchaseKind.SUBSCRIPTION, "details")
        now = 0
        assertNull(cache.get("pro", PurchaseKind.SUBSCRIPTION))
    }

    /**
     * The same identifier can exist as a subscription and as a one-time product. Handing the
     * purchase the wrong one is a wrong charge, not a cosmetic bug.
     */
    @Test fun theKindIsPartOfTheKey() {
        val cache = cache()
        cache.put("pro", PurchaseKind.SUBSCRIPTION, "subscription")
        cache.put("pro", PurchaseKind.PRODUCT, "one-time")
        assertEquals("subscription", cache.get("pro", PurchaseKind.SUBSCRIPTION))
        assertEquals("one-time", cache.get("pro", PurchaseKind.PRODUCT))
    }

    /** What the one-shot retry does after Play refuses a token: forget that product alone. */
    @Test fun invalidatingTouchesOneProduct() {
        val cache = cache()
        cache.put("pro", PurchaseKind.SUBSCRIPTION, "a")
        cache.put("plus", PurchaseKind.SUBSCRIPTION, "b")
        cache.invalidate("pro", PurchaseKind.SUBSCRIPTION)
        assertNull(cache.get("pro", PurchaseKind.SUBSCRIPTION))
        assertEquals("b", cache.get("plus", PurchaseKind.SUBSCRIPTION))
        cache.invalidate("missing", PurchaseKind.PRODUCT)
    }

    /** A reconnect can bring a different catalog: another storefront, a different Google account. */
    @Test fun aDroppedConnectionForgetsEverything() {
        val cache = cache()
        cache.put("pro", PurchaseKind.SUBSCRIPTION, "a")
        cache.put("plus", PurchaseKind.PRODUCT, "b")
        cache.clear()
        assertEquals(0, cache.size())
        assertNull(cache.get("pro", PurchaseKind.SUBSCRIPTION))
    }
}
