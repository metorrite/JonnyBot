package com.younglings.bot.ban;

import com.younglings.bot.welcome.WelcomeConfig;
import com.younglings.bot.welcome.WelcomeConfig.MessageType;
import com.younglings.bot.welcome.WelcomeRepository;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;

/**
 * Reads and writes a server's ban announcement, one row per server. It is a {@link WelcomeConfig}: the same message (text, embed or
 * container) with the same limits and variables, so the website's shared message editor edits it. The DM copy and the link button are
 * always off here.
 */
@BService
public class BanAnnouncementRepository {
    private static final Logger log = LoggerFactory.getLogger(BanAnnouncementRepository.class);

    private final ConnectionSupplier connectionSupplier;

    public BanAnnouncementRepository(ConnectionSupplier connectionSupplier, BanAnnouncementDatabaseInitializer initializer) {
        this.connectionSupplier = connectionSupplier;
    }

    /** This server's ban announcement, or the defaults (switched off) if it has never been saved. */
    public WelcomeConfig get(long guildId) {
        String sql = "SELECT * FROM younglings.ban_announcement_config WHERE guild_id = ?";
        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, guildId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? map(rs) : defaults(guildId);
            }
        } catch (SQLException e) {
            log.error("Failed to read the ban announcement for guild {}", guildId, e);
            throw new RuntimeException("Failed to read the ban announcement", e);
        }
    }

    public void save(WelcomeConfig c) {
        String sql = """
                INSERT INTO younglings.ban_announcement_config
                    (guild_id, enabled, message_type, channel_id, content, embed_color, embed_title, embed_title_url,
                     embed_description, author_name, author_icon_url, thumbnail_url, image_url, footer_text, footer_icon_url,
                     fields_json, footer_style, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW())
                ON CONFLICT (guild_id) DO UPDATE SET
                    enabled = EXCLUDED.enabled, message_type = EXCLUDED.message_type, channel_id = EXCLUDED.channel_id,
                    content = EXCLUDED.content, embed_color = EXCLUDED.embed_color, embed_title = EXCLUDED.embed_title,
                    embed_title_url = EXCLUDED.embed_title_url, embed_description = EXCLUDED.embed_description,
                    author_name = EXCLUDED.author_name, author_icon_url = EXCLUDED.author_icon_url,
                    thumbnail_url = EXCLUDED.thumbnail_url, image_url = EXCLUDED.image_url, footer_text = EXCLUDED.footer_text,
                    footer_icon_url = EXCLUDED.footer_icon_url, fields_json = EXCLUDED.fields_json,
                    footer_style = EXCLUDED.footer_style, updated_at = NOW()
                """;
        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            int i = 1;
            statement.setLong(i++, c.guildId());
            statement.setBoolean(i++, c.enabled());
            statement.setString(i++, c.messageType().name());
            if (c.channelId() == null) statement.setNull(i++, Types.BIGINT);
            else statement.setLong(i++, c.channelId());
            statement.setString(i++, c.content());
            if (c.color() == null) statement.setNull(i++, Types.INTEGER);
            else statement.setInt(i++, c.color());
            statement.setString(i++, c.title());
            statement.setString(i++, c.titleUrl());
            statement.setString(i++, c.description());
            statement.setString(i++, c.authorName());
            statement.setString(i++, c.authorIconUrl());
            statement.setString(i++, c.thumbnailUrl());
            statement.setString(i++, c.imageUrl());
            statement.setString(i++, c.footerText());
            statement.setString(i++, c.footerIconUrl());
            statement.setString(i++, WelcomeRepository.fieldsToJson(c.fields()));
            statement.setString(i, c.footerStyle());
            statement.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to save the ban announcement for guild {}", c.guildId(), e);
            throw new RuntimeException("Failed to save the ban announcement", e);
        }
    }

    /** Off, with a starting message an admin can change. */
    public static WelcomeConfig defaults(long guildId) {
        return new WelcomeConfig(guildId, false, MessageType.EMBED, null, false, "", 0xED4245, "{username} was banned",
                "", "**{username}** has been banned from **{server}**.", "", "", "", "", "", "", List.of(), false,
                "Link your RuneScape name", "primary", "small");
    }

    private static WelcomeConfig map(ResultSet rs) throws SQLException {
        long channel = rs.getLong("channel_id");
        Long channelId = rs.wasNull() ? null : channel;
        int color = rs.getInt("embed_color");
        Integer embedColor = rs.wasNull() ? null : color;
        MessageType type;
        try {
            type = MessageType.valueOf(rs.getString("message_type"));
        } catch (RuntimeException e) {
            type = MessageType.EMBED;
        }
        return new WelcomeConfig(rs.getLong("guild_id"), rs.getBoolean("enabled"), type, channelId, false, rs.getString("content"),
                embedColor, rs.getString("embed_title"), rs.getString("embed_title_url"), rs.getString("embed_description"),
                rs.getString("author_name"), rs.getString("author_icon_url"), rs.getString("thumbnail_url"), rs.getString("image_url"),
                rs.getString("footer_text"), rs.getString("footer_icon_url"), WelcomeRepository.fieldsFromJson(rs.getString("fields_json")),
                false, "Link your RuneScape name", "primary", rs.getString("footer_style"));
    }
}
