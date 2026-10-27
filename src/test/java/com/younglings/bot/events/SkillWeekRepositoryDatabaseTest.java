package com.younglings.bot.events;

import com.younglings.bot.testsupport.LocalDatabase;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Skill of the week's settings and weeks over real SQL (T-541): off until saved on, one claim per week moving the rota on, close once. Skipped without a local database. */
class SkillWeekRepositoryDatabaseTest {
    private static final long GUILD = Long.parseLong("9".repeat(17) + "1");
    private static final long ADMIN = Long.parseLong("5".repeat(18));
    private static final Instant MONDAY = Instant.parse("2026-10-26T00:00:00Z");
    private static ConnectionSupplier supplier;
    private SkillWeekRepository repository;

    @BeforeAll
    static void connect() throws Exception {
        supplier = LocalDatabase.supplier();
    }

    @BeforeEach
    void setUp() {
        repository = new SkillWeekRepository(supplier);
    }

    @AfterEach
    void cleanUp() throws Exception {
        try (Connection c = supplier.getConnection()) {
            for (String table : List.of("skill_week", "skill_week_settings")) {
                try (PreparedStatement s = c.prepareStatement("DELETE FROM younglings." + table + " WHERE guild_id = ?")) {
                    s.setLong(1, GUILD);
                    s.executeUpdate();
                }
            }
        }
    }

    @Test
    void offUntilSavedOnAndAChangedRotaStartsAgain() {
        assertEquals(SkillWeekRepository.Settings.OFF, repository.settings(GUILD));
        assertFalse(repository.enabledGuilds().contains(GUILD));
        repository.save(GUILD, true, List.of(9, 15), ADMIN);
        assertEquals(new SkillWeekRepository.Settings(true, List.of(9, 15), 0, ADMIN), repository.settings(GUILD));
        assertTrue(repository.enabledGuilds().contains(GUILD));

        assertTrue(repository.claim(GUILD, MONDAY, 9));
        assertEquals(1, repository.settings(GUILD).position());
        repository.save(GUILD, true, List.of(9, 15), ADMIN);
        assertEquals(1, repository.settings(GUILD).position(), "the same rota keeps its place");
        repository.save(GUILD, false, List.of(25, 9), ADMIN);
        assertEquals(0, repository.settings(GUILD).position());
        assertFalse(repository.enabledGuilds().contains(GUILD));
    }

    @Test
    void aWeekIsClaimedOnceAndClosedOnce() {
        repository.save(GUILD, true, List.of(9, 15), ADMIN);
        assertTrue(repository.claim(GUILD, MONDAY, 9));
        assertFalse(repository.claim(GUILD, MONDAY, 15), "a second pass or a restart doesn't open the week again");
        assertEquals(1, repository.settings(GUILD).position());

        SkillWeekRepository.Week week = repository.week(GUILD, MONDAY).orElseThrow();
        assertEquals(9, week.skillId());
        assertNull(week.eventId());
        repository.setEvent(GUILD, MONDAY, 42);
        assertEquals(42L, repository.week(GUILD, MONDAY).orElseThrow().eventId());
        assertTrue(repository.unclosedBefore(MONDAY.plusSeconds(1)).stream().anyMatch(w -> w.guildId() == GUILD));

        assertTrue(repository.close(GUILD, MONDAY, "[]"));
        assertFalse(repository.close(GUILD, MONDAY, "[]"));
        assertEquals("[]", repository.recent(GUILD, 4).get(0).winners());
        assertFalse(repository.unclosedBefore(MONDAY.plusSeconds(1)).stream().anyMatch(w -> w.guildId() == GUILD));
    }

    @Test
    void aReleasedWeekCanBeClaimedAgainWithTheSameSkill() {
        repository.save(GUILD, true, List.of(9, 15), ADMIN);
        assertTrue(repository.claim(GUILD, MONDAY, 9));
        repository.release(GUILD, MONDAY);
        assertTrue(repository.week(GUILD, MONDAY).isEmpty());
        assertEquals(0, repository.settings(GUILD).position());
        assertTrue(repository.claim(GUILD, MONDAY, 9));
    }
}
