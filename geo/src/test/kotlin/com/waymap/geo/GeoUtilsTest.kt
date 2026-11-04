package com.waymap.geo

import org.junit.Assert.assertEquals
import org.junit.Test

class GeoUtilsTest {

    private val taipei101 = LatLng(25.0339, 121.5645)

    @Test
    fun haversineMatchesKnownDistances() {
        // One degree of latitude ≈ 111.2 km on the spherical model.
        assertEquals(111_195.0, GeoUtils.haversine(LatLng(0.0, 0.0), LatLng(1.0, 0.0)), 1.0)
        assertEquals(0.0, GeoUtils.haversine(taipei101, taipei101), 1e-9)
    }

    @Test
    fun bearingPointsToCardinalDirections() {
        val o = LatLng(0.0, 0.0)
        assertEquals(0.0, GeoUtils.bearing(o, LatLng(1.0, 0.0)), 1e-6)
        assertEquals(90.0, GeoUtils.bearing(o, LatLng(0.0, 1.0)), 1e-6)
        assertEquals(180.0, GeoUtils.bearing(o, LatLng(-1.0, 0.0)), 1e-6)
        assertEquals(270.0, GeoUtils.bearing(o, LatLng(0.0, -1.0)), 1e-6)
    }

    @Test
    fun destinationPointRoundTripsWithDistanceAndBearing() {
        for (bearing in listOf(0.0, 45.0, 137.0, 270.0)) {
            val d = GeoUtils.destinationPoint(taipei101, 500.0, bearing)
            assertEquals(500.0, GeoUtils.haversine(taipei101, d), 0.01)
            assertEquals(0.0, GeoUtils.angleDiff(bearing, GeoUtils.bearing(taipei101, d)), 0.01)
        }
    }

    @Test
    fun angleDiffIsSymmetricAndWraps() {
        assertEquals(0.01, GeoUtils.angleDiff(359.99, 0.0), 1e-9)
        assertEquals(0.01, GeoUtils.angleDiff(0.0, 359.99), 1e-9)
        assertEquals(180.0, GeoUtils.angleDiff(90.0, 270.0), 1e-9)
        assertEquals(30.0, GeoUtils.angleDiff(-15.0, 15.0), 1e-9)
        assertEquals(45.0, GeoUtils.angleDiff(720.0 + 10.0, 55.0), 1e-9)
    }

    @Test
    fun destinationPointWrapsAcrossTheAntimeridian() {
        val d = GeoUtils.destinationPoint(LatLng(0.0, 179.9999), 100.0, 90.0)
        assertEquals(-180.0, d.lon, 0.01)
    }

    @Test
    fun routeLengthIncludesClosingSegmentOnlyWhenClosed() {
        val a = LatLng(0.0, 0.0)
        val b = LatLng(0.001, 0.0)
        val c = LatLng(0.001, 0.001)
        val open = GeoUtils.routeLengthMeters(listOf(a, b, c))
        val closed = GeoUtils.routeLengthMeters(listOf(a, b, c), closed = true)
        assertEquals(GeoUtils.haversine(c, a), closed - open, 1e-6)
        assertEquals(0.0, GeoUtils.routeLengthMeters(listOf(a)), 0.0)
    }

    @Test
    fun kmhToMs() {
        assertEquals(1.25, GeoUtils.kmhToMs(4.5), 1e-9)
    }
}
