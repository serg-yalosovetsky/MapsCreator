package com.mapscreator.osmandplugin

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.RemoteException
import android.widget.Toast
import net.osmand.aidl.IOsmAndAidlCallback
import net.osmand.aidl.IOsmAndAidlInterface
import net.osmand.aidl.contextmenu.AContextMenuButton
import net.osmand.aidl.contextmenu.AContextMenuButtonsParams
import net.osmand.aidl.contextmenu.ARemoveContextMenuButtonsParams
import net.osmand.aidl.gpx.AGetActiveGpxParams
import net.osmand.aidl.gpx.AGetActiveGpxResult

/**
 * Поддерживает AIDL-соединение с OsmAnd на протяжении жизни плагина.
 *
 * Жизненный цикл:
 *   OsmAndAutoStartReceiver / PluginActivity → start() → bindToOsmAnd()
 *   → onServiceConnected → registerButton()
 *   → пользователь нажимает кнопку → onContextMenuButtonClicked → handleButtonClick()
 *   → MapsCreatorPlugin.exportCurrentRoute() → MapsCreator открывается с маршрутом
 *
 * Когда OsmAnd завершается → onServiceDisconnected → stopSelf().
 * OsmAndAutoStartReceiver перезапустит сервис при следующем старте OsmAnd.
 *
 * Импорты из osmand-api.aar (положить в plugin/libs/osmand-api.aar):
 *   https://github.com/osmandapp/osmand-api-demo/raw/master/OsmAnd-api/libs/osmand-api.aar
 *
 * Если конкретная версия AAR изменила имена пакетов или методов — скорректировать
 * import-строки и сигнатуры ниже. Общая архитектура остаётся неизменной.
 */
class OsmAndButtonService : Service() {

    private var osmApi: IOsmAndAidlInterface? = null
    private var callbackId: Long = -1

    // ─── AIDL ServiceConnection ───────────────────────────────────────────────

    private val osmConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            osmApi = IOsmAndAidlInterface.Stub.asInterface(binder)
            registerButton()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            osmApi = null
            callbackId = -1
            // OsmAnd упал — ждём перезапуска через OsmAndAutoStartReceiver
            stopSelf()
        }
    }

    // ─── AIDL Callback ────────────────────────────────────────────────────────

    // Реализовать ВСЕ abstract-методы IOsmAndAidlCallback.Stub().
    // Незадействованные оставлены пустыми; сигнатуры берутся из реального AAR.
    // Если компилятор сообщит о неизвестном методе — проверьте версию osmand-api.aar
    // и при необходимости добавьте или переименуйте override.
    private val osmCallback = object : IOsmAndAidlCallback.Stub() {

        @Throws(RemoteException::class)
        override fun onSearchComplete(result: net.osmand.aidl.search.ASearchResult?) {}

        @Throws(RemoteException::class)
        override fun onUpdate() {}

        @Throws(RemoteException::class)
        override fun onAppInitialized() {}

        @Throws(RemoteException::class)
        override fun onGpxBitmapCreated(gpxBitmap: net.osmand.aidl.gpx.AGpxBitmap?) {}

        @Throws(RemoteException::class)
        override fun onRouteDirectionsCalculated(
            directions: MutableList<net.osmand.aidl.navigation.ADirectionPoint>?
        ) {}

        /**
         * Вызывается OsmAnd при нажатии на зарегистрированную кнопку.
         * buttonId соответствует BUTTON_ID, переданному в AContextMenuButton.
         */
        @Throws(RemoteException::class)
        override fun onContextMenuButtonClicked(buttonId: Int, lat: Double, lon: Double) {
            if (buttonId == BUTTON_ID) handleButtonClick()
        }

        @Throws(RemoteException::class)
        override fun onMapClicked(lat: Double, lon: Double) {}

        @Throws(RemoteException::class)
        override fun onMapLongClicked(lat: Double, lon: Double) {}

        @Throws(RemoteException::class)
        override fun onNavigationStarted() {}

        @Throws(RemoteException::class)
        override fun onNavigationFinished() {}

        // Некоторые версии AAR добавляют onNavigationAttach — раскомментировать при необходимости:
        // @Throws(RemoteException::class)
        // override fun onNavigationAttach(location: net.osmand.aidl.map.ALocation?, routeJson: String?) {}
    }

    // ─── Service lifecycle ────────────────────────────────────────────────────

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (osmApi == null) bindToOsmAnd()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        try {
            if (callbackId >= 0) osmApi?.unregisterFromUpdates(callbackId)
            osmApi?.removeMapContextMenuButtons(ARemoveContextMenuButtonsParams(PLUGIN_ID))
        } catch (_: RemoteException) {}
        runCatching { unbindService(osmConnection) }
    }

    // ─── OsmAnd binding ──────────────────────────────────────────────────────

    private fun bindToOsmAnd() {
        // Пробуем OsmAnd+ затем OsmAnd Free; первый успешный bindService останавливает перебор.
        val bound = OSMAND_PACKAGES.any { pkg ->
            val intent = Intent(IOsmAndAidlInterface::class.java.name).apply { setPackage(pkg) }
            runCatching { bindService(intent, osmConnection, Context.BIND_AUTO_CREATE) }
                .getOrDefault(false)
        }
        if (!bound) stopSelf()
    }

    // ─── Button registration ──────────────────────────────────────────────────

    private fun registerButton() {
        val api = osmApi ?: return
        try {
            callbackId = api.registerForUpdates(CALLBACK_KEY, osmCallback)

            val button = AContextMenuButton(
                BUTTON_ID,
                "MapsCreator", // leftTextCaption — первая строка кнопки
                "",            // rightTextCaption
                0,             // leftIconId  (0 = без иконки)
                0,             // rightIconId
                false,         // needColorizeIcon
                true           // enabled
            )
            api.addMapContextMenuButtons(AContextMenuButtonsParams(PLUGIN_ID, listOf(button)))
        } catch (e: RemoteException) {
            stopSelf()
        }
    }

    // ─── Button click handling ────────────────────────────────────────────────

    private fun handleButtonClick() {
        val routeJson = extractRouteJson()
        if (routeJson == null) {
            Toast.makeText(this, "Нет активного маршрута в OsmAnd", Toast.LENGTH_LONG).show()
            return
        }
        MapsCreatorPlugin.exportCurrentRoute(this, routeJson, "OsmAnd Route")
    }

    /**
     * Получает активные GPX-файлы из OsmAnd и строит GeoJSON LineString
     * из точек первого трека первого файла (навигационный маршрут).
     *
     * OsmAnd хранит текущий навигационный маршрут как активный GPX-трек;
     * если маршрут не запущен — список будет пустым.
     */
    private fun extractRouteJson(): String? {
        val api = osmApi ?: return null
        return try {
            val result = AGetActiveGpxResult()
            api.getActiveGpx(AGetActiveGpxParams(), result)

            val points = result.gpxFiles
                ?.firstOrNull()
                ?.gpxFile
                ?.tracks
                ?.firstOrNull()
                ?.segments
                ?.firstOrNull()
                ?.points
                ?.map { pt -> Pair(pt.lat, pt.lon) }
                ?: return null

            if (points.isEmpty()) null else MapsCreatorPlugin.buildRouteJson(points)
        } catch (_: RemoteException) {
            null
        }
    }

    // ─── Companion ────────────────────────────────────────────────────────────

    companion object {
        private val OSMAND_PACKAGES = listOf("net.osmand.plus", "net.osmand")

        /** Уникальный ID группы кнопок для addMapContextMenuButtons / remove. */
        private const val PLUGIN_ID = "com.mapscreator"

        /** ID кнопки, передаётся в onContextMenuButtonClicked. */
        private const val BUTTON_ID = 1

        /** Ключ для регистрации callback; произвольное уникальное число. */
        private const val CALLBACK_KEY = 101L

        fun start(context: Context) {
            context.startService(Intent(context, OsmAndButtonService::class.java))
        }
    }
}
