package com.younglings.bot.autoreply;

import com.younglings.bot.autoreply.AutoReply.Match;
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
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Automatic replies, kept in memory per server too (every message in a server asks for them) and refreshed whenever they change. */
@BService
public class AutoReplyRepository {
    private static final Logger log = LoggerFactory.getLogger(AutoReplyRepository.class);

    private final ConnectionSupplier connectionSupplier;
    private final Map<Long, List<AutoReply>> cache = new ConcurrentHashMap<>();

    public AutoReplyRepository(ConnectionSupplier connectionSupplier) {
        this.connectionSupplier = connectionSupplier;
        SchemaBootstrapper.run(connectionSupplier, log, "autoreply", List.of(
                "CREATE SCHEMA IF NOT EXISTS younglings;",
                """
                CREATE TABLE IF NOT EXISTS younglings.auto_reply (
                    id BIGSERIAL PRIMARY KEY,
                    guild_id BIGINT NOT NULL,
                    trigger_text TEXT NOT NULL,
                    match_mode TEXT NOT NULL DEFAULT 'WORD',
                    reply TEXT NOT NULL,
                    cooldown_seconds INTEGER NOT NULL DEFAULT 30,
                    enabled BOOLEAN NOT NULL DEFAULT TRUE,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
                );
                """,
                "CREATE INDEX IF NOT EXISTS auto_reply_guild ON younglings.auto_reply (guild_id);"));
    }

    /** This server's replies, from memory after the first look. */
    public List<AutoReply> forGuild(long guildId) {
        return cache.computeIfAbsent(guildId, this::load);
    }

    private List<AutoReply> load(long guildId) {
        List<AutoReply> list = new ArrayList<>();
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT id, trigger_text, match_mode, reply, cooldown_seconds, enabled FROM younglings.auto_reply WHERE guild_id = ? ORDER BY id")) {
            s.setLong(1, guildId);
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) {
                    list.add(new AutoReply(rs.getLong("id"), guildId, rs.getString("trigger_text"), Match.valueOf(rs.getString("match_mode")), rs.getString("reply"),
                            rs.getInt("cooldown_seconds"), rs.getBoolean("enabled")));
                }
            }
            return List.copyOf(list);
        } catch (SQLException e) {
            log.error("Failed to read the automatic replies of guild {}", guildId, e);
            throw new RuntimeException("Failed to read the automatic replies", e);
        }
    }

    public Optional<AutoReply> get(long guildId, long id) {
        return forGuild(guildId).stream().filter(r -> r.id() == id).findFirst();
    }

    /** Creates the reply when its id is null, otherwise replaces it. Returns it as saved. */
    public AutoReply save(AutoReply reply) {
        try (Connection c = connectionSupplier.getConnection()) {
            long id;
            if (reply.id() == null) {
                try (PreparedStatement s = c.prepareStatement(
                        "INSERT INTO younglings.auto_reply (guild_id, trigger_text, match_mode, reply, cooldown_seconds, enabled) VALUES (?, ?, ?, ?, ?, ?) RETURNING id")) {
                    s.setLong(1, reply.guildId());
                    s.setString(2, reply.trigger());
                    s.setString(3, reply.match().name());
                    s.setString(4, reply.reply());
                    s.setInt(5, reply.cooldownSeconds());
                    s.setBoolean(6, reply.enabled());
                    try (ResultSet rs = s.executeQuery()) {
                        rs.next();
                        id = rs.getLong(1);
                    }
                }
            } else {
                id = reply.id();
                try (PreparedStatement s = c.prepareStatement(
                        "UPDATE younglings.auto_reply SET trigger_text = ?, match_mode = ?, reply = ?, cooldown_seconds = ?, enabled = ? WHERE id = ? AND guild_id = ?")) {
                    s.setString(1, reply.trigger());
                    s.setString(2, reply.match().name());
                    s.setString(3, reply.reply());
                    s.setInt(4, reply.cooldownSeconds());
                    s.setBoolean(5, reply.enabled());
                    s.setLong(6, id);
                    s.setLong(7, reply.guildId());
                    s.executeUpdate();
                }
            }
            cache.remove(reply.guildId());
            return get(reply.guildId(), id).orElseThrow();
        } catch (SQLException e) {
            log.error("Failed to save an automatic reply for guild {}", reply.guildId(), e);
            throw new RuntimeException("Failed to save the automatic reply", e);
        }
    }

    public boolean delete(long guildId, long id) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("DELETE FROM younglings.auto_reply WHERE guild_id = ? AND id = ?")) {
            s.setLong(1, guildId);
            s.setLong(2, id);
            boolean gone = s.executeUpdate() > 0;
            cache.remove(guildId);
            return gone;
        } catch (SQLException e) {
            log.error("Failed to delete automatic reply {}", id, e);
            throw new RuntimeException("Failed to delete the automatic reply", e);
        }
    }
}
