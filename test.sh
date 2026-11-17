#!/bin/bash
set -euo pipefail
PROJECT="$(cd "$(dirname "$0")" && pwd)"
DEFAULT_TOOLS="$(cd "$PROJECT/../../work/toolchain" && pwd)"
J="${YETI_JAVA_HOME:-$DEFAULT_TOOLS/jdk-17.0.21+9/Contents/Home}/bin"
mkdir -p "$PROJECT/build/tests"
SRC="$PROJECT/app/src/main/java/ru/yeti/diag"
CORE=(Protocol KwpMeasurements ReadAvailability VehicleData Haldex Elm Tp20 Engine DiagnosticBlock DiagnosticSession ParameterSelection ParameterOrder MeasurementRetention BlockInfoReader BlockConfigurationReader ObdReader VagReader HaldexReader AnalysisRunner PollingBenchmark Tp20WaitBenchmark ObdRecoveryBenchmark FaultCatalog DashboardCatalog DashboardReadings DashboardTouch DashboardNeedle DashboardDemo DashboardConnection)
SOURCES=()
for name in "${CORE[@]}"; do SOURCES+=("$SRC/$name.java"); done
"$J/javac" -encoding UTF-8 -d "$PROJECT/build/tests" "${SOURCES[@]}" "$PROJECT"/tests/*.java
"$J/java" -cp "$PROJECT/build/tests" ru.yeti.diag.TestSuite
