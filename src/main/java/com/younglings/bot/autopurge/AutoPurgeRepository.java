package com.younglings.bot.autopurge;

import com.younglings.bot.database.SchemaBootstrapper;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

@BService
public class AutoPurgeRepository {
    private static final Logger log = LoggerFactory.getLogger(AutoPurgeRepository.class);

    private final ConnectionSupplier connectionSupplier;

    public AutoPurgeRepository(ConnectionSupplier connectionSupplier) {
        this.connectionSupplier = connectionSupplier;
        SchemaBootstrapper.run(connectionSupplier, log, "autopurge", List.of(
                "CREATE SCHEMA IF NOT EXISTS younglings;",
                """
                CREATE TABLE IF NOT EXISTS younglings.autopurge_rule (
                    guild_id BIGINT NOT NULL,
                    channel_id BIGINT NOT NULL,
                    max_age_minutes INTEGER NOT NULL,
                    keep_pinned BOOLEAN NOT NULL DEFAULT TRUE,
                    PRIMARY KEY (guild_id, channel_id)
                );
                """,
                "ALTER TABLE younglings.autopurge_rule ADD COLUMN IF NOT EXISTS authors TEXT NOT NULL DEFAULT 'ALL';",
                "ALTER TABLE younglings.autopurge_rule ADD COLUMN IF NOT EXISTS only_attachments BOOLEAN NOT NULL DEFAULT FALSE;",
                "ALTER TABLE younglings.autopurge_rule ADD COLUMN IF NOT EXISTS contains_text TEXT NULL;"));
    }

    public List<AutoPurgeRule> forGuild(long guildId) {
        return query("SELECT * FROM younglings.autopurge_rule WHERE guild_id = ? ORDER BY channel_id", guildId);
    }

    /** Every rule of every server, for the sweep. */
    public List<AutoPurgeRule> all() {
        return query("SELECT * FROM younglings.autopurge_rule ORDER BY guild_id, channel_id", null);
    }

    public void save(AutoPurgeRule rule) {
        String sql = """
                INSERT INTO younglings.autopurge_rule (guild_id, channel_id, max_age_minutes, keep_pinned, authors, only_attachments, contains_text) VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (guild_id, channel_id) DO UPDATE SET max_age_minutes = EXCLUDED.max_age_minutes, keep_pinned = EXCLUDED.keep_pinned,
                    authors = EXCLUDED.authors, only_attachments = EXCLUDED.only_attachments, contains_text = EXCLUDED.contains_text
                """;
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setLong(1, rule.guildId());
            s.setLong(2, rule.channelId());
            s.setInt(3, rule.maxAgeMinutes());
            s.setBoolean(4, rule.keepPinned());
            s.setString(5, rule.authors().name());
            s.setBoolean(6, rule.onlyWithAttachments());
            if (rule.containsText() == null) s.setNull(7, java.sql.Types.VARCHAR); else s.setString(7, rule.containsText());
            s.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to save the auto-clean rule", e);
        }
    }

    public boolean delete(long guildId, long channelId) {
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement("DELETE FROM younglings.autopurge_rule WHERE guild_id = ? AND channel_id = ?")) {
            s.setLong(1, guildId);
            s.setLong(2, channelId);
            return s.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to delete the auto-clean rule", e);
        }
    }

    private List<AutoPurgeRule> query(String sql, Long guildId) {
        List<AutoPurgeRule> rules = new ArrayList<>();
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            if (guildId != null) s.setLong(1, guildId);
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) {
                    rules.add(new AutoPurgeRule(rs.getLong("guild_id"), rs.getLong("channel_id"), rs.getInt("max_age_minutes"), rs.getBoolean("keep_pinned"),
                            AutoPurgeRule.Authors.valueOf(rs.getString("authors")), rs.getBoolean("only_attachments"), rs.getString("contains_text")));
                }
            }
            return rules;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read the auto-clean rules", e);
        }
    }
}
