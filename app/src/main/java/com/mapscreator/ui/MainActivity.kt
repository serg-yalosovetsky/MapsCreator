package com.mapscreator.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.mapscreator.R
import com.mapscreator.databinding.ActivityMainBinding
import com.mapscreator.export.GarminSender
import com.mapscreator.export.GmndExporter
import com.mapscreator.tiles.MapArea
import com.mapscreator.tiles.MBTilesStore
import com.mapscreator.tiles.TileSizeEstimator
import com.mapscreator.tiles.bbox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var store: MBTilesStore
    private val areas = mutableListOf<MapArea>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        store = MBTilesStore(this, "mapscreator")

        binding.recyclerAreas.layoutManager = LinearLayoutManager(this)
        binding.recyclerAreas.adapter = AreaAdapter(areas,
            onDownload = { area -> showDownloadDialog(area) },
            onExportGarmin = { area -> exportToGarmin(area) },
            onDelete = { area -> confirmDelete(area) }
        )

        binding.fabAdd.setOnClickListener {
            startActivityForResult(Intent(this, MapSelectorActivity::class.java), REQ_SELECT_AREA)
        }

        handleIncomingIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        reloadAreas()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent) {
        val routeJson = intent.getStringExtra(EXTRA_ROUTE_JSON) ?: return
        val areaName = intent.getStringExtra(EXTRA_ROUTE_NAME) ?: "Маршрут"
        startActivityForResult(
            Intent(this, MapSelectorActivity::class.java).apply {
                putExtra(MapSelectorActivity.EXTRA_ROUTE_JSON, routeJson)
                putExtra(MapSelectorActivity.EXTRA_PRESET_NAME, areaName)
            },
            REQ_SELECT_AREA
        )
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_SELECT_AREA && resultCode == Activity.RESULT_OK && data != null) {
            val areaJson = data.getStringExtra(MapSelectorActivity.RESULT_AREA_JSON) ?: return
            val areaName = data.getStringExtra(MapSelectorActivity.RESULT_AREA_NAME) ?: "Область"
            val zoomMin = data.getIntExtra(MapSelectorActivity.RESULT_ZOOM_MIN, 14)
            val zoomMax = data.getIntExtra(MapSelectorActivity.RESULT_ZOOM_MAX, 16)
            val sourceIds = data.getStringArrayExtra(MapSelectorActivity.RESULT_SOURCE_IDS)
                ?.toList() ?: listOf("osm")

            val area = MapArea(
                id = UUID.randomUUID().toString(),
                name = areaName,
                geojson = areaJson,
                sources = sourceIds.joinToString(","),
                zoomMin = zoomMin,
                zoomMax = zoomMax
            )
            store.upsertArea(area)
            showDownloadDialog(area)
            reloadAreas()
        }
    }

    private fun showDownloadDialog(area: MapArea) {
        DownloadConfirmDialog(this, store, area) { sourceIds, zoomMin, zoomMax, forceRefresh ->
            com.mapscreator.service.DownloadService.start(this, area.id, sourceIds, zoomMin, zoomMax, forceRefresh)
        }.show()
    }

    private fun exportToGarmin(area: MapArea) {
        val sourceId = area.sources.split(",").firstOrNull() ?: return
        val bbox = area.bbox() ?: run {
            Toast.makeText(this, "Не удалось определить координаты области", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            val tiles = withContext(Dispatchers.Default) {
                (area.zoomMin..area.zoomMax).flatMap { z ->
                    TileSizeEstimator.tilesInBbox(bbox.minLat, bbox.maxLat, bbox.minLon, bbox.maxLon, z)
                }
            }
            val result = withContext(Dispatchers.IO) {
                val exportsDir = File(getExternalFilesDir(null), "exports").also { it.mkdirs() }
                val outputFile = File(exportsDir, "${area.name}.gmnd")
                GmndExporter(store).export(tiles, sourceId, bbox.minLat, bbox.maxLat, bbox.minLon, bbox.maxLon, outputFile)
            }
            val sent = GarminSender.sendToGarmiand(this@MainActivity, result.file)
            if (!sent) GarminSender.shareGmndFile(this@MainActivity, result.file)
        }
    }

    private fun confirmDelete(area: MapArea) {
        AlertDialog.Builder(this)
            .setTitle("Удалить «${area.name}»?")
            .setMessage("Все скачанные тайлы будут удалены.")
            .setPositiveButton("Удалить") { _, _ ->
                store.deleteArea(area.id)
                reloadAreas()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun reloadAreas() {
        areas.clear()
        areas.addAll(store.allAreas())
        binding.recyclerAreas.adapter?.notifyDataSetChanged()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.menu_settings -> { startActivity(Intent(this, SettingsActivity::class.java)); true }
        else -> super.onOptionsItemSelected(item)
    }

    companion object {
        const val EXTRA_ROUTE_JSON = "route_json"
        const val EXTRA_ROUTE_NAME = "route_name"
        private const val REQ_SELECT_AREA = 1001
    }
}

fun formatAge(lastUpdated: Long): String {
    val ageMs = System.currentTimeMillis() - lastUpdated
    val days = ageMs / 86_400_000
    return when {
        days == 0L -> "сегодня"
        days == 1L -> "вчера"
        days < 7 -> "$days дн. назад"
        days < 30 -> "${days / 7} нед. назад"
        days < 365 -> "${days / 30} мес. назад"
        else -> "${days / 365} г. назад"
    }
}
