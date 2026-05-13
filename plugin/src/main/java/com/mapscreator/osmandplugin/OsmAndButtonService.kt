package com.mapscreator.osmandplugin

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import android.util.Xml
import android.widget.Toast
import net.osmand.aidlapi.IOsmAndAidlInterface
import net.osmand.aidlapi.navdrawer.NavDrawerItem
import net.osmand.aidlapi.navdrawer.SetNavDrawerItemsParams
import org.xmlpull.v1.XmlPullParser

class OsmAndButtonService : Service() {

    private var osmApi: IOsmAndAidlInterface? = null
    private var pendingExport = false
    private val retryHandler = android.os.Handler(android.os.Looper.getMainLooper())

    // ─── AIDL ServiceConnection ───────────────────────────────────────────────

    private val osmConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            Log.i(TAG, "onServiceConnected: $name")
            osmApi = IOsmAndAidlInterface.Stub.asInterface(binder)
            registerNavDrawerItem()
            if (pendingExport) {
                pendingExport = false
                handleExport()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.w(TAG, "onServiceDisconnected — OsmAnd killed, stopping service")
            osmApi = null
            stopSelf()
        }
    }

    // ─── Service lifecycle ────────────────────────────────────────────────────

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand action=${intent?.action} osmApi=${if (osmApi != null) "connected" else "null"}")
        startForegroundCompat()

        when (intent?.action) {
            ACTION_EXPORT_URI -> {
                @Suppress("DEPRECATION")
                val uri: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                    intent.getParcelableExtra(EXTRA_URI, Uri::class.java)
                else
                    intent.getParcelableExtra(EXTRA_URI)
                if (uri != null) handleExportFromUri(uri)
                else Log.w(TAG, "ACTION_EXPORT_URI: missing URI extra")
            }
            ACTION_EXPORT -> {
                if (osmApi != null) handleExport()
                else pendingExport = true
            }
        }

        if (osmApi == null) bindToOsmAnd()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.i(TAG, "onDestroy")
        super.onDestroy()
        retryHandler.removeCallbacksAndMessages(null)
        try {
            osmApi?.setNavDrawerItems(SetNavDrawerItemsParams(packageName, arrayListOf()))
        } catch (_: RemoteException) {}
        runCatching { unbindService(osmConnection) }
    }

    // ─── Foreground notification (required for Android 8+ background start) ───

    private fun startForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "MapsCreator Plugin", NotificationManager.IMPORTANCE_MIN)
                        .apply { setShowBadge(false) }
                )
            }
            val notification = Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("MapsCreator")
                .setContentText("Ожидание маршрута из OsmAnd")
                .setSmallIcon(android.R.drawable.ic_menu_mapmode)
                .build()
            startForeground(NOTIF_ID, notification)
        }
    }

    // ─── OsmAnd binding ──────────────────────────────────────────────────────

    private fun bindToOsmAnd() {
        Log.i(TAG, "bindToOsmAnd trying packages: $OSMAND_PACKAGES")
        val bound = OSMAND_PACKAGES.any { pkg ->
            val intent = Intent(OSMAND_AIDL_SERVICE).apply { setPackage(pkg) }
            val ok = runCatching { bindService(intent, osmConnection, Context.BIND_AUTO_CREATE) }
                .getOrDefault(false)
            Log.i(TAG, "  bind $pkg → $ok")
            ok
        }
        if (!bound) {
            Log.w(TAG, "bindToOsmAnd: no OsmAnd found, stopping")
            stopSelf()
        }
    }

    // ─── Nav drawer registration ──────────────────────────────────────────────

    private fun registerNavDrawerItem() {
        val api = osmApi ?: return
        try {
            // OsmAnd fires ACTION_VIEW mapscreator://export when user taps this item.
            // Returns false until user enables our plugin in OsmAnd → Plugins screen.
            val item = NavDrawerItem("MapsCreator", "mapscreator://export", "")
            val ok = api.setNavDrawerItems(SetNavDrawerItemsParams(packageName, arrayListOf(item)))
            Log.i(TAG, "registerNavDrawerItem: ok=$ok")
            if (!ok) {
                // Retry until user enables us in OsmAnd Plugins
                retryHandler.postDelayed({ registerNavDrawerItem() }, 10_000)
            }
        } catch (e: RemoteException) {
            Log.e(TAG, "registerNavDrawerItem failed: $e")
        }
    }

    // ─── Export via URI (Android 11+ picker path) ─────────────────────────────

    private fun handleExportFromUri(uri: Uri) {
        Log.i(TAG, "handleExportFromUri: $uri")
        val points = tryParseGpxFromUri(uri)
        if (points == null || points.isEmpty()) {
            Log.w(TAG, "handleExportFromUri: no track points in $uri")
            Toast.makeText(this, "Маршрут не найден в выбранном файле", Toast.LENGTH_LONG).show()
            return
        }
        Log.i(TAG, "handleExportFromUri: ${points.size} pts")
        val routeJson = MapsCreatorPlugin.buildRouteJson(points)
        MapsCreatorPlugin.exportCurrentRoute(this, routeJson, "OsmAnd Route")
    }

    private fun tryParseGpxFromUri(uri: Uri): List<Pair<Double, Double>>? {
        return try {
            contentResolver.openInputStream(uri)?.use { stream ->
                val points = mutableListOf<Pair<Double, Double>>()
                val parser = Xml.newPullParser()
                parser.setInput(stream, "UTF-8")
                var event = parser.eventType
                while (event != XmlPullParser.END_DOCUMENT) {
                    if (event == XmlPullParser.START_TAG && parser.name == "trkpt") {
                        val lat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull()
                        val lon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull()
                        if (lat != null && lon != null) points.add(lat to lon)
                    }
                    event = parser.next()
                }
                points.takeIf { it.isNotEmpty() }
            }
        } catch (e: Exception) {
            Log.e(TAG, "tryParseGpxFromUri failed: $e")
            null
        }
    }

    // ─── Export via AIDL (fallback / pre-Android 11) ─────────────────────────

    private fun handleExport() {
        Log.i(TAG, "handleExport")
        val routeJson = extractRouteJson()
        if (routeJson == null) {
            Log.w(TAG, "handleExport: no active route")
            Toast.makeText(this, "Нет активного маршрута в OsmAnd", Toast.LENGTH_LONG).show()
        }
        MapsCreatorPlugin.exportCurrentRoute(this, routeJson ?: "", "OsmAnd Route")
    }

    private fun extractRouteJson(): String? {
        val api = osmApi ?: return null
        return try {
            val files = mutableListOf<net.osmand.aidlapi.gpx.ASelectedGpxFile>()
            api.getActiveGpx(files)
            Log.i(TAG, "getActiveGpx: ${files.size} files — ${files.map { it.fileName }}")
            val file = files.firstOrNull() ?: return null
            readRouteFromFile(file.fileName)
        } catch (e: RemoteException) {
            Log.e(TAG, "getActiveGpx failed: $e")
            null
        }
    }

    private fun readRouteFromFile(fileName: String): String? {
        for (pkg in OSMAND_PACKAGES) {
            val base = "/sdcard/Android/data/$pkg/files/"
            val candidates = listOf(
                "$base$fileName",
                "${base}tracks/$fileName",
                "${base}tracks/rec/$fileName"
            )
            for (path in candidates) {
                val points = tryParseGpxTrkPts(path)
                if (points != null) {
                    Log.i(TAG, "readRouteFromFile: found ${points.size} pts in $path")
                    return MapsCreatorPlugin.buildRouteJson(points)
                }
            }
        }
        Log.w(TAG, "readRouteFromFile: $fileName not found in any candidate path")
        return null
    }

    private fun tryParseGpxTrkPts(path: String): List<Pair<Double, Double>>? {
        return try {
            val f = java.io.File(path)
            if (!f.exists()) return null
            val points = mutableListOf<Pair<Double, Double>>()
            val parser = Xml.newPullParser()
            parser.setInput(f.inputStream(), "UTF-8")
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && parser.name == "trkpt") {
                    val lat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull()
                    val lon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull()
                    if (lat != null && lon != null) points.add(lat to lon)
                }
                event = parser.next()
            }
            points.takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }

    // ─── Companion ────────────────────────────────────────────────────────────

    companion object {
        private const val TAG = "MapsCreator"
        private const val CHANNEL_ID = "mapscreator_plugin"
        private const val NOTIF_ID = 1
        private const val OSMAND_AIDL_SERVICE = "net.osmand.aidl.OsmandAidlServiceV2"
        private val OSMAND_PACKAGES = listOf("net.osmand.plus", "net.osmand")

        const val ACTION_EXPORT = "com.mapscreator.ACTION_EXPORT"
        const val ACTION_EXPORT_URI = "com.mapscreator.ACTION_EXPORT_URI"
        const val EXTRA_URI = "uri"

        fun start(context: Context) {
            context.startServiceCompat(Intent(context, OsmAndButtonService::class.java))
        }

        fun triggerExport(context: Context) {
            context.startServiceCompat(
                Intent(context, OsmAndButtonService::class.java).apply { action = ACTION_EXPORT }
            )
        }

        fun triggerExportFromUri(context: Context, uri: Uri) {
            context.startServiceCompat(
                Intent(context, OsmAndButtonService::class.java).apply {
                    action = ACTION_EXPORT_URI
                    putExtra(EXTRA_URI, uri)
                }
            )
        }

        private fun Context.startServiceCompat(intent: Intent) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
            else startService(intent)
        }
    }
}
