package com.waymap.ui

import kotlin.math.abs

/** Smallest zoom difference worth animating to. */
internal const val FOLLOW_ZOOM_MIN_DELTA = 0.25

/** Minimum time between two automatic zoom changes. */
internal const val FOLLOW_ZOOM_MIN_INTERVAL_MS = 3000L

/**
 * Throttle for speed-based follow zoom: change zoom only when [target] differs from
 * the [current] zoom by at least [FOLLOW_ZOOM_MIN_DELTA] and the previous automatic
 * change ([lastChangeMs], null if none yet) is at least [FOLLOW_ZOOM_MIN_INTERVAL_MS] old.
 */
internal fun shouldApplyFollowZoom(target: Double, current: Double, nowMs: Long, lastChangeMs: Long?): Boolean =
    abs(target - current) >= FOLLOW_ZOOM_MIN_DELTA &&
        (lastChangeMs == null || nowMs - lastChangeMs >= FOLLOW_ZOOM_MIN_INTERVAL_MS)
