package com.younglings.bot.announcement;

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

@BService
public class AnnouncementRepository {
    private static final Logger log = LoggerFactory.getLogger(AnnouncementRepository.class);

    private final ConnectionSupplier connectionSupplier;

    public AnnouncementRepository(ConnectionSupplier connectionSupplier, AnnouncementDatabaseInitializer initializer) {
        this.connectionSupplier = connectionSupplier;
    }

    /** {@code null} if this preset's text has never been set for this guild. */
    public String getText(long guildId, String presetKey) {
        String sql = "SELECT text FROM younglings.announcement_preset WHERE guild_id = ? AND preset_key = ?";

        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setLong(1, guildId);
            statement.setString(2, presetKey);

            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getString("text") : null;
            }

        } catch (SQLException e) {
            log.error("Failed to read announcement text for guild {} preset {}", guildId, presetKey, e);
            throw new RuntimeException("Failed to read announcement text", e);
        }
    }

    public void setText(long guildId, String presetKey, String text) {
        String sql = """
                INSERT INTO younglings.announcement_preset (guild_id, preset_key, text)
                VALUES (?, ?, ?)
                ON CONFLICT (guild_id, preset_key) DO UPDATE SET text = EXCLUDED.text
                """;

        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setLong(1, guildId);
            statement.setString(2, presetKey);
            statement.setString(3, text);
            statement.executeUpdate();

        } catch (SQLException e) {
            log.error("Failed to set announcement text for guild {} preset {}", guildId, presetKey, e);
            throw new RuntimeException("Failed to set announcement text", e);
        }
    }

    public record Destination(long id, long channelId, Long messageId) {}

    public List<Destination> getDestinations(long guildId, String presetKey) {
        String sql = "SELECT id, channel_id, message_id FROM younglings.announcement_destination WHERE guild_id = ? AND preset_key = ? ORDER BY id";

        List<Destination> results = new ArrayList<>();
        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setLong(1, guildId);
            statement.setString(2, presetKey);

            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    results.add(new Destination(rs.getLong("id"), rs.getLong("channel_id"), (Long) rs.getObject("message_id")));
                }
            }
            return results;

        } catch (SQLException e) {
            log.error("Failed to get announcement destinations for guild {} preset {}", guildId, presetKey, e);
            throw new RuntimeException("Failed to get announcement destinations", e);
        }
    }

    /** How many embeds the bot currently has posted for this guild — destinations whose message has actually been sent (a destination with no message id is configured but not posted). */
    public int countPostedEmbeds(long guildId) {
        String sql = "SELECT COUNT(*) FROM younglings.announcement_destination WHERE guild_id = ? AND message_id IS NOT NULL";

        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setLong(1, guildId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }

        } catch (SQLException e) {
            log.error("Failed to count posted embeds for guild {}", guildId, e);
            throw new RuntimeException("Failed to count posted embeds", e);
        }
    }

    /** No-ops (via the unique constraint) if this exact destination is already configured for this preset. */
    public void addDestination(long guildId, String presetKey, long channelId) {
        String sql = """
                INSERT INTO younglings.announcement_destination (guild_id, preset_key, channel_id)
                VALUES (?, ?, ?)
                ON CONFLICT (guild_id, preset_key, channel_id) DO NOTHING
                """;

        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setLong(1, guildId);
            statement.setString(2, presetKey);
            statement.setLong(3, channelId);
            statement.executeUpdate();

        } catch (SQLException e) {
            log.error("Failed to add announcement destination for guild {} preset {}", guildId, presetKey, e);
            throw new RuntimeException("Failed to add announcement destination", e);
        }
    }

    public void removeDestination(long guildId, long destinationId) {
        String sql = "DELETE FROM younglings.announcement_destination WHERE guild_id = ? AND id = ?";

        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setLong(1, guildId);
            statement.setLong(2, destinationId);
            statement.executeUpdate();

        } catch (SQLException e) {
            log.error("Failed to remove announcement destination {} for guild {}", destinationId, guildId, e);
            throw new RuntimeException("Failed to remove announcement destination", e);
        }
    }

    public void clearDestinations(long guildId, String presetKey) {
        String sql = "DELETE FROM younglings.announcement_destination WHERE guild_id = ? AND preset_key = ?";

        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setLong(1, guildId);
            statement.setString(2, presetKey);
            statement.executeUpdate();

        } catch (SQLException e) {
            log.error("Failed to clear announcement destinations for guild {} preset {}", guildId, presetKey, e);
            throw new RuntimeException("Failed to clear announcement destinations", e);
        }
    }

    /** Set once "Post / Update" actually posts (or re-finds) the message in that destination, so a later edit updates it in place. */
    public void setMessageId(long guildId, long destinationId, Long messageId) {
        String sql = "UPDATE younglings.announcement_destination SET message_id = ? WHERE guild_id = ? AND id = ?";

        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            if (messageId == null) statement.setNull(1, Types.BIGINT);
            else statement.setLong(1, messageId);
            statement.setLong(2, guildId);
            statement.setLong(3, destinationId);
            statement.executeUpdate();

        } catch (SQLException e) {
            log.error("Failed to set announcement message id for destination {} guild {}", destinationId, guildId, e);
            throw new RuntimeException("Failed to set announcement message id", e);
        }
    }
}
