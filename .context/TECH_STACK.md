# Tech Stack

## Language

**Kotlin** — both modules. Min SDK 26 (Android 8.0). JVM target 17.

## Libraries

| Library | Version | Why |
|---|---|---|
| osmdroid | 6.1.18 | Map display in `MapSelectorActivity`. No API key, MIT license. Used for the polygon drawing overlay and route preview. |
| OkHttp | 4.12.0 | Tile fetching in `TileDownloader`. Persistent connection pool; custom `User-Agent`. |
| WorkManager | 2.9.0 | Schedules the weekly auto-update check. Not used for the actual download — that's `DownloadService`. |
| Room | 2.6.1 | Used for `map_areas` table via DAO. `tiles` table uses raw SQLite (Room handles BLOB poorly with large byte arrays). |
| Coroutines | 1.8.0 | `TileDownloader` uses `async/await` with a `Semaphore(4)` for bounded parallelism. `DownloadService` launches a `coroutineScope`. |
| Material Components | 1.12.0 | Sliders (zoom range), SwitchMaterial (draw toggle), FAB, cards. |
| KSP | 1.9.23-1.0.20 | Room annotation processor — faster than kapt. |
| XmlPullParser | built-in | GPX parsing in `GpxParser`. No third-party GPX library. |

## Conventions (non-negotiable)

- **`Palette.kt` in `:app/export/` must stay in sync with `garmiand/android/.../map/Palette.kt`.**
  They share the same 64-color 4×4×4 RGB cube. If they diverge, `.gmnd` files will display garbled
  colors on the Garmin watch. Bump both when changing the palette.

- **`downloaded_at` + `source_id` are PRIMARY KEY components in the `tiles` table.**
  Never change the schema to composite-key-less — caching correctness depends on the uniqueness
  of `(zoom, col, row, source_id)` per source.

- **`DownloadService` is a Foreground Service, not a WorkManager job.**
  Downloads take minutes or hours. WorkManager is limited to 10-minute constraints on some
  OEMs without special permissions. Foreground Service + visible notification is the only
  guaranteed long-running Android path. WorkManager is only used for the lightweight weekly check.

- **Tile coordinate system is XYZ (top-left origin), not TMS (bottom-left).**
  We store tiles with the same `(zoom, tile_column, tile_row)` values that the tile servers use.
  This deviates from the strict MBTiles spec (which uses TMS Y) but is correct and consistent
  internally. Never flip the Y axis.

- **Bing requires quadkey, not XYZ.**
  `TileSource.tileUrl(z, x, y)` handles the conversion internally. All callers use the same
  interface; Bing specialness is invisible outside `TileSource`.

- **No JSON library.**
  GeoJSON is written as template strings in `MapSelectorActivity.buildBboxGeoJson` and
  `MapsCreatorPlugin.buildRouteJson`. GeoJSON is parsed with a regex in `parseBbox`.
  This is intentional — adding a JSON library for two use cases is not worth it.
