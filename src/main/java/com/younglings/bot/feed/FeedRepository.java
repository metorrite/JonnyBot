package com.younglings.bot.feed;

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
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@BService
public class FeedRepository {
    private static final Logger log = LoggerFactory.getLogger(FeedRepository.class);

    private final ConnectionSupplier connectionSupplier;

    public FeedRepository(ConnectionSupplier connectionSupplier) {
        this.connectionSupplier = connectionSupplier;
        SchemaBootstrapper.run(connectionSupplier, log, "feed", List.of(
                "CREATE SCHEMA IF NOT EXISTS younglings;",
                """
                CREATE TABLE IF NOT EXISTS younglings.feed_subscription (
                    id BIGSERIAL PRIMARY KEY,
                    guild_id BIGINT NOT NULL,
                    kind TEXT NOT NULL,
                    source TEXT NOT NULL,
                    flair TEXT NOT NULL DEFAULT '',
                    channel_id BIGINT NOT NULL,
                    template TEXT NOT NULL DEFAULT '',
                    enabled BOOLEAN NOT NULL DEFAULT TRUE,
                    primed BOOLEAN NOT NULL DEFAULT FALSE
                );
                """,
                "CREATE INDEX IF NOT EXISTS feed_subscription_guild ON younglings.feed_subscription (guild_id);",
                """
                CREATE TABLE IF NOT EXISTS younglings.feed_seen (
                    subscription_id BIGINT NOT NULL REFERENCES younglings.feed_subscription(id) ON DELETE CASCADE,
                    item_id TEXT NOT NULL,
                    seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                    PRIMARY KEY (subscription_id, item_id)
                );
                """));
    }

    public List<FeedSubscription> forGuild(long guildId) {
        return query("SELECT * FROM younglings.feed_subscription WHERE guild_id = ? ORDER BY id", guildId, null);
    }

    /** Every followed feed of every server that is switched on, for the poller. */
    public List<FeedSubscription> allEnabled() {
        return query("SELECT * FROM younglings.feed_subscription WHERE enabled ORDER BY id", null, null);
    }

    public Optional<FeedSubscription> find(long guildId, long id) {
        return query("SELECT * FROM younglings.feed_subscription WHERE guild_id = ? AND id = ?", guildId, id).stream().findFirst();
    }

    /** Adds a feed (id 0) or changes one. {@code restart} forgets what was seen, for when what is followed has changed. */
    public void save(FeedSubscription sub, boolean restart) {
        try (Connection c = connectionSupplier.getConnection()) {
            if (sub.id() == 0) {
                try (PreparedStatement s = c.prepareStatement("INSERT INTO younglings.feed_subscription (guild_id, kind, source, flair, channel_id, template, enabled) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
                    bind(s, sub);
                    s.executeUpdate();
                }
                return;
            }
            try (PreparedStatement s = c.prepareStatement("UPDATE younglings.feed_subscription SET guild_id = ?, kind = ?, source = ?, flair = ?, channel_id = ?, template = ?, enabled = ?"
                    + (restart ? ", primed = FALSE" : "") + " WHERE id = ? AND guild_id = ?")) {
                bind(s, sub);
                s.setLong(8, sub.id());
                s.setLong(9, sub.guildId());
                s.executeUpdate();
            }
            if (restart) {
                try (PreparedStatement s = c.prepareStatement("DELETE FROM younglings.feed_seen WHERE subscription_id = ?")) {
                    s.setLong(1, sub.id());
                    s.executeUpdate();
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to save the feed", e);
        }
    }

    public boolean delete(long guildId, long id) {
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement("DELETE FROM younglings.feed_subscription WHERE guild_id = ? AND id = ?")) {
            s.setLong(1, guildId);
            s.setLong(2, id);
            return s.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to delete the feed", e);
        }
    }

    public boolean isPrimed(long id) {
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement("SELECT primed FROM younglings.feed_subscription WHERE id = ?")) {
            s.setLong(1, id);
            try (ResultSet rs = s.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read the feed state", e);
        }
    }

    /** Records items as seen and marks the feed as started, so later runs post only what is newer. */
    public void markSeen(long id, List<String> itemIds) {
        try (Connection c = connectionSupplier.getConnection()) {
            try (PreparedStatement s = c.prepareStatement("INSERT INTO younglings.feed_seen (subscription_id, item_id) VALUES (?, ?) ON CONFLICT DO NOTHING")) {
                for (String itemId : itemIds) {
                    s.setLong(1, id);
                    s.setString(2, itemId);
                    s.addBatch();
                }
                s.executeBatch();
            }
            try (PreparedStatement s = c.prepareStatement("UPDATE younglings.feed_subscription SET primed = TRUE WHERE id = ?")) {
                s.setLong(1, id);
                s.executeUpdate();
            }
            try (PreparedStatement s = c.prepareStatement("DELETE FROM younglings.feed_seen WHERE subscription_id = ? AND seen_at < now() - interval '90 days'")) {
                s.setLong(1, id);
                s.executeUpdate();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to record seen feed items", e);
        }
    }

    public Set<String> seen(long id) {
        Set<String> seen = new HashSet<>();
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement("SELECT item_id FROM younglings.feed_seen WHERE subscription_id = ?")) {
            s.setLong(1, id);
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) seen.add(rs.getString(1));
            }
            return seen;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read seen feed items", e);
        }
    }

    private static void bind(PreparedStatement s, FeedSubscription sub) throws SQLException {
        s.setLong(1, sub.guildId());
        s.setString(2, sub.kind().name());
        s.setString(3, sub.source());
        s.setString(4, sub.flair() == null ? "" : sub.flair());
        s.setLong(5, sub.channelId());
        s.setString(6, sub.template() == null ? "" : sub.template());
        s.setBoolean(7, sub.enabled());
    }

    private List<FeedSubscription> query(String sql, Long guildId, Long id) {
        List<FeedSubscription> subs = new ArrayList<>();
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            int i = 1;
            if (guildId != null) s.setLong(i++, guildId);
            if (id != null) s.setLong(i, id);
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) {
                    FeedKind kind = FeedKind.parse(rs.getString("kind"));
                    if (kind == null) continue;
                    subs.add(new FeedSubscription(rs.getLong("id"), rs.getLong("guild_id"), kind, rs.getString("source"), rs.getString("flair"),
                            rs.getLong("channel_id"), rs.getString("template"), rs.getBoolean("enabled")));
                }
            }
            return subs;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read the feeds", e);
        }
    }
}
