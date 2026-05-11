# Bug Fixes / Regression-Sensitive Areas

Known failure modes and the rules that keep them away.

## Foreground Service killed before download completes

**Symptom.** Download notification disappears after a few minutes even though tiles are still
being fetched. `DownloadService.onDestroy()` is called mid-download.

**Root cause.** `startForeground()` was not called immediately, or the notification channel
was not created before `startForeground()`.

**Fix.** `createNotificationChannel()` is called in `onCreate()` (before any `onStartCommand`).
`startForeground(NOTIF_ID, buildNotification(...))` is the first statement inside the `ACTION_START`
branch of `onStartCommand`. Never move it after any async work.

**Invariant.** `DownloadService.startForeground()` must be called within 5 seconds of
`onStartCommand` (Android 12+ enforcement). Don't put any blocking work before it.

## Tiles downloaded twice (cache miss despite file existing)

**Symptom.** `TileDownloader` reports `downloaded=N` even though the tile was already on disk.

**Root cause.** `MBTilesStore.hasTile(...)` is called with `maxAgeMs = Long.MAX_VALUE` but the
`downloaded_at` column was not set correctly (default 0 → always treated as expired).

**Fix.** `putTile(...)` always sets `downloaded_at = System.currentTimeMillis()`. If you see
tiles with `downloaded_at = 0` in the database, they were inserted by an older version —
run `UPDATE tiles SET downloaded_at = 1 WHERE downloaded_at = 0` to fix.

## Garmiand not found (GMND export)

**Symptom.** `GarminSender.sendToGarmiand(...)` returns `false` silently.

**Check.** `packageManager.getPackageInfo("com.garmiand", 0)` throws `NameNotFoundException`
— garmiand is not installed. The fallback share sheet should appear. If neither happens, check
that `isGarmiandInstalled()` is called before `sendToGarmiand`.

## GMND file has wrong colors on Garmin watch

**Symptom.** Tiles appear with garbled/wrong colors when viewed in garmiand on the Fenix 7.

**Root cause.** `export/Palette.kt` diverged from garmiand's `Palette.kt`.

**Fix.** Copy garmiand's `Palette.kt` exactly. Both must have `VERSION = 1` and the same
`COLORS` array. The quantization formula `nearest(argb)` must be bit-identical.

## Bing tiles return 404

**Symptom.** Bing tiles fail to download, `failed` counter increments.

**Root cause.** Bing quadkey calculation is wrong, or the endpoint URL changed.

**Check.** Verify `TileSource.toQuadKey(z=14, x=8800, y=5400)` returns a 14-character string.
The Bing URL may also require a different subdomain (`t0`–`t7`) for load balancing — the
current URL (`ecn.t3.tiles.virtualearth.net`) may occasionally be slow.

## OsmAnd plugin: MapsCreator doesn't open after tapping the menu action

**Symptom.** Tapping "Скачать в MapsCreator" in OsmAnd does nothing.

**Check order:**
1. Is MapsCreator (`:app`) installed? `packageManager.getPackageInfo("com.mapscreator", 0)`.
2. Is the Intent action `com.mapscreator.ACTION_IMPORT_ROUTE` declared in MapsCreator's
   `AndroidManifest.xml` with `android:exported="true"`? (It must be for cross-app Intents.)
3. On Android 12+: cross-app explicit Intents require `FLAG_ACTIVITY_NEW_TASK`.
   `MapsCreatorPlugin.exportCurrentRoute` adds this flag.

## MBTiles file grows unboundedly

**Symptom.** `<name>.mbtiles` file keeps growing even for areas downloaded multiple times.

**Root cause.** `insertWithOnConflict(..., CONFLICT_REPLACE)` deletes the old row and inserts
a new one — this is correct and should not grow the file. SQLite's WAL mode may temporarily
inflate the file; run `VACUUM` to reclaim space. If the file grows without bounds, check that
`CONFLICT_REPLACE` is being used (not `CONFLICT_IGNORE`).

## `TileSizeEstimator` returns 0 tiles for a valid bbox

**Symptom.** `tileCount(minLat, maxLat, minLon, maxLon, zoom)` returns 0.

**Root cause.** `latLonToTile` returns `(xMax < xMin)` or `(yMax < yMin)` if the bbox
crosses the antimeridian (lon ±180°) or if lat/lon coordinates are swapped.

**Fix.** Ensure `minLat < maxLat` and `minLon < maxLon`. The estimator does not handle
antimeridian-crossing bboxes (rare for European hiking routes).

## `parseBbox` fails for complex GeoJSON polygons

**Symptom.** `DownloadService` starts but downloads 0 tiles, or downloads an incorrectly
sized area.

**Root cause.** `parseBbox` uses a regex to find all floats in the GeoJSON string. If the
GeoJSON contains additional properties with numeric values (e.g., `"elevation": 1234.5`),
they will be included in the min/max calculation, producing a wrong bbox.

**Fix.** For now, MapsCreator only writes its own GeoJSON (simple rectangles with no extra
properties). If user-provided GeoJSON is ever stored in `map_areas.geojson`, switch to a
proper GeoJSON coordinate extractor.
