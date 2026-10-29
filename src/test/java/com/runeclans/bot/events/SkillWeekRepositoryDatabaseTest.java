package com.runeclans.bot.events;

import com.runeclans.bot.testsupport.LocalDatabase;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.List;
import java.util.OptionalInt;

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
            for (String table : List.of("skill_week", "skill_week_settings", "skill_week_vote", "skill_week_enrolled", "skill_week_opt_out")) {
                try (PreparedStatement s = c.prepareStatement("DELETE FROM younglings." + table + " WHERE guild_id = ?")) {
                    s.setLong(1, GUILD);
                    s.executeUpdate();
                }
            }
        }
    }

    @Test
    void offUntilSavedOnAndAChangedRotaGoesOnAfterThisWeeksSkill() {
        assertEquals(SkillWeekRepository.Settings.OFF, repository.settings(GUILD));
        assertFalse(repository.enabledGuilds().contains(GUILD));
        repository.save(GUILD, true, List.of(9, 15), ADMIN);
        assertEquals(new SkillWeekRepository.Settings(true, List.of(9, 15), 0, ADMIN), repository.settings(GUILD));
        assertTrue(repository.enabledGuilds().contains(GUILD));

        assertEquals(OptionalInt.of(9), repository.claim(GUILD, MONDAY));
        assertEquals(1, repository.settings(GUILD).position());
        repository.save(GUILD, true, List.of(9, 15), ADMIN);
        assertEquals(1, repository.settings(GUILD).position(), "the same rota keeps its place");
        repository.save(GUILD, true, List.of(9, 15, 25), ADMIN);
        assertEquals(15, SkillWeekRules.skillAt(List.of(9, 15, 25), repository.settings(GUILD).position()), "a skill added this week doesn't bring this week's back");
        repository.save(GUILD, false, List.of(25, 9), ADMIN);
        assertEquals(25, SkillWeekRules.skillAt(List.of(25, 9), repository.settings(GUILD).position()));
        repository.save(GUILD, false, List.of(14, 15), ADMIN);
        assertEquals(0, repository.settings(GUILD).position(), "a rota without this week's skill starts from its first");
        assertFalse(repository.enabledGuilds().contains(GUILD));
    }

    @Test
    void aWeekIsClaimedOnceAndClosedOnce() {
        repository.save(GUILD, true, List.of(9, 15), ADMIN);
        assertEquals(OptionalInt.of(9), repository.claim(GUILD, MONDAY));
        assertTrue(repository.claim(GUILD, MONDAY).isEmpty(), "a second pass or a restart doesn't open the week again");
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
        assertEquals(OptionalInt.of(9), repository.claim(GUILD, MONDAY));
        repository.release(GUILD, MONDAY);
        assertTrue(repository.week(GUILD, MONDAY).isEmpty());
        assertEquals(0, repository.settings(GUILD).position());
        assertEquals(OptionalInt.of(9), repository.claim(GUILD, MONDAY));
    }

    @Test
    void onlyAnOldClaimWithoutACompetitionIsGivenBackAsStale() {
        repository.save(GUILD, true, List.of(9, 15), ADMIN);
        assertEquals(OptionalInt.of(9), repository.claim(GUILD, MONDAY));
        assertFalse(repository.releaseStale(GUILD, MONDAY, Instant.now().minusSeconds(600)), "claimed just now");
        assertTrue(repository.releaseStale(GUILD, MONDAY, Instant.now().plusSeconds(60)));
        assertEquals(0, repository.settings(GUILD).position());

        assertEquals(OptionalInt.of(9), repository.claim(GUILD, MONDAY));
        repository.setEvent(GUILD, MONDAY, 42);
        assertFalse(repository.releaseStale(GUILD, MONDAY, Instant.now().plusSeconds(60)), "a week with its competition is never given back");
    }

    @Test
    void aStaleClaimGivenBackIsClaimedAgainInTheSameSkillAndTheRotaDoesntSkip() {
        repository.save(GUILD, true, List.of(9, 15, 25), ADMIN);
        assertEquals(OptionalInt.of(9), repository.claim(GUILD, MONDAY));
        // the pass that claimed it died before its competition was kept; a later pass read the settings (position 1) and then gives it back
        SkillWeekRepository.Settings readBefore = repository.settings(GUILD);
        assertEquals(1, readBefore.position());
        assertTrue(repository.releaseStale(GUILD, MONDAY, Instant.now().plusSeconds(60)));
        assertEquals(OptionalInt.of(9), repository.claim(GUILD, MONDAY), "the week opens in the skill it was claimed in, not the one after");
        assertEquals(OptionalInt.of(15), repository.claim(GUILD, MONDAY.plusSeconds(7 * 86_400)), "and next week is the next skill");
        assertEquals(OptionalInt.of(25), repository.claim(GUILD, MONDAY.plusSeconds(14 * 86_400)));
    }

    @Test
    void noRotaClaimsNothing() {
        assertTrue(repository.claim(GUILD, MONDAY).isEmpty(), "no settings saved");
        repository.save(GUILD, false, List.of(), ADMIN);
        assertTrue(repository.claim(GUILD, MONDAY).isEmpty());
        assertTrue(repository.week(GUILD, MONDAY).isEmpty());
    }

    @Test
    void theOptionsAreKeptAndAVoteServerClaimsTheMembersChoice() {
        SkillWeekRepository.Options options = new SkillWeekRepository.Options(SkillWeekRepository.Mode.VOTE, Long.parseLong("6".repeat(18)), true);
        repository.save(GUILD, true, List.of(9, 15, 25), options, ADMIN);
        assertEquals(options, repository.settings(GUILD).options());

        long alice = 101, bob = 102, carol = 103;
        repository.vote(GUILD, MONDAY, alice, 15);
        repository.vote(GUILD, MONDAY, bob, 25);
        repository.vote(GUILD, MONDAY, carol, 25);
        repository.vote(GUILD, MONDAY, alice, 25); // changed her mind: still one vote each
        assertEquals(java.util.Map.of(25, 3), repository.votes(GUILD, MONDAY));
        assertEquals(OptionalInt.of(25), repository.vote(GUILD, MONDAY, alice));
        assertEquals(OptionalInt.empty(), repository.vote(GUILD, MONDAY.plusSeconds(7 * 86_400), alice), "a vote is for one week");

        assertEquals(OptionalInt.of(25), repository.claim(GUILD, MONDAY));
        assertEquals(1, repository.settings(GUILD).position(), "the rota still moves on one, for ties and weeks nobody voted in");
        // back to the rota: the options go back to off unless sent
        repository.save(GUILD, true, List.of(9, 15, 25), ADMIN);
        assertEquals(SkillWeekRepository.Options.OFF, repository.settings(GUILD).options());
    }

    @Test
    void aMemberIsAutoEnrolledInAWeekOnce() {
        assertTrue(repository.markEnrolled(GUILD, MONDAY, 101));
        assertFalse(repository.markEnrolled(GUILD, MONDAY, 101));
        assertTrue(repository.markEnrolled(GUILD, MONDAY.plusSeconds(7 * 86_400), 101), "next week is a new week");
        assertEquals(java.util.Set.of(101L), repository.enrolled(GUILD, MONDAY));
    }
    @Test
    void aMemberOptsOutOfAutoEnrolAndBackIn() {
        assertEquals(java.util.Set.of(), repository.optedOut(GUILD));
        repository.setOptOut(GUILD, 101, true);
        repository.setOptOut(GUILD, 101, true);
        assertEquals(java.util.Set.of(101L), repository.optedOut(GUILD));
        repository.setOptOut(GUILD, 101, false);
        assertEquals(java.util.Set.of(), repository.optedOut(GUILD));
    }
}
