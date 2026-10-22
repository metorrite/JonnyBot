#!/usr/bin/env python3
"""Read-only SQL against the bot's database (schema `younglings`), for investigating a bug report. It can only read.

    python tools/db_query.py "SELECT id, state FROM event WHERE id = 12"
    python tools/db_query.py "SELECT count(*) FROM event_participant" --format json
    python tools/db_query.py --tables                       # table names in the schema

Local dev database only. The URL comes from DATABASE_URL, or from the git-ignored "JonnyBot Dev" run configuration (never printed).

Three independent guards, so one mistake never writes:
  1. the statement check below refuses anything but a single SELECT / WITH ... SELECT / SHOW / EXPLAIN (no ANALYZE), and refuses writing keywords,
     locks, COPY, sequence and admin functions, comments hiding a second statement, and dollar quoting;
  2. the session is opened with default_transaction_read_only=on;
  3. the query runs inside BEGIN READ ONLY (never committed), so the server itself rejects a write that slipped past the check (and nothing is ever committed: the connection just closes).

The live database: `--live` only prints that Drake must approve the use and exits. It connects only when you ALSO pass --drake-approved AND
LIVE_DATABASE_URL is set in the environment (the credentials are not stored anywhere in this repository). Still read-only. See docs/BUG-REPORT.md.
"""
import argparse
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
RUN_CONFIGS = [REPO / ".idea" / "runConfigurations" / "JonnyBot_Dev.xml", REPO.parent / "JonnyBot" / ".idea" / "runConfigurations" / "JonnyBot_Dev.xml"]
SCHEMA = "younglings"
TIMEOUT_SECONDS = 60

ALLOWED_START = ("select", "with", "show", "explain", "values", "table")
# Words that never belong in a read. Matched on whole words after strings and comments are removed.
FORBIDDEN_WORDS = {
    "insert", "update", "delete", "merge", "drop", "alter", "create", "truncate", "grant", "revoke", "copy", "call", "do", "vacuum", "analyze",
    "analyse", "reindex", "cluster", "refresh", "lock", "listen", "notify", "unlisten", "prepare", "execute", "set", "reset", "begin", "commit",
    "rollback", "savepoint", "abort", "checkpoint", "discard", "load", "import",
    "into",  # SELECT ... INTO creates a table
}
LOCKING_CLAUSE = re.compile(r"\bfor\s+(no\s+key\s+update|key\s+share|update|share)\b", re.I)  # FOR UPDATE / FOR SHARE take row locks
FORBIDDEN_FUNCTIONS = re.compile(
    r"\b(nextval|setval|set_config|pg_sleep\w*|pg_terminate_backend|pg_cancel_backend|pg_reload_conf|pg_rotate_logfile|pg_read_\w+|pg_ls_\w+|pg_stat_file|"
    r"pg_advisory\w*|lo_\w+|dblink\w*|pg_create_\w+|pg_drop_\w+|pg_switch_\w+|pg_logical_\w+|txid_\w+|current_setting\s*\(\s*'(?:session_replication|role))",
    re.I,
)


class Refused(Exception):
    """The statement is not a plain read."""


def strip_literals(sql: str) -> str:
    """The statement with string literals, quoted identifiers and comments blanked, so keywords inside them neither count nor hide anything."""
    out, i, n = [], 0, len(sql)
    while i < n:
        c = sql[i]
        if sql.startswith("--", i):
            j = sql.find("\n", i)
            i = n if j < 0 else j
            out.append(" ")
        elif sql.startswith("/*", i):
            j = sql.find("*/", i + 2)
            if j < 0:
                raise Refused("unterminated comment")
            i = j + 2
            out.append(" ")
        elif c in ("'", '"'):
            j = i + 1
            while j < n:
                if sql[j] == c:
                    if j + 1 < n and sql[j + 1] == c:  # doubled quote is an escaped quote
                        j += 2
                        continue
                    break
                j += 1
            else:
                raise Refused("unterminated quote")
            i = j + 1
            out.append(" " + ("q" if c == '"' else "") + " ")  # a quoted identifier stays a harmless word
        else:
            out.append(c)
            i += 1
    return "".join(out)


def check_statement(sql: str) -> str:
    """Returns the statement to run, or raises Refused. Guard 1 of 3."""
    if not isinstance(sql, str) or not sql.strip():
        raise Refused("empty statement")
    if "\x00" in sql:
        raise Refused("NUL byte")
    if "$$" in sql or re.search(r"\$[A-Za-z_]*\$", sql):
        raise Refused("dollar-quoted strings are not allowed")
    if re.search(r"\\", strip_literals(sql)):
        raise Refused("backslash commands are not allowed")
    bare = strip_literals(sql).strip().rstrip(";").strip()
    if ";" in bare:
        raise Refused("only one statement at a time")
    words = re.findall(r"[a-z_][a-z0-9_]*", bare.lower())
    if not words or words[0] not in ALLOWED_START:
        raise Refused(f"only SELECT, WITH, SHOW, EXPLAIN, VALUES or TABLE are allowed (statement starts with {words[0] if words else 'nothing'!r})")
    if words[0] == "explain" and any(w in ("analyze", "analyse") for w in words[:6]):
        raise Refused("EXPLAIN ANALYZE would run the statement")
    for word in words:
        if word in FORBIDDEN_WORDS:
            raise Refused(f"{word.upper()} is not allowed in a read-only query")
    if LOCKING_CLAUSE.search(bare):
        raise Refused("FOR UPDATE / FOR SHARE take locks")
    if FORBIDDEN_FUNCTIONS.search(bare):
        raise Refused("that function changes or inspects server state")
    return sql.strip().rstrip(";").strip()


def run_config_value(name: str) -> str | None:
    for path in RUN_CONFIGS:
        if path.exists():
            match = re.search(r'name="%s"\s+value="([^"]*)"' % re.escape(name), path.read_text(encoding="utf-8"))
            if match:
                return match.group(1).replace("&amp;", "&")
    return None


def local_url() -> str:
    url = os.environ.get("DATABASE_URL") or run_config_value("DATABASE_URL")
    if not url:
        sys.exit("No local DATABASE_URL: set it, or create the JonnyBot Dev run configuration (docs/DEV-SETUP.md).")
    return url


def psql_binary() -> str:
    found = shutil.which("psql") or next(iter(sorted(Path("C:/Program Files/PostgreSQL").glob("*/bin/psql.exe"), reverse=True)), None)
    if not found:
        sys.exit("psql was not found on PATH (it ships with PostgreSQL).")
    return str(found)


def execute(url: str, sql: str, as_json: bool, checked: bool = True) -> subprocess.CompletedProcess:
    """Runs one statement through psql inside a read-only transaction. `checked=False` skips guard 1 and exists only so the tests can prove guards 2 and 3 on their own."""
    statement = check_statement(sql) if checked else sql.strip().rstrip(";")
    script = "BEGIN READ ONLY;\n"
    script += f"SELECT coalesce(json_agg(t), '[]'::json) FROM (\n{statement}\n) t;\n" if as_json else f"{statement};\n"
    env = dict(os.environ, PGOPTIONS=f"-c default_transaction_read_only=on -c search_path={SCHEMA},public -c statement_timeout=30000 -c idle_in_transaction_session_timeout=30000")
    args = [psql_binary(), "--no-psqlrc", "--set", "ON_ERROR_STOP=1", "--quiet"] + (["--tuples-only", "--no-align"] if as_json else []) + [url]  # options must come before the URL
    result = subprocess.run(args, input=script, capture_output=True, text=True, env=env, timeout=TIMEOUT_SECONDS)
    # psql prints the command tag of BEGIN first; it is the only line that is not the answer
    result.stdout = re.sub(r"\A\s*BEGIN\r?\n", "", result.stdout)
    return result


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("sql", nargs="?", help="one read-only statement")
    parser.add_argument("--format", choices=["table", "json"], default="table")
    parser.add_argument("--tables", action="store_true", help="list the tables in the younglings schema")
    parser.add_argument("--live", action="store_true", help="the live database: prints the approval reminder and stops unless --drake-approved is also given")
    parser.add_argument("--drake-approved", action="store_true", help="only with --live, and only after Drake has said yes in this thread")
    args = parser.parse_args()

    if args.live:
        print("LIVE DATABASE: Drake must approve before this is used. Ask him in this thread, say which question the query answers, then re-run with\n"
              "  --live --drake-approved   (and LIVE_DATABASE_URL set in your environment; it is not stored in the repository).\n"
              "Read-only still applies. Prefer the snapshot (tools/snapshot_inspect.py) or the local dev database first.", file=sys.stderr)
        if not args.drake_approved:
            sys.exit(3)
        url = os.environ.get("LIVE_DATABASE_URL")
        if not url:
            sys.exit("LIVE_DATABASE_URL is not set, so nothing was connected.")
    else:
        url = local_url()

    sql = "SELECT table_name FROM information_schema.tables WHERE table_schema = 'younglings' ORDER BY table_name" if args.tables else args.sql
    if not sql:
        parser.error("give a statement, or --tables")
    try:
        result = execute(url, sql, args.format == "json")
    except Refused as why:
        sys.exit(f"Refused: {why}. This tool only reads.")
    sys.stdout.write(result.stdout)
    if result.returncode != 0:
        # psql echoes the SQL and server messages, never the URL; strip anything URL-shaped to be safe
        sys.exit(re.sub(r"postgres(?:ql)?://\S+", "[url]", result.stderr).strip() or f"psql exited {result.returncode}")


if __name__ == "__main__":
    main()
