# Live Discord test script

**Read first:** [ECOSYSTEM.md](ECOSYSTEM.md), [DEV-SETUP.md](DEV-SETUP.md) and [EVENT-LIFECYCLE.md](EVENT-LIFECYCLE.md).

This is for Drake to run by hand: tickets **T-049** (event features, slices 2-8) and **T-050** (the background listeners). The features were unit-tested and compiled, but their Discord side has never run for real, and every earlier Discord feature needed fixes the first time it did. Nobody else runs these steps: a thread that writes or updates this file must not click anything in Discord.

## Ground rules

- **The dev bot lives in the real clan server** (guild `1495619657674264717`). A live test is real: roles and channels it creates exist for everyone with the right permissions, and a message it deletes is gone. Keep every test small, do it in a **private test channel**, and use the cleanup steps at the end of each part.
- You need: your own account, an **alt account** that is a member of the server (no staff roles), the dev bot running as **one** instance (`powershell -ExecutionPolicy Bypass -File tools/dev-start.ps1`; the log says `Finished Loading!`), and the dashboard signed in at `http://localhost:3000/dashboard/1495619657674264717`.
- Step tags: **[READ]** only reads, safe. **[REAL]** creates, changes or deletes something real (a role, channel, message, setting, a member's roles). **[CLEAN]** undoes it. Every [REAL] step has a [CLEAN] step in its part.
- "Expected" is what you should see. If something differs, stop that feature, write down what you did, what you saw and the time (with your time zone), and file it on the ticket board with the bug form (where, how bad, when, how often, steps, expected, actual, links). Mark the step FAIL in the results table at the end. A step you could not run is SKIP, with why.
- The dev bot does **not** post tracked activity to channels by default (posting is off on a dev instance). Nothing here needs it.
- Do not run parts at the same time as the website PR work of other people: one dev bot, one database.

## Part 0: set up once

| # | Step | Expected |
|---|---|---|
| 0.1 | **[REAL]** In the server, make a private text channel `#bot-live-test`: only you, your alt and the dev bot can see it (bot: View, Send, Manage Messages, Embed Links, Read History). Copy its id (Developer Mode: right click, Copy Channel ID). | The channel exists; the alt can post in it. |
| 0.2 | Write down your Discord user id, the alt's id and the channel id. | Used by the read-only checks below. |
| 0.3 | **[READ]** `python tools/dev_api.py GET whoami --actor <your id>` | `ADMIN` (or your access level). The command reads the secret from the run configuration and never prints it. |
| 0.4 | **[READ]** `python tools/dev_api.py GET events --actor <your id>` | A list of events (probably some old test events). Note their ids so you do not touch them. |
| 0.5 | **[READ]** Dashboard > Overview and Checkup. | No red items about the bot's permissions. If the checkup says a permission is missing (Manage Roles, Manage Channels, Manage Messages, Moderate Members, View Audit Log, Ban/Kick), note it: some steps below need those. |
| 0.6 | Confirm the dev bot's role sits **above** the roles it should manage (the test roles it will create sit below the bot automatically). | Needed for Part A4 and B-role-panel. |

---

# Part A: event features (T-049)

All of Part A uses **one tiny test event** with you as the main player and the alt as a second player. Name it `ZZ Live Test` so it is easy to find and delete. Do the steps in order: each builds on the one before.

## A1. Create the event and open signups (slice 2)

| # | Step | Expected |
|---|---|---|
| A1.1 | **[REAL]** In Discord, in `#bot-live-test`: `/events create kind:Boss night name:ZZ Live Test detail:Araxxor days:1 starts_in_hours:1` | A message "New event: ZZ Live Test" with its number and how to join. Write the event number down (EVENT). The event is in the signups stage. |
| A1.2 | **[READ]** `/events open` | ZZ Live Test is in the list. |
| A1.3 | **[READ]** `python tools/dev_api.py GET events/<EVENT> --actor <your id>` | The event's JSON: stage SIGNUPS, template boss-night, config.boss Araxxor. |
| A1.4 | Dashboard > Event manager > open ZZ Live Test. | The tabs load; the checklist ("before it starts") shows build, signups, roster, verification, teams as they apply. |
| A1.5 | **[REAL]** `/events join id:<EVENT>` as you, then as the alt. | Both are told they joined. `/events mine` shows the event for each. |
| A1.6 | Dashboard > Players tab. | Both players are listed, the alt as joined on their own (not staff-added). |
| A1.7 | **[REAL]** Add a made-up player by id on the dashboard, an id that is **not** in the server. | A clear refusal that they are not in this Discord server, with the server's invite link or "ask an organiser" if no invite is set (see Server setup > Discord invite link). |
| A1.8 | **[REAL]** Take the alt off the roster on the dashboard, then add the alt back. | The roster follows: the alt disappears and comes back. If the event has a Discord signup attached, the signup list follows too. |

## A2. Close signups, confirm the roster (slice 2)

| # | Step | Expected |
|---|---|---|
| A2.1 | **[REAL]** `/events close-signups id:<EVENT>` | "signups are closed", with how many are on the roster. If a Discord signup message exists it is paused. |
| A2.2 | As the alt: `/events join id:<EVENT>` after the close. | Refused: signups have closed, ask an organiser to add you. |
| A2.3 | Dashboard > Event manager: the checklist step for the roster. | It asks you to confirm the roster. Press confirm. |
| A2.4 | **[REAL]** Add a late player (staff add) after confirming, then look at the roster step. | It goes back to "confirm it again" (a change after confirming un-confirms it). Confirm again. |
| A2.5 | **[REAL]** Reopen signups (dashboard, or `/events reopen-signups id:<EVENT>`). | Signups are open again; the roster confirmation is cleared; a paused Discord signup resumes. Close and confirm again before going on. |

## A3. Verification gate (slice 2 and 6)

Skip to A4 if you do not want to test verification. If you do:

| # | Step | Expected |
|---|---|---|
| A3.1 | **[REAL]** Dashboard > event settings: switch verification on, with a short instruction ("send a screenshot of your inventory"). Reopen signups first if the event has moved on. | Saved. `/events mine` shows the alt as "needs verification". |
| A3.2 | As the alt, in `#bot-live-test`: `/submit event-verify event:<EVENT>` with a screenshot. | The proof goes to the review place with Approve and Deny buttons. The alt gets a confirmation. |
| A3.3 | **[REAL]** Press **Deny** (give a reason). | The alt can see the reason and may send proof again. |
| A3.4 | Send proof again, press **Approve**. | The alt is verified; the roster/team steps count them from the gate's moment. |
| A3.5 | **[CLEAN]** Switch verification off again if you only wanted it for this test. | Saved. |

## A4. Teams (slice 4), optional

Only if the event is a team event (change the scoring to team on the dashboard before the event leaves signups).

| # | Step | Expected |
|---|---|---|
| A4.1 | **[REAL]** Dashboard > Team builder: make two teams, put you on one and the alt on the other (or press Suggest). | Teams saved; ratings (1-5) show; the checklist asks you to finalise. |
| A4.2 | **[REAL]** Finalise the teams. Then in Discord: `/events team list id:<EVENT>`. | The list matches the dashboard. |
| A4.3 | **[REAL]** Move the alt to the other team after finalising. | The Teams step goes back to "finalise again". Finalise again. |
| A4.4 | **[READ]** `/events team` subcommands (assign, unassign, captain) as a non-staff alt. | The alt is refused: staff and organisers only. |

## A5. Roles and channels (slice 5) **[REAL, creates a category, channels and roles]**

This is the first time it runs on Discord. Do the read-only preview first.

| # | Step | Expected |
|---|---|---|
| A5.1 | **[READ]** `python tools/dev_api.py GET events/<EVENT>/space --actor <your id>` and `python tools/dev_api.py POST events/<EVENT>/space/preview '{"enabled": true}' --actor <your id>` (a preview only reads). | The plan: what would be created (category, staff, announcements, general, board, review channels, roles, a channel per team or player if enabled), the pre-flight (bot permissions, role hierarchy, room under 250 roles / 500 channels / 50 per category). No blocking problems. |
| A5.2 | Dashboard > Channels tab: switch on roles and channels; leave per-player channels off for the first run; keep the default names. Save. | Saved; the readiness step in the checklist now asks for "Roles and channels". |
| A5.3 | Make sure signups are closed, the roster is confirmed and (team event) teams are final. | The Set up button becomes available. |
| A5.4 | Look at the preview list in the dashboard. | Same as A5.1: a dry-run list of everything to create. Read it. |
| A5.5 | **[REAL]** Press **Set up**. | A progress message; the category `ZZ Live Test` (names from the patterns), its channels and the roles appear in Discord within a few minutes. The Channels tab shows the created ids and "complete". |
| A5.6 | As the **alt** (a member with the participant role): look at the channel list. | The alt sees the announcements, general and board channels (and their own team channel) but **not** the staff or review channels or another team's channel. |
| A5.7 | As a member **without** the role (a third account, or the alt after A5.9 removes the role): look at the channel list. | Nothing from the event is visible. |
| A5.8 | As you (admin): look at the channel list. | You see everything (staff, review, every team channel). |
| A5.9 | **[REAL]** Move the alt to another team, or remove the alt from the roster, then use "Check who holds the roles" / "Bring them up to date". | The role holders follow: the alt loses or changes the team role, and the visible channels change with it. |
| A5.10 | **[REAL]** Press Set up a second time. | Nothing is duplicated: only what is missing is made (idempotent). |
| A5.11 | **Check the names and who sees what** against what you wanted. | Note any wish (names like `{event} General Channel`, staff = Admin group, per-player channels). Defaults were my guess. |

## A6. `/submit` and review (slice 6)

| # | Step | Expected |
|---|---|---|
| A6.1 | Start the event (`/events start id:<EVENT>`, or the dashboard) once the checklist allows it. | "is on". Players can score. |
| A6.2 | As the alt, in the alt's own channel (or the general channel if it is a solo event): `/submit event` and start typing the tile / objective. | The autocomplete lists the objectives the alt can still send proof for. |
| A6.3 | **[REAL]** Send `/submit event` with a screenshot. | The alt gets a confirmation; the proof appears in the review channel with Approve / Deny buttons; a receipt appears in the alt's own channel. |
| A6.4 | **[REAL]** Press **Approve**. | The receipt in the alt's channel is edited to approved; the standings and (bingo) board update. |
| A6.5 | **[REAL]** Send another proof and press **Deny** with a reason. | The receipt is edited to denied with the reason. |
| A6.6 | **[REAL]** Dashboard > event settings: switch `submissions.require` on. As the alt, try `/submit event` from `#bot-live-test` (outside the event channels). | Refused: send it from your event channel. As staff (you) it works from anywhere. |
| A6.7 | **[CLEAN]** Switch `submissions.require` back to your preference. | Saved. |

## A7. Own boards (bingo only; skip for boss night)

Make a second tiny event `ZZ Live Test Bingo` with `/events create kind:Bingo name:ZZ Live Test Bingo detail:3`, join with both accounts, set board display to "Each team or player has their own board", and set up channels as in A5.

| # | Step | Expected |
|---|---|---|
| A7.1 | **[REAL]** Press Post / refresh the boards on the Board tab. | A board picture is posted in each player's own channel, and the shared board in the board channel (if "both"). The picture is readable; fonts are not broken; numbered tiles. |
| A7.2 | **[REAL]** Approve a proof for tile 1. | The board in that player's channel redraws with tile 1 done (within a minute). |
| A7.3 | Compare the look with the RuneScape and Template looks (Board tab > Look). | Both draw. |

## A8. The player's page on the website (slice 7)

| # | Step | Expected |
|---|---|---|
| A8.1 | As the alt, sign in to the website and open `/clan-events`. | The alt's events and open events are listed, with a Join button where it applies. |
| A8.2 | Open `/clan-events/<EVENT>`. | Timeline, verification state, team, links to the event's channels, the board or objectives, the standings and what the alt has sent. |
| A8.3 | A non-participant opens `/clan-events/<EVENT>`. | No other player's private proof or Discord details are shown. |

## A9. Wrap-up (slice 8) **[REAL, deletes things]**

| # | Step | Expected |
|---|---|---|
| A9.1 | Finish the event (`/events finish id:<EVENT>`). | "has finished". With the default (lock on finish) its channels go read-only for players. |
| A9.2 | Dashboard > Wrap-up tab: check the schedule (roles back after 7 days, channels kept). | Settings show. For this test set the roles-back time to 0 days (`rolesDays 0`) so you can see it happen. |
| A9.3 | **[REAL]** Press **Export**. | A zip is made and offered for download (event.json, messages.json, a folder per category with each channel as .json and .txt, images). Open it and check it. |
| A9.4 | **[REAL]** Press **Compress** (keep the originals). | Each private channel gets a transcript file in one staff-only channel. |
| A9.5 | **[REAL]** Press **Take the roles back**. | The participant, organiser and team roles are removed from the holders. The alt no longer sees the channels. |
| A9.6 | **[REAL]** Press **Archive**. | The event's channels move into a staff-only "Event archive" category; the event's own categories are deleted. |
| A9.7 | **[REAL]** Press **Delete** and confirm. | Everything the bot made for the event (channels, roles) is removed; nothing it did not make is touched. |

## A10. Bans and DMs (slice 2 and later)

| # | Step | Expected |
|---|---|---|
| A10.1 | Make another throwaway event (or use `ZZ Live Test Bingo` before wrap-up) and join with the alt. | Joined. |
| A10.2 | **[REAL]** Dashboard > Players: **Remove and bar** the alt with a reason. | The alt is removed from the roster, team and event roles, and is told (by DM when their DMs are open). Note whether the DM arrived and whether the wording is fine. |
| A10.3 | As the alt: `/events join id:<EVENT>` and try the website Join. | Refused with the reason, by every road. |
| A10.4 | **[REAL]** Lift the ban and let the alt join again. | Works. |

## A11. Clean up Part A **[CLEAN]**

1. Delete `ZZ Live Test` and `ZZ Live Test Bingo` on the dashboard (Event manager > delete). This removes the roles and channels the bot made for them.
2. Check the server's channel and role lists for leftovers named `ZZ Live Test`. Delete by hand only what is clearly a leftover, and note it as a bug.
3. Delete the dev DB test events if you want a clean list (the dashboard delete does it).
4. Leave `#bot-live-test` for Part B.

---

# Part B: background listeners (T-050)

All steps happen in `#bot-live-test` unless it says otherwise. Do each feature on its own: set it up on the dashboard, try it, then **switch it off or remove the rule** ([CLEAN]) before the next. Each page is in the dashboard menu under the name in the heading.

## B1. Starboard (Community > Starboard)

| # | Step | Expected |
|---|---|---|
| B1.1 | **[REAL]** Make `#bot-live-test-board` (private). Starboard: switch on, channel `#bot-live-test-board`, emoji ⭐, 2 reactions needed. Save. | Saved. |
| B1.2 | Post a message in `#bot-live-test` as you. React ⭐ with you and the alt (not the author's own, unless "star their own" is on). | After the 2nd reaction the message is copied to the board channel with author, text, first picture and a jump link; a count line (⭐ 2). |
| B1.3 | Add a third ⭐ (another account or the author with self-star on). | The count on the copy goes to 3. |
| B1.4 | Remove reactions until below 2. | The copy is taken down. |
| B1.5 | Channel rules: give `#bot-live-test` its own number (3), react twice. | Not copied until 3. |
| B1.6 | **[CLEAN]** Switch the starboard off; delete the board channel. | Done. |

## B2. Automatic replies (Features > Automatic replies)

| # | Step | Expected |
|---|---|---|
| B2.1 | **[REAL]** Add a reply: trigger `live test ping`, answer `Hello {user}, this is a test reply`, cooldown 60 s. | Saved. |
| B2.2 | Post `live test ping` as the alt. | One reply that mentions only the alt. |
| B2.3 | Post it again within 60 s as you. | No second reply (cooldown, server-wide). |
| B2.4 | After the cooldown, post it in a sentence ("this is a live test ping, ok"). | Replies if the match type allows partial matches; note which behaviour you got. |
| B2.5 | Have the bot's own message or a webhook contain the trigger. | No reply (bots and webhooks never trigger one). |
| B2.6 | **[CLEAN]** Delete the reply. | Done. |

## B3. Giveaways (Community > Giveaways)

| # | Step | Expected |
|---|---|---|
| B3.1 | **[REAL]** Start a giveaway in `#bot-live-test`: prize "test prize", 1 winner, ends in 2 minutes. | A message with an Enter button and an end time. |
| B3.2 | Press Enter as you and the alt. Press it again as the alt. | First press enters ("you're in", only you see it); the second press takes the alt out. |
| B3.3 | Wait for the end. | The winner is announced (an entrant); the message updates to ended. |
| B3.4 | Dashboard: **Draw again** / reroll; then **End early** on a second giveaway; then delete. | A new winner is drawn; early end works; delete removes it. |
| B3.5 | **[CLEAN]** Delete test giveaways. | Done. |

## B4. AFK (Commands > Away status)

| # | Step | Expected |
|---|---|---|
| B4.1 | As the alt: `/afk reason:testing`. | "You're marked away". |
| B4.2 | As you, mention the alt in `#bot-live-test`. | The bot answers that the alt is away (reason shown); the reply mentions nobody and removes itself after a few seconds. |
| B4.3 | The alt sends any message. | The alt is back: no longer away (a short welcome-back note that cleans itself). |
| B4.4 | Switch on the nickname tag, `/afk` again. | `[AFK] ` goes in front of the alt's display name (needs Manage Nicknames; note if it fails for the owner or higher roles). It is removed when they speak. |
| B4.5 | Staff: `/afk user:<alt>` while away. | The alt is taken off the list. |
| B4.6 | **[CLEAN]** Switch the nickname tag off. | Done. |

## B5. Role panel (Community > Role panels)

| # | Step | Expected |
|---|---|---|
| B5.1 | **[REAL]** Make a harmless test role `ZZ Panel Role` (no permissions, below the bot). Role panels: new panel with one button for that role, post it in `#bot-live-test`. | A message with the button. |
| B5.2 | The alt presses the button. | The alt gets the role; only the alt sees the answer. |
| B5.3 | Press again. | The role is taken off. |
| B5.4 | Make a role with Administrator and add it to the panel. | Refused/never handed out as unsafe (the panel looks the role up each time). |
| B5.5 | **[CLEAN]** Delete the panel and the role(s). | Done. |

## B6. Goodbye message (Members > Goodbye message)

| # | Step | Expected |
|---|---|---|
| B6.1 | **[REAL]** Goodbye: on, channel `#bot-live-test`, text with `{user}`; use the preview. | The preview shows the line. |
| B6.2 | With the alt, leave the server (or use a throwaway third account), then rejoin. | One goodbye line is posted in the channel when it leaves. |
| B6.3 | **[CLEAN]** Switch it off. | Done. (The rejoined account may need roles back; that is not this feature.) |

## B7. Suggestions (Community > Suggestions)

| # | Step | Expected |
|---|---|---|
| B7.1 | **[REAL]** Suggestions: channel `#bot-live-test`. `/suggest idea:test idea`. | A suggestion with up and down buttons appears. |
| B7.2 | Alt votes up; you vote down; the alt presses up again; the alt presses down. | A vote counts; pressing again takes it back; the other button switches it; only the presser sees the answer. |
| B7.3 | Dashboard: approve, deny, mark implemented, with a reply. | The suggestion message updates with the status and reply. |
| B7.4 | **[CLEAN]** Remove the test suggestions. | Done. |

## B8. Moderation log (Moderation > Moderation log)

| # | Step | Expected |
|---|---|---|
| B8.1 | **[REAL]** Mod log: channel `#bot-live-test`. Time the alt out for 1 minute (Discord's own timeout), then remove the timeout. | A timeout entry and an end entry; who did it comes from the audit log (left out, not guessed, if the bot cannot read it). |
| B8.2 | Ban and unban a throwaway account (not the alt, unless you accept re-inviting them). | Ban and unban entries with the reason when given. |
| B8.3 | **[CLEAN]** Switch the log off. | Done. |

## B9. New-account gate (Moderation > New accounts)

| # | Step | Expected |
|---|---|---|
| B9.1 | **[REAL]** Gate: on, minimum age 7 days, action **Alert**, alert channel `#bot-live-test`. Join with a brand-new account if you have one. | An alert naming the account and its age; the account stays. |
| B9.2 | Optional and risky: action **Kick** with a new account. | A DM saying when it may come back, then the account is removed. Only do this with an account you do not need. |
| B9.3 | **[CLEAN]** Switch it off. | Done. |

## B10. Auto-clean (Moderation > Auto-clean), channel that deletes old messages

| # | Step | Expected |
|---|---|---|
| B10.1 | **[REAL]** Make `#bot-live-test-clean`. Add a rule: delete messages older than 1 hour, keep pinned. Use the dry run first if the page offers it. | The dry run lists what it would delete and deletes nothing. |
| B10.2 | Post messages, pin one, wait an hour (or set the shortest age and wait). The sweep runs every ten minutes. | Messages older than the age are deleted; the pinned one stays. |
| B10.3 | **[CLEAN]** Remove the rule and the channel. | Done. Never point this at a real channel. |

## B11. Auto-delete rules (Moderation > Auto-delete)

| # | Step | Expected |
|---|---|---|
| B11.1 | **[REAL]** Rule on `#bot-live-test`: Discord invites + Links + Files and images (try them one at a time). | Saved. |
| B11.2 | As the alt, post an invite link, then a normal link, then an image. | Each is deleted at once with a note saying why that removes itself after a few seconds. |
| B11.3 | As you (you can manage messages), post the same. | Not touched. |
| B11.4 | **[CLEAN]** Remove the rule. | Done. |

## B12. Automod (Moderation > Automod)

| # | Step | Expected |
|---|---|---|
| B12.1 | **[REAL]** Automod: enable Links, Blocked words (`zzbadword`), Too many capitals, Mention spam, Fast spam, Repeated messages, Emoji floods one at a time, with action "delete" first. Exempt your admin role. | Saved. |
| B12.2 | As the alt trigger each: a link; `zzbadword`; "THIS IS ALL CAPITALS HELLO"; mention three people; send five messages in two seconds; send the same message three times; 15 emoji. | Each breaking message is deleted with a "please stop ..." note that removes itself. |
| B12.3 | Set the Links rule to **warn**, trigger it again. | The message is deleted and the alt gets a recorded warning (check Moderation > Warnings). |
| B12.4 | As you (exempt) do the same. | Not touched. |
| B12.5 | **[CLEAN]** Turn all rules off. | Done. |

## B13. Warnings and Autopunish (Moderation > Warnings)

| # | Step | Expected |
|---|---|---|
| B13.1 | **[REAL]** Autopunish: 2 warnings -> timeout 5 minutes. | Saved. |
| B13.2 | `/warn add user:<alt> reason:test` twice (or via automod warn). | The alt is told by DM (when open); at the 2nd warning the alt is timed out for 5 minutes, once; a third warning does not repeat it. |
| B13.3 | `/warn list`, `/warn remove`. | List shows them; remove takes one back. |
| B13.4 | **[CLEAN]** Remove the alt's timeout, delete the warnings and the rule. | Done. Never use KICK or BAN rules in a test. |

## B14. Ticket auto-close and tickets (Tickets > Settings)

| # | Step | Expected |
|---|---|---|
| B14.1 | **[REAL]** Make a test panel (or use the dev help panels from `/dev helpsetup`, never on live) pointing to a private category. Tickets > Settings: auto-close after 1 day of silence (the minimum). | Saved. |
| B14.2 | Open a ticket as the alt and say nothing. | After the warning period a message says the ticket will close unless someone replies (a day before it closes). |
| B14.3 | Reply in the ticket. | The pending close is cancelled by the reply. |
| B14.4 | Close the ticket with the Close button. | Transcript is saved and posted to the log channel; the channel goes after the delay. |
| B14.5 | **[CLEAN]** Turn auto-close off; delete the test panel and any leftover ticket channels. | Done. |

## B15. Clean up Part B **[CLEAN]**

Delete `#bot-live-test`, `#bot-live-test-board` and `#bot-live-test-clean`; delete `ZZ Panel Role`; remove every test rule, panel, giveaway, suggestion and warning; check the Audit log page and the Overview checkup for leftovers; stop the dev bot (`tools/dev-start.ps1` stops old copies the next time it runs).

---

## Results

Copy this table into the ticket log and fill it in: PASS, FAIL (with a bug ticket id), or SKIP (with why).

| Step | Result | Note / bug ticket |
|---|---|---|
| Part 0 | | |
| A1 create + join | | |
| A2 close + roster | | |
| A3 verification | | |
| A4 teams | | |
| A5 roles + channels | | |
| A6 /submit | | |
| A7 own boards | | |
| A8 player pages | | |
| A9 wrap-up | | |
| A10 bans + DMs | | |
| B1 starboard | | |
| B2 auto replies | | |
| B3 giveaways | | |
| B4 AFK | | |
| B5 role panel | | |
| B6 goodbye | | |
| B7 suggestions | | |
| B8 mod log | | |
| B9 account gate | | |
| B10 auto-clean | | |
| B11 auto-delete | | |
| B12 automod | | |
| B13 autopunish | | |
| B14 ticket auto-close | | |
