package com.waymap.search

import com.waymap.geo.LatLng
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * A named position returned by a place search. [name] may be empty when Photon
 * returns no name; the caller picks the placeholder text to display.
 */
data class GeoPlace(val name: String, val pos: LatLng)

/**
 * Place search via Photon (komoot's free, keyless OpenStreetMap geocoder).
 * Results are biased toward `near` when given, so "7-11" finds the nearby one.
 *
 * @param userAgent identifies the calling app, as OSM-based services ask clients to,
 *   e.g. "MyApp/1.0 (Android)".
 */
class PhotonGeocoder(
    private val userAgent: String,
    private val endpoint: String = DEFAULT_ENDPOINT,
) {

    suspend fun search(query: String, near: LatLng?, limit: Int = 6): List<GeoPlace> =
        withContext(Dispatchers.IO) {
            val url = buildString {
                append(endpoint).append("?q=").append(URLEncoder.encode(query, "UTF-8"))
                append("&limit=").append(limit)
                near?.let { append("&lat=").append(it.lat).append("&lon=").append(it.lon) }
            }
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 8_000
                conn.readTimeout = 8_000
                conn.setRequestProperty("User-Agent", userAgent)
                if (conn.responseCode != 200) error("HTTP ${conn.responseCode}")
                parse(conn.inputStream.bufferedReader().use { it.readText() })
            } finally {
                conn.disconnect()
            }
        }

    companion object {
        const val DEFAULT_ENDPOINT = "https://photon.komoot.io/api/"

        /** Parses a Photon GeoJSON FeatureCollection. Internal for tests. */
        internal fun parse(json: String): List<GeoPlace> {
            val features = JSONObject(json).optJSONArray("features") ?: return emptyList()
            return (0 until features.length()).mapNotNull { i ->
                val f = features.getJSONObject(i)
                val coords = f.optJSONObject("geometry")?.optJSONArray("coordinates") ?: return@mapNotNull null
                val props = f.optJSONObject("properties") ?: JSONObject()
                // GeoJSON order is [lon, lat].
                GeoPlace(label(props), LatLng(coords.getDouble(1), coords.getDouble(0)))
            }
        }

        /**
         * "name, street, city" from whatever Photon returned, skipping blanks/duplicates.
         * Returns "" when nothing usable is present; the caller supplies placeholder text.
         */
        private fun label(p: JSONObject): String =
            listOf("name", "street", "district", "city", "country")
                .map { p.optString(it) }
                .filter { it.isNotBlank() }
                .distinct()
                .take(3)
                .joinToString(", ")
    }
}
