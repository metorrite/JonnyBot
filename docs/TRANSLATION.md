# Translating JonnyBot

A message catalogue, a per-server language setting and a fallback to English. The bot's own text is on the catalogue (T-098, see "Where the text is"). Three **machine-drafted** language packs ship **off** (T-099, see "Language packs"): German, French and Brazilian Portuguese.

## How it works

**Bot** (`com.younglings.bot.i18n`)

- `Messages` (a `@BService`) looks a message up by key: `messages.text(guild, "split.people.range", 25)`. The language is the server's setting (`LanguageRepository`, table `younglings.guild_language`; no row means English).
- Lookup order: the server's language, then English, then the key itself (and a warning in the log). A test (`MessagesTest`) fails the build if code asks for a key English lacks, or if a language pack has different keys or `{0}` placeholders than English, so a user should never see a key.
- Catalogues are Java properties files in `src/main/resources/i18n/`, UTF-8: `messages_en.properties` is the source of truth. Placeholders are `{0}`, `{1}`, ... filled in order (plain replacement, no other escaping, so apostrophes are just text).
- The dashboard's **Language** page (`/dashboard/<server>/language`, bot route `language`) lists the languages a server may pick and saves its choice. With the packs off (the default) that is English alone.
- `/split` is the sample that was moved onto the catalogue, to prove the path end to end.
- `Text` is the same catalogue for code without a `Messages` of its own (T-098): `Text.of(event, "poll.this_command_can_only_be_used")` uses the language of the server the interaction came from (English in a DM), `Text.of(guild, ...)` / `Text.of(guildId, ...)` a server's, `Text.en(...)` English. It is backed by the bot's `Messages` once that exists and reads the English file directly before that, so tests that never make a `Messages` get exactly the English text. No constructor needs a new parameter.

**Website** (`src/lib/i18n`)

- `messages.en.ts` holds the English text by key (`dashboard.language.title`, ...); `t(language, key, ...args)` looks a key up in the language's pack and falls back to English. `getDashboardLanguage(guild)` reads the server's language from the bot, so a page translates itself with the server's setting. The Language page uses it for its own text as the sample.

## Adding a string

1. Bot: add `feature.what=English text` to `messages_en.properties` and use `Text.of(event, "feature.what", args)` (or `messages.text(guild, ...)` where a `Messages` is at hand). Keys are lower case, dotted, with `_` inside a part (`poll.you_already_voted`); they must be plain string literals in the call so the test can see them (both sides of a ternary are checked).
2. Website: add the key to `messages.en.ts` and call `t(language, "key")`.
3. Run `mvn -o test -Dtest=MessagesTest`.

## Language packs (T-099)

- **Which:** German (`de`), French (`fr`) and Brazilian Portuguese (`pt-BR`): RuneScape's own client languages, so the game terms players see match. Drafted by Claude; **no speaker has checked them yet**.
- **Off by default:** `LANGUAGE_PACKS_ENABLED=true` (docs/DEV-SETUP.md 3c) offers them on the Language page. Off, only English can be picked; a server that already picked a pack keeps it.
- **Marked as drafts:** they are listed under `drafts=` in `i18n/languages.properties`, their names on the Language page say "(machine-drafted)", and the first line of each file says so. Take a code off `drafts=` (and the words off its name) once a speaker has checked the pack.
- **What they cover:** every player-facing key (commands, tickets, events, the listeners). The `dashboard.*` keys are left out on purpose: the dashboard itself is still English until the website moves into the runeclans repo, and a translated error on an English page reads worse than English. A draft may lack keys; English shows for those, so a new string never blocks a PR.
- **Reporting a bad translation:** players tell staff; staff file it on the board's bug form with the language, what it said and what it should say. Fixing one is editing that line of `messages_<code>.properties` (keep every `{0}`); `MessagesTest` checks the placeholders. A report link on the Language page itself waits for the website move.
- **Rebuilding a pack** from translated part files: `CLAUDE_RESOURCES/notes/merge-train/build_pack.py` (keeps English's order and headings, refuses unknown keys and changed placeholders).

## Adding a language pack

1. Bot: add `i18n/messages_<code>.properties` (same keys as English, same `{0}` placeholders) and a line `<code>=Name in that language` to `i18n/languages.properties`. A machine-drafted pack also goes on the `drafts=` line.
2. Website: add `messages.<code>.ts` (same keys) and register it in `src/lib/i18n/index.ts`.
3. `MessagesTest` fails on a key English lacks or a changed placeholder. A finished pack must have every key; a draft may lack some.

## What is not covered

Discord's own slash-command names and descriptions (they are registered once and are not per-server), user-written text (tickets, welcome messages), and dates/number formats. Those need their own decisions before a real language pack.

## Where the text is (T-098)

Moved onto the catalogue, one PR per area: **commands** (#348), **tickets** (#350), **events** with every `EventProblem` (#351), **the other interaction listeners** (#352) and **the dashboard API's messages** (#353). The English is unchanged: each key's text is exactly what the code used to send, with `{0}`, `{1}`, ... where values were joined in. A few messages raised by parsing helpers with no server in reach use `Text.en`: their keys exist but stay English.

Still in the code: modal field labels (`Label.of`), scheduled posts (digests, reports, wrap-ups) and other text built away from an interaction or API call; they move when those areas are next touched. Not meant to move: slash-command names and descriptions (registered once with Discord, not per server), audit-log reasons and log lines (read by staff and the bot's owners, not players), text people write themselves, and default content seeded for a server that its admins then edit. The website's strings move after it joins the runeclans repo.

The mechanical part of a slice is done with `CLAUDE_RESOURCES/notes/merge-train/catalogue_move.py <worktree> <area> <folder>`: it moves literal text in the `Containers.reply` / `edit` helpers and lists what it couldn't (text built in variables, a sum before the first text), which is then moved by hand.
