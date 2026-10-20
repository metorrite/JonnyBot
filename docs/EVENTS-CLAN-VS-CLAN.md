# Clan-vs-clan event template (design)

Ticket T-058. Status: **draft with defaults** (Drake away, 2026-10-20). Builds on `docs/EVENTS-CROSS-SERVER.md` (T-059: host, co-hosts, origin) and `docs/EVENTS-HUB.md` (T-006: visibility, hub pages). Like every template, it is data over the existing engine (`EventTemplate`), not a new engine.

## 0. In one paragraph

A clan-vs-clan event is a team event where **each team is a clan**. The host invites other clans' servers as co-hosts (T-059); every accepting clan becomes a team automatically. A player's team is decided by which participating clan's in-game roster their linked RSN is on, not by a choice. The clans then compete on any objective the engine already scores (a bingo board per clan, XP, boss kills, scavenger items), with scoring that can be evened out so a 400-member clan does not win just by size. Standings show on the event page, and, when the event is Open, on the clans hub with a "Clan vs clan" history on each clan's hub page.

## 1. Template

Key `clan-vs-clan`. `fixedSettings()`: `scoringMode = TEAM`, teams managed by the template (no team builder), `teamPolicy = one team per clan`. It wraps an **inner game** chosen at creation:

| Inner game | What each clan does | Scored by |
|---|---|---|
| Bingo (default) | own board per clan (`board.display = OWN`, T-052/R26), same tiles for every clan | tiles and bonuses, as bingo |
| Competition | gain XP in a skill or overall | XP gained since the start (tracker, needs RSN) |
| Boss night | kill a boss | kill count |
| Scavenger hunt | collect the item list | items found |

Implementation note: the template delegates objectives and per-team scoring to the existing template class for the inner game and adds the team-per-clan rules on top; no copy of the bingo code.

## 2. Teams and membership

- Teams: the host's clan plus each **accepted co-host** with a clan set up. Team name = clan profile display name (falls back to the server name). Created when the co-host accepts; removed if it is removed before signups close.
- A player's team = the participating clan whose **roster** (`ClanSyncService.getRoster`) holds one of their linked RSNs. Found at join and re-checked at "Close signups".
- A player on **no** participating roster cannot join (message: "Only members of <clan list> can join this event. Link your RuneScape name with /rs if you are one.").
- A player on **two** participating rosters (rare, alt accounts): the clan of the origin server they joined through wins; if neither, the host staff choose on the roster page.
- Players still join the host Discord (D10). Team channels in the host server are one per clan (existing team channels), named by the pattern (`{event} {team}`).
- Clan leaves mid-event: its team stays, frozen (no new joins), scores kept.

## 3. Scoring that is fair across clan sizes

Option `cvc.scoring` (dashboard + Guided setup):

| Value | Meaning | Default |
|---|---|---|
| TOP_N | sum of the best N players' points per clan | **yes, N = 10** |
| AVERAGE | total / number of players who scored at least once | |
| TOTAL | plain sum (biggest clan usually wins) | |

- For bingo the board is per clan, so TOP_N and AVERAGE apply to individual contributions (points each player earned for the clan's board). The bonus points of the board count once for the clan, unchanged by the mode.
- Ties: earlier time to reach the score wins (the progress log has times).
- Option `cvc.minPlayers` (default 3): a clan with fewer players at Close signups is warned on the checklist and can be dropped by the host.

## 4. Who decides proof

Proof review stays with the **host's** staff and organisers (one queue). Option `cvc.reviewByOtherClan` (off): a co-host's organisers may review proof of **other** clans only, never their own; for when clans don't trust a single host. Organisers from co-hosts are added through the existing per-event organisers list (max 10).

## 5. Where results show

| Place | What |
|---|---|
| Event page (`/clan-events/<id>`) | clan standings, your clan's board, your contribution |
| Dashboard Standings tab | clan standings + per-player contribution inside each clan |
| Discord | `/events standings` shows clan standings; live board channel shows one board per clan (OWN boards) |
| Clans hub `/e/<id>` (Open events only, T-217) | clan standings (clan names only, never player names) |
| Clans hub clan page `c/<slug>` | new "Clan vs clan" section: past events with placing (only events that were Open and only for clans whose profile is listed) |

## 6. Dashboard and Discord (website first)

- Event manager create/Guided setup: "Clan vs clan" template card, inner game picker, scoring mode, N, min players, review-by-other-clan switch. All in `options()` so Guided/Quick/Standard streams get them.
- Co-hosts card (T-219) is required for this template (checklist step "Invite clans").
- Discord quick-create (`/events create kind:clan-vs-clan`) is **not** offered: inviting clans is a dashboard job.

## 7. Decisions made for Drake

| # | Question | Default |
|---|---|---|
| C1 | How clans take part | As co-hosts of the host's event (T-059); players join the host Discord (D10) |
| C2 | Team = clan, decided by | The in-game roster of the participating clans, via linked RSNs |
| C3 | Players in no participating clan | Can't join |
| C4 | Default inner game | Bingo with an own board per clan |
| C5 | Fair scoring | Top 10 players per clan by default; average and total as options |
| C6 | Who reviews proof | Host staff; optional "other clans review" mode, off |
| C7 | Hub leaderboard | Clan standings on the hub for Open events; clan page keeps a CvC history |
| C8 | Discord quick-create | Not offered |

## 8. Build tickets

| Ticket | What | Depends on |
|---|---|---|
| T-222 | Bot + website: `clan-vs-clan` template (inner game delegation, team per clan, roster membership, scoring modes, options, checklist step) | T-219 |
| T-223 | Website + bot feed: clan standings on the hub event page and the "Clan vs clan" history on clan pages | T-222, T-217 |
