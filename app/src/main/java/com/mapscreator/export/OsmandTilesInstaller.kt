package com.mapscreator.export

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException

/**
 * Доставка готового .sqlitedb в OsmAnd.
 *
 * Через share sheet это не работает: у OsmAnd единственный ACTION_SEND-фильтр
 * объявлен на mimeType text/plain (OsmAnd/AndroidManifest.xml), так что при
 * ACTION_SEND с application/octet-stream OsmAnd в списке не появится.
 *
 * Рабочий путь — ACTION_VIEW: у MapActivity есть фильтр на pathPattern *.sqlitedb,
 * и OsmAnd сам импортирует файл в свой каталог (ImportHelper.handleSqliteTileImport →
 * SqliteTileImportTask). Проверено на Fold 4 / Android 16: OsmAnd+ и OsmAnd
 * появляются в «Відкрити за допомогою» для .sqlitedb.
 *
 * Копирование в каталог OsmAnd оставлено первой попыткой, но ТОЛЬКО в уже
 * существующий каталог: на Android 11+ OsmAnd держит карты в
 * Android/data/<pkg>/files/tiles, куда сторонним приложениям доступа нет, а
 * Android/media/<pkg>/ у него попросту не существует. Создавать его через mkdirs()
 * нельзя — файл лёг бы в пустышку, которую OsmAnd не читает, и доставка отчиталась
 * бы успехом впустую.
 */
object OsmandTilesInstaller {

    private const val TAG = "OsmandTilesInstaller"

    /** Пакеты OsmAnd в порядке приоритета: Plus, бесплатный, ночная сборка. */
    private val PACKAGES = listOf("net.osmand.plus", "net.osmand", "net.osmand.dev")

    sealed interface Outcome {
        /** Файл лёг прямо в каталог OsmAnd. Источники карт OsmAnd перечитывает при старте. */
        data class Copied(val pkg: String, val file: File) : Outcome

        /** Intent надо запустить: OsmAnd импортирует файл сам. */
        data class HandOff(val intent: Intent, val pkg: String) : Outcome

        /** Файл сохранён, но подхватить его OsmAnd должен через свой импорт файлов. */
        data class NeedsManualImport(val location: String) : Outcome

        data class Failed(val reason: String) : Outcome
    }

    fun install(context: Context, source: File): Outcome =
        if (!source.isFile) {
            Outcome.Failed("файл ${source.name} не найден")
        } else {
            copyIntoExistingTilesDir(source)
                ?: handOffToOsmand(context, source)
                ?: saveToDownloads(context, source)
        }

    /** Копия в каталог OsmAnd, если он существует. null = такого каталога нет. */
    private fun copyIntoExistingTilesDir(source: File): Outcome.Copied? {
        val root = Environment.getExternalStorageDirectory()
        val candidates = PACKAGES.map { pkg ->
            pkg to File(root, "Android/media/$pkg/files/tiles")
        } + listOf(
            // Путь OsmAnd до scoped storage — на старых прошивках он ещё живой.
            "osmand (legacy)" to File(root, "osmand/tiles")
        )
        return candidates.firstNotNullOfOrNull { (pkg, dir) ->
            tryCopy(source, dir)?.let { Outcome.Copied(pkg, it) }
        }
    }

    /** Копия в dir БЕЗ его создания. null = каталога нет или запись не удалась. */
    private fun tryCopy(source: File, dir: File): File? = try {
        if (dir.isDirectory) {
            val target = source.copyTo(File(dir, source.name), overwrite = true)
            if (target.length() == source.length()) target else null
        } else {
            null
        }
    } catch (e: IOException) {
        Log.d(TAG, "каталог ${dir.path} недоступен для записи", e)
        null
    } catch (e: SecurityException) {
        Log.d(TAG, "нет прав на запись в ${dir.path}", e)
        null
    }

    /**
     * Intent на установленный OsmAnd. Пакет задаётся явно, чтобы файл не ушёл
     * в случайную читалку из общего списка. null = ни один OsmAnd не установлен
     * либо не отвечает на ACTION_VIEW (нужен <queries> в манифесте, иначе
     * resolveActivity вернёт null при живом приложении).
     */
    private fun handOffToOsmand(context: Context, source: File): Outcome.HandOff? {
        val uri = try {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", source)
        } catch (e: IllegalArgumentException) {
            Log.d(TAG, "файл ${source.path} вне file_provider_paths", e)
            return null
        }
        return PACKAGES.firstNotNullOfOrNull { pkg ->
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/octet-stream")
                setPackage(pkg)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (intent.resolveActivity(context.packageManager) == null) {
                null
            } else {
                Outcome.HandOff(intent, pkg)
            }
        }
    }

    private fun saveToDownloads(context: Context, source: File): Outcome {
        val subDir = "${Environment.DIRECTORY_DOWNLOADS}/MapsCreator"
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveViaMediaStore(context, source, subDir)
            } else {
                saveViaFile(source, subDir)
            }
        } catch (e: IOException) {
            Outcome.Failed(e.message ?: "ошибка записи в $subDir")
        } catch (e: SecurityException) {
            Outcome.Failed(e.message ?: "нет прав на запись в $subDir")
        }
    }

    private fun saveViaMediaStore(context: Context, source: File, subDir: String): Outcome {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, source.name)
            put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
            put(MediaStore.Downloads.RELATIVE_PATH, subDir)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        val stream = uri?.let { resolver.openOutputStream(it) }
        return if (stream == null) {
            Outcome.Failed("Downloads недоступен для записи")
        } else {
            stream.use { out -> source.inputStream().use { it.copyTo(out) } }
            Outcome.NeedsManualImport("$subDir/${source.name}")
        }
    }

    private fun saveViaFile(source: File, subDir: String): Outcome {
        val dir = File(Environment.getExternalStorageDirectory(), subDir)
        val target = if (dir.isDirectory || dir.mkdirs()) tryCopy(source, dir) else null
        return if (target == null) {
            Outcome.Failed("не удалось записать в $subDir")
        } else {
            Outcome.NeedsManualImport(target.absolutePath)
        }
    }
}
