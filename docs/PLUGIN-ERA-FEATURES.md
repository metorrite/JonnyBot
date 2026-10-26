# Plugin-era features (design)

Ticket T-062. Step 7 of the clan-platform plan: features that only the RS3 plugin API makes possible. Status: **design with defaults**. Nothing here is built. Each feature lists what it would take, what Jagex's rules allow, a default, and the questions only Drake can answer.

Read with: `docs/RUNESYNC-DESIGN.md` (RuneSync, T-002), `docs/LINK-SYSTEM.md` (RuneLink), and the local API library `CLAUDE_RESOURCES/rs-api-library` (`notes/capability-map.md`, `docs/submission.md`, `docs/game-coords.md`, `docs/game-entities.md`).

## 0. The rules that decide everything

From the API library (Jagex's pages, October 2026):

- **Exporting any information about other players is prohibited.** A plugin may only send its own player's data. Clan-wide views are built on JonnyBot's server, from many members who each report only themselves.
- **Obtaining information about other players is "subject to review".** Moderators look harder at it, and they also look harder at a plugin that depends on plugins that need review.
- **Combat state** (HP, prayer, adrenaline of others), predictions, and "extra player info in PvP" are prohibited or heavily reviewed. Overlays of non-combat information are allowed.
- **No clan data API** today: clan vars and clan rank are "currently unavailable". JonnyBot knows clan membership from the RuneMetrics roster, which is public.
- **HTTP only**, GET/POST to the manifest URLs, with no websockets. Anything "live" is polling, at best every few seconds.
- **Mobile** must work: no keyboard, mouse or console.

Anything that sends one player's data to another player's game has to respect what the receiving member could already see, and be **opt-in on both sides**.

## 1. "Has plugin" registry

**What:** clan staff (and maybe members) see who uses RuneSync, for example to know who will be auto-tracked in a bingo.

**What exists:** the dashboard's Clan plugin page shows **counts only** ("nobody is named"). That was a deliberate privacy choice. Since T-202 the bot knows each member's client and when it was last seen (`plugin_client_status`).

**Design (default):**
- A member opts in on their profile (Plugin tab): **"Show my clan that I use RuneSync"**, off by default.
- The dashboard's Clan plugin page gets a **"Members who chose to show it"** list: RSN, last seen (day only) and client version. Members who didn't opt in stay in the counts only.
- Events: the event page can mark opted-in participants with a small "auto-tracked" chip, so organisers know whose tiles tick themselves.
- Nothing extra in the game. The plugin sends nothing new.

**Rules:** server-side only, made from each member's own reports, with consent. No Jagex concern.

**Effort:** small (bot route + profile toggle + dashboard list). **Suggested ticket:** "Has-plugin list (opt-in)".

## 2. Plugin-driven bingo (what's left)

**Done already:**
- T-203 RuneSync collectors
- T-061 tile matching for drops, kills, clues, level-ups, collection log, quests and achievements, plus per-clan points
- T-207 in-game panel with own bingo progress

**Left (default: build, small tickets):**
1. **Tile label helper** on the dashboard's bingo editor: when typing a tile, show whether RuneSync can tick it automatically ("auto with RuneSync: drop of Torva boots") using the same rules as `PluginObjectiveMatcher`, so organisers don't have to learn the label conventions.
2. **Board in game:** the T-207 panel shows "5 of 25 tiles". A small grid of the member's own (or their team's) board, ticked tiles highlighted, is plain components and own/team data only.
3. **Team progress in game:** in a team bingo, show the team's ticked tiles (already visible to the member on the website). Data flows into the client only.

**Rules:** only the member's own reports leave the client; team data comes into it, and the member can already see it on the website. Fine.

## 3. Live party window

**What was imagined:** a small window showing the party's HP, prayer and adrenaline, or who is where, during a boss night.

**Feasibility:**
- Each member could report their own HP/prayer every few seconds. Prayer and hitpoints are readable for the local player; adrenaline would need checking in the beta.
- Others would see it through polling, which means 5 to 10 seconds of lag and a request every few seconds per member, which is heavy for the bot.
- Showing other players' **combat state** is exactly what the guidelines restrict ("combat info overlays", "extra player info"). The data being self-reported doesn't change how a moderator sees the window.

**Default: don't build the combat version.** Offer a **"team status"** instead. Inside a running event, for members of the same event team who all opted in, it shows:
- who is in the game with RuneSync now (from their own check-ins)
- each member's event progress

There's no location, no HP, no gear. It refreshes every 60 s and reuses the T-206 panel route.

**Rules:** low risk. It's non-combat and opt-in, with data the team already sees on the website.

**Question for Drake:** Q3 below.

## 4. Overhead clan titles

**What:** above nearby players who are in your clan, draw their clan title or rank (for example "Younglings · Corporal") in the game, like clan chat's rank icons.

**Feasibility:** possible.
- The plugin can see nearby players' names and positions (`docs/game-entities.md`) and turn a tile into a screen position (`ScreenConvert.CoordGridToScreen`, `docs/game-coords.md`), so a `ui.Text` can be placed over a head.
- The bot would send the client the clan's **public roster** (names and the ranks RuneMetrics already publishes) plus the titles the clan chooses.

**Rules:** medium risk.
- Reading nearby players' names to match them is "obtaining information about other players", which is subject to review. Nothing about them is exported, and the roster is public.
- It doesn't change player models (the guidelines prohibit that) and isn't PvP info.
- It must hide in PvP areas and never show non-clan players' info.
- Expect extra review rounds. It shouldn't go into RuneSync's first submission.

**Default:** a **separate optional plugin** ("Clan Titles", depending on RuneLink), so RuneSync's review stays simple.
- Off by default, with a clan switch on the dashboard.
- Only clan members who opted in on their profile ("Show my clan title over my head") get a title.
- Hidden in PvP and in combat instances; text only, no icons from other players.

**Questions for Drake:** Q4, Q5.

## 5. What JonnyBot needs on the server (shared)

| Piece | For | Exists? |
|---|---|---|
| Per-member opt-ins (show I use RuneSync, team status, overhead title) | 1, 3, 4 | no: add columns beside `plugin_consent` or a small table |
| Clan switches on the dashboard Clan plugin page | 3, 4 | `plugin_guild_settings` (T-206) can take more columns |
| Public roster + titles feed for the client | 4 | roster exists (`ClanSyncService`); a route like `GET runesync/titles` |
| Team status from check-ins | 3 | `plugin_client_status.last_seen` (T-202) |

Every switch is **website first**, so each one has a dashboard or profile control. Everything optional ships **off**.

## 6. Questions for Drake (defaults in brackets)

1. Q1. Has-plugin list: who sees it? [clan staff on the dashboard only; members see just themselves]
2. Q2. Bingo board in game: own board only, or also the team's in team events? [both; team only in team events]
3. Q3. Party window: is the "team status" lite version (online + progress, no combat stats) enough? Or do you want to ask Jagex in the plugin Discord whether self-reported HP/prayer for event teams would pass? [lite version; ask Jagex before anything with combat stats]
4. Q4. Overhead titles: worth a separate plugin and its own review, or park until the API has clan data? [park until after RuneSync's first approval, then a separate plugin]
5. Q5. Titles source: clan rank from RuneMetrics, a custom title per member set by staff, or both? [rank, with an optional custom title]

## 7. Proposed tickets (for the master thread to number)

| Ticket | Repo | Size | Depends on |
|---|---|---|---|
| Has-plugin list (opt-in) | bot + website | S | T-202 |
| Bingo tile label helper ("auto with RuneSync") | website (+ bot route reusing the matcher) | S | T-061 |
| In-game bingo board (own/team) | RuneSync + bot (panel route extension) | M | T-207 |
| Team status in game (lite party window) | RuneSync + bot + website | M | T-206, Q3 |
| Clan Titles plugin (separate, parked) | new plugin repo + bot + website | L | Q4, Q5, RuneSync approved |

Live party with combat stats is **not proposed**; see section 3.
