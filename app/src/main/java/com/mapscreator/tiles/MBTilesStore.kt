package com.mapscreator.tiles

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File

class MBTilesStore(context: Context, name: String) :
    SQLiteOpenHelper(
        context,
        File(context.getExternalFilesDir(null), "$name.mbtiles").absolutePath,
        null, DB_VERSION
    ) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS tiles (
                zoom_level    INTEGER NOT NULL,
                tile_column   INTEGER NOT NULL,
                tile_row      INTEGER NOT NULL,
                tile_data     BLOB    NOT NULL,
                downloaded_at INTEGER NOT NULL,
                source_id     TEXT    NOT NULL,
                PRIMARY KEY (zoom_level, tile_column, tile_row, source_id)
            )"""
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS map_areas (
                id           TEXT    PRIMARY KEY,
                name         TEXT    NOT NULL,
                geojson      TEXT    NOT NULL,
                sources      TEXT    NOT NULL,
                zoom_min     INTEGER NOT NULL,
                zoom_max     INTEGER NOT NULL,
                tile_count   INTEGER NOT NULL DEFAULT 0,
                created_at   INTEGER NOT NULL,
                last_updated INTEGER NOT NULL
            )"""
        )
        db.execSQL("CREATE TABLE IF NOT EXISTS metadata (name TEXT PRIMARY KEY, value TEXT)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    fun hasTile(z: Int, x: Int, y: Int, sourceId: String, maxAgeMs: Long = Long.MAX_VALUE): Boolean {
        val cutoff = System.currentTimeMillis() - maxAgeMs
        readableDatabase.rawQuery(
            """SELECT 1 FROM tiles
               WHERE zoom_level=? AND tile_column=? AND tile_row=? AND source_id=? AND downloaded_at>=?""",
            arrayOf(z.toString(), x.toString(), y.toString(), sourceId, cutoff.toString())
        ).use { return it.moveToFirst() }
    }

    fun putTile(z: Int, x: Int, y: Int, sourceId: String, data: ByteArray) {
        val cv = ContentValues().apply {
            put("zoom_level", z)
            put("tile_column", x)
            put("tile_row", y)
            put("source_id", sourceId)
            put("tile_data", data)
            put("downloaded_at", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict("tiles", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun getTile(z: Int, x: Int, y: Int, sourceId: String): ByteArray? {
        readableDatabase.rawQuery(
            "SELECT tile_data FROM tiles WHERE zoom_level=? AND tile_column=? AND tile_row=? AND source_id=?",
            arrayOf(z.toString(), x.toString(), y.toString(), sourceId)
        ).use { c -> return if (c.moveToFirst()) c.getBlob(0) else null }
    }

    fun countCached(tiles: List<TileCoord>, sourceId: String, maxAgeMs: Long = Long.MAX_VALUE): Int =
        tiles.count { (z, x, y) -> hasTile(z, x, y, sourceId, maxAgeMs) }

    fun upsertArea(area: MapArea) {
        val cv = ContentValues().apply {
            put("id", area.id)
            put("name", area.name)
            put("geojson", area.geojson)
            put("sources", area.sources)
            put("zoom_min", area.zoomMin)
            put("zoom_max", area.zoomMax)
            put("tile_count", area.tileCount)
            put("created_at", area.createdAt)
            put("last_updated", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict("map_areas", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun deleteArea(id: String) {
        writableDatabase.delete("map_areas", "id=?", arrayOf(id))
    }

    fun areaById(id: String): MapArea? {
        readableDatabase.rawQuery(
            "SELECT * FROM map_areas WHERE id=?", arrayOf(id)
        ).use { c ->
            if (!c.moveToFirst()) return null
            return MapArea(
                id = c.getString(c.getColumnIndexOrThrow("id")),
                name = c.getString(c.getColumnIndexOrThrow("name")),
                geojson = c.getString(c.getColumnIndexOrThrow("geojson")),
                sources = c.getString(c.getColumnIndexOrThrow("sources")),
                zoomMin = c.getInt(c.getColumnIndexOrThrow("zoom_min")),
                zoomMax = c.getInt(c.getColumnIndexOrThrow("zoom_max")),
                tileCount = c.getInt(c.getColumnIndexOrThrow("tile_count")),
                createdAt = c.getLong(c.getColumnIndexOrThrow("created_at")),
                lastUpdated = c.getLong(c.getColumnIndexOrThrow("last_updated"))
            )
        }
    }

    fun allAreas(): List<MapArea> {
        val result = mutableListOf<MapArea>()
        readableDatabase.rawQuery("SELECT * FROM map_areas ORDER BY last_updated DESC", null).use { c ->
            while (c.moveToNext()) {
                result += MapArea(
                    id = c.getString(c.getColumnIndexOrThrow("id")),
                    name = c.getString(c.getColumnIndexOrThrow("name")),
                    geojson = c.getString(c.getColumnIndexOrThrow("geojson")),
                    sources = c.getString(c.getColumnIndexOrThrow("sources")),
                    zoomMin = c.getInt(c.getColumnIndexOrThrow("zoom_min")),
                    zoomMax = c.getInt(c.getColumnIndexOrThrow("zoom_max")),
                    tileCount = c.getInt(c.getColumnIndexOrThrow("tile_count")),
                    createdAt = c.getLong(c.getColumnIndexOrThrow("created_at")),
                    lastUpdated = c.getLong(c.getColumnIndexOrThrow("last_updated"))
                )
            }
        }
        return result
    }

    companion object {
        private const val DB_VERSION = 1
    }
}

data class TileCoord(val z: Int, val x: Int, val y: Int)

data class MapArea(
    val id: String,
    val name: String,
    val geojson: String,
    val sources: String,
    val zoomMin: Int,
    val zoomMax: Int,
    val tileCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val lastUpdated: Long = System.currentTimeMillis()
)

data class Bbox(val minLat: Double, val maxLat: Double, val minLon: Double, val maxLon: Double)

// Parses bbox from MapsCreator-generated GeoJSON (coordinate pairs [lon, lat]).
// Regex instead of a JSON library — MapsCreator only reads its own GeoJSON (ADR-005).
fun MapArea.bbox(): Bbox? {
    val nums = Regex("""-?\d+\.\d+""").findAll(geojson)
        .map { it.value.toDouble() }.toList()
    if (nums.isEmpty()) return null
    val lons = nums.filterIndexed { i, _ -> i % 2 == 0 }
    val lats = nums.filterIndexed { i, _ -> i % 2 == 1 }
    return Bbox(
        minLat = lats.minOrNull() ?: return null,
        maxLat = lats.maxOrNull() ?: return null,
        minLon = lons.minOrNull() ?: return null,
        maxLon = lons.maxOrNull() ?: return null
    )
}
