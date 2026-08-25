#!/usr/bin/env python3
"""Собрать растровую карту Garmin Custom Maps (.kmz) для часов — с ПК, без телефона.

Fenix (7/8), Epix, Enduro и туристические навигаторы Garmin читают растровые
карты штатно: KMZ кладётся в /GARMIN/CustomMaps/ по USB и включается на часах
в «Карта → Шари карти». Никакого Connect IQ, BLE и телефона.

Формат (Garmin Custom Maps):
    KMZ = ZIP { doc.kml, files/tile-NNN.jpg }
    каждый кусок карты — <GroundOverlay> с <Icon><href> и <LatLonBox>
    ТОЛЬКО JPEG (PNG игнорируется), ≤ 1 мегапиксель на картинку,
    до 100 картинок в файле, один фиксированный уровень зума.

Почему один зум: Custom Maps — не пирамида тайлов. Картинка рисуется поверх
штатной карты в своих географических границах; отзумишь дальше её разрешения —
увидишь мыло, отзумишь сильно назад — часы перестанут её показывать. Поэтому
--zoom здесь ОДНО число, а не диапазон, как в osmand_tiles.py.

Почему коридор «толстеет»: JPEG непрозрачен, и наполовину заполненный кусок
лёг бы на штатную карту белым пятном. Поэтому любой блок, которого коснулся
коридор, докачивается целиком — карта остаётся цельной.

Примеры:
    python garmin_kmz.py --gpx Skole-Dovbush.gpx --corridor-m 1200 --zoom 15 \
        --source arcgis --out skole.kmz
    python garmin_kmz.py --center 48.9,23.7 --radius-km 5 --zoom 14 --out area.kmz

Как поставить на часы:
    1. Подключить часы по USB (увидишь их как «fenix 7X» в Проводнике).
    2. Скопировать .kmz в Internal Storage/GARMIN/CustomMaps/.
    3. Безопасно извлечь, отключить кабель.
    4. На часах: Карта → Шари карти → включить свою карту.
       Если её не видно — приблизь масштаб: Custom Maps показываются
       только вблизи своего разрешения.
"""

from __future__ import annotations

import argparse
import io
import math
import sys
import zipfile
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from xml.sax.saxutils import escape

from PIL import Image

from tile_store import StoreChain
from osmand_tiles import (
    SOURCES,
    Tile,
    bbox_from_center,
    corridor_tiles,
    densify,
    fetch,
    haversine_m,
    parse_gpx,
    tile_url,
    tiles_in_bbox,
)

# Ограничения формата — из спецификации Garmin Custom Maps.
MAX_OVERLAYS_PER_KMZ = 100
MAX_PIXELS_PER_OVERLAY = 1024 * 1024
JPEG_QUALITY = 85
DRAW_ORDER = 50
SOURCE_TILE_PX = 256


def tile_bounds(zoom: int, x: int, y: int) -> tuple[float, float, float, float]:
    """Границы тайла в градусах: (north, south, west, east)."""
    n = 1 << zoom

    def lat_at(ty: float) -> float:
        return math.degrees(math.atan(math.sinh(math.pi * (1.0 - 2.0 * ty / n))))

    return (
        lat_at(y),
        lat_at(y + 1),
        x / n * 360.0 - 180.0,
        (x + 1) / n * 360.0 - 180.0,
    )


def blocks_from_tiles(
    tiles: list[Tile], block: int
) -> dict[tuple[int, int], list[Tile]]:
    """Разложить тайлы по блокам block×block, выровненным по сетке зума.

    Выравнивание по сетке (а не по краю набора) важно: LatLonBox блока тогда
    считается из границ угловых тайлов и совпадает с картинкой пиксель в пиксель.
    """
    groups: dict[tuple[int, int], list[Tile]] = {}
    for t in tiles:
        groups.setdefault((t.x // block, t.y // block), []).append(t)
    return groups


def render_block(
    key: tuple[int, int],
    block: int,
    zoom: int,
    images: dict[tuple[int, int], bytes],
) -> tuple[Image.Image, tuple[float, float, float, float]]:
    """Склеить блок в одну картинку и вернуть её вместе с географическими границами."""
    bx, by = key
    x0, y0 = bx * block, by * block
    canvas = Image.new("RGB", (block * SOURCE_TILE_PX, block * SOURCE_TILE_PX), "white")
    for dy in range(block):
        for dx in range(block):
            blob = images.get((x0 + dx, y0 + dy))
            if blob is None:
                continue
            try:
                piece = Image.open(io.BytesIO(blob)).convert("RGB")
            except OSError:
                continue
            if piece.size != (SOURCE_TILE_PX, SOURCE_TILE_PX):
                piece = piece.resize((SOURCE_TILE_PX, SOURCE_TILE_PX))
            canvas.paste(piece, (dx * SOURCE_TILE_PX, dy * SOURCE_TILE_PX))
    north, _, west, _ = tile_bounds(zoom, x0, y0)
    _, south, _, east = tile_bounds(zoom, x0 + block - 1, y0 + block - 1)
    return canvas, (north, south, west, east)


def build_kml(
    overlays: list[tuple[str, tuple[float, float, float, float]]], title: str
) -> str:
    parts = [
        '<?xml version="1.0" encoding="UTF-8"?>',
        '<kml xmlns="http://www.opengis.net/kml/2.2">',
        "  <Document>",
        f"    <name>{escape(title)}</name>",
    ]
    for name, (north, south, west, east) in overlays:
        parts += [
            "    <GroundOverlay>",
            f"      <name>{escape(Path(name).stem)}</name>",
            f"      <drawOrder>{DRAW_ORDER}</drawOrder>",
            f"      <Icon><href>{escape(name)}</href></Icon>",
            "      <LatLonBox>",
            f"        <north>{north:.10f}</north>",
            f"        <south>{south:.10f}</south>",
            f"        <east>{east:.10f}</east>",
            f"        <west>{west:.10f}</west>",
            "      </LatLonBox>",
            "    </GroundOverlay>",
        ]
    parts += ["  </Document>", "</kml>"]
    return "\n".join(parts)


def write_kmz(
    out_path: Path,
    blocks: list[tuple[Image.Image, tuple[float, float, float, float]]],
    title: str,
) -> int:
    overlays = []
    payload = []
    for i, (image, box) in enumerate(blocks, start=1):
        name = f"files/tile-{i:03d}.jpg"
        buf = io.BytesIO()
        image.save(buf, format="JPEG", quality=JPEG_QUALITY, optimize=True)
        payload.append((name, buf.getvalue()))
        overlays.append((name, box))
    out_path.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(out_path, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("doc.kml", build_kml(overlays, title))
        for name, blob in payload:
            # JPEG уже сжат — второй проход deflate только ест время.
            z.writestr(name, blob, compress_type=zipfile.ZIP_STORED)
    return sum(len(b) for _, b in payload)


def main() -> int:
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    area = parser.add_mutually_exclusive_group(required=True)
    area.add_argument("--bbox", help="minLat,minLon,maxLat,maxLon")
    area.add_argument("--center", help="lat,lon (вместе с --radius-km)")
    area.add_argument("--gpx", help="GPX-трек: вырезать коридор вдоль него")
    parser.add_argument("--radius-km", type=float, default=3.0)
    parser.add_argument("--corridor-m", type=float, default=1000.0)
    parser.add_argument(
        "--zoom",
        type=int,
        default=15,
        help="ОДИН уровень зума (Custom Maps не пирамида); 15 — разумный для пешего",
    )
    parser.add_argument("--source", default="arcgis", choices=sorted(SOURCES))
    parser.add_argument(
        "--store",
        action="append",
        default=None,
        metavar="ПУТЬ",
        help="локальная база тайлов (.mbtiles или RMaps .sqlitedb). Можно "
        "повторять — порядок задаёт приоритет, первая найденная побеждает",
    )
    parser.add_argument(
        "--offline",
        action="store_true",
        help="не ходить в сеть: брать только то, что есть в --store",
    )
    parser.add_argument("--out", required=True, help="путь к .kmz")
    parser.add_argument(
        "--block",
        type=int,
        default=4,
        choices=(2, 4),
        help="тайлов в стороне куска: 4 → 1024px (по умолчанию), 2 → 512px",
    )
    parser.add_argument("--workers", type=int, default=8)
    args = parser.parse_args()

    side_px = args.block * SOURCE_TILE_PX
    if side_px * side_px > MAX_PIXELS_PER_OVERLAY:
        print(
            f"Кусок {side_px}×{side_px} больше мегапикселя — часы такую картинку "
            "отвергнут. Уменьши --block.",
            file=sys.stderr,
        )
        return 2

    zoom = args.zoom
    source = SOURCES[args.source]

    chain = None
    if args.store:
        try:
            chain = StoreChain(args.store)
        except (FileNotFoundError, ValueError) as exc:
            print(f"Склад не открылся: {exc}", file=sys.stderr)
            return 2
        print("Склад тайлов (по приоритету):")
        print(chain.describe())
        if zoom not in chain.zooms():
            print(
                f"  внимание: зума {zoom} в складе нет (есть {chain.zooms()}) — "
                + ("всё пойдёт из сети" if not args.offline else "брать нечего"),
            )
    elif args.offline:
        print("--offline без --store: брать неоткуда.", file=sys.stderr)
        return 2

    if args.gpx:
        gpx_path = Path(args.gpx)
        if not gpx_path.is_file():
            print(f"Нет файла: {gpx_path}", file=sys.stderr)
            return 2
        raw_points = parse_gpx(gpx_path)
        if not raw_points:
            print(f"В {gpx_path.name} нет точек маршрута.", file=sys.stderr)
            return 2
        points = densify(raw_points, max(args.corridor_m / 2.0, 25.0))
        length_km = (
            sum(haversine_m(a, b) for a, b in zip(raw_points, raw_points[1:])) / 1000.0
        )
        seed = corridor_tiles(points, args.corridor_m, zoom)
        print(
            f"Маршрут: {gpx_path.name}  точек {len(raw_points)}  длина {length_km:.1f} км"
        )
        title = gpx_path.stem
    else:
        if args.center:
            lat, lon = (float(v) for v in args.center.split(","))
            min_lat, min_lon, max_lat, max_lon = bbox_from_center(
                lat, lon, args.radius_km
            )
        else:
            min_lat, min_lon, max_lat, max_lon = (
                float(v) for v in args.bbox.split(",")
            )
        if min_lat > max_lat or min_lon > max_lon:
            print("bbox задан наоборот: minLat,minLon,maxLat,maxLon", file=sys.stderr)
            return 2
        seed = tiles_in_bbox(min_lat, min_lon, max_lat, max_lon, zoom)
        print(f"Область: {min_lat:.5f},{min_lon:.5f} .. {max_lat:.5f},{max_lon:.5f}")
        title = Path(args.out).stem

    if not seed:
        print("Область пустая — нечего собирать.", file=sys.stderr)
        return 2

    # Блок целиком или никак: полупустой JPEG лёг бы белым пятном на штатную карту.
    groups = blocks_from_tiles(seed, args.block)
    needed = [
        Tile(zoom, bx * args.block + dx, by * args.block + dy)
        for (bx, by) in groups
        for dy in range(args.block)
        for dx in range(args.block)
    ]

    print(
        f"Источник: {source['name']}  зум: {zoom}  "
        f"кусков: {len(groups)} по {side_px}×{side_px}  тайлов качать: {len(needed)}"
    )
    if len(seed) != len(needed):
        print(
            f"  (коридор задел {len(seed)} тайлов, блоки дозаполнены до {len(needed)} — "
            "иначе на карте были бы белые пятна)"
        )
    if len(groups) > MAX_OVERLAYS_PER_KMZ:
        print(
            f"Стоп: {len(groups)} кусков больше предела формата ({MAX_OVERLAYS_PER_KMZ} "
            "на файл). Сузь область, уменьши --corridor-m или возьми зум на 1 меньше "
            f"(--zoom {zoom - 1} даст примерно вчетверо меньше кусков).",
            file=sys.stderr,
        )
        return 2

    images: dict[tuple[int, int], bytes] = {}
    missing = 0
    from_store = 0

    # Сначала local-склад: тайл, который уже лежит на диске, качать незачем, а в
    # поле сети может не быть вовсе. Чужая детальная карта ставится первой в
    # цепочке и перекрывает общий склад на своём районе, не смешиваясь с ним.
    if chain is not None:
        for t in needed:
            blob, _ = chain.get(zoom, t.x, t.y)
            if blob is not None:
                images[(t.x, t.y)] = blob
                from_store += 1
        print(f"Из склада взято: {from_store} из {len(needed)}")

    rest = [t for t in needed if (t.x, t.y) not in images]
    if rest and args.offline:
        print(
            f"  {len(rest)} тайлов в складе нет, а --offline запрещает сеть — "
            "в этих местах карта будет белой."
        )
        missing = len(rest)
    elif rest:

        def download(t: Tile) -> tuple[Tile, bytes | None]:
            return t, fetch(tile_url(source, t))

        print(f"Догружаю из сети: {len(rest)}")
        with ThreadPoolExecutor(max_workers=args.workers) as pool:
            for done, (t, blob) in enumerate(pool.map(download, rest), start=1):
                if blob is None:
                    missing += 1
                else:
                    images[(t.x, t.y)] = blob
                if done % 25 == 0 or done == len(rest):
                    print(
                        f"\r{done}/{len(rest)}  ок={len(images) - from_store} нет={missing}",
                        end="",
                        flush=True,
                    )
        print()

    if not images:
        print(
            "Ни одного тайла не добыто — проверь склад, сеть и источник.",
            file=sys.stderr,
        )
        return 1

    blocks = [render_block(key, args.block, zoom, images) for key in sorted(groups)]
    out_path = Path(args.out)
    jpeg_bytes = write_kmz(out_path, blocks, title)

    size_mb = out_path.stat().st_size / 1_048_576
    print(
        f"Готово: {out_path}  {size_mb:.1f} МБ  "
        f"кусков {len(blocks)}, JPEG {jpeg_bytes / 1_048_576:.1f} МБ, "
        f"не скачалось тайлов {missing}"
    )
    print(
        "Скопируй файл в Internal Storage/GARMIN/CustomMaps/ на часах, отключи "
        "кабель и включи карту: Карта → Шари карти. Не видно — приблизь масштаб."
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
