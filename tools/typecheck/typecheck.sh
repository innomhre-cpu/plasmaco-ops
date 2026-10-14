#!/usr/bin/env bash
# Type-checks the Android app's Kotlin sources (and the domain and API modules) with plain kotlinc, against hand-written
# stubs of the Android, Compose, Room, Hilt, WorkManager, Retrofit and OkHttp APIs the app uses (tools/typecheck/stubs),
# and the server's Spring layer and tests against stubs of the Spring, servlet, SLF4J, Flyway and Testcontainers APIs
# they use (tools/typecheck/server-stubs, server-test-stubs).
#
# What this proves: the app code is consistent with itself and with the domain module (names, types, nullability,
# smart casts, exhaustive whens, coroutine and serialization usage), and with the library APIs *as modelled by the
# stubs*. What it does not prove: that the stubs match the real libraries exactly, Compose compiler rules, Room/Hilt
# annotation processing, resources and manifest merging. Those are checked only by the real Gradle build
# (./gradlew assembleDebug, ./gradlew :backend:build), which needs Google Maven and Maven Central access.
#
# Usage: tools/typecheck/typecheck.sh   (needs KOTLINC_HOME or kotlinc on PATH, and the kotlinx-serialization jars)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
KOTLINC_HOME="${KOTLINC_HOME:-/opt/kotlinc-dl/kotlinc}"
KOTLINC="$KOTLINC_HOME/bin/kotlinc"
OUT="${TYPECHECK_OUT:-$(mktemp -d)}"
GEN="$OUT/gen"
mkdir -p "$GEN"

# Serialization runtime: any 1.6+ jar works for type-checking; Gradle's distribution ships one.
SER_CORE="${SER_CORE:-$(ls /opt/gradle-*/lib/kotlinx-serialization-core-jvm-*.jar 2>/dev/null | head -1)}"
SER_JSON="${SER_JSON:-$(ls /opt/gradle-*/lib/kotlinx-serialization-json-jvm-*.jar 2>/dev/null | head -1)}"
COROUTINES="$KOTLINC_HOME/lib/kotlinx-coroutines-core-jvm.jar"

# Generated classes the Android build would create: R (from res/) and BuildConfig (from app/build.gradle.kts).
python3 - "$ROOT/app/src/main/res" "$GEN" <<'PY'
import os, re, sys
res, gen = sys.argv[1], sys.argv[2]
kinds = {}
for d in sorted(os.listdir(res)):
    kind = d.split('-')[0]
    path = os.path.join(res, d)
    if kind == 'values':
        for f in os.listdir(path):
            for m in re.finditer(r'<(string|color|style|dimen)\s+name="([^"]+)"', open(os.path.join(path, f)).read()):
                t = 'style' if m.group(1) == 'style' else m.group(1)
                kinds.setdefault(t, set()).add(m.group(2).replace('.', '_'))
    else:
        for f in os.listdir(path):
            kinds.setdefault(kind, set()).add(os.path.splitext(f)[0])
with open(os.path.join(gen, 'R.kt'), 'w') as out:
    out.write('package rw.plasmaco.ops\n\nobject R {\n')
    for k, names in sorted(kinds.items()):
        out.write(f'    object {k} {{\n')
        for i, n in enumerate(sorted(names)):
            out.write(f'        const val {n}: Int = {0x7f000000 + i}\n')
        out.write('    }\n')
    out.write('}\n')
with open(os.path.join(gen, 'BuildConfig.kt'), 'w') as out:
    out.write('package rw.plasmaco.ops\n\nobject BuildConfig {\n    const val DEBUG = true\n    const val APPLICATION_ID = "rw.plasmaco.ops"\n'
              '    const val VERSION_NAME = "1.0.0"\n    const val VERSION_CODE = 1\n    const val DEFAULT_SERVER_URL = ""\n}\n')
PY

echo "Type-checking with $("$KOTLINC" -version 2>&1 | grep -o 'kotlinc-jvm [0-9.]*' | head -1)…"
LIBS="$COROUTINES:$SER_CORE:$SER_JSON"
COMMON=(-language-version 2.0 -jvm-target 17 -nowarn -opt-in=kotlin.RequiresOptIn -Xplugin="$KOTLINC_HOME/lib/kotlinx-serialization-compiler-plugin.jar")

# Separate compilations, mirroring the real module boundaries: smart casts on another module's public properties are
# not allowed, so compiling everything together would hide errors the Gradle build reports.
compile() { # name, classpath, output, sources...
  local name="$1" cp="$2" out="$3"; shift 3
  echo "  $name"
  rm -rf "$out"  # never let classes from an earlier run satisfy a reference
  set +e
  "$KOTLINC" "${COMMON[@]}" -cp "$cp" -d "$out" "$@" > "$OUT/$name.log" 2>&1
  local st=$?
  set -e
  grep -E "error:" "$OUT/$name.log" | sed "s#$ROOT/##" | head -"${MAX_ERRORS:-400}" || true
  if [ $st -ne 0 ]; then echo "kotlinc failed for $name ($(grep -c 'error:' "$OUT/$name.log") error(s)); log: $OUT/$name.log"; exit $st; fi
}
compile domain "$LIBS" "$OUT/domain" "$ROOT/core/domain/src/main/kotlin"
compile api "$LIBS:$OUT/domain" "$OUT/api" "$ROOT/core/api/src/main/kotlin"
compile stubs "$LIBS" "$OUT/stubs" "$ROOT/tools/typecheck/stubs"
compile app "$LIBS:$OUT/domain:$OUT/api:$OUT/stubs" "$OUT/app" "$GEN" "$ROOT/app/src/main/kotlin"
echo "  app classes: $(find "$OUT/app" -name '*.class' | wc -l)"
compile server-stubs "$LIBS" "$OUT/server-stubs" "$ROOT/tools/typecheck/server-stubs"
compile server "$LIBS:$OUT/domain:$OUT/api:$OUT/server-stubs" "$OUT/server" "$ROOT/backend/src/main/kotlin"
# Server tests, including the PostgreSQL (Testcontainers) and HTTP (Spring Boot test) ones that only run under Gradle.
GRADLE_LIB="$(dirname "$SER_CORE")"
TEST_LIBS="$KOTLINC_HOME/lib/kotlin-test.jar:$KOTLINC_HOME/lib/kotlin-test-junit.jar:$(ls "$GRADLE_LIB"/junit-4*.jar | head -1):$(ls "$GRADLE_LIB"/hamcrest-core-*.jar | head -1)"
compile fixtures "$LIBS:$OUT/domain" "$OUT/fixtures" "$ROOT/core/domain/src/testFixtures/kotlin"
compile server-test-stubs "$LIBS:$OUT/server-stubs:$TEST_LIBS" "$OUT/server-test-stubs" "$ROOT/tools/typecheck/server-test-stubs"
compile server-tests "$LIBS:$OUT/domain:$OUT/fixtures:$OUT/api:$OUT/server-stubs:$OUT/server:$OUT/server-test-stubs:$TEST_LIBS" "$OUT/server-tests" "$ROOT/backend/src/test/kotlin"

# The app's JVM unit tests (app/src/test) cover pure logic, so they also run here, on app classes compiled against the stubs.
APP_TEST_CP="$LIBS:$OUT/domain:$OUT/fixtures:$OUT/api:$OUT/stubs:$OUT/app:$TEST_LIBS"
compile app-tests "$APP_TEST_CP" "$OUT/app-tests" "$ROOT/app/src/test/kotlin"
APP_TEST_CLASSES=$(cd "$ROOT/app/src/test/kotlin" && find . -name '*Test.kt' | sed 's#^\./##; s#\.kt$##; s#/#.#g' | sort)
echo "  app unit tests: $(echo "$APP_TEST_CLASSES" | wc -w) classes"
if ! java -cp "$APP_TEST_CP:$OUT/app-tests:$KOTLINC_HOME/lib/kotlin-stdlib.jar" org.junit.runner.JUnitCore $APP_TEST_CLASSES > "$OUT/app-tests.log" 2>&1; then
  grep -v '^\s*at ' "$OUT/app-tests.log" | head -60; echo "App unit tests failed; log: $OUT/app-tests.log"; exit 1
fi
grep -E '^OK \(' "$OUT/app-tests.log" | sed 's/^/    /'
# Instrumented tests (app/src/androidTest) need a device or emulator; here they are only type-checked.
compile android-test-stubs "$LIBS:$OUT/stubs:$TEST_LIBS" "$OUT/android-test-stubs" "$ROOT/tools/typecheck/android-test-stubs"
compile app-android-tests "$APP_TEST_CP:$OUT/android-test-stubs" "$OUT/app-android-tests" "$ROOT/app/src/androidTest/kotlin"

# Compose compiler frontend rules (@Composable call contexts, etc.). The plugin shipped in kotlinc/lib is built for
# the embeddable compiler, so its IntelliJ package references are relocated for the CLI compiler first. The run is
# expected to stop in the IR phase ("Cannot find the Composer class") because the stubs have no Compose runtime;
# reaching that point means the frontend, including the Compose checkers, reported no errors.
echo "  compose frontend"
rm -rf "$OUT/relocate" "$OUT/app-compose"
"$KOTLINC" -nowarn -cp "$KOTLINC_HOME/lib/kotlin-compiler.jar" -d "$OUT/relocate" "$ROOT/tools/typecheck/relocate/Relocate.kt" > "$OUT/relocate.log" 2>&1
java -cp "$KOTLINC_HOME/lib/kotlin-compiler.jar:$KOTLINC_HOME/lib/kotlin-stdlib.jar:$OUT/relocate" RelocateKt "$KOTLINC_HOME/lib/compose-compiler.jar" "$OUT/compose-compiler-cli.jar" 2>/dev/null
set +e
"$KOTLINC" "${COMMON[@]}" -Xplugin="$OUT/compose-compiler-cli.jar" -cp "$LIBS:$OUT/domain:$OUT/api:$OUT/stubs" -d "$OUT/app-compose" "$GEN" "$ROOT/app/src/main/kotlin" > "$OUT/compose.log" 2>&1
set -e
if grep -q "error:" "$OUT/compose.log"; then
  grep -E "error:" "$OUT/compose.log" | sed "s#$ROOT/##" | head -"${MAX_ERRORS:-400}"
  echo "Compose frontend check failed; log: $OUT/compose.log"; exit 1
elif ! grep -q "Cannot find the Composer class" "$OUT/compose.log"; then
  echo "Compose frontend check did not run as expected; log: $OUT/compose.log"; exit 1
fi

echo "  room schema and queries"
python3 "$ROOT/tools/typecheck/room_check.py"
echo "  hilt graph"
python3 "$ROOT/tools/typecheck/hilt_check.py"
echo "OK: app and server sources type-check against the shared modules and the API stubs; the app passes the Compose frontend, Room and Hilt checks."
