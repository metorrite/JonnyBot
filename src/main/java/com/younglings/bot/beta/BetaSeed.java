package com.younglings.bot.beta;

import com.younglings.bot.config.BotConfig;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The servers put on the beta list when a database is first set up, read from {@link BotConfig#BETA_SEED_GUILDS_ENV} and never from the
 * code (this repository holds no real server ids). The setting is a comma-separated list of server ids, each optionally followed by
 * {@code :label}, for example {@code <first id>:Clan A,<second id>}. Seeded rows get the column's default level, Manage
 * Server, as the first testers' servers always had.
 * <p>
 * It is applied once per database. The marker table {@code younglings.beta_guild_seeded} records that it ran, so a server removed on the
 * website later stays removed, and a database an older build already seeded (from ids that build kept in code) is left exactly as it is.
 * Unset, empty or without a usable id, nothing is seeded and no marker is written, so setting it later still works once.
 */
@BService
public class BetaSeed {
    private static final Logger log = LoggerFactory.getLogger(BetaSeed.class);
    private static final int MAX_LABEL = 100;

    /** One server to seed; {@code label} is empty when the entry had none. */
    record Entry(long guildId, String label) {}

    /** The setting read: the usable entries in order (a repeated id keeps its first label), and the 1-based positions of entries that were not. */
    record Parsed(List<Entry> entries, List<Integer> skipped) {}

    enum Outcome { NOTHING_TO_SEED, ALREADY_SEEDED, SEEDED, FAILED }

    /** Runs once at startup, after the beta tables exist. A failure is logged and never stops the bot: the list can be edited on the website. */
    public BetaSeed(ConnectionSupplier connectionSupplier, BotConfig config, BetaDatabaseInitializer schema) {
        seedOnce(connectionSupplier, config.getBetaSeedGuilds());
    }

    /** Reads the setting. An entry is a server id (15 to 20 digits) with an optional {@code :label}; blank entries are ignored. */
    static Parsed parse(String raw) {
        Map<Long, Entry> entries = new LinkedHashMap<>();
        List<Integer> skipped = new ArrayList<>();
        if (raw == null || raw.isBlank()) return new Parsed(List.of(), List.of());
        String[] parts = raw.split(",");
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i].strip();
            if (part.isEmpty()) continue;
            int colon = part.indexOf(':');
            String id = (colon < 0 ? part : part.substring(0, colon)).strip();
            String label = colon < 0 ? "" : part.substring(colon + 1).strip();
            if (label.length() > MAX_LABEL) label = label.substring(0, MAX_LABEL);
            long guildId;
            try {
                guildId = id.matches("\\d{15,20}") ? Long.parseLong(id) : -1;
            } catch (NumberFormatException tooLarge) {
                guildId = -1;
            }
            if (guildId <= 0) {
                skipped.add(i + 1);
                continue;
            }
            entries.putIfAbsent(guildId, new Entry(guildId, label));
        }
        return new Parsed(List.copyOf(entries.values()), List.copyOf(skipped));
    }

    /** Seeds the beta list from {@code raw} unless this database was seeded before. Logs counts and entry positions, never the ids. */
    static Outcome seedOnce(ConnectionSupplier connectionSupplier, String raw) {
        Parsed parsed = parse(raw);
        if (!parsed.skipped().isEmpty()) {
            log.warn("{}: entries {} are not server ids and were skipped (expected id or id:label, comma-separated).",
                    BotConfig.BETA_SEED_GUILDS_ENV, parsed.skipped());
        }
        if (parsed.entries().isEmpty()) {
            if (raw != null && !raw.isBlank()) log.warn("{} holds no usable server id; nothing was seeded.", BotConfig.BETA_SEED_GUILDS_ENV);
            return Outcome.NOTHING_TO_SEED;
        }
        try (Connection connection = connectionSupplier.getConnection()) {
            boolean auto = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                if (alreadySeeded(connection)) {
                    connection.rollback();
                    log.info("{} is set, but this database's beta list was seeded before; add or remove servers on the website.",
                            BotConfig.BETA_SEED_GUILDS_ENV);
                    return Outcome.ALREADY_SEEDED;
                }
                int added = 0;
                try (Statement marker = connection.createStatement();
                     PreparedStatement insert = connection.prepareStatement(
                             "INSERT INTO younglings.beta_guild (guild_id, label) VALUES (?, ?) ON CONFLICT (guild_id) DO NOTHING")) {
                    // Without IF NOT EXISTS: a second instance seeding at the same moment fails here and rolls back instead of seeding twice.
                    marker.execute("CREATE TABLE younglings.beta_guild_seeded (done BOOLEAN)");
                    for (Entry entry : parsed.entries()) {
                        insert.setLong(1, entry.guildId());
                        insert.setString(2, entry.label());
                        added += insert.executeUpdate();
                    }
                }
                connection.commit();
                log.info("Put {} server(s) on the beta list from {} ({} listed); this database will not be seeded again.",
                        added, BotConfig.BETA_SEED_GUILDS_ENV, parsed.entries().size());
                return Outcome.SEEDED;
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(auto);
            }
        } catch (SQLException e) {
            log.error("Could not seed the beta list from {}; add the servers on the website instead.", BotConfig.BETA_SEED_GUILDS_ENV, e);
            return Outcome.FAILED;
        }
    }

    private static boolean alreadySeeded(Connection connection) throws SQLException {
        try (Statement s = connection.createStatement();
             ResultSet rs = s.executeQuery("SELECT to_regclass('younglings.beta_guild_seeded') IS NOT NULL")) {
            return rs.next() && rs.getBoolean(1);
        }
    }
}
