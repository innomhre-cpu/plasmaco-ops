#!/usr/bin/env bash
# Compiles and tests :core:domain with a standalone Kotlin compiler and JUnit 4,
# for machines where Gradle cannot download plugins (offline or locked-down CI).
# The canonical build is still `./gradlew :core:domain:test`.
#
# Usage: tools/verify-core.sh [path-to-workbook-folder] [folder-for-sample-exports]
#   KOTLIN_HOME  folder containing bin/kotlinc            (default: /opt/kotlinc-dl/kotlinc)
#   JUNIT_JAR    junit-4.13.2.jar                          (default: Gradle's bundled copy)
#   HAMCREST_JAR hamcrest-core-1.3.jar                     (default: Gradle's bundled copy)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
KOTLIN_HOME="${KOTLIN_HOME:-/opt/kotlinc-dl/kotlinc}"
GRADLE_LIB="$(dirname "$(readlink -f "$(command -v gradle)")")/../lib"
JUNIT_JAR="${JUNIT_JAR:-$(ls "$GRADLE_LIB"/junit-4*.jar | head -1)}"
HAMCREST_JAR="${HAMCREST_JAR:-$(ls "$GRADLE_LIB"/hamcrest-core-*.jar | head -1)}"
OUT="$ROOT/tools/.verify"
SRC="$ROOT/core/domain/src"
mkdir -p "$OUT"
KLIB="$KOTLIN_HOME/lib"
TEST_CP="$KLIB/kotlin-test.jar:$KLIB/kotlin-test-junit.jar:$JUNIT_JAR:$HAMCREST_JAR"

echo "== compiling main"
"$KOTLIN_HOME/bin/kotlinc" -jvm-target 17 -nowarn "$SRC/main/kotlin" -d "$OUT/main.jar"
test -f "$OUT/main.jar"
echo "== compiling tests"
"$KOTLIN_HOME/bin/kotlinc" -jvm-target 17 -nowarn -cp "$OUT/main.jar:$TEST_CP" "$SRC/testFixtures/kotlin" "$SRC/test/kotlin" -d "$OUT/test.jar"
test -f "$OUT/test.jar"

CLASSES=$(cd "$SRC/test/kotlin" && grep -rl --include='*Test.kt' '@Test' . | sed 's#^\./##; s#\.kt$##; s#/#.#g')
echo "== running $(echo "$CLASSES" | wc -w) test classes"
RES="$SRC/test/resources"
WB_ARG=()
if [ "${1:-}" != "" ]; then WB_ARG+=("-Dplasmaco.workbooks=$1"); fi
if [ "${2:-}" != "" ]; then WB_ARG+=("-Dplasmaco.out=$2"); fi
java "${WB_ARG[@]}" -cp "$OUT/main.jar:$OUT/test.jar:$RES:$KLIB/kotlin-stdlib.jar:$TEST_CP" org.junit.runner.JUnitCore $CLASSES
