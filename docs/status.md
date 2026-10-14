# Status: what is built, what is verified, what remains

Last updated 13 Oct 2026.

## Built

| Area | Delivered |
|---|---|
| Daily production reports | Seven-step editor (identification, production, materials, downtime, quality, handover, review) with field-level validation; save draft, save and continue, preview, submit; duplicate; delete never-submitted drafts; single-report PDF |
| Workflow | Submit, approve, reject (reason), return for correction (reason), reopen (manager/admin, reason); segregation of duties; immutable version per submission with a change view; approval history; audit trail |
| History | Search and filter by text, date range, line, shift, product, status; role-scoped |
| Inventory | One ledger for finished goods (m) and raw materials (kg); 12 movement types (plus legacy reconciliation for imported history); production receipts only from approved reports and within good output; adjustments, scrap and count variances with approval; stock counts; minimum and reorder thresholds; stock cards; negative stock blocked, audited override off by default |
| Dashboard and analytics | 13 KPIs, 10 drill-down charts, period/line/product/shift filters, metric definitions in the app |
| Alerts | Eight alert types, notification list with unread count, Android notifications, automatic resolution |
| Reports | 11 report types with preview; PDF and Excel export saved through the system file picker or shared |
| Excel import | Stock-workbook importer: sheet selection, resin-material mapping, preview with statistics and cell-level findings, duplicate-file detection, idempotent re-import with conflict report, read-only history |
| Go-live | Opening-balance migration (administrator, once, non-negative, with a reason) |
| Offline and sync | Room database, outbox with idempotency keys, cursor pull, conflict review (use server copy / keep mine), failed-change retry, WorkManager scheduling, connectivity awareness |
| Security | Five roles from one permission matrix; PBKDF2 PINs/passwords; server tokens; lockout; offline sign-in window; idle sign-out; HTTPS only; no secrets in the repository or logs |
| Administration | Master data, users and roles (server-backed when a server is configured), audit log, device backup and restore, settings |
| Server | Spring Boot 3.3 + PostgreSQL 16 + Flyway: sign-in, push/pull sync with full server-side re-validation, account management, append-only history and audit |
| Delivery | CI workflow (tests, APKs, server jar, lint, emulator tests), dev container, Docker image and compose file, documentation |

## Verified, and how

| Check | Result | Evidence |
|---|---|---|
| Business rules (`core/domain`) | 60 JVM tests pass, including importing the six supplied workbooks and producing sample PDF/XLSX exports | `tools/verify-core.sh /path/to/workbooks` |
| Server sync, sign-in, store contract | 27 JVM tests pass | `tools/verify-server.sh` |
| Server schema and SQL | 27 checks pass on PostgreSQL 16.15, including append-only triggers and commit-ordered change feed under concurrent writers | `tools/verify-server-sql.sh` |
| App, server and test sources compile | Type-check passes against API stubs (app: 567 classes); Compose compiler frontend checks pass | `tools/typecheck/typecheck.sh` |
| Room | All 110 DAO and sync queries prepare against the entity schema in SQLite | same script |
| Hilt | 43 injectable classes, 190 dependencies resolved | same script |
| App logic | 18 JVM tests pass (routes, filters, editor rules, export access, data visibility, entity mappers) | same script |

## Not verified in this environment

The environment used to write this code could not reach Google Maven or Maven Central, so:

- **No Gradle build has run.** No APK has been produced yet; the CI workflow builds one on first push. Annotation
  processing (Room, Hilt), resource merging and R8 have not run. Expect to fix small issues the stubs could not show.
- **The UI has not been seen running.** No screenshots, no device or emulator run, no side-by-side comparison with the
  Testora template, no TalkBack or large-font check.
- **Written but not run:** the instrumented tests (Room on SQLite, Compose UI), the server store contract on
  PostgreSQL through JDBC, and the HTTP API end-to-end test (they need Gradle, Docker or a device; CI runs them).
- **Spring wiring** (component scan, configuration binding, message converters, filter registration) is type-checked
  against stubs only.
- **Performance** with a year or more of data has not been measured; analytics are computed on the device from the
  locally visible records.

## Known limitations

- English only.
- The importer reads the stock-workbook layout only; product matching is automatic by DN/PN (new products are
  created and should be reviewed in Master data); the daily production workbook was not supplied.
- Stock-count sheets are device-local (their variance movements sync); device audit entries stay on the device; master
  data is last-writer-wins between administrators.
- Records that become visible after a role change arrive only with a full re-sync of that device.
- Two alert thresholds are fixed in code (pending approval 24 h, material variance 10 %).
- Device backups are unencrypted SQLite copies; the server has no built-in TLS (use a reverse proxy) and no
  per-address rate limiting beyond the per-account lockout.
- Password reset is done by an administrator; there is no self-service reset or single sign-on.

## Next steps, in order

1. Push to GitHub and let CI build: fix anything the real compilers report, commit the exported Room schema, and
   install the debug APK on a phone and a tablet.
2. Walk every screen on device in light and dark themes, with TalkBack and the largest font, and compare with the
   template.
3. Settle the open questions in [decisions.md](decisions.md); enter lines, shifts, machines, categories, targets and
   run rates.
4. Deploy the server, import the workbook history, count stock, post opening balances, and run a pilot on one line.
5. Add ViewModel tests with fake repositories and more Compose UI tests; add a migration test with the first schema
   change.
