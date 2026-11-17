#!/bin/bash
set -euo pipefail
PROJECT="$(cd "$(dirname "$0")" && pwd)"
TOOLS="$(cd "$PROJECT/../../work/toolchain" && pwd)"
ADB="${YETI_ADB:-$TOOLS/sdk/platform-tools/adb}"
"$ADB" devices -l
COUNT="$("$ADB" devices | awk 'NR > 1 && $2 == "device" {n++} END {print n+0}')"
if [ "$COUNT" != 1 ]; then
    printf '%s\n' 'Нужен один телефон со статусом device. Подключи USB, включи отладку по USB и подтверди доверие этому Mac на телефоне.'
    exit 1
fi
"$ADB" install -r "$PROJECT/../yeti-diagnostics-0.12.0.apk"
"$ADB" shell am start -n ru.yeti.diag/.MainActivity
