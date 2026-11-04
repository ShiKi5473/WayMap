package com.waymap.ui

import android.graphics.Bitmap
import android.view.Gravity
import com.waymap.geo.LatLng

/**
 * A one-shot camera move. Deliberately NOT a data class: every request is a new
 * identity, so tapping "my location" again at the same coordinates still recenters.
 * When [zoom] is set the map zooms in to at least that level (never zooms out).
 */
class CenterRequest(val point: LatLng, val zoom: Double? = null)

/**
 * An image registered into the map style under [id]. [factory] runs once per style
 * load, so it may allocate freely. Not a data class: lambdas don't compare by value,
 * and icons are matched by [id] only.
 */
class MapIcon(val id: String, val factory: () -> Bitmap)

/**
 * A single annotation drawn with an icon and an optional text label. Meant for a
 * handful of interactive markers (positions, waypoints); use [PointLayer] for
 * hundreds or thousands of static points.
 *
 * Markers are matched by [id] between updates: a marker whose only change is
 * [pos] or [icon] is moved in place, so a position updated every second is cheap.
 */
data class MapMarker(
    val id: String,
    val pos: LatLng,
    /** A [MapIcon.id] passed to the map's `icons`. */
    val icon: String,
    val label: String? = null,
    val labelSize: Float = 15f,
    val labelColor: String = "#FFFFFF",
    val labelHaloColor: String = "#000000",
    val labelHaloWidth: Float = 1.2f,
    /** Higher keys draw above lower ones. */
    val sortKey: Float = 0f,
    /** Only tappable markers are hit-tested and reported via `onMarkerTap`. */
    val tappable: Boolean = false,
)

/** A polyline, optionally drawn over a wider [casingColor] line so it reads on any basemap. */
data class MapLine(
    val id: String,
    val points: List<LatLng>,
    val color: String,
    val width: Float = 4f,
    val casingColor: String? = null,
    val casingWidth: Float = 7f,
)

/** One feature of a [PointLayer]. */
data class MapPoint(
    val id: String,
    val pos: LatLng,
    /** A [MapIcon.id] passed to the map's `icons`. */
    val icon: String,
)

/**
 * A large set of points rendered by the GPU from a single GeoJSON source (thousands
 * of points stay smooth). Drawn above [MapLine]s and below [MapMarker]s.
 *
 * The source is re-uploaded only when [points] changes, so keep the list stable
 * (e.g. `remember`) instead of rebuilding it on every recomposition.
 */
data class PointLayer(
    val id: String,
    val points: List<MapPoint>,
    /** Hidden below this zoom level. */
    val minZoom: Float = 0f,
    val iconSize: Float = 1f,
    /** Whether taps on these points are reported via `onPointTap`. */
    val tappable: Boolean = true,
)

/** How the device's own location is shown and whether the camera follows it. */
enum class UserLocationMode {
    /** No location puck. */
    HIDDEN,

    /** Blue dot; the camera centers on the first fix only and is never taken over. */
    SHOW,

    /** Camera follows the location, north up. */
    FOLLOW,

    /** Camera follows the location and rotates with the direction of travel (driving). */
    FOLLOW_BEARING,
}

/** Compass position; it is shown only while the map is rotated (tap to face north). */
data class CompassPlacement(
    val gravity: Int = Gravity.BOTTOM or Gravity.END,
    val startDp: Int = 0,
    val topDp: Int = 0,
    val endDp: Int = 16,
    val bottomDp: Int = 76,
)
