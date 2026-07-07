package com.mapscreator.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.mapscreator.databinding.ActivityRouteExportBinding
import com.mapscreator.export.CorridorPlanner
import com.mapscreator.export.GarminSender
import com.mapscreator.export.GmndExporter
import com.mapscreator.export.OsmandSqliteExporter
import com.mapscreator.gpx.GpxParser
import com.mapscreator.gpx.GpxRoute
import com.mapscreator.service.DownloadService
import com.mapscreator.tiles.MBTilesStore
import com.mapscreator.tiles.TileCoord
import com.mapscreator.tiles.TileSizeEstimator
import com.mapscreator.tiles.TileSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline
import java.io.File

/**
 * Экран экспорта маршрута: маршрут на карте, ширина коридора, зум-уровни,
 * источник тайлов, предпросмотр выбранных тайлов (закрашенные уедут на часы,
 * контурные — остальной коридор), действия: офлайн / OsmAnd / часы.
 */
class RouteExportActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRouteExportBinding
    private lateinit var store: MBTilesStore

    private var route: GpxRoute? = null
    private var routePolyline: Polyline? = null
    private val tileOverlays = mutableListOf<Polygon>()
    private var plans: List<CorridorPlanner.ZoomPlan> = emptyList()
    private var planSeq = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().userAgentValue = "MapsCreator/1.0"
        binding = ActivityRouteExportBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        store = MBTilesStore(this, "mapscreator")

        binding.map.setTileSource(TileSourceFactory.MAPNIK)
        binding.map.setMultiTouchControls(true)
        binding.map.controller.setZoom(12.0)
        binding.map.controller.setCenter(GeoPoint(50.45, 30.52))

        binding.spinnerSource.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            TileSource.PRESETS.map { it.name }
        )
        binding.spinnerSource.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) = replan()
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }

        binding.btnLoadGpx.setOnClickListener {
            startActivityForResult(
                Intent(Intent.ACTION_GET_CONTENT).apply { type = "*/*" }, REQ_GPX
            )
        }

        binding.sliderWidth.addOnChangeListener { _, value, _ ->
            binding.tvWidthLabel.text = "Коридор: ${value.toInt()} м"
            replan()
        }

        for (cb in zoomCheckboxes()) {
            cb.second.setOnCheckedChangeListener { _, _ -> replan() }
        }

        binding.btnSaveOffline.setOnClickListener { saveOffline() }
        binding.btnOsmand.setOnClickListener { exportOsmand() }
        binding.btnWatch.setOnClickListener { exportWatch() }

        // Маршрут может прийти из OsmAnd-плагина / MainActivity.
        intent.getStringExtra(EXTRA_ROUTE_JSON)?.let { showRouteFromJson(it, intent.getStringExtra(EXTRA_ROUTE_NAME)) }
    }

    private fun zoomCheckboxes() = listOf(
        11 to binding.cbZ11, 12 to binding.cbZ12, 13 to binding.cbZ13,
        14 to binding.cbZ14, 15 to binding.cbZ15, 16 to binding.cbZ16,
    )

    private fun selectedZooms(): List<Int> = zoomCheckboxes().filter { it.second.isChecked }.map { it.first }

    private fun selectedSource(): TileSource = TileSource.PRESETS[binding.spinnerSource.selectedItemPosition]

    private fun showRouteFromJson(json: String, name: String?) {
        val coords = Regex("""\[(-?\d+\.\d+),\s*(-?\d+\.\d+)\]""").findAll(json)
            .map { GpxRoute.LatLon(it.groupValues[2].toDouble(), it.groupValues[1].toDouble()) }
            .toList()
        if (coords.isEmpty()) return
        applyRoute(
            GpxRoute(
                name = name ?: "Маршрут",
                points = coords,
                minLat = coords.minOf { it.lat }, maxLat = coords.maxOf { it.lat },
                minLon = coords.minOf { it.lon }, maxLon = coords.maxOf { it.lon },
            )
        )
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_GPX && resultCode == Activity.RESULT_OK) {
            data?.data?.let { uri ->
                try {
                    contentResolver.openInputStream(uri)?.use { applyRoute(GpxParser.parse(it)) }
                } catch (e: Exception) {
                    Toast.makeText(this, "Ошибка чтения GPX: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun applyRoute(r: GpxRoute) {
        route = r
        binding.tvRouteInfo.text = "${r.name} (${r.points.size} точек)"
        routePolyline?.let { binding.map.overlays.remove(it) }
        routePolyline = Polyline().apply {
            setPoints(r.points.map { GeoPoint(it.lat, it.lon) })
            outlinePaint.color = Color.rgb(220, 50, 50)
            outlinePaint.strokeWidth = 5f
        }
        binding.map.overlays.add(routePolyline)
        val buffered = r.withCorridorBuffer(binding.sliderWidth.value.toDouble())
        binding.map.post {
            binding.map.zoomToBoundingBox(
                BoundingBox(buffered.maxLat, buffered.maxLon, buffered.minLat, buffered.minLon), true, 48
            )
        }
        replan()
    }

    /**
     * Пересчёт плана коридора + обновление оверлеев и сводки. Планирование —
     * в фоне; устаревшие результаты отбрасываются по planSeq.
     */
    private fun replan() {
        val r = route ?: return
        val zooms = selectedZooms()
        val width = binding.sliderWidth.value.toDouble()
        val source = selectedSource()
        val seq = ++planSeq
        if (zooms.isEmpty()) {
            binding.tvPreview.text = "Выбери хотя бы один зум-уровень"
            setActionsEnabled(false)
            clearTileOverlays()
            return
        }
        binding.tvPreview.text = "Считаю..."
        lifecycleScope.launch {
            val result = withContext(Dispatchers.Default) {
                // Длинные треки прореживаем — коридору хватает каждой N-й точки.
                val pts = r.points.let { p ->
                    if (p.size <= 1000) p else p.filterIndexed { i, _ -> i % (p.size / 1000 + 1) == 0 }
                }.map { CorridorPlanner.LatLon(it.lat, it.lon) }
                val plansNow = CorridorPlanner.plan(pts, width, zooms)
                val cachedPerZoom = plansNow.associate { plan ->
                    plan.zoom to store.countCached(plan.tiles, source.id)
                }
                plansNow to cachedPerZoom
            }
            if (seq != planSeq) return@launch
            plans = result.first
            renderPlanOverlays()
            renderPlanSummary(result.second, source)
            setActionsEnabled(true)
        }
    }

    private fun setActionsEnabled(enabled: Boolean) {
        binding.btnSaveOffline.isEnabled = enabled
        binding.btnOsmand.isEnabled = enabled
        binding.btnWatch.isEnabled = enabled
    }

    private fun clearTileOverlays() {
        tileOverlays.forEach { binding.map.overlays.remove(it) }
        tileOverlays.clear()
        binding.map.invalidate()
    }

    // Закрашенные тайлы = уедут на часы; контурные = остальной коридор
    // (офлайн/OsmAnd качают весь коридор). Цвет — по зуму.
    private fun renderPlanOverlays() {
        clearTileOverlays()
        for (plan in plans.sortedBy { it.zoom }) {
            val color = ZOOM_COLORS[plan.zoom % ZOOM_COLORS.size]
            val watchSet = plan.watchTiles.toHashSet()
            var outlineBudget = MAX_OUTLINE_TILES
            for (tile in plan.tiles) {
                val toWatch = tile in watchSet
                if (!toWatch && outlineBudget-- <= 0) continue
                val b = CorridorPlanner.tileBounds(tile.z, tile.x, tile.y)
                val poly = Polygon().apply {
                    points = listOf(
                        GeoPoint(b[0], b[2]), GeoPoint(b[0], b[3]),
                        GeoPoint(b[1], b[3]), GeoPoint(b[1], b[2]), GeoPoint(b[0], b[2]),
                    )
                    outlinePaint.color = color
                    outlinePaint.strokeWidth = if (toWatch) 2.5f else 1f
                    fillPaint.color = if (toWatch) (color and 0x00FFFFFF) or (55 shl 24) else Color.TRANSPARENT
                }
                binding.map.overlays.add(poly)
                tileOverlays.add(poly)
            }
        }
        routePolyline?.let {
            // Маршрут поверх тайловой сетки
            binding.map.overlays.remove(it)
            binding.map.overlays.add(it)
        }
        binding.map.invalidate()
    }

    private fun renderPlanSummary(cachedPerZoom: Map<Int, Int>, source: TileSource) {
        val sb = StringBuilder()
        var watchTotalBytes = 0
        var corridorTotal = 0
        for (plan in plans) {
            watchTotalBytes += plan.watchBytes
            corridorTotal += plan.tiles.size
            val cached = cachedPerZoom[plan.zoom] ?: 0
            val trunc = if (plan.truncated) "▲" else ""
            sb.append("z${plan.zoom}: коридор ${plan.tiles.size} т. (в сторе $cached), на часы ${plan.watchTiles.size}$trunc\n")
        }
        sb.append("Часы: ~${TileSizeEstimator.formatBytes(watchTotalBytes.toLong())}")
        sb.append("  |  Офлайн/OsmAnd: $corridorTotal т. (${source.name})")
        if (plans.any { it.truncated }) sb.append("\n▲ кап часов — сузь коридор или убери зум")
        binding.tvPreview.text = sb.toString()
    }

    private fun allCorridorTiles(): List<TileCoord> = plans.flatMap { it.tiles }

    // ── Действия ──────────────────────────────────────────────────────────────

    private fun saveOffline() {
        val tiles = allCorridorTiles()
        if (tiles.isEmpty()) return
        val listFile = File(cacheDir, "corridor_${System.currentTimeMillis()}.tiles")
        listFile.writeText(tiles.joinToString("\n") { "${it.z},${it.x},${it.y}" })
        DownloadService.startTiles(this, listFile.absolutePath, listOf(selectedSource().id))
        Toast.makeText(this, "Скачиваю коридор: ${tiles.size} тайлов (${selectedSource().name})", Toast.LENGTH_LONG).show()
    }

    private fun exportOsmand() {
        val r = route ?: return
        val source = selectedSource()
        lifecycleScope.launch {
            val result = try {
                withContext(Dispatchers.IO) {
                    val exportsDir = File(getExternalFilesDir(null), "exports").also { it.mkdirs() }
                    OsmandSqliteExporter(store).export(
                        source.id, allCorridorTiles(), File(exportsDir, "${r.name}.sqlitedb")
                    )
                }
            } catch (e: Exception) {
                Toast.makeText(this@RouteExportActivity, "Экспорт в OsmAnd не удался: ${e.message}", Toast.LENGTH_LONG).show()
                return@launch
            }
            if (result.tilesWritten == 0) {
                Toast.makeText(this@RouteExportActivity, "В сторе нет тайлов коридора — сначала «Офлайн»", Toast.LENGTH_LONG).show()
                return@launch
            }
            if (result.tilesMissing > 0) {
                Toast.makeText(this@RouteExportActivity, "Не хватает ${result.tilesMissing} тайлов — доскачай «Офлайн»", Toast.LENGTH_LONG).show()
            }
            val uri: Uri = FileProvider.getUriForFile(
                this@RouteExportActivity, "$packageName.fileprovider", result.file
            )
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "application/octet-stream"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "Отправить .sqlitedb (выбери OsmAnd)"))
        }
    }

    private fun exportWatch() {
        val r = route ?: return
        val source = selectedSource()
        val zooms = selectedZooms()
        val width = binding.sliderWidth.value.toDouble()
        lifecycleScope.launch {
            val result = try {
                withContext(Dispatchers.IO) {
                    val exportsDir = File(getExternalFilesDir(null), "exports").also { it.mkdirs() }
                    val pts = r.points.map { CorridorPlanner.LatLon(it.lat, it.lon) }
                    GmndExporter(store).exportCorridor(
                        source.id, pts, width, zooms, File(exportsDir, "${r.name}.gmnd")
                    )
                }
            } catch (e: Exception) {
                Toast.makeText(this@RouteExportActivity, "Экспорт не удался: ${e.message}", Toast.LENGTH_LONG).show()
                return@launch
            }
            if (result.tileCount == 0) {
                Toast.makeText(this@RouteExportActivity, "Нет тайлов в сторе — сначала «Офлайн»", Toast.LENGTH_LONG).show()
                return@launch
            }
            val breakdown = result.perZoom.entries.filter { it.value > 0 }
                .joinToString(", ") { "z${it.key}:${it.value}" }
            Toast.makeText(
                this@RouteExportActivity,
                "GMND: ${result.tileCount} тайлов ($breakdown), ${TileSizeEstimator.formatBytes(result.bytes.toLong())}",
                Toast.LENGTH_LONG
            ).show()
            if (!GarminSender.sendToGarmiand(this@RouteExportActivity, result.file)) {
                Toast.makeText(this@RouteExportActivity, "garmiand не найден — share sheet", Toast.LENGTH_SHORT).show()
                GarminSender.shareGmndFile(this@RouteExportActivity, result.file)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        binding.map.onResume()
    }

    override fun onPause() {
        super.onPause()
        binding.map.onPause()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    companion object {
        const val EXTRA_ROUTE_JSON = "route_json"
        const val EXTRA_ROUTE_NAME = "route_name"
        private const val REQ_GPX = 3001
        private const val MAX_OUTLINE_TILES = 300
        private val ZOOM_COLORS = intArrayOf(
            Color.rgb(96, 125, 139),   // 0 — резерв
            Color.rgb(121, 85, 72),    // 1
            Color.rgb(63, 81, 181),    // z12 % 6 = 0... индексируется по модулю
            Color.rgb(0, 150, 136),
            Color.rgb(255, 152, 0),
            Color.rgb(233, 30, 99),
        )
    }
}
