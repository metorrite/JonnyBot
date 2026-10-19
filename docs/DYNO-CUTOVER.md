# Dyno cutover checklist

Dyno is being replaced one module at a time by JonnyBot. The rule that matters: **when a JonnyBot replacement is switched on for a server, the matching Dyno module must be switched off for that server**, or members get two of everything (two welcome messages, two warnings, two starboard posts).

Ledger: the Dyno Feature Ledger artifact (`features` collection) holds the per-feature status. This file is the operational checklist for the switch-off.

## Status key

- **live on master**: the code is on `master`, so it runs wherever the bot is deployed from `master`. Off per server until someone turns it on in the dashboard.
- **dev only**: on `dev` but not `master`. Nothing in this list is dev only as of 2026-10-19 (checked with `git ls-tree origin/master` for each package).
- **partial**: the replacement exists but misses part of what Dyno does. Keep the Dyno module on for the missing part, or accept the loss on purpose.
- **missing**: no replacement yet. Keep Dyno.

Checked against `origin/dev` at c1dbd93 (PR #207) and `origin/master` at 5eeefdc (PR #185). Dev is 64 commits ahead of master.

Which Dyno modules this server actually has switched on is not recorded in the repo. **Drake ticks the "On in Dyno?" column** before a switch-off; the rows below are the full set of Dyno modules that have a JonnyBot answer.

## Modules with a JonnyBot replacement

| Dyno module | JonnyBot replacement | Status | On in Dyno? | Switch-off step |
|---|---|---|---|---|
| Welcome | Welcome (`welcome` package, website only) | live on master, off until enabled | [ ] | Enable JonnyBot Welcome on the website (Bot settings > Welcome message), press Send test, decide on the DM copy, then turn Dyno Welcome off. Do the two in the same sitting. |
| Announcements (join, leave) | Welcome + Goodbye (`goodbye` package) | live on master (join and leave) | [ ] | Turn on Goodbye with the same channel as Dyno's leave message, then switch Dyno's join and leave off. |
| Announcements (ban, /announce) | none | **missing** (T-007) | [ ] | Keep Dyno's ban announcement and /announce on until T-007 ships. |
| Autoroles | Autoroles (`autorole` package) | live on master, remove-on-join **missing** (T-008) | [ ] | Copy each add rule to the website Autoroles page, check the delay, then switch the Dyno rule off. Keep any remove rule on Dyno until T-008. |
| Reaction Roles | Role panels (`rolepanel` package) | live on master, button panels only | [ ] | Re-post each panel as a button panel. Keep Dyno for dropdown and emoji-reaction panels (T-009). Remove the old Dyno message only after members have checked the new one. |
| Joinable Ranks | Self-assignable roles (website) | live on master | [ ] | Set the same roles on the website. Dyno /rank is already gone (Dyno prefix commands were removed after 2026-10-13). |
| Auto Delete | Auto delete (`autodelete` package) | live on master, invite/link/file filters only (T-010) | [ ] | Recreate each rule per channel. Switch off the Dyno rule once the filters match. Keep text and role rules on Dyno until T-010. |
| Auto Purge | Auto purge (`autopurge` package) | live on master, no message filters (T-011) | [ ] | Recreate the age-based rule. Test on a throwaway channel first: it deletes messages. Keep filtered purges on Dyno until T-011. |
| Auto Message | Scheduled posts (admin console, `ScheduledPostRunner`) | live on master | [ ] | Recreate each scheduled post, then switch the Dyno rule off. |
| Autoresponder | Automatic replies (`autoreply` package) | live on master, not yet exercised on Discord | [ ] | Run a test trigger in a private channel, then switch the Dyno autoresponder off. |
| Autoban (account age) | New account gate (`accountgate` package) | live on master, age only | [ ] | Copy the minimum age and alert channel, then switch off the Dyno age rule. |
| Autoban (username words) | none | **missing** (T-012) | [ ] | Keep Dyno's username rule on until T-012 ships. |
| Automod | Automod (`automod` package), first cut | live on master, partial (T-013) | [ ] | Map each Dyno rule to a JonnyBot rule. Switch off the ones that exist. Keep image spam, spoilers, masked links, zalgo and phishing rules on Dyno. |
| Warnings and Autopunish | Warnings (`warning` package) | live on master | [ ] | Existing Dyno warnings do not carry over. Note the counts first. Then recreate the Autopunish thresholds and switch off Dyno's. |
| Moderation (mod log, warn) | Mod log (`modlog`) and /warn | live on master, partial: no /ban, /kick, /mute cases (T-014) | [ ] | Keep Dyno's moderation commands until T-014 ships. The mod log can run alongside Dyno. |
| Giveaways | Giveaways (`giveaway` package) | live on master, partial (T-016) | [ ] | Winner DM, winner role and reaction entry are missing. Keep Dyno giveaways for events that need them. |
| Starboard | Starboard (`starboard` package) | live on master, one threshold (T-019) | [ ] | Set the same emoji and threshold, then switch the Dyno starboard off. |
| Points | Points (`points` package) | live on master, no role rewards or keyword triggers (T-018) | [ ] | Dyno points balances do not transfer. Decide whether to keep them. Switch off Dyno's points only after that call. |
| Reminders | Personal reminders (`reminder` package, /remindme) | live on master, personal only (T-020) | [ ] | Channel reminders with pings are missing. Keep Dyno reminders for channel use. |
| AFK | AFK (`afk` package) | live on master, no nickname tag, ignore list or staff clear (T-017) | [ ] | Switch off Dyno's AFK. Members lose the [AFK] nickname tag until T-017 ships. |
| Tickets | Tickets (`ticket` package) | live on master | [ ] | Re-post each ticket panel in the website's Ticket panels page, then switch off the Dyno panel. |
| Polls | Polls (`poll` package, /poll) | live on master | [ ] | Switch off Dyno /poll. |
| Suggestions | Suggestion box (`suggestion` package, /suggest) | live on master | [ ] | Point the suggestions channel at the JonnyBot one, then switch off Dyno's. |
| Action Log | Audit log relay (`tracking`) | live on master | [ ] | Route each group to the same channel, then switch off Dyno's log. Message and voice event logs are missing (T-034). |
| Command Permissions | Command channel groups (`commandchannel`) | live on master | [ ] | Recreate the allowed roles and channels per command. |
| Member Notes | Member notes (`/note` family) | live on master | [ ] | Existing Dyno notes do not transfer. Export them first if needed. |
| Manager and Moderator Roles | Admin and Support tiers (`/configure`) | live on master | [ ] | Set the same roles in /configure, then remove Dyno's manager roles. |
| Web dashboard and Dashboard change log | Admin console and Audit log page | live on master | [ ] | No switch-off step. |
| Diagnose | Bot health and Needs attention pages | live on master | [ ] | No switch-off step. |

## Dyno modules with no JonnyBot replacement yet

Keep these on Dyno. Each has a ticket or is parked.

| Dyno module | Ticket | Notes |
|---|---|---|
| Ban and mute appeals | T-033 | Planning: where the form lives. |
| Custom commands, tags | T-035 | Planning. |
| Twitch, YouTube, Kick, TikTok, Reddit feeds | T-039, T-040, T-041, T-042 | Twitch needs a Twitch app from Drake. |
| Forms | T-038 | Planning. |
| Fun commands, slowmode | T-044 | Drop or keep, Drake to decide. |
| Highlights | T-036 | Planning. |
| Levels (chat XP) | T-037 | Planning. |
| Server language | T-043 | Parked. |
| Lock, unlock, lockdown | T-015 | Ready. |
| Role persist and temp roles | T-021 | Ready. |
| Message embedder (edit and clone) | T-025 | Blocked on T-024. |
| Shared message editor | T-024 | Ready. Not a switch-off item. |
| Message and voice event logs | T-034 | Planning: needs the message content intent. |
| Voice text linking | T-027 | Ready. |
| Welcome image | T-026 | Ready. Dyno's is premium. |
| Economy and Dyno Battles | none | Marked wontdo in the ledger. Nothing replaces it. Switch it off when Drake agrees. |

## Order of switch-off

1. Welcome (done first, the most visible).
2. Announcements join and leave, once Goodbye is on.
3. Tickets, polls, suggestions, starboard, reminders: each has a full replacement with no partial loss.
4. Autoroles, autodelete, autopurge, autoresponder, autoban age rule: after a test in a private channel.
5. Automod, warnings, giveaways, points, AFK: only after the partial gaps are accepted or the rules kept on Dyno.

Each switch-off is a dashboard change plus a Dyno change. Do the JonnyBot side first, confirm it works, then switch Dyno off in the same sitting.
