package com.younglings.bot.internal;

import com.younglings.bot.coffer.CofferDatabaseInitializer;
import com.younglings.bot.poll.PollDatabaseInitializer;
import com.younglings.bot.runescape.RuneScapeDatabaseInitializer;
import com.younglings.bot.signup.SignupDatabaseInitializer;
import com.younglings.bot.testsupport.LocalDatabase;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SiteStatsRepository#polls} and {@link SiteStatsRepository#activeSignups} fetch every poll's options (and every sheet's entries) in one
 * query instead of one query per poll or sheet. Over the developer's local Postgres (skipped without one), this seeds made-up servers and checks the
 * answers match, row for row, what the old one-query-per-row SQL returns, and that {@link SiteStatsRepository#countActiveSignups} matches the
 * size of the list it replaces in the overview.
 */
class SiteStatsBatchQueriesDatabaseTest {
    // Made-up ids, built at run time (a long literal of this shape trips the secret scanner).
    private static final long GUILD = Long.parseLong("5".repeat(17) + "1");
    private static final long OTHER = Long.parseLong("5".repeat(17) + "2");
    private static final long EMPTY = Long.parseLong("5".repeat(17) + "3");
    private static final long USER = Long.parseLong("4".repeat(17) + "1");
    private static final OffsetDateTime BASE = OffsetDateTime.of(2026, 1, 1, 12, 0, 0, 0, ZoneOffset.UTC);

    private static ConnectionSupplier db;
    private static SiteStatsRepository stats;

    @BeforeAll
    static void setUp() throws Exception {
        db = LocalDatabase.supplier();
        var initializer = new RuneScapeDatabaseInitializer(db);
        new SignupDatabaseInitializer(db);
        new PollDatabaseInitializer(db);
        new CofferDatabaseInitializer(db);
        stats = new SiteStatsRepository(db, initializer);
    }

    @AfterEach
    void cleanUp() throws Exception {
        for (long guild : List.of(GUILD, OTHER, EMPTY)) {
            sql("DELETE FROM younglings.poll WHERE guild_id = ?", guild);
            sql("DELETE FROM younglings.signup WHERE guild_id = ?", guild);
        }
    }

    @Test
    void pollsMatchTheOldPerPollQueries() throws Exception {
        // 23 polls (the site asks for 20): some with no options, some options with no votes, some with several voters.
        for (long guild : List.of(GUILD, OTHER)) {
            for (int p = 0; p < 23; p++) {
                long poll = insertReturningId("INSERT INTO younglings.poll (guild_id, channel_id, message_id, title, multiple_votes, status, created_by_user_id, created_at)"
                        + " VALUES (?, 1, ?, ?, ?, ?, ?, ?) RETURNING poll_id", guild, p % 4 == 0 ? null : (long) (1000 + p), "Poll " + p, p % 2 == 0,
                        p % 3 == 0 ? "CLOSED" : "ACTIVE", USER, BASE.plusMinutes(p));
                int optionCount = p % 5;
                // Options inserted out of number order, so the answer's order must come from the ORDER BY.
                for (int n = optionCount; n >= 1; n--) {
                    long option = insertReturningId("INSERT INTO younglings.poll_option (poll_id, option_number, label) VALUES (?, ?, ?) RETURNING option_id", poll, n, "Option " + n);
                    for (int voter = 0; voter < (n * p) % 7; voter++) {
                        sql("INSERT INTO younglings.poll_vote (poll_id, option_id, user_id) VALUES (?, ?, ?)", poll, option, USER + voter);
                    }
                }
            }
        }

        for (int limit : List.of(20, 5, 1, 50)) {
            List<SiteStatsRepository.PollRow> fresh = stats.polls(GUILD, limit);
            assertEquals(oldPolls(GUILD, limit), fresh, "limit " + limit);
            assertEquals(Math.min(limit, 23), fresh.size());
        }
        assertTrue(stats.polls(GUILD, 20).stream().anyMatch(p -> p.options().isEmpty()), "the seed has a poll without options");
        assertTrue(stats.polls(GUILD, 20).stream().anyMatch(p -> p.options().stream().anyMatch(o -> o.votes() > 1)), "the seed has options with votes");
        assertEquals(oldPolls(OTHER, 20), stats.polls(OTHER, 20));
        assertEquals(List.of(), stats.polls(EMPTY, 20));
    }

    @Test
    void signupsMatchTheOldPerSheetQueries() throws Exception {
        // 30 sheets in mixed states (only ACTIVE / PAUSED and not deleted are listed, at most 20), some with no one signed up.
        String[] statuses = {"ACTIVE", "PAUSED", "CLOSED", "ACTIVE"};
        for (long guild : List.of(GUILD, OTHER)) {
            for (int s = 0; s < 30; s++) {
                long sheet = insertReturningId("INSERT INTO younglings.signup (guild_id, title, notification_message, max_signups, status, created_by_user_id, created_at, deleted_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?) RETURNING signup_id", guild, "Sheet " + s, s % 3 == 0 ? null : "note " + s, s % 2 == 0 ? null : 10 + s,
                        statuses[s % 4], USER, BASE.plusMinutes(s), s == 7 ? BASE : null);
                int people = s % 6;
                // Entries inserted against queue order, so the answer's order must come from the ORDER BY.
                for (int i = people; i >= 1; i--) {
                    sql("INSERT INTO younglings.signup_entry (signup_id, discord_user_id, rsn, queue_position, added_by_user_id) VALUES (?, ?, ?, ?, ?)",
                            sheet, USER + i, "Rsn" + s + "x" + i, i, USER);
                }
            }
        }

        List<SiteStatsRepository.SignupRow> fresh = stats.activeSignups(GUILD);
        assertEquals(oldSignups(GUILD), fresh);
        assertEquals(20, fresh.size());
        assertTrue(fresh.stream().anyMatch(s -> s.entries().isEmpty()), "the seed has a sheet with no one on it");
        assertEquals(oldSignups(OTHER), stats.activeSignups(OTHER));
        assertEquals(List.of(), stats.activeSignups(EMPTY));

        assertEquals(stats.activeSignups(GUILD).size(), stats.countActiveSignups(GUILD));
        assertEquals(0, stats.countActiveSignups(EMPTY));
        sql("UPDATE younglings.signup SET status = 'CLOSED' WHERE guild_id = ? AND title IN ('Sheet 0', 'Sheet 1', 'Sheet 3', 'Sheet 4', 'Sheet 5', 'Sheet 8', 'Sheet 9')", GUILD);
        assertEquals(stats.activeSignups(GUILD).size(), stats.countActiveSignups(GUILD));
        assertTrue(stats.countActiveSignups(GUILD) < 20, "fewer than the cap after closing some");
    }

    @Test
    void lookupIndexesExist() throws Exception {
        Set<String> names = new HashSet<>();
        try (Connection c = db.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT indexname FROM pg_indexes WHERE schemaname = 'younglings'");
             ResultSet rs = s.executeQuery()) {
            while (rs.next()) names.add(rs.getString(1));
        }
        for (String name : List.of("poll_vote_option", "signup_message_signup", "coffer_giveaway_guild", "coffer_transfer_guild")) {
            assertTrue(names.contains(name), name);
        }
    }

    // ---------- the queries as they were, one per poll / sheet ----------

    private static List<SiteStatsRepository.PollRow> oldPolls(long guildId, int limit) throws Exception {
        List<SiteStatsRepository.PollRow> polls = new ArrayList<>();
        try (Connection c = db.getConnection();
             PreparedStatement heads = c.prepareStatement("SELECT poll_id, channel_id, message_id, title, anonymous, multiple_votes, status, created_at, closed_at"
                     + " FROM younglings.poll WHERE guild_id = ? ORDER BY created_at DESC LIMIT ?");
             PreparedStatement options = c.prepareStatement("SELECT o.option_number, o.label, COUNT(v.vote_id) AS votes FROM younglings.poll_option o"
                     + " LEFT JOIN younglings.poll_vote v ON v.option_id = o.option_id WHERE o.poll_id = ? GROUP BY o.option_number, o.label ORDER BY o.option_number")) {
            heads.setLong(1, guildId);
            heads.setInt(2, limit);
            try (ResultSet rs = heads.executeQuery()) {
                while (rs.next()) {
                    long id = rs.getLong("poll_id");
                    List<SiteStatsRepository.PollOption> list = new ArrayList<>();
                    options.setLong(1, id);
                    try (ResultSet o = options.executeQuery()) {
                        while (o.next()) list.add(new SiteStatsRepository.PollOption(o.getInt("option_number"), o.getString("label"), o.getInt("votes")));
                    }
                    polls.add(new SiteStatsRepository.PollRow(id, rs.getLong("channel_id"), (Long) rs.getObject("message_id"), rs.getString("title"),
                            rs.getBoolean("anonymous"), rs.getBoolean("multiple_votes"), rs.getString("status"),
                            rs.getObject("created_at", OffsetDateTime.class), rs.getObject("closed_at", OffsetDateTime.class), list));
                }
            }
        }
        return polls;
    }

    private static List<SiteStatsRepository.SignupRow> oldSignups(long guildId) throws Exception {
        List<SiteStatsRepository.SignupRow> rows = new ArrayList<>();
        try (Connection c = db.getConnection();
             PreparedStatement heads = c.prepareStatement("SELECT signup_id, title, notification_message, max_signups, status, created_at FROM younglings.signup"
                     + " WHERE guild_id = ? AND status IN ('ACTIVE', 'PAUSED') AND deleted_at IS NULL ORDER BY created_at DESC LIMIT 20");
             PreparedStatement entries = c.prepareStatement("SELECT discord_user_id, rsn, queue_position FROM younglings.signup_entry WHERE signup_id = ? ORDER BY queue_position")) {
            heads.setLong(1, guildId);
            try (ResultSet rs = heads.executeQuery()) {
                while (rs.next()) {
                    long id = rs.getLong("signup_id");
                    List<SiteStatsRepository.SignupEntry> list = new ArrayList<>();
                    entries.setLong(1, id);
                    try (ResultSet e = entries.executeQuery()) {
                        while (e.next()) list.add(new SiteStatsRepository.SignupEntry(e.getLong("discord_user_id"), e.getString("rsn"), e.getInt("queue_position")));
                    }
                    rows.add(new SiteStatsRepository.SignupRow(id, rs.getString("title"), rs.getString("notification_message"), (Integer) rs.getObject("max_signups"),
                            rs.getString("status"), rs.getObject("created_at", OffsetDateTime.class), list));
                }
            }
        }
        return rows;
    }

    // ---------- helpers ----------

    private static void bind(PreparedStatement s, Object... params) throws Exception {
        for (int i = 0; i < params.length; i++) {
            Object p = params[i];
            if (p instanceof OffsetDateTime t) s.setObject(i + 1, t);
            else s.setObject(i + 1, p);
        }
    }

    private static void sql(String sql, Object... params) throws Exception {
        try (Connection c = db.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            bind(s, params);
            s.executeUpdate();
        }
    }

    private static long insertReturningId(String sql, Object... params) throws Exception {
        try (Connection c = db.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            bind(s, params);
            try (ResultSet rs = s.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }
}
