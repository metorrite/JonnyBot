package com.younglings.bot.runescape;

import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Backs the clan points/promotion system (see {@code com.younglings.bot.tracking.ClanPointsService}):
 * the per-guild rank ladder and its point thresholds, the daily/Citadel point values, the permanent
 * award ledger, and each member's running total and promotion-needed flag.
 */
@BService
public class ClanPointsRepository {
    private static final Logger log = LoggerFactory.getLogger(ClanPointsRepository.class);

    // The standard Jagex clan rank titles, lowest to highest — confirmed against the wiki's own Clan
    // Chat rank table (runescape.wiki/w/RuneScape:Clan_Chat#Ranks), which is also where TrackingIconCatalog's
    // rank icons (images/ranks/<order>.png) come from ("{RankName}_clan_rank.png", "Deputy Owner"
    // itself is "Deputy_owner_clan_rank.png" — lowercase "owner"). 12 tiers, not the 11 first guessed
    // here — "Overseer" sits between Coordinator and Deputy Owner and was missed originally. Used only
    // to seed a guild's clan_rank_config the first time it's read empty; every tier's name and
    // point_threshold are admin-editable afterward (a clan can rename any tier), so this list is never
    // consulted again once a guild has rows.
    private static final List<String> STANDARD_RANK_NAMES = List.of(
            "Recruit", "Corporal", "Sergeant", "Lieutenant", "Captain", "General",
            "Admin", "Organiser", "Coordinator", "Overseer", "Deputy Owner", "Owner");

    private final ConnectionSupplier connectionSupplier;

    public ClanPointsRepository(ConnectionSupplier connectionSupplier, RuneScapeDatabaseInitializer initializer) {
        this.connectionSupplier = connectionSupplier;
    }

    public record RankConfigRow(long id, long guildId, String rankName, int rankOrder, long pointThreshold) {}

    /**
     * Ordered lowest to highest. Seeds {@link #STANDARD_RANK_NAMES} on first call for a guild that has
     * none yet, all thresholds starting at 0. If a guild's row count doesn't match
     * {@link #STANDARD_RANK_NAMES}'s current size, it was seeded under an earlier (wrong-tier-count)
     * version of that list — safe to wipe and reseed fresh rather than trying to patch individual rows
     * in place, since a threshold only ever defaults to 0 until an admin sets real values, and that's
     * all any earlier seed could have had this soon after the feature shipped.
     */
    public List<RankConfigRow> getRanksOrdered(long guildId) {
        List<RankConfigRow> existing = queryRanks(guildId);
        if (existing.size() == STANDARD_RANK_NAMES.size()) return existing;

        if (!existing.isEmpty()) {
            log.info("Guild {}'s clan_rank_config has {} row(s), expected {} — reseeding from the current standard rank list.",
                    guildId, existing.size(), STANDARD_RANK_NAMES.size());
            deleteAllRanks(guildId);
        }
        seedStandardRanks(guildId);
        return queryRanks(guildId);
    }

    private void deleteAllRanks(long guildId) {
        String sql = "DELETE FROM younglings.clan_rank_config WHERE guild_id = ?";

        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setLong(1, guildId);
            statement.executeUpdate();

        } catch (SQLException e) {
            log.error("Failed to clear clan rank config for guild {}", guildId, e);
            throw new RuntimeException("Failed to clear clan rank config", e);
        }
    }

    private List<RankConfigRow> queryRanks(long guildId) {
        String sql = "SELECT id, guild_id, rank_name, rank_order, point_threshold FROM younglings.clan_rank_config WHERE guild_id = ? ORDER BY rank_order";

        List<RankConfigRow> results = new ArrayList<>();
        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setLong(1, guildId);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    results.add(new RankConfigRow(rs.getLong("id"), rs.getLong("guild_id"), rs.getString("rank_name"),
                            rs.getInt("rank_order"), rs.getLong("point_threshold")));
                }
            }
            return results;

        } catch (SQLException e) {
            log.error("Failed to get clan rank config for guild {}", guildId, e);
            throw new RuntimeException("Failed to get clan rank config", e);
        }
    }

    private void seedStandardRanks(long guildId) {
        String sql = """
                INSERT INTO younglings.clan_rank_config (guild_id, rank_name, rank_order, point_threshold)
                VALUES (?, ?, ?, 0)
                ON CONFLICT (guild_id, rank_order) DO NOTHING
                """;

        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            for (int order = 0; order < STANDARD_RANK_NAMES.size(); order++) {
                statement.setLong(1, guildId);
                statement.setString(2, STANDARD_RANK_NAMES.get(order));
                statement.setInt(3, order);
                statement.addBatch();
            }
            statement.executeBatch();

        } catch (SQLException e) {
            log.error("Failed to seed standard clan ranks for guild {}", guildId, e);
            throw new RuntimeException("Failed to seed standard clan ranks", e);
        }
    }

    public void setRankThreshold(long guildId, long rankConfigId, long pointThreshold) {
        String sql = "UPDATE younglings.clan_rank_config SET point_threshold = ? WHERE id = ? AND guild_id = ?";

        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setLong(1, pointThreshold);
            statement.setLong(2, rankConfigId);
            statement.setLong(3, guildId);
            statement.executeUpdate();

        } catch (SQLException e) {
            log.error("Failed to set rank threshold for guild {} rank {}", guildId, rankConfigId, e);
            throw new RuntimeException("Failed to set rank threshold", e);
        }
    }

    public record PointsSettings(long dailyMembershipPoints, long citadelVisitPoints, long citadelCapPoints) {}

    /** All-zero defaults if this guild has never set anything — nothing is awarded until an admin configures at least one value above 0. */
    public PointsSettings getSettings(long guildId) {
        String sql = "SELECT daily_membership_points, citadel_visit_points, citadel_cap_points FROM younglings.clan_points_settings WHERE guild_id = ?";

        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setLong(1, guildId);
            try (ResultSet rs = statement.executeQuery()) {
                if (rs.next()) {
                    return new PointsSettings(rs.getLong("daily_membership_points"), rs.getLong("citadel_visit_points"), rs.getLong("citadel_cap_points"));
                }
                return new PointsSettings(0, 0, 0);
            }

        } catch (SQLException e) {
            log.error("Failed to get clan points settings for guild {}", guildId, e);
            throw new RuntimeException("Failed to get clan points settings", e);
        }
    }

    public void setSettings(long guildId, long dailyMembershipPoints, long citadelVisitPoints, long citadelCapPoints) {
        String sql = """
                INSERT INTO younglings.clan_points_settings (guild_id, daily_membership_points, citadel_visit_points, citadel_cap_points)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (guild_id) DO UPDATE SET
                    daily_membership_points = EXCLUDED.daily_membership_points,
                    citadel_visit_points = EXCLUDED.citadel_visit_points,
                    citadel_cap_points = EXCLUDED.citadel_cap_points
                """;

        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setLong(1, guildId);
            statement.setLong(2, dailyMembershipPoints);
            statement.setLong(3, citadelVisitPoints);
            statement.setLong(4, citadelCapPoints);
            statement.executeUpdate();

        } catch (SQLException e) {
            log.error("Failed to set clan points settings for guild {}", guildId, e);
            throw new RuntimeException("Failed to set clan points settings", e);
        }
    }

    /**
     * Records one point award and adds it to the member's running total, unless this exact
     * (guild, rsn, awardType, awardedForDate) combination was already awarded — safe to call every
     * day for every member without ever double-crediting a re-run. Returns {@code true} if this call
     * actually awarded something new.
     */
    public boolean awardPoints(long guildId, String rsn, String awardType, long points, LocalDate awardedForDate) {
        String insertAward = """
                INSERT INTO younglings.clan_points_award (guild_id, rsn, award_type, points, awarded_for_date)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (guild_id, LOWER(rsn), award_type, awarded_for_date) DO NOTHING
                """;
        String upsertTotal = """
                INSERT INTO younglings.clan_member_points (guild_id, rsn, total_points)
                VALUES (?, ?, ?)
                ON CONFLICT (guild_id, LOWER(rsn)) DO UPDATE SET
                    total_points = clan_member_points.total_points + EXCLUDED.total_points
                """;

        try (Connection connection = connectionSupplier.getConnection()) {
            try (PreparedStatement statement = connection.prepareStatement(insertAward)) {
                statement.setLong(1, guildId);
                statement.setString(2, rsn);
                statement.setString(3, awardType);
                statement.setLong(4, points);
                statement.setObject(5, awardedForDate);
                if (statement.executeUpdate() == 0) return false; // already awarded — no-op
            }

            try (PreparedStatement statement = connection.prepareStatement(upsertTotal)) {
                statement.setLong(1, guildId);
                statement.setString(2, rsn);
                statement.setLong(3, points);
                statement.executeUpdate();
            }
            return true;

        } catch (SQLException e) {
            log.error("Failed to award '{}' points to '{}' in guild {}", awardType, rsn, guildId, e);
            throw new RuntimeException("Failed to award points", e);
        }
    }

    public record MemberPointsRow(String rsn, long totalPoints, boolean promotionNeeded, LocalDate promotionNeededSince) {}

    public MemberPointsRow getMemberPoints(long guildId, String rsn) {
        String sql = "SELECT rsn, total_points, promotion_needed, promotion_needed_since FROM younglings.clan_member_points WHERE guild_id = ? AND LOWER(rsn) = LOWER(?)";

        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setLong(1, guildId);
            statement.setString(2, rsn);
            try (ResultSet rs = statement.executeQuery()) {
                if (rs.next()) {
                    return new MemberPointsRow(rs.getString("rsn"), rs.getLong("total_points"),
                            rs.getBoolean("promotion_needed"), rs.getObject("promotion_needed_since", LocalDate.class));
                }
                return new MemberPointsRow(rsn, 0, false, null);
            }

        } catch (SQLException e) {
            log.error("Failed to get member points for '{}' in guild {}", rsn, guildId, e);
            throw new RuntimeException("Failed to get member points", e);
        }
    }

    /** Every member currently flagged as needing a promotion — what the daily Clan Report is built from. */
    public List<MemberPointsRow> getAllNeedingPromotion(long guildId) {
        String sql = "SELECT rsn, total_points, promotion_needed, promotion_needed_since FROM younglings.clan_member_points WHERE guild_id = ? AND promotion_needed = TRUE";

        List<MemberPointsRow> results = new ArrayList<>();
        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setLong(1, guildId);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    results.add(new MemberPointsRow(rs.getString("rsn"), rs.getLong("total_points"),
                            rs.getBoolean("promotion_needed"), rs.getObject("promotion_needed_since", LocalDate.class)));
                }
            }
            return results;

        } catch (SQLException e) {
            log.error("Failed to get members needing promotion for guild {}", guildId, e);
            throw new RuntimeException("Failed to get members needing promotion", e);
        }
    }

    /**
     * Sets whether {@code rsn} currently needs a promotion. {@code promotion_needed_since} is set to
     * {@code today} the first time this flips to {@code true} and left alone while it stays true
     * (so the report can show how long someone's been waiting), then cleared back to {@code NULL} the
     * moment it flips to {@code false}.
     */
    public void setPromotionNeeded(long guildId, String rsn, boolean needed, LocalDate today) {
        String sql = """
                INSERT INTO younglings.clan_member_points (guild_id, rsn, total_points, promotion_needed, promotion_needed_since)
                VALUES (?, ?, 0, ?, ?)
                ON CONFLICT (guild_id, LOWER(rsn)) DO UPDATE SET
                    promotion_needed = EXCLUDED.promotion_needed,
                    promotion_needed_since = CASE
                        WHEN EXCLUDED.promotion_needed AND clan_member_points.promotion_needed_since IS NULL THEN EXCLUDED.promotion_needed_since
                        WHEN NOT EXCLUDED.promotion_needed THEN NULL
                        ELSE clan_member_points.promotion_needed_since
                    END
                """;

        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setLong(1, guildId);
            statement.setString(2, rsn);
            statement.setBoolean(3, needed);
            statement.setObject(4, needed ? today : null);
            statement.executeUpdate();

        } catch (SQLException e) {
            log.error("Failed to set promotion-needed for '{}' in guild {}", rsn, guildId, e);
            throw new RuntimeException("Failed to set promotion-needed", e);
        }
    }
}
