package com.waymap.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotonGeocoderTest {

    @Test
    fun photonResponseParses() {
        val json = """{"features":[{"geometry":{"coordinates":[121.5645,25.0339]},
            "properties":{"name":"台北101","street":"信義路五段","city":"台北市"}}]}"""
        val places = PhotonGeocoder.parse(json)
        assertEquals(1, places.size)
        assertEquals("台北101, 信義路五段, 台北市", places[0].name)
        assertEquals(25.0339, places[0].pos.lat, 1e-9)
        assertEquals(121.5645, places[0].pos.lon, 1e-9)
    }

    @Test
    fun featuresWithoutGeometryAreSkippedAndBlankNamesGetAPlaceholder() {
        val json = """{"features":[{"properties":{"name":"x"}},
            {"geometry":{"coordinates":[121.0,25.0]},"properties":{}}]}"""
        val places = PhotonGeocoder.parse(json)
        assertEquals(1, places.size)
        assertEquals("未命名地點", places[0].name)
    }

    @Test
    fun missingFeatureArrayIsEmpty() {
        assertTrue(PhotonGeocoder.parse("{}").isEmpty())
    }
}
