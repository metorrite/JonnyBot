package com.runeclans.bot.forms;

import com.runeclans.bot.forms.WebForms.Answer;
import com.runeclans.bot.forms.WebForms.Form;
import com.runeclans.bot.forms.WebForms.Kind;
import com.runeclans.bot.forms.WebForms.Question;
import com.runeclans.bot.testsupport.LocalDatabase;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The form SQL against the developer's own local database. Skipped when there isn't one; uses a made-up server id and removes what it adds. */
class WebFormRepositoryDatabaseTest {
    private static final long GUILD = 990_000_000_000_000_021L;

    private ConnectionSupplier connections;
    private WebFormRepository repository;

    @BeforeEach
    void setUp() throws Exception {
        connections = LocalDatabase.supplier();
        repository = new WebFormRepository(connections);
        clean();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (connections != null) clean();
    }

    private void clean() throws Exception {
        try (Connection c = connections.getConnection(); PreparedStatement s = c.prepareStatement("DELETE FROM younglings.web_form WHERE guild_id = ?")) {
            s.setLong(1, GUILD);
            s.executeUpdate();
        }
    }

    private Form blank() {
        return new Form(0, GUILD, "Apply", "Apply", "Hello", false, null, null, null, false, 24,
                List.of(new Question("rsn", "RuneScape name", Kind.RSN, true, List.of(), 12), new Question("pick", "Pick", Kind.CHOICE, false, List.of("A", "B"), 50)));
    }

    @Test
    void aFormKeepsItsQuestionsAndSettingsThroughSaveAndRead() {
        Form created = repository.create(blank());
        assertTrue(created.id() > 0);
        assertEquals(2, created.questions().size());
        assertEquals(List.of("A", "B"), created.questions().get(1).options());
        assertNull(created.panelId());

        Form edited = new Form(created.id(), GUILD, "Renamed", "T", "D", true, 5L, 6L, 7L, true, 0, List.of(created.questions().get(0)));
        assertTrue(repository.save(edited));
        Form read = repository.get(created.id()).orElseThrow();
        assertEquals("Renamed", read.name());
        assertTrue(read.enabled());
        assertEquals(5L, read.panelId());
        assertEquals(7L, read.roleId());
        assertEquals(1, read.questions().size());
        assertEquals(1, repository.list(GUILD).size());
    }

    @Test
    void aFormCannotBeSavedOrDeletedFromAnotherServer() {
        Form created = repository.create(blank());

        assertFalse(repository.save(new Form(created.id(), GUILD + 1, "Hijack", "", "", false, null, null, null, false, 0, List.of())));
        assertFalse(repository.delete(GUILD + 1, created.id()));
        assertEquals("Apply", repository.get(created.id()).orElseThrow().name());
    }

    @Test
    void submissionsAreRememberedPerPersonAndGoWhenTheFormIsDeleted() {
        Form created = repository.create(blank());
        assertNull(repository.lastSubmission(created.id(), 42));

        long id = repository.addSubmission(created, 42, List.of(new Answer("rsn", "RuneScape name", "Zezima")));
        repository.setTicket(id, 9);

        assertNotNull(repository.lastSubmission(created.id(), 42));
        assertNull(repository.lastSubmission(created.id(), 43));
        var shown = repository.submissions(created.id(), 10);
        assertEquals(1, shown.size());
        assertEquals("Zezima", shown.get(0).answers().get(0).value());
        assertEquals(9L, shown.get(0).ticketId());
        assertEquals(1, repository.countSubmissions(created.id()));

        assertTrue(repository.delete(GUILD, created.id()));
        assertEquals(0, repository.countSubmissions(created.id()));
    }
}
