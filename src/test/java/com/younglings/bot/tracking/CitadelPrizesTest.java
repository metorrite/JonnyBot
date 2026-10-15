package com.younglings.bot.tracking;

import com.younglings.bot.coffer.CofferRepository;
import com.younglings.bot.commands.coffer.CofferHolder;
import com.younglings.bot.runescape.PlayerLink;
import com.younglings.bot.runescape.PlayerLinkRepository;
import com.younglings.bot.wheel.WheelWinnerRepository;
import com.younglings.bot.wheel.WheelWinnerRepository.GifSource;
import com.younglings.bot.wheel.WheelWinnerRepository.Method;
import com.younglings.bot.wheel.WheelWinnerRepository.Prize;
import com.younglings.bot.wheel.WheelWinnerRepository.PrizeStatus;
import com.younglings.bot.wheel.WheelWinnerRepository.Winner;
import net.dv8tion.jda.api.entities.Guild;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CitadelPrizesTest {
    private static final long GUILD = 3L;
    private static final long WINNER_USER = 700L;
    private static final long PAYER = 55L;
    private static final long ACTOR = 9L;
    private static final LocalDate WEEK = LocalDate.parse("2026-10-14");

    private WheelWinnerRepository repository;
    private CofferRepository coffer;
    private PlayerLinkRepository links;
    private CitadelPrizes prizes;
    private Guild guild;

    @BeforeEach
    void setUp() {
        repository = mock(WheelWinnerRepository.class);
        coffer = mock(CofferRepository.class);
        links = mock(PlayerLinkRepository.class);
        prizes = new CitadelPrizes(repository, coffer, links);
        guild = mock(Guild.class);
        when(guild.getIdLong()).thenReturn(GUILD);
        when(links.getAllLinks(GUILD)).thenReturn(List.of(new PlayerLink(1, GUILD, WINNER_USER, "Giff", "x", null, null)));
        when(repository.get(GUILD, WEEK, 1)).thenReturn(Optional.of(winner(PrizeStatus.PENDING, false)));
        when(repository.setPrize(anyLong(), any(), org.mockito.ArgumentMatchers.anyInt(), any(), anyBoolean())).thenReturn(true);
        when(coffer.getHolderBalance(GUILD, PAYER)).thenReturn(20_000_000L);
        when(coffer.insertGiveaway(anyLong(), anyLong(), anyLong(), anyLong(), any())).thenReturn(true);
    }

    private static Winner winner(PrizeStatus status, boolean inCoffer) {
        return new Winner(GUILD, WEEK, 1, "Giff", List.of("Axley", "Giff"), Method.SPUN, 1L, Instant.now(), true, GifSource.WEBSITE, "tok",
                new Prize(status, null, null, null, null, null, null, inCoffer));
    }

    private CitadelWinners.CannotLock refused(Runnable action) {
        return assertThrows(CitadelWinners.CannotLock.class, action::run);
    }

    // ---------- the coffer ----------

    @Test
    void onlyThosePeopleHoldingGpAreOfferedAsWhoPaidMostFirst() {
        when(coffer.getHolders(GUILD)).thenReturn(List.of(new CofferHolder(GUILD, 1, 0), new CofferHolder(GUILD, 2, 5_000_000), new CofferHolder(GUILD, 3, 9_000_000)));
        assertEquals(List.of(3L, 2L), prizes.holders(GUILD).stream().map(CofferHolder::discordUserId).toList());
    }

    @Test
    void aPrizeRecordedInTheCofferIsAGiveawayFromWhoPaidToTheLinkedWinner() {
        prizes.recordInCoffer(guild, WEEK, 1, "10m", PAYER, null, "Well played", ACTOR);
        ArgumentCaptor<String> description = ArgumentCaptor.forClass(String.class);
        verify(coffer).insertGiveaway(eq(GUILD), eq(PAYER), eq(WINNER_USER), eq(10_000_000L), description.capture());
        assertTrue(description.getValue().contains("Giff") && description.getValue().contains("place 1") && description.getValue().contains("Well played"), description.getValue());
        ArgumentCaptor<Prize> saved = ArgumentCaptor.forClass(Prize.class);
        verify(repository).setPrize(eq(GUILD), eq(WEEK), eq(1), saved.capture(), eq(true));
        assertEquals(PrizeStatus.GIVEN, saved.getValue().status());
        assertEquals(10_000_000L, saved.getValue().amount());
        assertEquals(PAYER, saved.getValue().paidBy());
        assertEquals(WINNER_USER, saved.getValue().recipient());
        assertEquals(ACTOR, saved.getValue().givenBy());
        assertTrue(saved.getValue().inCoffer());
    }

    @Test
    void aChosenDiscordAccountStandsInForAnUnlinkedWinner() {
        when(links.getAllLinks(GUILD)).thenReturn(List.of());
        assertTrue(refused(() -> prizes.recordInCoffer(guild, WEEK, 1, "10m", PAYER, null, null, ACTOR)).getMessage().contains("isn't linked"));
        verify(coffer, never()).insertGiveaway(anyLong(), anyLong(), anyLong(), anyLong(), any());

        prizes.recordInCoffer(guild, WEEK, 1, "10m", PAYER, 800L, null, ACTOR);
        verify(coffer).insertGiveaway(eq(GUILD), eq(PAYER), eq(800L), eq(10_000_000L), anyString());
    }

    @Test
    void noGpMovesWhenSomethingIsWrong() {
        assertEquals(400, refused(() -> prizes.recordInCoffer(guild, WEEK, 1, "lots", PAYER, null, null, ACTOR)).status(), "an amount that isn't gp");
        assertEquals(400, refused(() -> prizes.recordInCoffer(guild, WEEK, 1, "10m", WINNER_USER, null, null, ACTOR)).status(), "the winner can't pay themselves");
        when(coffer.getHolderBalance(GUILD, PAYER)).thenReturn(1_000_000L);
        var short_ = refused(() -> prizes.recordInCoffer(guild, WEEK, 1, "10m", PAYER, null, null, ACTOR));
        assertEquals(409, short_.status());
        assertTrue(short_.getMessage().contains("only holds"), short_.getMessage());
        when(repository.get(GUILD, WEEK, 1)).thenReturn(Optional.empty());
        assertEquals(404, refused(() -> prizes.recordInCoffer(guild, WEEK, 1, "10m", PAYER, null, null, ACTOR)).status());
        verify(coffer, never()).insertGiveaway(anyLong(), anyLong(), anyLong(), anyLong(), any());
        verify(repository, never()).setPrize(anyLong(), any(), org.mockito.ArgumentMatchers.anyInt(), any(), anyBoolean());
    }

    @Test
    void aPrizeAlreadyGivenCannotBePaidTwice() {
        when(repository.get(GUILD, WEEK, 1)).thenReturn(Optional.of(winner(PrizeStatus.GIVEN, true)));
        assertEquals(409, refused(() -> prizes.recordInCoffer(guild, WEEK, 1, "10m", PAYER, null, null, ACTOR)).status());
        verify(coffer, never()).insertGiveaway(anyLong(), anyLong(), anyLong(), anyLong(), any());
    }

    @Test
    void twoPeopleRecordingAtOnceCannotBothPay() {
        when(repository.setPrize(eq(GUILD), eq(WEEK), eq(1), any(), eq(true))).thenReturn(false);
        assertEquals(409, refused(() -> prizes.recordInCoffer(guild, WEEK, 1, "10m", PAYER, null, null, ACTOR)).status());
        verify(coffer, never()).insertGiveaway(anyLong(), anyLong(), anyLong(), anyLong(), any());
    }

    @Test
    void aBalanceThatChangedAtTheLastMomentPutsThePrizeBackToNotGiven() {
        when(coffer.insertGiveaway(anyLong(), anyLong(), anyLong(), anyLong(), any())).thenReturn(false);
        assertEquals(409, refused(() -> prizes.recordInCoffer(guild, WEEK, 1, "10m", PAYER, null, null, ACTOR)).status());
        ArgumentCaptor<Prize> back = ArgumentCaptor.forClass(Prize.class);
        verify(repository, times(2)).setPrize(eq(GUILD), eq(WEEK), eq(1), back.capture(), anyBoolean());
        assertEquals(PrizeStatus.PENDING, back.getAllValues().get(1).status());
    }

    // ---------- marking by hand ----------

    @Test
    void aPrizeMarkedGivenByHandRecordsWhoMarkedItAndNeverTouchesTheCoffer() {
        prizes.markGiven(GUILD, WEEK, 1, 2_000_000L, "  gave in game  ", ACTOR);
        ArgumentCaptor<Prize> saved = ArgumentCaptor.forClass(Prize.class);
        verify(repository).setPrize(eq(GUILD), eq(WEEK), eq(1), saved.capture(), eq(true));
        assertEquals(PrizeStatus.GIVEN, saved.getValue().status());
        assertEquals("gave in game", saved.getValue().note());
        assertEquals(false, saved.getValue().inCoffer());
        verify(coffer, never()).insertGiveaway(anyLong(), anyLong(), anyLong(), anyLong(), any());
    }

    @Test
    void anAmountNoteOrRepeatMarkIsChecked() {
        assertEquals(400, refused(() -> prizes.markGiven(GUILD, WEEK, 1, 0L, null, ACTOR)).status());
        assertEquals(400, refused(() -> prizes.markGiven(GUILD, WEEK, 1, null, "x".repeat(CitadelPrizes.MAX_NOTE + 1), ACTOR)).status());
        when(repository.get(GUILD, WEEK, 1)).thenReturn(Optional.of(winner(PrizeStatus.GIVEN, false)));
        assertEquals(409, refused(() -> prizes.markGiven(GUILD, WEEK, 1, null, null, ACTOR)).status());
    }

    @Test
    void aPrizeMarkedByHandCanBeSetBackButOneRecordedInTheCofferCannot() {
        when(repository.get(GUILD, WEEK, 1)).thenReturn(Optional.of(winner(PrizeStatus.GIVEN, false)));
        prizes.markPending(GUILD, WEEK, 1);
        ArgumentCaptor<Prize> saved = ArgumentCaptor.forClass(Prize.class);
        verify(repository).setPrize(eq(GUILD), eq(WEEK), eq(1), saved.capture(), eq(false));
        assertEquals(PrizeStatus.PENDING, saved.getValue().status());

        when(repository.get(GUILD, WEEK, 1)).thenReturn(Optional.of(winner(PrizeStatus.GIVEN, true)));
        assertEquals(409, refused(() -> prizes.markPending(GUILD, WEEK, 1)).status());
    }
}
