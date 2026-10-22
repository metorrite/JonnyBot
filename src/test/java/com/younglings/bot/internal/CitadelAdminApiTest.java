package com.younglings.bot.internal;

import com.younglings.bot.internal.TicketAdminApi.ApiError;
import com.younglings.bot.tracking.CitadelWheelEntries;
import com.younglings.bot.tracking.CitadelWheelPoster;
import com.younglings.bot.tracking.CitadelPrizes;
import com.younglings.bot.tracking.CitadelWinners;
import com.younglings.bot.wheel.WheelWinnerRepository;
import com.younglings.bot.wheel.WheelMessage;
import com.younglings.bot.wheel.WheelSettingsRepository;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.utils.data.DataArray;
import com.younglings.bot.wheel.WheelEligibility;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CitadelAdminApiTest {
    private CitadelWheelEntries digest;
    private WheelSettingsRepository settings;
    private CitadelWheelPoster poster;
    private CitadelWinners winners;
    private CitadelPrizes prizes;
    private CitadelAdminApi api;
    private Guild guild;
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-10-14T12:30:00Z");

    @BeforeEach
    void setUp() {
        digest = mock(CitadelWheelEntries.class);
        settings = mock(WheelSettingsRepository.class);
        poster = mock(CitadelWheelPoster.class);
        winners = mock(CitadelWinners.class);
        prizes = mock(CitadelPrizes.class);
        api = new CitadelAdminApi(digest, settings, poster, winners, prizes);
        api.useExecutor(Runnable::run);
        guild = mock(Guild.class);
        when(guild.getIdLong()).thenReturn(7L);
    }

    @Test
    void withNoDateItAnswersForTheWeekThatJustFinished() {
        when(digest.forWeek(eq(guild), any(), any())).thenReturn(new WheelEligibility.Result(List.of("Axley", "Giff"), List.of(new WheelEligibility.Left("Metorrite", "clan rank Admin")), List.of(new WheelEligibility.Person("Jonny Yong", List.of("Jonny Yong", "Jonny Young")))));
        DataObject answer = api.cappers(guild, null, NOW);
        assertEquals("2026-10-14", answer.getString("end"));
        assertEquals("2026-10-07", answer.getString("start"));
        assertEquals(2, answer.getArray("cappers").length());
        assertEquals("Metorrite", answer.getArray("leftOut").getObject(0).getString("name"));
        assertEquals("clan rank Admin", answer.getArray("leftOut").getObject(0).getString("reason"));
        assertEquals(2, answer.getArray("merged").getObject(0).getArray("accounts").length());
        assertEquals(8, answer.getArray("weeks").length());
        assertEquals("2026-10-14", answer.getArray("weeks").getObject(0).getString("end"));
        assertEquals("2026-08-26", answer.getArray("weeks").getObject(7).getString("end"));
        verify(digest).forWeek(guild, OffsetDateTime.parse("2026-10-07T00:10:00Z"), OffsetDateTime.parse("2026-10-14T00:10:00Z"));
    }

    @Test
    void aGivenWednesdayPicksThatWeek() {
        when(digest.forWeek(eq(guild), any(), any())).thenReturn(new WheelEligibility.Result(List.of(), List.of(), List.of()));
        api.cappers(guild, "2026-09-30", NOW);
        verify(digest).forWeek(guild, OffsetDateTime.parse("2026-09-23T00:10:00Z"), OffsetDateTime.parse("2026-09-30T00:10:00Z"));
    }

    @Test
    void aBadOrFutureDateIsRefusedWithAReason() {
        assertEquals(400, assertThrows(ApiError.class, () -> api.cappers(guild, "nonsense", NOW)).status);
        assertEquals(400, assertThrows(ApiError.class, () -> api.cappers(guild, "2026-10-15", NOW)).status, "not a Wednesday");
        assertEquals(400, assertThrows(ApiError.class, () -> api.cappers(guild, "2027-01-06", NOW)).status, "not yet");
    }

    @Test
    void onlyItsOwnRouteIsAnswered() {
        assertNull(api.route(guild, null, "GET", new String[]{"events"}, DataObject::empty));
        assertEquals(405, assertThrows(ApiError.class, () -> api.route(guild, null, "POST", new String[]{"citadel", "cappers"}, DataObject::empty)).status);
    }

    // ---------- announcing a winner ----------

    private GuildMessageChannel channel(boolean canTalk) {
        GuildMessageChannel channel = mock(GuildMessageChannel.class);
        when(channel.canTalk()).thenReturn(canTalk);
        when(channel.getName()).thenReturn("winners");
        when(guild.getChannelById(GuildMessageChannel.class, 9L)).thenReturn(channel);
        return channel;
    }

    private static Member actor() {
        Member member = mock(Member.class);
        when(member.getId()).thenReturn("1");
        when(member.getIdLong()).thenReturn(1L);
        return member;
    }

    @Test
    void theSettingsAreShownWithTheDefaultMessage() {
        when(settings.get(7L)).thenReturn(new WheelSettingsRepository.Settings(true, 9L, "Hello {winner}"));
        DataObject answer = api.route(guild, actor(), "GET", new String[]{"citadel", "wheel"}, DataObject::empty);
        assertEquals(true, answer.getBoolean("autoPost"));
        assertEquals("9", answer.getString("channelId"));
        assertEquals("Hello {winner}", answer.getString("message"));
        assertEquals(WheelMessage.DEFAULT, answer.getString("defaultMessage"));
    }

    @Test
    void savingChangesOnlyWhatWasSent() {
        channel(true);
        when(settings.get(7L)).thenReturn(new WheelSettingsRepository.Settings(false, null, WheelMessage.DEFAULT));
        api.saveWheelSettings(guild, actor(), DataObject.empty().put("autoPost", true).put("channelId", "9").put("message", "  Congrats {mention}!  "));
        verify(settings).save(7L, new WheelSettingsRepository.Settings(true, 9L, "Congrats {mention}!"));
    }

    @Test
    void theVideoOptionIsOffUntilChosenAndKeptWhenOtherThingsChange() {
        channel(true);
        when(settings.get(7L)).thenReturn(new WheelSettingsRepository.Settings(false, null, WheelMessage.DEFAULT));
        assertEquals(false, api.wheelSettings(guild).getBoolean("video"));

        api.saveWheelSettings(guild, actor(), DataObject.empty().put("video", true));
        verify(settings).save(7L, new WheelSettingsRepository.Settings(false, null, WheelMessage.DEFAULT, true));

        when(settings.get(7L)).thenReturn(new WheelSettingsRepository.Settings(false, null, WheelMessage.DEFAULT, true));
        api.saveWheelSettings(guild, actor(), DataObject.empty().put("channelId", "9"));
        verify(settings).save(7L, new WheelSettingsRepository.Settings(false, 9L, WheelMessage.DEFAULT, true));
    }

    @Test
    void autoPostNeedsAChannelAndAMessageThatSaysWhoWon() {
        when(settings.get(7L)).thenReturn(new WheelSettingsRepository.Settings(false, null, WheelMessage.DEFAULT));
        assertEquals(400, assertThrows(ApiError.class, () -> api.saveWheelSettings(guild, actor(), DataObject.empty().put("autoPost", true))).status);
        assertEquals(400, assertThrows(ApiError.class, () -> api.saveWheelSettings(guild, actor(), DataObject.empty().put("message", "Well done everyone"))).status);
        verify(settings, never()).save(anyLong(), any());
    }

    @Test
    void aChannelTheBotCannotTalkInIsRefused() {
        channel(false);
        when(settings.get(7L)).thenReturn(new WheelSettingsRepository.Settings(false, null, WheelMessage.DEFAULT));
        assertEquals(400, assertThrows(ApiError.class, () -> api.saveWheelSettings(guild, actor(), DataObject.empty().put("channelId", "9"))).status);
        assertEquals(400, assertThrows(ApiError.class, () -> api.saveWheelSettings(guild, actor(), DataObject.empty().put("channelId", "404"))).status, "a channel that is not in the server");
    }

    private static DataObject postBody(String winner) {
        return DataObject.empty().put("winner", winner).put("end", "2026-10-14").put("entries", DataArray.fromCollection(List.of("Axley", "Giff", "Giff")));
    }

    @Test
    void postingTheDashboardsWinnerIsQueuedAndAnswersWithTheChannel() throws Exception {
        GuildMessageChannel channel = channel(true);
        when(poster.channelFor(guild)).thenReturn(channel);
        DataObject answer = api.post(guild, actor(), postBody("Giff"));
        assertEquals(true, answer.getBoolean("queued"));
        assertEquals("winners", answer.getString("channel"));
        verify(poster).postFromDashboard(eq(guild), any(), eq("Giff"), eq(List.of("Axley", "Giff", "Giff")));
    }

    @Test
    void aWinnerThatIsNotOnTheWheelOrAChannelProblemIsRefused() {
        assertEquals(400, assertThrows(ApiError.class, () -> api.post(guild, actor(), postBody("Somebody Else"))).status);
        when(poster.channelFor(guild)).thenThrow(new CitadelWheelPoster.CannotPost("Choose the channel first."));
        assertEquals(409, assertThrows(ApiError.class, () -> api.post(guild, actor(), postBody("Axley"))).status);
    }

    // ---------- locking winners ----------

    private static final java.time.LocalDate WEEK_END = java.time.LocalDate.parse("2026-10-14");

    private WheelWinnerRepository.Winner locked(int place, String winner, boolean hasGif) {
        return new WheelWinnerRepository.Winner(7L, WEEK_END, place, winner, List.of("Axley", "Giff"), WheelWinnerRepository.Method.SPUN, 1L, java.time.Instant.parse("2026-10-14T12:00:00Z"), hasGif,
                hasGif ? WheelWinnerRepository.GifSource.WEBSITE : null, "tok-" + place, WheelWinnerRepository.Prize.pending());
    }

    private static DataObject lockBody(String winner) {
        return DataObject.empty().put("end", "2026-10-14").put("winner", winner).put("entries", DataArray.fromCollection(List.of("Axley", "Giff", "Giff")));
    }

    @Test
    void lockingSendsTheWinnerWithTheWebsitesPictureAndAnswersWithTheWeekAndItsMessage() {
        byte[] gif = "GIF89a-test-picture".getBytes();
        when(winners.lock(eq(7L), eq(WEEK_END), eq("Giff"), eq(List.of("Axley", "Giff", "Giff")), eq(WheelWinnerRepository.Method.SPUN), eq(1L), any(), eq(WheelWinnerRepository.GifSource.WEBSITE)))
                .thenReturn(locked(1, "Giff", true));
        when(winners.forWeek(7L, WEEK_END)).thenReturn(List.of(locked(1, "Giff", true)));
        when(poster.messageFor(eq(guild), any(), eq(List.of("Giff")), eq(2))).thenReturn("Well done **Giff**!");
        DataObject body = lockBody("Giff").put("gif", java.util.Base64.getEncoder().encodeToString(gif));
        DataObject answer = api.route(guild, actor(), "POST", new String[]{"citadel", "lock"}, () -> body);
        assertEquals("Well done **Giff**!", answer.getString("message"));
        assertEquals(1, answer.getArray("winners").length());
        assertEquals("Giff", answer.getArray("winners").getObject(0).getString("winner"));
        assertEquals(1, answer.getArray("winners").getObject(0).getInt("place"));
        assertEquals("tok-1", answer.getArray("winners").getObject(0).getString("gifToken"));
        assertEquals("PENDING", answer.getArray("winners").getObject(0).getObject("prize").getString("status"));
        org.mockito.ArgumentCaptor<byte[]> sent = org.mockito.ArgumentCaptor.forClass(byte[].class);
        verify(winners).lock(eq(7L), eq(WEEK_END), eq("Giff"), any(), any(), eq(1L), sent.capture(), any());
        assertEquals(new String(gif), new String(sent.getValue()));
    }

    @Test
    void aWeekWithSeveralWinnersListsThemInOrderWithOneMessage() {
        when(winners.forWeek(7L, WEEK_END)).thenReturn(List.of(locked(1, "Giff", true), locked(2, "Axley", true)));
        when(poster.messageFor(eq(guild), any(), eq(List.of("Giff", "Axley")), eq(2))).thenReturn("Congrats **Giff** and **Axley**");
        DataObject answer = api.route(guild, actor(), "GET", new String[]{"citadel", "winners", "2026-10-14"}, DataObject::empty);
        assertEquals(2, answer.getArray("winners").length());
        assertEquals("Axley", answer.getArray("winners").getObject(1).getString("winner"));
        assertEquals("Congrats **Giff** and **Axley**", answer.getString("message"));
    }

    @Test
    void aHandTypedPastWeekIsLockedAsManualWithoutAPicture() {
        when(winners.lock(eq(7L), eq(WEEK_END), eq("Axley"), any(), eq(WheelWinnerRepository.Method.MANUAL), eq(1L), org.mockito.ArgumentMatchers.isNull(), any())).thenReturn(locked(1, "Axley", false));
        when(winners.forWeek(7L, WEEK_END)).thenReturn(List.of(locked(1, "Axley", false)));
        when(poster.messageFor(eq(guild), any(), eq(List.of("Axley")), org.mockito.ArgumentMatchers.anyInt())).thenReturn("text");
        DataObject body = DataObject.empty().put("end", "2026-10-14").put("winner", "Axley").put("manual", true).put("entries", DataArray.fromCollection(List.of("Axley")));
        assertEquals(false, api.route(guild, actor(), "POST", new String[]{"citadel", "lock"}, () -> body).getArray("winners").getObject(0).getBoolean("hasGif"));
    }

    @Test
    void aLockThatCannotBeMadeIsRefusedWithTheServicesReason() {
        when(winners.lock(anyLong(), any(), any(), any(), any(), any(), any(), any())).thenThrow(new CitadelWinners.CannotLock(409, "Giff is already a winner this week."));
        assertEquals(409, assertThrows(ApiError.class, () -> api.route(guild, actor(), "POST", new String[]{"citadel", "lock"}, () -> lockBody("Giff"))).status);
        assertEquals(400, assertThrows(ApiError.class, () -> api.route(guild, actor(), "POST", new String[]{"citadel", "lock"}, () -> DataObject.empty().put("end", "soon").put("winner", "x"))).status);
        assertEquals(400, assertThrows(ApiError.class, () -> api.route(guild, actor(), "POST", new String[]{"citadel", "lock"}, () -> lockBody("x").put("gif", "%%%not base64"))).status);
    }

    @Test
    void aWeekCanBeUnlockedAndAnUnlockedOneIsNotFound() {
        api.route(guild, actor(), "POST", new String[]{"citadel", "unlock"}, () -> DataObject.empty().put("end", "2026-10-14"));
        verify(winners).unlockWeek(7L, WEEK_END);
        when(winners.forWeek(7L, java.time.LocalDate.parse("2026-10-07"))).thenReturn(List.of());
        assertEquals(404, assertThrows(ApiError.class, () -> api.route(guild, actor(), "GET", new String[]{"citadel", "winners", "2026-10-07"}, DataObject::empty)).status);
        org.mockito.Mockito.doThrow(new CitadelWinners.CannotLock(409, "A prize has been recorded.")).when(winners).unlockWeek(7L, WEEK_END);
        assertEquals(409, assertThrows(ApiError.class, () -> api.route(guild, actor(), "POST", new String[]{"citadel", "unlock"}, () -> DataObject.empty().put("end", "2026-10-14"))).status);
    }

    @Test
    void theRecordsListGroupsWinnersByWeek() {
        when(winners.list(7L, 300)).thenReturn(List.of(locked(1, "Giff", true), locked(2, "Axley", true)));
        DataObject answer = api.route(guild, actor(), "GET", new String[]{"citadel", "winners"}, DataObject::empty);
        assertEquals(1, answer.getArray("weeks").length());
        assertEquals(2, answer.getArray("weeks").getObject(0).getArray("winners").length());
    }

    @Test
    void theCappersAnswerCarriesTheLockedWeekAndTheWeekListShowsWhoWon() {
        when(digest.forWeek(eq(guild), any(), any())).thenReturn(new WheelEligibility.Result(List.of("Axley", "Giff"), List.of(), List.of()));
        when(winners.list(7L, 300)).thenReturn(List.of(locked(1, "Giff", true), locked(2, "Axley", true)));
        when(winners.forWeek(7L, WEEK_END)).thenReturn(List.of(locked(1, "Giff", true), locked(2, "Axley", true)));
        when(poster.messageFor(eq(guild), any(), org.mockito.ArgumentMatchers.<List<String>>any(), org.mockito.ArgumentMatchers.anyInt())).thenReturn("text");
        DataObject answer = api.cappers(guild, null, NOW);
        assertEquals(2, answer.getObject("locked").getArray("winners").length());
        assertEquals("Giff", answer.getArray("weeks").getObject(0).getArray("winners").getString(0));
        assertEquals(0, answer.getArray("weeks").getObject(1).getArray("winners").length(), "weeks nobody locked have no winners");
    }

    @Test
    void anOpenWeekHasNoLockedEntryInTheCappersAnswer() {
        when(digest.forWeek(eq(guild), any(), any())).thenReturn(new WheelEligibility.Result(List.of("Axley"), List.of(), List.of()));
        assertTrue(api.cappers(guild, null, NOW).isNull("locked"));
    }

    @Test
    void aLockedWeekIsPostedWithItsOwnPicturesAndAnUnlockedPostIsRefusedOnALockedWeek() throws Exception {
        GuildMessageChannel channel = channel(true);
        when(poster.channelFor(guild)).thenReturn(channel);
        when(winners.forWeek(7L, WEEK_END)).thenReturn(List.of(locked(1, "Giff", true)));
        api.route(guild, actor(), "POST", new String[]{"citadel", "winners", "2026-10-14", "post"}, DataObject::empty);
        verify(poster).postLocked(guild, WEEK_END);
        assertEquals(409, assertThrows(ApiError.class, () -> api.post(guild, actor(), DataObject.empty().put("winner", "Giff").put("end", "2026-10-14")
                .put("entries", DataArray.fromCollection(List.of("Axley", "Giff"))))).status);
        verify(poster, org.mockito.Mockito.never()).postFromDashboard(any(), any(), any(), any());
    }

    @Test
    void postingALockedWeekNeedsAChannelAndALockedWeek() {
        when(winners.forWeek(7L, WEEK_END)).thenReturn(List.of(locked(1, "Giff", true)));
        when(poster.channelFor(guild)).thenThrow(new CitadelWheelPoster.CannotPost("Choose the channel first."));
        assertEquals(409, assertThrows(ApiError.class, () -> api.route(guild, actor(), "POST", new String[]{"citadel", "winners", "2026-10-14", "post"}, DataObject::empty)).status);
        when(winners.forWeek(7L, java.time.LocalDate.parse("2026-10-07"))).thenReturn(List.of());
        assertEquals(404, assertThrows(ApiError.class, () -> api.route(guild, actor(), "POST", new String[]{"citadel", "winners", "2026-10-07", "post"}, DataObject::empty)).status);
    }

    @Test
    void aPictureNearTheSizeLimitSurvivesTheJourneyAsJson() {
        byte[] big = new byte[CitadelWinners.MAX_GIF_BYTES - 10];
        System.arraycopy("GIF89a".getBytes(), 0, big, 0, 6);
        // what the website sends, parsed the way the internal server parses a request body
        String json = lockBody("Giff").put("gif", java.util.Base64.getEncoder().encodeToString(big)).toString();
        DataObject body = DataObject.fromJson(new java.io.ByteArrayInputStream(json.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        when(winners.lock(anyLong(), any(), any(), any(), any(), any(), any(), any())).thenReturn(locked(1, "Giff", true));
        when(winners.forWeek(7L, WEEK_END)).thenReturn(List.of(locked(1, "Giff", true)));
        when(poster.messageFor(eq(guild), any(), org.mockito.ArgumentMatchers.<List<String>>any(), org.mockito.ArgumentMatchers.anyInt())).thenReturn("text");
        api.route(guild, actor(), "POST", new String[]{"citadel", "lock"}, () -> body);
        org.mockito.ArgumentCaptor<byte[]> sent = org.mockito.ArgumentCaptor.forClass(byte[].class);
        verify(winners).lock(anyLong(), any(), any(), any(), any(), any(), sent.capture(), any());
        assertEquals(big.length, sent.getValue().length);
    }

    // ---------- prizes ----------

    @Test
    void thePrizeHoldersAreListedWithTheirBalances() {
        Member holder = mock(Member.class);
        when(holder.getEffectiveName()).thenReturn("Metorrite");
        when(guild.getMemberById(55L)).thenReturn(holder);
        when(prizes.holders(7L)).thenReturn(List.of(new com.younglings.bot.commands.coffer.CofferHolder(7L, 55L, 12_000_000L)));
        DataObject answer = api.route(guild, actor(), "GET", new String[]{"citadel", "prize", "holders"}, DataObject::empty);
        assertEquals("55", answer.getArray("holders").getObject(0).getString("userId"));
        assertEquals("Metorrite", answer.getArray("holders").getObject(0).getString("name"));
        assertEquals(12_000_000L, answer.getArray("holders").getObject(0).getLong("balance"));
    }

    @Test
    void aPrizeCanBeMarkedGivenWithAShorthandAmountAndPutBack() {
        when(winners.forWeek(7L, WEEK_END)).thenReturn(List.of(locked(1, "Giff", true)));
        api.route(guild, actor(), "POST", new String[]{"citadel", "prize"}, () -> DataObject.empty().put("end", "2026-10-14").put("place", 1).put("action", "given").put("amount", "5m").put("note", "paid in game"));
        verify(prizes).markGiven(7L, WEEK_END, 1, 5_000_000L, "paid in game", 1L);
        api.route(guild, actor(), "POST", new String[]{"citadel", "prize"}, () -> DataObject.empty().put("end", "2026-10-14").put("place", 1).put("action", "pending"));
        verify(prizes).markPending(7L, WEEK_END, 1);
        assertEquals(400, assertThrows(ApiError.class, () -> api.route(guild, actor(), "POST", new String[]{"citadel", "prize"}, () -> DataObject.empty().put("end", "2026-10-14").put("action", "maybe"))).status);
        assertEquals(400, assertThrows(ApiError.class, () -> api.route(guild, actor(), "POST", new String[]{"citadel", "prize"}, () -> DataObject.empty().put("end", "2026-10-14").put("action", "given").put("amount", "lots"))).status);
    }

    @Test
    void recordingAPrizeInTheCofferPassesEverythingToTheService() {
        when(winners.forWeek(7L, WEEK_END)).thenReturn(List.of(locked(1, "Giff", true)));
        DataObject body = DataObject.empty().put("end", "2026-10-14").put("place", 1).put("amount", "10m").put("paidBy", "55").put("note", "Well played");
        api.route(guild, actor(), "POST", new String[]{"citadel", "prize", "coffer"}, () -> body);
        verify(prizes).recordInCoffer(guild, WEEK_END, 1, "10m", 55L, null, "Well played", 1L);
        assertEquals(400, assertThrows(ApiError.class, () -> api.route(guild, actor(), "POST", new String[]{"citadel", "prize", "coffer"}, () -> DataObject.empty().put("end", "2026-10-14").put("amount", "1m"))).status, "who paid is required");
        org.mockito.Mockito.doThrow(new CitadelWinners.CannotLock(409, "That person only holds 1M.")).when(prizes).recordInCoffer(any(), any(), org.mockito.ArgumentMatchers.anyInt(), any(), anyLong(), any(), any(), anyLong());
        assertEquals(409, assertThrows(ApiError.class, () -> api.route(guild, actor(), "POST", new String[]{"citadel", "prize", "coffer"}, () -> body)).status);
    }
}
