package com.younglings.bot.wheel;

import com.younglings.bot.testsupport.LocalDatabase;
import com.younglings.bot.wheel.WheelWinnerRepository.GifSource;
import com.younglings.bot.wheel.WheelWinnerRepository.Method;
import com.younglings.bot.wheel.WheelWinnerRepository.Prize;
import com.younglings.bot.wheel.WheelWinnerRepository.PrizeStatus;
import com.younglings.bot.wheel.WheelWinnerRepository.Winner;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runs the repository's SQL against the developer's local Postgres (skipped where there isn't one). */
class WheelWinnerRepositoryDatabaseTest {
    private static final long GUILD = 987654321012399L;
    private static final LocalDate WEEK = LocalDate.parse("2026-10-14");
    private ConnectionSupplier db;
    private WheelWinnerRepository repository;

    @BeforeEach
    void setUp() throws Exception {
        db = LocalDatabase.supplier();
        repository = new WheelWinnerRepository(db);
        clean();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (db != null) clean();
    }

    private void clean() throws Exception {
        try (Connection c = db.getConnection(); PreparedStatement s = c.prepareStatement("DELETE FROM younglings.citadel_wheel_winner WHERE guild_id = ?")) {
            s.setLong(1, GUILD);
            s.executeUpdate();
        }
    }

    private static Winner winner(LocalDate end, int place, String name, String token, boolean gif) {
        return new Winner(GUILD, end, place, name, List.of("Axley", "Giff", "Giff"), Method.SPUN, 5L, Instant.now(), gif, gif ? GifSource.WEBSITE : null, token, Prize.pending());
    }

    @Test
    void aLockedWinnerComesBackWithItsEntriesPlaceAndPictureOnlyWhenAsked() {
        byte[] gif = "GIF89a-fake-picture".getBytes();
        assertTrue(repository.insert(winner(WEEK, 1, "Giff", "token-one-aaaaaaaaaaaaaaaaaaaaaaaaaa", true), gif));
        Winner read = repository.get(GUILD, WEEK, 1).orElseThrow();
        assertEquals("Giff", read.winner());
        assertEquals(1, read.place());
        assertEquals(List.of("Axley", "Giff", "Giff"), read.entries());
        assertEquals(Method.SPUN, read.method());
        assertEquals(5L, read.lockedBy());
        assertTrue(read.hasGif());
        assertEquals(GifSource.WEBSITE, read.gifSource());
        assertEquals(PrizeStatus.PENDING, read.prize().status());
        assertArrayEquals(gif, repository.gif(GUILD, WEEK, 1).orElseThrow());
        assertArrayEquals(gif, repository.gifByToken("token-one-aaaaaaaaaaaaaaaaaaaaaaaaaa").orElseThrow());
    }

    @Test
    void aWeekCanHaveSeveralWinnersInOrderOfPlace() {
        assertEquals(1, repository.nextPlace(GUILD, WEEK));
        assertTrue(repository.insert(winner(WEEK, 1, "Giff", "token-two-aaaaaaaaaaaaaaaaaaaaaaaaaa", false), null));
        assertEquals(2, repository.nextPlace(GUILD, WEEK));
        assertTrue(repository.insert(winner(WEEK, 2, "Axley", "token-three-aaaaaaaaaaaaaaaaaaaaaaaaa", false), null));
        assertEquals(List.of("Giff", "Axley"), repository.forWeek(GUILD, WEEK).stream().map(Winner::winner).toList());
        assertEquals(3, repository.nextPlace(GUILD, WEEK));
    }

    @Test
    void aPlaceIsNeverReplacedAndAWeekCanBeUnlockedAsAWhole() {
        assertTrue(repository.insert(winner(WEEK, 1, "Giff", "token-four-aaaaaaaaaaaaaaaaaaaaaaaaa", false), null));
        assertTrue(repository.insert(winner(WEEK, 2, "Axley", "token-five-aaaaaaaaaaaaaaaaaaaaaaaaa", false), null));
        assertFalse(repository.insert(winner(WEEK, 1, "Someone", "token-six-aaaaaaaaaaaaaaaaaaaaaaaaaaa", false), null));
        assertEquals("Giff", repository.get(GUILD, WEEK, 1).orElseThrow().winner());
        assertEquals(2, repository.deleteWeek(GUILD, WEEK));
        assertEquals(0, repository.deleteWeek(GUILD, WEEK));
        assertTrue(repository.forWeek(GUILD, WEEK).isEmpty());
    }

    @Test
    void aMissingPictureCanBeFilledInButALateOneNeverReplacesTheFirst() {
        repository.insert(winner(WEEK, 1, "Giff", "token-seven-aaaaaaaaaaaaaaaaaaaaaaaaa", false), null);
        assertFalse(repository.get(GUILD, WEEK, 1).orElseThrow().hasGif());
        assertNull(repository.get(GUILD, WEEK, 1).orElseThrow().gifSource());
        assertTrue(repository.setGif(GUILD, WEEK, 1, "first".getBytes(), GifSource.BOT, true));
        assertFalse(repository.setGif(GUILD, WEEK, 1, "late".getBytes(), GifSource.BOT, true), "kept the first");
        assertArrayEquals("first".getBytes(), repository.gif(GUILD, WEEK, 1).orElseThrow());
        assertTrue(repository.setGif(GUILD, WEEK, 1, "better".getBytes(), GifSource.WEBSITE, false), "an explicit replacement wins");
        assertEquals(GifSource.WEBSITE, repository.get(GUILD, WEEK, 1).orElseThrow().gifSource());
    }

    @Test
    void aPrizeIsRecordedOnceWithWhoPaidAndCanBeClearedAgain() {
        repository.insert(winner(WEEK, 1, "Giff", "token-eight-aaaaaaaaaaaaaaaaaaaaaaaaa", false), null);
        Prize given = new Prize(PrizeStatus.GIVEN, 5_000_000L, 55L, 700L, 9L, Instant.parse("2026-10-15T12:00:00Z"), "Well played", true);
        assertTrue(repository.setPrize(GUILD, WEEK, 1, given, true));
        assertFalse(repository.setPrize(GUILD, WEEK, 1, given, true), "recording again while it is already given is refused");
        Prize read = repository.get(GUILD, WEEK, 1).orElseThrow().prize();
        assertEquals(PrizeStatus.GIVEN, read.status());
        assertEquals(5_000_000L, read.amount());
        assertEquals(55L, read.paidBy());
        assertEquals(700L, read.recipient());
        assertEquals(9L, read.givenBy());
        assertEquals("Well played", read.note());
        assertEquals(Instant.parse("2026-10-15T12:00:00Z"), read.givenAt());
        assertTrue(read.inCoffer());

        assertTrue(repository.setPrize(GUILD, WEEK, 1, Prize.pending(), false));
        Prize cleared = repository.get(GUILD, WEEK, 1).orElseThrow().prize();
        assertEquals(PrizeStatus.PENDING, cleared.status());
        assertNull(cleared.amount());
        assertNull(cleared.paidBy());
        assertFalse(cleared.inCoffer());
    }

    @Test
    void theListIsNewestWeekFirstAndByPlaceWithinAWeek() {
        repository.insert(winner(LocalDate.parse("2026-09-30"), 1, "Old", "token-nine-aaaaaaaaaaaaaaaaaaaaaaaaaa", false), null);
        repository.insert(winner(WEEK, 2, "Second", "token-ten-aaaaaaaaaaaaaaaaaaaaaaaaaaa", false), null);
        repository.insert(winner(WEEK, 1, "First", "token-eleven-aaaaaaaaaaaaaaaaaaaaaaaa", false), null);
        assertEquals(List.of("First", "Second", "Old"), repository.list(GUILD, 10).stream().map(Winner::winner).toList());
        assertEquals(1, repository.list(GUILD, 1).size());
    }
}
