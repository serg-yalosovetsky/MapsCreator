# Configuration

All tunable constants, where they live, and what they affect.

## `TileDownloader.kt`

| Constant / Parameter | Default | Meaning |
|---|---|---|
| `parallelism` param | `4` | Simultaneous tile HTTP requests. Increase cautiously — tile servers may rate-limit. |
| OkHttp `connectTimeout` | `10 s` | Connection timeout per tile request. |
| OkHttp `readTimeout` | `15 s` | Read timeout per tile response. |

## `TileSizeEstimator.kt`

| Method | Notes |
|---|---|
| `tilesInBbox(...)` | Returns all `(z, x, y)` coords for a bbox. Count grows as 4× per zoom level. |
| `avgTileSizeKb` in `TileSource` | Heuristic for size estimate before download: OSM PNG ~10 KB, satellite JPEG ~50 KB. Actual sizes vary ±50%. |

## `DownloadService.kt`

| Constant | Value | Meaning |
|---|---|---|
| `ACTION_START` | `com.mapscreator.START_DOWNLOAD` | Intent action to begin a download. |
| `ACTION_CANCEL` | `com.mapscreator.CANCEL_DOWNLOAD` | Intent action from notification "Отмена" button. |
| `CHANNEL_ID` | `mapscreator_download` | Notification channel for foreground service. Low importance (no sound). |
| `NOTIF_ID` | `1001` | Notification ID. Stable; service updates this notification in-place. |

## `GmndExporter.kt`

| Constant | Value | Meaning |
|---|---|---|
| `OUTPUT_SIZE` | `128` | Each tile is scaled to 128×128 px before quantization. Matches garmiand's `DEFAULT_TILE_OUTPUT`. |
| `MAX_TILES` | `20` | Hard cap per GMND bundle (watch RAM limit). Excess tiles are silently dropped. |

## `GpxParser.kt`

| Method parameter | Default | Meaning |
|---|---|---|
| `bufferMeters` in `withCorridorBuffer` | `500.0` | Metres added around route bbox for the corridor. 500 m covers typical off-trail variance. |

## `MBTilesStore.kt`

| Constant | Value | Meaning |
|---|---|---|
| `DB_VERSION` | `1` | SQLite schema version. Bump if schema changes (add migration in `onUpgrade`). |
| File path | `<externalFilesDir>/<name>.mbtiles` | One file per named area. `getExternalFilesDir(null)` — scoped storage, no WRITE_EXTERNAL_STORAGE on API 29+. |

## `MapSelectorActivity.kt`

| UI control | Default | Meaning |
|---|---|---|
| `slider_zoom_min` | `14` | Minimum zoom level to download. Zoom 14 ≈ 1 tile covers ~2.4 km². |
| `slider_zoom_max` | `16` | Maximum zoom level. Each +1 zoom → 4× more tiles. Satellite at z17 → ~300 KB/tile. |
| osmdroid default center | `50.45, 30.52` | Kyiv, Ukraine — default map center when no route is provided. |

## `SettingsActivity.kt` (persisted in SharedPreferences)

| Key | Default | Meaning |
|---|---|---|
| `auto_update_days` | `365` | Tiles older than this are considered stale for re-download. Slider range: 30–730 days. |

## `TileSource` presets

| ID | `avgTileSizeKb` | Notes |
|---|---|---|
| `osm` | 10 | PNG, typically 5–20 KB. Underestimate; actual can vary widely. |
| `arcgis` | 50 | JPEG, typically 30–80 KB. |
| `google` | 40 | JPEG, similar to ArcGIS. |
| `bing` | 45 | JPEG. |
| custom | 30 | Conservative default for unknown sources. |
