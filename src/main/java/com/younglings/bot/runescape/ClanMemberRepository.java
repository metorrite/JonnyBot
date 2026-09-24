package com.younglings.bot.runescape;

import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

@BService
public class ClanMemberRepository {
    private static final Logger log = LoggerFactory.getLogger(ClanMemberRepository.class);

    private final ConnectionSupplier connectionSupplier;

    public ClanMemberRepository(ConnectionSupplier connectionSupplier, RuneScapeDatabaseInitializer initializer) {
        this.connectionSupplier = connectionSupplier;
    }

    public record ClanMemberRow(long id, long guildId, String rsn, String clanRank,
                                 OffsetDateTime firstSeen, OffsetDateTime lastSeen, boolean active) {
    }

    /** Inserts a newly-seen member, or refreshes an existing one's rank/last-seen and marks it active again if it had left. */
    public void upsert(long guildId, String rsn, String clanRank) {
        String sql = """
                INSERT INTO younglings.clan_member (guild_id, rsn, clan_rank)
                VALUES (?, ?, ?)
                ON CONFLICT (guild_id, LOWER(rsn)) DO UPDATE SET
                    clan_rank = EXCLUDED.clan_rank, last_seen = NOW(), active = TRUE
                """;

        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setLong(1, guildId);
            statement.setString(2, rsn);
            statement.setString(3, clanRank);
            statement.executeUpdate();

        } catch (SQLException e) {
            log.error("Failed to upsert clan member '{}'", rsn, e);
            throw new RuntimeException("Failed to upsert clan member", e);
        }
    }

    public void markInactive(long guildId, String rsn) {
        String sql = "UPDATE younglings.clan_member SET active = FALSE WHERE guild_id = ? AND LOWER(rsn) = LOWER(?)";

        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setLong(1, guildId);
            statement.setString(2, rsn);
            statement.executeUpdate();

        } catch (SQLException e) {
            log.error("Failed to mark clan member '{}' inactive", rsn, e);
            throw new RuntimeException("Failed to mark clan member inactive", e);
        }
    }

    public List<ClanMemberRow> getAll(long guildId, boolean activeOnly) {
        String sql = "SELECT id, guild_id, rsn, clan_rank, first_seen, last_seen, active FROM younglings.clan_member WHERE guild_id = ?"
                + (activeOnly ? " AND active = TRUE" : "") + " ORDER BY LOWER(rsn)";

        List<ClanMemberRow> results = new ArrayList<>();

        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setLong(1, guildId);

            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    results.add(new ClanMemberRow(rs.getLong("id"), rs.getLong("guild_id"), rs.getString("rsn"),
                            rs.getString("clan_rank"), rs.getObject("first_seen", OffsetDateTime.class),
                            rs.getObject("last_seen", OffsetDateTime.class), rs.getBoolean("active")));
                }
            }
            return results;

        } catch (SQLException e) {
            log.error("Failed to get clan members for guild {}", guildId, e);
            throw new RuntimeException("Failed to get clan members", e);
        }
    }
}
