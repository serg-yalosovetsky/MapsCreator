package com.mapscreator

import android.app.Application
import org.osmdroid.config.Configuration

class MapsCreatorApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Configuration.getInstance().load(this, getSharedPreferences("osmdroid", 0))
        Configuration.getInstance().userAgentValue = "MapsCreator/1.0"
    }
}
