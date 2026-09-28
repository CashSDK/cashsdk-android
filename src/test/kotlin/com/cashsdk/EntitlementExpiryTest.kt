package com.cashsdk

import com.cashsdk.model.Entitlement
import com.cashsdk.model.Entitlements
import com.cashsdk.model.nextExpiryMillis
import com.cashsdk.model.parseIsoInstantMillis
import com.cashsdk.model.withoutExpired
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Cached access used to have no end: `Entitlement` did not decode `expiresAt`, so a refunded or
 * lapsed subscription kept unlocking the app until the process restarted.
 */
class EntitlementExpiryTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val now = Instant.parse("2026-09-23T12:00:00Z").toEpochMilli()

    private fun ent(id: String, rank: Int?, expiresAt: String?) = Entitlement(id, id, rank, "subscription", expiresAt)

    // ── ISO-8601 ──────────────────────────────────────────────────────────────

    @Test fun parsesWhatTheServerWritesExactlyLikeJavaTime() {
        for (text in listOf(
            "2026-09-23T12:00:00.000Z", "2026-09-23T12:15:00.123Z", "1970-01-01T00:00:00.000Z",
            "2000-02-29T23:59:59.999Z", "2024-12-31T23:59:59Z", "1969-12-31T23:59:59.500Z",
        )) {
            assertEquals(text, Instant.parse(text).toEpochMilli(), parseIsoInstantMillis(text))
        }
    }

    @Test fun acceptsOffsetsAndMissingPartsButNotNonsense() {
        assertEquals(Instant.parse("2026-09-23T06:30:00Z").toEpochMilli(), parseIsoInstantMillis("2026-09-23T12:00:00+05:30"))
        assertEquals(Instant.parse("2026-09-23T20:00:00Z").toEpochMilli(), parseIsoInstantMillis("2026-09-23T12:00-0800"))
        assertEquals(Instant.parse("2026-09-23T12:00:00.500Z").toEpochMilli(), parseIsoInstantMillis("2026-09-23T12:00:00.5Z"))
        assertEquals(Instant.parse("+10000-01-01T00:00:00Z").toEpochMilli(), parseIsoInstantMillis("+010000-01-01T00:00:00.000Z"))
        for (bad in listOf("", "soon", "2026-13-01T00:00:00Z", "2026-02-30T00:00:00Z", "2026-09-23", "1727000000000", "2026-09-23T25:00:00Z")) {
            assertNull(bad, parseIsoInstantMillis(bad))
        }
    }

    // ── One entitlement ─────────────────────────────────────────────────────────

    @Test fun accessEndsAtTheDeadlineAndNotBefore() {
        val e = ent("pro", 1, "2026-09-23T12:00:00.000Z")
        assertTrue(e.isActiveAt(now - 1))
        assertFalse("the deadline itself is already past", e.isActiveAt(now))
        assertFalse(e.isActiveAt(now + 1))
    }

    @Test fun nullMeansPerpetualAndUnreadableMeansEnded() {
        assertTrue(ent("lifetime", 1, null).isActiveAt(Long.MAX_VALUE))
        assertNull(ent("lifetime", 1, null).expiresAtMillis)
        assertFalse("a deadline we cannot read must not grant forever", ent("pro", 1, "next tuesday").isActiveAt(now))
    }

    @Test fun decodesExpiresAtFromTheServerSnapshot() {
        // Shape of EntitlementService.compute(): subscription access carries its deadline
        // (renewal leeway already added), a lifetime purchase carries null.
        val body = """{"entitlements":[
            |{"identifier":"pro","name":"Pro","rank":3,"source":"subscription","expiresAt":"2026-10-23T12:15:00.000Z"},
            |{"identifier":"lifetime","name":"Lifetime","rank":1,"source":"purchase","expiresAt":null}],
            |"tier":3,"tierIdentifier":"pro","version":4,"environment":"Production","userId":"A"}""".trimMargin()
        val decoded = json.decodeFromString<Entitlements>(body)
        assertEquals("2026-10-23T12:15:00.000Z", decoded.active[0].expiresAt)
        assertEquals(Instant.parse("2026-10-23T12:15:00Z").toEpochMilli(), decoded.active[0].expiresAtMillis)
        assertNull(decoded.active[1].expiresAt)
        // An older cache or server without the field still decodes, as perpetual.
        assertNull(json.decodeFromString<Entitlement>("""{"identifier":"pro"}""").expiresAt)
    }

    // ── Whole snapshot ──────────────────────────────────────────────────────────

    private val snapshot = Entitlements(
        active = listOf(
            ent("premium", 5, "2026-09-23T11:00:00.000Z"),
            ent("pro", 3, "2026-09-30T12:00:00.000Z"),
            ent("basic", 1, null),
        ),
        tier = 5,
        tierIdentifier = "premium",
    )

    @Test fun expiredAccessIsRemovedAndTheTierRecomputedFromWhatIsLeft() {
        val judged = snapshot.withoutExpired(now)
        assertEquals(listOf("pro", "basic"), judged.active.map { it.identifier })
        assertEquals(3, judged.tier)
        assertEquals("pro", judged.tierIdentifier)
    }

    @Test fun whenEverythingExpiresTheTierFallsToNothing() {
        val judged = Entitlements(listOf(ent("pro", 3, "2026-09-23T11:00:00.000Z")), tier = 3, tierIdentifier = "pro").withoutExpired(now)
        assertTrue(judged.active.isEmpty())
        assertEquals(0, judged.tier)
        assertNull(judged.tierIdentifier)
    }

    @Test fun tierRulesMatchTheServer() {
        // Highest rank wins, the first listed wins a tie, and rank 0 or none never becomes the tier.
        val judged = Entitlements(
            listOf(ent("gone", 9, "2026-09-23T11:00:00.000Z"), ent("unranked", null, null), ent("a", 2, null), ent("b", 2, null)),
            tier = 9, tierIdentifier = "gone",
        ).withoutExpired(now)
        assertEquals(2, judged.tier)
        assertEquals("a", judged.tierIdentifier)
    }

    @Test fun nothingExpiredKeepsTheSameInstanceSoListenersAreNotWoken() {
        val fresh = snapshot.withoutExpired(now - 3_600_001)
        assertSame(snapshot, fresh)
    }

    @Test fun nextDeadlineIsTheEarliestOneStillAhead() {
        assertEquals(Instant.parse("2026-09-30T12:00:00Z").toEpochMilli(), snapshot.nextExpiryMillis(now))
        assertEquals(Instant.parse("2026-09-23T11:00:00Z").toEpochMilli(), snapshot.nextExpiryMillis(now - 7_200_000))
        assertNull(Entitlements(listOf(ent("basic", 1, null))).nextExpiryMillis(now))
    }

    @Test fun gatingChecksIgnoreAccessWhoseDeadlineHasPassed() {
        // A snapshot the host kept from before a deadline is still judged at call time.
        val held = Entitlements(listOf(ent("pro", 3, "2001-01-01T00:00:00.000Z"), ent("lifetime", 1, null)))
        assertFalse(held.isActive("pro"))
        assertTrue(held.isActive("lifetime"))
        assertTrue(held.hasActiveEntitlement)
        assertFalse(Entitlements(listOf(ent("pro", 3, "2001-01-01T00:00:00.000Z"))).hasActiveEntitlement)
        assertTrue(Entitlements(listOf(ent("pro", 3, "2999-01-01T00:00:00.000Z"))).isActive("pro"))
    }
}
