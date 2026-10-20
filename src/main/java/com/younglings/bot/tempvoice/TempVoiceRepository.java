package com.younglings.bot.tempvoice;

import com.younglings.bot.database.SchemaBootstrapper;
import com.younglings.bot.tempvoice.TempVoiceRules.Hub;
import com.younglings.bot.tempvoice.TempVoiceRules.Temp;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Temporary voice channels: the hubs a server set up and the channels made from them. Channels are kept in the database so they are found again
 * (and cleaned up) after a restart. Hubs are read on every voice join, so they are cached by channel id in memory-free fashion: one indexed query.
 */
@BService
public class TempVoiceRepository {
    private static final Logger log = LoggerFactory.getLogger(TempVoiceRepository.class);

    private final ConnectionSupplier connectionSupplier;

    public TempVoiceRepository(ConnectionSupplier connectionSupplier) {
        this.connectionSupplier = connectionSupplier;
        SchemaBootstrapper.run(connectionSupplier, log, "temp voice", List.of(
                "CREATE SCHEMA IF NOT EXISTS younglings;",
                """
                CREATE TABLE IF NOT EXISTS younglings.tempvoice_hub (
                    hub_channel_id BIGINT PRIMARY KEY,
                    guild_id BIGINT NOT NULL,
                    category_id BIGINT NULL,
                    name_pattern TEXT NOT NULL DEFAULT '{user}''s channel',
                    user_limit INTEGER NOT NULL DEFAULT 0
                );
                """,
                "CREATE INDEX IF NOT EXISTS tempvoice_hub_guild ON younglings.tempvoice_hub (guild_id);",
                """
                CREATE TABLE IF NOT EXISTS younglings.tempvoice_channel (
                    channel_id BIGINT PRIMARY KEY,
                    guild_id BIGINT NOT NULL,
                    owner_id BIGINT NOT NULL,
                    hub_channel_id BIGINT NOT NULL,
                    locked BOOLEAN NOT NULL DEFAULT FALSE,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
                );
                """,
                "CREATE INDEX IF NOT EXISTS tempvoice_channel_guild ON younglings.tempvoice_channel (guild_id, owner_id);"));
    }

    // ---------- hubs ----------

    public List<Hub> hubs(long guildId) {
        List<Hub> out = new ArrayList<>();
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT hub_channel_id, guild_id, category_id, name_pattern, user_limit FROM younglings.tempvoice_hub WHERE guild_id = ? ORDER BY hub_channel_id")) {
            s.setLong(1, guildId);
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) out.add(map(rs));
            }
            return out;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read the temporary voice hubs", e);
        }
    }

    /** The hub that is this channel, or empty. Asked for on every voice join, so it is one primary-key lookup. */
    public Optional<Hub> hubByChannel(long hubChannelId) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT hub_channel_id, guild_id, category_id, name_pattern, user_limit FROM younglings.tempvoice_hub WHERE hub_channel_id = ?")) {
            s.setLong(1, hubChannelId);
            try (ResultSet rs = s.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read a temporary voice hub", e);
        }
    }

    /** Adds the hub or changes its settings. */
    public void saveHub(Hub hub) {
        String sql = """
                INSERT INTO younglings.tempvoice_hub (hub_channel_id, guild_id, category_id, name_pattern, user_limit) VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (hub_channel_id) DO UPDATE SET category_id = EXCLUDED.category_id, name_pattern = EXCLUDED.name_pattern, user_limit = EXCLUDED.user_limit
                WHERE younglings.tempvoice_hub.guild_id = EXCLUDED.guild_id
                """;
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setLong(1, hub.hubChannelId());
            s.setLong(2, hub.guildId());
            if (hub.categoryId() == null) s.setNull(3, Types.BIGINT); else s.setLong(3, hub.categoryId());
            s.setString(4, hub.namePattern());
            s.setInt(5, hub.userLimit());
            s.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to save the temporary voice hub", e);
        }
    }

    public boolean deleteHub(long guildId, long hubChannelId) {
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement("DELETE FROM younglings.tempvoice_hub WHERE guild_id = ? AND hub_channel_id = ?")) {
            s.setLong(1, guildId);
            s.setLong(2, hubChannelId);
            return s.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to delete the temporary voice hub", e);
        }
    }

    private static Hub map(ResultSet rs) throws SQLException {
        long category = rs.getLong(3);
        boolean noCategory = rs.wasNull();
        return new Hub(rs.getLong(2), rs.getLong(1), noCategory ? null : category, rs.getString(4), rs.getInt(5));
    }

    // ---------- temporary channels ----------

    public void addChannel(Temp temp) {
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(
                "INSERT INTO younglings.tempvoice_channel (channel_id, guild_id, owner_id, hub_channel_id, locked) VALUES (?, ?, ?, ?, ?) ON CONFLICT DO NOTHING")) {
            s.setLong(1, temp.channelId());
            s.setLong(2, temp.guildId());
            s.setLong(3, temp.ownerId());
            s.setLong(4, temp.hubChannelId());
            s.setBoolean(5, temp.locked());
            s.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to record the temporary channel", e);
        }
    }

    public Optional<Temp> channel(long channelId) {
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(
                "SELECT channel_id, guild_id, owner_id, hub_channel_id, locked FROM younglings.tempvoice_channel WHERE channel_id = ?")) {
            s.setLong(1, channelId);
            try (ResultSet rs = s.executeQuery()) {
                return rs.next() ? Optional.of(new Temp(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getBoolean(5))) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read the temporary channel", e);
        }
    }

    /** The channel an owner already has in a server, if any. */
    public Optional<Temp> channelOwnedBy(long guildId, long ownerId) {
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(
                "SELECT channel_id, guild_id, owner_id, hub_channel_id, locked FROM younglings.tempvoice_channel WHERE guild_id = ? AND owner_id = ? ORDER BY created_at LIMIT 1")) {
            s.setLong(1, guildId);
            s.setLong(2, ownerId);
            try (ResultSet rs = s.executeQuery()) {
                return rs.next() ? Optional.of(new Temp(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getBoolean(5))) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read the temporary channel", e);
        }
    }

    public List<Temp> channels(long guildId) {
        return query("SELECT channel_id, guild_id, owner_id, hub_channel_id, locked FROM younglings.tempvoice_channel WHERE guild_id = ?", guildId);
    }

    /** Every temporary channel of every server, for the clean-up after a restart. */
    public List<Temp> allChannels() {
        return query("SELECT channel_id, guild_id, owner_id, hub_channel_id, locked FROM younglings.tempvoice_channel", null);
    }

    public int count(long guildId) {
        return channels(guildId).size();
    }

    public void setOwner(long channelId, long ownerId) {
        update("UPDATE younglings.tempvoice_channel SET owner_id = ? WHERE channel_id = ?", ownerId, channelId);
    }

    public void setLocked(long channelId, boolean locked) {
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement("UPDATE younglings.tempvoice_channel SET locked = ? WHERE channel_id = ?")) {
            s.setBoolean(1, locked);
            s.setLong(2, channelId);
            s.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to update the temporary channel", e);
        }
    }

    public void deleteChannel(long channelId) {
        update("DELETE FROM younglings.tempvoice_channel WHERE channel_id = ?", channelId);
    }

    private void update(String sql, long... values) {
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) s.setLong(i + 1, values[i]);
            s.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to update the temporary channel", e);
        }
    }

    private List<Temp> query(String sql, Long guildId) {
        List<Temp> out = new ArrayList<>();
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            if (guildId != null) s.setLong(1, guildId);
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) out.add(new Temp(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getBoolean(5)));
            }
            return out;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read the temporary channels", e);
        }
    }
}
