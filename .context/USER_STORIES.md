# User Stories

End-to-end workflows MapsCreator supports.

## US-01 — Download a GPX route for offline use

**Actor**: hiker preparing a trip at home with Wi-Fi.

1. Open MapsCreator. Tap **+**.
2. Tap **GPX** → pick `.gpx` file → route bbox with 500 m corridor appears on map.
3. Check desired tile sources (OSM + ArcGIS Satellite). Set zoom 14–16.
4. Dialog shows: "2 847 тайлов (~58 MB), уже скачано: 0". Tap **Только новые**.
5. `DownloadService` starts. Notification: "MapsCreator — 145/2847 тайлов".
6. Download finishes (~10–20 min on Wi-Fi). Card appears in main list:
   "Маршрут Карпати · Обновлено: сегодня · 2847 тайлов · zoom 14–16".

## US-02 — Update stale tiles

**Actor**: same hiker, 14 months later.

1. Card shows: "Обновлено: 14 мес. назад".
2. Tap **Update** → dialog: "2847 тайлов, в кеше: 2847. Скачать новых: 0. Обновить всё?"
3. Tap **Обновить всё** → `DownloadService` re-downloads everything, ignoring `downloaded_at`.

Or: auto-update triggers (weekly WorkManager check sees `last_updated > 365 days`) →
notification: "MapsCreator: обновление карт в фоне".

## US-03 — Draw a custom area with stylus

**Actor**: climber who wants a square region around a peak, not tied to a route.

1. Open MapsCreator. Tap **+**.
2. In `MapSelectorActivity`: toggle **Рисовать** ON.
3. Drag stylus across map → blue polygon appears in real time.
4. Release → estimate updates: "847 тайлов (~42 MB)".
5. Enter a name → **Готово** → `DownloadConfirmDialog` → **Только новые**.

## US-04 — Export to Garmin Fenix

**Actor**: user who has both MapsCreator and garmiand installed.

1. Tap **Export to Garmin** on any downloaded area card.
2. `GmndExporter` reads up to 20 tiles from `MBTilesStore` (OSM or satellite — user picks source in a future dialog).
3. `.gmnd` file written to `<externalFilesDir>/exports/`.
4. garmiand opens (Intent), receives the file, and handles BLE/HTTPS transfer to the watch.

If garmiand is not installed → **Share** sheet opens instead (user can copy via file manager or send to another device).

## US-05 — OsmAnd plugin → quick download

**Actor**: user planning a route inside OsmAnd.

1. OsmAnd: route is calculated.
2. OsmAnd menu → plugin action "Скачать карту в MapsCreator".
3. MapsCreator opens with the route bbox pre-filled and route name pre-set.
4. User adjusts sources/zoom → downloads (continues as US-01 from step 4).

## US-06 — Add a custom tile server

**Actor**: user with access to a private tile server (WMS-to-tile proxy, custom aerial imagery).

1. `SettingsActivity` → Custom sources → **+**.
2. Enter name + XYZ URL: `https://tiles.example.com/{z}/{x}/{y}.png`.
3. New source appears in `MapSelectorActivity` checkboxes.
4. Downloaded tiles stored under `source_id = "custom_1"`.

*(Custom source UI not yet implemented — `TileSource.custom(...)` factory and storage schema are ready.)*
