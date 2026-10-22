package com.younglings.bot.staffdigest;

import com.younglings.bot.database.SchemaBootstrapper;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** The weekly staff digest's settings per server (T-193), and the day it last went out (so a restart never posts it twice). */
@BService
public class StaffDigestRepository {
    private static final Logger log = LoggerFactory.getLogger(StaffDigestRepository.class);

    /** A server's settings; {@code channelId} null until one is picked. Off by default. */
    public record Settings(boolean enabled, Long channelId, DayOfWeek weekday, int hour, LocalDate lastPostedOn) {
        public static final Settings DEFAULT = new Settings(false, null, DayOfWeek.MONDAY, 9, null);
    }

    private final ConnectionSupplier connectionSupplier;

    public StaffDigestRepository(ConnectionSupplier connectionSupplier) {
        this.connectionSupplier = connectionSupplier;
        SchemaBootstrapper.run(connectionSupplier, log, "staff digest", List.of(
                "CREATE SCHEMA IF NOT EXISTS younglings;",
                """
                CREATE TABLE IF NOT EXISTS younglings.staff_digest_config (
                    guild_id BIGINT PRIMARY KEY,
                    enabled BOOLEAN NOT NULL DEFAULT FALSE,
                    channel_id BIGINT,
                    weekday SMALLINT NOT NULL DEFAULT 1,
                    hour SMALLINT NOT NULL DEFAULT 9,
                    last_posted_on DATE,
                    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
                );
                """));
    }

    public Settings settings(long guildId) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT enabled, channel_id, weekday, hour, last_posted_on FROM younglings.staff_digest_config WHERE guild_id = ?")) {
            s.setLong(1, guildId);
            try (ResultSet rs = s.executeQuery()) {
                if (!rs.next()) return Settings.DEFAULT;
                long channel = rs.getLong(2);
                Long channelId = rs.wasNull() ? null : channel;
                Date last = rs.getDate(5);
                return new Settings(rs.getBoolean(1), channelId, DayOfWeek.of(rs.getShort(3)), rs.getShort(4), last == null ? null : last.toLocalDate());
            }
        } catch (SQLException e) {
            log.warn("Could not read staff digest settings for {}", guildId, e);
            return Settings.DEFAULT;
        }
    }

    /** Saves the settings a server chose (keeps when it last posted). */
    public void save(long guildId, boolean enabled, Long channelId, DayOfWeek weekday, int hour) throws SQLException {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("""
                     INSERT INTO younglings.staff_digest_config (guild_id, enabled, channel_id, weekday, hour, updated_at) VALUES (?, ?, ?, ?, ?, NOW())
                     ON CONFLICT (guild_id) DO UPDATE SET enabled = EXCLUDED.enabled, channel_id = EXCLUDED.channel_id, weekday = EXCLUDED.weekday,
                         hour = EXCLUDED.hour, updated_at = NOW()
                     """)) {
            s.setLong(1, guildId);
            s.setBoolean(2, enabled);
            if (channelId == null) s.setNull(3, Types.BIGINT);
            else s.setLong(3, channelId);
            s.setShort(4, (short) weekday.getValue());
            s.setShort(5, (short) hour);
            s.executeUpdate();
        }
    }

    /** Records that this week's digest went out on {@code day}. */
    public void markPosted(long guildId, LocalDate day) throws SQLException {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("UPDATE younglings.staff_digest_config SET last_posted_on = ? WHERE guild_id = ?")) {
            s.setDate(1, Date.valueOf(day));
            s.setLong(2, guildId);
            s.executeUpdate();
        }
    }

    /** Servers that have the digest switched on with a channel picked. */
    public List<Long> enabledGuilds() {
        List<Long> out = new ArrayList<>();
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT guild_id FROM younglings.staff_digest_config WHERE enabled AND channel_id IS NOT NULL");
             ResultSet rs = s.executeQuery()) {
            while (rs.next()) out.add(rs.getLong(1));
        } catch (SQLException e) {
            log.warn("Could not list servers with a staff digest", e);
        }
        return out;
    }
}
