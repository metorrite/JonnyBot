package com.younglings.bot.wheel;

import com.younglings.bot.testsupport.LocalDatabase;
import com.younglings.bot.wheel.WheelWinnerRepository.GifSource;
import com.younglings.bot.wheel.WheelWinnerRepository.Method;
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

    private static Winner winner(LocalDate end, String name, String token, boolean gif) {
        return new Winner(GUILD, end, name, List.of("Axley", "Giff", "Giff"), Method.SPUN, 5L, Instant.now(), gif, gif ? GifSource.WEBSITE : null, token);
    }

    @Test
    void aLockedWeekComesBackWithItsEntriesAndPictureOnlyWhenAsked() {
        byte[] gif = "GIF89a-fake-picture".getBytes();
        assertTrue(repository.insert(winner(WEEK, "Giff", "token-one-aaaaaaaaaaaaaaaaaaaaaaaaaa", true), gif));
        Winner read = repository.get(GUILD, WEEK).orElseThrow();
        assertEquals("Giff", read.winner());
        assertEquals(List.of("Axley", "Giff", "Giff"), read.entries());
        assertEquals(Method.SPUN, read.method());
        assertEquals(5L, read.lockedBy());
        assertTrue(read.hasGif());
        assertEquals(GifSource.WEBSITE, read.gifSource());
        assertArrayEquals(gif, repository.gif(GUILD, WEEK).orElseThrow());
        assertArrayEquals(gif, repository.gifByToken("token-one-aaaaaaaaaaaaaaaaaaaaaaaaaa").orElseThrow());
    }

    @Test
    void aWeekIsNeverReplacedAndCanBeUnlocked() {
        assertTrue(repository.insert(winner(WEEK, "Giff", "token-two-aaaaaaaaaaaaaaaaaaaaaaaaaa", false), null));
        assertFalse(repository.insert(winner(WEEK, "Axley", "token-three-aaaaaaaaaaaaaaaaaaaaaaaaa", false), null));
        assertEquals("Giff", repository.get(GUILD, WEEK).orElseThrow().winner());
        assertTrue(repository.delete(GUILD, WEEK));
        assertFalse(repository.delete(GUILD, WEEK));
        assertTrue(repository.get(GUILD, WEEK).isEmpty());
    }

    @Test
    void aMissingPictureCanBeFilledInButALateOneNeverReplacesTheFirst() {
        repository.insert(winner(WEEK, "Giff", "token-four-aaaaaaaaaaaaaaaaaaaaaaaaa", false), null);
        assertFalse(repository.get(GUILD, WEEK).orElseThrow().hasGif());
        assertNull(repository.get(GUILD, WEEK).orElseThrow().gifSource());
        assertTrue(repository.setGif(GUILD, WEEK, "first".getBytes(), GifSource.BOT, true));
        assertFalse(repository.setGif(GUILD, WEEK, "late".getBytes(), GifSource.BOT, true), "kept the first");
        assertArrayEquals("first".getBytes(), repository.gif(GUILD, WEEK).orElseThrow());
        assertTrue(repository.setGif(GUILD, WEEK, "better".getBytes(), GifSource.WEBSITE, false), "an explicit replacement wins");
        assertEquals(GifSource.WEBSITE, repository.get(GUILD, WEEK).orElseThrow().gifSource());
    }

    @Test
    void theListIsNewestFirstAndLimited() {
        repository.insert(winner(LocalDate.parse("2026-09-30"), "Old", "token-five-aaaaaaaaaaaaaaaaaaaaaaaaa", false), null);
        repository.insert(winner(WEEK, "New", "token-six-aaaaaaaaaaaaaaaaaaaaaaaaaaa", false), null);
        assertEquals(List.of("New", "Old"), repository.list(GUILD, 10).stream().map(Winner::winner).toList());
        assertEquals(1, repository.list(GUILD, 1).size());
    }
}
