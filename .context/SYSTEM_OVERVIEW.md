# System Overview

MapsCreator downloads offline map tiles (OSM + satellite) to an Android phone,
either for a route corridor or for a user-drawn area. Tiles are stored in MBTiles
(SQLite) format. Optionally, they can be exported as a GMND bundle and transferred
to a Garmin Fenix watch via the companion app [garmiand](../README.md — see g:\code\garmiand).

## Modules

### `:app` — Standalone Android App (`com.mapscreator`)

```
ui/
  MainActivity         — list of saved map areas, FAB to add
  MapSelectorActivity  — osmdroid map + stylus polygon drawing + GPX import
  DownloadConfirmDialog — tile count / size estimate / cache status before download
  AreaAdapter          — RecyclerView: area card with age, Update/Export/Delete actions
  SettingsActivity     — auto-update threshold (default: 365 days)
tiles/
  TileSource           — named presets + custom XYZ URL; Bing quadkey converter
  MBTilesStore         — SQLite: tiles table + map_areas table with downloaded_at
  TileDownloader       — parallel download (4 threads), skips cached tiles
  TileSizeEstimator    — counts tiles in bbox per zoom, estimates bytes before download
service/
  DownloadService      — Foreground Service with progress notification; Android won't kill it
gpx/
  GpxParser            — XmlPullParser → GpxRoute; corridor bbox with configurable buffer
export/
  Palette              — 64-color 4×4×4 RGB cube (MUST match garmiand's Palette.kt)
  GmndExporter         — reads tiles from MBTilesStore → quantize → GMND binary blob
  GarminSender         — Intent to garmiand app or generic share of .gmnd file
MapsCreatorApp         — Application subclass; initializes osmdroid
```

### `:plugin` — OsmAnd Plugin (`com.mapscreator.osmandplugin`)

Minimal APK. OsmAnd discovers it via `<meta-data android:name="osmand.plugin" ...>`.
Single responsibility: read the current route from OsmAnd and fire an Android Intent
to `:app`. No tile logic lives here.

## Runtime Boundaries

- **`:app`** runs standalone. It has no dependency on OsmAnd or garmiand being installed —
  both are optional integrations detected at runtime via `packageManager.getPackageInfo`.
- **`:plugin`** depends on OsmAnd being installed. Without OsmAnd, the plugin APK is inert.
- **garmiand** is a separate app at `g:\code\garmiand`. MapsCreator sends it a `.gmnd` file
  via FileProvider + Intent. garmiand handles the BLE/HTTPS transfer to the watch.
- **No backend** — MapsCreator talks directly to tile servers (OSM, ArcGIS, etc.).

## Main Flows

### Flow 1 — Area selection + download (standalone)

1. User taps FAB → `MapSelectorActivity` opens.
2. User draws polygon with stylus, or imports GPX → corridor bbox. On GPX import, the track
   is rendered as a red `Polyline` overlay and the map zooms to fit the full route bbox.
3. `TileSizeEstimator` counts tiles + estimates bytes → shown in `DownloadConfirmDialog`.
4. Dialog shows how many tiles are already cached (skip vs full download).
5. User confirms → `DownloadService.start(...)` called → Foreground Service starts.
6. `TileDownloader` fetches tiles in parallel (4 threads), skips cached, writes to `MBTilesStore`.
7. Persistent notification shows progress (N/total). User can cancel.
8. `MBTilesStore.upsertArea(...)` updates `last_updated` timestamp.

### Flow 2 — OsmAnd plugin → download

1. User has a route open in OsmAnd.
2. OsmAnd menu → "Скачать в MapsCreator" → `MapsCreatorPlugin.exportCurrentRoute(...)`.
3. Plugin fires `Intent(ACTION_IMPORT_ROUTE)` with `route_json` (GeoJSON LineString) + `route_name`.
4. `MainActivity.handleIncomingIntent(...)` receives it → opens `MapSelectorActivity` with route pre-loaded.
5. Rest of Flow 1 from step 3.

### Flow 3 — Export to Garmin

1. User taps "Export to Garmin" on a downloaded area card.
2. `GmndExporter.export(...)` reads tiles from `MBTilesStore` → quantizes (64-color) → writes `.gmnd` file.
3. `GarminSender.sendToGarmiand(...)` fires Intent to garmiand app with the file URI.
4. garmiand takes over: BLE chunking or HTTPS upload to its backend.

### Versioning / auto-update

- Each tile row has `downloaded_at` (unix ms). `MBTilesStore.hasTile(..., maxAgeMs)` skips tiles fresher than the threshold.
- `SettingsActivity` lets the user set "refresh tiles older than N days".
- WorkManager `PeriodicWork` (weekly) scans `map_areas.last_updated` and re-enqueues stale areas.
