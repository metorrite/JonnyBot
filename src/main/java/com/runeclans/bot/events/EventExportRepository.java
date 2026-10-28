package com.runeclans.bot.events;

import com.runeclans.bot.database.SchemaBootstrapper;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The finished wrap-up export of each event, kept until it expires (T-698, docs SCALING-STATE M6). It used to live in the memory of the process
 * that made it; in Postgres, whichever bot process the website asks can show and hand out the download. One row per event: a new export replaces it.
 */
@BService
public class EventExportRepository {
    private static final Logger log = LoggerFactory.getLogger(EventExportRepository.class);

    /** What the wrap-up page shows about an export, without its bytes. */
    public record Summary(String name, long size, Instant madeAt, int channels, int messages, int images, int links) {}

    private final @Nullable ConnectionSupplier connectionSupplier;

    /** {@code connectionSupplier} is null only for {@link #inMemory}. */
    public EventExportRepository(@Nullable ConnectionSupplier connectionSupplier) {
        this.connectionSupplier = connectionSupplier;
        if (connectionSupplier == null) return;
        SchemaBootstrapper.run(connectionSupplier, log, "event exports", List.of(
                "CREATE SCHEMA IF NOT EXISTS younglings;",
                """
                CREATE TABLE IF NOT EXISTS younglings.event_export_file (
                    event_id BIGINT PRIMARY KEY,
                    name TEXT NOT NULL,
                    data BYTEA NOT NULL,
                    made_at TIMESTAMPTZ NOT NULL,
                    channels INT NOT NULL,
                    messages INT NOT NULL,
                    images INT NOT NULL,
                    links INT NOT NULL,
                    expires_at TIMESTAMPTZ NOT NULL
                );
                """
        ));
    }

    /** A store kept in this process's memory, for tests. */
    public static EventExportRepository inMemory() {
        return new Memory();
    }

    /** Keeps the export until {@code expiresAt}, replacing the event's earlier one, and drops every expired export. */
    public void save(long eventId, EventWrapupOps.Export export, Instant expiresAt) {
        String sql = """
                INSERT INTO younglings.event_export_file (event_id, name, data, made_at, channels, messages, images, links, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (event_id) DO UPDATE SET name = EXCLUDED.name, data = EXCLUDED.data, made_at = EXCLUDED.made_at, channels = EXCLUDED.channels,
                    messages = EXCLUDED.messages, images = EXCLUDED.images, links = EXCLUDED.links, expires_at = EXCLUDED.expires_at
                """;
        try (Connection c = connection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setLong(1, eventId);
            s.setString(2, export.name());
            s.setBytes(3, export.data());
            s.setTimestamp(4, Timestamp.from(export.madeAt()));
            s.setInt(5, export.channels());
            s.setInt(6, export.messages());
            s.setInt(7, export.images());
            s.setInt(8, export.links());
            s.setTimestamp(9, Timestamp.from(expiresAt));
            s.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Could not keep the export: the database did not answer", e);
        }
        deleteExpired(export.madeAt());
    }

    /** The event's export if it hasn't expired at {@code now}, with its bytes, or null. */
    public EventWrapupOps.@Nullable Export get(long eventId, Instant now) {
        try (Connection c = connection();
             PreparedStatement s = c.prepareStatement("SELECT name, data, made_at, channels, messages, images, links FROM younglings.event_export_file WHERE event_id = ? AND expires_at > ?")) {
            s.setLong(1, eventId);
            s.setTimestamp(2, Timestamp.from(now));
            try (ResultSet rs = s.executeQuery()) {
                if (!rs.next()) return null;
                return new EventWrapupOps.Export(rs.getString("name"), rs.getBytes("data"), rs.getTimestamp("made_at").toInstant(), rs.getInt("channels"), rs.getInt("messages"),
                        rs.getInt("images"), rs.getInt("links"));
            }
        } catch (SQLException e) {
            log.warn("Could not read the export of event {}", eventId, e);
            return null;
        }
    }

    /** What {@link #get} would hand out, without reading the bytes (the page asks every couple of seconds), or null. */
    public @Nullable Summary summary(long eventId, Instant now) {
        try (Connection c = connection();
             PreparedStatement s = c.prepareStatement("SELECT name, octet_length(data) AS size, made_at, channels, messages, images, links FROM younglings.event_export_file WHERE event_id = ? AND expires_at > ?")) {
            s.setLong(1, eventId);
            s.setTimestamp(2, Timestamp.from(now));
            try (ResultSet rs = s.executeQuery()) {
                if (!rs.next()) return null;
                return new Summary(rs.getString("name"), rs.getLong("size"), rs.getTimestamp("made_at").toInstant(), rs.getInt("channels"), rs.getInt("messages"),
                        rs.getInt("images"), rs.getInt("links"));
            }
        } catch (SQLException e) {
            log.warn("Could not read the export of event {}", eventId, e);
            return null;
        }
    }

    /** Drops every export that expired before {@code now}. Never throws. */
    public void deleteExpired(Instant now) {
        try (Connection c = connection(); PreparedStatement s = c.prepareStatement("DELETE FROM younglings.event_export_file WHERE expires_at <= ?")) {
            s.setTimestamp(1, Timestamp.from(now));
            s.executeUpdate();
        } catch (SQLException e) {
            log.warn("Could not drop the expired event exports", e);
        }
    }

    private Connection connection() throws SQLException {
        if (connectionSupplier == null) throw new SQLException("no database");
        return connectionSupplier.getConnection();
    }

    /** {@link #inMemory}. */
    private static final class Memory extends EventExportRepository {
        private record Kept(EventWrapupOps.Export export, Instant expiresAt) {}

        private final Map<Long, Kept> kept = new ConcurrentHashMap<>();

        private Memory() {
            super(null);
        }

        @Override
        public void save(long eventId, EventWrapupOps.Export export, Instant expiresAt) {
            kept.put(eventId, new Kept(export, expiresAt));
        }

        @Override
        public EventWrapupOps.@Nullable Export get(long eventId, Instant now) {
            Kept k = kept.get(eventId);
            return k == null || !k.expiresAt().isAfter(now) ? null : k.export();
        }

        @Override
        public @Nullable Summary summary(long eventId, Instant now) {
            EventWrapupOps.Export e = get(eventId, now);
            return e == null ? null : new Summary(e.name(), e.data().length, e.madeAt(), e.channels(), e.messages(), e.images(), e.links());
        }

        @Override
        public void deleteExpired(Instant now) {
            kept.values().removeIf(k -> !k.expiresAt().isAfter(now));
        }
    }
}
