#!/usr/bin/env python3
"""Чтение тайлов из локальных баз — MBTiles и RMaps/OsmAnd .sqlitedb.

Нужен там, где сети нет или ходить в неё незачем: нарезка карты для часов из
уже собранного склада, демон на Pi в поле, повторная сборка того же района.

Понимает три раскладки, и все три по-разному хранят одну и ту же вещь:

    MBTiles         tiles(zoom_level, tile_column, tile_row, tile_data)
                    строка в TMS, снизу вверх: row = 2^z - 1 - y
    RMaps simple    tiles(x, y, z, s, image), зум и y прямые (XYZ)
    RMaps BigPlanet tiles(x, y, z, s, image), но зум ИНВЕРТИРОВАН: z_хранимый
                    = 17 - z_реальный. Именно этим отличаются карты из
                    SAS.Planet, и если принять их зум за прямой — база
                    выглядит пустой на всех запрашиваемых зумах.

Раскладка определяется по схеме и по info.tilenumbering, а не по имени файла:
расширение .sqlitedb носят обе разновидности RMaps.

Несколько баз объединяются в StoreChain: спрашиваются по очереди, побеждает
первая, где тайл есть. Так карта из чужого источника (детальная, но на малый
район) кладётся поверх общего склада, не смешиваясь с ним в одном файле.
"""

from __future__ import annotations

import sqlite3
from pathlib import Path

BIGPLANET_BASE = 17  # z_хранимый = 17 - z_реальный


class TileStore:
    """Одна база тайлов. Раскладка определяется при открытии."""

    def __init__(self, path: str | Path):
        self.path = Path(path)
        if not self.path.is_file():
            raise FileNotFoundError(self.path)
        self.db = sqlite3.connect(f"file:{self.path}?mode=ro", uri=True)
        tables = {
            r[0]
            for r in self.db.execute(
                "SELECT name FROM sqlite_master WHERE type='table'"
            )
        }
        if "tiles" not in tables:
            raise ValueError(f"{self.path.name}: нет таблицы tiles")
        cols = {r[1] for r in self.db.execute("PRAGMA table_info(tiles)")}

        if {"zoom_level", "tile_column", "tile_row"} <= cols:
            self.kind = "mbtiles"
        elif {"x", "y", "z"} <= cols:
            numbering = "simple"
            if "info" in tables:
                info_cols = {r[1] for r in self.db.execute("PRAGMA table_info(info)")}
                if "tilenumbering" in info_cols:
                    row = self.db.execute(
                        "SELECT tilenumbering FROM info LIMIT 1"
                    ).fetchone()
                    if row and row[0]:
                        numbering = str(row[0]).strip().lower()
            self.kind = (
                "rmaps_bigplanet" if numbering.startswith("bigplanet") else "rmaps"
            )
        else:
            raise ValueError(
                f"{self.path.name}: незнакомая схема tiles ({sorted(cols)})"
            )

    def get(self, z: int, x: int, y: int) -> bytes | None:
        """Тайл в координатах XYZ (y сверху вниз), как их отдают тайл-серверы."""
        if self.kind == "mbtiles":
            row = self.db.execute(
                "SELECT tile_data FROM tiles WHERE zoom_level=? AND tile_column=? AND tile_row=?",
                (z, x, (1 << z) - 1 - y),
            ).fetchone()
        elif self.kind == "rmaps":
            row = self.db.execute(
                "SELECT image FROM tiles WHERE z=? AND x=? AND y=?", (z, x, y)
            ).fetchone()
        else:
            row = self.db.execute(
                "SELECT image FROM tiles WHERE z=? AND x=? AND y=?",
                (BIGPLANET_BASE - z, x, y),
            ).fetchone()
        return row[0] if row and row[0] else None

    def zooms(self) -> list[int]:
        """Реальные (не хранимые) зумы, которые есть в базе."""
        if self.kind == "mbtiles":
            col = "zoom_level"
        else:
            col = "z"
        found = [
            r[0]
            for r in self.db.execute(f"SELECT DISTINCT {col} FROM tiles ORDER BY {col}")
        ]
        if self.kind == "rmaps_bigplanet":
            return sorted(BIGPLANET_BASE - z for z in found)
        return found

    def count(self) -> int:
        return self.db.execute("SELECT COUNT(*) FROM tiles").fetchone()[0]

    def describe(self) -> str:
        size_mb = self.path.stat().st_size / 1_048_576
        return (
            f"{self.path.name} [{self.kind}] "
            f"{self.count():,} тайлов, зумы {self.zooms()}, {size_mb:.0f} МБ"
        )

    def close(self) -> None:
        self.db.close()


class StoreChain:
    """Несколько баз по приоритету: побеждает первая, где тайл нашёлся."""

    def __init__(self, paths: list[str | Path]):
        self.stores: list[TileStore] = []
        for p in paths:
            self.stores.append(TileStore(p))

    def get(self, z: int, x: int, y: int) -> tuple[bytes | None, str | None]:
        """(данные, имя базы). Имя нужно, чтобы видеть, откуда пришёл тайл."""
        for store in self.stores:
            blob = store.get(z, x, y)
            if blob is not None:
                return blob, store.path.name
        return None, None

    def zooms(self) -> list[int]:
        found: set[int] = set()
        for store in self.stores:
            found.update(store.zooms())
        return sorted(found)

    def describe(self) -> str:
        return "\n".join(
            f"  {i + 1}. {s.describe()}" for i, s in enumerate(self.stores)
        )

    def close(self) -> None:
        for store in self.stores:
            store.close()


if __name__ == "__main__":
    import sys

    if len(sys.argv) < 2:
        print("Использование: tile_store.py <база.sqlitedb|.mbtiles> [ещё базы...]")
        raise SystemExit(2)
    chain = StoreChain(sys.argv[1:])
    print("Цепочка баз (по приоритету):")
    print(chain.describe())
    chain.close()
