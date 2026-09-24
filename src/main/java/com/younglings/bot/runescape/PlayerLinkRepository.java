package com.younglings.bot.runescape;

import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

@BService
public class PlayerLinkRepository {
    private static final Logger log = LoggerFactory.getLogger(PlayerLinkRepository.class);

    private final ConnectionSupplier connectionSupplier;

    public PlayerLinkRepository(ConnectionSupplier connectionSupplier, RuneScapeDatabaseInitializer initializer) {
        this.connectionSupplier = connectionSupplier;
    }

    private static VerificationAttempt mapAttempt(ResultSet rs) throws SQLException {
        return new VerificationAttempt(
                rs.getLong("attempt_id"),
                rs.getLong("guild_id"),
                rs.getLong("discord_user_id"),
                rs.getString("rsn"),
                rs.getString("assigned_hairstyle"),
                rs.getString("assigned_hair_color"),
                rs.getString("assigned_skin_tone"),
                rs.getString("status")
        );
    }

    private static PlayerLink mapLink(ResultSet rs) throws SQLException {
        return new PlayerLink(
                rs.getLong("link_id"),
                rs.getLong("guild_id"),
                rs.getLong("discord_user_id"),
                rs.getString("rsn"),
                rs.getString("verification_method"),
                rs.getObject("verified_at", java.time.OffsetDateTime.class)
        );
    }

    // --- Verification attempts ---

    public long createAttempt(long guildId, long discordUserId, String rsn,
                               String hairstyle, String hairColor, String skinTone) {
        String sql = """
                INSERT INTO younglings.player_verification_attempt
                    (guild_id, discord_user_id, rsn, assigned_hairstyle, assigned_hair_color, assigned_skin_tone)
                VALUES (?, ?, ?, ?, ?, ?)
                """;

        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {

            statement.setLong(1, guildId);
            statement.setLong(2, discordUserId);
            statement.setString(3, rsn);
            statement.setString(4, hairstyle);
            statement.setString(5, hairColor);
            statement.setString(6, skinTone);
            statement.executeUpdate();

            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (keys.next()) return keys.getLong(1);
            }

            throw new SQLException("No attempt_id returned after creating verification attempt.");

        } catch (SQLException e) {
            log.error("Failed to create verification attempt for '{}' (guild {})", rsn, guildId, e);
            throw new RuntimeException("Failed to create verification attempt", e);
        }
    }

    public VerificationAttempt getAttempt(long attemptId) {
        String sql = """
                SELECT attempt_id, guild_id, discord_user_id, rsn, assigned_hairstyle,
                       assigned_hair_color, assigned_skin_tone, status
                FROM younglings.player_verification_attempt
                WHERE attempt_id = ?
                """;

        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setLong(1, attemptId);

            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? mapAttempt(rs) : null;
            }

        } catch (SQLException e) {
            log.error("Failed to get verification attempt {}", attemptId, e);
            throw new RuntimeException("Failed to get verification attempt", e);
        }
    }

    public void resolveAttempt(long attemptId, String status, long resolvedByUserId) {
        String sql = """
                UPDATE younglings.player_verification_attempt
                SET status = ?, resolved_at = NOW(), resolved_by_user_id = ?
                WHERE attempt_id = ?
                """;

        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setString(1, status);
            statement.setLong(2, resolvedByUserId);
            statement.setLong(3, attemptId);
            statement.executeUpdate();

            log.info("Resolved verification attempt {} as {} (by {})", attemptId, status, resolvedByUserId);

        } catch (SQLException e) {
            log.error("Failed to resolve verification attempt {}", attemptId, e);
            throw new RuntimeException("Failed to resolve verification attempt", e);
        }
    }

    public List<VerificationAttempt> getPendingAttempts(long guildId) {
        String sql = """
                SELECT attempt_id, guild_id, discord_user_id, rsn, assigned_hairstyle,
                       assigned_hair_color, assigned_skin_tone, status
                FROM younglings.player_verification_attempt
                WHERE guild_id = ? AND status = 'PENDING'
                ORDER BY requested_at ASC
                """;

        List<VerificationAttempt> results = new ArrayList<>();

        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setLong(1, guildId);

            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) results.add(mapAttempt(rs));
            }

            return results;

        } catch (SQLException e) {
            log.error("Failed to get pending verification attempts for guild {}", guildId, e);
            throw new RuntimeException("Failed to get pending verification attempts", e);
        }
    }

    /** This user's own in-progress attempt, if any — so starting a new one doesn't leave orphaned duplicates behind. */
    public VerificationAttempt getPendingAttemptForUser(long guildId, long discordUserId) {
        String sql = """
                SELECT attempt_id, guild_id, discord_user_id, rsn, assigned_hairstyle,
                       assigned_hair_color, assigned_skin_tone, status
                FROM younglings.player_verification_attempt
                WHERE guild_id = ? AND discord_user_id = ? AND status = 'PENDING'
                ORDER BY requested_at DESC
                LIMIT 1
                """;

        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setLong(1, guildId);
            statement.setLong(2, discordUserId);

            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? mapAttempt(rs) : null;
            }

        } catch (SQLException e) {
            log.error("Failed to get pending verification attempt for user {}", discordUserId, e);
            throw new RuntimeException("Failed to get pending verification attempt", e);
        }
    }

    // --- Confirmed links ---

    public void createLink(long guildId, long discordUserId, String rsn, String verificationMethod) {
        String sql = """
                INSERT INTO younglings.player_link (guild_id, discord_user_id, rsn, verification_method)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (guild_id, LOWER(rsn)) DO UPDATE SET
                    discord_user_id = EXCLUDED.discord_user_id,
                    verification_method = EXCLUDED.verification_method,
                    verified_at = NOW()
                """;

        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setLong(1, guildId);
            statement.setLong(2, discordUserId);
            statement.setString(3, rsn);
            statement.setString(4, verificationMethod);
            statement.executeUpdate();

            log.info("Linked RSN '{}' to Discord user {} in guild {}", rsn, discordUserId, guildId);

        } catch (SQLException e) {
            log.error("Failed to link RSN '{}' to user {}", rsn, discordUserId, e);
            throw new RuntimeException("Failed to link RSN", e);
        }
    }

    public List<PlayerLink> getLinksForUser(long guildId, long discordUserId) {
        String sql = """
                SELECT link_id, guild_id, discord_user_id, rsn, verification_method, verified_at
                FROM younglings.player_link
                WHERE guild_id = ? AND discord_user_id = ?
                ORDER BY verified_at ASC
                """;

        List<PlayerLink> results = new ArrayList<>();

        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setLong(1, guildId);
            statement.setLong(2, discordUserId);

            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) results.add(mapLink(rs));
            }

            return results;

        } catch (SQLException e) {
            log.error("Failed to get links for user {}", discordUserId, e);
            throw new RuntimeException("Failed to get player links", e);
        }
    }

    public PlayerLink getLinkForRsn(long guildId, String rsn) {
        String sql = """
                SELECT link_id, guild_id, discord_user_id, rsn, verification_method, verified_at
                FROM younglings.player_link
                WHERE guild_id = ? AND LOWER(rsn) = LOWER(?)
                """;

        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setLong(1, guildId);
            statement.setString(2, rsn);

            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? mapLink(rs) : null;
            }

        } catch (SQLException e) {
            log.error("Failed to get link for RSN '{}'", rsn, e);
            throw new RuntimeException("Failed to get player link", e);
        }
    }

    public void deleteLink(long guildId, long linkId) {
        String sql = "DELETE FROM younglings.player_link WHERE guild_id = ? AND link_id = ?";

        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setLong(1, guildId);
            statement.setLong(2, linkId);
            statement.executeUpdate();

            log.info("Unlinked player_link {} in guild {}", linkId, guildId);

        } catch (SQLException e) {
            log.error("Failed to unlink {}", linkId, e);
            throw new RuntimeException("Failed to unlink RSN", e);
        }
    }

    /** All currently-linked RSNs across the guild, for the stats scheduler to poll. */
    public List<PlayerLink> getAllLinks(long guildId) {
        String sql = """
                SELECT link_id, guild_id, discord_user_id, rsn, verification_method, verified_at
                FROM younglings.player_link
                WHERE guild_id = ?
                """;

        List<PlayerLink> results = new ArrayList<>();

        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setLong(1, guildId);

            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) results.add(mapLink(rs));
            }

            return results;

        } catch (SQLException e) {
            log.error("Failed to get all player links for guild {}", guildId, e);
            throw new RuntimeException("Failed to get all player links", e);
        }
    }

    /** Every confirmed link across every guild — used by the stats scheduler, which isn't scoped to one guild. */
    public List<PlayerLink> getAllLinksAcrossGuilds() {
        String sql = """
                SELECT link_id, guild_id, discord_user_id, rsn, verification_method, verified_at
                FROM younglings.player_link
                """;

        List<PlayerLink> results = new ArrayList<>();

        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rs = statement.executeQuery()) {

            while (rs.next()) results.add(mapLink(rs));
            return results;

        } catch (SQLException e) {
            log.error("Failed to get all player links across guilds", e);
            throw new RuntimeException("Failed to get all player links", e);
        }
    }

    // --- Stats snapshots ---

    /** Saves a snapshot and returns its generated {@code snapshot_id}, so per-skill rows can reference it. */
    public long saveSnapshot(long guildId, String rsn, RuneScapeProfile profile, String skillsJson) {
        String sql = """
                INSERT INTO younglings.player_stats_snapshot
                    (rsn, guild_id, total_level, total_xp, combat_level, quests_complete,
                     quests_started, quests_not_started, skills_json)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;

        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {

            statement.setString(1, rsn);
            statement.setLong(2, guildId);
            statement.setInt(3, profile.totalLevel());
            statement.setLong(4, profile.totalXp());
            statement.setInt(5, profile.combatLevel());
            statement.setInt(6, profile.questsComplete());
            statement.setInt(7, profile.questsStarted());
            statement.setInt(8, profile.questsNotStarted());
            statement.setString(9, skillsJson);
            statement.executeUpdate();

            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (keys.next()) return keys.getLong(1);
            }
            throw new SQLException("No snapshot_id returned after saving stats snapshot.");

        } catch (SQLException e) {
            log.error("Failed to save stats snapshot for '{}'", rsn, e);
            throw new RuntimeException("Failed to save stats snapshot", e);
        }
    }

    /**
     * Same as {@link #saveSnapshot}, but with an explicit {@code snapshotAt} instead of relying on
     * the column's {@code NOW()} default — only real callers need "right now"; backfilling test/
     * historical data needs to place rows in the past instead.
     */
    public long saveSnapshotAt(long guildId, String rsn, java.time.OffsetDateTime snapshotAt, RuneScapeProfile profile, String skillsJson) {
        String sql = """
                INSERT INTO younglings.player_stats_snapshot
                    (rsn, guild_id, snapshot_at, total_level, total_xp, combat_level, quests_complete,
                     quests_started, quests_not_started, skills_json)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;

        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {

            statement.setString(1, rsn);
            statement.setLong(2, guildId);
            statement.setObject(3, snapshotAt);
            statement.setInt(4, profile.totalLevel());
            statement.setLong(5, profile.totalXp());
            statement.setInt(6, profile.combatLevel());
            statement.setInt(7, profile.questsComplete());
            statement.setInt(8, profile.questsStarted());
            statement.setInt(9, profile.questsNotStarted());
            statement.setString(10, skillsJson);
            statement.executeUpdate();

            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (keys.next()) return keys.getLong(1);
            }
            throw new SQLException("No snapshot_id returned after saving stats snapshot.");

        } catch (SQLException e) {
            log.error("Failed to save backdated stats snapshot for '{}'", rsn, e);
            throw new RuntimeException("Failed to save backdated stats snapshot", e);
        }
    }

    public void saveSkillSnapshot(long snapshotId, List<SkillValue> skills) {
        if (skills.isEmpty()) return;

        String sql = """
                INSERT INTO younglings.player_skill_snapshot (snapshot_id, skill_id, level, xp, rank)
                VALUES (?, ?, ?, ?, ?)
                """;

        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            for (SkillValue skill : skills) {
                statement.setLong(1, snapshotId);
                statement.setInt(2, skill.skillId());
                statement.setInt(3, skill.level());
                statement.setLong(4, skill.xp());
                statement.setInt(5, skill.rank());
                statement.addBatch();
            }
            statement.executeBatch();

        } catch (SQLException e) {
            log.error("Failed to save skill snapshot rows for snapshot {}", snapshotId, e);
            throw new RuntimeException("Failed to save skill snapshot", e);
        }
    }

    /** Per-skill breakdown for one snapshot, ordered by skill ID (the game's own skill order). */
    public List<SkillValue> getSkillsForSnapshot(long snapshotId) {
        String sql = """
                SELECT skill_id, level, xp, rank
                FROM younglings.player_skill_snapshot
                WHERE snapshot_id = ?
                ORDER BY skill_id
                """;

        List<SkillValue> results = new ArrayList<>();

        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setLong(1, snapshotId);

            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    results.add(new SkillValue(rs.getInt("skill_id"), rs.getInt("level"),
                            rs.getLong("xp"), rs.getInt("rank")));
                }
            }
            return results;

        } catch (SQLException e) {
            log.error("Failed to get skills for snapshot {}", snapshotId, e);
            throw new RuntimeException("Failed to get skill snapshot", e);
        }
    }

    /** Every snapshot since {@code since}, oldest first — used to find "first vs. latest this period" totals. */
    public List<StatsSnapshotRow> getSnapshotsSince(long guildId, String rsn, java.time.OffsetDateTime since) {
        String sql = """
                SELECT snapshot_id, snapshot_at, total_level, total_xp, combat_level,
                       quests_complete, quests_started, quests_not_started
                FROM younglings.player_stats_snapshot
                WHERE guild_id = ? AND LOWER(rsn) = LOWER(?) AND snapshot_at >= ?
                ORDER BY snapshot_at ASC
                """;

        List<StatsSnapshotRow> results = new ArrayList<>();

        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setLong(1, guildId);
            statement.setString(2, rsn);
            statement.setObject(3, since);

            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) results.add(mapSnapshotRow(rs));
            }
            return results;

        } catch (SQLException e) {
            log.error("Failed to get snapshots since {} for '{}'", since, rsn, e);
            throw new RuntimeException("Failed to get snapshots since", e);
        }
    }

    /** Every skill's XP at every poll since {@code since}, oldest first — grouped/reduced in Java to find per-skill gains over the period. */
    public List<SkillHistoryPoint> getAllSkillsXpHistorySince(long guildId, String rsn, java.time.OffsetDateTime since) {
        String sql = """
                SELECT sk.skill_id, s.snapshot_at, sk.xp
                FROM younglings.player_skill_snapshot sk
                JOIN younglings.player_stats_snapshot s ON s.snapshot_id = sk.snapshot_id
                WHERE s.guild_id = ? AND LOWER(s.rsn) = LOWER(?) AND s.snapshot_at >= ?
                ORDER BY sk.skill_id, s.snapshot_at ASC
                """;

        List<SkillHistoryPoint> results = new ArrayList<>();

        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setLong(1, guildId);
            statement.setString(2, rsn);
            statement.setObject(3, since);

            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    results.add(new SkillHistoryPoint(rs.getInt("skill_id"),
                            rs.getObject("snapshot_at", java.time.OffsetDateTime.class), rs.getLong("xp")));
                }
            }
            return results;

        } catch (SQLException e) {
            log.error("Failed to get all-skills XP history since {} for '{}'", since, rsn, e);
            throw new RuntimeException("Failed to get all-skills XP history", e);
        }
    }

    public record SkillHistoryPoint(int skillId, java.time.OffsetDateTime timestamp, long xp) {
    }

    /** Activities recorded since {@code since}, oldest first — the monthly recap's data source for "times capped" and "most challenged". */
    public List<PlayerActivity> getActivitiesSince(long guildId, String rsn, java.time.OffsetDateTime since) {
        String sql = """
                SELECT activity_date, activity_text, activity_details
                FROM younglings.player_activity
                WHERE guild_id = ? AND LOWER(rsn) = LOWER(?) AND recorded_at >= ?
                ORDER BY recorded_at ASC
                """;

        List<PlayerActivity> results = new ArrayList<>();

        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setLong(1, guildId);
            statement.setString(2, rsn);
            statement.setObject(3, since);

            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    results.add(new PlayerActivity(rs.getString("activity_date"),
                            rs.getString("activity_text"), rs.getString("activity_details")));
                }
            }
            return results;

        } catch (SQLException e) {
            log.error("Failed to get activities since {} for '{}'", since, rsn, e);
            throw new RuntimeException("Failed to get activities since", e);
        }
    }

    /** One skill's XP at each poll since {@code since}, oldest first — the XP-over-time chart's data source. */
    public List<SkillXpPoint> getSkillXpHistory(long guildId, String rsn, int skillId, java.time.OffsetDateTime since) {
        String sql = """
                SELECT s.snapshot_at, sk.xp
                FROM younglings.player_skill_snapshot sk
                JOIN younglings.player_stats_snapshot s ON s.snapshot_id = sk.snapshot_id
                WHERE s.guild_id = ? AND LOWER(s.rsn) = LOWER(?) AND sk.skill_id = ? AND s.snapshot_at >= ?
                ORDER BY s.snapshot_at ASC
                """;

        List<SkillXpPoint> results = new ArrayList<>();

        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setLong(1, guildId);
            statement.setString(2, rsn);
            statement.setInt(3, skillId);
            statement.setObject(4, since);

            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    results.add(new SkillXpPoint(rs.getObject("snapshot_at", java.time.OffsetDateTime.class), rs.getLong("xp")));
                }
            }
            return results;

        } catch (SQLException e) {
            log.error("Failed to get skill XP history for '{}' skill {}", rsn, skillId, e);
            throw new RuntimeException("Failed to get skill XP history", e);
        }
    }

    /** Most recent snapshot for {@code rsn}, or {@code null} if it's never been polled. */
    public StatsSnapshotRow getLatestSnapshot(long guildId, String rsn) {
        String sql = """
                SELECT snapshot_id, snapshot_at, total_level, total_xp, combat_level,
                       quests_complete, quests_started, quests_not_started
                FROM younglings.player_stats_snapshot
                WHERE guild_id = ? AND LOWER(rsn) = LOWER(?)
                ORDER BY snapshot_at DESC
                LIMIT 1
                """;

        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setLong(1, guildId);
            statement.setString(2, rsn);

            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? mapSnapshotRow(rs) : null;
            }

        } catch (SQLException e) {
            log.error("Failed to get latest snapshot for '{}'", rsn, e);
            throw new RuntimeException("Failed to get latest stats snapshot", e);
        }
    }

    /** Every snapshot for {@code rsn}, most recent first, capped at {@code limit} — the trend/history view's data source. */
    public List<StatsSnapshotRow> getSnapshotHistory(long guildId, String rsn, int limit) {
        String sql = """
                SELECT snapshot_id, snapshot_at, total_level, total_xp, combat_level,
                       quests_complete, quests_started, quests_not_started
                FROM younglings.player_stats_snapshot
                WHERE guild_id = ? AND LOWER(rsn) = LOWER(?)
                ORDER BY snapshot_at DESC
                LIMIT ?
                """;

        List<StatsSnapshotRow> results = new ArrayList<>();

        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setLong(1, guildId);
            statement.setString(2, rsn);
            statement.setInt(3, limit);

            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) results.add(mapSnapshotRow(rs));
            }
            return results;

        } catch (SQLException e) {
            log.error("Failed to get snapshot history for '{}'", rsn, e);
            throw new RuntimeException("Failed to get snapshot history", e);
        }
    }

    private static StatsSnapshotRow mapSnapshotRow(ResultSet rs) throws SQLException {
        return new StatsSnapshotRow(
                rs.getLong("snapshot_id"),
                rs.getObject("snapshot_at", java.time.OffsetDateTime.class),
                rs.getInt("total_level"),
                rs.getLong("total_xp"),
                rs.getInt("combat_level"),
                rs.getInt("quests_complete"),
                rs.getInt("quests_started"),
                rs.getInt("quests_not_started")
        );
    }

    public record StatsSnapshotRow(long snapshotId, java.time.OffsetDateTime snapshotAt, int totalLevel, long totalXp,
                                    int combatLevel, int questsComplete, int questsStarted, int questsNotStarted) {
    }

    // --- Activity history ---

    /** Inserts every activity not already recorded for this player (naturally deduped — see the unique index). */
    public void saveActivities(long guildId, String rsn, List<PlayerActivity> activities) {
        if (activities.isEmpty()) return;

        String sql = """
                INSERT INTO younglings.player_activity (guild_id, rsn, activity_date, activity_text, activity_details)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (guild_id, LOWER(rsn), activity_date, activity_text) DO NOTHING
                """;

        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            for (PlayerActivity activity : activities) {
                statement.setLong(1, guildId);
                statement.setString(2, rsn);
                statement.setString(3, activity.date());
                statement.setString(4, activity.text());
                statement.setString(5, activity.details());
                statement.addBatch();
            }
            statement.executeBatch();

        } catch (SQLException e) {
            log.error("Failed to save activities for '{}'", rsn, e);
            throw new RuntimeException("Failed to save activities", e);
        }
    }

    /** Most recently *recorded* activities for {@code rsn} (i.e. by when we first saw them, not the in-game date string). */
    public List<PlayerActivity> getRecentActivities(long guildId, String rsn, int limit) {
        String sql = """
                SELECT activity_date, activity_text, activity_details
                FROM younglings.player_activity
                WHERE guild_id = ? AND LOWER(rsn) = LOWER(?)
                ORDER BY recorded_at DESC
                LIMIT ?
                """;

        List<PlayerActivity> results = new ArrayList<>();

        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setLong(1, guildId);
            statement.setString(2, rsn);
            statement.setInt(3, limit);

            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    results.add(new PlayerActivity(rs.getString("activity_date"),
                            rs.getString("activity_text"), rs.getString("activity_details")));
                }
            }
            return results;

        } catch (SQLException e) {
            log.error("Failed to get recent activities for '{}'", rsn, e);
            throw new RuntimeException("Failed to get recent activities", e);
        }
    }
}
