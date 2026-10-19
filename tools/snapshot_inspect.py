#!/usr/bin/env python3
"""Prints a readable summary of a dev snapshot zip (docs/DEV-SNAPSHOT.md), for whoever investigates a bug report.

    python tools/snapshot_inspect.py jb-snapshot-snap-20261018-143005-a3f9.zip
    python tools/snapshot_inspect.py snap-....zip --table event_participant --rows 20
    python tools/snapshot_inspect.py snap-....zip --json settings      # one file of the zip, raw

It reads the file only: no network, no database, nothing is extracted to disk. Works on the bot's own zip and on the combined one the website button
downloads (which adds site/page.json, site/client-log.json and site/storage.json).
"""
import argparse
import json
import re
import sys
import zipfile
from datetime import datetime, timezone

LOG_LINE = re.compile(r"^(\d{4}-\d{2}-\d{2}T[\d:.]+Z)\s+(\w+)\s+(\S+)\s+-\s+(.*)$")


def load(zf: zipfile.ZipFile, name: str):
    try:
        return json.loads(zf.read(name).decode("utf-8"))
    except KeyError:
        return None


def parse_time(text) -> datetime | None:
    if not isinstance(text, str):
        return None
    try:
        return datetime.fromisoformat(text.replace("Z", "+00:00")).astimezone(timezone.utc)
    except ValueError:
        return None


def clip(text: str, width: int = 140) -> str:
    text = " ".join(str(text).split())
    return text if len(text) <= width else text[: width - 1] + "…"


def log_entries(zf: zipfile.ZipFile) -> list[tuple[datetime, str, str]]:
    """(time, level, text) per log entry; stack lines stay with their entry."""
    if "logs.txt" not in zf.namelist():
        return []
    entries: list[list] = []
    for line in zf.read("logs.txt").decode("utf-8", "replace").splitlines():
        match = LOG_LINE.match(line)
        if match and parse_time(match.group(1)):
            entries.append([parse_time(match.group(1)), match.group(2), f"{match.group(3)} - {match.group(4)}"])
        elif entries and line.strip():
            entries[-1][2] += " | " + line.strip()
    return [(t, level, text) for t, level, text in entries]


def section(title: str) -> None:
    print(f"\n== {title} ==")


def summarise(zf: zipfile.ZipFile, args) -> None:
    manifest = load(zf, "manifest.json") or {}
    if manifest.get("format") != "jonnybot-dev-snapshot":
        sys.exit("That does not look like a JonnyBot dev snapshot (manifest.json missing or the wrong format).")
    taken = parse_time(manifest.get("createdAtUtc"))
    requested = manifest.get("requestedBy") or {}

    print(f"Snapshot {manifest.get('id')}  (format {manifest.get('formatVersion')})")
    print(f"  taken      {manifest.get('createdAtUtc')} UTC by {requested.get('name')} ({requested.get('id')})")
    print(f"  server     {manifest.get('guildName')} ({manifest.get('guildId')})")
    print(f"  focus      {manifest.get('focus') or '(none: whole server)'}")
    print(f"  environment {manifest.get('environment')}   bot {manifest.get('botCommit')}   site {manifest.get('siteCommit')}")

    env = load(zf, "environment.json") or {}
    if env:
        section("Bot")
        gw = env.get("gateway") or {}
        print(f"  started {env.get('startedAtUtc')} (up {env.get('uptimeSeconds')} s), gateway {gw.get('status')} ping {gw.get('pingMs')} ms, {gw.get('servers')} servers")
        print(f"  database time {env.get('databaseTimeUtc')}, memory {env.get('memoryUsedMb')}/{env.get('memoryMaxMb')} MB, auto-poll {env.get('pollAutoEnabled')}")

    page = load(zf, "site/page.json")
    if page:
        section("Website (from the button)")
        print(f"  {page.get('url')}  route {page.get('route')}  face {page.get('face')}  role {page.get('role')}")
        print(f"  browser time {page.get('browserTime')} ({page.get('browserTimezone')}), viewport {page.get('viewport')}, guild {page.get('guildInContext')}")

    pacer = (load(zf, "polling.json") or {}).get("pacer")
    if pacer:
        section("RuneMetrics pacer")
        print("  " + ", ".join(f"{k} {pacer[k]}" for k in ("queued", "inFlight", "slowdownMultiplier", "rateLimited", "gaveUp", "failed") if k in pacer))

    guild = load(zf, "guild.json") or {}
    stored = guild.get("featuresWithSettingsStored")
    if stored is not None:
        section("Server settings")
        print(f"  features with settings stored ({len(stored)}): {', '.join(stored) if stored else 'none'}")
        print("  full settings: --json guild.json")

    logs = log_entries(zf)
    section(f"Warnings and errors from the 30 minutes before ({len(logs)})")
    counts: dict[str, int] = {}
    for _, level, _ in logs:
        counts[level] = counts.get(level, 0) + 1
    print("  " + (", ".join(f"{level} {n}" for level, n in sorted(counts.items())) or "none"))

    ring = (load(zf, "site/client-log.json") or {}).get("entries", [])
    section(f"Timeline (log + browser errors, newest last; snapshot at {manifest.get('createdAtUtc')})")
    events: list[tuple[datetime, str, str]] = [(t, f"bot {level}", text) for t, level, text in logs]
    events += [(t, f"browser {e.get('kind')}", e.get("message", "")) for e in ring if (t := parse_time(e.get("at")))]
    for job in (load(zf, "polling.json") or {}).get("jobs", []):
        if (t := parse_time(job.get("lastRunUtc"))):
            events.append((t, "poll job", f"{job.get('name')} last ran"))
    events.sort(key=lambda e: e[0])
    shown = events[-args.timeline :]
    if len(events) > len(shown):
        print(f"  … {len(events) - len(shown)} earlier entries (use --timeline N)")
    for t, kind, text in shown:
        delta = f"{(t - taken).total_seconds():+.0f}s" if taken else ""
        print(f"  {t.strftime('%H:%M:%S')} {delta:>7}  {kind:<14} {clip(text)}")
    if not shown:
        print("  nothing recorded")

    tables = sorted(n for n in zf.namelist() if n.startswith("db/") and n.endswith(".json"))
    section(f"Focused rows ({len(tables)} tables)")
    for name in tables:
        data = load(zf, name)
        count = len(data) if isinstance(data, list) else 1
        print(f"  {name[3:-5]:<34} {count} row{'s' if count != 1 else ''}")
    if tables:
        print("  rows: --table <name> [--rows N]")


def show_table(zf: zipfile.ZipFile, name: str, limit: int) -> None:
    data = load(zf, f"db/{name}.json")
    if data is None:
        sys.exit(f"No table {name!r} in this snapshot. Tables: " + ", ".join(n[3:-5] for n in zf.namelist() if n.startswith("db/")))
    rows = data if isinstance(data, list) else [data]
    print(f"{name}: {len(rows)} rows (showing {min(limit, len(rows))})")
    for row in rows[:limit]:
        print("  " + clip(json.dumps(row, ensure_ascii=False), 220))


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("zip", help="a snapshot zip (bot or combined)")
    parser.add_argument("--table", help="print the rows of one db/<table>.json")
    parser.add_argument("--rows", type=int, default=10, help="rows to show with --table (default 10)")
    parser.add_argument("--json", metavar="FILE", help="print one file of the zip as it is (e.g. guild.json, site/client-log.json, db/event.json)")
    parser.add_argument("--timeline", type=int, default=40, help="timeline entries to show (default 40)")
    args = parser.parse_args()
    try:
        zf = zipfile.ZipFile(args.zip)
    except (OSError, zipfile.BadZipFile) as error:
        sys.exit(f"Cannot read {args.zip}: {error}")
    with zf:
        if args.json:
            if args.json not in zf.namelist():
                sys.exit(f"{args.json} is not in the zip. Files: " + ", ".join(zf.namelist()))
            sys.stdout.write(zf.read(args.json).decode("utf-8", "replace"))
        elif args.table:
            show_table(zf, args.table, args.rows)
        else:
            summarise(zf, args)


if __name__ == "__main__":
    main()
