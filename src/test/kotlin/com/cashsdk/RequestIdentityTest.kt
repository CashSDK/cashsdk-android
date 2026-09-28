package com.cashsdk

import com.cashsdk.net.RequestIdentity
import org.junit.Assert.*
import org.junit.Test

class RequestIdentityTest {
    @Test fun headerPairIsImmutableAcrossAccountChanges() {
        val identity = RequestIdentity()
        identity.set("A", "token-A")
        val request = identity.current
        identity.set("B", "token-B")
        assertEquals("A", request.userId)
        assertEquals("token-A", request.userToken)
        assertEquals("B", identity.current.userId)
        assertEquals("token-B", identity.current.userToken)
        assertFalse(identity.isCurrent(request))
    }

    @Test fun signingBackIntoSameAccountDoesNotReviveOldRequests() {
        val identity = RequestIdentity()
        identity.set("A", "token-A")
        val request = identity.current
        identity.set(null, null)
        identity.set("A", "token-A")
        assertFalse(identity.isCurrent(request))
        assertTrue(identity.isCurrent(identity.current))
    }

    @Test fun tokenRefreshInvalidatesRequestsWithExpiredCredentials() {
        val identity = RequestIdentity()
        identity.set("A", "expired")
        val request = identity.current
        identity.set("A", "fresh")
        assertFalse(identity.isCurrent(request))
        assertEquals("fresh", identity.current.userToken)
    }
}
