package com.mapscreator.export

import com.mapscreator.tiles.TileCoord
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sinh
import kotlin.math.tan

/**
 * Планировщик тайлов коридора вокруг маршрута — общий для предпросмотра в UI,
 * GMND-экспорта на часы, экспорта в OsmAnd и офлайн-скачивания.
 *
 * Тайлы возвращаются в порядке ПЕРВОГО КАСАНИЯ маршрутом: если кап часов
 * режет список, остаётся непрерывный кусок от начала маршрута — предсказуемо
 * и видно в предпросмотре.
 */
object CorridorPlanner {

    data class LatLon(val lat: Double, val lon: Double)

    data class ZoomPlan(
        val zoom: Int,
        val outputPx: Int,
        val watchCap: Int,
        /** Весь коридор на этом зуме, в порядке first-touch вдоль маршрута. */
        val tiles: List<TileCoord>,
    ) {
        /** Что реально уедет на часы (обрезано по капу). */
        val watchTiles: List<TileCoord> get() = tiles.take(watchCap)
        val watchBytes: Int get() = watchTiles.size * outputPx * outputPx
        val truncated: Boolean get() = tiles.size > watchCap
    }

    // Бюджет пиксельных данных GMND-бандла: blob + декодированные bitmap'ы
    // должны влезть в ~678 KB свободной RAM Fenix 7.
    private const val WATCH_PIXEL_BUDGET = 320 * 1024

    fun outputPxFor(zoom: Int): Int = if (zoom <= 12) 64 else 128

    /**
     * Кап тайлов на зум для часов. Классическое трио 12/13/15 → 4/12/6
     * (совместимо с garmiand/TileQuantizer); любой другой набор — равный
     * делёж пиксельного бюджета.
     */
    fun watchCaps(zooms: List<Int>): Map<Int, Int> {
        val sorted = zooms.distinct().sorted()
        if (sorted == listOf(12, 13, 15)) return mapOf(12 to 4, 13 to 12, 15 to 6)
        if (sorted.isEmpty()) return emptyMap()
        val perZoomBudget = WATCH_PIXEL_BUDGET / sorted.size
        return sorted.associateWith { z ->
            val px = outputPxFor(z)
            (perZoomBudget / (px * px)).coerceAtLeast(1)
        }
    }

    fun plan(points: List<LatLon>, bufferMeters: Double, zooms: List<Int>): List<ZoomPlan> {
        val caps = watchCaps(zooms)
        return zooms.distinct().sorted().map { z ->
            ZoomPlan(
                zoom = z,
                outputPx = outputPxFor(z),
                watchCap = caps[z] ?: 1,
                tiles = corridorTiles(points, bufferMeters, z),
            )
        }
    }

    /** Тайлы в пределах [bufferMeters] от любой точки маршрута, порядок first-touch. */
    fun corridorTiles(points: List<LatLon>, bufferMeters: Double, zoom: Int): List<TileCoord> {
        val seen = LinkedHashSet<Long>()
        for (pt in points) {
            val bufLat = bufferMeters / 111_000.0
            val bufLon = bufferMeters / (111_000.0 * cos(pt.lat * PI / 180.0))
            val (tx0, ty0) = latLonToTileFractional(pt.lat + bufLat, pt.lon - bufLon, zoom)
            val (tx1, ty1) = latLonToTileFractional(pt.lat - bufLat, pt.lon + bufLon, zoom)
            for (ty in floor(ty0).toInt()..floor(ty1).toInt()) {
                for (tx in floor(tx0).toInt()..floor(tx1).toInt()) {
                    seen.add((tx.toLong() shl 32) or (ty.toLong() and 0xFFFFFFFFL))
                }
            }
        }
        return seen.map { TileCoord(zoom, (it shr 32).toInt(), it.toInt()) }
    }

    /** Географические границы тайла: [north, south, west, east]. */
    fun tileBounds(zoom: Int, x: Int, y: Int): DoubleArray {
        val n = 1 shl zoom
        val west = x.toDouble() / n * 360.0 - 180.0
        val east = (x + 1).toDouble() / n * 360.0 - 180.0
        val north = tileYToLat(y.toDouble(), n)
        val south = tileYToLat((y + 1).toDouble(), n)
        return doubleArrayOf(north, south, west, east)
    }

    fun latLonToTileFractional(lat: Double, lon: Double, zoom: Int): Pair<Double, Double> {
        val latRad = lat * PI / 180.0
        val n = (1 shl zoom).toDouble()
        val x = (lon + 180.0) / 360.0 * n
        val y = (1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / PI) / 2.0 * n
        return x to y
    }

    fun tileYToLat(ty: Double, n: Int): Double =
        Math.toDegrees(atan(sinh(PI * (1.0 - 2.0 * ty / n))))
}
