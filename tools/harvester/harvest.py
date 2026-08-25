#!/usr/bin/env python3
"""Медленный склад спутниковых тайлов в MBTiles — для офлайн-карт Garmin и OsmAnd.

Качает понемногу и вразнобой: за один запуск берёт небольшую случайную пачку,
между тайлами спит случайное время, изредка делает долгую паузу. Это не приёмы
маскировки, а то, ради чего всё затевалось: тайлы нужны один раз, спешить некуда,
а ровный поток из тысяч запросов подряд — прямой путь к тому, что источник
закроет доступ и карты не будет вовсе.

Запускается по cron несколько раз в день. Между запусками состояние живёт в самой
базе: что уже скачано, то повторно не запрашивается, поэтому прерывать можно в
любой момент.

Область задаётся в areas.json рядом со скриптом:

    {
      "areas": [
        {"name": "Сколівські Бескиди", "bbox": [48.70, 23.10, 49.25, 23.95],
         "zooms": [13, 14, 15, 16], "priority": 1}
      ]
    }

bbox — [minLat, minLon, maxLat, maxLon]. Меньший priority обслуживается раньше;
внутри области сначала идут низкие зумы (обзор появляется быстро, детализация
дотягивается потом). Вместо bbox можно задать "gpx": "tracks/поход.gpx" вместе с
"corridor_m" — тогда берётся полоса вдоль трека.

MBTiles хранит строку тайла в схеме TMS (снизу вверх), а тайл-серверы отдают XYZ
(сверху вниз), поэтому при записи y переворачивается. Забыть это — получить
базу, которая читается вверх ногами.

Примеры:
    python3 harvest.py                    # обычный прогон по cron
    python3 harvest.py --budget 50        # короткий пробный
    python3 harvest.py --plan             # только показать, что осталось
"""

from __future__ import annotations

import argparse
import json
import math
import os
import random
import sqlite3
import sys
import time
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET
from datetime import datetime
from pathlib import Path

HERE = Path(__file__).resolve().parent
DB_PATH = HERE / "arcgis.mbtiles"
AREAS_PATH = HERE / "areas.json"
LOG_PATH = HERE / "harvest.log"
LOCK_PATH = HERE / "harvest.lock"

TILE_URL = (
    "https://server.arcgisonline.com/ArcGIS/rest/services/"
    "World_Imagery/MapServer/tile/{z}/{y}/{x}"
)
USER_AGENT = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"
)

# Пачка за запуск и паузы между тайлами — берутся случайно из этих границ.
BUDGET_MIN, BUDGET_MAX = 250, 700
DELAY_MIN, DELAY_MAX = 0.7, 3.5
# Изредка — «человек отвлёкся».
LONG_PAUSE_CHANCE = 0.04
LONG_PAUSE_MIN, LONG_PAUSE_MAX = 20.0, 90.0
# Сколько подряд неудач терпим, прежде чем свернуться до следующего запуска.
FAIL_LIMIT = 12
# 403/429 — источник недоволен: уходим сразу и надолго.
BACKOFF_CODES = (403, 429, 503)


def log(msg: str) -> None:
    line = f"{datetime.now():%Y-%m-%d %H:%M:%S}  {msg}"
    print(line, flush=True)
    try:
        with LOG_PATH.open("a", encoding="utf-8") as fh:
            fh.write(line + "\n")
    except OSError:
        pass


def open_db() -> sqlite3.Connection:
    db = sqlite3.connect(DB_PATH, timeout=60)
    db.execute("PRAGMA journal_mode=WAL")
    db.execute("PRAGMA synchronous=NORMAL")
    db.execute(
        "CREATE TABLE IF NOT EXISTS tiles ("
        "zoom_level integer, tile_column integer, tile_row integer, tile_data blob)"
    )
    db.execute(
        "CREATE UNIQUE INDEX IF NOT EXISTS tile_index "
        "ON tiles (zoom_level, tile_column, tile_row)"
    )
    db.execute("CREATE TABLE IF NOT EXISTS metadata (name text, value text)")
    db.execute("CREATE UNIQUE INDEX IF NOT EXISTS metadata_name ON metadata (name)")
    # Пустые тайлы источника (океан, дыры в покрытии) — чтобы не долбить их каждый раз.
    db.execute(
        "CREATE TABLE IF NOT EXISTS blanks ("
        "zoom_level integer, tile_column integer, tile_row integer, "
        "seen_at text, PRIMARY KEY (zoom_level, tile_column, tile_row))"
    )
    for name, value in (
        ("name", "ArcGIS World Imagery"),
        ("format", "jpg"),
        ("type", "baselayer"),
        ("version", "1"),
        ("description", "Спутниковые тайлы, собираются постепенно (harvest.py)"),
    ):
        db.execute(
            "INSERT OR IGNORE INTO metadata (name, value) VALUES (?, ?)", (name, value)
        )
    db.commit()
    return db


def lat_lon_to_tile(lat: float, lon: float, z: int) -> tuple[int, int]:
    n = 1 << z
    x = int((lon + 180.0) / 360.0 * n)
    r = math.radians(max(-85.05, min(85.05, lat)))
    y = int((1.0 - math.log(math.tan(r) + 1.0 / math.cos(r)) / math.pi) / 2.0 * n)
    return max(0, min(x, n - 1)), max(0, min(y, n - 1))


def tiles_for_bbox(bbox: list[float], z: int) -> list[tuple[int, int]]:
    min_lat, min_lon, max_lat, max_lon = bbox
    x0, y0 = lat_lon_to_tile(max_lat, min_lon, z)
    x1, y1 = lat_lon_to_tile(min_lat, max_lon, z)
    return [(x, y) for x in range(x0, x1 + 1) for y in range(y0, y1 + 1)]


def gpx_points(path: Path) -> list[tuple[float, float]]:
    """Точки трека. Теги ищутся по локальному имени: namespace у GPX 1.0/1.1 разный."""
    root = ET.parse(path).getroot()
    for tag in ("trkpt", "rtept", "wpt"):
        found = []
        for el in root.iter():
            if el.tag.rsplit("}", 1)[-1] != tag:
                continue
            lat, lon = el.get("lat"), el.get("lon")
            if lat and lon:
                found.append((float(lat), float(lon)))
        if found:
            return found
    return []


def tiles_for_corridor(
    points: list[tuple[float, float]], buffer_m: float, z: int
) -> list[tuple[int, int]]:
    n = 1 << z
    seen: dict[tuple[int, int], None] = {}
    for lat, lon in points:
        d_lat = buffer_m / 111_000.0
        d_lon = buffer_m / (111_000.0 * max(math.cos(math.radians(lat)), 1e-6))
        x0, y0 = lat_lon_to_tile(lat + d_lat, lon - d_lon, z)
        x1, y1 = lat_lon_to_tile(lat - d_lat, lon + d_lon, z)
        for y in range(min(y0, y1), max(y0, y1) + 1):
            for x in range(min(x0, x1), max(x0, x1) + 1):
                if 0 <= x < n and 0 <= y < n:
                    seen.setdefault((x, y))
    return list(seen)


def load_areas() -> list[dict]:
    if not AREAS_PATH.is_file():
        log(f"нет {AREAS_PATH.name} — нечего качать")
        return []
    areas = json.loads(AREAS_PATH.read_text(encoding="utf-8")).get("areas", [])
    return sorted(areas, key=lambda a: a.get("priority", 100))


def pending_for_area(db: sqlite3.Connection, area: dict) -> list[tuple[int, int, int]]:
    """Что осталось скачать в этой области: (z, x, y), низкие зумы первыми."""
    out: list[tuple[int, int, int]] = []
    for z in sorted(area.get("zooms", [13, 14, 15])):
        if "gpx" in area:
            gpx_path = HERE / area["gpx"]
            if not gpx_path.is_file():
                log(f"  !! нет трека {area['gpx']}, область пропущена")
                continue
            coords = tiles_for_corridor(
                gpx_points(gpx_path), float(area.get("corridor_m", 3000)), z
            )
        else:
            coords = tiles_for_bbox(area["bbox"], z)
        if not coords:
            continue
        have = {
            (r[0], r[1])
            for r in db.execute(
                "SELECT tile_column, tile_row FROM tiles WHERE zoom_level = ?", (z,)
            )
        }
        blank = {
            (r[0], r[1])
            for r in db.execute(
                "SELECT tile_column, tile_row FROM blanks WHERE zoom_level = ?", (z,)
            )
        }
        n = 1 << z
        for x, y in coords:
            row = n - 1 - y  # XYZ -> TMS
            if (x, row) not in have and (x, row) not in blank:
                out.append((z, x, y))
    return out


def fetch(z: int, x: int, y: int) -> tuple[bytes | None, int | None]:
    """(данные, http-код). Данные None — тайла нет или ошибка."""
    url = TILE_URL.format(z=z, x=x, y=y)
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            return resp.read() or None, resp.status
    except urllib.error.HTTPError as exc:
        return None, exc.code
    except (urllib.error.URLError, TimeoutError, OSError):
        return None, None


def main() -> int:
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    parser.add_argument(
        "--budget",
        type=int,
        default=None,
        help="сколько тайлов взять (по умолчанию случайно 250-700)",
    )
    parser.add_argument(
        "--plan", action="store_true", help="только показать остаток, ничего не качать"
    )
    args = parser.parse_args()

    # Два экземпляра сразу — двойная нагрузка на источник; cron перекрывается легко.
    if LOCK_PATH.exists():
        age = time.time() - LOCK_PATH.stat().st_mtime
        if age < 3600:
            log(f"уже работает (замок {age / 60:.0f} мин) — выхожу")
            return 0
        log(f"замок протух ({age / 3600:.1f} ч) — снимаю")
    LOCK_PATH.write_text(str(os.getpid()))

    try:
        db = open_db()
        areas = load_areas()
        if not areas:
            return 1

        total_have = db.execute("SELECT COUNT(*) FROM tiles").fetchone()[0]
        size_mb = DB_PATH.stat().st_size / 1_048_576 if DB_PATH.exists() else 0
        log(f"склад: {total_have:,} тайлов, {size_mb:.1f} МБ")

        queue: list[tuple[int, int, int]] = []
        for area in areas:
            pending = pending_for_area(db, area)
            log(f"  {area.get('name', '?')}: осталось {len(pending):,}")
            queue.extend(pending)

        if not queue:
            log("всё скачано — работы нет")
            return 0
        if args.plan:
            log(f"итого осталось {len(queue):,} тайлов")
            return 0

        budget = args.budget or random.randint(BUDGET_MIN, BUDGET_MAX)
        # Берём из головы очереди (приоритет областей и зумов), но внутри пачки идём
        # вразнобой: последовательный обход сетки — самый узнаваемый признак робота.
        batch = queue[: budget * 3]
        random.shuffle(batch)
        batch = batch[:budget]
        log(f"беру {len(batch)} тайлов из {len(queue):,}")

        ok = blank = fail = 0
        fails_in_row = 0
        started = time.time()

        for z, x, y in batch:
            data, code = fetch(z, x, y)
            if code in BACKOFF_CODES:
                log(f"!! источник ответил {code} — сворачиваюсь до следующего запуска")
                break
            if data is None:
                if code == 404:
                    n = 1 << z
                    db.execute(
                        "INSERT OR IGNORE INTO blanks VALUES (?, ?, ?, ?)",
                        (z, x, n - 1 - y, datetime.now().isoformat(timespec="seconds")),
                    )
                    blank += 1
                    fails_in_row = 0
                else:
                    fail += 1
                    fails_in_row += 1
                    if fails_in_row >= FAIL_LIMIT:
                        log(
                            f"!! {fails_in_row} ошибок подряд — сеть или источник лёг, выхожу"
                        )
                        break
            else:
                n = 1 << z
                db.execute(
                    "INSERT OR REPLACE INTO tiles VALUES (?, ?, ?, ?)",
                    (z, x, n - 1 - y, data),
                )
                ok += 1
                fails_in_row = 0
                if ok % 50 == 0:
                    db.commit()

            time.sleep(random.uniform(DELAY_MIN, DELAY_MAX))
            if random.random() < LONG_PAUSE_CHANCE:
                time.sleep(random.uniform(LONG_PAUSE_MIN, LONG_PAUSE_MAX))

        db.commit()
        total_have = db.execute("SELECT COUNT(*) FROM tiles").fetchone()[0]
        size_mb = DB_PATH.stat().st_size / 1_048_576
        mins = (time.time() - started) / 60
        log(
            f"готово за {mins:.1f} мин: +{ok} тайлов, пустых {blank}, ошибок {fail}. "
            f"Склад: {total_have:,} тайлов, {size_mb:.1f} МБ. "
            f"Осталось ~{len(queue) - ok - blank:,}"
        )
        db.close()
        return 0
    finally:
        LOCK_PATH.unlink(missing_ok=True)


if __name__ == "__main__":
    sys.exit(main())
