package com.mapscreator.osmandplugin

import android.content.Context
import android.content.Intent
import android.widget.Toast

object MapsCreatorPlugin {

    private const val MAPSCREATOR_PKG = "com.mapscreator"
    private const val ACTION_IMPORT_ROUTE = "com.mapscreator.ACTION_IMPORT_ROUTE"

    /**
     * Открывает MapsCreator с переданным маршрутом.
     *
     * @param routeJson GeoJSON LineString с координатами маршрута
     * @param routeName отображаемое имя (используется как название области)
     */
    fun exportCurrentRoute(context: Context, routeJson: String, routeName: String) {
        val installed = runCatching {
            context.packageManager.getPackageInfo(MAPSCREATOR_PKG, 0)
            true
        }.getOrDefault(false)

        if (!installed) {
            Toast.makeText(context, "MapsCreator не установлен", Toast.LENGTH_LONG).show()
            return
        }

        context.startActivity(
            Intent(ACTION_IMPORT_ROUTE).apply {
                setPackage(MAPSCREATOR_PKG)
                putExtra("route_json", routeJson)
                putExtra("route_name", routeName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }

    /**
     * Строит GeoJSON LineString из списка пар (lat, lon).
     * Вызывается из OsmAndButtonService после извлечения точек из AGpxFile.
     */
    fun buildRouteJson(points: List<Pair<Double, Double>>): String {
        val coords = points.joinToString(",") { (lat, lon) -> "[$lon,$lat]" }
        return """{"type":"LineString","coordinates":[$coords]}"""
    }
}
