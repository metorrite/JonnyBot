package com.younglings.bot.tracking;

import com.younglings.bot.runescape.PlayerLink;
import com.younglings.bot.runescape.PlayerLinkRepository;
import com.younglings.bot.wheel.WheelEligibility;
import com.younglings.bot.wheel.WheelMessage;
import com.younglings.bot.wheel.WheelSettingsRepository;
import com.younglings.bot.wheel.WheelWinnerRepository;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.requests.restaction.MessageCreateAction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CitadelWheelPosterTest {
    private static final long GUILD = 5L;
    private static final long CHANNEL = 9L;
    private static final OffsetDateTime[] WINDOW = CitadelWeek.windowEndingOn(java.time.LocalDate.parse("2026-10-14"));

    private CitadelWheelEntries entries;
    private WheelSettingsRepository settings;
    private PlayerLinkRepository links;
    private TrackingPostingToggle toggle;
    private CitadelWinners winners;
    private CitadelWheelPoster poster;
    private Guild guild;
    private GuildMessageChannel channel;
    /** Every call made on the message being built, as "method(args)", so a test can see what was sent. */
    private final List<String> calls = new ArrayList<>();
    private final List<CharSequence> sent = new ArrayList<>();

    @BeforeEach
    void setUp() {
        entries = mock(CitadelWheelEntries.class);
        settings = mock(WheelSettingsRepository.class);
        links = mock(PlayerLinkRepository.class);
        toggle = mock(TrackingPostingToggle.class);
        when(toggle.isEnabled()).thenReturn(true);
        winners = mock(CitadelWinners.class);
        poster = new CitadelWheelPoster(entries, settings, links, toggle, winners);

        guild = mock(Guild.class);
        when(guild.getIdLong()).thenReturn(GUILD);
        channel = mock(GuildMessageChannel.class);
        when(channel.canTalk()).thenReturn(true);
        when(guild.getChannelById(GuildMessageChannel.class, CHANNEL)).thenReturn(channel);
        // a message being built answers every builder call with itself, and remembers what it was given
        MessageCreateAction action = mock(MessageCreateAction.class, invocation -> {
            calls.add(invocation.getMethod().getName());
            return invocation.getMethod().getReturnType().isInstance(invocation.getMock()) ? invocation.getMock() : null;
        });
        when(channel.sendMessage(any(CharSequence.class))).thenAnswer(i -> {
            sent.add(i.getArgument(0));
            return action;
        });
    }

    private void settings(boolean autoPost, Long channelId, String message) {
        when(settings.get(GUILD)).thenReturn(new WheelSettingsRepository.Settings(autoPost, channelId, message));
    }

    @Test
    void autoPostDoesNothingWhileItIsOff() {
        settings(false, CHANNEL, WheelMessage.DEFAULT);
        poster.autoPost(guild, WINDOW);
        verify(entries, never()).forWeek(any(), any(), any());
        assertTrue(sent.isEmpty());
    }

    @Test
    void autoPostDoesNothingUntilAChannelIsChosen() {
        settings(true, null, WheelMessage.DEFAULT);
        poster.autoPost(guild, WINDOW);
        verify(entries, never()).forWeek(any(), any(), any());
    }

    @Test
    void autoPostStaysQuietWhenPostingIsSwitchedOffOnThisInstance() {
        settings(true, CHANNEL, WheelMessage.DEFAULT);
        when(toggle.isEnabled()).thenReturn(false);
        poster.autoPost(guild, WINDOW);
        verify(entries, never()).forWeek(any(), any(), any());
        assertTrue(sent.isEmpty());
    }

    @Test
    void autoPostDoesNothingWhenNobodyCapped() {
        settings(true, CHANNEL, WheelMessage.DEFAULT);
        when(entries.forWeek(any(), any(), any())).thenReturn(new WheelEligibility.Result(List.of(), List.of(), List.of()));
        poster.autoPost(guild, WINDOW);
        assertTrue(sent.isEmpty());
    }

    @Test
    void autoPostAnnouncesAWinnerFromTheEligibleNamesWithTheGif() {
        settings(true, CHANNEL, "Winner: {winner} out of {entries} ({week})");
        when(entries.forWeek(guild, WINDOW[0], WINDOW[1])).thenReturn(new WheelEligibility.Result(List.of("Axley", "Giff"), List.of(), List.of()));

        poster.autoPost(guild, WINDOW);

        assertEquals(1, sent.size());
        String text = sent.get(0).toString();
        assertTrue(text.equals("Winner: **Axley** out of 2 (7 Oct to 13 Oct)") || text.equals("Winner: **Giff** out of 2 (7 Oct to 13 Oct)"), text);
        assertTrue(calls.contains("addFiles"), "the wheel gif is attached: " + calls);
        assertTrue(calls.contains("queue"), "and the message is sent: " + calls);
    }

    @Test
    void aProblemWhileAutoPostingNeverEscapes() {
        settings(true, CHANNEL, WheelMessage.DEFAULT);
        when(entries.forWeek(any(), any(), any())).thenThrow(new RuntimeException("database down"));
        poster.autoPost(guild, WINDOW); // would fail the weekly run if it threw
        assertTrue(sent.isEmpty());
    }

    @Test
    void aLinkedWinnerIsMentionedAndPinged() {
        settings(false, CHANNEL, "Well done {mention}!");
        Member member = mock(Member.class);
        when(guild.getMemberById(77L)).thenReturn(member);
        when(links.getAllLinks(GUILD)).thenReturn(List.of(new PlayerLink(1, GUILD, 77L, "Axley", "x", null, null)));

        poster.postWinner(guild, WINDOW, "axley", 4, null);

        assertEquals("Well done <@77>!", sent.get(0).toString());
        assertTrue(calls.contains("mentionUsers"), "only the winner is pinged: " + calls);
        assertFalse(calls.contains("addFiles"), "no gif was given");
    }

    @Test
    void anUnlinkedWinnerIsNamedAndNobodyIsPinged() {
        settings(false, CHANNEL, "Well done {mention}!");
        when(links.getAllLinks(GUILD)).thenReturn(List.of());
        poster.postWinner(guild, WINDOW, "Axley", 4, null);
        assertEquals("Well done **Axley**!", sent.get(0).toString());
        assertFalse(calls.contains("mentionUsers"));
    }

    @Test
    void withoutAUsableChannelThePostIsRefusedWithAReason() {
        settings(false, null, WheelMessage.DEFAULT);
        assertThrows(CitadelWheelPoster.CannotPost.class, () -> poster.postWinner(guild, WINDOW, "Axley", 4, null));

        settings(false, 404L, WheelMessage.DEFAULT);
        assertThrows(CitadelWheelPoster.CannotPost.class, () -> poster.channelFor(guild));

        settings(false, CHANNEL, WheelMessage.DEFAULT);
        when(channel.canTalk()).thenReturn(false);
        assertThrows(CitadelWheelPoster.CannotPost.class, () -> poster.channelFor(guild));
        assertTrue(sent.isEmpty());
    }

    @Test
    void theDashboardsWinnerMustBeOnTheWheel() {
        settings(false, CHANNEL, WheelMessage.DEFAULT);
        assertThrows(CitadelWheelPoster.CannotPost.class, () -> poster.postFromDashboard(guild, WINDOW, "Nobody", List.of("Axley", "Giff")));
    }

    @Test
    void theDashboardsWinnerIsAnnouncedWithAGif() throws Exception {
        settings(false, CHANNEL, "Winner: {winner}");
        when(links.getAllLinks(GUILD)).thenReturn(List.of());
        poster.postFromDashboard(guild, WINDOW, "giff", List.of("Axley", "Giff"));
        assertEquals("Winner: **Giff**", sent.get(0).toString());
        assertTrue(calls.contains("addFiles"));
    }

    // ---------- locked weeks ----------

    private static final java.time.LocalDate WEEK_END = java.time.LocalDate.parse("2026-10-14");

    private WheelWinnerRepository.Winner locked(String winner, boolean hasGif) {
        return new WheelWinnerRepository.Winner(GUILD, WEEK_END, winner, List.of("Axley", "Giff"), WheelWinnerRepository.Method.SPUN, 1L, java.time.Instant.now(), hasGif,
                hasGif ? WheelWinnerRepository.GifSource.WEBSITE : null, "token");
    }

    @Test
    void theAnnouncementTextCanBeFetchedWithoutPostingAnything() {
        settings(false, CHANNEL, "Well done {mention}! ({week}, {entries})");
        Member member = mock(Member.class);
        when(guild.getMemberById(77L)).thenReturn(member);
        when(links.getAllLinks(GUILD)).thenReturn(List.of(new PlayerLink(1, GUILD, 77L, "Axley", "x", null, null)));
        assertEquals("Well done <@77>! (7 Oct to 13 Oct, 2)", poster.messageFor(guild, WINDOW, "Axley", 2));
        assertTrue(sent.isEmpty());
    }

    @Test
    void autoPostLeavesAWeekThatAlreadyHasALockedWinnerAlone() {
        settings(true, CHANNEL, WheelMessage.DEFAULT);
        when(winners.get(GUILD, WEEK_END)).thenReturn(java.util.Optional.of(locked("Axley", true)));
        poster.autoPost(guild, WINDOW);
        verify(entries, never()).forWeek(any(), any(), any());
        assertTrue(sent.isEmpty());
    }

    @Test
    void autoPostLocksTheWinnerBeforeItAnnouncesThem() {
        settings(true, CHANNEL, "Winner: {winner}");
        when(winners.get(GUILD, WEEK_END)).thenReturn(java.util.Optional.empty());
        when(entries.forWeek(guild, WINDOW[0], WINDOW[1])).thenReturn(new WheelEligibility.Result(List.of("Axley", "Giff"), List.of(), List.of()));
        poster.autoPost(guild, WINDOW);
        org.mockito.ArgumentCaptor<String> winner = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(winners).lock(org.mockito.ArgumentMatchers.eq(GUILD), org.mockito.ArgumentMatchers.eq(WEEK_END), winner.capture(), any(), org.mockito.ArgumentMatchers.eq(WheelWinnerRepository.Method.AUTO),
                org.mockito.ArgumentMatchers.isNull(), any(), org.mockito.ArgumentMatchers.eq(WheelWinnerRepository.GifSource.BOT));
        assertTrue(sent.get(0).toString().contains(winner.getValue()), "the announced winner is the locked one");
    }

    @Test
    void aLockedWeekIsAnnouncedWithItsSavedPicture() {
        settings(false, CHANNEL, "Winner: {winner}");
        when(winners.get(GUILD, WEEK_END)).thenReturn(java.util.Optional.of(locked("Giff", true)));
        when(winners.gif(GUILD, WEEK_END)).thenReturn(java.util.Optional.of("GIF89a-bytes-here".getBytes()));
        when(links.getAllLinks(GUILD)).thenReturn(List.of());
        poster.postLocked(guild, WEEK_END);
        assertEquals("Winner: **Giff**", sent.get(0).toString());
        assertTrue(calls.contains("addFiles"), "the saved picture is attached: " + calls);
    }

    @Test
    void anUnlockedWeekCannotBePostedAsALockedOne() {
        settings(false, CHANNEL, WheelMessage.DEFAULT);
        when(winners.get(GUILD, WEEK_END)).thenReturn(java.util.Optional.empty());
        assertThrows(CitadelWheelPoster.CannotPost.class, () -> poster.postLocked(guild, WEEK_END));
    }
}
