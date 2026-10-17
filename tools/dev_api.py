#!/usr/bin/env python3
"""Calls the running dev bot's internal admin API (the routes the website's dashboard uses), for checking things without the browser.

    python tools/dev_api.py GET events/12/space --actor <your discord user id>
    python tools/dev_api.py POST events/12/space/preview '{"enabled": true}' --actor <your discord user id>

The secret and the server id are read from the "JonnyBot Dev" run configuration in .idea/workspace.xml (INTERNAL_API_SECRET and GUILD_ID); they are
never printed. --actor is the Discord user id of a dashboard admin (or set DEV_ACTOR_ID). The bot must be running (the API listens on port 8081).

Remember that the dev bot sits in the real clan server: a GET (or a preview) only reads, but a POST/PUT/DELETE changes real things.
"""
import argparse
import json
import os
import re
import sys
import urllib.error
import urllib.request
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
# a git worktree has no .idea folder of its own: fall back to the main checkout next to it
WORKSPACE = next((p for p in (REPO / ".idea" / "workspace.xml", REPO.parent / "JonnyBot" / ".idea" / "workspace.xml") if p.exists()), REPO / ".idea" / "workspace.xml")


def run_config_value(name: str) -> str:
    text = WORKSPACE.read_text(encoding="utf-8")
    match = re.search(r'name="%s"\s+value="([^"]*)"' % re.escape(name), text)
    if not match:
        sys.exit(f"{name} isn't set in {WORKSPACE} (is the JonnyBot Dev run configuration there?)")
    return match.group(1).replace("&amp;", "&")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("method", choices=["GET", "POST", "PUT", "DELETE"])
    parser.add_argument("route", help="the part after /internal/admin/, for example events/12/space")
    parser.add_argument("body", nargs="?", help="a JSON body for POST and PUT")
    parser.add_argument("--actor", default=os.environ.get("DEV_ACTOR_ID"), help="Discord user id of a dashboard admin")
    parser.add_argument("--port", default="8081")
    args = parser.parse_args()
    if not args.actor:
        sys.exit("Say who is asking: --actor <discord user id> (or set DEV_ACTOR_ID).")

    headers = {
        "X-Internal-Secret": run_config_value("INTERNAL_API_SECRET"),
        "X-Guild-Id": run_config_value("GUILD_ID"),
        "X-Actor-Id": str(args.actor),
        "Content-Type": "application/json",
    }
    data = None
    if args.method != "GET":
        data = (args.body or "{}").encode("utf-8")
        json.loads(data)  # fail here, not in the bot, if the body isn't JSON
    request = urllib.request.Request(f"http://localhost:{args.port}/internal/admin/{args.route}", data=data, headers=headers, method=args.method)
    try:
        with urllib.request.urlopen(request, timeout=120) as response:
            status, raw = response.status, response.read().decode("utf-8")
    except urllib.error.HTTPError as error:
        status, raw = error.code, error.read().decode("utf-8")
    except urllib.error.URLError as error:
        sys.exit(f"Couldn't reach the bot on port {args.port}: {error.reason}. Is JonnyBot Dev running?")
    try:
        raw = json.dumps(json.loads(raw), indent=2, ensure_ascii=False)
    except ValueError:
        pass
    sys.stdout.reconfigure(encoding="utf-8")
    print(status)
    print(raw)


if __name__ == "__main__":
    main()
