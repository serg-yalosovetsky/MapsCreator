# Map Tiles

Tile sources, coordinate system, projection, and GMND export constraints.

## Web Mercator (EPSG:3857)

All tile sources use the Web Mercator (Spherical Mercator) projection.
A tile at zoom `z`, column `x`, row `y` covers:

```
lon_min = x / 2^z * 360 - 180
lon_max = (x+1) / 2^z * 360 - 180

lat_max = atan(sinh(π * (1 - 2*y / 2^z))) * 180/π
lat_min = atan(sinh(π * (1 - 2*(y+1) / 2^z))) * 180/π
```

The inverse (lat/lon → tile coords) is in `TileSizeEstimator.latLonToTile`.

## Zoom Level Guide

| Zoom | Approx tile coverage | Typical use |
|---|---|---|
| 12 | ~20 km × 20 km | Overview, route planning |
| 13 | ~10 km × 10 km | Garmiand corridor default |
| 14 | ~5 km × 5 km | Recommended minimum for MapsCreator |
| 15 | ~2.5 km × 2.5 km | Good for hiking trails |
| 16 | ~1.2 km × 1.2 km | Recommended maximum for downloads |
| 17 | ~600 m × 600 m | Very detailed, large file sizes |

Tile count per zoom level doubles in each dimension → 4× more tiles per zoom step.
A 50 km × 50 km area at zoom 16 = ~1 600 tiles.

## Tile Sources

### OpenStreetMap
- URL: `https://tile.openstreetmap.org/{z}/{x}/{y}.png`
- Format: PNG
- ToS: attribution `© OpenStreetMap contributors` required in any visible display.
- Rate limit: ~2 req/s per IP without a tile CDN mirror. `parallelism=4` with osm is borderline — consider ArcGIS for bulk downloads.

### ArcGIS World Imagery
- URL: `https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}`
- **Note: Y and X are swapped in the URL** — this is ArcGIS's path convention, not an error.
- Format: JPEG
- ToS: free for non-commercial use. Attribution: `Esri, Maxar, Earthstar Geographics`.
- Good availability, no API key needed.

### Google Satellite
- URL: `https://mt1.google.com/vt/lyrs=s&x={x}&y={y}&z={z}`
- Format: JPEG
- ToS: **not a public API**. Personal use only. Do not use in a distributed product.
- Subjectively the highest-resolution imagery for most of Europe.

### Bing Aerial
- URL: `https://ecn.t3.tiles.virtualearth.net/tiles/a{q}.jpeg?g=1`
- Format: JPEG, addressed by **quadkey** (`{q}`).
- Quadkey: for zoom z, concatenate z digits, each `0`(top-left), `1`(top-right), `2`(bottom-left), `3`(bottom-right).
  See `TileSource.toQuadKey(z, x, y)`.
- ToS: requires Bing Maps key for production use, but the subdomain endpoint often works without one.

## GMND Palette Constraint

When exporting tiles to Garmin (`.gmnd`), each pixel is quantized to one of **64 colors**
in the fixed `Palette` (4×4×4 RGB cube, indices 0–63).

The quantization is lossy — satellite imagery looks noticeably degraded at 64 colors.
OSM/vector tiles (which use fewer distinct colors) quantize better.

**Palette invariant**: `export/Palette.kt` in MapsCreator must exactly match
`garmiand/android/.../map/Palette.kt`. Both use `Palette.VERSION = 1`. If either changes,
all existing `.gmnd` files become invalid for the watch decoder.

## GMND Tile Count Limit

`GmndExporter.MAX_TILES = 20`. This is a watch RAM constraint (garmiand/Fenix 7):
- Each tile: 128×128 px × 1 byte = 16 KB in watch Application.Storage.
- 20 tiles × 16 KB × 2 (blob + decoded bitmaps) ≈ 640 KB — near the Fenix 7 limit.

`GmndExporter` silently drops tiles beyond the cap. If the user selected a large area
at high zoom, only the first 20 tiles (sorted by download order) will appear on the watch.
Future improvement: let user pick which 20 tiles to include (e.g., center on GPS position).
