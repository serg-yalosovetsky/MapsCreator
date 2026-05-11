package com.mapscreator.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.mapscreator.tiles.*
import kotlinx.coroutines.*
import okhttp3.OkHttpClient

class DownloadService : Service() {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var activeJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> cancelDownload()
            ACTION_START -> {
                val areaId = intent.getStringExtra(EXTRA_AREA_ID) ?: return START_NOT_STICKY
                val sourceIds = intent.getStringArrayExtra(EXTRA_SOURCE_IDS) ?: return START_NOT_STICKY
                val zoomMin = intent.getIntExtra(EXTRA_ZOOM_MIN, 14)
                val zoomMax = intent.getIntExtra(EXTRA_ZOOM_MAX, 16)
                val forceRefresh = intent.getBooleanExtra(EXTRA_FORCE_REFRESH, false)
                startDownload(areaId, sourceIds.toList(), zoomMin, zoomMax, forceRefresh)
            }
        }
        return START_NOT_STICKY
    }

    private fun startDownload(
        areaId: String,
        sourceIds: List<String>,
        zoomMin: Int,
        zoomMax: Int,
        forceRefresh: Boolean
    ) {
        startForeground(NOTIF_ID, buildNotification("Подготовка...", 0, 0))
        activeJob = scope.launch {
            try {
                runDownload(areaId, sourceIds, zoomMin, zoomMax, forceRefresh)
                updateNotification("Загрузка завершена", 1, 1)
            } catch (e: CancellationException) {
                // нормальная отмена
            } catch (e: Exception) {
                updateNotification("Ошибка: ${e.message}", 0, 0)
            } finally {
                delay(2000)
                stopSelf()
            }
        }
    }

    private suspend fun runDownload(
        areaId: String,
        sourceIds: List<String>,
        zoomMin: Int,
        zoomMax: Int,
        forceRefresh: Boolean
    ) {
        val store = MBTilesStore(this@DownloadService, "mapscreator")
        val area = store.areaById(areaId) ?: return

        val bbox = area.bbox() ?: return
        val tiles = (zoomMin..zoomMax).flatMap { z ->
            TileSizeEstimator.tilesInBbox(bbox.minLat, bbox.maxLat, bbox.minLon, bbox.maxLon, z)
        }
        val sources = sourceIds.mapNotNull { id -> TileSource.PRESETS.find { it.id == id } }
        val maxAgeMs = if (forceRefresh) 0L else Long.MAX_VALUE
        val totalSources = sources.size
        val downloader = TileDownloader(store, OkHttpClient())

        sources.forEachIndexed { si, source ->
            val prefix = if (totalSources > 1) "${source.name}: " else ""
            downloader.download(tiles, source, maxAgeMs) { p ->
                val text = "$prefix${p.downloaded + p.skipped}/${p.total} тайлов"
                updateNotification(text, p.done, p.total * totalSources + si * p.total)
            }
        }

        store.upsertArea(area.copy(tileCount = tiles.size * totalSources))
    }

    private fun cancelDownload() {
        activeJob?.cancel()
        stopSelf()
    }

    private fun updateNotification(text: String, progress: Int, max: Int) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(text, progress, max))
    }

    private fun buildNotification(text: String, progress: Int, max: Int): Notification {
        val cancelIntent = PendingIntent.getService(
            this, 0,
            Intent(this, DownloadService::class.java).apply { action = ACTION_CANCEL },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MapsCreator — загрузка карты")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setProgress(max, progress, max == 0)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Отмена", cancelIntent)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Загрузка карт", NotificationManager.IMPORTANCE_LOW)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.mapscreator.START_DOWNLOAD"
        const val ACTION_CANCEL = "com.mapscreator.CANCEL_DOWNLOAD"
        const val EXTRA_AREA_ID = "area_id"
        const val EXTRA_SOURCE_IDS = "source_ids"
        const val EXTRA_ZOOM_MIN = "zoom_min"
        const val EXTRA_ZOOM_MAX = "zoom_max"
        const val EXTRA_FORCE_REFRESH = "force_refresh"
        const val CHANNEL_ID = "mapscreator_download"
        private const val NOTIF_ID = 1001

        fun start(
            context: Context,
            areaId: String,
            sourceIds: List<String>,
            zoomMin: Int = 14,
            zoomMax: Int = 16,
            forceRefresh: Boolean = false
        ) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_AREA_ID, areaId)
                putExtra(EXTRA_SOURCE_IDS, sourceIds.toTypedArray())
                putExtra(EXTRA_ZOOM_MIN, zoomMin)
                putExtra(EXTRA_ZOOM_MAX, zoomMax)
                putExtra(EXTRA_FORCE_REFRESH, forceRefresh)
            }
            context.startForegroundService(intent)
        }
    }
}

