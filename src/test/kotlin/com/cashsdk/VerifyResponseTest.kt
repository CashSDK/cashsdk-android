package com.cashsdk

import com.cashsdk.model.Entitlements
import com.cashsdk.net.VerifyAttribution
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The verify response is the money path's decision point: from it the SDK decides whether to
 * cache a snapshot, and whether to consume/acknowledge a purchase with Google. These cover the
 * three shapes that used to be indistinguishable — granted, pending, and unattributed.
 *
 * Bodies are copied from `apps/api` (`PlayController.verifyPurchase`), not invented.
 */
class VerifyResponseTest {

    private val json = Json { ignoreUnknownKeys = true }

    // ── PENDING ────────────────────────────────────────────────────────────────

    private val pendingBody =
        """{"entitlements":[],"tier":0,"tierIdentifier":null,"pending":true}"""

    @Test
    fun pendingIsDistinguishableFromSettledEmpty() {
        val pending = json.decodeFromString<Entitlements>(pendingBody)
        val settledEmpty = json.decodeFromString<Entitlements>(
            """{"entitlements":[],"tier":0,"tierIdentifier":null,"consumables":[],"productType":"consumable"}""",
        )
        assertTrue("a deferred purchase must be recognisable as pending", pending.pending)
        assertFalse("a settled-but-empty snapshot is not pending", settledEmpty.pending)
    }

    @Test
    fun grantedResponseCarriesProductTypeAndBalances() {
        val granted = json.decodeFromString<Entitlements>(
            """{"entitlements":[{"identifier":"pro","name":"Pro","rank":10,"source":"subscription"}],
               |"tier":10,"tierIdentifier":"pro","consumables":[{"productIdentifier":"coins_100","balance":100}],
               |"productType":"consumable","quantity":1,"acknowledged":true}""".trimMargin(),
        )
        assertTrue(granted.isActive("pro"))
        assertEquals(100, granted.balanceOf("coins_100"))
        assertEquals("consumable", granted.productType)
        assertEquals(true, granted.acknowledged)
        assertFalse(granted.pending)
    }

    // ── ATTRIBUTION ────────────────────────────────────────────────────────────

    @Test
    fun unattributedEmptyResponseIsDetected() {
        // The exact early-return the API sends when lifecycle could not resolve a user.
        val body = """{"entitlements":[],"tier":0,"tierIdentifier":null,"acknowledged":true}"""
        val decoded = json.decodeFromString<Entitlements>(body)
        assertFalse(VerifyAttribution.isAttributed(body, decoded))
    }

    @Test
    fun attributedEmptyResponseIsNotMistakenForUnattributed() {
        // A real user who simply holds nothing: the `consumables` key is present.
        val body = """{"entitlements":[],"tier":0,"tierIdentifier":null,"consumables":[]}"""
        val decoded = json.decodeFromString<Entitlements>(body)
        assertTrue(VerifyAttribution.isAttributed(body, decoded))
    }

    @Test
    fun explicitServerSignalWinsOverTheHeuristic() {
        // Once the API returns an explicit flag (see the SDK handoff note) it is authoritative.
        val no = """{"entitlements":[],"tier":0,"tierIdentifier":null,"consumables":[],"attributed":false}"""
        val yes = """{"entitlements":[],"tier":0,"tierIdentifier":null,"attributed":true}"""
        assertFalse(VerifyAttribution.isAttributed(no, json.decodeFromString(no)))
        assertTrue(VerifyAttribution.isAttributed(yes, json.decodeFromString(yes)))
    }

    @Test
    fun unparseableBodyIsAssumedAttributed() {
        // Failing open here strands nothing; failing closed would reject a real purchase.
        assertTrue(VerifyAttribution.isAttributed("not json", Entitlements.EMPTY))
    }

    // ── CACHING ────────────────────────────────────────────────────────────────

    @Test
    fun perTransactionFieldsAreStrippedBeforeCaching() {
        val verify = json.decodeFromString<Entitlements>(
            """{"entitlements":[],"tier":0,"tierIdentifier":null,"productType":"consumable",
               |"quantity":3,"pending":true,"acknowledged":true,"transferredFromAnotherAccount":true}""".trimMargin(),
        )
        val cached = verify.gatingSnapshot()
        assertFalse("pending must never survive into a hydrated gating snapshot", cached.pending)
        assertNull(cached.productType)
        assertNull(cached.acknowledged)
        assertEquals(1, cached.quantity)
        assertFalse("a transfer notice is shown once, not on every launch", cached.transferredFromAnotherAccount)
    }
}
