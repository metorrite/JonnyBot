#!/usr/bin/env python3
"""
Builds the boss/drop catalogue from the official RuneScape Wiki and downloads every picture it needs, so the bot
and the website serve them from our own files instead of calling the wiki on every page view.

  python tools/wiki_sync.py --site ../younglings-website            # everything (skips images already downloaded)
  python tools/wiki_sync.py --site ../younglings-website --force    # re-download every image
  python tools/wiki_sync.py --only "Vorago,Nex"                      # just these bosses (by display name)

Writes:
  src/main/resources/catalog/bosses.json        boss -> aliases, wiki page, picture, full drop table
  <site>/public/bosses/<key>.png                the wiki's own picture of each boss
  <site>/public/items/<item-key>.png            the wiki's inventory icon for every dropped item

Boss pictures and item icons are RuneScape artwork (Jagex), used here the same way the wiki uses them.
Be polite to the wiki: requests are sequential with a short pause, and the user agent says who we are.
"""
import argparse
import json
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

API = "https://runescape.wiki/api.php"
UA = "YounglingsClanBot/1.0 (RuneScape clan site; contact drakeforness@gmail.com)"
PAUSE = 0.25

# display name, wiki page title (None = search for it), extra names the adventure log may use for the same boss
BOSSES = [
    ("King Black Dragon", "King Black Dragon", []),
    ("Vindicta & Gorvek", "Vindicta & Gorvek", ["Vindicta", "Gorvek"]),
    ("K'ril Tsutsaroth", "K'ril Tsutsaroth", []),
    ("Raksha", "Raksha, the Shadow Colossus", ["Raksha"]),
    ("Arch-Glacor", "Arch-Glacor", []),
    ("Kalphite Queen", "Kalphite Queen", []),
    ("Kree'arra", "Kree'arra", []),
    ("Telos", "Telos, the Warden", ["Telos, the Warden"]),
    ("The Gate of Elidinis", "The Gate of Elidinis", ["Gate of Elidinis"]),
    ("Helwyr", "Helwyr", []),
    ("Rex Matriarch", "Rex Matriarchs", ["Rex Matriarchs"]),
    ("Zamorak", "Zamorak, Lord of Chaos", []),
    ("Amascut", "Amascut, the Devourer", []),
    ("Nex", "Nex", []),
    ("Araxxi", "Araxxi", []),
    ("Kerapac", "Kerapac, the Bound", ["Kerapac, the Bound"]),
    ("Commander Zilyana", "Commander Zilyana", []),
    ("Chaos Elemental", "Chaos Elemental", []),
    ("Hermod", "Hermod, the Spirit of War", ["Hermod, the Spirit of War"]),
    ("Vermyx", "Vermyx", []),
    ("Kezalam", "Kezalam, the Wanderer", ["Kezalam, the Wanderer"]),
    ("Nakatra", "Nakatra, Devourer Eternal", ["Nakatra, Devourer Eternal"]),
    ("General Graardor", "General Graardor", []),
    ("Gregorovic", "Gregorovic", []),
    ("Rasial", "Rasial, the First Necromancer", ["Rasial, the First Necromancer"]),
    ("Zemouregal & Vorkath", "Zemouregal & Vorkath", ["Zemouregal and Vorkath"]),
    ("Croesus", "Croesus", ["Croesus'"]),
    ("Dagannoth Kings", "Dagannoth Kings", ["Dagannoth King", "Dagannoth Rex", "Dagannoth Prime", "Dagannoth Supreme"]),
    ("Black Stone Dragon", "Black Stone Dragon", []),
    ("Vorago", "Vorago", []),
    ("Corporeal Beast", "Corporeal Beast", []),
    ("The Magister", "The Magister", ["Magister"]),
    ("Kalphite King", "Kalphite King", []),
    ("Queen Black Dragon", "Queen Black Dragon", []),
    ("Seiryu the Azure Serpent", "Seiryu, the Azure Serpent", ["Seiryu"]),
    ("Har-Aken", "Har-Aken", []),
    ("TzKal-Zuk", "TzKal-Zuk", []),
    ("TzTok-Jad", "TzTok-Jad", []),
    ("Solak", "Solak", []),
    ("The Twin Furies", "The Twin Furies", ["Nymora", "Avaryss", "Nymora, the Vengeful", "Avaryss, the Unceasing"]),
    ("Tormented demon", "Tormented demon", ["tormented demon"]),
    ("Giant Mole", "Giant Mole", []),
    ("Barrows brothers", "Barrows", []),
    ("Telos (Enrage)", None, []),
]
# Not real bosses: kept out of the catalogue so they stay plain (picture-less) rows rather than pretending to be bosses.
BOSSES = [b for b in BOSSES if b[1] is not None]


def api(**params):
    params["format"] = "json"
    url = API + "?" + urllib.parse.urlencode(params)
    for attempt in range(4):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": UA})
            with urllib.request.urlopen(req, timeout=40) as r:
                time.sleep(PAUSE)
                return json.load(r)
        except (urllib.error.URLError, TimeoutError) as e:
            time.sleep(2 * (attempt + 1))
            last = e
    raise RuntimeError(f"wiki request failed: {last}")


def slug(text):
    return re.sub(r"[^a-z0-9]+", "_", text.lower().replace("'", "")).strip("_")


def wikitext(title):
    d = api(action="query", titles=title, redirects=1, prop="revisions", rvprop="content", rvslots="main")
    page = next(iter(d["query"]["pages"].values()))
    if "revisions" not in page:
        return None, None
    return page["title"], page["revisions"][0]["slots"]["main"]["*"]


def search_title(name):
    d = api(action="query", list="search", srsearch=name, srlimit=3)
    hits = d["query"]["search"]
    return hits[0]["title"] if hits else None


def template_blocks(text, name):
    """Every {{name|...}} block, brace-matched so nested templates inside the arguments don't cut it short."""
    out, i, key = [], 0, "{{" + name
    while True:
        i = text.find(key, i)
        if i < 0:
            return out
        depth, j = 0, i
        while j < len(text):
            if text.startswith("{{", j):
                depth += 1
                j += 2
            elif text.startswith("}}", j):
                depth -= 1
                j += 2
                if depth == 0:
                    break
            else:
                j += 1
        out.append(text[i:j])
        i = j


def top_level_args(block):
    """The |key=value arguments of one template, split only at its own top-level pipes."""
    inner = block[2:-2]
    parts, depth, cur, k = [], 0, [], 0
    while k < len(inner):
        two = inner[k:k + 2]
        if two in ("{{", "[["):
            depth += 1
            cur.append(two)
            k += 2
        elif two in ("}}", "]]"):
            depth -= 1
            cur.append(two)
            k += 2
        elif inner[k] == "|" and depth == 0:
            parts.append("".join(cur))
            cur = []
            k += 1
        else:
            cur.append(inner[k])
            k += 1
    parts.append("".join(cur))
    args = {}
    for p in parts[1:]:
        if "=" in p:
            a, b = p.split("=", 1)
            args[a.strip().lower()] = b.strip()
    return args


def parse_drops(text):
    seen, drops = set(), []
    for block in template_blocks(text, "DropsLine"):
        a = top_level_args(block)
        name = re.sub(r"\[\[(?:[^\]|]*\|)?([^\]]*)\]\]", r"\1", a.get("name", "")).strip()
        if not name or name.lower() in seen:
            continue
        seen.add(name.lower())
        drops.append({"item": name, "key": slug(name), "quantity": a.get("quantity", ""), "rarity": a.get("rarity", "")})
    return drops


def page_image(title):
    d = api(action="query", titles=title, redirects=1, prop="pageimages", piprop="original")
    page = next(iter(d["query"]["pages"].values()))
    return page.get("original", {}).get("source")


def file_urls(filenames):
    """filename -> url for the files the wiki actually has, 50 per request."""
    found = {}
    names = list(filenames)
    for i in range(0, len(names), 50):
        chunk = names[i:i + 50]
        d = api(action="query", titles="|".join("File:" + n for n in chunk), prop="imageinfo", iiprop="url")
        norm = {n["to"]: n["from"] for n in d["query"].get("normalized", [])}
        for page in d["query"]["pages"].values():
            if "imageinfo" in page:
                title = page["title"]
                original = norm.get(title, title)
                found[original[len("File:"):]] = page["imageinfo"][0]["url"]
    return found


def download(url, dest, force):
    if dest.exists() and not force:
        return True
    try:
        req = urllib.request.Request(url, headers={"User-Agent": UA})
        with urllib.request.urlopen(req, timeout=60) as r:
            data = r.read()
        dest.parent.mkdir(parents=True, exist_ok=True)
        dest.write_bytes(data)
        time.sleep(PAUSE)
        return True
    except Exception as e:  # a missing picture is not fatal; the site falls back to a default
        print("   download failed:", url, e)
        return False


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--site", default="../younglings-website")
    ap.add_argument("--force", action="store_true")
    ap.add_argument("--only", default="")
    args = ap.parse_args()

    root = Path(__file__).resolve().parent.parent
    site = (root / args.site).resolve()
    only = {s.strip().lower() for s in args.only.split(",") if s.strip()}
    out_json = root / "src/main/resources/catalog/bosses.json"

    existing = {}
    if out_json.exists() and only:
        existing = {b["name"]: b for b in json.loads(out_json.read_text(encoding="utf-8"))["bosses"]}

    bosses, problems = dict(existing), []
    for name, page_title, aliases in BOSSES:
        if only and name.lower() not in only:
            continue
        print(f"== {name}")
        title, text = wikitext(page_title)
        drops = parse_drops(text) if text else []
        if not drops:
            alt = search_title(page_title + " drops")
            print(f"   no drop table at '{page_title}', trying search hit '{alt}'")
            if alt:
                title, text = wikitext(alt)
                drops = parse_drops(text) if text else []
        if not drops:
            problems.append(name)
        image_url = page_image(title) if title else None
        key = slug(name)
        image_ok = bool(image_url) and download(image_url, site / "public/bosses" / f"{key}.png", args.force)
        print(f"   page '{title}', {len(drops)} drops, picture {'ok' if image_ok else 'MISSING'}")
        bosses[name] = {
            "key": key,
            "name": name,
            "wikiPage": title,
            "aliases": sorted({name, *aliases}),
            "image": f"/bosses/{key}.png" if image_ok else None,
            "drops": drops,
        }

    # one icon per distinct item across every boss
    items = {}
    for b in bosses.values():
        for d in b["drops"]:
            items.setdefault(d["key"], d["item"])
    print(f"== {len(items)} distinct items; fetching icons")
    wanted = {key: f"{name[0].upper() + name[1:]}.png" for key, name in items.items()}
    to_fetch = {k: f for k, f in wanted.items() if args.force or not (site / "public/items" / f"{k}.png").exists()}
    urls = file_urls(set(to_fetch.values())) if to_fetch else {}
    missing = []
    for key, filename in to_fetch.items():
        url = urls.get(filename)
        if not url or not download(url, site / "public/items" / f"{key}.png", args.force):
            missing.append(items[key])
    have = {k for k in items if (site / "public/items" / f"{k}.png").exists()}
    for b in bosses.values():
        for d in b["drops"]:
            d["icon"] = f"/items/{d['key']}.png" if d["key"] in have else None

    out_json.parent.mkdir(parents=True, exist_ok=True)
    ordered = [bosses[n] for n, _, _ in BOSSES if n in bosses]
    out_json.write_text(json.dumps({"bosses": ordered}, indent=1, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"\nwrote {out_json} ({len(ordered)} bosses)")
    if problems:
        print("bosses with NO drop table found:", ", ".join(problems))
    print(f"items without an icon: {len(missing)}", ("-> " + ", ".join(missing[:40])) if missing else "")


if __name__ == "__main__":
    sys.exit(main())
