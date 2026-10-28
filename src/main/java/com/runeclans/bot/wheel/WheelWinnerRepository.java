package com.runeclans.bot.wheel;

import com.runeclans.bot.database.SchemaBootstrapper;
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
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The Citadel wheel's winners for each week once they have been locked in, each with the wheel picture (a GIF) that goes with them so the announcement
 * can be fetched again at any time, and whether their prize has been handed over. A week can have several winners (first place, second place and so on),
 * one row each. The picture is kept in the row but is only read when asked for, never in a list.
 */
@BService
public class WheelWinnerRepository {
    private static final Logger log = LoggerFactory.getLogger(WheelWinnerRepository.class);

    /** How the winner came to be: spun on a wheel, typed in by hand (past weeks), or picked by the weekly auto-post. */
    public enum Method { SPUN, MANUAL, AUTO }

    /** Where the picture came from: the website (drawn there) or the bot's own drawing. */
    public enum GifSource { WEBSITE, BOT }

    /** Whether the winner has been given their prize yet. */
    public enum PrizeStatus { PENDING, GIVEN }

    /** The prize details recorded when it is handed over: how much, who paid it (a holder of the clan coffer), who received it, who recorded it and any note. */
    public record Prize(PrizeStatus status, Long amount, Long paidBy, Long recipient, Long givenBy, Instant givenAt, String note, boolean inCoffer) {
        public static Prize pending() {
            return new Prize(PrizeStatus.PENDING, null, null, null, null, null, null, false);
        }
    }

    /** One locked winner. {@code entries} is every slice of the wheel they were picked from (a name once per entry); {@code place} is 1 for the first winner of the week. */
    public record Winner(long guildId, LocalDate weekEnd, int place, String winner, List<String> entries, Method method, Long lockedBy, Instant lockedAt, boolean hasGif,
                         GifSource gifSource, String gifToken, Prize prize) {}

    private final ConnectionSupplier connectionSupplier;

    public WheelWinnerRepository(ConnectionSupplier connectionSupplier) {
        this.connectionSupplier = connectionSupplier;
        SchemaBootstrapper.run(connectionSupplier, log, "citadel wheel winners", List.of(
                "CREATE SCHEMA IF NOT EXISTS younglings;",
                """
                CREATE TABLE IF NOT EXISTS younglings.citadel_wheel_winner (
                    guild_id BIGINT NOT NULL,
                    week_end DATE NOT NULL,
                    place INT NOT NULL DEFAULT 1,
                    winner TEXT NOT NULL,
                    entries TEXT NOT NULL,
                    method TEXT NOT NULL,
                    locked_by BIGINT NULL,
                    locked_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                    gif BYTEA NULL,
                    gif_source TEXT NULL,
                    gif_token TEXT NOT NULL UNIQUE,
                    PRIMARY KEY (guild_id, week_end, place)
                );
                """,
                // a table made before weeks could have several winners keyed on the week alone
                "ALTER TABLE younglings.citadel_wheel_winner ADD COLUMN IF NOT EXISTS place INT NOT NULL DEFAULT 1;",
                """
                DO $$
                BEGIN
                    IF NOT EXISTS (SELECT 1 FROM information_schema.key_column_usage
                                   WHERE table_schema = 'younglings' AND table_name = 'citadel_wheel_winner'
                                     AND constraint_name = 'citadel_wheel_winner_pkey' AND column_name = 'place') THEN
                        ALTER TABLE younglings.citadel_wheel_winner DROP CONSTRAINT citadel_wheel_winner_pkey;
                        ALTER TABLE younglings.citadel_wheel_winner ADD PRIMARY KEY (guild_id, week_end, place);
                    END IF;
                END $$;
                """,
                "ALTER TABLE younglings.citadel_wheel_winner ADD COLUMN IF NOT EXISTS prize_status TEXT NOT NULL DEFAULT 'PENDING';",
                "ALTER TABLE younglings.citadel_wheel_winner ADD COLUMN IF NOT EXISTS prize_amount BIGINT NULL;",
                "ALTER TABLE younglings.citadel_wheel_winner ADD COLUMN IF NOT EXISTS prize_paid_by BIGINT NULL;",
                "ALTER TABLE younglings.citadel_wheel_winner ADD COLUMN IF NOT EXISTS prize_recipient BIGINT NULL;",
                "ALTER TABLE younglings.citadel_wheel_winner ADD COLUMN IF NOT EXISTS prize_given_by BIGINT NULL;",
                "ALTER TABLE younglings.citadel_wheel_winner ADD COLUMN IF NOT EXISTS prize_given_at TIMESTAMPTZ NULL;",
                "ALTER TABLE younglings.citadel_wheel_winner ADD COLUMN IF NOT EXISTS prize_note TEXT NULL;",
                "ALTER TABLE younglings.citadel_wheel_winner ADD COLUMN IF NOT EXISTS prize_in_coffer BOOLEAN NOT NULL DEFAULT FALSE;"));
    }

    private static final String COLUMNS = "guild_id, week_end, place, winner, entries, method, locked_by, locked_at, gif IS NOT NULL AS has_gif, gif_source, gif_token, "
            + "prize_status, prize_amount, prize_paid_by, prize_recipient, prize_given_by, prize_given_at, prize_note, prize_in_coffer";

    /** Locks a winner into a place of a week. False when that place of that week already has a winner (it is never replaced; unlock the week first). */
    public boolean insert(Winner w, byte[] gif) {
        String sql = """
                INSERT INTO younglings.citadel_wheel_winner (guild_id, week_end, place, winner, entries, method, locked_by, gif, gif_source, gif_token)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (guild_id, week_end, place) DO NOTHING
                """;
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setLong(1, w.guildId());
            s.setDate(2, Date.valueOf(w.weekEnd()));
            s.setInt(3, w.place());
            s.setString(4, w.winner());
            DataArray entries = DataArray.empty();
            w.entries().forEach(entries::add);
            s.setString(5, entries.toString());
            s.setString(6, w.method().name());
            if (w.lockedBy() == null) s.setNull(7, Types.BIGINT);
            else s.setLong(7, w.lockedBy());
            if (gif == null) s.setNull(8, Types.BINARY);
            else s.setBytes(8, gif);
            if (gif == null || w.gifSource() == null) s.setNull(9, Types.VARCHAR);
            else s.setString(9, w.gifSource().name());
            s.setString(10, w.gifToken());
            return s.executeUpdate() == 1;
        } catch (SQLException e) {
            log.error("Failed to lock the Citadel wheel winner of guild {}", w.guildId(), e);
            throw new RuntimeException("Failed to lock the winner", e);
        }
    }

    public Optional<Winner> get(long guildId, LocalDate weekEnd, int place) {
        List<Winner> rows = query("SELECT " + COLUMNS + " FROM younglings.citadel_wheel_winner WHERE guild_id = ? AND week_end = ? AND place = ?", guildId, weekEnd, place, 0);
        return rows.stream().findFirst();
    }

    /** A week's winners in order of place (empty when the week isn't locked). */
    public List<Winner> forWeek(long guildId, LocalDate weekEnd) {
        return query("SELECT " + COLUMNS + " FROM younglings.citadel_wheel_winner WHERE guild_id = ? AND week_end = ? ORDER BY place", guildId, weekEnd, null, 0);
    }

    /** Locked winners, newest week first and by place within a week, without their pictures. */
    public List<Winner> list(long guildId, int limit) {
        return query("SELECT " + COLUMNS + " FROM younglings.citadel_wheel_winner WHERE guild_id = ? ORDER BY week_end DESC, place ASC LIMIT ?", guildId, null, null, limit);
    }

    private List<Winner> query(String sql, long guildId, LocalDate weekEnd, Integer place, int limit) {
        List<Winner> list = new ArrayList<>();
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            int i = 1;
            s.setLong(i++, guildId);
            if (weekEnd != null) s.setDate(i++, Date.valueOf(weekEnd));
            if (place != null) s.setInt(i++, place);
            if (limit > 0) s.setInt(i, limit);
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) list.add(map(rs));
            }
            return list;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read the winners", e);
        }
    }

    /** The place the next winner of this week takes (1 when the week has none yet). */
    public int nextPlace(long guildId, LocalDate weekEnd) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT COALESCE(MAX(place), 0) + 1 FROM younglings.citadel_wheel_winner WHERE guild_id = ? AND week_end = ?")) {
            s.setLong(1, guildId);
            s.setDate(2, Date.valueOf(weekEnd));
            try (ResultSet rs = s.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to count the winners", e);
        }
    }

    /** Removes every winner of a week. Returns how many were removed. */
    public int deleteWeek(long guildId, LocalDate weekEnd) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("DELETE FROM younglings.citadel_wheel_winner WHERE guild_id = ? AND week_end = ?")) {
            s.setLong(1, guildId);
            s.setDate(2, Date.valueOf(weekEnd));
            return s.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to unlock the week", e);
        }
    }

    /**
     * Stores a picture. {@code onlyIfMissing} keeps one that is already there (the bot's own late drawing must never replace one the website
     * made). False when the winner is gone or the picture was kept.
     */
    public boolean setGif(long guildId, LocalDate weekEnd, int place, byte[] gif, GifSource source, boolean onlyIfMissing) {
        String sql = "UPDATE younglings.citadel_wheel_winner SET gif = ?, gif_source = ? WHERE guild_id = ? AND week_end = ? AND place = ?" + (onlyIfMissing ? " AND gif IS NULL" : "");
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setBytes(1, gif);
            s.setString(2, source.name());
            s.setLong(3, guildId);
            s.setDate(4, Date.valueOf(weekEnd));
            s.setInt(5, place);
            return s.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to save the winner's picture", e);
        }
    }

    public Optional<byte[]> gif(long guildId, LocalDate weekEnd, int place) {
        return gifQuery("SELECT gif FROM younglings.citadel_wheel_winner WHERE guild_id = ? AND week_end = ? AND place = ?", guildId, weekEnd, place, null);
    }

    public Optional<byte[]> gifByToken(String token) {
        return gifQuery("SELECT gif FROM younglings.citadel_wheel_winner WHERE gif_token = ?", 0, null, 0, token);
    }

    private Optional<byte[]> gifQuery(String sql, long guildId, LocalDate weekEnd, int place, String token) {
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            if (token != null) {
                s.setString(1, token);
            } else {
                s.setLong(1, guildId);
                s.setDate(2, Date.valueOf(weekEnd));
                s.setInt(3, place);
            }
            try (ResultSet rs = s.executeQuery()) {
                return rs.next() ? Optional.ofNullable(rs.getBytes(1)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read the winner's picture", e);
        }
    }

    /**
     * Sets a winner's prize. Marking it given writes who, how much and when; marking it pending clears all of that. {@code onlyIfPending} makes
     * "given" apply only while the prize is still pending, so two people recording it at once can't both pay it. False when nothing changed.
     */
    public boolean setPrize(long guildId, LocalDate weekEnd, int place, Prize prize, boolean onlyIfPending) {
        String sql = """
                UPDATE younglings.citadel_wheel_winner SET prize_status = ?, prize_amount = ?, prize_paid_by = ?, prize_recipient = ?, prize_given_by = ?,
                    prize_given_at = ?, prize_note = ?, prize_in_coffer = ?
                WHERE guild_id = ? AND week_end = ? AND place = ?
                """ + (onlyIfPending ? " AND prize_status = 'PENDING'" : "");
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setString(1, prize.status().name());
            setLong(s, 2, prize.amount());
            setLong(s, 3, prize.paidBy());
            setLong(s, 4, prize.recipient());
            setLong(s, 5, prize.givenBy());
            if (prize.givenAt() == null) s.setNull(6, Types.TIMESTAMP_WITH_TIMEZONE);
            else s.setTimestamp(6, Timestamp.from(prize.givenAt()));
            if (prize.note() == null) s.setNull(7, Types.VARCHAR);
            else s.setString(7, prize.note());
            s.setBoolean(8, prize.inCoffer());
            s.setLong(9, guildId);
            s.setDate(10, Date.valueOf(weekEnd));
            s.setInt(11, place);
            return s.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to save the prize", e);
        }
    }

    private static void setLong(PreparedStatement s, int index, Long value) throws SQLException {
        if (value == null) s.setNull(index, Types.BIGINT);
        else s.setLong(index, value);
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static Winner map(ResultSet rs) throws SQLException {
        List<String> entries = new ArrayList<>();
        DataArray array = DataObject.fromJson("{\"e\":" + rs.getString("entries") + "}").getArray("e");
        for (int i = 0; i < array.length(); i++) entries.add(array.getString(i));
        Long lockedBy = nullableLong(rs, "locked_by");
        String source = rs.getString("gif_source");
        Timestamp given = rs.getTimestamp("prize_given_at");
        Prize prize = new Prize(PrizeStatus.valueOf(rs.getString("prize_status")), nullableLong(rs, "prize_amount"), nullableLong(rs, "prize_paid_by"), nullableLong(rs, "prize_recipient"),
                nullableLong(rs, "prize_given_by"), given == null ? null : given.toInstant(), rs.getString("prize_note"), rs.getBoolean("prize_in_coffer"));
        return new Winner(rs.getLong("guild_id"), rs.getDate("week_end").toLocalDate(), rs.getInt("place"), rs.getString("winner"), entries, Method.valueOf(rs.getString("method")), lockedBy,
                rs.getTimestamp("locked_at").toInstant(), rs.getBoolean("has_gif"), source == null ? null : GifSource.valueOf(source), rs.getString("gif_token"), prize);
    }
}
