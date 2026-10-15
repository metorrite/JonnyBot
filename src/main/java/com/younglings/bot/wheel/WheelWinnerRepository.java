package com.younglings.bot.wheel;

import com.younglings.bot.database.SchemaBootstrapper;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The Citadel wheel's winner for each week once it has been locked in, with the wheel picture (a GIF) that goes with them so the announcement can
 * be fetched again at any time. One row per server and week. The picture is kept in the row but is only read when asked for, never in a list.
 */
@BService
public class WheelWinnerRepository {
    private static final Logger log = LoggerFactory.getLogger(WheelWinnerRepository.class);

    /** How the winner came to be: spun on a wheel, typed in by hand (past weeks), or picked by the weekly auto-post. */
    public enum Method { SPUN, MANUAL, AUTO }

    /** Where the picture came from: the website (drawn there) or the bot's own drawing. */
    public enum GifSource { WEBSITE, BOT }

    /** One locked week. {@code entries} is every slice of the wheel the winner was picked from (a name once per entry). */
    public record Winner(long guildId, LocalDate weekEnd, String winner, List<String> entries, Method method, Long lockedBy, Instant lockedAt, boolean hasGif,
                         GifSource gifSource, String gifToken) {}

    private final ConnectionSupplier connectionSupplier;

    public WheelWinnerRepository(ConnectionSupplier connectionSupplier) {
        this.connectionSupplier = connectionSupplier;
        SchemaBootstrapper.run(connectionSupplier, log, "citadel wheel winners", List.of(
                "CREATE SCHEMA IF NOT EXISTS younglings;",
                """
                CREATE TABLE IF NOT EXISTS younglings.citadel_wheel_winner (
                    guild_id BIGINT NOT NULL,
                    week_end DATE NOT NULL,
                    winner TEXT NOT NULL,
                    entries TEXT NOT NULL,
                    method TEXT NOT NULL,
                    locked_by BIGINT NULL,
                    locked_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                    gif BYTEA NULL,
                    gif_source TEXT NULL,
                    gif_token TEXT NOT NULL UNIQUE,
                    PRIMARY KEY (guild_id, week_end)
                );
                """));
    }

    private static final String COLUMNS = "guild_id, week_end, winner, entries, method, locked_by, locked_at, gif IS NOT NULL AS has_gif, gif_source, gif_token";

    /** Locks a week. False when that week already has a winner (it is never replaced; unlock it first). */
    public boolean insert(Winner w, byte[] gif) {
        String sql = """
                INSERT INTO younglings.citadel_wheel_winner (guild_id, week_end, winner, entries, method, locked_by, gif, gif_source, gif_token)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (guild_id, week_end) DO NOTHING
                """;
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setLong(1, w.guildId());
            s.setDate(2, Date.valueOf(w.weekEnd()));
            s.setString(3, w.winner());
            DataArray entries = DataArray.empty();
            w.entries().forEach(entries::add);
            s.setString(4, entries.toString());
            s.setString(5, w.method().name());
            if (w.lockedBy() == null) s.setNull(6, Types.BIGINT);
            else s.setLong(6, w.lockedBy());
            if (gif == null) s.setNull(7, Types.BINARY);
            else s.setBytes(7, gif);
            if (gif == null || w.gifSource() == null) s.setNull(8, Types.VARCHAR);
            else s.setString(8, w.gifSource().name());
            s.setString(9, w.gifToken());
            return s.executeUpdate() == 1;
        } catch (SQLException e) {
            log.error("Failed to lock the Citadel wheel winner of guild {}", w.guildId(), e);
            throw new RuntimeException("Failed to lock the winner", e);
        }
    }

    public Optional<Winner> get(long guildId, LocalDate weekEnd) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT " + COLUMNS + " FROM younglings.citadel_wheel_winner WHERE guild_id = ? AND week_end = ?")) {
            s.setLong(1, guildId);
            s.setDate(2, Date.valueOf(weekEnd));
            try (ResultSet rs = s.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read the winner", e);
        }
    }

    /** Locked weeks, newest first, without their pictures. */
    public List<Winner> list(long guildId, int limit) {
        List<Winner> list = new ArrayList<>();
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT " + COLUMNS + " FROM younglings.citadel_wheel_winner WHERE guild_id = ? ORDER BY week_end DESC LIMIT ?")) {
            s.setLong(1, guildId);
            s.setInt(2, limit);
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) list.add(map(rs));
            }
            return list;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read the winners", e);
        }
    }

    public boolean delete(long guildId, LocalDate weekEnd) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("DELETE FROM younglings.citadel_wheel_winner WHERE guild_id = ? AND week_end = ?")) {
            s.setLong(1, guildId);
            s.setDate(2, Date.valueOf(weekEnd));
            return s.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to unlock the winner", e);
        }
    }

    /**
     * Stores a picture. {@code onlyIfMissing} keeps one that is already there (the bot's own late drawing must never replace one the website
     * made). False when the week is gone or the picture was kept.
     */
    public boolean setGif(long guildId, LocalDate weekEnd, byte[] gif, GifSource source, boolean onlyIfMissing) {
        String sql = "UPDATE younglings.citadel_wheel_winner SET gif = ?, gif_source = ? WHERE guild_id = ? AND week_end = ?" + (onlyIfMissing ? " AND gif IS NULL" : "");
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setBytes(1, gif);
            s.setString(2, source.name());
            s.setLong(3, guildId);
            s.setDate(4, Date.valueOf(weekEnd));
            return s.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to save the winner's picture", e);
        }
    }

    public Optional<byte[]> gif(long guildId, LocalDate weekEnd) {
        return gifQuery("SELECT gif FROM younglings.citadel_wheel_winner WHERE guild_id = ? AND week_end = ?", guildId, weekEnd, null);
    }

    public Optional<byte[]> gifByToken(String token) {
        return gifQuery("SELECT gif FROM younglings.citadel_wheel_winner WHERE gif_token = ?", 0, null, token);
    }

    private Optional<byte[]> gifQuery(String sql, long guildId, LocalDate weekEnd, String token) {
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            if (token != null) {
                s.setString(1, token);
            } else {
                s.setLong(1, guildId);
                s.setDate(2, Date.valueOf(weekEnd));
            }
            try (ResultSet rs = s.executeQuery()) {
                return rs.next() ? Optional.ofNullable(rs.getBytes(1)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read the winner's picture", e);
        }
    }

    private static Winner map(ResultSet rs) throws SQLException {
        List<String> entries = new ArrayList<>();
        DataArray array = DataObject.fromJson("{\"e\":" + rs.getString("entries") + "}").getArray("e");
        for (int i = 0; i < array.length(); i++) entries.add(array.getString(i));
        long by = rs.getLong("locked_by");
        Long lockedBy = rs.wasNull() ? null : by;
        String source = rs.getString("gif_source");
        return new Winner(rs.getLong("guild_id"), rs.getDate("week_end").toLocalDate(), rs.getString("winner"), entries, Method.valueOf(rs.getString("method")), lockedBy,
                rs.getTimestamp("locked_at").toInstant(), rs.getBoolean("has_gif"), source == null ? null : GifSource.valueOf(source), rs.getString("gif_token"));
    }
}
