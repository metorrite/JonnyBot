package com.younglings.bot.anniversary;

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

/** The weekly clan-anniversary post's settings per server (T-194), and the day it last went out (so a restart never posts it twice). */
@BService
public class AnniversaryRepository {
    private static final Logger log = LoggerFactory.getLogger(AnniversaryRepository.class);

    /** A server's settings; {@code channelId} null until one is picked. Off by default. */
    public record Settings(boolean enabled, Long channelId, DayOfWeek weekday, int hour, int minYears, LocalDate lastPostedOn) {
        public static final Settings DEFAULT = new Settings(false, null, DayOfWeek.MONDAY, 12, 1, null);
    }

    private final ConnectionSupplier connectionSupplier;

    public AnniversaryRepository(ConnectionSupplier connectionSupplier) {
        this.connectionSupplier = connectionSupplier;
        SchemaBootstrapper.run(connectionSupplier, log, "clan anniversaries", List.of(
                "CREATE SCHEMA IF NOT EXISTS younglings;",
                """
                CREATE TABLE IF NOT EXISTS younglings.anniversary_config (
                    guild_id BIGINT PRIMARY KEY,
                    enabled BOOLEAN NOT NULL DEFAULT FALSE,
                    channel_id BIGINT,
                    weekday SMALLINT NOT NULL DEFAULT 1,
                    hour SMALLINT NOT NULL DEFAULT 12,
                    min_years SMALLINT NOT NULL DEFAULT 1,
                    last_posted_on DATE,
                    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
                );
                """));
    }

    public Settings settings(long guildId) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT enabled, channel_id, weekday, hour, min_years, last_posted_on FROM younglings.anniversary_config WHERE guild_id = ?")) {
            s.setLong(1, guildId);
            try (ResultSet rs = s.executeQuery()) {
                if (!rs.next()) return Settings.DEFAULT;
                long channel = rs.getLong(2);
                Date last = rs.getDate(6);
                return new Settings(rs.getBoolean(1), rs.wasNull() ? null : channel, DayOfWeek.of(rs.getShort(3)), rs.getShort(4), rs.getShort(5), last == null ? null : last.toLocalDate());
            }
        } catch (SQLException e) {
            log.warn("Could not read anniversary settings for {}", guildId, e);
            return Settings.DEFAULT;
        }
    }

    /** Saves the settings a server chose (keeps when it last posted). */
    public void save(long guildId, boolean enabled, Long channelId, DayOfWeek weekday, int hour, int minYears) throws SQLException {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("""
                     INSERT INTO younglings.anniversary_config (guild_id, enabled, channel_id, weekday, hour, min_years, updated_at) VALUES (?, ?, ?, ?, ?, ?, NOW())
                     ON CONFLICT (guild_id) DO UPDATE SET enabled = EXCLUDED.enabled, channel_id = EXCLUDED.channel_id, weekday = EXCLUDED.weekday,
                         hour = EXCLUDED.hour, min_years = EXCLUDED.min_years, updated_at = NOW()
                     """)) {
            s.setLong(1, guildId);
            s.setBoolean(2, enabled);
            if (channelId == null) s.setNull(3, Types.BIGINT);
            else s.setLong(3, channelId);
            s.setShort(4, (short) weekday.getValue());
            s.setShort(5, (short) hour);
            s.setShort(6, (short) minYears);
            s.executeUpdate();
        }
    }

    /** Records that this week's post went out on {@code day}. */
    public void markPosted(long guildId, LocalDate day) throws SQLException {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("UPDATE younglings.anniversary_config SET last_posted_on = ? WHERE guild_id = ?")) {
            s.setDate(1, Date.valueOf(day));
            s.setLong(2, guildId);
            s.executeUpdate();
        }
    }

    /** Servers that have the post switched on with a channel picked. */
    public List<Long> enabledGuilds() {
        List<Long> out = new ArrayList<>();
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT guild_id FROM younglings.anniversary_config WHERE enabled AND channel_id IS NOT NULL");
             ResultSet rs = s.executeQuery()) {
            while (rs.next()) out.add(rs.getLong(1));
        } catch (SQLException e) {
            log.warn("Could not list servers with anniversary posts", e);
        }
        return out;
    }
}
