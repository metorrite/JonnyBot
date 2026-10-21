package com.younglings.bot.ticket;

import com.younglings.bot.testsupport.LocalDatabase;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;

import static org.junit.jupiter.api.Assertions.*;

/** Runs the repository's SQL against the developer's local Postgres (skipped where there isn't one). */
class HelpExtrasRepositoryDatabaseTest {
    private static final long GUILD = 987654321012777L;
    private static final long TICKET = 987654321012778L;
    private ConnectionSupplier db;
    private HelpExtrasRepository repository;

    @BeforeEach
    void setUp() throws Exception {
        db = LocalDatabase.supplier();
        repository = new HelpExtrasRepository(db);
        clean();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (db != null) clean();
    }

    private void clean() throws Exception {
        try (Connection c = db.getConnection()) {
            try (PreparedStatement s = c.prepareStatement("DELETE FROM younglings.help_extras WHERE guild_id = ?")) {
                s.setLong(1, GUILD);
                s.executeUpdate();
            }
            try (PreparedStatement s = c.prepareStatement("DELETE FROM younglings.help_ticket_link WHERE ticket_a IN (?, ?) OR ticket_b IN (?, ?)")) {
                s.setLong(1, TICKET);
                s.setLong(2, TICKET + 1);
                s.setLong(3, TICKET);
                s.setLong(4, TICKET + 1);
                s.executeUpdate();
            }
            try (PreparedStatement s = c.prepareStatement("DELETE FROM younglings.help_ticket_event WHERE ticket_id = ?")) {
                s.setLong(1, TICKET);
                s.executeUpdate();
            }
        }
    }

    @Test
    void aServerThatNeverSavedGetsTheOffDefaults() {
        assertEquals(HelpExtras.defaults(GUILD), repository.get(GUILD));
    }

    @Test
    void savedExtrasComeBackAndASecondSaveReplacesThem() {
        repository.save(new HelpExtras(GUILD, true, 6, 2, true, "Vorago, Telos", true, 45, false, 96, "Master=50, Grandmaster=100"));
        assertEquals(new HelpExtras(GUILD, true, 6, 2, true, "Vorago, Telos", true, 45, false, 96, "Master=50, Grandmaster=100"), repository.get(GUILD));
        repository.save(new HelpExtras(GUILD, false, 12, 3, false, "Solak", false, 7, true, 0, ""));
        assertEquals(new HelpExtras(GUILD, false, 12, 3, false, "Solak", false, 7, true, 0, ""), repository.get(GUILD));
    }

    @Test
    void aTicketBecomesOneEventOnlyOnce() {
        assertTrue(repository.eventOf(TICKET).isEmpty());
        assertTrue(repository.claimEvent(TICKET, 5L));
        assertFalse(repository.claimEvent(TICKET, 6L), "a second click can't claim it too");
        assertTrue(repository.eventOf(TICKET).isEmpty(), "still being made");
        repository.setEvent(TICKET, 42L);
        assertEquals(42L, repository.eventOf(TICKET).getAsLong());
        assertFalse(repository.claimEvent(TICKET, 7L));
        repository.forgetEvent(TICKET);
        assertTrue(repository.eventOf(TICKET).isEmpty());
        assertTrue(repository.claimEvent(TICKET, 8L), "freed after a failure");
    }

    @Test
    void twoTicketsAreLinkedOnceWhicheverWayRound() {
        assertTrue(repository.linkedTo(TICKET).isEmpty());
        assertTrue(repository.link(TICKET, TICKET + 1, 5L));
        assertFalse(repository.link(TICKET + 1, TICKET, 6L), "the same pair the other way round is the same link");
        assertEquals(java.util.List.of(TICKET + 1), repository.linkedTo(TICKET));
        assertEquals(java.util.List.of(TICKET), repository.linkedTo(TICKET + 1));
    }
}
