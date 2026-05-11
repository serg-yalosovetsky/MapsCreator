# Architecture Decisions

Decisions here are load-bearing. Don't reverse them without the same reasoning that produced them.

## ADR-001: Foreground Service, not WorkManager, for tile download

**Decision.** `DownloadService extends Service` calls `startForeground()` immediately.
WorkManager is only used for the weekly auto-update check (`PeriodicWork`).

**Why.** Downloading thousands of satellite tiles can take 10–30 minutes. WorkManager's
`ListenableWorker` is subject to OEM-specific battery optimizations that can kill the job
before it finishes on many Android 12+ devices. A Foreground Service with a visible
notification is the only path Android guarantees will not be killed while the user is
aware of ongoing work. WorkManager is appropriate for short (<10 min) or deferrable tasks.

**Implication.** `AndroidManifest.xml` requires `FOREGROUND_SERVICE` and
`FOREGROUND_SERVICE_DATA_SYNC` permissions, and the service declares
`android:foregroundServiceType="dataSync"`.

## ADR-002: Extended MBTiles schema — add `downloaded_at` and `source_id`

**Decision.** The `tiles` table extends the standard MBTiles spec with two extra columns:
`downloaded_at INTEGER` and `source_id TEXT`. The PRIMARY KEY is `(zoom_level, tile_column, tile_row, source_id)`.

**Why.** Standard MBTiles has a single tile per coordinate. We need:
1. **Multiple layers** — OSM and satellite for the same area in the same file.
2. **Cache expiry** — know when each tile was downloaded to support "refresh tiles older than N days".

**Implication.** The resulting `.mbtiles` file is not directly importable into vanilla MBTiles
readers (they expect a unique key per coordinate). If compatibility with external readers is
needed in the future, export a single-source subset.

## ADR-003: XYZ coordinate system (not TMS Y-flip)

**Decision.** Tile rows are stored with the same Y index that tile servers use (top-left origin,
Y increasing downward). We do not apply the TMS Y-flip (`(1 << zoom) - 1 - y`).

**Why.** MapsCreator is not a static tile hosting server — it is a download cache. The only
consumers of stored tiles are our own `MBTilesStore.getTile()` and `GmndExporter`. Flipping on
write and unflipping on read would be pointless complexity. TileSource URLs and MBTilesStore
use the same coordinate space.

**Implication.** A `.mbtiles` file produced by MapsCreator opened in QGIS or MapTiler Desktop
(which follow the TMS spec) will appear vertically mirrored. This is a known tradeoff.

## ADR-004: Palette is copied, not shared via library

**Decision.** `Palette.kt` is copied from `garmiand` into `com.mapscreator.export.Palette` rather
than extracting a shared Android library.

**Why.** Creating a third Gradle module (`shared-map`) adds dependency management overhead for
a 57-line file. The copy is explicit and traceable. The invariant ("both must match") is documented
in `TECH_STACK.md` and enforced by the comment in the file header.

**When to revisit.** If the palette changes twice in 6 months, or if more garmiand internals are
needed in MapsCreator, extract a `shared-map` Gradle module (`:shared`) and declare it as `api()`
in both `:app` and `garmiand/android/app`.

## ADR-005: GeoJSON written as template strings, parsed with regex

**Decision.** GeoJSON polygon/linestring is serialized and deserialized without a JSON library.
Writing: Kotlin string templates. Reading bbox: regex `(-?\d+\.\d+)`.

**Why.** The only two GeoJSON operations are: (1) write a rectangle or polyline when the user
selects an area, (2) read back the coordinate extremes when starting a download. A full JSON
library (Gson, kotlinx.serialization) would be ≥60 KB of dependency overhead for two trivial calls.
The regex approach is fragile against complex GeoJSON, but MapsCreator only ever writes and reads
its own GeoJSON — the format is under our control.

**Implication.** If the geojson field ever needs to store proper polygon geometries (not just bbox),
add `kotlinx-serialization-json` at that point.

## ADR-006: OsmAnd integration via Intent, not plugin API deep integration

**Decision.** The `:plugin` module fires a standard Android `Intent` to `:app` with route data as
a JSON string extra. It does not access OsmAnd's tile cache, database, or internal APIs.

**Why.** OsmAnd's plugin API (`OsmAndPlugin` class) has poor Maven availability and changes
across OsmAnd versions. The Intent approach requires only that OsmAnd call our code — we do not
depend on any OsmAnd internals. If OsmAnd plugin API becomes too limited (e.g., can't read route
points), the fallback is a fork of OsmAnd (see plan notes).

**Implication.** The plugin cannot passively observe OsmAnd state — it requires an explicit user
action (tapping "Download in MapsCreator"). Automatic background sync from OsmAnd is not possible
with this architecture.
