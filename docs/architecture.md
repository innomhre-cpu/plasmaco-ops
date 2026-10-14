# Architecture

## Modules

| Module | What it holds | Depends on |
|---|---|---|
| `core/domain` | All business rules as plain Kotlin: master data, production reports and validation, the approval workflow, the inventory ledger and movement rules, metrics and analytics, the 11 report builders, PDF and XLSX writers, the legacy-workbook parser and import planner, roles and permissions, sync ordering rules. No Android or framework code | Kotlin stdlib only |
| `core/api` | Wire contract for `/api/v1`: DTOs, DTO ↔ domain mappers, `WireJson` settings, entity-type names, push statuses | `core/domain`, kotlinx-serialization |
| `app` | Android app: Jetpack Compose UI (Material 3), Navigation Compose, ViewModels, Room database, DataStore settings, Hilt, WorkManager sync, Retrofit client, Storage Access Framework import/export | `core/domain`, `core/api` |
| `backend` | Spring Boot server: framework-free sync and sign-in services (`server/core`) plus the Spring web and JDBC layer (`server/web`), Flyway schema | `core/domain`, `core/api` |

The same rule code runs on the device (immediate feedback, offline work) and on the server (authority). Nothing in
`core/domain` knows where it runs.

## App layers

```
ui/<feature>/        Composable screens + @HiltViewModel (StateFlow<…Ui>, one-shot events)
data/repository/     Repositories: read Room as Flow, call domain rules, write Room + audit + outbox in one transaction
data/local/          Room entities, DAOs, mappers, migrations (schema JSON in app/schemas)
data/sync/           Outbox push, cursor pull, conflict handling, WorkManager worker, connectivity
data/remote/         Retrofit API and client (HTTPS only, bearer token added per request, never logged)
data/settings/       DataStore preferences (server address, thresholds, working days, idle timeout, override switch)
ui/theme, ui/components   Design tokens and the shared component library (docs/design-system.md)
```

ViewModels never touch Room or Retrofit directly. Every write path goes through a repository that (1) checks the
permission for the signed-in actor, (2) runs the domain rule, (3) writes the record, an audit entry and, when a
server is configured, an outbox operation in one Room transaction.

## Offline-first sync

- **Outbox.** Each local change adds an operation with a client-generated idempotency key and the server version it
  was based on. `SyncRules.pushOrder` keeps operations for the same entity in order (report content before its
  workflow action).
- **Push** sends up to 200 operations; each result is applied locally (`APPLIED`/`DUPLICATE` → synced,
  `CONFLICT` → kept for side-by-side review, `REJECTED` → shown as a sync problem).
- **Pull** reads the change feed after the stored cursor until `hasMore` is false, writing server copies locally.
  A stock movement that another device posted first with the same idempotency key replaces the local copy.
- **When:** WorkManager runs sync every 15 minutes with a network constraint, two seconds after local changes while
  online, and on demand from the top bar.
- **Authority:** the server re-validates every operation (docs/server.md). The device's own validation is for
  immediate feedback only.

Standalone mode (no server address) skips the outbox entirely; the device is then the only copy, and the
administrator should take regular backups (Administration → Backup and restore).

## Inventory model

One ledger of signed transactions per item (finished goods in metres, raw materials in kg). A balance is always a
sum of posted transactions; nothing stores a running balance. Adjustments, scrap write-offs and count variances wait
for approval before they affect stock. Imported workbook history is flagged `historical` and never changes live
balances; live balances start from opening balances posted by an administrator at go-live. Negative balances are
refused unless the audited override is explicitly enabled (decisions.md).

## Security

- Roles: Operator, Storekeeper, Supervisor, Manager, Administrator. The permission matrix is one table
  (`RolePermissions`) used by the app and the server; screens hide what a role cannot do and repositories refuse it.
- Sign-in: with a server, online sign-in returns a token (HS256, 12 h by default) and caches a PBKDF2 hash of the
  PIN/password so the same person can sign in offline for 14 days. Wrong credentials and locked accounts never fall
  back to offline sign-in. Without a server, local accounts with PBKDF2-hashed PINs.
- Shared devices sign out after the idle timeout. Tokens and secrets are never logged; OS backup of app data is disabled
  (`allowBackup=false`) and the network security configuration permits HTTPS only.
- Imported files are untrusted: type and size limits (40 MB file, capped decompressed size against zip bombs), an
  XLSX reader that never evaluates formulas or runs macros and refuses XML DOCTYPE declarations, cell-level issues
  reported in the preview, and duplicate-file detection by SHA-256.
- Audit: every create, edit, workflow action, stock movement, approval, import, backup and setting change writes an
  audit entry on the device; the server keeps its own append-only audit of sign-ins and applied operations.

## Known design limits

- Device audit entries stay on the device (the server audits what it applies, which covers every synced change).
- Stock-count sheets are device-local; only the resulting count-variance movements sync.
- Master data uses last-writer-wins between administrators.
- When a user's role changes, records that became visible to them are not re-sent; a full pull (clearing the app's
  data on that device and signing in again) brings them.
