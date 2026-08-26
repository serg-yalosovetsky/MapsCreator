# Склад спутниковых тайлов

Живёт на OpenWrt-роутере (RPi CM5), `/mnt/hdd/satellite/`. Копия кода здесь —
чтобы он был под версией, а не только на устройстве.

- `harvest.py` — качалка в MBTiles. Берёт понемногу и вразнобой: пачка 250–700
  тайлов за запуск, паузы 0.7–3.5 с, изредка долгая. При 403/429/503 сворачивается
  до следующего раза. Прерывать можно когда угодно — состояние в самой базе.
- `tick.sh` — обёртка для cron: сначала случайная задержка до 25 минут, потом запуск.
- `areas.json` — районы. Границы взяты из РЕАЛЬНЫХ треков с телефона, а не из
  названий файлов: первая попытка угадать по именам промахнулась на градус.

Раскатка:

    scp harvest.py tick.sh areas.json root@100.126.187.74:/mnt/hdd/satellite/
    ssh root@100.126.187.74 'chmod +x /mnt/hdd/satellite/tick.sh'

Cron на роутере (5 раз в сутки, дописано в конец `/etc/crontabs/root`, где уже
полтора десятка чужих задач):

    17 3 * * *   /mnt/hdd/satellite/tick.sh
    42 8 * * *   /mnt/hdd/satellite/tick.sh
    5 13 * * *   /mnt/hdd/satellite/tick.sh
    53 17 * * *  /mnt/hdd/satellite/tick.sh
    31 22 * * *  /mnt/hdd/satellite/tick.sh

Посмотреть, как идёт: `ssh root@100.126.187.74 'tail /mnt/hdd/satellite/harvest.log'`

## Тайл-сервер

`tileserver.py` отдаёт склад по HTTP — средний уровень между кешем на телефоне
и интернетом (garmiand APK ходит сюда перед тем, как качать из сети).

    GET /tile/{z}/{x}/{y}   тайл в схеме XYZ; 404 — «нет на складе», не сбой
    GET /health             какие базы открыты, сколько тайлов, какие зумы

Базы берутся все из каталога, приоритет — по имени файла: `00-karp26-savva.sqlitedb`
идёт раньше `arcgis.mbtiles`, потому что на общих зумах она измеримо детальнее
(резкость 82.6 против 61.5 на z15, 69.1 против 35.8 на z17 — ArcGIS там, похоже,
растягивает более низкий зум).

`tileserver.init` — procd-сервис OpenWrt, ставится как `/etc/init.d/tileserver`:

    scp tileserver.py root@100.126.187.74:/mnt/hdd/satellite/
    scp tileserver.init root@100.126.187.74:/etc/init.d/tileserver
    ssh root@100.126.187.74 'chmod +x /etc/init.d/tileserver && \
        /etc/init.d/tileserver enable && /etc/init.d/tileserver start'

START=95 — склад лежит на /mnt/hdd, до монтирования отдавать нечего.

ВНИМАНИЕ про `owrt-checkpoint`: он взводит авто-откат UCI через 30 минут и
вешает ssh на своём фоновом цикле. Для добавления файла в `/etc/init.d` он не
нужен (это не UCI, а `/etc` и так версионируется `autocommit.sh` из cron). Если
всё же вызвал — обязательно `owrt-commit`, иначе конфигурация откатится.
