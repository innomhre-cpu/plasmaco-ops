# PLASMACO Operations

Android app and server for PLASMACO's HDPE pipe plant: daily production reports with an approval workflow, a
finished-goods and raw-material stock ledger, dashboards and analytics, alerts, printable reports (PDF and Excel),
import of the existing stock workbook, and offline work with sync between devices.

- **App:** Kotlin, Jetpack Compose with Material 3, Navigation Compose, MVVM, Coroutines and Flow, Room, DataStore,
  Hilt, WorkManager, Storage Access Framework. Android 8.0 (API 26) and later; phones and tablets.
- **Server:** Kotlin, Spring Boot 3.3, PostgreSQL 16 with Flyway, versioned REST API (`/api/v1`).
- **Shared:** all business rules in a plain-Kotlin module used by both, so the device and the server apply the same
  rules.

> **Status:** feature-complete for a pilot, not yet built with Gradle or run on a device. The business rules, the
> server's sync and SQL, and the app's types, Room queries and dependency graph are verified by the checks in
> [docs/status.md](docs/status.md); the APK build, UI on device and Spring/JDBC runtime are not. Read that page before
> relying on anything.

## Repository

```
core/domain   business rules: reports, workflow, ledger, metrics, analytics, report builders, PDF/XLSX writers, workbook import
core/api      wire DTOs and JSON settings shared by app and server
app           Android app
backend       Spring Boot server
deploy        Dockerfile, docker-compose.yml, .env.example
docs          documentation (below)
tools         offline verification scripts and the type-check harness
```

## Quick start

```bash
./gradlew :app:assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew test                        # JVM tests (server PostgreSQL tests need Docker)
./gradlew :backend:bootJar            # server jar; see docs/server.md to run it
```

On first start the app asks how the device works. **Connect to the company server**: enter the server's `https://`
address and sign in with the bootstrap administrator configured on the server. **Use this device on its own**: create
the first administrator; the device is then the only copy of its data.

## Documentation

| Page | For |
|---|---|
| [docs/user-guide.md](docs/user-guide.md) | Everyone using the app |
| [docs/data-migration.md](docs/data-migration.md) | Moving from the stock workbook: import, opening balances, cut-over |
| [docs/decisions.md](docs/decisions.md) | Verified facts, recommended changes, and the questions PLASMACO still needs to answer |
| [docs/metrics.md](docs/metrics.md) | Every KPI, chart and alert, with its definition |
| [docs/architecture.md](docs/architecture.md) | Modules, layers, sync, inventory model, security |
| [docs/server.md](docs/server.md) | Deploying and configuring the server; API and sync semantics |
| [docs/build.md](docs/build.md) | Building, signing, testing, CI |
| [docs/design-system.md](docs/design-system.md) | Colours, type, shapes and components |
| [docs/status.md](docs/status.md) | What is built, what is verified, known limitations, next steps |

## Licences

Inter font: SIL Open Font Licence 1.1 ([docs/licenses/Inter-OFL.txt](docs/licenses/Inter-OFL.txt)). Product weights
(kg/m) are seeded from ISO 4427-2 tables as supplied in PLASMACO's workbook.
