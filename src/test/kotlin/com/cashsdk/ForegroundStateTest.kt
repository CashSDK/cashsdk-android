package com.cashsdk

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundStateTest {
    private var processVisible = false
    private val state = ForegroundState { processVisible }

    @Test fun configuredAfterTheFirstActivityStartedStillReadsForeground() {
        // Midgame configures during its first composition: that start was never reported.
        processVisible = true
        assertTrue(state.isForeground)
        // Its stop is reported; the count stays at zero and the process decides.
        state.activityStopped()
        processVisible = false
        assertFalse(state.isForeground)
    }

    @Test fun startedActivitiesAreForegroundWhateverTheProcessSays() {
        assertFalse(state.isForeground)
        assertTrue("the first start is a fresh foreground", state.activityStarted())
        assertTrue(state.isForeground)
        assertFalse("a second activity is not", state.activityStarted())
        state.activityStopped()
        assertTrue(state.isForeground)
        state.activityStopped()
        assertFalse(state.isForeground)
        assertTrue("coming back is a fresh foreground again", state.activityStarted())
    }

    @Test fun anUnreportedStartFollowedByAReportedOneKeepsTheForeground() {
        processVisible = true // activity A started before configure()
        state.activityStarted() // activity B, reported
        state.activityStopped() // A stops; the count reaches zero while B is still started
        assertTrue("the process still has a visible activity", state.isForeground)
    }

    @Test fun extraStopsNeverCountBelowZero() {
        repeat(3) { state.activityStopped() }
        assertTrue(state.activityStarted())
        assertTrue(state.isForeground)
    }
}
