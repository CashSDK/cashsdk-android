package com.cashsdk

import com.cashsdk.entitlements.EntitlementStore
import com.cashsdk.entitlements.SnapshotStorage
import com.cashsdk.model.Entitlement
import com.cashsdk.model.Entitlements
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.Dispatchers
import java.time.Instant

/**
 * The store published whatever it last cached, forever. It now keeps the server's record intact
 * (so its ETag still describes it) and publishes it without anything past its deadline.
 */
class EntitlementStoreTest {
    private class MemoryStorage : SnapshotStorage {
        var value: String? = null
        var failWrites = false
        override fun read(): String? = value
        override fun write(value: String): Boolean {
            if (failWrites) return false
            this.value = value
            return true
        }
        override fun remove() {
            value = null
        }
    }

    private val storage = MemoryStorage()
    private var now = Instant.parse("2026-09-23T12:00:00Z").toEpochMilli()
    // `io = Dispatchers.Unconfined` keeps the write on the test's own thread, so a test can assert
    // on what landed in `storage` on the next line instead of pumping a scheduler.
    private fun store() = EntitlementStore(storage, environment = "Production", clock = { now }, io = Dispatchers.Unconfined)

    private val proUntilOne = Entitlement("pro", "Pro", 3, "subscription", "2026-09-23T13:00:00.000Z")
    private val lifetime = Entitlement("basic", "Basic", 1, "purchase", null)
    private val snapshot = Entitlements(listOf(proUntilOne, lifetime), tier = 3, tierIdentifier = "pro", userId = "A")

    @After fun resetClock() = ServerClock.reset()

    // Every assertion reads the store (judged with the injected clock) or calls isActiveAt(now).
    // Entitlements.isActive(id) uses the real clock, so it must not be used with these 2026
    // deadlines: from 13:00 UTC on 2026-09-23 it would read them as expired.
    private fun Entitlements.ids() = active.map { it.identifier }

    @Test fun accessDisappearsAtItsDeadlineWithoutAnyNetwork() = runTest {
        val store = store()
        store.update("A", snapshot, "W/\"etag\"")
        assertEquals(listOf("pro", "basic"), store.current.ids())
        assertEquals(3, store.snapshot.value.tier)

        now = Instant.parse("2026-09-23T13:00:00Z").toEpochMilli()
        // The getter judges at read time even before anything republishes.
        assertEquals(listOf("basic"), store.current.ids())
        assertEquals("basic", store.current.tierIdentifier)

        store.republish()
        assertEquals(listOf("basic"), store.snapshot.value.ids())
        assertEquals(1, store.snapshot.value.tier)
        // The raw record and its ETag are untouched, so a 304 still revalidates it.
        assertEquals("W/\"etag\"", store.etag)
        assertEquals(2, store.rawSnapshot.value.active.size)
    }

    @Test fun anExpiredCacheIsNeverServedOnTheNextLaunch() = runTest {
        store().update("A", snapshot, "W/\"etag\"")
        now = Instant.parse("2026-09-24T00:00:00Z").toEpochMilli()
        val relaunched = store()
        relaunched.hydrate("A")
        assertEquals(listOf("basic"), relaunched.current.ids())
        assertEquals(listOf("basic"), relaunched.snapshot.value.ids())
        assertEquals("W/\"etag\"", relaunched.etag)
    }

    @Test fun anotherUsersRecordStillYieldsNothing() = runTest {
        store().update("A", snapshot, "W/\"etag\"")
        val other = store()
        other.hydrate("B")
        assertEquals(Entitlements.EMPTY, other.current)
        assertNull(other.etag)
        assertNull("someone else's record is dropped from disk", storage.value)
    }

    @Test fun aRecordWrittenBeforeExpiresAtExistedStillHydrates() = runTest {
        storage.value = """{"userId":"A","environment":"Production","entitlements":{"entitlements":[{"identifier":"pro","name":"Pro","rank":3,"source":"subscription"}],"tier":3,"tierIdentifier":"pro"},"etag":"W/\"old\""}"""
        val store = store()
        store.hydrate("A")
        assertEquals(listOf("pro"), store.current.ids())
        assertTrue("no deadline means perpetual", store.current.active.single().isActiveAt(Long.MAX_VALUE))
        assertEquals("W/\"old\"", store.etag)
    }

    @Test fun aFailedWriteDropsTheEtagButStillPublishes() = runTest {
        storage.failWrites = true
        val store = store()
        assertFalse(store.update("A", snapshot, "W/\"etag\""))
        assertNull(store.etag)
        assertEquals(listOf("pro", "basic"), store.current.ids())
    }

    @Test fun perTransactionFieldsNeverReachTheCache() = runTest {
        val store = store()
        store.update("A", snapshot.copy(transferredFromAnotherAccount = true, alreadyOwned = true, pending = true), null)
        assertFalse(store.current.transferredFromAnotherAccount)
        assertFalse(store.current.alreadyOwned)
        assertFalse(store.current.pending)
    }

    /**
     * `purchaseOutcomeConfirmed` describes ONE verify: the server recorded that purchase but could
     * not confirm the access it grants. Cached, it was republished on every later read and hydrated
     * again on the next launch, so a host checking it kept running its recovery for a purchase that
     * had long since been confirmed. iOS already stripped it; Android did not.
     */
    @Test fun anUnconfirmedPurchaseOutcomeIsNotRememberedAsStandingAccess() = runTest {
        val store = store()
        store.update("A", snapshot.copy(purchaseOutcomeConfirmed = false), null)
        assertNull(store.current.purchaseOutcomeConfirmed)
        // And it does not come back from disk either.
        store.hydrate("A")
        assertNull(store.current.purchaseOutcomeConfirmed)
    }

    @Test fun theServerClockOffsetSurvivesARelaunch() = runTest {
        // Two responses agree: the device is an hour behind.
        ServerClock.observe(serverDateMillis = 10_000_000, deviceMillis = 6_400_000)
        ServerClock.observe(serverDateMillis = 20_000_000, deviceMillis = 16_400_000)
        assertEquals(3_600_000, ServerClock.offsetMs)
        store().update("A", snapshot, null)
        ServerClock.reset()
        store().hydrate("A")
        assertEquals(3_600_000, ServerClock.offsetMs)
        // An offset this run agrees on wins over what was persisted.
        ServerClock.observe(serverDateMillis = 5_000, deviceMillis = 5_000)
        ServerClock.observe(serverDateMillis = 9_000, deviceMillis = 9_000)
        store().hydrate("A")
        assertEquals(0, ServerClock.offsetMs)
    }
}
