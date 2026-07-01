package com.mapscreator.export

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import com.mapscreator.tiles.MBTilesStore
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sinh
import kotlin.math.tan

private const val MAGIC: Int = 0x474D4E44 // 'G','M','N','D'
private const val VERSION: Byte = 1
private const val TILE_ENTRY_SIZE = 21
private const val HEADER_FIXED_SIZE = 24

/**
 * Экспорт GMND-бандла для часов (garmiand / TileDecoder.mc).
 *
 * Часы декодируют один OSM-зум за раз и переключаются между уровнями
 * overview/normal/detail (исторически z12/z13/z15 — см. NavigationView.checkZoomSwitch),
 * поэтому бандл собирается СРАЗУ на трёх зумах с теми же лимитами, что и
 * garmiand/TileQuantizer.quantizeMultiZoom:
 *
 *   z12: 64×64 px,  до 4 тайлов  (~16 KB)  — обзор
 *   z13: 128×128 px, до 12 тайлов (~192 KB) — основной
 *   z15: 128×128 px, до 6 тайлов  (~96 KB)  — детальный
 *
 * Итого ≈ 304 KB — лимит RAM Fenix 7 (~678 KB на blob + декодированные bitmap'ы).
 *
 * Тайлы берутся из офлайн-хранилища MBTiles. Если нужного зума в сторе нет,
 * тайл собирается из потомков (z+1..z+3, даунскейл) или вырезается из предка
 * (z-1..z-3, апскейл) — так экспорт работает при любом скачанном диапазоне зумов.
 * Выбор тайлов — от центра bbox наружу, а не «первые N по порядку».
 */
class GmndExporter(private val store: MBTilesStore) {

    data class ExportResult(
        val file: File,
        val tileCount: Int,
        val bytes: Int,
        val perZoom: Map<Int, Int>,
    )

    private data class ZoomSpec(val zoom: Int, val maxTiles: Int, val outputPx: Int)

    // Должно соответствовать ожиданиям часов (NavigationView) и бюджету RAM Fenix 7.
    private val zoomSpecs = listOf(
        ZoomSpec(12, 4, 64),
        ZoomSpec(13, 12, 128),
        ZoomSpec(15, 6, 128),
    )

    fun export(
        sourceId: String,
        minLat: Double, maxLat: Double, minLon: Double, maxLon: Double,
        outputFile: File,
    ): ExportResult {
        val cLat = (minLat + maxLat) / 2.0
        val cLon = (minLon + maxLon) / 2.0

        val tiles = mutableListOf<QuantizedTile>()
        val perZoom = mutableMapOf<Int, Int>()
        for (spec in zoomSpecs) {
            val coords = pickTiles(minLat, maxLat, minLon, maxLon, cLat, cLon, spec.zoom, spec.maxTiles)
            var added = 0
            for ((tx, ty) in coords) {
                val bmp = renderTile(spec.zoom, tx, ty, sourceId, spec.outputPx) ?: continue
                val pixels = quantizeBitmap(bmp)
                bmp.recycle()
                tiles += QuantizedTile(spec.zoom, tx, ty, spec.outputPx, spec.outputPx, pixels)
                added++
            }
            perZoom[spec.zoom] = added
        }

        // bbox заголовка — реальный охват включённых тайлов, не исходный bbox области
        // (часы используют его как fallback-viewport, когда маршрут не загружен).
        var bMinLat = Double.MAX_VALUE
        var bMaxLat = -Double.MAX_VALUE
        var bMinLon = Double.MAX_VALUE
        var bMaxLon = -Double.MAX_VALUE
        for (t in tiles) {
            val n = 1 shl t.zoom
            val west = t.tileX.toDouble() / n * 360.0 - 180.0
            val east = (t.tileX + 1).toDouble() / n * 360.0 - 180.0
            val north = tileYToLat(t.tileY.toDouble(), n)
            val south = tileYToLat((t.tileY + 1).toDouble(), n)
            if (south < bMinLat) bMinLat = south
            if (north > bMaxLat) bMaxLat = north
            if (west < bMinLon) bMinLon = west
            if (east > bMaxLon) bMaxLon = east
        }
        if (tiles.isEmpty()) {
            bMinLat = minLat; bMaxLat = maxLat; bMinLon = minLon; bMaxLon = maxLon
        }

        val blob = serialize(bMinLat, bMaxLat, bMinLon, bMaxLon, tiles)
        outputFile.writeBytes(blob)
        return ExportResult(outputFile, tiles.size, blob.size, perZoom)
    }

    /** Тайлы bbox на данном зуме, отсортированные от центра области наружу, максимум [cap]. */
    private fun pickTiles(
        minLat: Double, maxLat: Double, minLon: Double, maxLon: Double,
        cLat: Double, cLon: Double,
        zoom: Int, cap: Int,
    ): List<Pair<Int, Int>> {
        val (x0, y0) = latLonToTile(maxLat, minLon, zoom)
        val (x1, y1) = latLonToTile(minLat, maxLon, zoom)
        val (cx, cy) = latLonToTileFractional(cLat, cLon, zoom)
        val all = mutableListOf<Pair<Int, Int>>()
        for (x in x0..x1) for (y in y0..y1) all += x to y
        return all.sortedBy { (x, y) ->
            val dx = (x + 0.5) - cx
            val dy = (y + 0.5) - cy
            dx * dx + dy * dy
        }.take(cap)
    }

    /**
     * Рендер тайла (z, x, y) размером [outputPx] из офлайн-стора:
     *  1) точный тайл; 2) сборка из потомков z+1..z+3 (даунскейл);
     *  3) вырезка из предка z-1..z-3 (апскейл); 4) null — тайл пропускается.
     */
    private fun renderTile(z: Int, x: Int, y: Int, sourceId: String, outputPx: Int): Bitmap? {
        decodeStoredTile(z, x, y, sourceId)?.let { return scaleTo(it, outputPx) }

        for (dz in 1..3) {
            val composed = composeFromChildren(z, x, y, dz, sourceId, outputPx)
            if (composed != null) return composed
        }
        for (dz in 1..3) {
            val cropped = cropFromAncestor(z, x, y, dz, sourceId, outputPx)
            if (cropped != null) return cropped
        }
        return null
    }

    private fun decodeStoredTile(z: Int, x: Int, y: Int, sourceId: String): Bitmap? {
        val raw = store.getTile(z, x, y, sourceId) ?: return null
        return BitmapFactory.decodeByteArray(raw, 0, raw.size)
    }

    /** Собрать тайл из сетки 2^dz × 2^dz потомков на зуме z+dz. Null, если ни одного потомка нет. */
    private fun composeFromChildren(z: Int, x: Int, y: Int, dz: Int, sourceId: String, outputPx: Int): Bitmap? {
        val grid = 1 shl dz
        val sub = outputPx / grid
        if (sub < 1) return null
        var found = false
        val canvasBmp = Bitmap.createBitmap(outputPx, outputPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(canvasBmp)
        for (cy in 0 until grid) {
            for (cx in 0 until grid) {
                val child = decodeStoredTile(z + dz, (x shl dz) + cx, (y shl dz) + cy, sourceId) ?: continue
                found = true
                canvas.drawBitmap(child, null, Rect(cx * sub, cy * sub, (cx + 1) * sub, (cy + 1) * sub), null)
                child.recycle()
            }
        }
        if (!found) {
            canvasBmp.recycle()
            return null
        }
        return canvasBmp
    }

    /** Вырезать четверть/шестнадцатую и т.д. из предка на зуме z-dz и растянуть до [outputPx]. */
    private fun cropFromAncestor(z: Int, x: Int, y: Int, dz: Int, sourceId: String, outputPx: Int): Bitmap? {
        if (z - dz < 1) return null
        val parent = decodeStoredTile(z - dz, x shr dz, y shr dz, sourceId) ?: return null
        val grid = 1 shl dz
        val subPx = parent.width / grid
        if (subPx < 1) { parent.recycle(); return null }
        val sx = (x and (grid - 1)) * subPx
        val sy = (y and (grid - 1)) * subPx
        val out = Bitmap.createBitmap(outputPx, outputPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawBitmap(parent, Rect(sx, sy, sx + subPx, sy + subPx), Rect(0, 0, outputPx, outputPx), null)
        parent.recycle()
        return out
    }

    private fun scaleTo(bmp: Bitmap, outputPx: Int): Bitmap =
        if (bmp.width == outputPx && bmp.height == outputPx) bmp
        else Bitmap.createScaledBitmap(bmp, outputPx, outputPx, true).also { if (it !== bmp) bmp.recycle() }

    private fun quantizeBitmap(bmp: Bitmap): ByteArray {
        val w = bmp.width
        val h = bmp.height
        val rowMajor = IntArray(w * h)
        bmp.getPixels(rowMajor, 0, w, 0, 0, w, h)
        val out = ByteArray(w * h)
        for (col in 0 until w)
            for (row in 0 until h)
                out[col * h + row] = Palette.nearest(rowMajor[row * w + col]).toByte()
        return out
    }

    private fun serialize(
        minLat: Double, maxLat: Double, minLon: Double, maxLon: Double,
        tiles: List<QuantizedTile>,
    ): ByteArray {
        val paletteBytes = Palette.toBytes()
        val entriesOffset = HEADER_FIXED_SIZE + paletteBytes.size
        val pixelsStart = entriesOffset + tiles.size * TILE_ENTRY_SIZE
        val totalSize = pixelsStart + tiles.sumOf { it.pixels.size }

        val buf = ByteBuffer.allocate(totalSize).order(ByteOrder.BIG_ENDIAN)
        buf.putInt(MAGIC)
        buf.put(VERSION)
        buf.put(Palette.SIZE.toByte())
        buf.putShort(tiles.size.toShort())
        buf.putFloat(minLat.toFloat())
        buf.putFloat(maxLat.toFloat())
        buf.putFloat(minLon.toFloat())
        buf.putFloat(maxLon.toFloat())
        buf.put(paletteBytes)

        var pixelOffset = pixelsStart
        for (t in tiles) {
            buf.put(t.zoom.toByte())
            buf.putInt(t.tileX)
            buf.putInt(t.tileY)
            buf.putShort(t.width.toShort())
            buf.putShort(t.height.toShort())
            buf.putInt(pixelOffset)
            buf.putInt(t.pixels.size)
            pixelOffset += t.pixels.size
        }
        for (t in tiles) buf.put(t.pixels)

        return buf.array()
    }

    private fun latLonToTile(lat: Double, lon: Double, zoom: Int): Pair<Int, Int> {
        val (fx, fy) = latLonToTileFractional(lat, lon, zoom)
        val n = 1 shl zoom
        return fx.toInt().coerceIn(0, n - 1) to fy.toInt().coerceIn(0, n - 1)
    }

    private fun latLonToTileFractional(lat: Double, lon: Double, zoom: Int): Pair<Double, Double> {
        val latRad = lat * PI / 180.0
        val n = (1 shl zoom).toDouble()
        val x = (lon + 180.0) / 360.0 * n
        val y = (1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / PI) / 2.0 * n
        return x to y
    }

    private fun tileYToLat(ty: Double, n: Int): Double =
        Math.toDegrees(atan(sinh(PI * (1.0 - 2.0 * ty / n))))

    private data class QuantizedTile(
        val zoom: Int, val tileX: Int, val tileY: Int,
        val width: Int, val height: Int, val pixels: ByteArray,
    )
}
