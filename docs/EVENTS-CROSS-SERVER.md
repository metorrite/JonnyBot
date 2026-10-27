# Cross-server and alliance events, and the fill queue (design)

Ticket T-059. Status: **draft with defaults** (Drake away, 2026-10-20). Everything here is opt-in per event and **off by default**. Read with `docs/EVENTS-HUB.md` (T-006: visibility and the hub) and `docs/EVENTS-CLAN-VS-CLAN.md` (T-058, which builds on this).

## 0. In one paragraph

One server **hosts** an event; servers in the same alliance can be invited as **co-hosts**. A co-host gets a signup panel in its own Discord and a read-only view of the event on its own dashboard, but every role, channel, board and proof review stays in the host server, and every player still joins the host Discord to take part (D10). Each player remembers which server they came through (their **origin**), which feeds the team-mixing rules that already exist and a fairer fill queue: optional seat quotas per server and an optional round-robin waitlist.

## 1. What exists

- Alliances: `alliance`, `alliance_member (alliance_id, guild_id, status)`, admin routes `alliances/*`, Alliances dashboard page, hub alliance pages (T-069).
- `EventKinds.kindOf(host, userGuildId, user)` already returns PARTNER when `userGuildId != host`, but today every join passes the host's id, so PARTNER never happens.
- `TeamRules` policies (who may join, which kinds may share a team) and `QueueRules.Policy { FIRST_COME, CLAN_FIRST }` with a waitlist.
- Scope ALLIANCE on events (stored, unused until T-216).
- Roles/channels (`EventSpace*`) operate on the host guild only.

## 2. Host model

| Role | Who | Can do |
|---|---|---|
| Host server | the server that made the event | everything today: settings, roster, teams, channels, review, wrap-up |
| Co-host server | an accepted member of an alliance the host is in, invited to this event and accepted by that server's admins | post a signup panel in its own server; see the event, its own players and their status on its dashboard; remove its own players before the roster is confirmed; nothing else |
| Partner player | a player who joined through a co-host (origin = co-host) | same as any player once in the host Discord |

- Co-host invitations only go to servers that share an **accepted** alliance with the host at invite time. Leaving the alliance does not remove an accepted co-host from a running event (it does stop new invites).
- A co-host's admins never see other servers' players by Discord id; they see count + their own players.
- Host staff can remove a co-host before signups close; afterwards only by removing its players by hand.

## 3. Origin and joining

- New column `event_participant.origin_guild_id` (null = host). Set by the road the player used: the co-host's panel, or the website/hub Join with "I'm joining with <server>" picked from servers the person shares with the event's co-hosts (default: host).
- `EventKinds` uses `origin_guild_id`, so PARTNER finally means something: the existing `TeamRules` policies ("partners on their own teams", "everyone mixes", ...) apply unchanged.
- **Joining from a co-host panel:** the bot is in both servers, so it checks host membership directly. In the host server -> joined at once. Not in it -> an ephemeral reply with the host's event invite link (T-216 `events.inviteUrl`) and a **pending join** is saved; when they join the host server (member-join listener) the join completes and they get a DM-free notice in the event's general channel only if channels exist (otherwise nothing). Pending joins expire at the signup deadline.
- Signup panels in co-host servers are normal RuneClan Bot signup messages created and removed by the host's event lifecycle (opened, paused at close, deleted at DONE), so the existing pause/redraw code is reused.

## 4. Fill queue

| Policy | Today | Behaviour |
|---|---|---|
| FIRST_COME | yes (default) | waitlist in join order |
| CLAN_FIRST | yes | host clan members first, then join order |
| **ROUND_ROBIN** (new) | | waitlist interleaves origins: host, co-host A, co-host B, host, ... each in join order; a server with nobody waiting is skipped |
| **QUOTA** (new, combinable with any policy above) | | each server (host and each co-host) has a seat quota; a player beyond their server's quota waits; at the **release time** (default 24 h before signups close, or at close if no deadline) unfilled seats go to the general waitlist in the base policy's order |

- Pure rules in `QueueRules` (tested like today); quotas stored on `event_cohost.quota` and `config.queue.hostQuota`.
- Limits: quotas must add up to at most `maxParticipants` (the dashboard shows the sum); no quota = unlimited for that server.
- Staff late-adds ignore quotas (as today staff add ignores the deadline).

## 5. Roles and channels

- Only in the host server, unchanged. Partner players get roles there because they are members (D10).
- Co-host servers get **no** roles or channels made. Optional (off): when the event starts, post the event's announcement (name, start, host invite) in the co-host's panel channel.
- Discord limits are per server, so co-hosting does not raise the host's role/channel use beyond the extra players.

## 6. Dashboard and Discord (website first)

| Setting / action | Host dashboard | Co-host dashboard | Discord |
|---|---|---|---|
| Invite / remove co-hosts | Event manager, new "Co-hosts" card (alliance servers to pick from) | | none (v1) |
| Accept / decline an invitation | | Events page: "Invitations" list | none (v1) |
| Panel channel for the co-host's signup | | on accepting (channel picker) | |
| Queue policy + quotas + release time | "Co-hosts" card and Guided setup (EventOptions) | quota shown read-only | |
| See the event and own players; remove own players before roster confirm | | "Events you co-host" list | |
| Start announcement in co-host servers | "Co-hosts" card switch, **off** | | |
| Join | website/hub Join with origin picker | | co-host signup panel button |

## 7. Data

- `event_cohost (event_id, guild_id, status INVITED|ACCEPTED|DECLINED|REMOVED, quota NULL, panel_channel_id, signup_id, invited_by, decided_by, created_at, decided_at)`.
- `event_participant.origin_guild_id BIGINT NULL`.
- `event_pending_join (event_id, user_id, origin_guild_id, created_at, expires_at)`.
- Admin routes `events/{id}/cohosts` (host), `cohost-events` (co-host list, accept/decline, own players). Every route checks the calling guild's relation to the event; this access check is the risky part and gets its own tests (host, accepted co-host, invited co-host, stranger, former alliance member).

## 8. Decisions made for Drake

| # | Question | Default |
|---|---|---|
| X1 | Who can co-host | Accepted members of an alliance the host is in, invited per event and accepting |
| X2 | Where roles/channels live | Host server only; co-hosts get a signup panel and nothing else |
| X3 | Do partner players join the host Discord | Yes (D10); co-host panel saves a pending join that completes when they join |
| X4 | What co-host admins see | The event, counts, their own players; never other servers' players |
| X5 | New queue policies | ROUND_ROBIN and QUOTA (+ release time 24 h before close); default stays FIRST_COME, no quotas |
| X6 | Discord commands for co-hosting | None in v1; dashboard only |
| X7 | Start announcement in co-host servers | Built, off |
| X8 | Leaving the alliance mid-event | Accepted co-hosts stay for that event; no new invites |

## 9. Build tickets

| Ticket | What | Depends on |
|---|---|---|
| T-219 | Bot + website: co-host model, origin, access checks, Co-hosts card, co-host Events list | T-216 |
| T-220 | Bot + website: ROUND_ROBIN and QUOTA queue policies with release time | T-219 |
| T-221 | Bot + website: co-host signup panels, pending joins, optional start announcement | T-219 |
