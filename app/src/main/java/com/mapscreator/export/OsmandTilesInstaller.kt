package com.mapscreator.export

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.IOException

/**
 * Доставка готового .sqlitedb в OsmAnd.
 *
 * Через share sheet это не работает: у OsmAnd единственный ACTION_SEND-фильтр
 * объявлен на mimeType text/plain (OsmAnd/AndroidManifest.xml), так что при
 * ACTION_SEND с application/octet-stream OsmAnd просто не появится в списке.
 * Поэтому файл кладём прямо в его каталог tiles/, а если тот недоступен —
 * в Downloads, откуда OsmAnd берёт файл своим импортом.
 *
 * Android/media/<pkg>/ выбран намеренно: в отличие от Android/data/, он остался
 * доступен обычному File API после scoped storage. Гарантий на всех прошивках
 * это не даёт, поэтому результат попытки возвращается наружу — вызывающий код
 * обязан сказать пользователю, каким путём файл реально ушёл.
 */
object OsmandTilesInstaller {

    private const val TAG = "OsmandTilesInstaller"

    /** Пакеты OsmAnd в порядке приоритета: Plus, бесплатный, ночная сборка. */
    private val PACKAGES = listOf("net.osmand.plus", "net.osmand", "net.osmand.dev")

    sealed interface Outcome {
        /** Файл лёг в tiles/ OsmAnd. Список источников карт OsmAnd перечитывает при старте. */
        data class Installed(val pkg: String, val file: File) : Outcome

        /** Файл сохранён, но подхватить его OsmAnd должен сам — через свой импорт файлов. */
        data class NeedsManualImport(val location: String) : Outcome

        data class Failed(val reason: String) : Outcome
    }

    fun install(context: Context, source: File): Outcome =
        if (!source.isFile) {
            Outcome.Failed("файл ${source.name} не найден")
        } else {
            installIntoTilesDir(source) ?: saveToDownloads(context, source)
        }

    /** Первый каталог OsmAnd, в который удалось записать. null = ни один не доступен. */
    private fun installIntoTilesDir(source: File): Outcome.Installed? {
        val root = Environment.getExternalStorageDirectory()
        val candidates = PACKAGES.map { pkg ->
            pkg to File(root, "Android/media/$pkg/files/tiles")
        } + listOf(
            // Путь OsmAnd до scoped storage — на старых прошивках он ещё живой.
            "osmand (legacy)" to File(root, "osmand/tiles")
        )
        return candidates.firstNotNullOfOrNull { (pkg, dir) ->
            tryCopy(source, dir)?.let { Outcome.Installed(pkg, it) }
        }
    }

    /** Копия в dir, если тот существует или создаётся. null = каталог недоступен. */
    private fun tryCopy(source: File, dir: File): File? = try {
        if (dir.isDirectory || dir.mkdirs()) {
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
        val target = tryCopy(source, File(Environment.getExternalStorageDirectory(), subDir))
        return if (target == null) {
            Outcome.Failed("не удалось записать в $subDir")
        } else {
            Outcome.NeedsManualImport(target.absolutePath)
        }
    }
}
