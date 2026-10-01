package com.younglings.bot.runescape;

import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

/** Backs {@code TrackingIconCatalog}'s detection of a managed Discord application emoji whose source image has changed since it was last uploaded — see the {@code icon_emoji_hash} table comment. */
@BService
public class IconEmojiHashRepository {
    private static final Logger log = LoggerFactory.getLogger(IconEmojiHashRepository.class);

    private final ConnectionSupplier connectionSupplier;

    public IconEmojiHashRepository(ConnectionSupplier connectionSupplier, RuneScapeDatabaseInitializer initializer) {
        this.connectionSupplier = connectionSupplier;
    }

    /** Every recorded emoji name -> content hash, fetched once per sync pass rather than once per emoji. */
    public Map<String, String> getAllHashes() {
        String sql = "SELECT emoji_name, content_hash FROM younglings.icon_emoji_hash";

        Map<String, String> results = new HashMap<>();
        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rs = statement.executeQuery()) {

            while (rs.next()) results.put(rs.getString("emoji_name"), rs.getString("content_hash"));
            return results;

        } catch (SQLException e) {
            log.error("Failed to load icon emoji hashes", e);
            throw new RuntimeException("Failed to load icon emoji hashes", e);
        }
    }

    public void setHash(String emojiName, String contentHash) {
        String sql = """
                INSERT INTO younglings.icon_emoji_hash (emoji_name, content_hash, updated_at)
                VALUES (?, ?, NOW())
                ON CONFLICT (emoji_name) DO UPDATE SET content_hash = EXCLUDED.content_hash, updated_at = NOW()
                """;

        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setString(1, emojiName);
            statement.setString(2, contentHash);
            statement.executeUpdate();

        } catch (SQLException e) {
            log.error("Failed to record icon emoji hash for '{}'", emojiName, e);
            throw new RuntimeException("Failed to record icon emoji hash", e);
        }
    }
}
