package com.mapscreator.export

import android.content.Context
import com.mapscreator.tiles.MBTilesStore
import com.mapscreator.tiles.TileCoord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Один путь «набор тайлов → .sqlitedb → каталог OsmAnd» для всех экранов,
 * чтобы экспорт области и экспорт коридора не разъезжались в деталях формата.
 */
object OsmandExport {

    /** Символы, недопустимые в имени файла на FAT/exFAT; кириллица и пробелы остаются. */
    private val UNSAFE_NAME = Regex("""[\\/:*?"<>|]""")

    /**
     * Собирает .sqlitedb из того, что уже лежит в сторе, и доставляет в OsmAnd.
     * Возвращает готовую строку для показа пользователю — включая то, каким
     * путём файл ушёл, потому что от этого зависит, надо ли что-то делать руками.
     */
    suspend fun exportAndInstall(
        context: Context,
        store: MBTilesStore,
        sourceId: String,
        tiles: List<TileCoord>,
        name: String,
    ): String = withContext(Dispatchers.IO) {
        if (tiles.isEmpty()) return@withContext "Нет тайлов для экспорта — выбери зум-уровни"

        val safeName = UNSAFE_NAME.replace(name, "_").trim().ifEmpty { "map" }
        val exportsDir = File(context.getExternalFilesDir(null), "exports").also { it.mkdirs() }
        val result = OsmandSqliteExporter(store)
            .export(sourceId, tiles, File(exportsDir, "$safeName.sqlitedb"))

        if (result.tilesWritten == 0) {
            return@withContext "В сторе нет тайлов ($sourceId) — сначала скачай их кнопкой «Офлайн»"
        }

        val head = "OsmAnd: ${result.tilesWritten} тайлов" +
            if (result.tilesMissing > 0) " (не хватило ${result.tilesMissing} — доскачай)" else ""

        when (val outcome = OsmandTilesInstaller.install(context, result.file)) {
            is OsmandTilesInstaller.Outcome.Installed ->
                "$head → ${outcome.pkg}/tiles. Перезапусти OsmAnd: " +
                    "Настроить карту → Источник карты → $safeName"

            is OsmandTilesInstaller.Outcome.NeedsManualImport ->
                "$head → ${outcome.location}. Каталог OsmAnd недоступен — " +
                    "открой файл из OsmAnd (импорт) или перенеси в osmand/tiles вручную"

            is OsmandTilesInstaller.Outcome.Failed ->
                "$head, но доставка не удалась: ${outcome.reason}. " +
                    "Файл лежит в ${result.file.absolutePath}"
        }
    }
}
