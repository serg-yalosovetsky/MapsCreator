package com.mapscreator.export

import android.database.sqlite.SQLiteDatabase
import com.mapscreator.tiles.MBTilesStore
import com.mapscreator.tiles.TileCoord
import java.io.File

/**
 * Экспорт тайлов в формат RMaps/BigPlanet .sqlitedb, который OsmAnd подхватывает
 * как офлайн-растровую карту (файл кладётся в <OsmAnd>/tiles/, см. OsmandTilesInstaller).
 *
 * Схема: tiles(x, y, z, s, image) + info(...). Зум пишется ПРЯМОЙ (OSM), поэтому
 * в info обязательна колонка `tilenumbering` = 'simple'. Без неё OsmAnd считает
 * файл BigPlanet-нумерованным и ищет тайл зума Z под `z = 17 - Z`, где ничего нет,
 * так что карта остаётся пустой. Из SQLiteTileSource.java (OsmAnd master):
 *
 *     // колонки tilenumbering нет в info:
 *     inversiveZoom = true;
 *     addInfoColumn(db, TILENUMBERING, BIG_PLANET_TILE_NUMBERING);
 *     ...
 *     private int getFileZoom(int zoom) { return inversiveZoom ? 17 - zoom : zoom; }
 *
 * `url` намеренно не пишется: без него OsmAnd не пытается доскачивать тайлы онлайн,
 * и карта остаётся честно офлайновой. Bing-quadkey-шаблон OsmAnd всё равно не понял бы.
 */
class OsmandSqliteExporter(private val store: MBTilesStore) {

    data class Result(val file: File, val tilesWritten: Int, val tilesMissing: Int)

    fun export(sourceId: String, tiles: List<TileCoord>, outputFile: File): Result {
        if (outputFile.exists()) outputFile.delete()
        outputFile.parentFile?.mkdirs()
        val db = SQLiteDatabase.openOrCreateDatabase(outputFile, null)
        try {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS tiles " +
                    "(x int, y int, z int, s int, image blob, PRIMARY KEY (x,y,z,s))"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS info " +
                    "(tilenumbering text, minzoom int, maxzoom int, tilesize int, " +
                    "ellipsoid int, inverted_y int, timecolumn text, title text)"
            )
            var written = 0
            var missing = 0
            db.beginTransaction()
            try {
                val insert = db.compileStatement(
                    "INSERT OR REPLACE INTO tiles (x, y, z, s, image) VALUES (?, ?, ?, 0, ?)"
                )
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
                writeInfo(db, tiles, outputFile.nameWithoutExtension)
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            return Result(outputFile, written, missing)
        } finally {
            db.close()
        }
    }

    /**
     * Одна строка метаданных. Пишется всегда, даже при нуле тайлов: файл без info
     * OsmAnd трактует как BigPlanet, и потом «пустая карта» выглядит как баг рендера,
     * а не как пустой экспорт.
     *
     * ellipsoid=0 / inverted_y=0 — сферический Меркатор с обычной OSM-осью Y; так
     * отдают тайлы все наши источники (OSM, Google, Bing, ArcGIS).
     */
    private fun writeInfo(db: SQLiteDatabase, tiles: List<TileCoord>, title: String) {
        val minZ = tiles.minOfOrNull { it.z } ?: 0
        val maxZ = tiles.maxOfOrNull { it.z } ?: 0
        db.execSQL("DELETE FROM info")
        db.execSQL(
            "INSERT INTO info " +
                "(tilenumbering, minzoom, maxzoom, tilesize, ellipsoid, inverted_y, timecolumn, title) " +
                "VALUES ('simple', ?, ?, 256, 0, 0, 'no', ?)",
            arrayOf<Any>(minZ, maxZ, title)
        )
    }
}
