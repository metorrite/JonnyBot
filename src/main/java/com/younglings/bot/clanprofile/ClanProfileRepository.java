package com.younglings.bot.clanprofile;

import com.younglings.bot.clanprofile.ClanProfile.Focus;
import com.younglings.bot.clanprofile.ClanProfile.Recruiting;
import com.younglings.bot.clanprofile.ClanProfile.Visibility;
import com.younglings.bot.database.SchemaBootstrapper;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Clan profiles and the reports people make about them. */
@BService
public class ClanProfileRepository {
    private static final Logger log = LoggerFactory.getLogger(ClanProfileRepository.class);

    /** A report made through the public site; {@code reporter} is whatever the site chose to record (never required). */
    public record Report(long id, long guildId, String reason, String reporter, Instant createdAt, String status) {}

    private final ConnectionSupplier connectionSupplier;

    public ClanProfileRepository(ConnectionSupplier connectionSupplier) {
        this.connectionSupplier = connectionSupplier;
        SchemaBootstrapper.run(connectionSupplier, log, "clan profile", List.of(
                "CREATE SCHEMA IF NOT EXISTS younglings;",
                """
                CREATE TABLE IF NOT EXISTS younglings.clan_profile (
                    guild_id BIGINT PRIMARY KEY,
                    visibility TEXT NOT NULL DEFAULT 'PRIVATE',
                    slug TEXT NULL,
                    display_name TEXT NOT NULL DEFAULT '',
                    tagline TEXT NOT NULL DEFAULT '',
                    description TEXT NOT NULL DEFAULT '',
                    logo_url TEXT NULL,
                    colour INTEGER NULL,
                    timezone TEXT NULL,
                    focus TEXT NOT NULL DEFAULT '',
                    recruiting TEXT NOT NULL DEFAULT 'CLOSED',
                    requirements TEXT NOT NULL DEFAULT '',
                    apply_url TEXT NULL,
                    verified_clan_name TEXT NULL,
                    verified_at TIMESTAMPTZ NULL,
                    verified_by BIGINT NULL,
                    suspended_at TIMESTAMPTZ NULL,
                    suspended_reason TEXT NULL,
                    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
                );
                """,
                "CREATE UNIQUE INDEX IF NOT EXISTS clan_profile_slug ON younglings.clan_profile (slug) WHERE slug IS NOT NULL;",
                "CREATE INDEX IF NOT EXISTS clan_profile_verified_name ON younglings.clan_profile (LOWER(verified_clan_name)) WHERE verified_clan_name IS NOT NULL;",
                """
                CREATE TABLE IF NOT EXISTS younglings.clan_report (
                    id BIGSERIAL PRIMARY KEY,
                    guild_id BIGINT NOT NULL,
                    reason TEXT NOT NULL,
                    reporter TEXT NULL,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                    status TEXT NOT NULL DEFAULT 'OPEN',
                    resolved_by BIGINT NULL,
                    resolved_at TIMESTAMPTZ NULL
                );
                """,
                "CREATE INDEX IF NOT EXISTS clan_report_status ON younglings.clan_report (status, id DESC);"));
    }

    // ---------- profiles ----------

    public Optional<ClanProfile> get(long guildId) {
        return one("SELECT * FROM younglings.clan_profile WHERE guild_id = ?", s -> s.setLong(1, guildId));
    }

    public Optional<ClanProfile> bySlug(String slug) {
        return one("SELECT * FROM younglings.clan_profile WHERE slug = ?", s -> s.setString(1, slug));
    }

    /** Every profile that is not private, for the directory to filter by what is actually listed right now. */
    public List<ClanProfile> shared() {
        List<ClanProfile> list = new ArrayList<>();
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT * FROM younglings.clan_profile WHERE visibility <> 'PRIVATE' AND suspended_at IS NULL ORDER BY display_name");
             ResultSet rs = s.executeQuery()) {
            while (rs.next()) list.add(map(rs));
            return list;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read the clan profiles", e);
        }
    }

    /** Every profile that has been shared or is suspended or has reports, for the bot owner's moderation view. */
    public List<ClanProfile> all() {
        List<ClanProfile> list = new ArrayList<>();
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT * FROM younglings.clan_profile ORDER BY display_name");
             ResultSet rs = s.executeQuery()) {
            while (rs.next()) list.add(map(rs));
            return list;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read the clan profiles", e);
        }
    }

    public boolean slugTaken(String slug, long exceptGuildId) {
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement("SELECT 1 FROM younglings.clan_profile WHERE slug = ? AND guild_id <> ?")) {
            s.setString(1, slug);
            s.setLong(2, exceptGuildId);
            try (ResultSet rs = s.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to check the address", e);
        }
    }

    /** The server (other than {@code exceptGuildId}) that has already verified this RuneScape clan, if any. */
    public Optional<Long> verifiedHolder(String clanName, long exceptGuildId) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT guild_id FROM younglings.clan_profile WHERE LOWER(verified_clan_name) = LOWER(?) AND guild_id <> ? LIMIT 1")) {
            s.setString(1, clanName);
            s.setLong(2, exceptGuildId);
            try (ResultSet rs = s.executeQuery()) {
                return rs.next() ? Optional.of(rs.getLong(1)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to check who verified the clan", e);
        }
    }

    /** Saves what the server edits. Verification and suspension are kept as they are: they have their own methods. */
    public void saveContent(ClanProfile p) {
        String sql = """
                INSERT INTO younglings.clan_profile (guild_id, visibility, slug, display_name, tagline, description, logo_url, colour, timezone, focus, recruiting, requirements, apply_url, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW())
                ON CONFLICT (guild_id) DO UPDATE SET visibility = EXCLUDED.visibility, slug = EXCLUDED.slug, display_name = EXCLUDED.display_name,
                    tagline = EXCLUDED.tagline, description = EXCLUDED.description, logo_url = EXCLUDED.logo_url, colour = EXCLUDED.colour,
                    timezone = EXCLUDED.timezone, focus = EXCLUDED.focus, recruiting = EXCLUDED.recruiting, requirements = EXCLUDED.requirements,
                    apply_url = EXCLUDED.apply_url, updated_at = NOW()
                """;
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setLong(1, p.guildId());
            s.setString(2, p.visibility().name());
            nullable(s, 3, p.slug());
            s.setString(4, p.displayName());
            s.setString(5, p.tagline());
            s.setString(6, p.description());
            nullable(s, 7, p.logoUrl());
            if (p.colour() == null) s.setNull(8, Types.INTEGER); else s.setInt(8, p.colour());
            nullable(s, 9, p.timezone());
            s.setString(10, writeFocus(p.focus()));
            s.setString(11, p.recruiting().name());
            s.setString(12, p.requirements());
            nullable(s, 13, p.applyUrl());
            s.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to save the clan profile", e);
        }
    }

    public void setVerified(long guildId, String clanName, long verifiedBy) {
        String sql = """
                INSERT INTO younglings.clan_profile (guild_id, verified_clan_name, verified_at, verified_by) VALUES (?, ?, NOW(), ?)
                ON CONFLICT (guild_id) DO UPDATE SET verified_clan_name = EXCLUDED.verified_clan_name, verified_at = NOW(), verified_by = EXCLUDED.verified_by
                """;
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setLong(1, guildId);
            s.setString(2, clanName);
            s.setLong(3, verifiedBy);
            s.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to record the verification", e);
        }
    }

    /** Suspends a listing (reason required by the caller) or lifts the suspension when {@code reason} is null. */
    public void setSuspended(long guildId, String reason) {
        String sql = reason == null
                ? "UPDATE younglings.clan_profile SET suspended_at = NULL, suspended_reason = NULL WHERE guild_id = ?"
                : "UPDATE younglings.clan_profile SET suspended_at = NOW(), suspended_reason = ?, visibility = 'PRIVATE' WHERE guild_id = ?";
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            if (reason == null) {
                s.setLong(1, guildId);
            } else {
                s.setString(1, reason);
                s.setLong(2, guildId);
            }
            s.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to change the suspension", e);
        }
    }

    // ---------- reports ----------

    /** Records a report, unless this clan has been reported {@code maxPerDay} times in the last day already (a flood guard). Returns whether it was kept. */
    public boolean addReport(long guildId, String reason, String reporter, int maxPerDay) {
        try (Connection c = connectionSupplier.getConnection()) {
            try (PreparedStatement s = c.prepareStatement("SELECT COUNT(*) FROM younglings.clan_report WHERE guild_id = ? AND created_at > NOW() - INTERVAL '1 day'")) {
                s.setLong(1, guildId);
                try (ResultSet rs = s.executeQuery()) {
                    rs.next();
                    if (rs.getInt(1) >= maxPerDay) return false;
                }
            }
            try (PreparedStatement s = c.prepareStatement("INSERT INTO younglings.clan_report (guild_id, reason, reporter) VALUES (?, ?, ?)")) {
                s.setLong(1, guildId);
                s.setString(2, reason);
                nullable(s, 3, reporter);
                s.executeUpdate();
            }
            return true;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to record the report", e);
        }
    }

    public List<Report> openReports() {
        List<Report> list = new ArrayList<>();
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT id, guild_id, reason, reporter, created_at, status FROM younglings.clan_report WHERE status = 'OPEN' ORDER BY id DESC LIMIT 200");
             ResultSet rs = s.executeQuery()) {
            while (rs.next()) {
                list.add(new Report(rs.getLong("id"), rs.getLong("guild_id"), rs.getString("reason"), rs.getString("reporter"), rs.getTimestamp("created_at").toInstant(), rs.getString("status")));
            }
            return list;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read the reports", e);
        }
    }

    public boolean resolveReport(long id, String status, long resolvedBy) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("UPDATE younglings.clan_report SET status = ?, resolved_by = ?, resolved_at = NOW() WHERE id = ? AND status = 'OPEN'")) {
            s.setString(1, status);
            s.setLong(2, resolvedBy);
            s.setLong(3, id);
            return s.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to resolve the report", e);
        }
    }

    // ---------- helpers ----------

    @FunctionalInterface
    private interface Binder {
        void bind(PreparedStatement s) throws SQLException;
    }

    private Optional<ClanProfile> one(String sql, Binder binder) {
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            binder.bind(s);
            try (ResultSet rs = s.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read the clan profile", e);
        }
    }

    private static void nullable(PreparedStatement s, int index, String value) throws SQLException {
        if (value == null || value.isBlank()) s.setNull(index, Types.VARCHAR); else s.setString(index, value);
    }

    static String writeFocus(Set<Focus> focus) {
        List<String> names = new ArrayList<>();
        for (Focus f : Focus.values()) if (focus.contains(f)) names.add(f.name());
        return String.join(",", names);
    }

    static Set<Focus> parseFocus(String text) {
        Set<Focus> focus = EnumSet.noneOf(Focus.class);
        if (text == null || text.isBlank()) return focus;
        for (String part : text.split(",")) {
            try {
                focus.add(Focus.valueOf(part.strip()));
            } catch (IllegalArgumentException ignored) {
                // a focus this version doesn't know is skipped
            }
        }
        return focus;
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }

    private static ClanProfile map(ResultSet rs) throws SQLException {
        int colour = rs.getInt("colour");
        boolean noColour = rs.wasNull();
        long by = rs.getLong("verified_by");
        boolean noBy = rs.wasNull();
        return new ClanProfile(rs.getLong("guild_id"), Visibility.valueOf(rs.getString("visibility")), rs.getString("slug"), rs.getString("display_name"),
                rs.getString("tagline"), rs.getString("description"), rs.getString("logo_url"), noColour ? null : colour, rs.getString("timezone"),
                parseFocus(rs.getString("focus")), Recruiting.valueOf(rs.getString("recruiting")), rs.getString("requirements"), rs.getString("apply_url"),
                rs.getString("verified_clan_name"), instant(rs, "verified_at"), noBy ? null : by, instant(rs, "suspended_at"), rs.getString("suspended_reason"),
                instant(rs, "updated_at"));
    }
}
