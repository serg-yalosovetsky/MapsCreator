package com.mapscreator.tiles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic unit tests for tile math and URL templating.
 * These run on the JVM (no Android framework), so `./gradlew testDebugUnitTest` is fast.
 */
class TileMathTest {

    @Test
    fun xyzUrlSubstitutesZXY() {
        assertEquals(
            "https://tile.openstreetmap.org/5/10/20.png",
            TileSource.OSM.tileUrl(z = 5, x = 10, y = 20)
        )
    }

    @Test
    fun arcgisTemplatePlacesYBeforeX() {
        // ArcGIS template is .../tile/{z}/{y}/{x}
        assertEquals(
            "https://server.arcgisonline.com/ArcGIS/rest/services/" +
                "World_Imagery/MapServer/tile/4/6/8",
            TileSource.ARCGIS_SATELLITE.tileUrl(z = 4, x = 8, y = 6)
        )
    }

    @Test
    fun bingUsesQuadKey() {
        // Canonical Microsoft example: tile x=3, y=5, z=3 -> quadkey "213".
        assertEquals(
            "https://ecn.t3.tiles.virtualearth.net/tiles/a213.jpeg?g=1",
            TileSource.BING_SATELLITE.tileUrl(z = 3, x = 3, y = 5)
        )
    }

    @Test
    fun tileCountIsOneAtZoomZero() {
        // Zoom 0 has a single tile covering the whole world.
        assertEquals(1, TileSizeEstimator.tileCount(50.0, 50.1, 30.0, 30.1, zoom = 0))
    }

    @Test
    fun estimateMultipliesTileCountByAvgSize() {
        val estimates = TileSizeEstimator.estimate(
            minLat = 50.0, maxLat = 50.1, minLon = 30.0, maxLon = 30.1,
            zoomLevels = 0..0,
            sources = listOf(TileSource.OSM)
        )
        val osm = estimates.getValue(TileSource.OSM)
        assertEquals(1, osm.tileCount)
        assertEquals(10L * 1024, osm.estimatedBytes) // avgTileSizeKb=10
    }

    @Test
    fun formatBytesIsHumanReadable() {
        assertEquals("512 B", TileSizeEstimator.formatBytes(512))
        assertEquals("2 KB", TileSizeEstimator.formatBytes(2048))
        assertTrue(TileSizeEstimator.formatBytes(3L * 1_048_576).endsWith("MB"))
    }
}
