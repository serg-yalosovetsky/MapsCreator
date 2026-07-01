package com.mapscreator.export

import android.database.sqlite.SQLiteDatabase
import com.mapscreator.tiles.MBTilesStore
import com.mapscreator.tiles.TileCoord
import java.io.File

/**
 * Экспорт тайлов в формат RMaps/BigPlanet .sqlitedb, который OsmAnd подхватывает
 * как офлайн-растровую карту (Настройки → Карты → Источник карты, файл кладётся
 * в osmand/tiles/ или импортируется открытием файла).
 *
 * Схема: tiles(x, y, z, s, image), info(minzoom, maxzoom). Нумерация зума —
 * прямая OSM (minzoom < maxzoom); инвертированную BigPlanet-нумерацию (17-z)
 * OsmAnd отличает по minzoom > maxzoom, так что двусмысленности нет.
 */
class OsmandSqliteExporter(private val store: MBTilesStore) {

    data class Result(val file: File, val tilesWritten: Int, val tilesMissing: Int)

    fun export(sourceId: String, tiles: List<TileCoord>, outputFile: File): Result {
        if (outputFile.exists()) outputFile.delete()
        val db = SQLiteDatabase.openOrCreateDatabase(outputFile, null)
        try {
            db.execSQL("CREATE TABLE IF NOT EXISTS tiles (x int, y int, z int, s int, image blob, PRIMARY KEY (x,y,z,s))")
            db.execSQL("CREATE TABLE IF NOT EXISTS info (minzoom int, maxzoom int)")
            var written = 0
            var missing = 0
            db.beginTransaction()
            try {
                val insert = db.compileStatement("INSERT OR REPLACE INTO tiles (x, y, z, s, image) VALUES (?, ?, ?, 0, ?)")
                for ((z, x, y) in tiles) {
                    val data = store.getTile(z, x, y, sourceId)
                    if (data == null) {
                        missing++
                        continue
                    }
                    insert.bindLong(1, x.toLong())
                    insert.bindLong(2, y.toLong())
                    insert.bindLong(3, z.toLong())
                    insert.bindBlob(4, data)
                    insert.executeInsert()
                    written++
                }
                if (written > 0) {
                    val minZ = tiles.minOf { it.z }
                    val maxZ = tiles.maxOf { it.z }
                    db.execSQL("DELETE FROM info")
                    db.execSQL("INSERT INTO info (minzoom, maxzoom) VALUES ($minZ, $maxZ)")
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            return Result(outputFile, written, missing)
        } finally {
            db.close()
        }
    }
}
