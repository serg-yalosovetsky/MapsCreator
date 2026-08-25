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
