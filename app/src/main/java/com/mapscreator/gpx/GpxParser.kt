package com.mapscreator.gpx

import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream
import kotlin.math.cos

data class GpxRoute(
    val name: String,
    val points: List<LatLon>,
    val minLat: Double,
    val maxLat: Double,
    val minLon: Double,
    val maxLon: Double
) {
    data class LatLon(val lat: Double, val lon: Double)

    fun withCorridorBuffer(bufferMeters: Double = 500.0): GpxRoute {
        val bufLat = bufferMeters / 111_000.0
        val midLat = (minLat + maxLat) / 2.0
        val bufLon = bufferMeters / (111_000.0 * cos(Math.toRadians(midLat)))
        return copy(
            minLat = minLat - bufLat,
            maxLat = maxLat + bufLat,
            minLon = minLon - bufLon,
            maxLon = maxLon + bufLon
        )
    }
}

object GpxParser {
    private val POINT_TAGS = setOf("trkpt", "rtept", "wpt")

    fun parse(input: InputStream, defaultName: String = "Route"): GpxRoute {
        val points = mutableListOf<GpxRoute.LatLon>()
        var routeName = defaultName
        val parser = XmlPullParserFactory.newInstance().newPullParser().apply {
            setInput(input, null)
        }
        var event = parser.eventType
        while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            if (event == org.xmlpull.v1.XmlPullParser.START_TAG) {
                when (parser.name) {
                    in POINT_TAGS -> {
                        val lat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull()
                        val lon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull()
                        if (lat != null && lon != null) points += GpxRoute.LatLon(lat, lon)
                    }
                    "name" -> {
                        val text = parser.nextText().trim()
                        if (text.isNotEmpty()) routeName = text
                    }
                }
            }
            event = parser.next()
        }
        require(points.isNotEmpty()) { "GPX file contains no track/route points" }
        return GpxRoute(
            name = routeName,
            points = points,
            minLat = points.minOf { it.lat },
            maxLat = points.maxOf { it.lat },
            minLon = points.minOf { it.lon },
            maxLon = points.maxOf { it.lon }
        )
    }
}
