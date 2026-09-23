package com.younglings.bot.embed;

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
public class EmbedRepository {
    private static final Logger log = LoggerFactory.getLogger(EmbedRepository.class);

    private final ConnectionSupplier connectionSupplier;

    public EmbedRepository(ConnectionSupplier connectionSupplier, EmbedDatabaseInitializer initializer) {
        this.connectionSupplier = connectionSupplier;
    }

    private static PostedEmbed map(ResultSet rs) throws SQLException {
        return new PostedEmbed(
                rs.getLong("posted_embed_id"),
                rs.getLong("guild_id"),
                rs.getLong("channel_id"),
                rs.getLong("message_id"),
                rs.getString("embed_type")
        );
    }

    public void recordPosted(long guildId, long channelId, long messageId, String embedType) {
        String sql = """
                INSERT INTO younglings.posted_embed (guild_id, channel_id, message_id, embed_type)
                VALUES (?, ?, ?, ?)
                """;

        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setLong(1, guildId);
            statement.setLong(2, channelId);
            statement.setLong(3, messageId);
            statement.setString(4, embedType);
            statement.executeUpdate();

            log.info("Recorded posted {} embed: message {} in channel {}", embedType, messageId, channelId);

        } catch (SQLException e) {
            log.error("Failed to record posted embed (type={}, channel={})", embedType, channelId, e);
            throw new RuntimeException("Failed to record posted embed", e);
        }
    }

    public List<PostedEmbed> getPostedInGuild(long guildId) {
        String sql = """
                SELECT posted_embed_id, guild_id, channel_id, message_id, embed_type
                FROM younglings.posted_embed
                WHERE guild_id = ?
                ORDER BY posted_at ASC
                """;

        List<PostedEmbed> results = new ArrayList<>();

        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setLong(1, guildId);

            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) results.add(map(rs));
            }

            return results;

        } catch (SQLException e) {
            log.error("Failed to get posted embeds for guild {}", guildId, e);
            throw new RuntimeException("Failed to get posted embeds", e);
        }
    }

    /** The oldest tracked embed of {@code embedType} in {@code channelId}, or {@code null} if none. */
    public PostedEmbed findOne(long guildId, long channelId, String embedType) {
        String sql = """
                SELECT posted_embed_id, guild_id, channel_id, message_id, embed_type
                FROM younglings.posted_embed
                WHERE guild_id = ? AND channel_id = ? AND embed_type = ?
                ORDER BY posted_at ASC
                LIMIT 1
                """;

        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setLong(1, guildId);
            statement.setLong(2, channelId);
            statement.setString(3, embedType);

            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? map(rs) : null;
            }

        } catch (SQLException e) {
            log.error("Failed to find posted embed (guild={}, channel={}, type={})", guildId, channelId, embedType, e);
            throw new RuntimeException("Failed to find posted embed", e);
        }
    }

    public void delete(long postedEmbedId) {
        String sql = "DELETE FROM younglings.posted_embed WHERE posted_embed_id = ?";

        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setLong(1, postedEmbedId);
            statement.executeUpdate();

            log.info("Removed tracked posted embed {}", postedEmbedId);

        } catch (SQLException e) {
            log.error("Failed to delete posted embed {}", postedEmbedId, e);
            throw new RuntimeException("Failed to delete posted embed", e);
        }
    }

    public void deleteAllInGuild(long guildId) {
        String sql = "DELETE FROM younglings.posted_embed WHERE guild_id = ?";

        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setLong(1, guildId);
            int deleted = statement.executeUpdate();

            log.info("Removed {} tracked posted embed(s) for guild {}", deleted, guildId);

        } catch (SQLException e) {
            log.error("Failed to delete posted embeds for guild {}", guildId, e);
            throw new RuntimeException("Failed to delete posted embeds", e);
        }
    }
}
