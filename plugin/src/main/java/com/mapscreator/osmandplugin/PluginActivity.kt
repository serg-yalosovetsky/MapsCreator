package com.mapscreator.osmandplugin

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast

/**
 * Activity-заглушка для OsmAnd.
 *
 * Два сценария запуска:
 *  1. OsmAnd → Plugins → Settings (action=MAIN, data=null) — активирует сервис
 *  2. OsmAnd nav drawer item (action=VIEW, data=mapscreator://export) — показывает
 *     системный picker для выбора GPX-файла, затем передаёт URI в сервис
 *
 * Тема Theme.Translucent.NoTitleBar обязательна для сценария 2: Theme.NoDisplay
 * крашится с BadTokenException если Activity остаётся живой до onActivityResult.
 */
class PluginActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent?.data?.scheme == "mapscreator") {
            val picker = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(
                    Intent.EXTRA_MIME_TYPES,
                    arrayOf("application/gpx+xml", "application/xml", "text/xml", "application/octet-stream")
                )
            }
            startActivityForResult(picker, REQUEST_GPX)
        } else {
            OsmAndButtonService.start(this)
            Toast.makeText(this, "MapsCreator plugin активирован", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQUEST_GPX && resultCode == RESULT_OK) {
            val uri = data?.data
            if (uri != null) {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                OsmAndButtonService.triggerExportFromUri(this, uri)
            } else {
                Toast.makeText(this, "GPX-файл не выбран", Toast.LENGTH_SHORT).show()
            }
        }
        finish()
    }

    companion object {
        private const val REQUEST_GPX = 1
    }
}
