#!/usr/bin/env python3
"""
Static check of the Room layer without the Room annotation processor:

1. Builds the SQLite schema Room would generate from the @Entity data classes in Entities.kt (including @Embedded
   columns with their prefix) and creates it in an in-memory SQLite database, including declared indices.
2. Prepares every @Query in Daos.kt against that schema (EXPLAIN), which fails on unknown tables or columns and on
   SQL syntax errors — the same class of mistakes the Room processor reports at build time.
3. Checks every entity column type is either SQLite-native or has a @TypeConverter pair in Converters.kt.

It does not check DAO return-type mapping or @Relation wiring; the real Gradle build does.
Usage: python3 tools/typecheck/room_check.py
"""
import os
import re
import sqlite3
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
LOCAL = os.path.join(ROOT, "app/src/main/kotlin/rw/plasmaco/ops/data/local")
NATIVE = {"String": "TEXT", "Int": "INTEGER", "Long": "INTEGER", "Boolean": "INTEGER", "Double": "REAL", "Float": "REAL", "ByteArray": "BLOB"}


def balanced(text, start):
    """Returns the index just after the parenthesis matching text[start] == '('."""
    depth = 0
    for i in range(start, len(text)):
        if text[i] == "(":
            depth += 1
        elif text[i] == ")":
            depth -= 1
            if depth == 0:
                return i + 1
    raise ValueError("unbalanced parentheses")


def split_params(body):
    params, depth, cur = [], 0, ""
    for ch in body:
        if ch in "(<[":
            depth += 1
        elif ch in ")>]":
            depth -= 1
        if ch == "," and depth == 0:
            params.append(cur)
            cur = ""
        else:
            cur += ch
    if cur.strip():
        params.append(cur)
    return [p for p in (x.strip() for x in params) if p]


def strip_comments(src):
    src = re.sub(r"/\*.*?\*/", "", src, flags=re.S)
    return re.sub(r"//[^\n]*", "", src)


def classes(src):
    """name -> (annotation text before the class, list of (name, type, annotations))."""
    out = {}
    for m in re.finditer(r"((?:@\w+(?:\([^)]*(?:\([^)]*\)[^)]*)*\))?\s*)*)data class (\w+)\(", src):
        start = m.end() - 1
        end = balanced(src, start)
        fields = []
        for p in split_params(src[start + 1:end - 1]):
            pm = re.match(r"((?:@\w+(?:\([^)]*\))?\s*)*)(?:val|var)\s+(\w+)\s*:\s*([\w.<>?, ]+?)\s*(?:=.*)?$", p, re.S)
            if pm:
                fields.append((pm.group(2), pm.group(3).strip(), pm.group(1)))
        out[m.group(2)] = (m.group(1), fields)
    return out


def main():
    ents = strip_comments(open(os.path.join(LOCAL, "Entities.kt")).read())
    daos = strip_comments(open(os.path.join(LOCAL, "Daos.kt")).read())
    conv = open(os.path.join(LOCAL, "Converters.kt")).read()
    converted = set(re.findall(r"@TypeConverter fun \w+\(v: (\w+)\?\)", conv))
    cls = classes(ents)
    problems = []

    def columns(name, prefix=""):
        cols = []
        for fname, ftype, ann in cls[name][1]:
            if "@Relation" in ann:
                continue
            if "@Embedded" in ann:
                pm = re.search(r'prefix\s*=\s*"([^"]*)"', ann)
                cols += columns(ftype.rstrip("?"), prefix + (pm.group(1) if pm else ""))
                continue
            base = ftype.rstrip("?")
            if base in NATIVE:
                sql = NATIVE[base]
            elif base in converted:
                sql = "TEXT"
            else:
                problems.append(f"{name}.{fname}: type {ftype} has no SQLite mapping or @TypeConverter")
                sql = "TEXT"
            cols.append((prefix + fname, sql, "@PrimaryKey" in ann, not ftype.endswith("?")))
        return cols

    db = sqlite3.connect(":memory:")
    tables = 0
    for name, (ann, _) in cls.items():
        em = re.search(r'@Entity\(\s*tableName\s*=\s*"(\w+)"', ann)
        if not em:
            continue
        table = em.group(1)
        cols = columns(name)
        pk = [c[0] for c in cols if c[2]]
        ann_pk = re.search(r"primaryKeys\s*=\s*\[([^\]]*)\]", ann)
        if ann_pk:
            pk = re.findall(r'"(\w+)"', ann_pk.group(1))
        if not pk:
            problems.append(f"{name}: no primary key")
        defs = ", ".join(f'`{c}` {t}{" NOT NULL" if nn else ""}' for c, t, _, nn in cols)
        db.execute(f"CREATE TABLE `{table}` ({defs}, PRIMARY KEY({', '.join('`%s`' % p for p in pk)}))")
        tables += 1
        for im in re.finditer(r"Index\((?:value\s*=\s*)?\[([^\]]*)\](?:,\s*unique\s*=\s*(true|false))?\)|Index\(\"(\w+)\"\)", ann):
            idx_cols = re.findall(r'"(\w+)"', im.group(1)) if im.group(1) is not None else [im.group(3)]
            known = {c[0] for c in cols}
            for c in idx_cols:
                if c not in known:
                    problems.append(f"{name}: index column {c} does not exist")
            uniq = "UNIQUE " if im.group(2) == "true" else ""
            db.execute(f"CREATE {uniq}INDEX IF NOT EXISTS `idx_{table}_{'_'.join(idx_cols)}` ON `{table}` ({', '.join('`%s`' % c for c in idx_cols)})")

    queries = 0
    for qm in re.finditer(r'@Query\(\s*"((?:[^"\\]|\\.)*)"\s*\)', daos):
        sql = qm.group(1).replace('\\"', '"')
        line = daos[: qm.start()].count("\n") + 1
        try:
            db.execute("EXPLAIN " + sql, {p: None for p in re.findall(r":(\w+)", sql)})
            queries += 1
        except sqlite3.Error as e:
            problems.append(f"Daos.kt ~line {line}: {e}: {sql}")

    # Dynamic SQL in the sync layer: the same statements run against every synced table.
    sync = strip_comments(open(os.path.join(ROOT, "app/src/main/kotlin/rw/plasmaco/ops/data/sync/SyncRepository.kt")).read())
    tm = re.search(r"tableFor\s*=\s*mapOf\((.*?)\n\s*\)", sync, re.S)
    synced = re.findall(r'to "(\w+)"', tm.group(1)) if tm else []
    templates = re.findall(r'"((?:SELECT|UPDATE|DELETE)[^"]*\$table[^"]*)"', sync)
    for t in synced:
        for tpl in templates:
            sql = tpl.replace("$table", t)
            try:
                db.execute("EXPLAIN " + sql, [None] * sql.count("?"))
                queries += 1
            except sqlite3.Error as e:
                problems.append(f"SyncRepository dynamic SQL on {t}: {e}: {sql}")

    for p in problems:
        print("PROBLEM:", p)
    print(f"{tables} tables, {queries} queries prepared ({len(synced)} synced tables x {len(templates)} sync statements included), {len(problems)} problem(s)")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
