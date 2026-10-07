#!/usr/bin/env python3
"""
Builds the Combat Mastery achievement catalogue from the official RuneScape Wiki, including what each achievement's own wiki page says
about it (the description, tips, what it requires), and downloads the few pictures the tables use.

  python tools/ca_sync.py --site ../younglings-website            # everything (reuses pages fetched earlier today)
  python tools/ca_sync.py --site ../younglings-website --fresh    # fetch every page again
  python tools/ca_sync.py --limit 15                               # just the first 15 achievements, to try it out

Reads (all from runescape.wiki):
  Combat Mastery achievements        the full list: name, description, members, subcategory, subsubcategory, tier, CombatScore, RuneScore
  <each achievement's own page>      the infobox, the written description and tips, the achievements it lists

Writes:
  src/main/resources/catalog/combat_achievements.json    tiers + every achievement; the bot loads this into its database on startup
  <site>/public/combat/*.png                              tier icons, CombatScore and RuneScore icons, members and free-to-play icons

The pictures are RuneScape artwork (Jagex), used here the same way the wiki uses them.
Be polite to the wiki: requests are sequential with a short pause, and the user agent says who we are. Fetched pages are kept in a
temporary folder so an interrupted run carries on where it stopped.
"""
import argparse
import hashlib
import html
import json
import re
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
from html.parser import HTMLParser
from pathlib import Path

API = "https://runescape.wiki/api.php"
SITE = "https://runescape.wiki"
UA = "YounglingsClanBot/1.0 (RuneScape clan site; contact drakeforness@gmail.com)"
PAUSE = 0.3
MAIN_PAGE = "Combat Mastery achievements"
TIERS = ["Easy", "Medium", "Hard", "Elite", "Master", "Grandmaster"]
CACHE = Path(tempfile.gettempdir()) / "ca_sync_cache"

# Sections of an achievement page that say nothing useful about doing it.
SKIP_SECTIONS = {"update history", "achievements", "gallery", "references", "see also", "external links", "transcript"}
VOID = {"br", "img", "hr", "meta", "link", "input", "wbr", "source", "col", "area", "base", "embed", "param", "track"}


def get(url, binary=False):
    request = urllib.request.Request(url, headers={"User-Agent": UA})
    for attempt in range(4):
        try:
            with urllib.request.urlopen(request, timeout=60) as response:
                data = response.read()
            time.sleep(PAUSE)
            return data if binary else data.decode("utf-8")
        except (urllib.error.URLError, TimeoutError) as error:
            if attempt == 3:
                raise
            print(f"  retrying after {error}", file=sys.stderr)
            time.sleep(2 * (attempt + 1))


def page_html(title, fresh):
    """The rendered HTML of a wiki page, cached on disk by title."""
    CACHE.mkdir(exist_ok=True)
    cached = CACHE / (hashlib.sha1(title.encode()).hexdigest() + ".html")
    if cached.exists() and not fresh:
        return cached.read_text(encoding="utf-8")
    query = urllib.parse.urlencode({"action": "parse", "page": title, "prop": "text", "format": "json", "formatversion": 2, "redirects": 1})
    data = json.loads(get(f"{API}?{query}"))
    if "parse" not in data:
        return None
    body = data["parse"]["text"]
    cached.write_text(body, encoding="utf-8")
    return body


def clean(fragment):
    """Visible text of an HTML fragment, one line."""
    text = re.sub(r"<[^>]+>", " ", fragment)
    return re.sub(r"\s+", " ", html.unescape(text)).strip()


def link_of(fragment):
    """(text, absolute url, page title) of the first link in a fragment, or (text, None, None)."""
    match = re.search(r'<a [^>]*href="([^"]+)"[^>]*?(?:title="([^"]*)")?[^>]*>(.*?)</a>', fragment, re.S)
    if not match:
        return clean(fragment), None, None
    href, title, inner = match.groups()
    return clean(inner), SITE + html.unescape(href), html.unescape(title) if title else None


def original_image(src):
    """A thumbnail's address -> the full-size file: /images/thumb/X.png/22px-X.png?abc -> /images/X.png"""
    src = html.unescape(src).split("?")[0]
    match = re.match(r"/images/thumb/([^/]+)/", src)
    return f"{SITE}/images/{match.group(1)}" if match else SITE + src


def parse_main(body):
    """The all-tiers table (the one with a Subsubcategory column) and the tier summary table."""
    tables = re.findall(r"<table[^>]*data-tableid=\"Achievements list\"[^>]*>.*?</table>", body, re.S)
    full = next(t for t in tables if "Subsubcategory" in t)

    rows = re.split(r'<tr data-rowid="(\d+)">', full)[1:]
    achievements, icons = [], {}
    for rowid, rest in zip(rows[0::2], rows[1::2]):
        cells = re.findall(r"<td[^>]*>(.*?)</td>", rest.split("</tr>")[0], re.S)
        if len(cells) < 8:
            print(f"  skipping row {rowid}: {len(cells)} cells", file=sys.stderr)
            continue
        name, url, title = link_of(cells[0])
        subcategory, subcategory_url, _ = link_of(cells[3])
        subsub_text, subsub_url, _ = link_of(cells[4])
        tier_text = clean(cells[5])
        tier_icon = re.search(r'<img [^>]*src="([^"]+)"', cells[5])
        if tier_icon:
            icons[tier_text] = original_image(tier_icon.group(1))
        achievements.append({
            "id": int(rowid),
            "name": name,
            # The link's address names the page exactly (a title attribute or the link text can name a different page that shares the name).
            "wikiTitle": urllib.parse.unquote(url.split("/w/", 1)[1]).replace("_", " ") if url and "/w/" in url else (title or name),
            "wikiUrl": url,
            "description": clean(cells[1]),
            "members": "P2P_icon" in cells[2],
            "subcategory": subcategory,
            "subcategoryUrl": subcategory_url,
            "subsubcategory": None if subsub_text in ("", "N/A") else subsub_text,
            "subsubcategoryUrl": None if subsub_text in ("", "N/A") else subsub_url,
            "tier": tier_text,
            "tierNumber": TIERS.index(tier_text) + 1,
            "combatScore": int(clean(cells[6]).replace(",", "") or 0),
            "runeScore": int(clean(cells[7]).replace(",", "") or 0),
        })

    other = {}
    for key, label in (("combatScore", "CombatScore"), ("runeScore", "RuneScore")):
        header = re.search(rf'<th>\s*<span[^>]*>\s*<a [^>]*title="{label}"[^>]*>\s*<img [^>]*src="([^"]+)"', full)
        other[key] = original_image(header.group(1)) if header else None
    members = re.search(r'<img [^>]*src="([^"]*P2P_icon[^"]*)"', full)
    free = re.search(r'<img [^>]*src="([^"]*F2P_icon[^"]*)"', full)
    return achievements, icons, {
        "combatScore": other["combatScore"],
        "runeScore": other["runeScore"],
        "members": original_image(members.group(1)) if members else None,
        "free": original_image(free.group(1)) if free else None,
    }, tier_summary(body)


def tier_summary(body):
    """Per tier: its reward, CombatScore per achievement and how many achievements it holds, from the first table on the page."""
    table = re.search(r"<table[^>]*data-tableid=\"Achievements list\"[^>]*>.*?</table>", body, re.S).group(0)
    summary = {}
    for row in re.findall(r"<tr[^>]*>(.*?)</tr>", table, re.S):
        cells = re.findall(r"<td[^>]*>(.*?)</td>", row, re.S)
        if len(cells) < 5:
            continue
        # cells: icon, name, reward, CombatScore per achievement, "22" or "60(+1)" achievements, CombatScore in tier, cumulative
        name = clean(cells[1]).replace("Combat Mastery - ", "").strip()
        if name in TIERS:
            count = re.match(r"\d+", clean(cells[4]))
            summary[name] = {"reward": clean(cells[2]), "combatScorePer": int(clean(cells[3]) or 0), "count": int(count.group(0)) if count else 0}
    return summary


class PageText(HTMLParser):
    """Turns an achievement page's HTML into the readable text a player would want: headings, paragraphs and bullet lists, without the
    infobox, navigation boxes, references or tables. Also remembers the infobox's fields and the achievements the page lists."""

    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.out, self.line = [], []
        self.stack = []          # open tags, for knowing when a skipped element ends
        self.skip_depth = None   # stack length at which a skipped element started
        self.heading = None
        self.in_li = 0
        self.listed = []
        self._row_id = None

    # ----- helpers -----
    def flush(self):
        text = re.sub(r"\s+", " ", "".join(self.line)).strip()
        self.line = []
        if text:
            self.out.append(("• " if self.in_li else "") + text)

    def skipping(self):
        return self.skip_depth is not None

    @staticmethod
    def should_skip(tag, attrs):
        classes = (attrs.get("class") or "").split()
        if tag in ("style", "script", "sup", "noscript"):
            return True
        if tag == "table" and any(c in classes for c in ("rsw-infobox", "navbox", "navbox-subgroup", "wikitable", "toc")):
            return True
        if tag == "div" and any(c in classes for c in ("navbox", "toc", "printfooter", "references", "reflist", "gallery", "thumb", "hatnote")):
            return True
        if tag in ("span", "div") and any(c in classes for c in ("mw-editsection", "mw-headline-anchor")):
            return True
        if tag in ("ol", "ul") and "references" in classes:
            return True
        return False

    # ----- events -----
    def handle_starttag(self, tag, attrs_list):
        attrs = dict(attrs_list)
        if tag in VOID:
            if tag == "br" and not self.skipping():
                self.line.append(" ")
            return
        self.stack.append(tag)

        # The achievements a page lists (a perfect or unorthodox achievement's targets, say): the first link of each table row.
        # Recorded before the skip check, because those tables are skipped as text.
        if tag == "tr" and attrs.get("data-rowid"):
            self._row_id = attrs["data-rowid"]
        if tag == "a" and self._row_id and attrs.get("title"):
            self.listed.append({"id": int(self._row_id), "name": html.unescape(attrs["title"])})
            self._row_id = None

        if not self.skipping() and self.should_skip(tag, attrs):
            self.skip_depth = len(self.stack)
            return
        if self.skipping():
            return

        if tag in ("h2", "h3", "h4"):
            self.flush()
            self.heading = tag
            self.line = []
        elif tag in ("p", "div", "ul", "ol", "dl", "dd", "dt"):
            self.flush()
        elif tag == "li":
            self.flush()
            self.in_li += 1

    def handle_endtag(self, tag):
        if tag in VOID:
            return
        if tag not in self.stack:
            return
        while self.stack:
            popped = self.stack.pop()
            if self.skipping() and len(self.stack) < self.skip_depth:
                self.skip_depth = None
            if popped == tag:
                break
        if self.skipping():
            return
        if tag in ("h2", "h3", "h4"):
            text = re.sub(r"\s+", " ", "".join(self.line)).strip()
            self.line = []
            self.heading = None
            if text:
                self.out.append(("## " if tag == "h2" else "### ") + text)
        elif tag == "li":
            self.flush()
            self.in_li = max(0, self.in_li - 1)
        elif tag in ("p", "div", "ul", "ol", "dl", "dd", "dt"):
            self.flush()

    def handle_data(self, data):
        if not self.skipping():
            self.line.append(data)

    def text(self):
        self.flush()
        # drop whole sections that say nothing about doing the achievement, and headings left with nothing under them
        sections, current = [], [None, []]
        for line in self.out:
            if line.startswith("## "):
                sections.append(current)
                current = [line[3:].strip(), []]
            else:
                current[1].append(line)
        sections.append(current)
        kept = []
        for title, lines in sections:
            if title and title.lower() in SKIP_SECTIONS:
                continue
            if title is None:
                kept.extend(lines)
            elif any(not l.startswith("### ") for l in lines):
                kept.append(f"## {title}")
                kept.extend(lines)
        # paragraphs and headings are separated by a blank line; the bullets of one list stay together
        text = ""
        for index, line in enumerate(kept):
            if index:
                text += "\n" if line.startswith("• ") and kept[index - 1].startswith("• ") else "\n\n"
            text += line
        return text.strip()


def infobox_of(body):
    """label -> value for the achievement infobox ("Release", "RuneScore", "Requirements", "Rewards"...)."""
    match = re.search(r'<table class="rsw-infobox[^"]*infobox-achievement">.*?</table>', body, re.S)
    if not match:
        return {}
    fields = {}
    for row in re.findall(r"<tr>(.*?)</tr>", match.group(0), re.S):
        label = re.search(r"<th[^>]*>(.*?)</th>", row, re.S)
        value = re.search(r"<td[^>]*data-attr-param[^>]*>(.*?)</td>", row, re.S)
        if label and value:
            items = re.findall(r"<li[^>]*>(.*?)</li>", value.group(1), re.S)
            text = "; ".join(clean(i) for i in items) if items else clean(value.group(1))
            if text:
                fields[clean(label.group(1))] = text
    return fields


def page_details(body):
    parser = PageText()
    parser.feed(body)
    text = parser.text()
    summary = next((l for l in text.split("\n") if l and not l.startswith(("##", "•"))), "")
    return {"summary": summary, "text": text, "infobox": infobox_of(body), "listed": parser.listed}


def download_images(urls, site, force):
    target = Path(site) / "public" / "combat"
    target.mkdir(parents=True, exist_ok=True)
    for name, url in urls.items():
        if not url:
            print(f"  no address for {name}", file=sys.stderr)
            continue
        destination = target / f"{name}.png"
        if destination.exists() and not force:
            continue
        destination.write_bytes(get(url, binary=True))
        print(f"  downloaded {destination.name} ({destination.stat().st_size} bytes)")


def main():
    parser = argparse.ArgumentParser(description="Build the Combat Mastery achievement catalogue from the RuneScape Wiki.")
    parser.add_argument("--site", default="../younglings-website", help="the website checkout, where the pictures go")
    parser.add_argument("--fresh", action="store_true", help="fetch every wiki page again instead of reusing earlier ones")
    parser.add_argument("--force-images", action="store_true", help="download the pictures again")
    parser.add_argument("--limit", type=int, default=0, help="only the first N achievements (to try it out)")
    args = parser.parse_args()

    root = Path(__file__).resolve().parent.parent
    print(f"Reading {MAIN_PAGE}...")
    main_body = page_html(MAIN_PAGE, args.fresh)
    achievements, tier_icons, other_icons, summary = parse_main(main_body)
    print(f"  {len(achievements)} achievements, tiers: " + ", ".join(f"{t} {sum(1 for a in achievements if a['tier'] == t)}" for t in TIERS))
    if args.limit:
        achievements = achievements[: args.limit]

    for number, achievement in enumerate(achievements, 1):
        body = page_html(achievement["wikiTitle"], args.fresh)
        if body is None:
            print(f"  [{number}/{len(achievements)}] {achievement['name']}: page not found", file=sys.stderr)
            achievement.update({"wikiSummary": None, "wikiText": None, "infobox": {}, "listedAchievements": []})
            continue
        details = page_details(body)
        achievement.update({"wikiSummary": details["summary"] or None, "wikiText": details["text"] or None,
                            "infobox": details["infobox"], "listedAchievements": details["listed"]})
        if number % 25 == 0 or number == len(achievements):
            print(f"  [{number}/{len(achievements)}] {achievement['name']}")

    slug = lambda tier: f"tier-{tier.lower()}"
    images = {slug(t): tier_icons.get(t) for t in TIERS}
    images.update({"combat-score": other_icons["combatScore"], "rune-score": other_icons["runeScore"],
                   "members": other_icons["members"], "free": other_icons["free"]})
    print("Pictures...")
    download_images(images, args.site, args.force_images)

    tiers = [{"name": t, "number": i + 1, "icon": f"/combat/{slug(t)}.png", "wikiUrl": f"{SITE}/w/Combat_Mastery_-_{t}",
              "reward": summary.get(t, {}).get("reward"), "combatScorePer": summary.get(t, {}).get("combatScorePer", i + 1),
              "count": sum(1 for a in achievements if a["tier"] == t)} for i, t in enumerate(TIERS)]
    for a in achievements:
        a["tierIcon"] = f"/combat/{slug(a['tier'])}.png"
        a["combatScoreIcon"] = "/combat/combat-score.png"
        a["runeScoreIcon"] = "/combat/rune-score.png"
        a["membersIcon"] = "/combat/members.png" if a["members"] else "/combat/free.png"

    out = root / "src" / "main" / "resources" / "catalog" / "combat_achievements.json"
    document = {"source": f"{SITE}/w/Combat_Mastery_achievements", "tiers": tiers, "achievements": achievements}
    out.write_text(json.dumps(document, ensure_ascii=False, indent=1) + "\n", encoding="utf-8", newline="\n")
    print(f"Wrote {out} ({out.stat().st_size // 1024} KB)")


if __name__ == "__main__":
    main()
