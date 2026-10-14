# Server: deployment, configuration and API

The server (`backend/`) is a Kotlin Spring Boot 3.3 application on PostgreSQL 16. It is the shared source of truth when
several devices are used: devices push their queued changes and pull everyone else's. A single device can also run
without a server (standalone mode); see the user guide.

## Run it

Prerequisites: JDK 17, PostgreSQL 16 (or Docker), a TLS-terminating reverse proxy (nginx, Caddy, a cloud load
balancer). The Android app only accepts `https://` server addresses.

```bash
./gradlew :backend:bootJar                       # backend/build/libs/plasmaco-server.jar
cp deploy/.env.example deploy/.env               # fill in the values; never commit deploy/.env
docker compose -f deploy/docker-compose.yml --env-file deploy/.env up -d
```

Or run the jar directly against an existing database:

```bash
export PLASMACO_DB_URL=jdbc:postgresql://db-host:5432/plasmaco PLASMACO_DB_USER=plasmaco PLASMACO_DB_PASSWORD=...
export PLASMACO_JWT_SECRET="$(openssl rand -base64 48)"
export PLASMACO_BOOTSTRAP_ADMIN_USERNAME=admin PLASMACO_BOOTSTRAP_ADMIN_PASSWORD='a strong first password'
java -jar backend/build/libs/plasmaco-server.jar
```

Flyway creates the schema on first start (`backend/src/main/resources/db/migration`). When the database has no user,
the bootstrap administrator is created from the two `PLASMACO_BOOTSTRAP_ADMIN_*` variables (the password is never
logged). Remove those variables after the first start; that administrator then creates every other account from
the app (Administration → Users and roles).

### Configuration

| Variable | Default | Meaning |
|---|---|---|
| `PLASMACO_DB_URL` | `jdbc:postgresql://localhost:5432/plasmaco` | JDBC URL |
| `PLASMACO_DB_USER` / `PLASMACO_DB_PASSWORD` | `plasmaco` / empty | Database account (least privilege: owner of its own schema only) |
| `PLASMACO_DB_POOL_SIZE` | 10 | Connection pool size |
| `PLASMACO_JWT_SECRET` | none — **required** | Base64 of ≥ 32 random bytes; signs API tokens. The server refuses to start without it |
| `PLASMACO_TOKEN_TTL_HOURS` | 12 | Token lifetime |
| `PLASMACO_ALLOW_NEGATIVE_OVERRIDE` | `false` | Accept audited negative-stock overrides from Manager/Admin (see decisions.md) |
| `PLASMACO_MAX_LOGIN_FAILURES` / `PLASMACO_LOCK_MINUTES` | 5 / 15 | Account lock after repeated failed sign-ins |
| `PLASMACO_TIME_ZONE` | `Africa/Kigali` | Plant time zone (business dates, lock messages) |
| `PLASMACO_BOOTSTRAP_ADMIN_USERNAME` / `_PASSWORD` / `_NAME` | empty | First administrator, used only while the database has no user |
| `PORT` | 8080 | HTTP port behind the proxy |

### Operations

- **Backups:** `pg_dump -Fc plasmaco > plasmaco-$(date +%F).dump` daily, kept off the server. Restore with `pg_restore`.
  The `audit_log` and `record_history` tables are append-only (triggers refuse UPDATE, DELETE and TRUNCATE), so a
  restore is the only way to roll data back, and it is visible as such.
- **Rotating the token secret** signs everyone out; devices keep working offline and sign in again when online.
- **Logs** contain no passwords, tokens or request bodies; unexpected errors are logged by exception type only.

## Data model

Every synced entity is stored as its wire JSON (the `core/api` DTOs) in `records` (one row per entity, latest
version) with every accepted version kept in `record_history`. Each write takes the next value of `record_seq` while
holding a transaction-scoped advisory lock, so sequence order equals commit order and a device pulling "changes after
N" can never skip a change committed late. `applied_ops` remembers idempotency keys; `credentials` holds PBKDF2
password hashes (never synced); `audit_log` records sign-ins, failed sign-ins, account changes and every applied push.

## API (v1)

All endpoints are under `/api/v1`, JSON in and out (`application/json`, the shared `WireJson` settings). Every call
except sign-in and health needs `Authorization: Bearer <token>`. Errors are `{"code", "message", "details"}`
(`ApiError`); messages are written for the person using the app.

| Method and path | Who | Answer |
|---|---|---|
| `GET /health` | anyone | `{"status":"ok","api":"v1"}` |
| `POST /auth/login` `{username, password, deviceId}` | anyone | 200 `{token, expiresAt, user}`; 401 `invalid_credentials` (same message whether or not the user exists); 403 `account_locked` |
| `POST /sync/push` `{deviceId, operations[]}` | signed in | 200 `{results[]}`, one result per operation, in order |
| `GET /sync/pull?since=<cursor>&limit=<1..1000>` | signed in | 200 `{cursor, hasMore, changes[]}` |
| `GET /admin/users` | Administrator | 200 list of accounts; 403 otherwise |
| `POST /admin/users` `{user, password?}` | Administrator | 200 saved account (new version); 403 `users.forbidden`; 422 with the rule that failed |

Other statuses: 401 `unauthorized` (missing, invalid or expired token, or a deactivated account), 400 `bad_request`
(malformed JSON), 413 `too_large` (bodies over 8 MB), 500 `internal`.

### Push semantics

Each operation is `{id, idempotencyKey, entityType, entityId, kind, baseVersion, payload}` with `kind` one of `UPSERT`,
`DELETE`, `ACTION`. Operations are applied one by one, each in its own transaction, at most 200 per request. Results:

| Status | Meaning | What the app does |
|---|---|---|
| `APPLIED` | Accepted; `version` is the new server version | Marks the local record synced |
| `DUPLICATE` | This idempotency key was applied before (a retry) | Same as applied |
| `CONFLICT` | Someone else changed the record since `baseVersion`, or the record is locked by the workflow; `serverCopy` holds the server's version | Shows the conflict side by side in Settings → Sync; the user keeps the server copy or re-applies their change |
| `REJECTED` | A business rule refused it (`message` says which) | Shows it in Sync problems; nothing is applied |

The server re-checks every rule instead of trusting the device:

- **Permissions** from the account's current roles (re-read on every request, so a role change or deactivation applies at once).
- **Reports:** only editable statuses can change; status, authorship and version cannot be changed through an edit.
  A device's own queued edits chain (same device, no intervening change by anyone else); an edit based on an older
  version from another device is a conflict.
- **Workflow actions** (`report_action`): the transition must be allowed for the role and current status; a
  submission must match the stored content and pass submission validation; decisions need the current version and
  respect segregation of duties. Each submission stores an immutable `report_version` and each action an `approval`.
- **Stock movements** (`inventory_txn`) are validated against the server's ledger, not the device's: item kind,
  permission, positive quantities, production receipts only from approved reports and never above good output, and
  no negative balance unless the override is enabled and the actor holds the permission (with a note).
- **Master data** only from administrators; last accepted change wins.

### Pull semantics

`cursor` is opaque; send back the last one received (`since` omitted = from the beginning). Each change is
`{entityType, entityId, version, deleted, payload}` at the record's latest version. Visibility follows the reader's
role: master data and accounts for everyone; reports the reader may view (operators: their own) with their
approvals; stock movements only with stock visibility. Keep pulling while `hasMore` is true.

## Tests

- `tools/verify-server.sh` — sync, sign-in and store-contract tests without Spring (runs anywhere with a JDK and kotlinc).
- `tools/verify-server-sql.sh` — applies the migrations to a throwaway PostgreSQL 16 and exercises every SQL statement.
- `./gradlew :backend:test` — all of the above plus the store contract on PostgreSQL and the HTTP API end to end
  (Testcontainers; skipped when no Docker daemon is available).
