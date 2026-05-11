package com.mapscreator.export

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.mapscreator.tiles.MBTilesStore
import com.mapscreator.tiles.TileCoord
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val OUTPUT_SIZE = 128
private const val MAX_TILES = 20
private const val MAGIC: Int = 0x474D4E44 // 'G','M','N','D'
private const val VERSION: Byte = 1
private const val TILE_ENTRY_SIZE = 21
private const val HEADER_FIXED_SIZE = 24

class GmndExporter(private val store: MBTilesStore) {

    data class ExportResult(val file: File, val tileCount: Int, val bytes: Int)

    fun export(
        tiles: List<TileCoord>,
        sourceId: String,
        minLat: Double, maxLat: Double, minLon: Double, maxLon: Double,
        outputFile: File
    ): ExportResult {
        val capped = if (tiles.size > MAX_TILES) tiles.take(MAX_TILES) else tiles

        val quantized = capped.mapNotNull { (z, x, y) ->
            val raw = store.getTile(z, x, y, sourceId) ?: return@mapNotNull null
            val bmp = BitmapFactory.decodeByteArray(raw, 0, raw.size) ?: return@mapNotNull null
            val resized = if (bmp.width == OUTPUT_SIZE && bmp.height == OUTPUT_SIZE) bmp
            else Bitmap.createScaledBitmap(bmp, OUTPUT_SIZE, OUTPUT_SIZE, true)
                .also { if (it !== bmp) bmp.recycle() }
            val pixels = quantizeBitmap(resized)
            resized.recycle()
            QuantizedTile(z, x, y, OUTPUT_SIZE, OUTPUT_SIZE, pixels)
        }

        val blob = serialize(minLat, maxLat, minLon, maxLon, quantized)
        outputFile.writeBytes(blob)
        return ExportResult(outputFile, quantized.size, blob.size)
    }

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
        tiles: List<QuantizedTile>
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

    private data class QuantizedTile(
        val zoom: Int, val tileX: Int, val tileY: Int,
        val width: Int, val height: Int, val pixels: ByteArray
    )
}
