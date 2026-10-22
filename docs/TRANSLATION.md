# Translating JonnyBot

A message catalogue, a per-server language setting and a fallback to English. **No language is shipped yet.** The bot's text is being moved onto the catalogue area by area (T-098, see "Where the text is" below); the language packs are T-099.

## How it works

**Bot** (`com.younglings.bot.i18n`)

- `Messages` (a `@BService`) looks a message up by key: `messages.text(guild, "split.people.range", 25)`. The language is the server's setting (`LanguageRepository`, table `younglings.guild_language`; no row means English).
- Lookup order: the server's language, then English, then the key itself (and a warning in the log). A test (`MessagesTest`) fails the build if code asks for a key English lacks, or if a language pack has different keys or `{0}` placeholders than English, so a user should never see a key.
- Catalogues are Java properties files in `src/main/resources/i18n/`, UTF-8: `messages_en.properties` is the source of truth. Placeholders are `{0}`, `{1}`, ... filled in order (plain replacement, no other escaping, so apostrophes are just text).
- The dashboard's **Language** page (`/dashboard/<server>/language`, bot route `language`) lists the languages that exist and saves the server's choice. English is the only one today, so the list has one entry.
- `/split` is the sample that was moved onto the catalogue, to prove the path end to end.
- `Text` is the same catalogue for code without a `Messages` of its own (T-098): `Text.of(event, "poll.this_command_can_only_be_used")` uses the language of the server the interaction came from (English in a DM), `Text.of(guild, ...)` / `Text.of(guildId, ...)` a server's, `Text.en(...)` English. It is backed by the bot's `Messages` once that exists and reads the English file directly before that, so tests that never make a `Messages` get exactly the English text. No constructor needs a new parameter.

**Website** (`src/lib/i18n`)

- `messages.en.ts` holds the English text by key (`dashboard.language.title`, ...); `t(language, key, ...args)` looks a key up in the language's pack and falls back to English. `getDashboardLanguage(guild)` reads the server's language from the bot, so a page translates itself with the server's setting. The Language page uses it for its own text as the sample.

## Adding a string

1. Bot: add `feature.what=English text` to `messages_en.properties` and use `Text.of(event, "feature.what", args)` (or `messages.text(guild, ...)` where a `Messages` is at hand). Keys are lower case, dotted, with `_` inside a part (`poll.you_already_voted`); they must be plain string literals in the call so the test can see them (both sides of a ternary are checked).
2. Website: add the key to `messages.en.ts` and call `t(language, "key")`.
3. Run `mvn -o test -Dtest=MessagesTest`.

## Adding a language pack (later)

1. Bot: add `i18n/messages_<code>.properties` with **every** key English has (same `{0}` placeholders), and a line `<code>=Name in that language` to `i18n/languages.properties`. It then appears on the Language page by itself.
2. Website: add `messages.<code>.ts` (same keys) and register it in `src/lib/i18n/index.ts`.
3. Missing keys are caught by the tests; until a pack is complete, the unlisted keys would show English, but the test insists on completeness so nothing is half translated.

## What is not covered

Discord's own slash-command names and descriptions (they are registered once and are not per-server), user-written text (tickets, welcome messages), and dates/number formats. Those need their own decisions before a real language pack.

## Where the text is (T-098)

Moved onto the catalogue, one PR per area: **commands** (every `/command` reply in `commands/`, except tickets and events, which have their own slices). The English is unchanged: each key's text is exactly what the code used to send, with `{0}`, `{1}`, ... where values were joined in.

Still in the code, to move in later slices: the rest of the bot's areas. Not meant to move: slash-command names and descriptions (registered once with Discord, not per server), audit-log reasons and log lines (read by staff and the bot's owners, not players), and text people write themselves.

The mechanical part of a slice is done with `CLAUDE_RESOURCES/notes/merge-train/catalogue_move.py <worktree> <area> <folder>`: it moves literal text in the `Containers.reply` / `edit` helpers and lists what it couldn't (text built in variables, a sum before the first text), which is then moved by hand.
