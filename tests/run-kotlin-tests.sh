#!/usr/bin/env bash
# Dependency-free Kotlin tests (protocol + core). Needs kotlinc and java on PATH (kotlinc distribution ships coroutines).
set -euo pipefail
cd "$(dirname "$0")/.."
KHOME="$(dirname "$(dirname "$(readlink -f "$(which kotlinc)")")")"
CP="$KHOME/lib/kotlin-stdlib.jar:$KHOME/lib/kotlinx-coroutines-core-jvm.jar"
OUT=$(mktemp -d)
kotlinc -nowarn -cp "$CP" shared-protocol/kotlin/src/main shared-protocol/kotlin/src/test -d "$OUT/t.jar"
java -cp "$OUT/t.jar:$CP" com.nexus.tests.TestMainKt
