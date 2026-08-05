package com.cashsdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Golden vectors for the canonical appAccountToken. These MUST match the server
 * (apps/api test/app-account-token.test.ts) and the iOS SDK byte-for-byte, or a Play purchase's
 * obfuscatedExternalAccountId won't match the AppUser row and attribution silently misses.
 */
class AppAccountTokenTest {
    @Test fun canonicalShape() {
        assertTrue(Regex("^00000000-0000-4000-8000-[0-9a-f]{12}$").matches(AppAccountToken.derive("1")))
    }

    @Test fun numericIdsEmbedTheId() {
        // Reversible; identical to the backend deriveAppAccountToken + the seed's aat().
        assertEquals("00000000-0000-4000-8000-000000000001", AppAccountToken.derive("1"))
        assertEquals("00000000-0000-4000-8000-00000000007b", AppAccountToken.derive("123"))
        assertEquals("00000000-0000-4000-8000-0000000f4240", AppAccountToken.derive("1000000"))
        assertEquals("00000000-0000-4000-8000-00000000002a", AppAccountToken.derive("42"))
    }

    @Test fun opaqueIdsUseFnv() {
        assertEquals("00000000-0000-4000-8000-e33fe1b269b9", AppAccountToken.derive("user_abc"))
        assertEquals("00000000-0000-4000-8000-1af6ca2ab142", AppAccountToken.derive("u_9f3a"))
        assertEquals("00000000-0000-4000-8000-1a0e23dd7369", AppAccountToken.derive("anon-xyz"))
    }

    @Test fun leadingSignFallsToFnv() {
        // toULongOrNull("+123")==123, but the server regex /^[0-9]+$/ rejects the +, so it FNV-hashes.
        // The derive() fix must reject a sign too, or a phone-number (E.164) user id misses attribution.
        assertEquals("00000000-0000-4000-8000-69c0cff2262e", AppAccountToken.derive("+123"))
        assertEquals("00000000-0000-4000-8000-129949cbd436", AppAccountToken.derive("+15551234567"))
        assertEquals("00000000-0000-4000-8000-0f07b497d7f7", AppAccountToken.derive("-5"))
    }

    @Test fun zeroPaddedNumericIdsCollide() {
        // DOCUMENTED CONSTRAINT, not a bug to "fix" here. The numeric branch embeds the VALUE of
        // the id, so "7", "07" and "007" all derive the same token — on the server, on iOS and on
        // Android alike (byte-parity is the whole point, and changing one side would break it).
        //
        // The consequence for an integrator: a backend that emits zero-padded user ids merges two
        // users' purchases onto one account. Pass the un-padded id to identify(), or use an
        // opaque (non-numeric) id, which always FNV-hashes and never collides this way.
        assertEquals(AppAccountToken.derive("7"), AppAccountToken.derive("007"))
        assertEquals(AppAccountToken.derive("42"), AppAccountToken.derive("0000042"))
        // Prefixing with a non-digit forces the FNV branch, which IS padding-sensitive.
        assertNotEquals(AppAccountToken.derive("u7"), AppAccountToken.derive("u007"))
    }

    @Test fun determinismAndUniqueness() {
        assertEquals(AppAccountToken.derive("user_abc"), AppAccountToken.derive("user_abc"))
        assertNotEquals(AppAccountToken.derive("user_abc"), AppAccountToken.derive("user_abd"))
        // A numeric id past 2^48-1 can't embed → hashed, never a wrong-truncated zero.
        assertNotEquals("00000000-0000-4000-8000-000000000000", AppAccountToken.derive("281474976710656"))
    }
}
