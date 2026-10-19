#!/usr/bin/env python3
"""Tests for tools/db_query.py and tools/snapshot_inspect.py.   python -m unittest tools/test_investigation_kit.py -v   (from the repository root)

The write-refusal tests are the proof that the query helper cannot write: the statement check refuses every write form below, and, with that check
switched off on purpose, the local dev database itself refuses them because the transaction is read-only. The database tests are skipped when
there is no local dev database; they never create or change anything (every statement is rejected before it does anything).
"""
import io
import json
import sys
import unittest
import zipfile
from contextlib import redirect_stdout
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import db_query  # noqa: E402
import snapshot_inspect  # noqa: E402

WRITES = [
    "INSERT INTO event (id) VALUES (1)",
    "UPDATE event SET name = 'x'",
    "DELETE FROM event",
    "DROP TABLE event",
    "TRUNCATE event",
    "ALTER TABLE event ADD COLUMN x int",
    "CREATE TABLE t (x int)",
    "GRANT ALL ON event TO public",
    "COPY event TO PROGRAM 'echo hi'",
    "CALL some_proc()",
    "DO $$ BEGIN DELETE FROM event; END $$",
    "SELECT 1; DELETE FROM event",
    "SELECT 1;DROP TABLE event",
    "WITH d AS (DELETE FROM event RETURNING *) SELECT * FROM d",
    "WITH i AS (INSERT INTO event (id) VALUES (1) RETURNING *) SELECT * FROM i",
    "SELECT * INTO newtable FROM event",
    "SELECT * FROM event FOR UPDATE",
    "SELECT nextval('event_id_seq')",
    "SELECT setval('event_id_seq', 1)",
    "SELECT set_config('default_transaction_read_only', 'off', false)",
    "SELECT pg_terminate_backend(1)",
    "SELECT pg_read_file('/etc/passwd')",
    "EXPLAIN ANALYZE DELETE FROM event",
    "SET default_transaction_read_only = off",
    "BEGIN; DELETE FROM event; COMMIT",
    "COMMIT",
    "ROLLBACK",
    "VACUUM event",
    "LOCK TABLE event",
    "  \n delete from event",
    "SELECT 1 /* ; */ ; DELETE FROM event",
    "/* hide */ DELETE FROM event",
    "-- note\nDELETE FROM event",
    "",
    "   ",
]

READS = [
    "SELECT * FROM event WHERE id = 12",
    "select count(*) from event_participant",
    "WITH x AS (SELECT 1 AS n) SELECT * FROM x",
    "SELECT 'delete from event; drop table event' AS harmless",
    'SELECT "update" FROM t',
    "SELECT * FROM event -- trailing note",
    "SELECT * FROM event;",
    "EXPLAIN SELECT * FROM event",
    "SHOW search_path",
]


class StatementCheck(unittest.TestCase):
    def test_every_write_is_refused(self):
        for sql in WRITES:
            with self.subTest(sql=sql):
                with self.assertRaises(db_query.Refused):
                    db_query.check_statement(sql)

    def test_plain_reads_pass(self):
        for sql in READS:
            with self.subTest(sql=sql):
                self.assertTrue(db_query.check_statement(sql))

    def test_live_prints_a_reminder_and_does_not_connect(self):
        # no network or database is touched on this path: it exits before a URL is even looked up
        import os
        import subprocess

        env = {k: v for k, v in os.environ.items() if k != "LIVE_DATABASE_URL"}
        out = subprocess.run([sys.executable, str(Path(db_query.__file__)), "--live", "SELECT 1"], capture_output=True, text=True, env=env)
        self.assertEqual(out.returncode, 3)
        self.assertIn("Drake must approve", out.stderr)
        self.assertEqual(out.stdout, "")
        # the second flag alone still does not connect without the live URL being provided by the environment
        out = subprocess.run([sys.executable, str(Path(db_query.__file__)), "--live", "--drake-approved", "SELECT 1"], capture_output=True, text=True, env=env)
        self.assertNotEqual(out.returncode, 0)
        self.assertIn("LIVE_DATABASE_URL is not set", out.stderr)


def local_database():
    try:
        url = db_query.local_url()
        done = db_query.execute(url, "SELECT 1", False)
        return url if done.returncode == 0 else None
    except (SystemExit, Exception):
        return None


URL = local_database()


@unittest.skipUnless(URL, "no local dev database")
class ServerSideReadOnly(unittest.TestCase):
    """Guard 1 off: the database's own read-only transaction must still stop each write."""

    def test_database_rejects_writes_even_without_the_statement_check(self):
        for sql in ["DELETE FROM event WHERE false", "CREATE TEMP TABLE kit_probe (x int)", "INSERT INTO admin_audit SELECT * FROM admin_audit WHERE false",
                    "UPDATE event SET id = id WHERE false", "TRUNCATE kit_probe_missing"]:
            with self.subTest(sql=sql):
                result = db_query.execute(URL, sql, False, checked=False)
                self.assertNotEqual(result.returncode, 0, result.stdout)
                self.assertIn("read-only transaction", result.stderr)

    def test_a_read_works(self):
        result = db_query.execute(URL, "SELECT 1 AS one", True)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(json.loads(result.stdout), [{"one": 1}])


def fake_zip() -> zipfile.ZipFile:
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w") as z:
        z.writestr("manifest.json", json.dumps({"format": "jonnybot-dev-snapshot", "formatVersion": 1, "id": "snap-20261019-030000-abcd", "createdAtUtc": "2026-10-19T03:00:00Z",
                                                 "guildId": "1", "guildName": "Test", "requestedBy": {"id": "2", "name": "Drake"}, "focus": "event:12", "environment": "dev",
                                                 "botCommit": "abc", "siteCommit": None}))
        z.writestr("logs.txt", "2026-10-19T02:59:30Z ERROR com.x.Y - boom\n  java.lang.Oops: bad\n    at com.x.Y.run(Y.java:1)\n2026-10-19T02:59:50Z WARN com.x.Z - careful\n")
        z.writestr("site/client-log.json", json.dumps({"entries": [{"at": "2026-10-19T02:59:55Z", "kind": "fetch", "message": "POST /x -> 500"}]}))
        z.writestr("db/event.json", json.dumps([{"id": "12", "name": "Bingo"}]))
        z.writestr("db/event_participant.json", json.dumps([{"event_id": "12", "user_id": "190000000000000001"}]))
    return zipfile.ZipFile(io.BytesIO(buffer.getvalue()))


class Inspect(unittest.TestCase):
    def test_summary_has_timeline_errors_and_rows(self):
        class Args:
            timeline = 40

        out = io.StringIO()
        with redirect_stdout(out):
            snapshot_inspect.summarise(fake_zip(), Args)
        text = out.getvalue()
        self.assertIn("snap-20261019-030000-abcd", text)
        self.assertIn("ERROR 1", text)
        self.assertIn("boom | java.lang.Oops: bad", text)
        self.assertIn("browser fetch", text)
        self.assertIn("event_participant", text)
        self.assertLess(text.index("boom"), text.index("POST /x -> 500"))

    def test_table_keeps_19_digit_ids_as_text(self):
        out = io.StringIO()
        with redirect_stdout(out):
            snapshot_inspect.show_table(fake_zip(), "event_participant", 5)
        self.assertIn('"190000000000000001"', out.getvalue())


if __name__ == "__main__":
    unittest.main()
