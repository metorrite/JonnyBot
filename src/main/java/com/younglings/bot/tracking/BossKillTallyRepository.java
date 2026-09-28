package com.younglings.bot.tracking;

import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Locale;

/**
 * A per-(guild, player, boss) kill counter — see {@link TrackingEventClassifier}'s boss-kill branch
 * for why this exists: RuneMetrics reports every single kill, not just round-number milestones, so
 * this is what lets the Boss Kills group post only every 10th kill instead of spamming one line per
 * kill.
 */
@BService
public class BossKillTallyRepository {
    private static final Logger log = LoggerFactory.getLogger(BossKillTallyRepository.class);

    private final ConnectionSupplier connectionSupplier;

    public BossKillTallyRepository(ConnectionSupplier connectionSupplier, TrackingDatabaseInitializer initializer) {
        this.connectionSupplier = connectionSupplier;
    }

    /** Increments this player+boss's kill count and returns the new total. */
    public int incrementAndGet(long guildId, String rsn, String boss) {
        // Aliased so the ON CONFLICT clause can reference the pre-existing row's count unambiguously
        // ("bkt.kill_count") — Postgres doesn't accept the schema-qualified table name there.
        String sql = """
                INSERT INTO younglings.boss_kill_tally AS bkt (guild_id, rsn_lower, boss, kill_count)
                VALUES (?, ?, ?, 1)
                ON CONFLICT (guild_id, rsn_lower, boss) DO UPDATE SET kill_count = bkt.kill_count + 1
                RETURNING kill_count
                """;

        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setLong(1, guildId);
            statement.setString(2, rsn.toLowerCase(Locale.ROOT));
            statement.setString(3, boss);

            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return rs.getInt("kill_count");
            }

        } catch (SQLException e) {
            log.error("Failed to increment boss kill tally for guild {} rsn {} boss {}", guildId, rsn, boss, e);
            throw new RuntimeException("Failed to increment boss kill tally", e);
        }
    }
}
