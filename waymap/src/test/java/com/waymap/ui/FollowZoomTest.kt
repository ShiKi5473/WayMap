package com.waymap.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FollowZoomTest {
    @Test
    fun smallDeltaIsIgnored() {
        assertFalse(shouldApplyFollowZoom(16.2, 16.0, nowMs = 10_000, lastChangeMs = null))
        assertFalse(shouldApplyFollowZoom(15.9, 16.0, nowMs = 10_000, lastChangeMs = null))
    }

    @Test
    fun deltaAtThresholdApplies() {
        assertTrue(shouldApplyFollowZoom(16.25, 16.0, nowMs = 10_000, lastChangeMs = null))
        assertTrue(shouldApplyFollowZoom(15.75, 16.0, nowMs = 10_000, lastChangeMs = null))
    }

    @Test
    fun firstChangeIsNotThrottled() {
        assertTrue(shouldApplyFollowZoom(17.0, 16.0, nowMs = 0, lastChangeMs = null))
    }

    @Test
    fun throttledWithinInterval() {
        assertFalse(shouldApplyFollowZoom(17.0, 16.0, nowMs = 12_999, lastChangeMs = 10_000))
    }

    @Test
    fun allowedAfterInterval() {
        assertTrue(shouldApplyFollowZoom(17.0, 16.0, nowMs = 13_000, lastChangeMs = 10_000))
    }

    @Test
    fun smallDeltaStillIgnoredAfterInterval() {
        assertFalse(shouldApplyFollowZoom(16.1, 16.0, nowMs = 60_000, lastChangeMs = 10_000))
    }
}
