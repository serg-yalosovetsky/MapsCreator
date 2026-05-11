package com.mapscreator.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.mapscreator.databinding.ActivityMapSelectorBinding
import com.mapscreator.gpx.GpxParser
import com.mapscreator.tiles.TileSource
import com.mapscreator.tiles.TileSizeEstimator
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.gestures.RotationGestureOverlay

class MapSelectorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMapSelectorBinding
    private val polygonPoints = mutableListOf<GeoPoint>()
    private var drawingOverlay: Polygon? = null
    private var routePolyline: Polyline? = null
    private var presetName: String = "Область"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().userAgentValue = "MapsCreator/1.0"
        binding = ActivityMapSelectorBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        setupMap()
        setupControls()

        // Если пришёл маршрут от OsmAnd plugin
        intent.getStringExtra(EXTRA_ROUTE_JSON)?.let { showRouteFromJson(it) }
        presetName = intent.getStringExtra(EXTRA_PRESET_NAME) ?: "Область"
    }

    private fun setupMap() {
        binding.map.setTileSource(TileSourceFactory.MAPNIK)
        binding.map.setMultiTouchControls(true)
        binding.map.controller.setZoom(12.0)
        binding.map.controller.setCenter(GeoPoint(50.45, 30.52)) // Kyiv default

        val rotation = RotationGestureOverlay(binding.map)
        binding.map.overlays.add(rotation)

        // Рисование полигона стилусом/пальцем
        binding.map.setOnTouchListener { _, event ->
            if (binding.toggleDraw.isChecked) {
                handleDrawTouch(event)
                true
            } else {
                false
            }
        }
    }

    private fun handleDrawTouch(event: MotionEvent) {
        val projection = binding.map.projection
        when (event.action) {
            MotionEvent.ACTION_DOWN -> polygonPoints.clear()
            MotionEvent.ACTION_MOVE -> {
                val gp = projection.fromPixels(event.x.toInt(), event.y.toInt()) as GeoPoint
                polygonPoints.add(gp)
                refreshPolygonOverlay()
            }
            MotionEvent.ACTION_UP -> {
                if (polygonPoints.size > 2) {
                    refreshPolygonOverlay()
                    updateEstimate()
                } else {
                    Toast.makeText(this, "Нарисуйте область побольше", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun refreshPolygonOverlay() {
        drawingOverlay?.let { binding.map.overlays.remove(it) }
        if (polygonPoints.size < 2) return
        drawingOverlay = Polygon().apply {
            points = polygonPoints + polygonPoints.first()
            fillPaint.color = Color.argb(50, 33, 150, 243)
            outlinePaint.color = Color.rgb(33, 150, 243)
            outlinePaint.strokeWidth = 3f
        }
        binding.map.overlays.add(drawingOverlay)
        binding.map.invalidate()
    }

    private fun showRouteFromJson(json: String) {
        // Минимальный парсер: координаты из JSON array [[lon,lat],...]
        try {
            val coords = Regex("""\[(-?\d+\.\d+),\s*(-?\d+\.\d+)\]""").findAll(json)
                .map { GeoPoint(it.groupValues[2].toDouble(), it.groupValues[1].toDouble()) }
                .toList()
            if (coords.isEmpty()) return
            routePolyline?.let { binding.map.overlays.remove(it) }
            routePolyline = Polyline().apply {
                setPoints(coords)
                outlinePaint.color = Color.rgb(220, 50, 50)
                outlinePaint.strokeWidth = 5f
            }
            binding.map.overlays.add(routePolyline)
            val bbox = org.osmdroid.util.BoundingBox.fromGeoPoints(coords)
            binding.map.post { binding.map.zoomToBoundingBox(bbox, true, 64) }
            binding.map.invalidate()
        } catch (_: Exception) {}
    }

    private fun setupControls() {
        binding.btnClear.setOnClickListener {
            polygonPoints.clear()
            drawingOverlay?.let { binding.map.overlays.remove(it) }
            drawingOverlay = null
            binding.tvEstimate.text = ""
            binding.map.invalidate()
        }

        binding.btnConfirm.setOnClickListener {
            if (polygonPoints.size < 3) {
                Toast.makeText(this, "Нарисуйте область или загрузите GPX", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            returnResult()
        }

        binding.btnLoadGpx.setOnClickListener {
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply { type = "*/*" }
            startActivityForResult(intent, REQ_GPX)
        }

        val sourceListener = { _: android.widget.CompoundButton, _: Boolean -> updateEstimate() }
        binding.cbOsm.setOnCheckedChangeListener(sourceListener)
        binding.cbArcgis.setOnCheckedChangeListener(sourceListener)
        binding.cbGoogle.setOnCheckedChangeListener(sourceListener)
        binding.cbBing.setOnCheckedChangeListener(sourceListener)

        binding.sliderZoomMin.addOnChangeListener { _, value, _ ->
            binding.tvZoomMinLabel.text = "Зум мин: ${value.toInt()}"
            updateEstimate()
        }
        binding.sliderZoomMax.addOnChangeListener { _, value, _ ->
            binding.tvZoomMaxLabel.text = "Зум макс: ${value.toInt()}"
            updateEstimate()
        }
    }

    private fun updateEstimate() {
        val bbox = polygonBbox() ?: return
        val selectedSources = getSelectedSources()
        val zoomMin = binding.sliderZoomMin.value.toInt()
        val zoomMax = binding.sliderZoomMax.value.toInt()

        val estimates = TileSizeEstimator.estimate(
            bbox.minLat, bbox.maxLat, bbox.minLon, bbox.maxLon,
            zoomMin..zoomMax, selectedSources
        )
        val totalTiles = estimates.values.sumOf { it.tileCount }
        val totalBytes = estimates.values.sumOf { it.estimatedBytes }
        binding.tvEstimate.text = "~$totalTiles тайлов, ${TileSizeEstimator.formatBytes(totalBytes)}"
    }

    private fun returnResult() {
        val bbox = polygonBbox() ?: return
        val geojson = buildBboxGeoJson(bbox)
        val sourceIds = getSelectedSources().map { it.id }.toTypedArray()
        val zoomMin = binding.sliderZoomMin.value.toInt()
        val zoomMax = binding.sliderZoomMax.value.toInt()

        setResult(Activity.RESULT_OK, Intent().apply {
            putExtra(RESULT_AREA_JSON, geojson)
            putExtra(RESULT_AREA_NAME, presetName)
            putExtra(RESULT_ZOOM_MIN, zoomMin)
            putExtra(RESULT_ZOOM_MAX, zoomMax)
            putExtra(RESULT_SOURCE_IDS, sourceIds)
        })
        finish()
    }

    private fun polygonBbox(): Bbox? {
        if (polygonPoints.isEmpty()) return null
        return Bbox(
            minLat = polygonPoints.minOf { it.latitude },
            maxLat = polygonPoints.maxOf { it.latitude },
            minLon = polygonPoints.minOf { it.longitude },
            maxLon = polygonPoints.maxOf { it.longitude }
        )
    }

    private fun getSelectedSources(): List<TileSource> = buildList {
        if (binding.cbOsm.isChecked) add(TileSource.OSM)
        if (binding.cbArcgis.isChecked) add(TileSource.ARCGIS_SATELLITE)
        if (binding.cbGoogle.isChecked) add(TileSource.GOOGLE_SATELLITE)
        if (binding.cbBing.isChecked) add(TileSource.BING_SATELLITE)
    }

    private fun buildBboxGeoJson(bbox: Bbox): String =
        """{"type":"Polygon","coordinates":[[
            [${bbox.minLon},${bbox.minLat}],
            [${bbox.maxLon},${bbox.minLat}],
            [${bbox.maxLon},${bbox.maxLat}],
            [${bbox.minLon},${bbox.maxLat}],
            [${bbox.minLon},${bbox.minLat}]
        ]]}"""

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_GPX && resultCode == Activity.RESULT_OK) {
            data?.data?.let { uri ->
                try {
                    contentResolver.openInputStream(uri)?.use { stream ->
                        val route = GpxParser.parse(stream)
                        presetName = route.name
                        val buffered = route.withCorridorBuffer(500.0)
                        polygonPoints.clear()
                        polygonPoints += listOf(
                            GeoPoint(buffered.minLat, buffered.minLon),
                            GeoPoint(buffered.minLat, buffered.maxLon),
                            GeoPoint(buffered.maxLat, buffered.maxLon),
                            GeoPoint(buffered.maxLat, buffered.minLon)
                        )
                        refreshPolygonOverlay()

                        routePolyline?.let { binding.map.overlays.remove(it) }
                        routePolyline = Polyline().apply {
                            setPoints(route.points.map { GeoPoint(it.lat, it.lon) })
                            outlinePaint.color = Color.rgb(220, 50, 50)
                            outlinePaint.strokeWidth = 5f
                        }
                        binding.map.overlays.add(routePolyline)

                        val bbox = org.osmdroid.util.BoundingBox(
                            buffered.maxLat, buffered.maxLon, buffered.minLat, buffered.minLon
                        )
                        binding.map.post { binding.map.zoomToBoundingBox(bbox, true, 64) }
                        updateEstimate()
                    }
                } catch (e: Exception) {
                    Toast.makeText(this, "Ошибка чтения GPX: ${e.message}", Toast.LENGTH_LONG).show()
                }
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

    private data class Bbox(val minLat: Double, val maxLat: Double, val minLon: Double, val maxLon: Double)

    companion object {
        const val EXTRA_ROUTE_JSON = "route_json"
        const val EXTRA_PRESET_NAME = "preset_name"
        const val RESULT_AREA_JSON = "area_json"
        const val RESULT_AREA_NAME = "area_name"
        const val RESULT_ZOOM_MIN = "zoom_min"
        const val RESULT_ZOOM_MAX = "zoom_max"
        const val RESULT_SOURCE_IDS = "source_ids"
        private const val REQ_GPX = 2001
    }
}
