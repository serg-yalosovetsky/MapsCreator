# Code Style Guide

## Package Layout

```
com.mapscreator/
  ui/          — Activities, Adapters, Dialogs. No business logic.
  tiles/       — TileSource, MBTilesStore, TileDownloader, TileSizeEstimator.
  service/     — DownloadService. No UI.
  gpx/         — GpxParser. Pure I/O, no Android dependencies preferred.
  export/      — GmndExporter, GarminSender, Palette.
```

Don't add a top-level package without a clear responsibility boundary.

## Logging

No logging convention is established yet. Use `android.util.Log` with a `TAG` per class.
When a UI-visible log panel is added (like garmiand's `AppLog`), migrate to it.

## Service

`DownloadService` must call `startForeground()` as the very first action in `onStartCommand`.
Android 12+ kills foreground services that don't call it within 5 seconds.

## Coroutines

- `TileDownloader.download(...)` is a `suspend fun`. Always call it from a coroutine scope.
- `DownloadService` owns a `SupervisorJob` scope. Child jobs survive sibling failures.
- Don't do heavy work (tile decode, file writes) on `Dispatchers.Main`. Use `Dispatchers.IO`.

## Database

- `tiles` table: use raw `SQLiteOpenHelper` + `rawQuery` / `insertWithOnConflict`. Room does
  not handle large BLOBs gracefully (it materializes them in-memory when mapping to entities).
- `map_areas` table: Room DAO is appropriate (small rows, no BLOBs).
- Never call `writableDatabase` from the main thread.

## Error Handling

- `TileDownloader` increments `failed` counter per tile but does not abort the whole batch.
  A partial download (some tiles failed) is better than a full abort.
- `GmndExporter` skips tiles missing from `MBTilesStore` (`getTile` returns null) rather than
  throwing. It returns an `ExportResult` with the actual tile count.
- `GarminSender.sendToGarmiand` returns `false` if garmiand is not installed — caller shows
  a fallback share sheet.

## No comments restating code

Comment only when the *why* is non-obvious: a tile server quirk, a Web Mercator subtlety,
a workaround for an Android limitation. Knowing the reason is the only thing that helps the
reader six months from now.

## UI-specific rules

- `MapSelectorActivity` handles `onResume`/`onPause` to properly pause/resume osmdroid.
  Do not remove these calls — osmdroid holds GPS/network resources when active.
- `DownloadConfirmDialog` is not a `DialogFragment` — it's a plain `AlertDialog` builder.
  This is intentional (simpler lifecycle, no backstack interaction needed).
- **Every control that affects the tile estimate must call `updateEstimate()`** — this includes
  the four source checkboxes and both zoom sliders. If a new source or zoom control is added
  to `MapSelectorActivity`, wire it immediately. `updateEstimate()` is safe to call before a
  polygon is drawn (`polygonBbox() ?: return` guards it).

## Plugin module rules

The `:plugin` module is a **separate APK** (`android.application`, `applicationId = "com.mapscreator.osmandplugin"`).
It must never be declared as `android.library`.

**OsmAnd AIDL integration** — `android-aidl-lib:5.3` is resolved automatically via ivy in `plugin/build.gradle.kts`:
```
ivy { url = "https://builder.osmand.net"; pattern "ivy/[org]/[module]/[rev]/[artifact]-[rev].[ext]" }
implementation("net.osmand:android-aidl-lib:5.3@aar")
```
No manual download needed. Without a resolvable AAR the `:plugin` module does not compile; `:app` is unaffected.

**API 5.3 vs legacy `osmand-api.aar` differences (important):**
- Package: `net.osmand.aidlapi` (was `net.osmand.aidl`)
- `addContextMenuButtons(ContextMenuButtonsParams, IOsmAndAidlCallback)` — takes callback directly, returns `Long callbackId`; no separate `registerForUpdates` needed
- `ContextMenuButtonsParams(leftBtn, rightBtn, id, appPkg, layerId, callbackId=0, pointsIds)` — button icons are `String` names, not `Int` ids
- `RemoveContextMenuButtonsParams(paramsId: String, callbackId: Long)` — use the ID returned by `addContextMenuButtons`
- `getActiveGpx(MutableList<ASelectedGpxFile>)` — out-parameter, fills metadata only (no track coordinates)
- `onContextMenuButtonClicked(buttonId: Int, pointId: String?, layerId: String?)` — no lat/lon
- New callback methods: `updateNavigationInfo(ADirectionInfo?)`, `onVoiceRouterNotify(OnVoiceNavigationParams?)`, `onKeyEvent(KeyEvent?)`, `onLogcatMessage(OnLogcatMessageParams?)`
- Route coordinates: read GPX file directly from `Android/data/<osmand_pkg>/files/` (AIDL no longer exposes track points)

**Key classes in `:plugin`:**

| Class | Role |
|---|---|
| `PluginActivity` | Transparent Activity OsmAnd launches from its plugin list. Starts `OsmAndButtonService` and finishes. |
| `OsmAndButtonService` | Maintains AIDL binding to OsmAnd (`IOsmAndAidlInterface`). Registers the «Скачать в MapsCreator» button via `addMapContextMenuButtons`. Handles `onContextMenuButtonClicked` → calls `getActiveGpx` → fires `ACTION_IMPORT_ROUTE`. |
| `OsmAndAutoStartReceiver` | Receives `BOOT_COMPLETED` and `net.osmand.api.OSMAND_STARTED`; restarts `OsmAndButtonService`. |
| `MapsCreatorPlugin` | Stateless helpers: `exportCurrentRoute()` (fires `ACTION_IMPORT_ROUTE` to `:app`) and `buildRouteJson()` (list of lat/lon pairs → GeoJSON LineString). |

**Plugin lifecycle:**
1. `OsmAndAutoStartReceiver` starts `OsmAndButtonService` on boot / OsmAnd launch.
2. Service binds to `net.osmand.plus` or `net.osmand` (tries both).
3. On `onServiceConnected`: registers AIDL callback (`registerForUpdates`) + adds context menu button.
4. On button click: `onContextMenuButtonClicked` → `getActiveGpx` → `exportCurrentRoute` → MapsCreator opens.
5. On `onServiceDisconnected` (OsmAnd killed): service stops itself; receiver restarts it next time.

**IOsmAndAidlCallback.Stub() — implement all abstract methods.**
If the AAR version adds new abstract methods, the Kotlin compiler will report them as missing overrides.
Add empty stubs for new methods; override only `onContextMenuButtonClicked`.

**osmand.plugin meta-data value must be `"true"`** — not a class name. OsmAnd 4.x ignores class-name values.

## Palette invariant (critical)

`export/Palette.kt` is a copy of garmiand's palette. Any change requires:
1. Changing both files simultaneously.
2. Bumping `Palette.VERSION` in both.
3. Regenerating all `.gmnd` files in testing.

Never change the palette in isolation.
