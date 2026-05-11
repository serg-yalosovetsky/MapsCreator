# MapsCreator

Downloads offline map tiles (OSM + satellite) to an Android phone — either for a route corridor
or a hand-drawn area. Tiles are stored in MBTiles format and optionally exported to a Garmin Fenix
watch via [garmiand](../garmiand).

## What it does

- **Offline tiles** — download OpenStreetMap, ArcGIS Satellite, Google Satellite, or Bing Aerial
  for any area while you have Wi-Fi. Use them offline in the field.
- **Route-aware** — load a `.gpx` file and MapsCreator downloads only the tiles along the corridor
  (±500 m buffer), not the whole bounding box.
- **Area drawing** — draw a polygon with stylus or finger directly on the map.
- **Caching** — tiles already on disk are skipped on re-download. Each tile tracks when it was
  downloaded; stale tiles can be refreshed automatically.
- **Size preview** — before downloading, the app shows how many tiles will be fetched and
  estimates the total size.
- **Garmin export** — package downloaded tiles as a GMND bundle and send to
  [garmiand](../garmiand) for transfer to a Garmin Fenix 7.
- **OsmAnd plugin** — optional APK that adds a "Download in MapsCreator" button to OsmAnd's route menu.

## Project Structure

```
MapsCreator/
├── app/              Android app (com.mapscreator)
│   └── src/main/
│       ├── java/com/mapscreator/
│       │   ├── ui/           Activities, Adapter, Dialogs
│       │   ├── tiles/        TileSource, MBTilesStore, TileDownloader, TileSizeEstimator
│       │   ├── service/      DownloadService (Foreground Service)
│       │   ├── gpx/          GpxParser
│       │   └── export/       GmndExporter, GarminSender, Palette
│       ├── res/              Layouts, strings, themes
│       └── AndroidManifest.xml
├── plugin/           OsmAnd plugin (com.mapscreator.osmandplugin)
├── .context/         Architecture docs — read before changing anything cross-cutting
├── gradle/
│   ├── libs.versions.toml
│   └── wrapper/
└── settings.gradle.kts
```

## Quick Start

```powershell
# Build debug APK
cd g:\code\MapsCreator
.\gradlew :app:assembleDebug

# Install on connected device
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb install -r app\build\outputs\apk\debug\app-debug.apk
```

See [.context/BUILD_AND_DEPLOY.md](.context/BUILD_AND_DEPLOY.md) for full instructions.

## Tile Sources

| Source | Free? | Quality | Notes |
|---|---|---|---|
| OpenStreetMap | ✓ | Vector-style | Attribution required |
| ArcGIS Satellite | ✓ (non-commercial) | High | Best legal option for satellite |
| Google Satellite | Personal use | Highest | Not an official API |
| Bing Satellite | Key required | High | Quadkey addressing |
| Custom XYZ | — | — | Any `{z}/{x}/{y}` URL |

## Related Projects

- **[garmiand](../garmiand)** — Transfers GPX routes and tile bundles to Garmin Fenix 7 over BLE.
  MapsCreator exports `.gmnd` files that garmiand sends to the watch.

## Architecture Notes

Read `.context/` before making cross-cutting changes:

- [`SYSTEM_OVERVIEW.md`](.context/SYSTEM_OVERVIEW.md) — modules and main flows
- [`ADR_LOG.md`](.context/ADR_LOG.md) — why Foreground Service, why extended MBTiles schema
- [`API_CONTRACTS.md`](.context/API_CONTRACTS.md) — tile URLs, MBTiles schema, Intent contracts
- [`bug_fixes.md`](.context/bug_fixes.md) — known failure modes

## Key Invariants

**`export/Palette.kt` must match `garmiand/android/.../map/Palette.kt`** — both define the
64-color palette used to quantize tiles for the Garmin watch. If they diverge, exported
`.gmnd` files will display wrong colors. Change both simultaneously; bump `Palette.VERSION`.

**Tile coordinates are XYZ (not TMS)** — Y is top-left origin, increases downward, matching
tile server URLs. Do not apply the TMS Y-flip.

**`DownloadService` calls `startForeground()` first** — Android 12+ kills services that don't
call it within 5 seconds of `onStartCommand`.
