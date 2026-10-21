package com.younglings.bot.clanprofile;

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

/** Storage for clans' recruiting posts: one row per server that wrote one. */
@BService
public class ClanRecruitingRepository {
    private static final Logger log = LoggerFactory.getLogger(ClanRecruitingRepository.class);

    private final ConnectionSupplier connectionSupplier;

    public ClanRecruitingRepository(ConnectionSupplier connectionSupplier) {
        this.connectionSupplier = connectionSupplier;
        SchemaBootstrapper.run(connectionSupplier, log, "clan recruiting posts", List.of(
                "CREATE SCHEMA IF NOT EXISTS younglings;",
                """
                CREATE TABLE IF NOT EXISTS younglings.clan_recruiting (
                    guild_id BIGINT PRIMARY KEY,
                    post TEXT NOT NULL DEFAULT '',
                    apply_form_id BIGINT NULL,
                    invite_url TEXT NULL,
                    updated_at TIMESTAMPTZ NULL
                );
                """
        ));
    }

    /** The server's post, or an empty one. */
    public ClanRecruiting of(long guildId) {
        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT post, apply_form_id, invite_url, updated_at FROM younglings.clan_recruiting WHERE guild_id = ?")) {
            statement.setLong(1, guildId);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) return ClanRecruiting.empty(guildId);
                long form = rs.getLong("apply_form_id");
                Long formId = rs.wasNull() ? null : form;
                Timestamp at = rs.getTimestamp("updated_at");
                return new ClanRecruiting(guildId, rs.getString("post"), formId, rs.getString("invite_url"), at == null ? null : at.toInstant());
            }
        } catch (SQLException e) {
            log.error("Failed to read the recruiting post of guild {}", guildId, e);
            throw new RuntimeException("Failed to read the recruiting post", e);
        }
    }

    public void save(ClanRecruiting r) {
        String sql = "INSERT INTO younglings.clan_recruiting (guild_id, post, apply_form_id, invite_url, updated_at) VALUES (?, ?, ?, ?, ?) "
                + "ON CONFLICT (guild_id) DO UPDATE SET post = EXCLUDED.post, apply_form_id = EXCLUDED.apply_form_id, invite_url = EXCLUDED.invite_url, updated_at = EXCLUDED.updated_at";
        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, r.guildId());
            statement.setString(2, r.post());
            if (r.applyFormId() == null) statement.setNull(3, Types.BIGINT);
            else statement.setLong(3, r.applyFormId());
            statement.setString(4, r.inviteUrl());
            statement.setTimestamp(5, r.updatedAt() == null ? null : Timestamp.from(r.updatedAt()));
            statement.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to save the recruiting post of guild {}", r.guildId(), e);
            throw new RuntimeException("Failed to save the recruiting post", e);
        }
        SiteInvalidator.signal(SiteInvalidator.Area.HUB);
    }
}
