package com.cashsdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** JUnit has no `assertContains`; the message is what matters, so say which part is missing. */
private fun assertContains(haystack: String, needle: String) =
    assertTrue("expected to find \"$needle\" in: \"$haystack\"", haystack.contains(needle))

/**
 * Play Billing failures have to be readable by the developer who caused them.
 *
 * `Billing error (code=4)` was the message for the most common first-run failure on Android,
 * and every cause of code 4 is a Play Console setup step rather than a bug in the integrating
 * app. A bare number sent people to a search engine during their first hour with the SDK.
 */
class BillingErrorMessageTest {

    @Test
    fun `the most common first-run failure names its actual causes`() {
        val message = CashSDKError.Billing(4).message ?: ""
        // The four things that actually cause ITEM_UNAVAILABLE during a first integration.
        assertContains(message, "ACTIVE in Play Console")
        assertContains(message, "published to a track")
        assertContains(message, "signed with the same key")
        assertContains(message, "licence tester")
    }

    @Test
    fun `every documented response code explains itself`() {
        // -3..12 covers the BillingResponseCode range the client can return.
        for (code in -3..12) {
            val message = CashSDKError.Billing(code).message ?: ""
            assertTrue("code $code has no explanation: \"$message\"", message.length > 30)
            assertFalse(
                "code $code fell back to the old bare-number message",
                message.startsWith("Billing error"),
            )
            // The number stays, so it can be searched and matched against Google's docs.
            assertContains(message, "Play Billing code $code")
        }
    }

    @Test
    fun `the response code stays machine-readable for callers that branch on it`() {
        // The message got better; the shape must not change, or `when (e.responseCode)` breaks
        // in every app that already handles specific codes.
        val error = CashSDKError.Billing(7, "already owned")
        assertEquals(7, error.responseCode)
        assertEquals("already owned", error.debug)
        assertContains(error.message ?: "", "already owns")
        assertContains(error.message ?: "", "Play says: already owned")
    }

    @Test
    fun `a blank debug string does not produce a dangling separator`() {
        val message = CashSDKError.Billing(2, "  ").message ?: ""
        assertFalse("blank debug leaked into the message: \"$message\"", message.contains("Play says"))
    }

    @Test
    fun `Billing 9 purchase sub-response codes remain actionable and machine-readable`() {
        val insufficientFunds = CashSDKError.Billing(2, subResponseCode = 1)
        assertEquals(1, insufficientFunds.subResponseCode)
        assertContains(insufficientFunds.message ?: "", "insufficient funds")

        val ineligible = CashSDKError.Billing(5, subResponseCode = 2)
        assertEquals(2, ineligible.subResponseCode)
        assertContains(ineligible.message ?: "", "not eligible")
    }

    @Test
    fun `an unknown code still returns something rather than an empty message`() {
        val message = CashSDKError.Billing(9999).message ?: ""
        assertContains(message, "unexpected")
        assertContains(message, "9999")
    }
}
