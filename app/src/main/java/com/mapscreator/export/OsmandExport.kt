package com.mapscreator.export

import android.content.Context
import android.content.Intent
import com.mapscreator.tiles.MBTilesStore
import com.mapscreator.tiles.TileCoord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Один путь «набор тайлов → .sqlitedb → OsmAnd» для всех экранов,
 * чтобы экспорт области и экспорт коридора не разъезжались в деталях формата.
 */
object OsmandExport {

    /** Символы, недопустимые в имени файла на FAT/exFAT; кириллица и пробелы остаются. */
    private val UNSAFE_NAME = Regex("""[\\/:*?"<>|]""")

    /**
     * @param message готовая строка для пользователя: включает то, каким путём ушёл
     *   файл, потому что от этого зависит, надо ли что-то делать руками.
     * @param intent если не null — вызывающий экран обязан его запустить, OsmAnd
     *   импортирует файл сам (startActivity нельзя делать из объекта без Activity).
     */
    data class Result(val message: String, val intent: Intent?)

    /** Собирает .sqlitedb из того, что уже лежит в сторе, и доставляет в OsmAnd. */
    suspend fun exportAndInstall(
        context: Context,
        store: MBTilesStore,
        sourceId: String,
        tiles: List<TileCoord>,
        name: String,
    ): Result = withContext(Dispatchers.IO) {
        if (tiles.isEmpty()) {
            return@withContext Result("Нет тайлов для экспорта — выбери зум-уровни", null)
        }

        val safeName = UNSAFE_NAME.replace(name, "_").trim().ifEmpty { "map" }
        val exportsDir = File(context.getExternalFilesDir(null), "exports").also { it.mkdirs() }
        val result = OsmandSqliteExporter(store)
            .export(sourceId, tiles, File(exportsDir, "$safeName.sqlitedb"))

        if (result.tilesWritten == 0) {
            return@withContext Result(
                "В сторе нет тайлов ($sourceId) — сначала скачай их кнопкой «Офлайн»", null
            )
        }

        val head = "OsmAnd: ${result.tilesWritten} тайлов" +
            if (result.tilesMissing > 0) " (не хватило ${result.tilesMissing} — доскачай)" else ""

        when (val outcome = OsmandTilesInstaller.install(context, result.file)) {
            is OsmandTilesInstaller.Outcome.Copied -> Result(
                "$head → ${outcome.pkg}/tiles. Перезапусти OsmAnd: " +
                    "Налаштувати мапу → Джерело мапи → $safeName",
                null
            )

            is OsmandTilesInstaller.Outcome.HandOff -> Result(
                "$head → передаю в ${outcome.pkg}. После импорта: Налаштувати мапу → " +
                    "Джерело мапи (или Допоміжний шар) → $safeName. " +
                    "Нужен включённый плагин «Онлайн-мапи»",
                outcome.intent
            )

            is OsmandTilesInstaller.Outcome.NeedsManualImport -> Result(
                "$head → ${outcome.location}. OsmAnd не найден — " +
                    "открой файл из OsmAnd (импорт) вручную",
                null
            )

            is OsmandTilesInstaller.Outcome.Failed -> Result(
                "$head, но доставка не удалась: ${outcome.reason}. " +
                    "Файл лежит в ${result.file.absolutePath}",
                null
            )
        }
    }
}
