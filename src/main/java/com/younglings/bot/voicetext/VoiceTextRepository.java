package com.younglings.bot.voicetext;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@BService
public class VoiceTextRepository {
    private static final Logger log = LoggerFactory.getLogger(VoiceTextRepository.class);

    /** A server's voice-text switches. Everything is off until the server turns it on. */
    public record Config(long guildId, boolean enabled, boolean announce, boolean purgeWhenEmpty) {
        public static final int MAX_LINKS = 25;

        public static Config defaults(long guildId) {
            return new Config(guildId, false, false, false);
        }
    }

    private final ConnectionSupplier connectionSupplier;

    public VoiceTextRepository(ConnectionSupplier connectionSupplier) {
        this.connectionSupplier = connectionSupplier;
        SchemaBootstrapper.run(connectionSupplier, log, "voice text", List.of(
                "CREATE SCHEMA IF NOT EXISTS younglings;",
                """
                CREATE TABLE IF NOT EXISTS younglings.voice_text_config (
                    guild_id BIGINT PRIMARY KEY,
                    enabled BOOLEAN NOT NULL DEFAULT FALSE,
                    announce BOOLEAN NOT NULL DEFAULT FALSE,
                    purge_when_empty BOOLEAN NOT NULL DEFAULT FALSE
                );
                """,
                """
                CREATE TABLE IF NOT EXISTS younglings.voice_text_link (
                    guild_id BIGINT NOT NULL,
                    voice_channel_id BIGINT NOT NULL,
                    text_channel_id BIGINT NOT NULL,
                    PRIMARY KEY (guild_id, voice_channel_id)
                );
                """,
                // the member overwrites JonnyBot itself added, so leaving removes only those and never one a person set by hand
                """
                CREATE TABLE IF NOT EXISTS younglings.voice_text_grant (
                    guild_id BIGINT NOT NULL,
                    text_channel_id BIGINT NOT NULL,
                    user_id BIGINT NOT NULL,
                    PRIMARY KEY (guild_id, text_channel_id, user_id)
                );
                """));
    }

    public Config config(long guildId) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT enabled, announce, purge_when_empty FROM younglings.voice_text_config WHERE guild_id = ?")) {
            s.setLong(1, guildId);
            try (ResultSet rs = s.executeQuery()) {
                return rs.next() ? new Config(guildId, rs.getBoolean(1), rs.getBoolean(2), rs.getBoolean(3)) : Config.defaults(guildId);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read the voice text settings", e);
        }
    }

    public void saveConfig(Config config) {
        String sql = """
                INSERT INTO younglings.voice_text_config (guild_id, enabled, announce, purge_when_empty) VALUES (?, ?, ?, ?)
                ON CONFLICT (guild_id) DO UPDATE SET enabled = EXCLUDED.enabled, announce = EXCLUDED.announce, purge_when_empty = EXCLUDED.purge_when_empty
                """;
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setLong(1, config.guildId());
            s.setBoolean(2, config.enabled());
            s.setBoolean(3, config.announce());
            s.setBoolean(4, config.purgeWhenEmpty());
            s.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to save the voice text settings", e);
        }
    }

    /** Voice channel id to its text channel id. */
    public Map<Long, Long> links(long guildId) {
        Map<Long, Long> links = new LinkedHashMap<>();
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT voice_channel_id, text_channel_id FROM younglings.voice_text_link WHERE guild_id = ? ORDER BY voice_channel_id")) {
            s.setLong(1, guildId);
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) links.put(rs.getLong(1), rs.getLong(2));
            }
            return links;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read the voice text links", e);
        }
    }

    public Optional<Long> textFor(long guildId, long voiceChannelId) {
        return Optional.ofNullable(links(guildId).get(voiceChannelId));
    }

    /** Replaces the server's links with {@code links} (voice id to text id). */
    public void saveLinks(long guildId, Map<Long, Long> links) {
        try (Connection c = connectionSupplier.getConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement delete = c.prepareStatement("DELETE FROM younglings.voice_text_link WHERE guild_id = ?");
                 PreparedStatement insert = c.prepareStatement("INSERT INTO younglings.voice_text_link (guild_id, voice_channel_id, text_channel_id) VALUES (?, ?, ?)")) {
                delete.setLong(1, guildId);
                delete.executeUpdate();
                for (var link : links.entrySet()) {
                    insert.setLong(1, guildId);
                    insert.setLong(2, link.getKey());
                    insert.setLong(3, link.getValue());
                    insert.executeUpdate();
                }
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to save the voice text links", e);
        }
    }

    public void addGrant(long guildId, long textChannelId, long userId) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("INSERT INTO younglings.voice_text_grant (guild_id, text_channel_id, user_id) VALUES (?, ?, ?) ON CONFLICT DO NOTHING")) {
            s.setLong(1, guildId);
            s.setLong(2, textChannelId);
            s.setLong(3, userId);
            s.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to record the access grant", e);
        }
    }

    /** Forgets a grant; true if there was one (that is, JonnyBot added the access and so should take it away). */
    public boolean removeGrant(long guildId, long textChannelId, long userId) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("DELETE FROM younglings.voice_text_grant WHERE guild_id = ? AND text_channel_id = ? AND user_id = ?")) {
            s.setLong(1, guildId);
            s.setLong(2, textChannelId);
            s.setLong(3, userId);
            return s.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to clear the access grant", e);
        }
    }

    public boolean hasGrant(long guildId, long textChannelId, long userId) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT 1 FROM younglings.voice_text_grant WHERE guild_id = ? AND text_channel_id = ? AND user_id = ?")) {
            s.setLong(1, guildId);
            s.setLong(2, textChannelId);
            s.setLong(3, userId);
            try (ResultSet rs = s.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read the access grant", e);
        }
    }

    /** Everyone JonnyBot gave access to in this text channel. */
    public List<Long> grantsIn(long guildId, long textChannelId) {
        List<Long> users = new ArrayList<>();
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT user_id FROM younglings.voice_text_grant WHERE guild_id = ? AND text_channel_id = ?")) {
            s.setLong(1, guildId);
            s.setLong(2, textChannelId);
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) users.add(rs.getLong(1));
            }
            return users;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to list the access grants", e);
        }
    }
}
