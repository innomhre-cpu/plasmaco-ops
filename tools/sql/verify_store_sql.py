"""Exercises the server's schema and StoreSql statements on a live PostgreSQL (started by tools/verify-server-sql.sh).

Statements are read from the Kotlin source (object StoreSql in JdbcServerStore.kt), so what is checked is exactly what
the server runs; JDBC '?' placeholders become $1..$n in PREPARE. Each check prints one line; any failure exits 1.
"""
import os
import re
import subprocess
import sys
import threading
import time

ROOT = sys.argv[1]
PSQL = os.path.join(os.environ.get("PG_BIN", "/usr/lib/postgresql/16/bin"), "psql")
DB = "plasmaco"
failures = []


def check(name, ok, detail=""):
    print(("  ok    " if ok else "  FAIL  ") + name + ("" if ok else f"  ({detail})"))
    if not ok:
        failures.append(name)


def run(script, db=DB, expect_error=False):
    """Runs a psql script; returns (rows by label, stderr). Rows are '|'-separated lines after each `\\echo @@label`."""
    p = subprocess.run([PSQL, "-X", "-q", "-At", "-F", "|", "-v", "ON_ERROR_STOP=1", "-d", db],
                       input=script, capture_output=True, text=True)
    if (p.returncode != 0) != expect_error:
        raise RuntimeError(f"psql exit {p.returncode}: {p.stderr.strip()}\n--- script ---\n{script}")
    out, label = {}, None
    for line in p.stdout.splitlines():
        if line.startswith("@@"):
            label = line[2:]
            out[label] = []
        elif label is not None:
            out[label].append(line.split("|"))
    return out, p.stderr


def lit(v):
    if v is None:
        return "NULL"
    if isinstance(v, bool):
        return "true" if v else "false"
    if isinstance(v, int):
        return str(v)
    return "'" + str(v).replace("'", "''") + "'"


def ex(name, *args):
    return f"EXECUTE {name}" + (f"({', '.join(lit(a) for a in args)})" if args else "") + ";\n"


# --- statements from the Kotlin source ---------------------------------------------------------------------------
src = open(os.path.join(ROOT, "backend/src/main/kotlin/rw/plasmaco/ops/server/web/JdbcServerStore.kt")).read()
body = src[src.index("object StoreSql {"):]
body = body[:body.index("\n}\n")]
sql = {}
for m in re.finditer(r'const val (\w+) = (?:"""(.*?)"""|"((?:[^"\\]|\\.)*)")', body, re.S):
    text = m.group(2) if m.group(2) is not None else m.group(3)
    sql[m.group(1)] = re.sub(r"\$(\w+)", lambda r: sql[r.group(1)], text)


def to_pg(text):
    n = 0

    def nxt(_):
        nonlocal n
        n += 1
        return f"${n}"
    return re.sub(r"\?", nxt, text)


PREPARE = "".join(f"PREPARE {k.lower()} AS {to_pg(v)};\n" for k, v in sql.items())
check(f"StoreSql parsed ({len(sql)} statements, no unresolved templates)", len(sql) >= 10 and not any(re.search(r"\$[A-Za-z_{]", v) for v in sql.values()))

# --- migrations ----------------------------------------------------------------------------------------------------
run(f"CREATE DATABASE {DB};", db="postgres")
mig_dir = os.path.join(ROOT, "backend/src/main/resources/db/migration")
migrations = sorted((f for f in os.listdir(mig_dir) if f.endswith(".sql")), key=lambda f: [int(x) for x in re.match(r"V([\d_]+)__", f).group(1).split("_")])
check("migration file names follow Flyway's V<version>__<description>.sql", all(re.match(r"V\d+(_\d+)*__\w+\.sql$", f) for f in migrations), str(migrations))
for f in migrations:
    run("BEGIN;\n" + open(os.path.join(mig_dir, f)).read() + "\nCOMMIT;\n")
check(f"migrations apply cleanly ({', '.join(migrations)})", True)
tables, _ = run("\\echo @@t\nSELECT string_agg(table_name, ',' ORDER BY table_name) FROM information_schema.tables WHERE table_schema = 'public';")
check("tables created", tables["t"][0][0] == "applied_ops,audit_log,credentials,record_history,records", tables["t"])

# --- every statement prepares -------------------------------------------------------------------------------------
run(PREPARE)
check(f"all {len(sql)} StoreSql statements prepare against the schema", True)

# --- the store's write path, as JdbcServerStore.write runs it ------------------------------------------------------


def write(t, i, version, payload, deleted, at, actor="u-admin", device="dev-1"):
    """One write transaction; returns the version the FOR UPDATE read saw (None if no row)."""
    out, _ = run(PREPARE + "BEGIN;\n" + ex("write_lock") + "\\echo @@cur\n" + ex("record_for_update", t, i)
                 + ex("upsert", t, i, version, payload, deleted, at, actor, device)
                 + ex("history", t, i, version, payload, deleted, at, actor, device) + "COMMIT;\n")
    rows = out["cur"]
    return int(rows[0][2]) if rows else None


seen = [write("REPORT", "r1", 1, '{"v":1}', False, "2026-10-13T06:00:00Z"),
        write("REPORT", "r1", 2, '{"v":2}', False, "2026-10-13T06:05:00Z"),
        write("TXN", "t1", 1, '{"q":5}', False, "2026-10-13T06:06:00Z"),
        write("REPORT", "r1", 3, None, True, "2026-10-13T06:10:00Z")]
check("FOR UPDATE read returns the current version before each write", seen == [None, 1, None, 2], seen)

out, _ = run(PREPARE + "\\echo @@all\n" + ex("changes", 0, 100) + "\\echo @@after\n"
             + "SELECT seq FROM records WHERE type = 'TXN' AND id = 't1' \\gset\n" + "EXECUTE changes(:seq, 100);\n"
             + "\\echo @@lim\n" + ex("changes", 0, 1) + "\\echo @@live\n" + ex("records", "REPORT") + "\\echo @@txn\n" + ex("records", "TXN")
             + "\\echo @@hist\nSELECT version, deleted, payload IS NULL FROM record_history WHERE id = 'r1' ORDER BY version;\n"
             + "\\echo @@utc\nSELECT to_char(updated_at AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI:SS') FROM records WHERE id = 't1';\n")
feed = [(r[0], r[1], int(r[2]), r[4], int(r[8])) for r in out["all"]]
check("change feed has one row per record, latest version, in sequence order",
      [(t, i, v, d) for t, i, v, d, _ in feed] == [("TXN", "t1", 1, "f"), ("REPORT", "r1", 3, "t")] and feed[0][4] < feed[1][4], feed)
check("change feed after a cursor returns only later changes", [(r[0], r[1]) for r in out["after"]] == [("REPORT", "r1")], out["after"])
check("change feed honours the page limit", len(out["lim"]) == 1, out["lim"])
check("live records exclude deleted ones", out["live"] == [] and [r[1] for r in out["txn"]] == ["t1"], (out["live"], out["txn"]))
check("history keeps every version (deleted version has no payload)", out["hist"] == [["1", "f", "f"], ["2", "f", "f"], ["3", "t", "t"]], out["hist"])
check("timestamps keep the instant whatever the session time zone", out["utc"] == [["2026-10-13 06:06:00"]], out["utc"])

# --- schema guarantees ---------------------------------------------------------------------------------------------
_, err = run(PREPARE + ex("upsert", "REPORT", "r2", 1, None, False, "2026-10-13T07:00:00Z", "u", "d"), expect_error=True)
check("a live record must have a payload", "check constraint" in err, err.strip())
_, err = run(PREPARE + ex("upsert", "REPORT", "r3", 0, "{}", False, "2026-10-13T07:00:00Z", "u", "d"), expect_error=True)
check("versions start at 1", "check constraint" in err, err.strip())
_, err = run("UPDATE record_history SET payload = 'x' WHERE id = 'r1';", expect_error=True)
check("record history cannot be changed", "record_history is append-only" in err, err.strip())
_, err = run("DELETE FROM record_history;", expect_error=True)
check("record history cannot be deleted", "record_history is append-only" in err, err.strip())
_, err = run("TRUNCATE record_history;", expect_error=True)
check("record history cannot be truncated", "record_history is append-only" in err, err.strip())

run(PREPARE + ex("record_applied", "op-1", "dev-1", "REPORT", "r1", 2, "2026-10-13T06:05:00Z", "u-1")
    + ex("record_applied", "op-1", "dev-2", "REPORT", "r1", 9, "2026-10-13T06:09:00Z", "u-2")
    + ex("record_applied", "op-2", "dev-1", "REPORT", "r1", None, "2026-10-13T06:09:00Z", "u-1"))
out, _ = run(PREPARE + "\\echo @@a\n" + ex("applied", "op-1") + "\\echo @@b\n" + ex("applied", "op-2") + "\\echo @@c\n" + ex("applied", "nope"))
check("a repeated idempotency key keeps the first application", [r[:2] + [r[4]] for r in out["a"]] == [["op-1", "dev-1", "2"]], out["a"])
check("applied operations may have no version", out["b"][0][4] == "" and out["c"] == [], out)

run(PREPARE + ex("save_credentials", "u-1", "Alain", "pbkdf2$a", 0, None)
    + ex("save_credentials", "u-1", "Alain", "pbkdf2$b", 3, "2026-10-13T08:15:00Z"))
out, _ = run(PREPARE + "\\echo @@n\n" + ex("credentials", "ALAIN") + "\\echo @@i\n" + ex("credentials_by_id", "u-1"))
check("credentials update in place and are found by username in any case",
      [r[:4] for r in out["n"]] == [["u-1", "Alain", "pbkdf2$b", "3"]] and out["i"][0][4] != "", out)
_, err = run(PREPARE + ex("save_credentials", "u-2", "alain", "pbkdf2$c", 0, None), expect_error=True)
check("two users cannot share a username that differs only in case", "credentials_username_idx" in err, err.strip())

audit = ("a-1", "2026-10-13T06:05:00Z", "u-1", "Alain", "dev-1", "REPORT", "r1", "SUBMIT", "Submitted v2")
run(PREPARE + ex("audit", *audit))
_, err = run("UPDATE audit_log SET summary = 'x';", expect_error=True)
check("audit log cannot be changed", "audit_log is append-only" in err, err.strip())
_, err = run("DELETE FROM audit_log;", expect_error=True)
check("audit log cannot be deleted", "audit_log is append-only" in err, err.strip())
_, err = run("TRUNCATE audit_log;", expect_error=True)
check("audit log cannot be truncated", "audit_log is append-only" in err, err.strip())
_, err = run(PREPARE + ex("audit", *audit), expect_error=True)
check("audit entries keep unique ids", "audit_log_pkey" in err, err.strip())

# --- sequence order equals commit order ----------------------------------------------------------------------------
# Writer A takes its sequence number, then is slow to commit; writer B starts later and commits quickly. A puller that
# reads between B's and A's commits must not move its cursor past A's change.


def concurrent(locked, tag):
    lock = ex("write_lock") if locked else ""
    a = (PREPARE + "BEGIN;\n" + lock + ex("upsert", "TXN", f"{tag}-a", 1, "{}", False, "2026-10-13T09:00:00Z", "u", "dev-a")
         + "SELECT pg_sleep(1.5);\nCOMMIT;\n")
    b = PREPARE + "BEGIN;\n" + lock + ex("upsert", "TXN", f"{tag}-b", 1, "{}", False, "2026-10-13T09:00:00Z", "u", "dev-b") + "COMMIT;\n"
    start, cursor = run("\\echo @@m\nSELECT coalesce(max(seq), 0) FROM records;")[0]["m"][0][0], {}
    ta = threading.Thread(target=run, args=(a,))
    tb = threading.Thread(target=run, args=(b,))
    ta.start()
    time.sleep(0.4)
    tb.start()
    time.sleep(0.5)
    # The puller, between B's (unlocked) commit and A's commit.
    seen1 = run(PREPARE + "\\echo @@c\n" + ex("changes", int(start), 100))[0]["c"]
    cursor = max([int(r[8]) for r in seen1], default=int(start))
    ta.join()
    tb.join()
    seen2 = run(PREPARE + "\\echo @@c\n" + ex("changes", cursor, 100))[0]["c"]
    return {r[1] for r in seen1} | {r[1] for r in seen2}


got = concurrent(locked=False, tag="nolock")
check("control: without the write lock a puller can skip a change", got == {"nolock-b"}, got)
got = concurrent(locked=True, tag="lock")
check("with the write lock every change reaches the puller", got == {"lock-a", "lock-b"}, got)

print(f"== {'FAILED: ' + str(len(failures)) + ' check(s)' if failures else 'OK'}")
sys.exit(1 if failures else 0)
