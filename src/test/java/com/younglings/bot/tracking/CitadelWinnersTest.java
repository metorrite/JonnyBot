package com.younglings.bot.tracking;

import com.younglings.bot.wheel.WheelWinnerRepository;
import com.younglings.bot.wheel.WheelWinnerRepository.GifSource;
import com.younglings.bot.wheel.WheelWinnerRepository.Method;
import com.younglings.bot.wheel.WheelWinnerRepository.Winner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CitadelWinnersTest {
    private static final long GUILD = 3L;
    private static final LocalDate WEEK = LocalDate.parse("2026-10-14");
    private static final byte[] GIF = "GIF89a-picture-bytes".getBytes();

    private WheelWinnerRepository repository;
    private CitadelWinners winners;

    @BeforeEach
    void setUp() {
        repository = mock(WheelWinnerRepository.class);
        winners = new CitadelWinners(repository);
        winners.useExecutor(Runnable::run);
        when(repository.insert(any(), any())).thenReturn(true);
        when(repository.get(anyLong(), any())).thenAnswer(i -> Optional.empty());
    }

    private Winner row(String name, boolean hasGif) {
        return new Winner(GUILD, WEEK, name, List.of("Axley", "Giff"), Method.SPUN, 1L, java.time.Instant.now(), hasGif, hasGif ? GifSource.WEBSITE : null, "token");
    }

    private CitadelWinners.CannotLock refused(Runnable action) {
        return assertThrows(CitadelWinners.CannotLock.class, action::run);
    }

    @Test
    void aWinnerIsLockedWithTheWebsitesPictureAndAnUnguessableToken() {
        winners.lock(GUILD, WEEK, "  Giff ", List.of("Axley", "Giff", "Giff"), Method.SPUN, 1L, GIF, GifSource.WEBSITE);
        ArgumentCaptor<Winner> saved = ArgumentCaptor.forClass(Winner.class);
        verify(repository).insert(saved.capture(), eq(GIF));
        assertEquals("Giff", saved.getValue().winner());
        assertEquals(List.of("Axley", "Giff", "Giff"), saved.getValue().entries());
        assertEquals(GifSource.WEBSITE, saved.getValue().gifSource());
        assertTrue(saved.getValue().gifToken().length() >= 30 && saved.getValue().gifToken().matches("[A-Za-z0-9_-]+"), saved.getValue().gifToken());
    }

    @Test
    void aWinnerTypedInByHandIsAddedToTheWheelWhenItIsNotOnIt() {
        winners.lock(GUILD, WEEK, "Berserker87", List.of("Axley"), Method.MANUAL, 1L, GIF, GifSource.WEBSITE);
        ArgumentCaptor<Winner> saved = ArgumentCaptor.forClass(Winner.class);
        verify(repository).insert(saved.capture(), any());
        assertEquals(List.of("Axley", "Berserker87"), saved.getValue().entries());
        assertEquals(Method.MANUAL, saved.getValue().method());
    }

    @Test
    void theWinnerIsKeptInTheSpellingOfTheirEntry() {
        winners.lock(GUILD, WEEK, "giff", List.of("Axley", "Giff"), Method.SPUN, 1L, GIF, GifSource.WEBSITE);
        ArgumentCaptor<Winner> saved = ArgumentCaptor.forClass(Winner.class);
        verify(repository).insert(saved.capture(), any());
        assertEquals("Giff", saved.getValue().winner());
    }

    @Test
    void aWeekThatIsAlreadyLockedIsNeverReplaced() {
        when(repository.insert(any(), any())).thenReturn(false);
        when(repository.get(GUILD, WEEK)).thenReturn(Optional.of(row("Axley", true)));
        var problem = refused(() -> winners.lock(GUILD, WEEK, "Giff", List.of("Giff"), Method.SPUN, 1L, GIF, GifSource.WEBSITE));
        assertEquals(409, problem.status());
        assertTrue(problem.getMessage().contains("Axley") && problem.getMessage().contains("Unlock"), problem.getMessage());
    }

    @Test
    void onlyARealFinishedCitadelWeekCanBeLocked() {
        assertEquals(400, refused(() -> winners.lock(GUILD, LocalDate.parse("2026-10-15"), "Giff", List.of("Giff"), Method.SPUN, 1L, GIF, GifSource.WEBSITE)).status(), "not a Wednesday");
        assertEquals(400, refused(() -> winners.lock(GUILD, LocalDate.now().plusWeeks(3).with(java.time.temporal.TemporalAdjusters.nextOrSame(java.time.DayOfWeek.WEDNESDAY)), "Giff", List.of("Giff"), Method.SPUN, 1L, GIF, GifSource.WEBSITE)).status(), "not yet");
        verify(repository, never()).insert(any(), any());
    }

    @Test
    void aBlankOrTooLongWinnerIsRefused() {
        assertEquals(400, refused(() -> winners.lock(GUILD, WEEK, "  ", List.of(), Method.SPUN, 1L, GIF, GifSource.WEBSITE)).status());
        assertEquals(400, refused(() -> winners.lock(GUILD, WEEK, "x".repeat(60), List.of(), Method.SPUN, 1L, GIF, GifSource.WEBSITE)).status());
    }

    @Test
    void aPictureThatIsNotAGifOrIsTooBigIsRefused() {
        assertEquals(400, refused(() -> winners.lock(GUILD, WEEK, "Giff", List.of("Giff"), Method.SPUN, 1L, "not a gif at all".getBytes(), GifSource.WEBSITE)).status());
        byte[] huge = new byte[CitadelWinners.MAX_GIF_BYTES + 1];
        System.arraycopy(GIF, 0, huge, 0, GIF.length);
        assertEquals(400, refused(() -> winners.lock(GUILD, WEEK, "Giff", List.of("Giff"), Method.SPUN, 1L, huge, GifSource.WEBSITE)).status());
        verify(repository, never()).insert(any(), any());
    }

    @Test
    void withoutAPictureTheBotDrawsItsOwnAndNeverReplacesOneThatArrivedFirst() {
        when(repository.get(GUILD, WEEK)).thenReturn(Optional.of(row("Giff", false)));
        winners.lock(GUILD, WEEK, "Giff", List.of("Axley", "Giff"), Method.SPUN, 1L, null, null);
        ArgumentCaptor<byte[]> drawn = ArgumentCaptor.forClass(byte[].class);
        verify(repository).setGif(eq(GUILD), eq(WEEK), drawn.capture(), eq(GifSource.BOT), eq(true));
        assertTrue(CitadelWinners.isGif(drawn.getValue()), "a real GIF was drawn");
    }

    @Test
    void theBotSkipsDrawingWhenAPictureIsAlreadyThere() {
        when(repository.get(GUILD, WEEK)).thenReturn(Optional.of(row("Giff", true)));
        winners.drawBotGif(GUILD, WEEK);
        verify(repository, never()).setGif(anyLong(), any(), any(), any(), anyBoolean());
    }

    @Test
    void unlockingNeedsALockedWeek() {
        when(repository.delete(GUILD, WEEK)).thenReturn(true);
        winners.unlock(GUILD, WEEK);
        when(repository.delete(GUILD, WEEK)).thenReturn(false);
        assertEquals(404, refused(() -> winners.unlock(GUILD, WEEK)).status());
    }

    @Test
    void aPictureIsOnlyHandedOutForAWellFormedToken() {
        when(repository.gifByToken("abcdefghijklmnopqrstuvwxyz012345")).thenReturn(Optional.of(GIF));
        assertTrue(winners.gifByToken("abcdefghijklmnopqrstuvwxyz012345").isPresent());
        assertFalse(winners.gifByToken("short").isPresent());
        assertFalse(winners.gifByToken("../../etc/passwd../../etc/passwd").isPresent());
        assertFalse(winners.gifByToken(null).isPresent());
    }

    @Test
    void replacingThePictureNeedsAGifAndALockedWeek() {
        when(repository.setGif(GUILD, WEEK, GIF, GifSource.WEBSITE, false)).thenReturn(true);
        winners.replaceGif(GUILD, WEEK, GIF);
        assertEquals(400, refused(() -> winners.replaceGif(GUILD, WEEK, "nope".getBytes())).status());
        when(repository.setGif(GUILD, WEEK, GIF, GifSource.WEBSITE, false)).thenReturn(false);
        assertEquals(404, refused(() -> winners.replaceGif(GUILD, WEEK, GIF)).status());
    }
}
