package com.younglings.bot.internal;

import com.younglings.bot.internal.TicketAdminApi.ApiError;
import com.younglings.bot.tracking.CitadelWheelEntries;
import com.younglings.bot.tracking.CitadelWheelPoster;
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
    private CitadelAdminApi api;
    private Guild guild;
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-10-14T12:30:00Z");

    @BeforeEach
    void setUp() {
        digest = mock(CitadelWheelEntries.class);
        settings = mock(WheelSettingsRepository.class);
        poster = mock(CitadelWheelPoster.class);
        winners = mock(CitadelWinners.class);
        api = new CitadelAdminApi(digest, settings, poster, winners);
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

    // ---------- locking a winner ----------

    private static final java.time.LocalDate WEEK_END = java.time.LocalDate.parse("2026-10-14");

    private WheelWinnerRepository.Winner locked(String winner, boolean hasGif) {
        return new WheelWinnerRepository.Winner(7L, WEEK_END, winner, List.of("Axley", "Giff"), WheelWinnerRepository.Method.SPUN, 1L, java.time.Instant.parse("2026-10-14T12:00:00Z"), hasGif,
                hasGif ? WheelWinnerRepository.GifSource.WEBSITE : null, "tok-abc");
    }

    @Test
    void lockingSendsTheWeeksWinnerWithTheWebsitesPictureAndAnswersWithTheMessage() {
        byte[] gif = "GIF89a-test-picture".getBytes();
        when(winners.lock(eq(7L), eq(WEEK_END), eq("Giff"), eq(List.of("Axley", "Giff", "Giff")), eq(WheelWinnerRepository.Method.SPUN), eq(1L), any(), eq(WheelWinnerRepository.GifSource.WEBSITE)))
                .thenReturn(locked("Giff", true));
        when(poster.messageFor(eq(guild), any(), eq("Giff"), eq(2))).thenReturn("Well done **Giff**!");
        DataObject body = DataObject.empty().put("end", "2026-10-14").put("winner", "Giff").put("entries", DataArray.fromCollection(List.of("Axley", "Giff", "Giff")))
                .put("gif", java.util.Base64.getEncoder().encodeToString(gif));
        DataObject answer = api.route(guild, actor(), "POST", new String[]{"citadel", "lock"}, () -> body);
        assertEquals("Giff", answer.getString("winner"));
        assertEquals("Well done **Giff**!", answer.getString("message"));
        assertEquals("tok-abc", answer.getString("gifToken"));
        org.mockito.ArgumentCaptor<byte[]> sent = org.mockito.ArgumentCaptor.forClass(byte[].class);
        verify(winners).lock(eq(7L), eq(WEEK_END), eq("Giff"), any(), any(), eq(1L), sent.capture(), any());
        assertEquals(new String(gif), new String(sent.getValue()));
    }

    @Test
    void aHandTypedPastWeekIsLockedAsManualWithoutAPicture() {
        when(winners.lock(eq(7L), eq(WEEK_END), eq("Axley"), any(), eq(WheelWinnerRepository.Method.MANUAL), eq(1L), org.mockito.ArgumentMatchers.isNull(), any())).thenReturn(locked("Axley", false));
        DataObject body = DataObject.empty().put("end", "2026-10-14").put("winner", "Axley").put("manual", true).put("entries", DataArray.fromCollection(List.of("Axley")));
        assertEquals(false, api.route(guild, actor(), "POST", new String[]{"citadel", "lock"}, () -> body).getBoolean("hasGif"));
    }

    @Test
    void aLockThatCannotBeMadeIsRefusedWithTheServicesReason() {
        when(winners.lock(anyLong(), any(), any(), any(), any(), any(), any(), any())).thenThrow(new CitadelWinners.CannotLock(409, "This week is already locked."));
        DataObject body = DataObject.empty().put("end", "2026-10-14").put("winner", "Giff");
        ApiError refused = assertThrows(ApiError.class, () -> api.route(guild, actor(), "POST", new String[]{"citadel", "lock"}, () -> body));
        assertEquals(409, refused.status);
        assertEquals(400, assertThrows(ApiError.class, () -> api.route(guild, actor(), "POST", new String[]{"citadel", "lock"}, () -> DataObject.empty().put("end", "soon").put("winner", "x"))).status);
        assertEquals(400, assertThrows(ApiError.class, () -> api.route(guild, actor(), "POST", new String[]{"citadel", "lock"}, () -> DataObject.empty().put("end", "2026-10-14").put("winner", "x").put("gif", "%%%not base64"))).status);
    }

    @Test
    void aLockedWeekCanBeReadBackWithItsMessageAndUnlocked() {
        when(winners.get(7L, WEEK_END)).thenReturn(java.util.Optional.of(locked("Giff", true)));
        when(poster.messageFor(eq(guild), any(), eq("Giff"), eq(2))).thenReturn("Well done **Giff**!");
        DataObject answer = api.route(guild, actor(), "GET", new String[]{"citadel", "winners", "2026-10-14"}, DataObject::empty);
        assertEquals("Well done **Giff**!", answer.getString("message"));
        assertEquals(2, answer.getArray("entries").length());

        when(winners.get(7L, java.time.LocalDate.parse("2026-10-07"))).thenReturn(java.util.Optional.empty());
        assertEquals(404, assertThrows(ApiError.class, () -> api.route(guild, actor(), "GET", new String[]{"citadel", "winners", "2026-10-07"}, DataObject::empty)).status);

        api.route(guild, actor(), "POST", new String[]{"citadel", "unlock"}, () -> DataObject.empty().put("end", "2026-10-14"));
        verify(winners).unlock(7L, WEEK_END);
    }

    @Test
    void theWeekListShowsWhoWonAndTheCappersAnswerCarriesTheLockedWinner() {
        when(digest.forWeek(eq(guild), any(), any())).thenReturn(new WheelEligibility.Result(List.of("Axley", "Giff"), List.of(), List.of()));
        when(winners.list(7L, 100)).thenReturn(List.of(locked("Giff", true)));
        when(poster.messageFor(eq(guild), any(), any(), org.mockito.ArgumentMatchers.anyInt())).thenReturn("text");
        DataObject answer = api.cappers(guild, null, NOW);
        assertEquals("Giff", answer.getObject("winner").getString("winner"));
        assertEquals("Giff", answer.getArray("weeks").getObject(0).getString("winner"));
        assertTrue(answer.getArray("weeks").getObject(1).isNull("winner"), "weeks nobody has locked have no winner");
    }

    @Test
    void aLockedWeekIsPostedWithItsOwnPictureAndAnotherWinnerIsRefused() throws Exception {
        GuildMessageChannel channel = channel(true);
        when(poster.channelFor(guild)).thenReturn(channel);
        when(winners.get(7L, WEEK_END)).thenReturn(java.util.Optional.of(locked("Giff", true)));
        api.post(guild, actor(), DataObject.empty().put("winner", "Giff").put("end", "2026-10-14").put("entries", DataArray.fromCollection(List.of("Axley", "Giff"))));
        verify(poster).postLocked(guild, WEEK_END);
        verify(poster, org.mockito.Mockito.never()).postFromDashboard(any(), any(), any(), any());
        assertEquals(409, assertThrows(ApiError.class, () -> api.post(guild, actor(), DataObject.empty().put("winner", "Axley").put("end", "2026-10-14")
                .put("entries", DataArray.fromCollection(List.of("Axley", "Giff"))))).status);
    }

    @Test
    void postingALockedWeekFromTheRecordsListNeedsAChannelAndALockedWeek() {
        when(winners.get(7L, WEEK_END)).thenReturn(java.util.Optional.of(locked("Giff", true)));
        when(poster.channelFor(guild)).thenThrow(new CitadelWheelPoster.CannotPost("Choose the channel first."));
        assertEquals(409, assertThrows(ApiError.class, () -> api.route(guild, actor(), "POST", new String[]{"citadel", "winners", "2026-10-14", "post"}, DataObject::empty)).status);
        when(winners.get(7L, java.time.LocalDate.parse("2026-10-07"))).thenReturn(java.util.Optional.empty());
        assertEquals(404, assertThrows(ApiError.class, () -> api.route(guild, actor(), "POST", new String[]{"citadel", "winners", "2026-10-07", "post"}, DataObject::empty)).status);
    }

    @Test
    void aPictureNearTheSizeLimitSurvivesTheJourneyAsJson() {
        byte[] big = new byte[CitadelWinners.MAX_GIF_BYTES - 10];
        System.arraycopy("GIF89a".getBytes(), 0, big, 0, 6);
        // what the website sends, parsed the way the internal server parses a request body
        String json = DataObject.empty().put("end", "2026-10-14").put("winner", "Giff").put("entries", DataArray.fromCollection(List.of("Giff")))
                .put("gif", java.util.Base64.getEncoder().encodeToString(big)).toString();
        DataObject body = DataObject.fromJson(new java.io.ByteArrayInputStream(json.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        when(winners.lock(anyLong(), any(), any(), any(), any(), any(), any(), any())).thenReturn(locked("Giff", true));
        api.route(guild, actor(), "POST", new String[]{"citadel", "lock"}, () -> body);
        org.mockito.ArgumentCaptor<byte[]> sent = org.mockito.ArgumentCaptor.forClass(byte[].class);
        verify(winners).lock(anyLong(), any(), any(), any(), any(), any(), sent.capture(), any());
        assertEquals(big.length, sent.getValue().length);
    }
}
