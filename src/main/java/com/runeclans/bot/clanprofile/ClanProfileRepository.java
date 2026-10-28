package com.runeclans.bot.clanprofile;

import com.runeclans.bot.internal.SiteInvalidator;
import com.runeclans.bot.clanprofile.ClanProfile.Focus;
import com.runeclans.bot.clanprofile.ClanProfile.Recruiting;
import com.runeclans.bot.clanprofile.ClanProfile.Visibility;
import com.runeclans.bot.database.SchemaBootstrapper;
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
                    leaderboard_opt_in BOOLEAN NOT NULL DEFAULT FALSE,
                    verified_clan_name TEXT NULL,
                    verified_at TIMESTAMPTZ NULL,
                    verified_by BIGINT NULL,
                    suspended_at TIMESTAMPTZ NULL,
                    suspended_reason TEXT NULL,
                    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
                );
                """,
                "ALTER TABLE younglings.clan_profile ADD COLUMN IF NOT EXISTS leaderboard_opt_in BOOLEAN NOT NULL DEFAULT FALSE;",
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
                "CREATE INDEX IF NOT EXISTS clan_report_status ON younglings.clan_report (status, id DESC);",
                // Discord events a clan chose to show publicly. Absent means private; later this can grow a visibility column for "partners".
                """
                CREATE TABLE IF NOT EXISTS younglings.shared_event (
                    guild_id BIGINT NOT NULL,
                    event_id BIGINT NOT NULL,
                    shared_by BIGINT NULL,
                    shared_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                    PRIMARY KEY (guild_id, event_id)
                );
                """,
                // Who may see a shared event: PUBLIC (everyone, in the directory feeds) or ALLIANCE (only servers in an alliance with this one).
                "ALTER TABLE younglings.shared_event ADD COLUMN IF NOT EXISTS scope TEXT NOT NULL DEFAULT 'PUBLIC';",
                // An address a server gave up, held for it for ProfileRules.SLUG_GRACE (T-290). guild_id is who gave it up; a server leaving is
                // purged with the rest of its rows.
                """
                CREATE TABLE IF NOT EXISTS younglings.clan_slug_hold (
                    slug TEXT PRIMARY KEY,
                    guild_id BIGINT NOT NULL,
                    released_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
                );
                """));
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

    /** Whether another server has this address, or gave it up less than {@link ProfileRules#SLUG_GRACE} ago (T-290: it is still held for it). */
    public boolean slugTaken(String slug, long exceptGuildId) {
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement("""
                SELECT 1 FROM younglings.clan_profile WHERE slug = ? AND guild_id <> ?
                UNION ALL
                SELECT 1 FROM younglings.clan_slug_hold WHERE slug = ? AND guild_id <> ? AND released_at > ?
                """)) {
            s.setString(1, slug);
            s.setLong(2, exceptGuildId);
            s.setString(3, slug);
            s.setLong(4, exceptGuildId);
            s.setTimestamp(5, Timestamp.from(Instant.now().minus(ProfileRules.SLUG_GRACE)));
            try (ResultSet rs = s.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to check the address", e);
        }
    }

    /**
     * The server that gave up this address less than {@link ProfileRules#SLUG_GRACE} ago, if no clan has it now: its old address forwards
     * to its current one meanwhile (T-290).
     */
    public Optional<Long> slugHolder(String slug) {
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement("""
                SELECT h.guild_id FROM younglings.clan_slug_hold h
                WHERE h.slug = ? AND h.released_at > ? AND NOT EXISTS (SELECT 1 FROM younglings.clan_profile p WHERE p.slug = h.slug)
                """)) {
            s.setString(1, slug);
            s.setTimestamp(2, Timestamp.from(Instant.now().minus(ProfileRules.SLUG_GRACE)));
            try (ResultSet rs = s.executeQuery()) {
                return rs.next() ? Optional.of(rs.getLong(1)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to look up a held address", e);
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
                INSERT INTO younglings.clan_profile (guild_id, visibility, slug, display_name, tagline, description, logo_url, colour, timezone, focus, recruiting, requirements, apply_url, leaderboard_opt_in, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW())
                ON CONFLICT (guild_id) DO UPDATE SET visibility = EXCLUDED.visibility, slug = EXCLUDED.slug, display_name = EXCLUDED.display_name,
                    tagline = EXCLUDED.tagline, description = EXCLUDED.description, logo_url = EXCLUDED.logo_url, colour = EXCLUDED.colour,
                    timezone = EXCLUDED.timezone, focus = EXCLUDED.focus, recruiting = EXCLUDED.recruiting, requirements = EXCLUDED.requirements,
                    apply_url = EXCLUDED.apply_url, leaderboard_opt_in = EXCLUDED.leaderboard_opt_in, updated_at = NOW()
                """;
        try (Connection c = connectionSupplier.getConnection()) {
            boolean auto = c.getAutoCommit();
            c.setAutoCommit(false);
            try {
                holdOldSlug(c, p.guildId(), p.slug());
                writeContent(c, sql, p);
                c.commit();
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(auto);
            }
            SiteInvalidator.signal(p.guildId(), SiteInvalidator.Area.HUB);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to save the clan profile", e);
        }
    }

    /**
     * When a server changes its address (T-290): the old one is held for it for {@link ProfileRules#SLUG_GRACE} (other clans can't take it, and
     * it forwards to the new one), its own hold on the new address goes (it is taking it back), and holds past their time are cleared.
     */
    private static void holdOldSlug(Connection c, long guildId, String newSlug) throws SQLException {
        String old = null;
        try (PreparedStatement s = c.prepareStatement("SELECT slug FROM younglings.clan_profile WHERE guild_id = ?")) {
            s.setLong(1, guildId);
            try (ResultSet rs = s.executeQuery()) {
                if (rs.next()) old = rs.getString(1);
            }
        }
        if (old != null && !old.equals(newSlug)) {
            try (PreparedStatement s = c.prepareStatement("""
                    INSERT INTO younglings.clan_slug_hold (slug, guild_id, released_at) VALUES (?, ?, NOW())
                    ON CONFLICT (slug) DO UPDATE SET guild_id = EXCLUDED.guild_id, released_at = EXCLUDED.released_at
                    """)) {
                s.setString(1, old);
                s.setLong(2, guildId);
                s.executeUpdate();
            }
        }
        if (newSlug != null) {
            try (PreparedStatement s = c.prepareStatement("DELETE FROM younglings.clan_slug_hold WHERE slug = ? AND guild_id = ?")) {
                s.setString(1, newSlug);
                s.setLong(2, guildId);
                s.executeUpdate();
            }
        }
        try (PreparedStatement s = c.prepareStatement("DELETE FROM younglings.clan_slug_hold WHERE released_at < ?")) {
            s.setTimestamp(1, Timestamp.from(Instant.now().minus(ProfileRules.SLUG_GRACE)));
            s.executeUpdate();
        }
    }

    private static void writeContent(Connection c, String sql, ClanProfile p) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(sql)) {
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
            s.setBoolean(14, p.leaderboardOptIn());
            s.executeUpdate();
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
            SiteInvalidator.signal(guildId, SiteInvalidator.Area.HUB);
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
            SiteInvalidator.signal(guildId, SiteInvalidator.Area.HUB);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to change the suspension", e);
        }
    }

    // ---------- shared events ----------

    /** The ids of this server's events that it has chosen to show publicly (the public feeds use only these). */
    public java.util.Set<Long> sharedEvents(long guildId) {
        java.util.Set<Long> ids = new java.util.HashSet<>();
        eventScopes(guildId).forEach((id, scope) -> {
            if (scope.equals("PUBLIC")) ids.add(id);
        });
        return ids;
    }

    /** Every event this server shares, with who may see it: PUBLIC or ALLIANCE. */
    public java.util.Map<Long, String> eventScopes(long guildId) {
        java.util.Map<Long, String> scopes = new java.util.HashMap<>();
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement("SELECT event_id, scope FROM younglings.shared_event WHERE guild_id = ?")) {
            s.setLong(1, guildId);
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) scopes.put(rs.getLong(1), rs.getString(2));
            }
            return scopes;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read the shared events", e);
        }
    }

    public void setEventShared(long guildId, long eventId, boolean shared, long by) {
        if (shared) setEventScope(guildId, eventId, "PUBLIC", by);
        else setEventScope(guildId, eventId, null, by);
    }

    /** Shares an event with {@code scope} ("PUBLIC" or "ALLIANCE"), changing who may see it if already shared; null stops sharing it. */
    public void setEventScope(long guildId, long eventId, String scope, long by) {
        String sql = scope != null
                ? "INSERT INTO younglings.shared_event (guild_id, event_id, shared_by, scope) VALUES (?, ?, ?, ?) ON CONFLICT (guild_id, event_id) DO UPDATE SET scope = EXCLUDED.scope"
                : "DELETE FROM younglings.shared_event WHERE guild_id = ? AND event_id = ?";
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setLong(1, guildId);
            s.setLong(2, eventId);
            if (scope != null) {
                s.setLong(3, by);
                s.setString(4, scope);
            }
            s.executeUpdate();
            SiteInvalidator.signal(guildId, SiteInvalidator.Area.HUB);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to change whether the event is shared", e);
        }
    }

    /** Forgets shared events that no longer exist on Discord (finished and deleted), so the table doesn't collect them. */
    public int forgetSharedExcept(long guildId, java.util.Set<Long> existing) {
        java.util.Set<Long> gone = new java.util.HashSet<>(eventScopes(guildId).keySet());
        gone.removeAll(existing);
        for (long id : gone) setEventShared(guildId, id, false, 0);
        return gone.size();
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
                rs.getBoolean("leaderboard_opt_in"), rs.getString("verified_clan_name"), instant(rs, "verified_at"), noBy ? null : by, instant(rs, "suspended_at"), rs.getString("suspended_reason"),
                instant(rs, "updated_at"));
    }
}
