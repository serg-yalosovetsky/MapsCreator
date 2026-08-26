#!/usr/bin/env python3
"""Отдаёт тайлы из склада на роутере по HTTP — средний уровень между кешем
телефона и интернетом.

Зачем: тайлы, уже скачанные роутером, не надо качать заново с чужих серверов.
Телефон дома (или через Tailscale откуда угодно) берёт их отсюда — быстро,
без трафика и без вопросов к источнику. В интернет уходит только то, чего на
складе нет.

    GET /tile/{z}/{x}/{y}   тайл в схеме XYZ (y сверху вниз), как у тайл-серверов
    GET /health             что открыто, сколько тайлов, какие зумы

Склад — все базы из каталога рядом со скриптом, по приоритету имени файла
(00-, 10-): первая, где тайл нашёлся, побеждает. Раскладки (MBTiles, RMaps,
RMaps BigPlanet) разбирает tile_store.py, лежащий там же.

Соединения SQLite держатся по одному на поток: объект соединения нельзя
использовать из чужого потока, а сервер многопоточный.
"""

from __future__ import annotations

import argparse
import sys
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

from tile_store import StoreChain  # noqa: E402

STORE_PATTERNS = ("*.mbtiles", "*.sqlitedb")
_local = threading.local()


def store_paths(dirpath: Path) -> list[Path]:
    found: list[Path] = []
    for pat in STORE_PATTERNS:
        found.extend(
            p for p in dirpath.glob(pat) if p.is_file() and p.stat().st_size > 0
        )
    return sorted(set(found), key=lambda p: p.name)


def chain_for_thread(paths: list[Path]) -> StoreChain:
    """Своя цепочка баз на поток: sqlite3-соединение к чужому потоку непригодно."""
    chain = getattr(_local, "chain", None)
    if chain is None:
        chain = StoreChain(paths)
        _local.chain = chain
    return chain


def sniff_type(blob: bytes) -> str:
    if blob[:2] == b"\xff\xd8":
        return "image/jpeg"
    if blob[:8] == b"\x89PNG\r\n\x1a\n":
        return "image/png"
    if blob[:4] == b"RIFF" and blob[8:12] == b"WEBP":
        return "image/webp"
    return "application/octet-stream"


class Handler(BaseHTTPRequestHandler):
    server_version = "MeshTileServer/1.0"
    paths: list[Path] = []
    quiet = True

    def log_message(self, fmt: str, *args) -> None:
        if not self.quiet:
            super().log_message(fmt, *args)

    def _send(self, code: int, body: bytes, ctype: str, cache: bool = False) -> None:
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        if cache:
            # Тайл по координатам неизменен: пусть телефон и прокси кешируют долго.
            self.send_header("Cache-Control", "public, max-age=2592000, immutable")
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self) -> None:  # noqa: N802
        parts = self.path.strip("/").split("?")[0].split("/")

        if parts and parts[0] == "health":
            chain = chain_for_thread(self.paths)
            lines = [f"базы: {len(self.paths)}", f"зумы: {chain.zooms()}", ""]
            lines.append(chain.describe())
            self._send(
                200, "\n".join(lines).encode("utf-8"), "text/plain; charset=utf-8"
            )
            return

        if len(parts) == 4 and parts[0] == "tile":
            try:
                z, x, y = (int(v) for v in parts[1:4])
            except ValueError:
                self._send(400, b"z/x/y must be integers\n", "text/plain")
                return
            if not (0 <= z <= 22) or x < 0 or y < 0 or x >= (1 << z) or y >= (1 << z):
                self._send(400, b"tile out of range\n", "text/plain")
                return
            blob, src = chain_for_thread(self.paths).get(z, x, y)
            if blob is None:
                # 404 — обычный ответ, а не сбой: телефон пойдёт в интернет.
                self._send(404, b"", "application/octet-stream")
                return
            self.send_response(200)
            self.send_header("Content-Type", sniff_type(blob))
            self.send_header("Content-Length", str(len(blob)))
            self.send_header("Cache-Control", "public, max-age=2592000, immutable")
            self.send_header("X-Tile-Source", src or "?")
            self.end_headers()
            self.wfile.write(blob)
            return

        self._send(404, b"use /tile/{z}/{x}/{y} or /health\n", "text/plain")


def main() -> int:
    ap = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    ap.add_argument(
        "--dir", default=str(HERE), help="каталог со складом (по умолчанию рядом)"
    )
    ap.add_argument("--port", type=int, default=8811)
    ap.add_argument("--host", default="0.0.0.0")
    ap.add_argument("--verbose", action="store_true", help="логировать каждый запрос")
    args = ap.parse_args()

    dirpath = Path(args.dir)
    paths = store_paths(dirpath)
    if not paths:
        print(
            f"В {dirpath} нет ни одной базы тайлов — нечего отдавать.", file=sys.stderr
        )
        return 2

    probe = StoreChain(paths)
    print(f"Склад ({len(paths)} баз):")
    print(probe.describe())
    print(f"Зумы: {probe.zooms()}")
    probe.close()

    Handler.paths = paths
    Handler.quiet = not args.verbose
    srv = ThreadingHTTPServer((args.host, args.port), Handler)
    srv.daemon_threads = True
    print(f"Слушаю http://{args.host}:{args.port}/tile/{{z}}/{{x}}/{{y}}", flush=True)
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        pass
    return 0


if __name__ == "__main__":
    sys.exit(main())
