package com.lasvegas.secmess.model

import org.json.JSONArray
import org.json.JSONObject

data class Point(val lat: Double, val lon: Double)

/**
 * Unsafe zones are NO LONGER hardcoded. They start EMPTY and are driven entirely by the
 * server: the admin draws polygons and publishes a GeoJSON file to the discovery gist,
 * which the app fetches and loads here. Empty list => there are no unsafe zones.
 */
object UnsafePlacesRegistry {
    @Volatile
    private var polygons: List<List<Point>> = emptyList()

    fun hasZones(): Boolean = polygons.isNotEmpty()

    /** Replace the active zones from a GeoJSON string (FeatureCollection / Feature / Geometry). */
    fun loadFromGeoJson(geoJson: String?) {
        if (geoJson.isNullOrBlank()) { polygons = emptyList(); return }
        val out = mutableListOf<List<Point>>()
        try {
            val root = JSONObject(geoJson)
            val features = root.optJSONArray("features")
            when {
                features != null -> for (i in 0 until features.length()) {
                    features.optJSONObject(i)?.optJSONObject("geometry")?.let { addGeometry(it, out) }
                }
                root.optJSONObject("geometry") != null -> addGeometry(root.getJSONObject("geometry"), out)
                root.has("coordinates") -> addGeometry(root, out)
            }
        } catch (_: Exception) { /* malformed -> keep whatever we had */ return }
        polygons = out
    }

    private fun addGeometry(geom: JSONObject, out: MutableList<List<Point>>) {
        when (geom.optString("type")) {
            "Polygon" -> geom.optJSONArray("coordinates")?.optJSONArray(0)?.let { out.add(ringToPoints(it)) }
            "MultiPolygon" -> geom.optJSONArray("coordinates")?.let { multi ->
                for (p in 0 until multi.length()) {
                    multi.optJSONArray(p)?.optJSONArray(0)?.let { out.add(ringToPoints(it)) }
                }
            }
        }
    }

    // GeoJSON coordinates are [longitude, latitude].
    private fun ringToPoints(ring: JSONArray): List<Point> {
        val pts = mutableListOf<Point>()
        for (i in 0 until ring.length()) {
            val c = ring.optJSONArray(i) ?: continue
            pts.add(Point(lat = c.optDouble(1), lon = c.optDouble(0)))
        }
        return pts
    }

    /** True if the point is inside ANY active polygon. No zones => always false. */
    fun isInsideUnsafeZone(lat: Double, lon: Double): Boolean {
        val polys = polygons
        if (polys.isEmpty()) return false
        return polys.any { inPolygon(lat, lon, it) }
    }

    private fun inPolygon(lat: Double, lon: Double, polygon: List<Point>): Boolean {
        if (polygon.size < 3) return false
        var isInside = false
        var j = polygon.size - 1
        for (i in polygon.indices) {
            if (((polygon[i].lat > lat) != (polygon[j].lat > lat)) &&
                (lon < (polygon[j].lon - polygon[i].lon) * (lat - polygon[i].lat) / (polygon[j].lat - polygon[i].lat) + polygon[i].lon)
            ) {
                isInside = !isInside
            }
            j = i
        }
        return isInside
    }
}
