package no.blckswan.monicakey

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object Exporter {
    fun gpx(points: List<LocationPoint>): String {
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        return buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            append("<gpx version=\"1.1\" creator=\"Monica Key\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
            append("  <trk><name>Monica Key tidslinje</name><trkseg>\n")
            points.forEach { p ->
                append("    <trkpt lat=\"").append(p.latitude).append("\" lon=\"").append(p.longitude).append("\">")
                append("<time>").append(iso.format(Date(p.timestamp))).append("</time>")
                append("<hdop>").append(p.accuracy).append("</hdop>")
                append("</trkpt>\n")
            }
            append("  </trkseg></trk>\n</gpx>\n")
        }
    }

    fun geoJson(points: List<LocationPoint>): String {
        val coordinates = JSONArray()
        points.forEach { p -> coordinates.put(JSONArray().put(p.longitude).put(p.latitude)) }
        val feature = JSONObject()
            .put("type", "Feature")
            .put("properties", JSONObject()
                .put("name", "Monica Key tidslinje")
                .put("pointCount", points.size)
                .put("from", points.firstOrNull()?.timestamp)
                .put("to", points.lastOrNull()?.timestamp))
            .put("geometry", JSONObject()
                .put("type", "LineString")
                .put("coordinates", coordinates))
        return JSONObject()
            .put("type", "FeatureCollection")
            .put("features", JSONArray().put(feature))
            .toString(2)
    }
}
