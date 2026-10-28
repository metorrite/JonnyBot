package com.runeclans.bot.events;

import com.runeclans.bot.database.SchemaBootstrapper;
import com.runeclans.bot.events.BingoTilePool.Difficulty;
import com.runeclans.bot.events.BingoTilePool.Entry;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** A server's own bingo tile pool. Empty until the server adds tiles; nothing in the bot reads it unless an event is made with "Your tile pool". */
@BService
public class BingoTileRepository {
    private static final Logger log = LoggerFactory.getLogger(BingoTileRepository.class);

    /** A stored tile. {@code source} is "manual" or the id of the starter pack it came from. */
    public record Stored(long id, long guildId, Entry entry, String source) {}

    private static final String COLUMNS = "id, guild_id, label, points, target, difficulty, tags, enabled, source";

    private final ConnectionSupplier connectionSupplier;

    public BingoTileRepository(ConnectionSupplier connectionSupplier) {
        this.connectionSupplier = connectionSupplier;
        SchemaBootstrapper.run(connectionSupplier, log, "bingo tile pool", List.of(
                "CREATE SCHEMA IF NOT EXISTS younglings;",
                """
                CREATE TABLE IF NOT EXISTS younglings.bingo_tile (
                    id BIGSERIAL PRIMARY KEY,
                    guild_id BIGINT NOT NULL,
                    label TEXT NOT NULL,
                    points BIGINT NOT NULL,
                    target BIGINT NOT NULL DEFAULT 1,
                    difficulty TEXT NOT NULL DEFAULT 'medium',
                    tags TEXT NOT NULL DEFAULT '',
                    enabled BOOLEAN NOT NULL DEFAULT TRUE,
                    source TEXT NOT NULL DEFAULT 'manual',
                    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
                );
                """,
                "CREATE UNIQUE INDEX IF NOT EXISTS bingo_tile_label_idx ON younglings.bingo_tile (guild_id, lower(label));"));
    }

    private static Stored map(ResultSet rs) throws SQLException {
        String tags = rs.getString("tags");
        List<String> tagList = tags == null || tags.isBlank() ? List.of() : Arrays.stream(tags.split(",")).map(String::strip).filter(t -> !t.isEmpty()).toList();
        Difficulty difficulty = Difficulty.parse(rs.getString("difficulty"));
        return new Stored(rs.getLong("id"), rs.getLong("guild_id"),
                new Entry(rs.getString("label"), rs.getLong("points"), rs.getLong("target"), difficulty == null ? Difficulty.MEDIUM : difficulty, tagList, rs.getBoolean("enabled")),
                rs.getString("source"));
    }

    public List<Stored> list(long guildId) {
        String sql = "SELECT " + COLUMNS + " FROM younglings.bingo_tile WHERE guild_id = ? ORDER BY id";
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setLong(1, guildId);
            List<Stored> out = new ArrayList<>();
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) out.add(map(rs));
            }
            return out;
        } catch (SQLException e) {
            log.error("Failed to list the bingo tile pool of guild {}", guildId, e);
            throw new RuntimeException("Failed to list the bingo tile pool", e);
        }
    }

    public int count(long guildId) {
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement("SELECT COUNT(*) FROM younglings.bingo_tile WHERE guild_id = ?")) {
            s.setLong(1, guildId);
            try (ResultSet rs = s.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to count bingo tiles", e);
        }
    }

    /** Adds a tile. Returns its id, or -1 if the server already has a tile with that name. */
    public long add(long guildId, Entry e, String source) {
        String sql = "INSERT INTO younglings.bingo_tile (guild_id, label, points, target, difficulty, tags, enabled, source) VALUES (?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING";
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            bind(s, guildId, e, source);
            if (s.executeUpdate() == 0) return -1;
            try (ResultSet keys = s.getGeneratedKeys()) {
                keys.next();
                return keys.getLong("id");
            }
        } catch (SQLException ex) {
            log.error("Failed to add a bingo tile in guild {}", guildId, ex);
            throw new RuntimeException("Failed to add a bingo tile", ex);
        }
    }

    /** Changes a tile. False if there is no such tile or its new name clashes with another. */
    public boolean update(long guildId, long id, Entry e) {
        String sql = "UPDATE younglings.bingo_tile SET label = ?, points = ?, target = ?, difficulty = ?, tags = ?, enabled = ? WHERE guild_id = ? AND id = ?";
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setString(1, e.label());
            s.setLong(2, e.points());
            s.setLong(3, e.target());
            s.setString(4, e.difficulty().key());
            s.setString(5, String.join(",", e.tags()));
            s.setBoolean(6, e.enabled());
            s.setLong(7, guildId);
            s.setLong(8, id);
            return s.executeUpdate() > 0;
        } catch (SQLException ex) {
            if ("23505".equals(ex.getSQLState())) return false; // another tile already has that name
            log.error("Failed to update bingo tile {}", id, ex);
            throw new RuntimeException("Failed to update a bingo tile", ex);
        }
    }

    public boolean delete(long guildId, long id) {
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement("DELETE FROM younglings.bingo_tile WHERE guild_id = ? AND id = ?")) {
            s.setLong(1, guildId);
            s.setLong(2, id);
            return s.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to delete a bingo tile", e);
        }
    }

    /** Removes every tile that came from {@code source} (a pack id), or every tile when {@code source} is null. Returns how many. */
    public int deleteBySource(long guildId, String source) {
        String sql = "DELETE FROM younglings.bingo_tile WHERE guild_id = ?" + (source == null ? "" : " AND source = ?");
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setLong(1, guildId);
            if (source != null) s.setString(2, source.toLowerCase(Locale.ROOT));
            return s.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to clear bingo tiles", e);
        }
    }

    private static void bind(PreparedStatement s, long guildId, Entry e, String source) throws SQLException {
        s.setLong(1, guildId);
        s.setString(2, e.label());
        s.setLong(3, e.points());
        s.setLong(4, e.target());
        s.setString(5, e.difficulty().key());
        s.setString(6, String.join(",", e.tags()));
        s.setBoolean(7, e.enabled());
        s.setString(8, source);
    }
}
