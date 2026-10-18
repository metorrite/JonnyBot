package com.younglings.bot.events;

import com.younglings.bot.events.EventRepository.Event;
import com.younglings.bot.events.EventRules.Evidence;
import com.younglings.bot.events.EventRules.ObjectiveSpec;
import com.younglings.bot.events.EventRules.Scope;
import com.younglings.bot.events.EventRules.ScoringMode;
import com.younglings.bot.events.EventRules.Status;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel;
import net.dv8tion.jda.api.requests.restaction.MessageCreateAction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EventBoardPanelsTest {
    private static final long GUILD = 5L;
    private static final long EVENT = 11L;
    private static final long CHANNEL = 777L;

    private EventRepository repository;
    private EventService service;
    private EventBoardPanels panels;
    private EventSpaceRepository space;
    private Guild guild;
    private GuildMessageChannel channel;
    private final List<String> calls = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        repository = mock(EventRepository.class);
        service = mock(EventService.class);
        BingoTiles tiles = mock(BingoTiles.class);
        space = mock(EventSpaceRepository.class);
        panels = new EventBoardPanels(repository, service, space, new EventChanges(), tiles);

        JDA jda = mock(JDA.class);
        guild = mock(Guild.class);
        when(jda.getGuildById(GUILD)).thenReturn(guild);
        Field field = EventBoardPanels.class.getDeclaredField("jda");
        field.setAccessible(true);
        field.set(panels, jda);

        channel = mock(GuildMessageChannel.class);
        when(channel.getIdLong()).thenReturn(CHANNEL);
        when(channel.getName()).thenReturn("bingo");
        when(guild.getChannelById(GuildMessageChannel.class, CHANNEL)).thenReturn(channel);
        net.dv8tion.jda.api.entities.SelfMember self = mock(net.dv8tion.jda.api.entities.SelfMember.class);
        when(self.hasPermission(any(GuildChannel.class), any(Permission[].class))).thenReturn(true);
        when(guild.getSelfMember()).thenReturn(self);

        MessageCreateAction action = mock(MessageCreateAction.class, invocation -> {
            calls.add(invocation.getMethod().getName());
            return invocation.getMethod().getReturnType().isInstance(invocation.getMock()) ? invocation.getMock() : null;
        });
        when(channel.sendMessage(any(CharSequence.class))).thenReturn(action);

        when(service.objectives(EVENT)).thenReturn(List.of(new ObjectiveSpec("t1", "Nex: Torva boots", "", 5, Evidence.EITHER, 1, 1, List.of()),
                new ObjectiveSpec("t2", "Araxxor: Noxious blade", "", 5, Evidence.EITHER, 1, 1, List.of())));
        when(service.standings(GUILD, EVENT)).thenReturn(List.of());
        when(repository.participants(EVENT)).thenReturn(List.of());
        when(repository.panel(EVENT)).thenReturn(Optional.empty());
    }

    private void event(String template, Status status, String config) {
        when(repository.event(GUILD, EVENT)).thenReturn(Optional.of(new Event(EVENT, GUILD, template, "Bingo night", "", status, Scope.PRIVATE, ScoringMode.INDIVIDUAL, null, null, 50, config,
                1L, Instant.now(), null, false)));
    }

    private static final String BOARD = "\"board\":{\"keys\":[\"t1\",\"t2\",\"t3\",\"t4\",\"t5\",\"t6\",\"t7\",\"t8\",\"t9\"],\"size\":3}";

    @Test
    void aRunningBingoWithABoardChannelPostsItsBoardAsAPicture() {
        event(BingoTemplate.KEY, Status.RUNNING, "{\"boardChannelId\":\"" + CHANNEL + "\"," + BOARD + "}");

        String result = panels.refresh(GUILD, EVENT);

        assertEquals("Posted the board in #bingo.", result);
        verify(channel).sendMessage(any(CharSequence.class));
        assertTrue(calls.contains("addFiles"), "the board picture is attached: " + calls);
    }

    @Test
    void aServerThatCannotDrawTextOnAPictureStillGetsItsBoardAsText() {
        event(BingoTemplate.KEY, Status.RUNNING, "{\"boardChannelId\":\"" + CHANNEL + "\"," + BOARD + "}");
        List<String> sent = new ArrayList<>();
        when(channel.sendMessage(any(CharSequence.class))).thenAnswer(invocation -> {
            sent.add(invocation.getArgument(0).toString());
            return mock(MessageCreateAction.class, inner -> {
                calls.add(inner.getMethod().getName());
                return inner.getMethod().getReturnType().isInstance(inner.getMock()) ? inner.getMock() : null;
            });
        });

        try (var drawing = org.mockito.Mockito.mockStatic(BingoBoardImage.class)) {
            drawing.when(() -> BingoBoardImage.render(any(net.dv8tion.jda.api.utils.data.DataObject.class), any(), any(), any(), any()))
                    .thenThrow(new RuntimeException("Fontconfig head is null, check your fonts or fonts configuration"));

            String result = panels.refresh(GUILD, EVENT);

            assertTrue(result.startsWith("Posted the board in #bingo."), result);
            assertTrue(result.contains("as text"), result);
        }
        assertEquals(1, sent.size());
        assertTrue(sent.get(0).contains("Bingo night"), "the event's name is there");
        assertTrue(sent.get(0).contains("⬜ **1.**"), "the tiles are listed: " + sent.get(0));
        assertTrue(!calls.contains("addFiles"), "no picture is attached: " + calls);
    }

    @Test
    void everyReasonNothingIsPostedIsSaidInWords() {
        event(BingoTemplate.KEY, Status.RUNNING, "{" + BOARD + "}");
        assertEquals("Shared board: there is no live board channel yet. Choose or make one on the Channels tab.", panels.refresh(GUILD, EVENT));

        event(BingoTemplate.KEY, Status.SIGNUPS, "{\"boardChannelId\":\"" + CHANNEL + "\"," + BOARD + "}");
        assertEquals("The board is posted once the event starts.", panels.refresh(GUILD, EVENT));

        event("boss-night", Status.RUNNING, "{\"boardChannelId\":\"" + CHANNEL + "\"}");
        assertEquals("Only bingo events have a board.", panels.refresh(GUILD, EVENT));

        event(BingoTemplate.KEY, Status.RUNNING, "{\"boardChannelId\":\"999\"," + BOARD + "}");
        assertTrue(panels.refresh(GUILD, EVENT).startsWith("The board channel isn't a channel JonnyBot can post in"));

        verify(channel, never()).sendMessage(any(CharSequence.class));
        verify(repository, never()).setPanel(anyLong(), anyLong(), eq(0L));
    }

    @Test
    void withoutPermissionItSaysSoInsteadOfPostingNothing() {
        event(BingoTemplate.KEY, Status.RUNNING, "{\"boardChannelId\":\"" + CHANNEL + "\"," + BOARD + "}");
        net.dv8tion.jda.api.entities.SelfMember self = mock(net.dv8tion.jda.api.entities.SelfMember.class);
        when(self.hasPermission(any(GuildChannel.class), any(Permission[].class))).thenReturn(false);
        when(guild.getSelfMember()).thenReturn(self);

        assertEquals("JonnyBot needs to see, send messages and attach files in #bingo.", panels.refresh(GUILD, EVENT));
    }

    // ---------- each player's or team's own board ----------

    private EventRepository.Participant player(long userId) {
        return new EventRepository.Participant(EVENT, userId, GUILD, null, com.younglings.bot.events.TeamRules.Kind.HOME_CLAN, false, PrizeRules.BuyInStatus.NONE, Instant.now(),
                EventRoster.Verification.PENDING, null, null, "", null, EventRepository.RosterSource.STAFF);
    }

    @Test
    void withOwnBoardsEachPlayerGetsTheirBoardInTheirOwnChannelAndWhoHasNoPlaceIsSaid() {
        event(BingoTemplate.KEY, Status.RUNNING, "{" + BOARD.replace("\"size\":3}", "\"size\":3,\"display\":\"OWN\"}") + "}");
        when(repository.participants(EVENT)).thenReturn(List.of(player(501), player(502)));
        when(space.channels(EVENT)).thenReturn(List.of(new EventSpaceRepository.ChannelRow(EVENT, "player:501", CHANNEL, "bingo-501", true, "category")));

        String result = panels.refresh(GUILD, EVENT);

        assertEquals("Own boards: 1 posted, 0 updated, 0 unchanged, 1 with nowhere to post (set up the event's channels, or choose a live board channel).", result);
        verify(channel).sendMessage(any(CharSequence.class));
    }

    @Test
    void ownBoardsNeedNoLiveBoardChannelAndAPlayerWithoutAChannelUsesItWhenThereIsOne() {
        event(BingoTemplate.KEY, Status.RUNNING, "{\"boardChannelId\":\"" + CHANNEL + "\"," + BOARD.replace("\"size\":3}", "\"size\":3,\"display\":\"OWN\"}") + "}");
        when(repository.participants(EVENT)).thenReturn(List.of(player(501), player(502)));

        String result = panels.refresh(GUILD, EVENT);

        assertEquals("Own boards: 2 posted, 0 updated, 0 unchanged.", result, "no channels of their own: both go in the live board channel");
        verify(channel, org.mockito.Mockito.times(2)).sendMessage(any(CharSequence.class));
    }

    @Test
    void anOwnBoardsEventPostsNoSharedBoardAndABothEventPostsBoth() {
        event(BingoTemplate.KEY, Status.RUNNING, "{\"boardChannelId\":\"" + CHANNEL + "\"," + BOARD.replace("\"size\":3}", "\"size\":3,\"display\":\"OWN\"}") + "}");
        when(repository.participants(EVENT)).thenReturn(List.of(player(501)));
        assertEquals("Own boards: 1 posted, 0 updated, 0 unchanged.", panels.refresh(GUILD, EVENT), "no 'Posted the board' sentence: only their own");

        event(BingoTemplate.KEY, Status.RUNNING, "{\"boardChannelId\":\"" + CHANNEL + "\"," + BOARD.replace("\"size\":3}", "\"size\":3,\"display\":\"BOTH\"}") + "}");
        String both = panels.refresh(GUILD, EVENT);
        assertTrue(both.startsWith("Posted the board in #bingo."), both);
        assertTrue(both.contains("Own boards: 1 posted"), both);
    }

    @Test
    void ownBoardsWithNobodyInTheEventSaySo() {
        event(BingoTemplate.KEY, Status.RUNNING, "{" + BOARD.replace("\"size\":3}", "\"size\":3,\"display\":\"OWN\"}") + "}");
        assertEquals("No own boards yet: nobody is in the event.", panels.refresh(GUILD, EVENT));
    }
}
