package com.mapscreator.osmandplugin

import android.app.Activity
import android.os.Bundle
import android.widget.Toast

/**
 * Activity-заглушка для OsmAnd.
 *
 * OsmAnd запускает её при нажатии «Settings» в списке плагинов.
 * Никакого UI не показывает: запускает OsmAndButtonService и закрывается.
 */
class PluginActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        OsmAndButtonService.start(this)
        Toast.makeText(this, "MapsCreator plugin активирован", Toast.LENGTH_SHORT).show()
        finish()
    }
}
