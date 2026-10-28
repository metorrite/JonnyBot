package com.runeclans.bot.premium;

import com.runeclans.bot.database.SchemaBootstrapper;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The overall admin panel's Premium access list (N5, runeclans.com/admin): premium granted to one clan site (by its server id) or to one person
 * (by Discord user id), from a date and optionally until one. STRUCTURE ONLY: nothing reads this list to unlock anything, and it is separate from
 * {@code premium_guild} (what {@link Premium} does read), so a row here never changes what a server gets while premium is off
 * ({@code PREMIUM_ENABLED}). When premium is switched on, reading these grants is a deliberate later change.
 */
@BService
public class PremiumAccessRepository {
    private static final Logger log = LoggerFactory.getLogger(PremiumAccessRepository.class);

    /** What a grant is for: one clan site (its Discord server) or one person. */
    public enum Kind {
        SITE, USER;

        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Optional<Kind> ofKey(String raw) {
            for (Kind k : values()) if (k.key().equals(raw)) return Optional.of(k);
            return Optional.empty();
        }
    }

    public record Grant(long id, Kind kind, long targetId, Instant startsAt, Instant endsAt, String note, long grantedBy, Instant grantedAt) {
        /** In force at {@code now}: started, and not ended. */
        public boolean active(Instant now) {
            return !startsAt.isAfter(now) && (endsAt == null || endsAt.isAfter(now));
        }
    }

    private final ConnectionSupplier connectionSupplier;

    public PremiumAccessRepository(ConnectionSupplier connectionSupplier) {
        this.connectionSupplier = connectionSupplier;
        SchemaBootstrapper.run(connectionSupplier, log, "premium access list", List.of(
                "CREATE SCHEMA IF NOT EXISTS younglings;",
                """
                CREATE TABLE IF NOT EXISTS younglings.premium_access_grant (
                    id BIGSERIAL PRIMARY KEY,
                    kind TEXT NOT NULL CHECK (kind IN ('site', 'user')),
                    target_id BIGINT NOT NULL,
                    starts_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                    ends_at TIMESTAMPTZ NULL,
                    note TEXT NOT NULL DEFAULT '',
                    granted_by BIGINT NOT NULL,
                    granted_at TIMESTAMPTZ NOT NULL DEFAULT now()
                );
                """
        ));
    }

    public List<Grant> all() {
        List<Grant> out = new ArrayList<>();
        String sql = "SELECT id, kind, target_id, starts_at, ends_at, note, granted_by, granted_at FROM younglings.premium_access_grant ORDER BY granted_at, id";
        try (Connection c = connectionSupplier.getConnection(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            while (rs.next()) {
                Kind kind = Kind.ofKey(rs.getString("kind")).orElse(null);
                if (kind == null) continue;
                Timestamp ends = rs.getTimestamp("ends_at");
                out.add(new Grant(rs.getLong("id"), kind, rs.getLong("target_id"), rs.getTimestamp("starts_at").toInstant(), ends == null ? null : ends.toInstant(),
                        rs.getString("note"), rs.getLong("granted_by"), rs.getTimestamp("granted_at").toInstant()));
            }
        } catch (SQLException e) {
            log.error("Failed to read the premium access list", e);
            throw new RuntimeException("Failed to read the premium access list", e);
        }
        return out;
    }

    /** Adds a grant; answers its id. */
    public long add(Kind kind, long targetId, Instant startsAt, Instant endsAt, String note, long grantedBy) {
        String sql = "INSERT INTO younglings.premium_access_grant (kind, target_id, starts_at, ends_at, note, granted_by) VALUES (?, ?, ?, ?, ?, ?) RETURNING id";
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setString(1, kind.key());
            s.setLong(2, targetId);
            s.setTimestamp(3, Timestamp.from(startsAt));
            if (endsAt == null) s.setNull(4, Types.TIMESTAMP_WITH_TIMEZONE);
            else s.setTimestamp(4, Timestamp.from(endsAt));
            s.setString(5, note);
            s.setLong(6, grantedBy);
            try (ResultSet rs = s.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            log.error("Failed to add a premium access grant", e);
            throw new RuntimeException("Failed to add the premium access grant", e);
        }
    }

    public boolean delete(long id) {
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement("DELETE FROM younglings.premium_access_grant WHERE id = ?")) {
            s.setLong(1, id);
            return s.executeUpdate() == 1;
        } catch (SQLException e) {
            log.error("Failed to remove premium access grant {}", id, e);
            throw new RuntimeException("Failed to remove the premium access grant", e);
        }
    }
}
