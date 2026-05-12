package com.mapscreator.osmandplugin

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Перезапускает OsmAndButtonService при:
 *   - старте устройства (BOOT_COMPLETED)
 *   - запуске OsmAnd (net.osmand.api.OSMAND_STARTED)
 *
 * Без этого receiver'а кнопка «Скачать в MapsCreator» пропадает после
 * перезагрузки или переустановки OsmAnd.
 */
class OsmAndAutoStartReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        OsmAndButtonService.start(context)
    }
}
