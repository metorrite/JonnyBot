package com.younglings.bot.twitch;

import com.younglings.bot.database.SchemaBootstrapper;
import com.younglings.bot.twitch.TwitchRules.OfflineAction;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Storage for Twitch alerts: the streamers a server follows, and the server's on/off switch and what happens when a stream ends. */
@BService
public class TwitchRepository {
    private static final Logger log = LoggerFactory.getLogger(TwitchRepository.class);

    /** One followed streamer. {@code postedStream}, {@code postedMessageId} and {@code postedChannelId} describe the go-live post that is still up. */
    public record Streamer(long id, long guildId, String login, String displayName, long channelId, String message, Long pingRoleId, boolean enabled, boolean primed,
                           String postedStream, Long postedMessageId, Long postedChannelId, int misses) {}

    public record Settings(boolean enabled, OfflineAction offlineAction) {
        public static Settings defaults() {
            return new Settings(false, OfflineAction.EDIT);
        }
    }

    private static final String COLUMNS = "id, guild_id, login, display_name, channel_id, message, ping_role_id, enabled, primed, posted_stream, posted_message_id, posted_channel_id, misses";

    private final ConnectionSupplier connectionSupplier;

    public TwitchRepository(ConnectionSupplier connectionSupplier) {
        this.connectionSupplier = connectionSupplier;
        SchemaBootstrapper.run(connectionSupplier, log, "twitch alerts", List.of(
                "CREATE SCHEMA IF NOT EXISTS younglings;",
                // A server has nothing followed and the switch off until it opts in on the dashboard.
                """
                CREATE TABLE IF NOT EXISTS younglings.twitch_settings (
                    guild_id BIGINT PRIMARY KEY,
                    enabled BOOLEAN NOT NULL DEFAULT FALSE,
                    offline_action TEXT NOT NULL DEFAULT 'EDIT'
                );
                """,
                """
                CREATE TABLE IF NOT EXISTS younglings.twitch_streamer (
                    id BIGSERIAL PRIMARY KEY,
                    guild_id BIGINT NOT NULL,
                    login TEXT NOT NULL,
                    display_name TEXT NOT NULL DEFAULT '',
                    channel_id BIGINT NOT NULL,
                    message TEXT NULL,
                    ping_role_id BIGINT NULL,
                    enabled BOOLEAN NOT NULL DEFAULT TRUE,
                    primed BOOLEAN NOT NULL DEFAULT FALSE,
                    posted_stream TEXT NULL,
                    posted_message_id BIGINT NULL,
                    posted_channel_id BIGINT NULL,
                    misses INT NOT NULL DEFAULT 0,
                    UNIQUE (guild_id, login)
                );
                """
        ));
    }

    // ---------- settings ----------

    public Settings settings(long guildId) {
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement("SELECT enabled, offline_action FROM younglings.twitch_settings WHERE guild_id = ?")) {
            s.setLong(1, guildId);
            try (ResultSet rs = s.executeQuery()) {
                if (!rs.next()) return Settings.defaults();
                return new Settings(rs.getBoolean("enabled"), OfflineAction.ofKey(rs.getString("offline_action")).orElse(OfflineAction.EDIT));
            }
        } catch (SQLException e) {
            throw fail("read the Twitch settings", e);
        }
    }

    public void saveSettings(long guildId, Settings settings) {
        String sql = "INSERT INTO younglings.twitch_settings (guild_id, enabled, offline_action) VALUES (?, ?, ?) ON CONFLICT (guild_id) DO UPDATE SET enabled = EXCLUDED.enabled, offline_action = EXCLUDED.offline_action";
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setLong(1, guildId);
            s.setBoolean(2, settings.enabled());
            s.setString(3, settings.offlineAction().name());
            s.executeUpdate();
        } catch (SQLException e) {
            throw fail("save the Twitch settings", e);
        }
    }

    // ---------- streamers ----------

    public List<Streamer> streamers(long guildId) {
        return query("SELECT " + COLUMNS + " FROM younglings.twitch_streamer WHERE guild_id = ? ORDER BY login", guildId);
    }

    /** Every enabled streamer of every server whose Twitch switch is on. */
    public List<Streamer> active() {
        return query("SELECT " + COLUMNS + " FROM younglings.twitch_streamer t WHERE enabled AND EXISTS (SELECT 1 FROM younglings.twitch_settings g WHERE g.guild_id = t.guild_id AND g.enabled)", null);
    }

    public Optional<Streamer> get(long guildId, long id) {
        List<Streamer> found = query("SELECT " + COLUMNS + " FROM younglings.twitch_streamer WHERE guild_id = ? AND id = " + id, guildId);
        return found.stream().findFirst();
    }

    /** Follows a streamer. Returns empty if the server already follows that login. */
    public Optional<Streamer> add(long guildId, String login, String displayName, long channelId, String message, Long pingRoleId) {
        String sql = "INSERT INTO younglings.twitch_streamer (guild_id, login, display_name, channel_id, message, ping_role_id) VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT (guild_id, login) DO NOTHING RETURNING id";
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setLong(1, guildId);
            s.setString(2, login);
            s.setString(3, displayName);
            s.setLong(4, channelId);
            s.setString(5, message);
            if (pingRoleId == null) s.setNull(6, Types.BIGINT); else s.setLong(6, pingRoleId);
            try (ResultSet rs = s.executeQuery()) {
                return rs.next() ? get(guildId, rs.getLong(1)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw fail("follow the streamer", e);
        }
    }

    public boolean update(long guildId, long id, long channelId, String message, Long pingRoleId, boolean enabled) {
        String sql = "UPDATE younglings.twitch_streamer SET channel_id = ?, message = ?, ping_role_id = ?, enabled = ? WHERE guild_id = ? AND id = ?";
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setLong(1, channelId);
            s.setString(2, message);
            if (pingRoleId == null) s.setNull(3, Types.BIGINT); else s.setLong(3, pingRoleId);
            s.setBoolean(4, enabled);
            s.setLong(5, guildId);
            s.setLong(6, id);
            return s.executeUpdate() > 0;
        } catch (SQLException e) {
            throw fail("save the streamer", e);
        }
    }

    public boolean delete(long guildId, long id) {
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement("DELETE FROM younglings.twitch_streamer WHERE guild_id = ? AND id = ?")) {
            s.setLong(1, guildId);
            s.setLong(2, id);
            return s.executeUpdate() > 0;
        } catch (SQLException e) {
            throw fail("unfollow the streamer", e);
        }
    }

    // ---------- the poller's bookkeeping ----------

    /** First look: marks the streamer looked at and remembers a stream that is already running, without a message. */
    public void prime(long id, String runningStream) {
        run("UPDATE younglings.twitch_streamer SET primed = TRUE, posted_stream = ?, posted_message_id = NULL, posted_channel_id = NULL, misses = 0 WHERE id = ?", runningStream, id);
    }

    /** Claims a stream before its message is sent, so a slow send can never lead to a second post for the same stream. */
    public boolean claim(long id, String stream, String previousStream) {
        String sql = "UPDATE younglings.twitch_streamer SET posted_stream = ?, posted_message_id = NULL, posted_channel_id = NULL, misses = 0 WHERE id = ? AND posted_stream IS NOT DISTINCT FROM ?";
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setString(1, stream);
            s.setLong(2, id);
            s.setString(3, previousStream);
            return s.executeUpdate() > 0;
        } catch (SQLException e) {
            throw fail("record the stream", e);
        }
    }

    public void recordMessage(long id, long channelId, long messageId) {
        run("UPDATE younglings.twitch_streamer SET posted_message_id = ?, posted_channel_id = ? WHERE id = ?", messageId, channelId, id);
    }

    public void setMisses(long id, int misses) {
        run("UPDATE younglings.twitch_streamer SET misses = ? WHERE id = ?", misses, id);
    }

    /** The stream is over: forget it and its post. */
    public void clearPosted(long id) {
        run("UPDATE younglings.twitch_streamer SET posted_stream = NULL, posted_message_id = NULL, posted_channel_id = NULL, misses = 0 WHERE id = ?", id);
    }

    // ---------- plumbing ----------

    private List<Streamer> query(String sql, Long guildId) {
        List<Streamer> found = new ArrayList<>();
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            if (guildId != null) s.setLong(1, guildId);
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) found.add(read(rs));
            }
            return found;
        } catch (SQLException e) {
            throw fail("read the streamers", e);
        }
    }

    private void run(String sql, Object... values) {
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) s.setObject(i + 1, values[i]);
            s.executeUpdate();
        } catch (SQLException e) {
            throw fail("update the streamer", e);
        }
    }

    private static Streamer read(ResultSet rs) throws SQLException {
        long role = rs.getLong("ping_role_id");
        Long pingRole = rs.wasNull() ? null : role;
        long message = rs.getLong("posted_message_id");
        Long postedMessage = rs.wasNull() ? null : message;
        long channel = rs.getLong("posted_channel_id");
        Long postedChannel = rs.wasNull() ? null : channel;
        return new Streamer(rs.getLong("id"), rs.getLong("guild_id"), rs.getString("login"), rs.getString("display_name"), rs.getLong("channel_id"), rs.getString("message"),
                pingRole, rs.getBoolean("enabled"), rs.getBoolean("primed"), rs.getString("posted_stream"), postedMessage, postedChannel, rs.getInt("misses"));
    }

    private static RuntimeException fail(String what, SQLException e) {
        log.error("Failed to {}", what, e);
        return new RuntimeException("Failed to " + what, e);
    }
}
