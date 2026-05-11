package com.mapscreator.tiles

data class TileSource(
    val id: String,
    val name: String,
    val urlTemplate: String,
    val tileFormat: TileFormat,
    val avgTileSizeKb: Int
) {
    enum class TileFormat { XYZ, ARCGIS_YX_SWAP, BING_QUADKEY }

    fun tileUrl(z: Int, x: Int, y: Int): String = when (tileFormat) {
        TileFormat.XYZ, TileFormat.ARCGIS_YX_SWAP -> urlTemplate
            .replace("{z}", z.toString())
            .replace("{x}", x.toString())
            .replace("{y}", y.toString())
        TileFormat.BING_QUADKEY -> urlTemplate.replace("{q}", toQuadKey(z, x, y))
    }

    companion object {
        val OSM = TileSource(
            "osm", "OpenStreetMap",
            "https://tile.openstreetmap.org/{z}/{x}/{y}.png",
            TileFormat.XYZ, avgTileSizeKb = 10
        )
        val ARCGIS_SATELLITE = TileSource(
            "arcgis", "ArcGIS Satellite",
            "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}",
            TileFormat.ARCGIS_YX_SWAP, avgTileSizeKb = 50
        )
        val GOOGLE_SATELLITE = TileSource(
            "google", "Google Satellite",
            "https://mt1.google.com/vt/lyrs=s&x={x}&y={y}&z={z}",
            TileFormat.XYZ, avgTileSizeKb = 40
        )
        val BING_SATELLITE = TileSource(
            "bing", "Bing Satellite",
            "https://ecn.t3.tiles.virtualearth.net/tiles/a{q}.jpeg?g=1",
            TileFormat.BING_QUADKEY, avgTileSizeKb = 45
        )

        val PRESETS: List<TileSource> = listOf(OSM, ARCGIS_SATELLITE, GOOGLE_SATELLITE, BING_SATELLITE)

        fun custom(id: String, name: String, url: String) =
            TileSource(id, name, url, TileFormat.XYZ, avgTileSizeKb = 30)
    }
}

private fun toQuadKey(z: Int, x: Int, y: Int): String {
    val sb = StringBuilder()
    for (i in z downTo 1) {
        var digit = 0
        val mask = 1 shl (i - 1)
        if (x and mask != 0) digit++
        if (y and mask != 0) digit += 2
        sb.append(digit)
    }
    return sb.toString()
}
