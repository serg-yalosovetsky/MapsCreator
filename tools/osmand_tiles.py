#!/usr/bin/env python3
"""Собрать офлайн-карту .sqlitedb для OsmAnd прямо на ПК.

Тот же формат, что пишет MapsCreator (app/src/main/java/com/mapscreator/export/
OsmandSqliteExporter.kt), но без телефона: задал область — получил файл, который
кладётся в каталог OsmAnd `tiles/`.

Ключевая деталь формата: зум пишется ПРЯМОЙ (OSM), поэтому в info обязательна
колонка `tilenumbering` = 'simple'. Без неё OsmAnd считает файл BigPlanet-
нумерованным и ищет тайл зума Z под z = 17 - Z, где ничего нет — карта
открывается пустой (OsmAnd SQLiteTileSource.getFileZoom).

Примеры:
    python osmand_tiles.py --center 50.4501,30.5234 --radius-km 3 --zoom 14-16 \
        --source arcgis --out kyiv-centre.sqlitedb
    python osmand_tiles.py --bbox 50.40,30.45,50.50,30.60 --zoom 13-15 --out kyiv.sqlitedb

Как отдать готовый файл телефону (проверено на Fold 4 / Android 16):
    1. Скинуть .sqlitedb на телефон (Telegram, Downloads, adb push — куда угодно).
    2. Открыть файл любым файловым менеджером и выбрать OsmAnd — он ответит
       «Мапу імпортовано» и положит карту к себе сам.
    3. OsmAnd: Налаштувати мапу → Джерело мапи (или Допоміжний шар мапи) → <имя файла>.

Копировать файл в каталог OsmAnd руками НЕ получится: свои карты он держит в
Android/data/<pkg>/files/tiles, закрытом для сторонних приложений после scoped
storage, а Android/media/<pkg>/ у него не существует. Растровые источники к тому
же не видны, пока в OsmAnd не включён плагин «Онлайн-мапи».
"""

from __future__ import annotations

import argparse
import math
import sqlite3
import sys
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass
from pathlib import Path

# Источники — те же, что в TileSource.kt, чтобы карта на ПК и в приложении совпадала.
SOURCES: dict[str, dict[str, str]] = {
    "osm": {
        "name": "OpenStreetMap",
        "url": "https://tile.openstreetmap.org/{z}/{x}/{y}.png",
        "kind": "xyz",
    },
    "arcgis": {
        "name": "ArcGIS Satellite",
        "url": "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}",
        "kind": "xyz",
    },
    "google": {
        "name": "Google Satellite",
        "url": "https://mt1.google.com/vt/lyrs=s&x={x}&y={y}&z={z}",
        "kind": "xyz",
    },
    "bing": {
        "name": "Bing Satellite",
        "url": "https://ecn.t3.tiles.virtualearth.net/tiles/a{q}.jpeg?g=1",
        "kind": "quadkey",
    },
}

USER_AGENT = "MapsCreator-osmand-tiles/1.0 (personal offline use)"


@dataclass(frozen=True)
class Tile:
    z: int
    x: int
    y: int


def lat_lon_to_tile(lat: float, lon: float, zoom: int) -> tuple[int, int]:
    n = 1 << zoom
    x = int((lon + 180.0) / 360.0 * n)
    lat_rad = math.radians(lat)
    y = int(
        (1.0 - math.log(math.tan(lat_rad) + 1.0 / math.cos(lat_rad)) / math.pi)
        / 2.0
        * n
    )
    return max(0, min(x, n - 1)), max(0, min(y, n - 1))


def tiles_in_bbox(
    min_lat: float, min_lon: float, max_lat: float, max_lon: float, zoom: int
) -> list[Tile]:
    x_min, y_min = lat_lon_to_tile(max_lat, min_lon, zoom)
    x_max, y_max = lat_lon_to_tile(min_lat, max_lon, zoom)
    return [
        Tile(zoom, x, y)
        for x in range(x_min, x_max + 1)
        for y in range(y_min, y_max + 1)
    ]


def quadkey(z: int, x: int, y: int) -> str:
    """Bing-нумерация: биты x/y чередуются от старшего зума к младшему."""
    key = []
    for i in range(z, 0, -1):
        digit = 0
        mask = 1 << (i - 1)
        if x & mask:
            digit += 1
        if y & mask:
            digit += 2
        key.append(str(digit))
    return "".join(key)


def tile_url(source: dict[str, str], t: Tile) -> str:
    if source["kind"] == "quadkey":
        return source["url"].replace("{q}", quadkey(t.z, t.x, t.y))
    return (
        source["url"]
        .replace("{z}", str(t.z))
        .replace("{x}", str(t.x))
        .replace("{y}", str(t.y))
    )


def fetch(url: str, retries: int = 3, timeout: int = 20) -> bytes | None:
    """Тайл или None. Ошибки не считаются фатальными: дыры лучше обрыва сборки."""
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    for attempt in range(retries):
        try:
            with urllib.request.urlopen(request, timeout=timeout) as response:
                data = response.read()
                return data if data else None
        except (urllib.error.URLError, urllib.error.HTTPError, TimeoutError, OSError):
            if attempt == retries - 1:
                return None
    return None


def create_db(path: Path) -> sqlite3.Connection:
    if path.exists():
        path.unlink()
    path.parent.mkdir(parents=True, exist_ok=True)
    db = sqlite3.connect(path)
    db.execute(
        "CREATE TABLE tiles (x int, y int, z int, s int, image blob, PRIMARY KEY (x,y,z,s))"
    )
    db.execute(
        "CREATE TABLE info (tilenumbering text, minzoom int, maxzoom int, tilesize int, "
        "ellipsoid int, inverted_y int, timecolumn text, title text)"
    )
    return db


def write_info(
    db: sqlite3.Connection, min_zoom: int, max_zoom: int, title: str
) -> None:
    db.execute("DELETE FROM info")
    db.execute(
        "INSERT INTO info (tilenumbering, minzoom, maxzoom, tilesize, ellipsoid, "
        "inverted_y, timecolumn, title) VALUES ('simple', ?, ?, 256, 0, 0, 'no', ?)",
        (min_zoom, max_zoom, title),
    )


def parse_zoom(text: str) -> list[int]:
    if "-" in text:
        lo, hi = text.split("-", 1)
        return list(range(int(lo), int(hi) + 1))
    return [int(z) for z in text.split(",")]


def bbox_from_center(
    lat: float, lon: float, radius_km: float
) -> tuple[float, float, float, float]:
    d_lat = radius_km / 111.32
    d_lon = radius_km / (111.32 * math.cos(math.radians(lat)))
    return lat - d_lat, lon - d_lon, lat + d_lat, lon + d_lon


def main() -> int:
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    area = parser.add_mutually_exclusive_group(required=True)
    area.add_argument("--bbox", help="minLat,minLon,maxLat,maxLon")
    area.add_argument("--center", help="lat,lon (вместе с --radius-km)")
    parser.add_argument(
        "--radius-km",
        type=float,
        default=3.0,
        help="радиус для --center (по умолчанию 3)",
    )
    parser.add_argument(
        "--zoom", default="14-16", help="'14-16' или '13,15,17' (по умолчанию 14-16)"
    )
    parser.add_argument(
        "--source", default="arcgis", choices=sorted(SOURCES), help="источник тайлов"
    )
    parser.add_argument("--out", required=True, help="путь к .sqlitedb")
    parser.add_argument(
        "--max-tiles",
        type=int,
        default=20000,
        help="предохранитель (по умолчанию 20000)",
    )
    parser.add_argument("--workers", type=int, default=8, help="параллельных загрузок")
    args = parser.parse_args()

    if args.center:
        lat, lon = (float(v) for v in args.center.split(","))
        min_lat, min_lon, max_lat, max_lon = bbox_from_center(lat, lon, args.radius_km)
    else:
        min_lat, min_lon, max_lat, max_lon = (float(v) for v in args.bbox.split(","))
    if min_lat > max_lat or min_lon > max_lon:
        print(
            "bbox задан наоборот: ожидается minLat,minLon,maxLat,maxLon",
            file=sys.stderr,
        )
        return 2

    zooms = parse_zoom(args.zoom)
    source = SOURCES[args.source]
    tiles = [
        t for z in zooms for t in tiles_in_bbox(min_lat, min_lon, max_lat, max_lon, z)
    ]

    print(f"Область: {min_lat:.5f},{min_lon:.5f} .. {max_lat:.5f},{max_lon:.5f}")
    print(f"Источник: {source['name']}  зумы: {zooms}  тайлов: {len(tiles)}")
    if len(tiles) > args.max_tiles:
        print(
            f"Стоп: {len(tiles)} тайлов больше предохранителя --max-tiles={args.max_tiles}. "
            "Сузь область или убери верхний зум.",
            file=sys.stderr,
        )
        return 2

    out_path = Path(args.out)
    db = create_db(out_path)
    written = 0
    missing = 0

    # Качают потоки, пишет только главный: sqlite3-соединение привязано к потоку,
    # в котором создано (ProgrammingError при записи из воркера).
    def download(tile: Tile) -> tuple[Tile, bytes | None]:
        return tile, fetch(tile_url(source, tile))

    try:
        with ThreadPoolExecutor(max_workers=args.workers) as pool:
            for done, (tile, data) in enumerate(pool.map(download, tiles), start=1):
                if data is None:
                    missing += 1
                else:
                    db.execute(
                        "INSERT OR REPLACE INTO tiles (x, y, z, s, image) VALUES (?, ?, ?, 0, ?)",
                        (tile.x, tile.y, tile.z, data),
                    )
                    written += 1
                if done % 25 == 0 or done == len(tiles):
                    print(
                        f"\r{done}/{len(tiles)}  ок={written} нет={missing}",
                        end="",
                        flush=True,
                    )
        write_info(db, min(zooms), max(zooms), out_path.stem)
        db.commit()
    finally:
        db.close()

    print()
    size_mb = out_path.stat().st_size / 1_048_576
    print(
        f"Готово: {out_path}  {size_mb:.1f} МБ  тайлов {written}, не скачалось {missing}"
    )
    if written == 0:
        print(
            "Ни одного тайла — карта будет пустой; проверь сеть и источник.",
            file=sys.stderr,
        )
        return 1
    print(
        "Скинь файл на телефон и открой его — выбери OsmAnd, он импортирует сам "
        "(«Мапу імпортовано»). Затем: Налаштувати мапу → Джерело мапи → "
        f"{out_path.stem}. Нужен включённый плагин «Онлайн-мапи»."
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
