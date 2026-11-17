# Исследование совместимости · 11 ноября 2026

## Исходные данные пользователя

- Škoda Yeti 2015, 1.8 TSI CDAB, DQ250, Haldex поколения 5.
- Samsung Galaxy S25, заявлены One UI 9 и Android 17; версии на устройстве не проверены.
- Viecar работает через **Bluetooth Classic**, не BLE. Это видно на экране настройки подключения.
- Установлен профиль `PQ25, PQ35, PQ46 + UDS AT/DSG DQ500`, потому что отдельного DQ250 в списке нет.
- Значения на скриншотах демонстрационные. Из них нельзя выводить наличие или неисправность датчиков.

## Статический анализ предоставленного APK

Файл переданного диагностического приложения, 77 011 290 байт.
SHA-256: `5bcefb035baf2c07109869a85099b3a7d190b1f4890c42d0ef0e7524ad77d549`.

APK не запускался и не изменялся. Анализ локальный, сетевой передачи APK не было.

Приложение использует .NET MAUI. Управляемые сборки находятся в `lib/arm64-v8a/libassembly-store.so`, контейнер XABA; сборки внутри сжаты LZ4 (XALZ). Из контейнера извлечена основная сборка для чтения метаданных и отдельных инструкций IL — промежуточного кода .NET.

Найдены классы `VwTp20Worker`, `VAGECU`, `VagTP20Reader`, `VagUnitHelper`. Изучена сборка запроса в `VwTp20Worker.BuildRequestForData`: канал задаёт CAN-заголовок/фильтр, TP2.0-пакет включает номер последовательности и длину KWP-сообщения. Поведение сопоставлено с открытым описанием TP2.0.

В списке адресов `VAGECU.BuildList` найдены маршрут полного привода `70F` и логический адрес `0A`, а также двигатель `7E0` и коробка `7E1`. Маршрут Haldex `70F → 779` независимо подтверждён открытым OpenHaldex-S3. Номер блока «22» в интерфейсе VAG **не равен** адресу канала TP2.0 `0A`.

Во встроенных ресурсах найдены `profiles7.db` и `yeti.db`. `profiles7.db` содержит LZMA-сжатые данные; после распаковки содержимое не является открытой таблицей SQLite/JSON. Полная расшифровка базы и полный реверс всех алгоритмов не выполнены. Название файла `cs_profiles.dat` вводит в заблуждение: в этой сборке он содержит контрольные суммы файлов, а не открытый список датчиков.

В приложение не включены код, бинарные файлы, изображения или закрытая база параметров стороннего приложения. Ограничения платной версии не изменялись. Собственный клиент реализован по открытым протоколам; факты об адресах используются для совместимости.

## Источники

- ELM327, команды и протоколы: https://www.elmelectronics.com/wp-content/uploads/2017/01/ELM327DS.pdf
- Bluetooth RFCOMM Android: https://developer.android.com/develop/connectivity/bluetooth/connect-bluetooth-devices
- Разрешения Android: https://developer.android.com/develop/connectivity/bluetooth/bt-permissions
- Служба connectedDevice: https://developer.android.com/develop/background-work/services/fgs/service-types#connected-device
- Структура .NET assembly store: https://github.com/dotnet/android/blob/main/Documentation/project-docs/AssemblyStores.md
- Стандартные команды OBD: https://python-obd.readthedocs.io/en/latest/Command%20Tables/
- SAE J1979, назначение Mode 01 и диагностических режимов OBD: https://saemobilus.sae.org/standards/j1979_199709-e-e-diagnostic-test-modes
- VAG TP2.0, описание автора реализации: https://jazdw.net/tp20
- VAG Blocks, открытая реализация: https://github.com/jazdw/vag-blocks
- Пропуски зажигания, группы 014–016: https://www.ross-tech.com/vag-com/m_blocks/010-019.html
- Наддув, группы 114–118: https://www.ross-tech.com/vag-com/m_blocks/110-119.html
- DQ250/02E, температурная группа 019: https://wiki.ross-tech.com/wiki/index.php/6-Speed_Direct_Shift_Gearbox_(DSG/02E)
- VAG группы 014–016 (пропуски): https://www.ross-tech.com/vag-com/m_blocks/010-019.html
- VAG группа 020 (откаты зажигания): https://www.ross-tech.com/vag-com/m_blocks/020-029.html
- VAG группы 091/093 (фазы): https://www.ross-tech.com/vag-com/m_blocks/090-098.html
- VAG группы 032 (топливные коррекции): https://www.ross-tech.com/vag-com/m_blocks/030-049.html
- VAG группы 114/115 (наддув): https://www.ross-tech.com/vag-com/m_blocks/110-119.html
- VAG группы 001–009 (базовые измерения): https://www.ross-tech.com/vag-com/m_blocks/001-009.html
- VAG группы 090–098 (фазы распредвала): https://www.ross-tech.com/vag-com/m_blocks/090-098.html
- VAG группы 130–137 (контур охлаждения): https://www.ross-tech.com/vag-com/m_blocks/130-137.html
- DQ250/02E измерительные блоки валов, сцеплений и давлений: https://catalog.polo.blue/self-study/ssp-094-diagnosis-of-the-automatic-gearboxes-0am-02e/measured-value-blocks-02e/
- Haldex 5, профиль 0CQ907554D/7076: https://github.com/meatro/OpenHaldex-S3/blob/main/data/openhaldex-gen5-mwb-compact.json
- Haldex UDS-маршруты: https://github.com/meatro/OpenHaldex-S3/blob/main/src/functions/diag/uds.cpp

## Что даст первый журнал

Подтвердит ответы и версию Viecar, выбранный протокол, маску OBD-параметров именно двигателя, формат многочастных ответов, поддержку TP2.0, идентификаторы коробки и Haldex. Позволит отличить отсутствие параметра от ошибки связи или неизвестной формулы. Без аппаратного теста это установить по скриншотам и APK невозможно.

## Что показали журналы 11 ноября 2026

Пять присланных ZIP разобраны локально. Два сеанса завершились до обмена с ЭБУ из-за ошибки SPP; три длинных сеанса подтвердили ELM327 v1.5, CAN 11-bit и двигатель `7E0/7E8`. Маска `0100` дала 29 поддерживаемых PID из каталога приложения. Стандартный OBD сообщил выключенную лампу Check Engine и 0 сохранённых кодов двигателя в момент записи.

Haldex ответил по UDS на `70F → 779` и сообщил `0AY907554J / 7522`. Это не профиль DQ500: DQ500 был выбран пользователем только как ближайший пункт, потому что отдельного DQ250 не было. Номер `0AY907554J` используется на Yeti 4x4 с Haldex пятого поколения; это подтверждается каталогом блока и независимым списком поколений. Открытая карта OpenHaldex в исходном проекте описывает другой вариант `0CQ907554D / 7076`, поэтому формулы для `0AY/7522` в приложении помечаются как требующие проверки.

Температурная группа DQ250 `019` отвечает тремя значениями около 49–50 °C. В приложении это теперь показано автомобильными названиями G93/G509/G510 с пометкой предварительного соответствия. Сырые ответы и все ошибки связи остаются в журнале.

См. отдельный отчёт: `log-analysis-2026-11-11.md`.
