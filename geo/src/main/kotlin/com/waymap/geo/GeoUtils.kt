package com.waymap.geo

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Simple immutable WGS84 coordinate. */
data class LatLng(val lat: Double, val lon: Double)

/**
 * Spherical-earth geodesy helpers. Accurate enough for the short distances a
 * map app deals with (metres to a few km), and cheap to run every location fix.
 */
object GeoUtils {

    private const val EARTH_RADIUS_M = 6_371_000.0

    /** Great-circle distance between two points, in metres. */
    fun haversine(a: LatLng, b: LatLng): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val lat1 = Math.toRadians(a.lat)
        val lat2 = Math.toRadians(b.lat)
        val h = sin(dLat / 2) * sin(dLat / 2) +
            cos(lat1) * cos(lat2) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * EARTH_RADIUS_M * atan2(sqrt(h), sqrt(1 - h))
    }

    /** Initial bearing from [a] to [b] in degrees, normalised to 0..360. */
    fun bearing(a: LatLng, b: LatLng): Double {
        val lat1 = Math.toRadians(a.lat)
        val lat2 = Math.toRadians(b.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    /** Point reached by travelling [distanceMeters] from [start] along [bearingDeg]. */
    fun destinationPoint(start: LatLng, distanceMeters: Double, bearingDeg: Double): LatLng {
        val angular = distanceMeters / EARTH_RADIUS_M
        val brng = Math.toRadians(bearingDeg)
        val lat1 = Math.toRadians(start.lat)
        val lon1 = Math.toRadians(start.lon)

        val lat2 = Math.asin(
            sin(lat1) * cos(angular) + cos(lat1) * sin(angular) * cos(brng)
        )
        val lon2 = lon1 + atan2(
            sin(brng) * sin(angular) * cos(lat1),
            cos(angular) - sin(lat1) * sin(lat2)
        )
        return LatLng(Math.toDegrees(lat2), ((Math.toDegrees(lon2) + 540.0) % 360.0) - 180.0)
    }

    /**
     * Closest point to [p] on segment [a]→[b]. Uses a local equirectangular
     * projection, which is plenty accurate at route-segment scale.
     */
    fun projectOnSegment(p: LatLng, a: LatLng, b: LatLng): LatLng {
        val k = cos(Math.toRadians(a.lat))
        val bx = (b.lon - a.lon) * k
        val by = b.lat - a.lat
        val px = (p.lon - a.lon) * k
        val py = p.lat - a.lat
        val len2 = bx * bx + by * by
        if (len2 == 0.0) return a
        val t = ((px * bx + py * by) / len2).coerceIn(0.0, 1.0)
        return LatLng(a.lat + t * (b.lat - a.lat), a.lon + t * (b.lon - a.lon))
    }

    /** Convert km/h to m/s. */
    fun kmhToMs(kmh: Double): Double = kmh / 3.6

    /** Smallest difference between two compass bearings, 0..180 (359.99° vs 0° → 0.01°). */
    fun angleDiff(a: Double, b: Double): Double {
        val d = ((a - b) % 360.0 + 360.0) % 360.0
        return minOf(d, 360.0 - d)
    }

    /**
     * Total length of a polyline in metres. When [closed] is true (looping routes),
     * the closing segment from the last point back to the first is included.
     */
    fun routeLengthMeters(waypoints: List<LatLng>, closed: Boolean = false): Double {
        if (waypoints.size < 2) return 0.0
        var total = 0.0
        for (i in 0 until waypoints.lastIndex) {
            total += haversine(waypoints[i], waypoints[i + 1])
        }
        if (closed) total += haversine(waypoints.last(), waypoints.first())
        return total
    }
}
