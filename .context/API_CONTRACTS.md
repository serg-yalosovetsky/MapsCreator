# API Contracts

Three interfaces: tile server URLs, MBTiles database schema, and Android Intents.

## Tile Server URLs

All tile sources use XYZ template variables: `{z}` (zoom), `{x}` (column), `{y}` (row).
Exception: Bing uses `{q}` (quadkey). The converter is in `TileSource.toQuadKey(z, x, y)`.

| Source ID | URL Template | Notes |
|---|---|---|
| `osm` | `https://tile.openstreetmap.org/{z}/{x}/{y}.png` | Standard XYZ. ToS: attribution required. |
| `arcgis` | `https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}` | **Y and X are swapped** in the URL path (ArcGIS convention). TileSource.tileUrl handles this — the template uses `{y}/{x}` in that order. |
| `google` | `https://mt1.google.com/vt/lyrs=s&x={x}&y={y}&z={z}` | Not official API. ToS restricts to personal use only. |
| `bing` | `https://ecn.t3.tiles.virtualearth.net/tiles/a{q}.jpeg?g=1` | `{q}` is quadkey. See `TileSource.toQuadKey`. |

**User-Agent header**: all requests send `User-Agent: MapsCreator/1.0`. OSM ToS requires a
descriptive user-agent. Do not remove it.

## MBTiles Database Schema (extended)

File path: `<externalFilesDir>/<name>.mbtiles`

```sql
CREATE TABLE tiles (
    zoom_level    INTEGER NOT NULL,
    tile_column   INTEGER NOT NULL,
    tile_row      INTEGER NOT NULL,
    tile_data     BLOB    NOT NULL,
    downloaded_at INTEGER NOT NULL,  -- unix ms (System.currentTimeMillis())
    source_id     TEXT    NOT NULL,  -- matches TileSource.id: "osm", "arcgis", ...
    PRIMARY KEY (zoom_level, tile_column, tile_row, source_id)
);

CREATE TABLE map_areas (
    id           TEXT    PRIMARY KEY,   -- UUID
    name         TEXT    NOT NULL,
    geojson      TEXT    NOT NULL,      -- GeoJSON Polygon or LineString bbox
    sources      TEXT    NOT NULL,      -- comma-separated source IDs: "osm,arcgis"
    zoom_min     INTEGER NOT NULL,
    zoom_max     INTEGER NOT NULL,
    tile_count   INTEGER NOT NULL DEFAULT 0,
    created_at   INTEGER NOT NULL,      -- unix ms
    last_updated INTEGER NOT NULL       -- unix ms; updated after each download
);

CREATE TABLE metadata (
    name  TEXT PRIMARY KEY,
    value TEXT
);
```

Coordinate system: XYZ (top-left origin, Y increases downward). **Not TMS** (see ADR-003).

## Android Intents

### `:plugin` → `:app` (route import from OsmAnd)

```
Action:  com.mapscreator.ACTION_IMPORT_ROUTE
Package: com.mapscreator
Extras:
  "route_json"  String  GeoJSON LineString: {"type":"LineString","coordinates":[[lon,lat],...]}
  "route_name"  String  Human-readable route name (from OsmAnd route name or file name)
```

Received in `MainActivity.handleIncomingIntent()`. Opens `MapSelectorActivity` with the route
pre-loaded and name pre-filled.

### `:app` → `garmiand` (GMND file export)

```
Action:        android.intent.action.SEND
Component:     com.garmiand / com.garmiand.ui.MainActivity
Type:          application/octet-stream
Extras:
  EXTRA_STREAM  Uri    FileProvider URI of the .gmnd file (FLAG_GRANT_READ_URI_PERMISSION)
  "gmnd_uri"    String same URI as string (garmiand-specific extra)
```

Handled in `GarminSender.sendToGarmiand(context, gmndFile)`. Falls back to `ACTION_SEND`
share sheet if garmiand is not installed.

### FileProvider authority

`com.mapscreator.fileprovider` — declared in `AndroidManifest.xml`.
Serves files from `<externalFilesDir>/exports/` (configured in `res/xml/file_provider_paths.xml`).

## GMND Binary Format

Defined by garmiand. MapsCreator produces but does not consume it.
See [garmiand/.context/MAP_RENDERING.md](../../../garmiand/.context/MAP_RENDERING.md) for the
full spec. Key invariants:

| Field | Value | Where |
|---|---|---|
| Magic | `"GMND"` (0x474D4E44) | offset 0 |
| Version | `1` | offset 4; must match `Palette.VERSION` |
| Palette | 64 colors × 3 bytes RGB | offset 24 |
| Tile pixels | column-major, 1 byte = palette index | after tile entries |

**Max tiles per bundle:** 20 (watch RAM constraint — see `GmndExporter.MAX_TILES`).
**Tile output size:** 128×128 px (see `GmndExporter.OUTPUT_SIZE`).
