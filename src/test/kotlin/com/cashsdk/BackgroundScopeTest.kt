package com.cashsdk

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.MalformedURLException

@OptIn(ExperimentalCoroutinesApi::class)
class BackgroundScopeTest {
    @Test fun aFailureInBackgroundWorkIsLoggedAndTheOtherJobsCarryOn() = runTest {
        val logged = mutableListOf<Throwable>()
        val scope = sdkBackgroundScope(StandardTestDispatcher(testScheduler)) { logged += it }
        // What a malformed apiBase used to throw out of the deadline watch.
        scope.launch { throw MalformedURLException("no protocol: not a url") }
        runCurrent()
        assertEquals(1, logged.size)
        assertTrue(logged.single() is MalformedURLException)
        var ranAfter = false
        scope.launch { ranAfter = true }
        runCurrent()
        assertTrue("one failed job does not take the scope down", ranAfter)
        scope.cancel()
    }
}
