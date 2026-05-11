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

## Palette invariant (critical)

`export/Palette.kt` is a copy of garmiand's palette. Any change requires:
1. Changing both files simultaneously.
2. Bumping `Palette.VERSION` in both.
3. Regenerating all `.gmnd` files in testing.

Never change the palette in isolation.
