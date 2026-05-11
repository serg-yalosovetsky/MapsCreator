# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this project does

Downloads offline map tiles (OSM + satellite) to an Android phone — either for a route corridor
or a hand-drawn area. Tiles are stored in MBTiles format and optionally exported to a Garmin Fenix
watch via [garmiand](../garmiand). Two modules ship as separate APKs:

- **`:app`** — standalone map downloader (`com.mapscreator`)
- **`:plugin`** — OsmAnd plugin that adds "Download in MapsCreator" to OsmAnd's route menu (`com.mapscreator.osmandplugin`)

## Build commands

```powershell
cd g:\code\MapsCreator

# Debug APK — app
.\gradlew :app:assembleDebug
# Output: app\build\outputs\apk\debug\app-debug.apk

# Debug APK — OsmAnd plugin
.\gradlew :plugin:assembleDebug
# Output: plugin\build\outputs\apk\debug\plugin-debug.apk

# Install on connected device
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb install -r app\build\outputs\apk\debug\app-debug.apk
& $adb install -r plugin\build\outputs\apk\debug\plugin-debug.apk

# Logcat filter
& $adb logcat -v time TileDownloader:I DownloadService:I MBTilesStore:I GmndExporter:I *:S
```

Build via Android Studio: open `g:\code\MapsCreator\` and use the Run button.

## Architecture

Two Gradle modules, one project:

**`:app`** (`app/src/main/java/com/mapscreator/`)  
`MainActivity` shows saved map areas → FAB opens `MapSelectorActivity` (osmdroid, polygon drawing
or GPX import) → `DownloadConfirmDialog` shows tile count + size estimate → `DownloadService`
(Foreground Service) drives `TileDownloader` → tiles go into `MBTilesStore` (MBTiles SQLite).
Export path: `MainActivity` → `GmndExporter` (reads `MBTilesStore`, quantizes to 64 colors,
writes binary GMND) → `GarminSender` (Intent to garmiand or fallback share sheet).

**`:plugin`** (`plugin/src/main/java/com/mapscreator/osmandplugin/`)  
`MapsCreatorPlugin` hooks into OsmAnd's plugin system via `<meta-data android:name="osmand.plugin">`.
On "Download in MapsCreator": reads current OsmAnd route, serializes to GeoJSON LineString, fires
`Intent(ACTION_IMPORT_ROUTE)` at `MainActivity`. Requires MapsCreator `:app` to be installed.

**Key classes:**

| Class | File | Role |
|---|---|---|
| `TileSource` | `tiles/TileSource.kt` | URL presets + quadkey for Bing |
| `MBTilesStore` | `tiles/MBTilesStore.kt` | SQLite; `hasTile`, `putTile`, `getTile` |
| `TileDownloader` | `tiles/TileDownloader.kt` | Coroutine parallel fetch (Semaphore 4) |
| `TileSizeEstimator` | `tiles/TileSizeEstimator.kt` | Count tiles + estimate MB before download |
| `DownloadService` | `service/DownloadService.kt` | Foreground Service, progress notification |
| `GmndExporter` | `export/GmndExporter.kt` | MBTiles → GMND binary |
| `GarminSender` | `export/GarminSender.kt` | Intent to garmiand or share sheet |
| `Palette` | `export/Palette.kt` | 64-color palette — **must match garmiand** |
| `GpxParser` | `gpx/GpxParser.kt` | GPX → `GpxRoute` with corridor buffer |
| `MapSelectorActivity` | `ui/MapSelectorActivity.kt` | osmdroid + polygon drawing |

## Invariants that span multiple files

**`Palette.kt` must be byte-identical in both repos** — `export/Palette.kt` (MapsCreator) and
`garmiand/android/.../map/Palette.kt` (garmiand) define the same 64-color 4×4×4 RGB cube.
If they diverge, exported `.gmnd` files display wrong colors on the watch.
Always change both simultaneously and bump `Palette.VERSION` in both.

**Tile coordinates are XYZ (not TMS)** — Y is top-left origin, increases downward, matching
tile server URLs. Do not apply the TMS Y-flip anywhere.

**ArcGIS URL swaps Y and X** — `ARCGIS_YX_SWAP` format: the URL template contains `{z}/{y}/{x}`,
not `{z}/{x}/{y}`. This is ArcGIS's path convention. The `tileUrl()` method in `TileSource` still
takes `(z, x, y)` in the standard order — the swap is internal.

**`DownloadService.startForeground()` must be first** — Android 12+ kills foreground services that
don't call `startForeground()` within 5 seconds of `onStartCommand`. It is called as the first
statement in the `ACTION_START` branch, before any async work. Never move it.

**`MBTilesStore.putTile()` always sets `downloaded_at`** — `downloaded_at = System.currentTimeMillis()`.
Rows with `downloaded_at = 0` are treated as expired by `hasTile(maxAgeMs = Long.MAX_VALUE)`.
This was a past bug — don't regress it.

**GMND tile cap is 20** — `GmndExporter.MAX_TILES = 20`. Each tile is 128×128 px × 1 byte = 16 KB
on the watch. 20 tiles × 16 KB × 2 (blob + bitmap) ≈ 640 KB — near Fenix 7's storage limit.
Tiles beyond 20 are silently dropped. This is intentional, not a bug.

**FileProvider authority is `com.mapscreator.fileprovider`** — used in `GarminSender` to produce
a `content://` URI for the `.gmnd` file. The authority string must match `AndroidManifest.xml`.

**`ACTION_IMPORT_ROUTE` must be `exported=true`** — cross-app Intents from the OsmAnd plugin
require `android:exported="true"` in `MainActivity`'s intent filter and `FLAG_ACTIVITY_NEW_TASK`
in the plugin's `Intent`.

## Code style

**Kotlin**: use `Dispatchers.IO` for all tile I/O and database work. Never call `writableDatabase`
from the main thread. `TileDownloader.download()` is a `suspend fun` — always call from a coroutine.
`DownloadService` owns a `SupervisorJob` scope; child job failures don't cancel siblings.

**Logging**: `android.util.Log` with a `TAG` per class. When a UI-visible log panel is added
(like garmiand's `AppLog`), migrate to it.

**Error handling**: `TileDownloader` increments `failed` counter per tile but does not abort the
batch — partial downloads are better than full aborts. `GmndExporter` skips tiles where
`MBTilesStore.getTile()` returns null, returning an `ExportResult` with actual tile count.

**No comments restating code** — comment only when the *why* is non-obvious: a tile server quirk,
a Web Mercator subtlety, a workaround for an Android limitation.

## Context docs

`.context/` has load-bearing docs — read before making cross-cutting changes:

- [`SYSTEM_OVERVIEW.md`](.context/SYSTEM_OVERVIEW.md) — modules, runtime boundaries, main flows
- [`ADR_LOG.md`](.context/ADR_LOG.md) — why Foreground Service, why extended MBTiles schema, why XYZ not TMS
- [`API_CONTRACTS.md`](.context/API_CONTRACTS.md) — tile URLs, MBTiles schema, Intent contracts, GMND format
- [`CONFIGURATION.md`](.context/CONFIGURATION.md) — all tunable constants and where they live
- [`MAP_TILES.md`](.context/MAP_TILES.md) — Web Mercator math, zoom guide, source ToS, palette + tile cap
- [`bug_fixes.md`](.context/bug_fixes.md) — recurring failure modes and their fixes
- [`arch_code_style_guide.md`](.context/arch_code_style_guide.md) — package layout, coroutines, DB rules, Palette invariant
