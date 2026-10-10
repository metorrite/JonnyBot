package com.younglings.bot.welcome;

import com.younglings.bot.welcome.WelcomeConfig.EmbedField;
import com.younglings.bot.welcome.WelcomeConfig.MessageType;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

/** Reads and writes {@link WelcomeConfig}, one row per server. */
@BService
public class WelcomeRepository {
    private static final Logger log = LoggerFactory.getLogger(WelcomeRepository.class);

    private final ConnectionSupplier connectionSupplier;

    public WelcomeRepository(ConnectionSupplier connectionSupplier, WelcomeDatabaseInitializer initializer) {
        this.connectionSupplier = connectionSupplier;
    }

    /** This server's welcome message, or the defaults (switched off) if it has never been saved. */
    public WelcomeConfig get(long guildId) {
        String sql = "SELECT * FROM younglings.welcome_config WHERE guild_id = ?";
        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, guildId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? map(rs) : WelcomeConfig.defaults(guildId);
            }
        } catch (SQLException e) {
            log.error("Failed to read the welcome message for guild {}", guildId, e);
            throw new RuntimeException("Failed to read the welcome message", e);
        }
    }

    public void save(WelcomeConfig config) {
        String sql = """
                INSERT INTO younglings.welcome_config
                    (guild_id, enabled, message_type, channel_id, also_dm, content, embed_color, embed_title, embed_title_url,
                     embed_description, author_name, author_icon_url, thumbnail_url, image_url, footer_text, footer_icon_url,
                     fields_json, link_button, link_button_label, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW())
                ON CONFLICT (guild_id) DO UPDATE SET
                    enabled = EXCLUDED.enabled, message_type = EXCLUDED.message_type, channel_id = EXCLUDED.channel_id,
                    also_dm = EXCLUDED.also_dm, content = EXCLUDED.content, embed_color = EXCLUDED.embed_color,
                    embed_title = EXCLUDED.embed_title, embed_title_url = EXCLUDED.embed_title_url,
                    embed_description = EXCLUDED.embed_description, author_name = EXCLUDED.author_name,
                    author_icon_url = EXCLUDED.author_icon_url, thumbnail_url = EXCLUDED.thumbnail_url,
                    image_url = EXCLUDED.image_url, footer_text = EXCLUDED.footer_text, footer_icon_url = EXCLUDED.footer_icon_url,
                    fields_json = EXCLUDED.fields_json, link_button = EXCLUDED.link_button,
                    link_button_label = EXCLUDED.link_button_label, updated_at = NOW()
                """;
        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            int i = 1;
            statement.setLong(i++, config.guildId());
            statement.setBoolean(i++, config.enabled());
            statement.setString(i++, config.messageType().name());
            if (config.channelId() == null) statement.setNull(i++, Types.BIGINT);
            else statement.setLong(i++, config.channelId());
            statement.setBoolean(i++, config.alsoDm());
            statement.setString(i++, config.content());
            if (config.color() == null) statement.setNull(i++, Types.INTEGER);
            else statement.setInt(i++, config.color());
            statement.setString(i++, config.title());
            statement.setString(i++, config.titleUrl());
            statement.setString(i++, config.description());
            statement.setString(i++, config.authorName());
            statement.setString(i++, config.authorIconUrl());
            statement.setString(i++, config.thumbnailUrl());
            statement.setString(i++, config.imageUrl());
            statement.setString(i++, config.footerText());
            statement.setString(i++, config.footerIconUrl());
            statement.setString(i++, fieldsToJson(config.fields()));
            statement.setBoolean(i++, config.linkButton());
            statement.setString(i, config.linkButtonLabel());
            statement.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to save the welcome message for guild {}", config.guildId(), e);
            throw new RuntimeException("Failed to save the welcome message", e);
        }
    }

    private static WelcomeConfig map(ResultSet rs) throws SQLException {
        long channel = rs.getLong("channel_id");
        Long channelId = rs.wasNull() ? null : channel;
        int color = rs.getInt("embed_color");
        Integer embedColor = rs.wasNull() ? null : color;
        return new WelcomeConfig(rs.getLong("guild_id"), rs.getBoolean("enabled"), parseType(rs.getString("message_type")), channelId,
                rs.getBoolean("also_dm"), rs.getString("content"), embedColor, rs.getString("embed_title"), rs.getString("embed_title_url"),
                rs.getString("embed_description"), rs.getString("author_name"), rs.getString("author_icon_url"),
                rs.getString("thumbnail_url"), rs.getString("image_url"), rs.getString("footer_text"), rs.getString("footer_icon_url"),
                fieldsFromJson(rs.getString("fields_json")), rs.getBoolean("link_button"), rs.getString("link_button_label"));
    }

    private static MessageType parseType(String raw) {
        try {
            return MessageType.valueOf(raw);
        } catch (RuntimeException e) {
            return MessageType.EMBED_TEXT;
        }
    }

    static String fieldsToJson(List<EmbedField> fields) {
        DataArray array = DataArray.empty();
        for (EmbedField field : fields) {
            array.add(DataObject.empty().put("name", field.name()).put("value", field.value()).put("inline", field.inline()));
        }
        return array.toString();
    }

    static List<EmbedField> fieldsFromJson(String json) {
        List<EmbedField> fields = new ArrayList<>();
        if (json == null || json.isBlank()) return fields;
        try {
            DataArray array = DataArray.fromJson(json);
            for (int i = 0; i < array.length(); i++) {
                DataObject o = array.getObject(i);
                fields.add(new EmbedField(o.getString("name", ""), o.getString("value", ""), o.getBoolean("inline", false)));
            }
        } catch (RuntimeException e) {
            log.warn("Stored welcome fields couldn't be read; ignoring them", e);
        }
        return fields;
    }
}
