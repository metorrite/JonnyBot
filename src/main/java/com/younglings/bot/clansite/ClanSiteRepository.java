package com.younglings.bot.clansite;

import com.younglings.bot.clansite.ClanSite.Template;
import com.younglings.bot.database.SchemaBootstrapper;
import com.younglings.bot.internal.SiteInvalidator;
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
import java.util.List;
import java.util.Optional;

/** Storage for clan websites: one row per server that has used the builder. */
@BService
public class ClanSiteRepository {
    private static final Logger log = LoggerFactory.getLogger(ClanSiteRepository.class);
    private static final String COLUMNS = "guild_id, enabled, published, template, theme, pages, updated_by, updated_at, published_at, takedown_reason";

    private final ConnectionSupplier connectionSupplier;

    public ClanSiteRepository(ConnectionSupplier connectionSupplier) {
        this.connectionSupplier = connectionSupplier;
        SchemaBootstrapper.run(connectionSupplier, log, "clan websites", List.of(
                "CREATE SCHEMA IF NOT EXISTS younglings;",
                """
                CREATE TABLE IF NOT EXISTS younglings.clan_site (
                    guild_id BIGINT PRIMARY KEY,
                    enabled BOOLEAN NOT NULL DEFAULT FALSE,
                    published BOOLEAN NOT NULL DEFAULT FALSE,
                    template TEXT NOT NULL DEFAULT 'CLASSIC',
                    theme TEXT NOT NULL DEFAULT '{}',
                    pages TEXT NOT NULL DEFAULT '[]',
                    updated_by BIGINT NULL,
                    updated_at TIMESTAMPTZ NULL,
                    published_at TIMESTAMPTZ NULL,
                    takedown_reason TEXT NULL
                );
                """
        ));
    }

    public Optional<ClanSite> byGuild(long guildId) {
        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT " + COLUMNS + " FROM younglings.clan_site WHERE guild_id = ?")) {
            statement.setLong(1, guildId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(read(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            log.error("Failed to read the clan website of guild {}", guildId, e);
            throw new RuntimeException("Failed to read the clan website", e);
        }
    }

    /** Writes the whole row (insert or replace) and tells the website its cached copy is stale. */
    public void save(ClanSite site) {
        String sql = "INSERT INTO younglings.clan_site (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                + "ON CONFLICT (guild_id) DO UPDATE SET enabled = EXCLUDED.enabled, published = EXCLUDED.published, template = EXCLUDED.template, "
                + "theme = EXCLUDED.theme, pages = EXCLUDED.pages, updated_by = EXCLUDED.updated_by, updated_at = EXCLUDED.updated_at, "
                + "published_at = EXCLUDED.published_at, takedown_reason = EXCLUDED.takedown_reason";
        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, site.guildId());
            statement.setBoolean(2, site.enabled());
            statement.setBoolean(3, site.published());
            statement.setString(4, site.template().name());
            statement.setString(5, site.theme());
            statement.setString(6, site.pages());
            if (site.updatedBy() == null) statement.setNull(7, Types.BIGINT);
            else statement.setLong(7, site.updatedBy());
            statement.setTimestamp(8, timestamp(site.updatedAt()));
            statement.setTimestamp(9, timestamp(site.publishedAt()));
            statement.setString(10, site.takedownReason());
            statement.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to save the clan website of guild {}", site.guildId(), e);
            throw new RuntimeException("Failed to save the clan website", e);
        }
        SiteInvalidator.signal(SiteInvalidator.Area.HUB);
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }

    private static ClanSite read(ResultSet rs) throws SQLException {
        long by = rs.getLong("updated_by");
        Long updatedBy = rs.wasNull() ? null : by;
        return new ClanSite(rs.getLong("guild_id"), rs.getBoolean("enabled"), rs.getBoolean("published"),
                Template.parse(rs.getString("template")).orElse(Template.CLASSIC), rs.getString("theme"), rs.getString("pages"),
                updatedBy, instant(rs, "updated_at"), instant(rs, "published_at"), rs.getString("takedown_reason"));
    }
}
