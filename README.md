# RuneClan Bot

RuneClan Bot (formerly JonnyBot) is a Discord bot for RuneScape 3 clans: clan rosters and RuneScape tracking, events (bingo, competitions, boss nights), tickets, moderation, welcome messages, polls, points and more. Every setting has a web dashboard, and the bot serves an internal HTTP API that the RuneClans websites call.

## Build and test

Java 21 and Maven 3.9:

```
mvn verify
```

Without a database the database-backed tests skip silently, so run the full suite against a throwaway local PostgreSQL: [docs/DEV-SETUP.md](docs/DEV-SETUP.md) section 5 names the settings, and `.github/workflows/build-test.yml` shows the exact setup CI uses ([docs/CI.md](docs/CI.md)).

## Documentation

- [docs/DEV-SETUP.md](docs/DEV-SETUP.md): setting up a machine, and every environment variable the bot reads (section 3c).
- [docs/ECOSYSTEM.md](docs/ECOSYSTEM.md): how the bot, the websites and the RuneClans hub fit together, and the rules a change follows.
- [docs/BUG-REPORT.md](docs/BUG-REPORT.md): what a good bug report holds, and the read-only investigation tools.
- The other files in [docs/](docs/) are design notes for individual features.

## Names

The product is RuneClan Bot. Some technical names keep the old one for now: this repository, the jar (`JonnyBot`), the Java package `com.younglings.bot`, the database schema `younglings`, and the deprecated `JONNYBOT_*` settings (the bot reads `RUNECLAN_BOT_*` first).

## Contributing

Branch from `dev` and open pull requests into `dev`; `master` is the production deploy. Never commit secrets or real Discord ids: tests build made-up ids at runtime. A gitleaks pre-commit hook and a CI secret scan check every change (docs/DEV-SETUP.md section 3b).
