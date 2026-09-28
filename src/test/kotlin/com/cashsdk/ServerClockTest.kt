package com.cashsdk

import com.cashsdk.model.Entitlement
import com.cashsdk.model.Entitlements
import com.cashsdk.net.ApiClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.Base64

/**
 * One `Date` header used to set the clock the SDK judges access with: a single bad one (a proxy,
 * a captive portal) hours ahead ended every cached entitlement and made the pre-purchase token
 * check refuse valid tokens.
 */
class ServerClockTest {
    private val hour = 3_600_000L

    @After fun reset() = ServerClock.reset()

    @Test fun aSingleOutlierIsIgnored() {
        ServerClock.observe(serverDateMillis = 1_000_000 + 5 * hour, deviceMillis = 1_000_000)
        assertEquals(0L, ServerClock.offsetMs)
        // The next response disagrees with it, so it never becomes the clock.
        ServerClock.observe(serverDateMillis = 2_000_000, deviceMillis = 2_000_000)
        assertEquals(0L, ServerClock.offsetMs)
    }

    @Test fun twoResponsesThatAgreeSetTheOffset() {
        ServerClock.observe(serverDateMillis = 10 * hour, deviceMillis = 7 * hour)
        ServerClock.observe(serverDateMillis = 12 * hour + 40_000, deviceMillis = 9 * hour)
        assertEquals(3 * hour + 40_000, ServerClock.offsetMs)
    }

    @Test fun responsesThatDisagreeByMoreThanAMinuteDoNot() {
        ServerClock.observe(serverDateMillis = 10 * hour, deviceMillis = 7 * hour)
        ServerClock.observe(serverDateMillis = 12 * hour + 61_000, deviceMillis = 9 * hour)
        assertEquals(0L, ServerClock.offsetMs)
    }

    @Test fun smallDifferencesAreNoise() {
        repeat(2) { ServerClock.observe(serverDateMillis = 1_004_000, deviceMillis = 1_000_000) }
        assertEquals(0L, ServerClock.offsetMs)
    }

    @Test fun anyOffsetTwoResponsesAgreeOnIsAdopted() {
        // Agreement is the protection, not a size limit: a clock a year off needs it most.
        val year = 365 * 24 * hour
        repeat(2) { ServerClock.observe(serverDateMillis = 10 * year, deviceMillis = 9 * year) }
        assertEquals(year, ServerClock.offsetMs)
        repeat(2) { ServerClock.observe(serverDateMillis = 0, deviceMillis = 2 * year) }
        assertEquals(-2 * year, ServerClock.offsetMs)
    }

    @Test fun aDeviceAWeekAheadStillSeesItsWeeklySubscription() {
        // The device clock runs 7 days 15 minutes ahead of the server. A weekly subscription that
        // the server says ends in 6 days reads as long expired on the device clock alone.
        val ahead = 7 * 24 * hour + 15 * 60_000
        val deviceNow = System.currentTimeMillis()
        repeat(2) { i -> ServerClock.observe(serverDateMillis = deviceNow - ahead + i * 1_000, deviceMillis = deviceNow + i * 1_000) }
        assertEquals(-ahead, ServerClock.offsetMs)
        val serverNow = deviceNow - ahead
        val weekly = Entitlements(listOf(Entitlement("pro", expiresAt = Instant.ofEpochMilli(serverNow + 6 * 24 * hour).toString())))
        assertTrue(weekly.isActive("pro"))
        assertTrue(weekly.hasActiveEntitlement)
        // And a refund the server ended an hour ago is gone, although the device clock would keep it.
        val refunded = Entitlements(listOf(Entitlement("pro", expiresAt = Instant.ofEpochMilli(serverNow - hour).toString())))
        assertFalse(refunded.isActive("pro"))
    }

    @Test fun aDeviceBehindNoLongerKeepsRefundedAccess() {
        val behind = 30 * 24 * hour
        val deviceNow = System.currentTimeMillis()
        repeat(2) { i -> ServerClock.observe(serverDateMillis = deviceNow + behind + i * 1_000, deviceMillis = deviceNow + i * 1_000) }
        val endedYesterdayOnTheServer = Entitlements(listOf(Entitlement("pro", expiresAt = Instant.ofEpochMilli(deviceNow + behind - 24 * hour).toString())))
        assertFalse(endedYesterdayOnTheServer.isActive("pro"))
    }

    @Test fun theLenientClockIsNeverAheadOfEither() {
        repeat(2) { ServerClock.observe(serverDateMillis = 5 * hour, deviceMillis = 3 * hour) }
        val device = System.currentTimeMillis()
        assertTrue(ServerClock.lenientNowMillis() <= device + 1_000)
        assertTrue(ServerClock.lenientNowMillis() <= ServerClock.nowMillis())
    }

    @Test fun aWrongOffsetCannotRefuseATokenTheServerWouldAccept() {
        // Two agreeing (but wrong) responses put the clock two hours ahead.
        repeat(2) { i -> ServerClock.observe(serverDateMillis = (10 + i) * hour + 2 * hour, deviceMillis = (10 + i) * hour) }
        assertEquals(2 * hour, ServerClock.offsetMs)
        val expiresInAnHour = System.currentTimeMillis() / 1000 + 3_600
        val token = "h.${Base64.getUrlEncoder().withoutPadding().encodeToString("""{"sub":"A","exp":$expiresInAnHour}""".toByteArray())}.s"
        val api = ApiClient(Configuration("csk_pk_test"))
        api.setIdentity("A", token)
        api.requireValidUserToken(api.identitySnapshot()) // does not throw
        assertTrue(api.hasUsableToken())
    }

    @Test fun anAgreedOffsetOutranksThePersistedOne() {
        ServerClock.restore(hour)
        assertEquals(hour, ServerClock.offsetMs)
        repeat(2) { ServerClock.observe(serverDateMillis = 5_000, deviceMillis = 5_000) }
        assertEquals(0L, ServerClock.offsetMs)
        ServerClock.restore(hour)
        assertEquals("a later hydrate must not undo what this run agreed on", 0L, ServerClock.offsetMs)
    }
}
