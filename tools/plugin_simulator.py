#!/usr/bin/env python3
"""
A stand-in for the RuneScape clan plugin: posts realistic batches of events to JonnyBot's plugin API (schema v1) so the whole pipeline
can be built and tested before Jagex's plugin API exists.

Setup (one time):
  1. Link a RuneScape name to your Discord account with /rs.
  2. On your JonnyBot profile, make a plugin token and switch on the categories you want to test (nothing is stored for a category that
     is off, which is also worth testing).
  3. Run:  python tools/plugin_simulator.py --token jbp_... --rsn "Your Name"

Examples:
  python tools/plugin_simulator.py --token jbp_... --rsn "Jonny Young" --whoami
  python tools/plugin_simulator.py --token jbp_... --rsn "Jonny Young" --count 40 --types DROP,KILL
  python tools/plugin_simulator.py --token jbp_... --rsn "Jonny Young" --count 5 --dry-run        # print the batch, send nothing
  python tools/plugin_simulator.py --token jbp_... --rsn "Jonny Young" --count 5 --seed 7 --resend # same batch twice: proves duplicates are ignored

--base-url defaults to the local website (http://localhost:3000). Point it at a deployed site, or straight at the bot's internal API
(http://127.0.0.1:8081 with --direct and --secret) when the website route isn't running.
Uses only the Python standard library.
"""
import argparse
import json
import random
import sys
import uuid
import urllib.error
import urllib.request
from datetime import datetime, timedelta, timezone

BOSSES = [("Telos, the Warden", 4800), ("Araxxor", 3000), ("Vorago", 1500), ("Nex: Angel of Death", 700), ("Rasial, the First Necromancer", 5200),
          ("The Magister", 900), ("Kerapac, the Bound", 300)]
DROPS = [("Crystal Telos armour piece", "NPC", "Telos, the Warden", 120_000_000), ("Araxxi's web", "NPC", "Araxxor", 38_000_000), ("Vorago's pet", "NPC", "Vorago", 0),
         ("Divine charm", "CHEST", "Reward chest", 2_500), ("Spirit seed", "ACTIVITY", "Seren spirit", 450_000), ("Coins", "NPC", "Nex: Angel of Death", 1_200_000)]
SKILLS = ["Attack", "Mining", "Divination", "Slayer", "Runecrafting", "Invention", "Archaeology"]
QUESTS = ["Dragon Slayer II", "Desperate Times", "Sliske's Endgame", "Elite Dungeon: Dragonkin Laboratory"]
ACHIEVEMENTS = ["Master of the Warden", "Slayer Veteran", "Cape of Accomplishment"]
LOG_ITEMS = ["Pet rock", "Dragon rider lance", "Ancient icon"]
ALL_TYPES = ["DROP", "KILL", "LEVEL_UP", "QUEST_COMPLETE", "ACHIEVEMENT", "COLLECTION_LOG", "PRESENCE"]


def make_event(rng, kind, at):
    base = {"id": uuid.UUID(int=rng.getrandbits(128)).hex, "type": kind, "at": at.strftime("%Y-%m-%dT%H:%M:%SZ")}
    if kind == "DROP":
        name, source_kind, source, value = rng.choice(DROPS)
        base["source"] = {"kind": source_kind, "name": source}
        base["data"] = {"itemName": name, "quantity": rng.choice([1, 1, 1, 2, 5]), "value": value}
    elif kind == "KILL":
        boss, _ = rng.choice(BOSSES)
        base["data"] = {"bossName": boss, "killCount": rng.randint(1, 1500), "durationSeconds": rng.randint(60, 900)}
    elif kind == "LEVEL_UP":
        base["data"] = {"skill": rng.choice(SKILLS), "level": rng.randint(2, 120)}
    elif kind == "QUEST_COMPLETE":
        base["data"] = {"quest": rng.choice(QUESTS)}
    elif kind == "ACHIEVEMENT":
        base["data"] = {"name": rng.choice(ACHIEVEMENTS), "kind": "combat"}
    elif kind == "COLLECTION_LOG":
        base["data"] = {"itemName": rng.choice(LOG_ITEMS)}
    else:
        state = rng.choice(["ONLINE", "ACTIVITY", "OFFLINE"])
        base["data"] = {"state": state}
        if state == "ACTIVITY":
            base["data"]["activity"] = rng.choice(["Fighting Telos", "Mining", "Training Slayer"])
    return base


def build_batch(args, rng):
    kinds = [t.strip().upper() for t in args.types.split(",")] if args.types else ALL_TYPES
    unknown = [k for k in kinds if k not in ALL_TYPES]
    if unknown:
        sys.exit("Unknown event type(s): " + ", ".join(unknown) + ". Choose from " + ", ".join(ALL_TYPES))
    now = datetime.now(timezone.utc)
    events = []
    for i in range(args.count):
        at = now - timedelta(seconds=rng.randint(0, 3600 * args.hours))
        events.append(make_event(rng, kinds[i % len(kinds)] if args.round_robin else rng.choice(kinds), at))
    return {"schema": 1, "client": {"name": "jonnybot-plugin-simulator", "version": "0.1.0"}, "player": {"rsn": args.rsn}, "events": events}


def request(url, token, secret, method="GET", body=None):
    headers = {"Content-Type": "application/json", "User-Agent": "jonnybot-plugin-simulator/0.1"}
    if secret:
        headers["X-Internal-Secret"] = secret
        headers["X-Plugin-Token"] = token
    else:
        headers["Authorization"] = "Bearer " + token
    data = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(url, data=data, method=method, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=20) as response:
            return response.status, json.loads(response.read().decode("utf-8") or "{}")
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", "replace")
        try:
            return e.code, json.loads(raw)
        except ValueError:
            return e.code, {"error": raw[:300]}
    except urllib.error.URLError as e:
        sys.exit("Couldn't reach %s: %s" % (url, e.reason))


def main():
    parser = argparse.ArgumentParser(description="Simulate the JonnyBot clan plugin.")
    parser.add_argument("--token", required=True, help="your plugin token (jbp_...)")
    parser.add_argument("--rsn", help="a RuneScape name linked to your Discord account in JonnyBot")
    parser.add_argument("--base-url", default="http://localhost:3000", help="the website (default) or, with --direct, the bot's internal API")
    parser.add_argument("--direct", action="store_true", help="talk to the bot's internal API instead of the website (needs --secret)")
    parser.add_argument("--secret", help="the bot's INTERNAL_API_SECRET (only with --direct)")
    parser.add_argument("--count", type=int, default=10, help="events per batch (1 to 100)")
    parser.add_argument("--types", help="comma-separated event types (default: all)")
    parser.add_argument("--hours", type=int, default=2, help="spread events over the last N hours")
    parser.add_argument("--seed", type=int, help="make the batch repeatable")
    parser.add_argument("--round-robin", action="store_true", help="cycle through the types instead of picking at random")
    parser.add_argument("--dry-run", action="store_true", help="print the batch and send nothing")
    parser.add_argument("--resend", action="store_true", help="send the same batch twice to show duplicates are ignored")
    parser.add_argument("--whoami", action="store_true", help="only ask what this token may report for")
    args = parser.parse_args()

    if args.direct and not args.secret:
        sys.exit("--direct needs --secret")
    base = args.base_url.rstrip("/")
    ingest = base + ("/internal/plugin/ingest" if args.direct else "/api/ingest/v1/events")
    whoami = base + ("/internal/plugin/whoami" if args.direct else "/api/ingest/v1/whoami")

    if args.whoami:
        status, body = request(whoami, args.token, args.secret)
        print(status, json.dumps(body, indent=2))
        return
    if not args.rsn:
        sys.exit("--rsn is required")
    if not 1 <= args.count <= 100:
        sys.exit("--count must be 1 to 100")

    rng = random.Random(args.seed)
    batch = build_batch(args, rng)
    if args.dry_run:
        print(json.dumps(batch, indent=2))
        return
    for attempt in range(2 if args.resend else 1):
        status, body = request(ingest, args.token, args.secret, "POST", batch)
        print("send %d: HTTP %d" % (attempt + 1, status))
        print(json.dumps(body, indent=2))
    sys.exit(0 if status == 200 else 1)


if __name__ == "__main__":
    main()
