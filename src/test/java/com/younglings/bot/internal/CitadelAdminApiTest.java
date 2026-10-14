package com.younglings.bot.internal;

import com.younglings.bot.internal.TicketAdminApi.ApiError;
import com.younglings.bot.tracking.CitadelWheelEntries;
import com.younglings.bot.tracking.CitadelWheelPoster;
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
    private CitadelAdminApi api;
    private Guild guild;
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-10-14T12:30:00Z");

    @BeforeEach
    void setUp() {
        digest = mock(CitadelWheelEntries.class);
        settings = mock(WheelSettingsRepository.class);
        poster = mock(CitadelWheelPoster.class);
        api = new CitadelAdminApi(digest, settings, poster);
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
}
