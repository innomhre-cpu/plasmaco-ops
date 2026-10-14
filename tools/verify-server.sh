#!/usr/bin/env bash
# Compiles and tests the server's framework-free core (sync, auth, tokens) with a standalone Kotlin compiler and
# JUnit 4, against :core:domain and :core:api. The Spring layer (backend/.../web) is type-checked separately by
# tools/typecheck and built for real by `./gradlew :backend:test`, which also runs the PostgreSQL store tests.
#
# Usage: tools/verify-server.sh
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
KOTLIN_HOME="${KOTLIN_HOME:-/opt/kotlinc-dl/kotlinc}"
GRADLE_LIB="$(dirname "$(readlink -f "$(command -v gradle)")")/../lib"
JUNIT_JAR="${JUNIT_JAR:-$(ls "$GRADLE_LIB"/junit-4*.jar | head -1)}"
HAMCREST_JAR="${HAMCREST_JAR:-$(ls "$GRADLE_LIB"/hamcrest-core-*.jar | head -1)}"
SER_CORE="${SER_CORE:-$(ls "$GRADLE_LIB"/kotlinx-serialization-core-jvm-*.jar | head -1)}"
SER_JSON="${SER_JSON:-$(ls "$GRADLE_LIB"/kotlinx-serialization-json-jvm-*.jar | head -1)}"
KLIB="$KOTLIN_HOME/lib"
KOTLINC="$KOTLIN_HOME/bin/kotlinc"
OUT="$ROOT/tools/.verify-server"
rm -rf "$OUT" && mkdir -p "$OUT"
SER="$SER_CORE:$SER_JSON"
TEST_CP="$KLIB/kotlin-test.jar:$KLIB/kotlin-test-junit.jar:$JUNIT_JAR:$HAMCREST_JAR"
PLUGIN="-Xplugin=$KLIB/kotlinx-serialization-compiler-plugin.jar"

echo "== domain";       "$KOTLINC" -jvm-target 17 -nowarn "$ROOT/core/domain/src/main/kotlin" -d "$OUT/domain.jar"
echo "== fixtures";     "$KOTLINC" -jvm-target 17 -nowarn -cp "$OUT/domain.jar" "$ROOT/core/domain/src/testFixtures/kotlin" -d "$OUT/fixtures.jar"
echo "== api";          "$KOTLINC" -jvm-target 17 -nowarn "$PLUGIN" -cp "$OUT/domain.jar:$SER" "$ROOT/core/api/src/main/kotlin" -d "$OUT/api.jar"
echo "== server core";  "$KOTLINC" -jvm-target 17 -nowarn "$PLUGIN" -cp "$OUT/domain.jar:$OUT/api.jar:$SER" "$ROOT/backend/src/main/kotlin/rw/plasmaco/ops/server/core" -d "$OUT/core.jar"
# Everything under src/test that needs no Spring, Testcontainers or JDBC driver: the sync and auth tests, and the store
# contract run against the in-memory store. The PostgreSQL variants run in the Gradle build.
SOURCES=$(cd "$ROOT/backend/src/test/kotlin" && grep -rL --include='*.kt' 'org.springframework\|org.testcontainers\|java.sql\|javax.sql' . | sort || true)
echo "== server tests"
(cd "$ROOT/backend/src/test/kotlin" && "$KOTLINC" -jvm-target 17 -nowarn "$PLUGIN" -cp "$OUT/domain.jar:$OUT/fixtures.jar:$OUT/api.jar:$OUT/core.jar:$SER:$TEST_CP" \
  $SOURCES -d "$OUT/test.jar")
CLASSES=$(echo "$SOURCES" | grep 'Test\.kt$' | sed 's#^\./##; s#\.kt$##; s#/#.#g')
echo "== running $(echo "$CLASSES" | wc -w) test classes"
java -cp "$OUT/domain.jar:$OUT/fixtures.jar:$OUT/api.jar:$OUT/core.jar:$OUT/test.jar:$KLIB/kotlin-stdlib.jar:$SER:$TEST_CP" org.junit.runner.JUnitCore $CLASSES
