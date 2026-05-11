package com.mapscreator.tiles

import kotlin.math.*

object TileSizeEstimator {

    data class SourceEstimate(val tileCount: Int, val estimatedBytes: Long)

    fun estimate(
        minLat: Double, maxLat: Double, minLon: Double, maxLon: Double,
        zoomLevels: IntRange,
        sources: List<TileSource>
    ): Map<TileSource, SourceEstimate> = sources.associateWith { source ->
        val count = zoomLevels.sumOf { z -> tileCount(minLat, maxLat, minLon, maxLon, z) }
        SourceEstimate(count, count.toLong() * source.avgTileSizeKb * 1024)
    }

    fun tileCount(minLat: Double, maxLat: Double, minLon: Double, maxLon: Double, zoom: Int): Int {
        val (xMin, yMin) = latLonToTile(maxLat, minLon, zoom)
        val (xMax, yMax) = latLonToTile(minLat, maxLon, zoom)
        return (xMax - xMin + 1).coerceAtLeast(1) * (yMax - yMin + 1).coerceAtLeast(1)
    }

    fun tilesInBbox(
        minLat: Double, maxLat: Double, minLon: Double, maxLon: Double,
        zoom: Int
    ): List<TileCoord> {
        val (xMin, yMin) = latLonToTile(maxLat, minLon, zoom)
        val (xMax, yMax) = latLonToTile(minLat, maxLon, zoom)
        return buildList {
            for (x in xMin..xMax)
                for (y in yMin..yMax)
                    add(TileCoord(zoom, x, y))
        }
    }

    fun formatBytes(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> String.format("%.1f MB", bytes / 1_048_576.0)
    }

    private fun latLonToTile(lat: Double, lon: Double, zoom: Int): Pair<Int, Int> {
        val n = 1 shl zoom
        val x = ((lon + 180.0) / 360.0 * n).toInt().coerceIn(0, n - 1)
        val latRad = Math.toRadians(lat)
        val y = ((1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / PI) / 2.0 * n).toInt().coerceIn(0, n - 1)
        return x to y
    }
}
