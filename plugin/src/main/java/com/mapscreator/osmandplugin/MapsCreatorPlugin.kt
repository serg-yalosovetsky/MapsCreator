package com.mapscreator.osmandplugin

import android.content.Context
import android.content.Intent
import android.widget.Toast

/**
 * OsmAnd plugin entry point.
 *
 * OsmAnd загружает плагины через механизм APK-расширений. Плагин должен объявить
 * себя через <meta-data android:name="osmand.plugin" ...> в AndroidManifest.xml.
 *
 * Если OsmAnd plugin API недоступен через Maven, подключить osmand-api.jar вручную:
 *   plugin/libs/osmand-api.jar
 * и раскомментировать соответствующие импорты.
 *
 * API reference: https://github.com/osmandapp/osmand-api-demo
 */
class MapsCreatorPlugin {

    companion object {
        private const val MAPSCREATOR_PKG = "com.mapscreator"
        private const val ACTION_IMPORT_ROUTE = "com.mapscreator.ACTION_IMPORT_ROUTE"

        /**
         * Экспортирует текущий маршрут из OsmAnd в MapsCreator.
         *
         * Вызывается из OsmAnd plugin callback когда пользователь нажимает
         * "Скачать карту в MapsCreator" в меню.
         *
         * @param context Android context
         * @param routeJson GeoJSON LineString с точками маршрута
         * @param routeName название маршрута
         */
        fun exportCurrentRoute(context: Context, routeJson: String, routeName: String) {
            val pm = context.packageManager
            val isInstalled = try {
                pm.getPackageInfo(MAPSCREATOR_PKG, 0)
                true
            } catch (_: Exception) { false }

            if (!isInstalled) {
                Toast.makeText(context, "MapsCreator не установлен", Toast.LENGTH_LONG).show()
                return
            }

            val intent = Intent(ACTION_IMPORT_ROUTE).apply {
                setPackage(MAPSCREATOR_PKG)
                putExtra("route_json", routeJson)
                putExtra("route_name", routeName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }

        /**
         * Строит GeoJSON LineString из массива точек (lat/lon пары).
         */
        fun buildRouteJson(points: List<Pair<Double, Double>>): String {
            val coords = points.joinToString(",") { (lat, lon) -> "[$lon,$lat]" }
            return """{"type":"LineString","coordinates":[$coords]}"""
        }
    }
}
