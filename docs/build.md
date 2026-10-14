# Building, testing and releasing

## Prerequisites

- JDK 17
- Android SDK with platform 35 and build tools 35 (Android Studio Ladybug or newer, or the command-line tools)
- Docker, for the server's PostgreSQL tests (optional: they are skipped without it)
- Network access to Google Maven, Maven Central and the Gradle plugin portal for the first build

All library and plugin versions are pinned in `gradle/libs.versions.toml`; the Gradle wrapper pins Gradle 8.11.1.
The repository also opens in a dev container (`.devcontainer/`) that installs JDK 17, the Android SDK and Docker.

## Commands

| Goal | Command | Output |
|---|---|---|
| Debug APK | `./gradlew :app:assembleDebug` | `app/build/outputs/apk/debug/app-debug.apk` |
| Release APK | `./gradlew :app:assembleRelease` (signing below) | `app/build/outputs/apk/release/app-release.apk` |
| Server jar | `./gradlew :backend:bootJar` | `backend/build/libs/plasmaco-server.jar` |
| All JVM tests | `./gradlew test` | domain, API, app unit and server tests |
| Instrumented tests | `./gradlew :app:connectedDebugAndroidTest` (device or emulator) | Room on SQLite, Compose UI |
| Lint | `./gradlew :app:lintDebug` | `app/build/reports/lint-results-debug.html` |

The first build exports the Room schema to `app/schemas/rw.plasmaco.ops.data.local.PlasmacoDatabase/1.json`.
Commit it: every later schema version is migrated from it and tested against it.

## Release signing

No key is stored in the repository. Create a keystore once and keep it (and its passwords) in a password manager —
losing it means the app can no longer be updated on devices:

```bash
keytool -genkeypair -v -keystore plasmaco-release.keystore -alias plasmaco -keyalg RSA -keysize 4096 -validity 10000
```

Then build with the four variables set:

```bash
export PLASMACO_KEYSTORE=/secure/path/plasmaco-release.keystore PLASMACO_KEYSTORE_PASSWORD=... \
       PLASMACO_KEY_ALIAS=plasmaco PLASMACO_KEY_PASSWORD=...
./gradlew :app:assembleRelease
```

Without them the release APK is signed with the debug key: fine for testing, not for distribution. In CI, store the
base64 of the keystore as the `PLASMACO_KEYSTORE_BASE64` secret plus the three password/alias secrets.

Install on a device with `adb install app/build/outputs/apk/release/app-release.apk`, or distribute through a managed
Google Play track or your MDM.

## Continuous integration

`.github/workflows/ci.yml` runs on every push and pull request:

1. **Repository checks** — Gradle wrapper validation; the server schema and every SQL statement on PostgreSQL 16.
2. **Shared modules and server** — domain, API and server tests (PostgreSQL via Testcontainers); uploads the server jar.
3. **Android app** — unit tests, debug and release APKs (uploaded as the `plasmaco-apks` artifact), lint report.
4. **Instrumented tests** — on an API 34 emulator, when run by hand or on `main`.

## Checks that run without Gradle

These scripts exist because the environment this code was written in could not reach Google Maven or Maven Central.
They are useful anywhere Gradle cannot download dependencies, and they are what the verification claims in
[status.md](status.md) rest on.

| Script | What it does |
|---|---|
| `tools/verify-core.sh [workbook-folder] [export-folder]` | Compiles `core/domain` with kotlinc and runs its 60 tests; with a folder of the original workbooks, also the import integration tests; writes sample PDF/XLSX exports |
| `tools/verify-server.sh` | Compiles the server's framework-free core and runs the sync, sign-in and store-contract tests |
| `tools/verify-server-sql.sh` | Starts a throwaway PostgreSQL 16, applies the Flyway migrations and exercises every SQL statement the server runs, including concurrent writers |
| `tools/typecheck/typecheck.sh` | Type-checks the app, the server and all tests against hand-written stubs of Android, Compose, Room, Hilt, WorkManager, Retrofit, Spring, Flyway and Testcontainers APIs; runs the Compose compiler's frontend checks; checks every Room query against the schema in SQLite and the Hilt dependency graph; runs the app's JVM unit tests |

The type-check proves the code is consistent with itself and with the APIs *as the stubs model them*. It does not
replace the Gradle build: annotation processing (Room, Hilt), resource merging, R8 and the real libraries are only
exercised by `./gradlew`.
