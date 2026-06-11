package com.younglings.bot.poll;

import com.younglings.bot.commands.poll.PollOption;
import com.younglings.bot.commands.poll.PollSession;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.*;

@BService
public class PollRepository {
    private static final Logger log = LoggerFactory.getLogger(PollRepository.class);

    private final ConnectionSupplier connectionSupplier;

    public PollRepository(ConnectionSupplier connectionSupplier, PollDatabaseInitializer initializer) {
        this.connectionSupplier = connectionSupplier;
    }

    // --- Mappers ---

    private static PollSession mapPoll(ResultSet rs) throws SQLException {
        Long messageId = rs.getObject("message_id") == null ? null : rs.getLong("message_id");
        return new PollSession(
                rs.getLong("poll_id"),
                rs.getLong("guild_id"),
                rs.getLong("channel_id"),
                messageId,
                rs.getString("title"),
                rs.getBoolean("anonymous"),
                rs.getBoolean("multiple_votes"),
                rs.getString("status")
        );
    }

    private static PollOption mapOption(ResultSet rs) throws SQLException {
        return new PollOption(
                rs.getLong("option_id"),
                rs.getLong("poll_id"),
                rs.getInt("option_number"),
                rs.getString("label")
        );
    }

    // --- Poll CRUD ---

    public long createPoll(long guildId, long channelId, String title, boolean anonymous,
                            boolean multipleVotes, long createdByUserId) {
        String sql = """
                INSERT INTO younglings.poll
                    (guild_id, channel_id, title, anonymous, multiple_votes, created_by_user_id)
                VALUES (?, ?, ?, ?, ?, ?)
                RETURNING poll_id
                """;
        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, guildId);
            statement.setLong(2, channelId);
            statement.setString(3, title);
            statement.setBoolean(4, anonymous);
            statement.setBoolean(5, multipleVotes);
            statement.setLong(6, createdByUserId);
            try (ResultSet rs = statement.executeQuery()) {
                if (rs.next()) return rs.getLong("poll_id");
            }
            throw new SQLException("No poll_id returned.");
        } catch (SQLException e) {
            log.error("Failed to create poll for guild {}", guildId, e);
            throw new RuntimeException("Failed to create poll", e);
        }
    }

    public long addOption(long pollId, int optionNumber, String label) {
        String sql = """
                INSERT INTO younglings.poll_option (poll_id, option_number, label)
                VALUES (?, ?, ?)
                RETURNING option_id
                """;
        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, pollId);
            statement.setInt(2, optionNumber);
            statement.setString(3, label);
            try (ResultSet rs = statement.executeQuery()) {
                if (rs.next()) return rs.getLong("option_id");
            }
            throw new SQLException("No option_id returned.");
        } catch (SQLException e) {
            log.error("Failed to add option to poll {}", pollId, e);
            throw new RuntimeException("Failed to add poll option", e);
        }
    }

    public void saveMessageId(long pollId, long messageId) {
        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE younglings.poll SET message_id = ? WHERE poll_id = ?")) {
            statement.setLong(1, messageId);
            statement.setLong(2, pollId);
            statement.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to save message ID for poll {}", pollId, e);
            throw new RuntimeException("Failed to save poll message ID", e);
        }
    }

    public List<PollSession> getAllActivePolls() {
        String sql = """
                SELECT poll_id, guild_id, channel_id, message_id, title, anonymous, multiple_votes, status
                FROM younglings.poll
                WHERE status = 'ACTIVE'
                ORDER BY created_at ASC
                """;
        List<PollSession> polls = new ArrayList<>();
        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rs = statement.executeQuery()) {
            while (rs.next()) polls.add(mapPoll(rs));
            return polls;
        } catch (SQLException e) {
            log.error("Failed to load active polls", e);
            throw new RuntimeException("Failed to load active polls", e);
        }
    }

    public PollSession getPollById(long pollId) {
        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT poll_id, guild_id, channel_id, message_id, title, anonymous, multiple_votes, status FROM younglings.poll WHERE poll_id = ?")) {
            statement.setLong(1, pollId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? mapPoll(rs) : null;
            }
        } catch (SQLException e) {
            log.error("Failed to get poll {}", pollId, e);
            throw new RuntimeException("Failed to get poll", e);
        }
    }

    public PollSession findPollByTitle(long guildId, String query) {
        String sql = """
                SELECT poll_id, guild_id, channel_id, message_id, title, anonymous, multiple_votes, status
                FROM younglings.poll
                WHERE guild_id = ? AND status = 'ACTIVE' AND LOWER(title) LIKE LOWER(?)
                ORDER BY created_at DESC
                LIMIT 1
                """;
        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, guildId);
            statement.setString(2, "%" + query + "%");
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? mapPoll(rs) : null;
            }
        } catch (SQLException e) {
            log.error("Failed to find poll by title in guild {}", guildId, e);
            throw new RuntimeException("Failed to find poll", e);
        }
    }

    public List<PollOption> getOptions(long pollId) {
        String sql = """
                SELECT option_id, poll_id, option_number, label
                FROM younglings.poll_option
                WHERE poll_id = ?
                ORDER BY option_number ASC
                """;
        List<PollOption> options = new ArrayList<>();
        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, pollId);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) options.add(mapOption(rs));
            }
            return options;
        } catch (SQLException e) {
            log.error("Failed to get options for poll {}", pollId, e);
            throw new RuntimeException("Failed to get poll options", e);
        }
    }

    // --- Voting ---

    public boolean addVote(long pollId, long optionId, long userId) {
        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO younglings.poll_vote (poll_id, option_id, user_id) VALUES (?, ?, ?) ON CONFLICT DO NOTHING")) {
            statement.setLong(1, pollId);
            statement.setLong(2, optionId);
            statement.setLong(3, userId);
            return statement.executeUpdate() > 0;
        } catch (SQLException e) {
            log.error("Failed to add vote for poll {}", pollId, e);
            throw new RuntimeException("Failed to add vote", e);
        }
    }

    public boolean removeVote(long pollId, long optionId, long userId) {
        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "DELETE FROM younglings.poll_vote WHERE poll_id = ? AND option_id = ? AND user_id = ?")) {
            statement.setLong(1, pollId);
            statement.setLong(2, optionId);
            statement.setLong(3, userId);
            return statement.executeUpdate() > 0;
        } catch (SQLException e) {
            log.error("Failed to remove vote for poll {}", pollId, e);
            throw new RuntimeException("Failed to remove vote", e);
        }
    }

    public void removeAllVotesForUser(long pollId, long userId) {
        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "DELETE FROM younglings.poll_vote WHERE poll_id = ? AND user_id = ?")) {
            statement.setLong(1, pollId);
            statement.setLong(2, userId);
            statement.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to clear votes for user {} in poll {}", userId, pollId, e);
            throw new RuntimeException("Failed to clear user votes", e);
        }
    }

    public boolean hasVotedForOption(long pollId, long optionId, long userId) {
        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT 1 FROM younglings.poll_vote WHERE poll_id = ? AND option_id = ? AND user_id = ? LIMIT 1")) {
            statement.setLong(1, pollId);
            statement.setLong(2, optionId);
            statement.setLong(3, userId);
            try (ResultSet rs = statement.executeQuery()) { return rs.next(); }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to check vote", e);
        }
    }

    public boolean hasVotedInPoll(long pollId, long userId) {
        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT 1 FROM younglings.poll_vote WHERE poll_id = ? AND user_id = ? LIMIT 1")) {
            statement.setLong(1, pollId);
            statement.setLong(2, userId);
            try (ResultSet rs = statement.executeQuery()) { return rs.next(); }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to check poll vote", e);
        }
    }

    public Map<Long, Integer> getVoteCounts(long pollId) {
        Map<Long, Integer> counts = new HashMap<>();
        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT option_id, COUNT(*) AS vote_count FROM younglings.poll_vote WHERE poll_id = ? GROUP BY option_id")) {
            statement.setLong(1, pollId);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) counts.put(rs.getLong("option_id"), rs.getInt("vote_count"));
            }
            return counts;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to get vote counts", e);
        }
    }

    public Map<Long, List<Long>> getVotersByOption(long pollId) {
        Map<Long, List<Long>> voters = new LinkedHashMap<>();
        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT option_id, user_id FROM younglings.poll_vote WHERE poll_id = ? ORDER BY option_id, voted_at ASC")) {
            statement.setLong(1, pollId);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    voters.computeIfAbsent(rs.getLong("option_id"), k -> new ArrayList<>())
                          .add(rs.getLong("user_id"));
                }
            }
            return voters;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to get voters by option", e);
        }
    }

    public void closePoll(long pollId) {
        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE younglings.poll SET status = 'CLOSED', closed_at = NOW() WHERE poll_id = ?")) {
            statement.setLong(1, pollId);
            statement.executeUpdate();
            log.info("Closed poll {}", pollId);
        } catch (SQLException e) {
            log.error("Failed to close poll {}", pollId, e);
            throw new RuntimeException("Failed to close poll", e);
        }
    }
}
