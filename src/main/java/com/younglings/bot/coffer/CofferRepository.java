package com.younglings.bot.coffer;

import com.younglings.bot.commands.coffer.CofferDonation;
import com.younglings.bot.commands.coffer.CofferHolder;
import com.younglings.bot.commands.coffer.CofferTransfer;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

@BService
public class CofferRepository {
    private static final Logger log = LoggerFactory.getLogger(CofferRepository.class);

    private final ConnectionSupplier connectionSupplier;

    public CofferRepository(ConnectionSupplier connectionSupplier, CofferDatabaseInitializer initializer) {
        this.connectionSupplier = connectionSupplier;
    }

    // --- Mappers ---

    private static CofferDonation mapDonation(ResultSet rs) throws SQLException {
        return new CofferDonation(
                rs.getLong("donation_id"),
                rs.getLong("guild_id"),
                rs.getString("donor_name"),
                rs.getLong("amount"),
                rs.getLong("submitted_by_discord_id"),
                rs.getObject("submitted_at", OffsetDateTime.class)
        );
    }

    private static CofferHolder mapHolder(ResultSet rs) throws SQLException {
        return new CofferHolder(
                rs.getLong("guild_id"),
                rs.getLong("discord_user_id"),
                rs.getLong("amount")
        );
    }

    private static CofferTransfer mapTransfer(ResultSet rs) throws SQLException {
        OffsetDateTime resolvedAt = rs.getObject("resolved_at") == null
                ? null : rs.getObject("resolved_at", OffsetDateTime.class);
        return new CofferTransfer(
                rs.getLong("transfer_id"),
                rs.getLong("guild_id"),
                rs.getLong("from_discord_id"),
                rs.getLong("to_discord_id"),
                rs.getLong("amount"),
                rs.getString("status"),
                rs.getObject("created_at", OffsetDateTime.class),
                resolvedAt
        );
    }

    // --- Holder balance (shared helper) ---

    private void adjustHolderBalance(Connection conn, long guildId, long discordUserId, long delta) throws SQLException {
        String sql = """
                INSERT INTO younglings.coffer_holder (guild_id, discord_user_id, amount)
                VALUES (?, ?, ?)
                ON CONFLICT (guild_id, discord_user_id)
                DO UPDATE SET amount = younglings.coffer_holder.amount + EXCLUDED.amount
                """;

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, guildId);
            ps.setLong(2, discordUserId);
            ps.setLong(3, delta);
            ps.execute();
        }
    }

    // --- Donations ---

    public void insertDonation(long guildId, String donorName, long amount, long submittedBy) {
        String insertDonation = """
                INSERT INTO younglings.coffer_donation (guild_id, donor_name, amount, submitted_by_discord_id)
                VALUES (?, ?, ?, ?)
                """;

        try (Connection conn = connectionSupplier.getConnection()) {
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement ps = conn.prepareStatement(insertDonation)) {
                    ps.setLong(1, guildId);
                    ps.setString(2, donorName);
                    ps.setLong(3, amount);
                    ps.setLong(4, submittedBy);
                    ps.execute();
                }
                adjustHolderBalance(conn, guildId, submittedBy, amount);
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            log.error("Failed to insert coffer donation", e);
            throw new RuntimeException("Failed to insert coffer donation", e);
        }
    }

    public List<CofferDonation> getRecentDonations(long guildId, int limit) {
        String sql = """
                SELECT * FROM younglings.coffer_donation
                WHERE guild_id = ?
                ORDER BY submitted_at DESC
                LIMIT ?
                """;

        try (Connection conn = connectionSupplier.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setLong(1, guildId);
            ps.setInt(2, limit);
            ResultSet rs = ps.executeQuery();

            List<CofferDonation> donations = new ArrayList<>();
            while (rs.next()) donations.add(mapDonation(rs));
            return donations;

        } catch (SQLException e) {
            log.error("Failed to get recent coffer donations", e);
            throw new RuntimeException("Failed to get recent coffer donations", e);
        }
    }

    // --- Holders ---

    public List<CofferHolder> getHolders(long guildId) {
        String sql = """
                SELECT * FROM younglings.coffer_holder
                WHERE guild_id = ? AND amount > 0
                ORDER BY amount DESC
                """;

        try (Connection conn = connectionSupplier.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setLong(1, guildId);
            ResultSet rs = ps.executeQuery();

            List<CofferHolder> holders = new ArrayList<>();
            while (rs.next()) holders.add(mapHolder(rs));
            return holders;

        } catch (SQLException e) {
            log.error("Failed to get coffer holders", e);
            throw new RuntimeException("Failed to get coffer holders", e);
        }
    }

    public long getHolderBalance(long guildId, long discordUserId) {
        String sql = """
                SELECT amount FROM younglings.coffer_holder
                WHERE guild_id = ? AND discord_user_id = ?
                """;

        try (Connection conn = connectionSupplier.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setLong(1, guildId);
            ps.setLong(2, discordUserId);
            ResultSet rs = ps.executeQuery();

            return rs.next() ? rs.getLong("amount") : 0L;

        } catch (SQLException e) {
            log.error("Failed to get coffer holder balance", e);
            throw new RuntimeException("Failed to get coffer holder balance", e);
        }
    }

    // --- Transfers ---

    public void executeTransfer(long guildId, long fromId, long toId, long amount) {
        String insertTransfer = """
                INSERT INTO younglings.coffer_transfer
                    (guild_id, from_discord_id, to_discord_id, amount, status, resolved_at)
                VALUES (?, ?, ?, ?, 'COMPLETED', NOW())
                """;

        try (Connection conn = connectionSupplier.getConnection()) {
            conn.setAutoCommit(false);
            try {
                adjustHolderBalance(conn, guildId, fromId, -amount);
                adjustHolderBalance(conn, guildId, toId, amount);

                try (PreparedStatement ps = conn.prepareStatement(insertTransfer)) {
                    ps.setLong(1, guildId);
                    ps.setLong(2, fromId);
                    ps.setLong(3, toId);
                    ps.setLong(4, amount);
                    ps.execute();
                }

                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            log.error("Failed to execute coffer transfer", e);
            throw new RuntimeException("Failed to execute coffer transfer", e);
        }
    }

    public long insertPendingTransfer(long guildId, long fromId, long toId, long amount) {
        String sql = """
                INSERT INTO younglings.coffer_transfer
                    (guild_id, from_discord_id, to_discord_id, amount, status)
                VALUES (?, ?, ?, ?, 'PENDING')
                RETURNING transfer_id
                """;

        try (Connection conn = connectionSupplier.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setLong(1, guildId);
            ps.setLong(2, fromId);
            ps.setLong(3, toId);
            ps.setLong(4, amount);
            ResultSet rs = ps.executeQuery();

            if (rs.next()) return rs.getLong("transfer_id");
            throw new SQLException("No transfer_id returned");

        } catch (SQLException e) {
            log.error("Failed to insert pending transfer", e);
            throw new RuntimeException("Failed to insert pending transfer", e);
        }
    }

    public CofferTransfer getPendingTransfer(long transferId) {
        String sql = "SELECT * FROM younglings.coffer_transfer WHERE transfer_id = ?";

        try (Connection conn = connectionSupplier.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setLong(1, transferId);
            ResultSet rs = ps.executeQuery();
            return rs.next() ? mapTransfer(rs) : null;

        } catch (SQLException e) {
            log.error("Failed to get pending transfer", e);
            throw new RuntimeException("Failed to get pending transfer", e);
        }
    }

    public void acceptTransfer(long transferId) {
        String getTransfer = "SELECT * FROM younglings.coffer_transfer WHERE transfer_id = ? AND status = 'PENDING'";
        String updateStatus = """
                UPDATE younglings.coffer_transfer
                SET status = 'COMPLETED', resolved_at = NOW()
                WHERE transfer_id = ?
                """;

        try (Connection conn = connectionSupplier.getConnection()) {
            conn.setAutoCommit(false);
            try {
                CofferTransfer transfer;
                try (PreparedStatement ps = conn.prepareStatement(getTransfer)) {
                    ps.setLong(1, transferId);
                    ResultSet rs = ps.executeQuery();
                    if (!rs.next()) throw new SQLException("Transfer not found or already resolved");
                    transfer = mapTransfer(rs);
                }

                adjustHolderBalance(conn, transfer.guildId(), transfer.fromDiscordId(), -transfer.amount());
                adjustHolderBalance(conn, transfer.guildId(), transfer.toDiscordId(), transfer.amount());

                try (PreparedStatement ps = conn.prepareStatement(updateStatus)) {
                    ps.setLong(1, transferId);
                    ps.execute();
                }

                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            log.error("Failed to accept coffer transfer", e);
            throw new RuntimeException("Failed to accept coffer transfer", e);
        }
    }

    public void rejectTransfer(long transferId) {
        String sql = """
                UPDATE younglings.coffer_transfer
                SET status = 'REJECTED', resolved_at = NOW()
                WHERE transfer_id = ? AND status = 'PENDING'
                """;

        try (Connection conn = connectionSupplier.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setLong(1, transferId);
            ps.executeUpdate();

        } catch (SQLException e) {
            log.error("Failed to reject coffer transfer", e);
            throw new RuntimeException("Failed to reject coffer transfer", e);
        }
    }

    // --- Giveaways ---

    public void insertGiveaway(long guildId, long givenById, long recipientId, long amount, String description) {
        String insertGiveaway = """
                INSERT INTO younglings.coffer_giveaway
                    (guild_id, given_by_discord_id, recipient_discord_id, amount, description)
                VALUES (?, ?, ?, ?, ?)
                """;

        try (Connection conn = connectionSupplier.getConnection()) {
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement ps = conn.prepareStatement(insertGiveaway)) {
                    ps.setLong(1, guildId);
                    ps.setLong(2, givenById);
                    ps.setLong(3, recipientId);
                    ps.setLong(4, amount);
                    ps.setString(5, description);
                    ps.execute();
                }
                adjustHolderBalance(conn, guildId, givenById, -amount);
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            log.error("Failed to insert coffer giveaway", e);
            throw new RuntimeException("Failed to insert coffer giveaway", e);
        }
    }
}
