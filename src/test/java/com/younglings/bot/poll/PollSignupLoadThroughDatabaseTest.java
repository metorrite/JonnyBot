package com.younglings.bot.poll;

import com.younglings.bot.commands.poll.PollService;
import com.younglings.bot.commands.poll.PollSession;
import com.younglings.bot.commands.signup.SignupService;
import com.younglings.bot.commands.signup.SignupType;
import com.younglings.bot.signup.SignupDatabaseInitializer;
import com.younglings.bot.signup.SignupRepository;
import com.younglings.bot.testsupport.LocalDatabase;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two processes on one database (the gateway and the website's {@code api} process): a poll or signup the "other" process writes after this
 * one started is found on a cache miss, so its buttons and votes work without a restart (scaling inventory M5). Skipped without a local Postgres.
 */
class PollSignupLoadThroughDatabaseTest {
    private static final long GUILD = 987654321012351L;
    private static final long CHANNEL = 987654321012352L;
    private static final long OWNER = 21L;
    private static final long VOTER = 22L;

    private ConnectionSupplier db;
    private PollRepository polls;
    private SignupRepository signups;

    @BeforeEach
    void setUp() throws Exception {
        db = LocalDatabase.supplier();
        polls = new PollRepository(db, new PollDatabaseInitializer(db));
        signups = new SignupRepository(db, new SignupDatabaseInitializer(db));
        clean();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (db != null) clean();
    }

    private void clean() throws Exception {
        try (Connection c = db.getConnection()) {
            for (String sql : new String[]{
                    "DELETE FROM younglings.poll_vote WHERE poll_id IN (SELECT poll_id FROM younglings.poll WHERE guild_id = ?)",
                    "DELETE FROM younglings.poll_option WHERE poll_id IN (SELECT poll_id FROM younglings.poll WHERE guild_id = ?)",
                    "DELETE FROM younglings.poll WHERE guild_id = ?",
                    "DELETE FROM younglings.signup_entry WHERE signup_id IN (SELECT signup_id FROM younglings.signup WHERE guild_id = ?)",
                    "DELETE FROM younglings.signup_message WHERE signup_id IN (SELECT signup_id FROM younglings.signup WHERE guild_id = ?)",
                    "DELETE FROM younglings.signup WHERE guild_id = ?"}) {
                try (PreparedStatement s = c.prepareStatement(sql)) {
                    s.setLong(1, GUILD);
                    s.executeUpdate();
                }
            }
        }
    }

    /** What the other process does when it makes a poll: the row, its options, then the posted message's id. */
    private long pollMadeElsewhere(String title) {
        long pollId = polls.createPoll(GUILD, CHANNEL, title, false, false, OWNER);
        polls.addOption(pollId, 1, "Yes");
        polls.addOption(pollId, 2, "No");
        polls.saveMessageId(pollId, 555L);
        return pollId;
    }

    @Test
    void aPollMadeAfterStartUpIsFoundAndCanBeVotedIn() {
        PollService service = new PollService(polls); // "started" before the poll exists
        long pollId = pollMadeElsewhere("Raid night?");

        PollSession found = service.getSessionById(pollId);
        assertNotNull(found);
        assertEquals("Raid night?", found.title());
        assertEquals(555L, found.messageId());
        assertEquals(List.of("Yes", "No"), service.getOptions(pollId).stream().map(o -> o.label()).toList());

        long yes = service.getOptions(pollId).getFirst().optionId();
        assertEquals(PollService.VoteResult.ADDED, service.toggleVote(pollId, yes, VOTER));
        assertEquals(1, service.totalVotes(pollId));
        assertEquals(List.of(pollId), service.activePolls(GUILD).stream().map(PollSession::pollId).toList());
    }

    @Test
    void aPollEndedElsewhereLeavesThePanelAndTakesNoMoreVotes() {
        long pollId = pollMadeElsewhere("Old poll");
        PollService service = new PollService(polls); // loaded at start-up
        assertNotNull(service.getSessionById(pollId));

        polls.closePoll(pollId); // the other process ends it
        assertTrue(service.activePolls(GUILD).isEmpty());
        assertNull(service.getSessionById(pollId));
    }

    @Test
    void aSignupMadeAfterStartUpIsFoundAndCanBeJoined() {
        SignupService service = new SignupService(signups);
        long signupId = signups.createSignup(GUILD, "Boss mass", SignupType.QUEUE, null, null, null, OWNER, null);

        assertNotNull(service.getSessionById(signupId));
        assertEquals("Boss mass", service.getSessionFromComponentId("signup_join:" + signupId).title());
        assertTrue(service.addUser(null, signupId, VOTER, "Some Rsn", null));
        assertEquals(1, service.getEntries(signupId).size());
    }

    @Test
    void aSignupDeletedElsewhereIsNotFound() {
        long signupId = signups.createSignup(GUILD, "Gone soon", SignupType.QUEUE, null, null, null, OWNER, null);
        signups.deleteSignupForAdminArchive(signupId);
        SignupService service = new SignupService(signups);

        assertNull(service.getSessionById(signupId));
    }
}
