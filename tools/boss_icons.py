#!/usr/bin/env python3
"""
Keeps the bot and the website using the same pictures for bosses and for the kinds of clan activity, and gives every boss one.

  python tools/boss_icons.py --site ../younglings-website

What it does (run it after tools/wiki_sync.py, which builds the boss catalogue and the website's boss pictures):
  1. Adds the named bosses the catalogue doesn't cover (EXTRA_BOSSES below: the Combat Mastery bosses that can show up in a kill line) by downloading
     their picture from the RuneScape Wiki into <site>/public/bosses/, the same folder the catalogued bosses' pictures are in.
  2. Writes src/main/resources/catalog/boss_icons.json: every boss with its picture key and every name a kill line may use for it. The bot's
     BossCatalog reads it, so a kill is matched to its boss and shows that boss's icon instead of the generic one.
  3. Copies every boss picture into the bot's src/main/resources/images/bosses/ (the bot uploads them as emojis, like its drop and skill icons).
  4. Copies the bot's own activity icons (quest, clue, citadel, RuneScore, and the default boss and drop) into <site>/public/tracking/, so the
     website's Clan Activity shows the very same pictures as the bot's posts.
  5. Writes <site>/src/lib/activityIcons.generated.ts: which boss or dropped item a line of activity text is about, and its picture.

The pictures are RuneScape artwork (Jagex), used the way the wiki uses them. Requests are sequential with a short pause, like wiki_sync.py.
"""
import argparse
import hashlib
import json
import shutil
import sys
from pathlib import Path

import wiki_sync as wiki

# display name, wiki page, extra names a kill line may use. Bosses of the Combat Mastery achievements that wiki_sync's boss catalogue (the drop
# tables on the website's PvM pages) doesn't have; they only get a picture and an icon here, no drop table.
EXTRA_BOSSES = [
    ("Astellarn", "Astellarn", []),
    ("Beastmaster Durzag", "Beastmaster Durzag", []),
    ("Crassian Leviathan", "Crassian Leviathan", []),
    ("The Ambassador", "The Ambassador", ["Ambassador"]),
    ("Ivar, King of Bones", "Ivar, King of Bones", ["Ivar"]),
    ("Legiones", "Legiones", []),
    ("Masuta the Ascended", "Masuta the Ascended", ["Masuta"]),
    ("Silverquill, the Dreadhog", "Silverquill, the Dreadhog", ["Silverquill"]),
    ("Taraket the Necromancer", "Taraket the Necromancer", ["Taraket"]),
    ("Verak Lith", "Verak Lith", []),
    ("Yakamaru", "Yakamaru", []),
    ("The Sanctum Guardian", "The Sanctum Guardian", ["Sanctum Guardian"]),
]

# the bot's own pictures for the kinds of activity that aren't a skill, a boss or an item
TRACKING_ICONS = ["quest", "clue", "citadel", "runescore", "default_boss", "default_drop"]


def main():
    parser = argparse.ArgumentParser(description="Sync boss and activity icons between the bot and the website.")
    parser.add_argument("--site", default="../younglings-website", help="the website checkout")
    parser.add_argument("--force", action="store_true", help="download the extra bosses' pictures again")
    args = parser.parse_args()

    root = Path(__file__).resolve().parent.parent
    site = (root / args.site).resolve()
    catalogue = json.loads((root / "src/main/resources/catalog/bosses.json").read_text(encoding="utf-8"))["bosses"]

    bosses = [{"key": b["key"], "name": b["name"], "aliases": sorted(set(b["aliases"]) | {b["name"]}), "image": b.get("image")} for b in catalogue]

    print("Extra bosses...")
    for name, page, aliases in EXTRA_BOSSES:
        key = wiki.slug(name)
        title, text = wiki.wikitext(page)
        url = wiki.page_image(title) if title else None
        ok = bool(url) and wiki.download(url, site / "public/bosses" / f"{key}.png", args.force)
        print(f"   {name}: page '{title}', picture {'ok' if ok else 'MISSING'}")
        bosses.append({"key": key, "name": name, "aliases": sorted({name, *aliases}), "image": f"/bosses/{key}.png" if ok else None})

    # a boss whose picture is missing is left out, so it keeps the generic icon rather than a broken one
    bosses = [b for b in bosses if b["image"] and (site / "public" / b["image"].lstrip("/")).exists()]

    # two bosses with the very same picture means one was fetched wrong (Nakatra once showed Amascut); refuse rather than ship it
    seen = {}
    for b in bosses:
        digest = hashlib.md5((site / "public" / b["image"].lstrip("/")).read_bytes()).hexdigest()
        if digest in seen:
            sys.exit(f"{b['name']} and {seen[digest]} have identical pictures; delete the wrong one from {site / 'public/bosses'} and run again with --force")
        seen[digest] = b["name"]

    out = root / "src/main/resources/catalog/boss_icons.json"
    out.write_text(json.dumps({"bosses": bosses}, indent=1, ensure_ascii=False) + "\n", encoding="utf-8", newline="\n")
    print(f"Wrote {out.relative_to(root)} ({len(bosses)} bosses)")

    target = root / "src/main/resources/images/bosses"
    target.mkdir(parents=True, exist_ok=True)
    for b in bosses:
        shutil.copyfile(site / "public" / b["image"].lstrip("/"), target / f"{b['key']}.png")
    print(f"Copied {len(bosses)} boss pictures to the bot's images/bosses/")

    tracking = site / "public/tracking"
    tracking.mkdir(parents=True, exist_ok=True)
    for key in TRACKING_ICONS:
        shutil.copyfile(root / "src/main/resources/images/tracking" / f"{key}.png", tracking / f"{key}.png")
    print(f"Copied {len(TRACKING_ICONS)} activity icons to the website's public/tracking/")

    write_site_table(site, bosses, catalogue)


def ts(value):
    return json.dumps(value, ensure_ascii=False)


def write_site_table(site, bosses, catalogue):
    """Which boss or item a line of activity text is about. Longest names first, so "Telos, the Warden" is tried before "Telos"."""
    boss_names = sorted(((alias, b["key"]) for b in bosses for alias in b["aliases"]), key=lambda p: (-len(p[0]), p[0]))

    items = {}
    for b in catalogue:
        for d in b["drops"]:
            if d.get("icon") and (site / "public" / d["icon"].lstrip("/")).exists():
                items.setdefault(d["item"], d["key"])
    item_names = sorted(items.items(), key=lambda p: (-len(p[0]), p[0]))

    lines = [
        "// Generated by JonnyBot's tools/boss_icons.py. Do not edit by hand; run the tool again instead.",
        "// Which boss or dropped item a line of clan activity is about, and so which picture to show for it.",
        "",
        "/** [name a kill line may use, boss picture key under /bosses/]. Longest names first. */",
        "export const BOSS_NAMES: readonly (readonly [string, string])[] = [",
        *[f"  [{ts(name)}, {ts(key)}]," for name, key in boss_names],
        "];",
        "",
        "/** [item name, item picture key under /items/]. Longest names first. */",
        "export const ITEM_NAMES: readonly (readonly [string, string])[] = [",
        *[f"  [{ts(name)}, {ts(key)}]," for name, key in item_names],
        "];",
        "",
    ]
    out = site / "src/lib/activityIcons.generated.ts"
    out.write_text("\n".join(lines), encoding="utf-8", newline="\n")
    print(f"Wrote {out.name} ({len(boss_names)} boss names, {len(item_names)} items)")


if __name__ == "__main__":
    sys.exit(main())
