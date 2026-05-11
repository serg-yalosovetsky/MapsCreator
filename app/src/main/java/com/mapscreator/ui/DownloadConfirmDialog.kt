package com.mapscreator.ui

import android.content.Context
import androidx.appcompat.app.AlertDialog
import com.mapscreator.tiles.MapArea
import com.mapscreator.tiles.MBTilesStore
import com.mapscreator.tiles.TileSource
import com.mapscreator.tiles.TileSizeEstimator
import kotlinx.coroutines.*

class DownloadConfirmDialog(
    private val context: Context,
    private val store: MBTilesStore,
    private val area: MapArea,
    private val onConfirm: (sourceIds: List<String>, zoomMin: Int, zoomMax: Int, forceRefresh: Boolean) -> Unit
) {
    fun show() {
        val sourceIds = area.sources.split(",").filter { it.isNotBlank() }
        val sources = TileSource.PRESETS.filter { it.id in sourceIds }
        val zoomMin = area.zoomMin
        val zoomMax = area.zoomMax

        val bbox = parseBboxFromGeojson(area.geojson)
        val estimates = TileSizeEstimator.estimate(bbox.minLat, bbox.maxLat, bbox.minLon, bbox.maxLon, zoomMin..zoomMax, sources)

        val lines = StringBuilder()
        var totalTiles = 0
        var totalBytes = 0L
        var totalCached = 0

        for ((src, est) in estimates) {
            val tiles = TileSizeEstimator.tilesInBbox(bbox.minLat, bbox.maxLat, bbox.minLon, bbox.maxLon, zoomMin)
            val cached = store.countCached(tiles, src.id)
            val toDownload = (est.tileCount - cached).coerceAtLeast(0)
            lines.appendLine("${src.name}: ${est.tileCount} тайлов (~${TileSizeEstimator.formatBytes(est.estimatedBytes)}), в кеше: $cached")
            totalTiles += est.tileCount
            totalBytes += est.estimatedBytes
            totalCached += cached
        }

        val toDownload = (totalTiles - totalCached).coerceAtLeast(0)
        lines.appendLine()
        lines.append("Итого: ~$totalTiles тайлов, ${TileSizeEstimator.formatBytes(totalBytes)}\n")
        lines.append("Уже скачано: $totalCached / $totalTiles\nСкачать новых: $toDownload")

        AlertDialog.Builder(context)
            .setTitle("Загрузить «${area.name}»?")
            .setMessage(lines.toString())
            .setPositiveButton("Только новые ($toDownload)") { _, _ ->
                onConfirm(sourceIds, zoomMin, zoomMax, false)
            }
            .setNeutralButton("Обновить всё") { _, _ ->
                onConfirm(sourceIds, zoomMin, zoomMax, true)
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private data class Bbox(val minLat: Double, val maxLat: Double, val minLon: Double, val maxLon: Double)

    private fun parseBboxFromGeojson(geojson: String): Bbox {
        val nums = Regex("""-?\d+\.\d+""").findAll(geojson).map { it.value.toDouble() }.toList()
        val lons = nums.filterIndexed { i, _ -> i % 2 == 0 }
        val lats = nums.filterIndexed { i, _ -> i % 2 == 1 }
        return Bbox(
            minLat = lats.minOrNull() ?: 0.0, maxLat = lats.maxOrNull() ?: 0.0,
            minLon = lons.minOrNull() ?: 0.0, maxLon = lons.maxOrNull() ?: 0.0
        )
    }
}
