package com.younglings.bot.lookup;

import com.younglings.bot.testsupport.LocalDatabase;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;

import static org.junit.jupiter.api.Assertions.*;

/** Runs the repository's SQL against the developer's local Postgres (skipped where there isn't one). */
class LookupReminderRepositoryDatabaseTest {
    private static final long GUILD = 987654321012399L;
    private ConnectionSupplier db;
    private LookupReminderRepository repository;

    @BeforeEach
    void setUp() throws Exception {
        db = LocalDatabase.supplier();
        repository = new LookupReminderRepository(db);
        clean();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (db != null) clean();
    }

    private void clean() throws Exception {
        try (Connection c = db.getConnection(); PreparedStatement s = c.prepareStatement("DELETE FROM younglings.lookup_reminder WHERE guild_id = ?")) {
            s.setLong(1, GUILD);
            s.executeUpdate();
        }
    }

    @Test
    void everyReminderStartsOff() {
        assertTrue(repository.forGuild(GUILD).stream().noneMatch(LookupReminder::enabled));
    }

    @Test
    void savedSettingsComeBackAndAPostIsRemembered() {
        repository.save(new LookupReminder(GUILD, ReminderKind.VOS, true, 42L, 7L, ""));
        LookupReminder r = repository.get(GUILD, ReminderKind.VOS);
        assertTrue(r.enabled());
        assertEquals(42L, r.channelId());
        assertEquals(7L, r.roleId());
        assertTrue(repository.enabled(ReminderKind.VOS).stream().anyMatch(x -> x.guildId() == GUILD));

        repository.markPosted(GUILD, ReminderKind.VOS, "2026-10-19T10");
        assertEquals("2026-10-19T10", repository.get(GUILD, ReminderKind.VOS).lastKey());

        // saving settings again keeps the memory of the last post, and clearing the role works
        repository.save(new LookupReminder(GUILD, ReminderKind.VOS, true, 42L, null, ""));
        LookupReminder again = repository.get(GUILD, ReminderKind.VOS);
        assertNull(again.roleId());
        assertEquals("2026-10-19T10", again.lastKey());
    }
}
