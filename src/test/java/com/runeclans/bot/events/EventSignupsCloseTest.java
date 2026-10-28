package com.runeclans.bot.events;

import com.runeclans.bot.commands.signup.SignupEntry;
import com.runeclans.bot.commands.signup.SignupService;
import com.runeclans.bot.points.PointsRepository;
import com.runeclans.bot.points.PointsService;
import com.runeclans.bot.points.RewardsRepository;
import com.runeclans.bot.testsupport.LocalDatabase;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.SelfUser;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Closing an event's signups as the organiser's one action: the event stops taking players' own joins first, then the Discord signup is paused, then
 * the roster is synced one last time (so nobody can slip in between), and the deadline does the same by itself. Runs over the developer's local
 * Postgres (skipped without one); the Discord signup is a mock.
 */
class EventSignupsCloseTest {
    private static final long G = 999_000_000_000_000_031L;
    private static ConnectionSupplier supplier;
    private EventRepository repository;
    private EventService service;
    private SignupService signupService;
    private EventSignups signups;
    private Guild guild;

    @BeforeAll
    static void connect() throws Exception {
        supplier = LocalDatabase.supplier();
    }

    @BeforeEach
    void setUp() {
        repository = new EventRepository(supplier);
        EventKinds kinds = new EventKinds(null, null) {
            @Override
            public TeamRules.Kind kindOf(long hostGuildId, long userGuildId, long userId) {
                return TeamRules.Kind.HOME_CLAN;
            }
        };
        service = new EventService(repository, new EventTemplates(List.of(new CustomTemplate())), kinds, new PointsService(new PointsRepository(supplier), new RewardsRepository(supplier)), new EventChanges());
        signupService = Mockito.mock(SignupService.class);
        signups = new EventSignups(repository, service, signupService, new EventChanges());
        guild = Mockito.mock(Guild.class);
        Mockito.when(guild.getIdLong()).thenReturn(G);
        Mockito.when(guild.getJDA()).thenReturn(Mockito.mock(JDA.class, Mockito.RETURNS_DEEP_STUBS));
    }

    @AfterEach
    void cleanUp() throws Exception {
        try (Connection c = supplier.getConnection(); PreparedStatement s = c.prepareStatement("DELETE FROM younglings.event WHERE guild_id = ?")) {
            s.setLong(1, G);
            s.executeUpdate();
        }
    }

    private long signupsOpen(String name, String config) {
        long id = service.createEvent(G, 9L, new EventService.EventInput(name, "", "custom", EventRules.Scope.PRIVATE, EventRules.ScoringMode.INDIVIDUAL, null, null, 0, DataObject.fromJson(config)));
        service.moveStage(G, id, EventRules.Status.SIGNUPS);
        return id;
    }

    @Test
    void closingMarksTheEventClosedBeforePausingThenSyncsOneLastTime() {
        long id = signupsOpen("Close me", "{}");
        repository.setSignup(id, 7777L);
        Mockito.when(signupService.getEntries(7777L)).thenReturn(List.of(new SignupEntry(501, "one", null), new SignupEntry(502, "two", null)));
        Mockito.when(signupService.isSignupActive(7777L)).thenReturn(true);
        List<Boolean> closedWhenPaused = new ArrayList<>();
        Mockito.when(signupService.togglePause(7777L)).thenAnswer(invocation -> {
            closedWhenPaused.add(repository.setup(id).signupsClosed());
            return "PAUSED";
        });

        EventSignups.Closed closed = signups.closeSignups(guild, id, 9L);

        assertEquals(List.of(true), closedWhenPaused, "the event was already closed to players when the Discord signup was paused");
        assertEquals(2, closed.added(), "the last sync added both entries");
        assertEquals(2, closed.active());
        assertEquals(0, closed.waiting());
        assertEquals(2, repository.participants(id).size());
        assertEquals(9L, repository.setup(id).signupsClosedBy());
        assertThrows(EventProblem.class, () -> signups.closeSignups(guild, id, 9L), "closing twice is refused");
        // a player can't join for themselves now, and an entry that arrives after is still synced because staff-style adds are allowed
        assertThrows(EventProblem.class, () -> service.join(G, id, G, 503, null, false, null));
    }

    @Test
    void reopeningSwitchesTheDiscordSignupBackOnAndTheEventTakesPlayersAgain() {
        long id = signupsOpen("Reopen me", "{}");
        repository.setSignup(id, 7777L);
        Mockito.when(signupService.getEntries(7777L)).thenReturn(List.of());
        Mockito.when(signupService.isSignupActive(7777L)).thenReturn(true);
        signups.closeSignups(guild, id, 9L);

        Mockito.when(signupService.isSignupActive(7777L)).thenReturn(false);
        signups.reopenSignups(guild, id);

        Mockito.verify(signupService, Mockito.times(2)).togglePause(7777L);
        // the panels in Discord are redrawn each time, so they say PAUSED and then ACTIVE at once (they used to keep the old status until someone clicked one)
        Mockito.verify(signupService, Mockito.times(2)).updateMessages(guild, 7777L);
        assertFalse(repository.setup(id).signupsClosed());
        service.join(G, id, G, 503, null, false, null);
        assertEquals(1, repository.participants(id).size());
        assertThrows(EventProblem.class, () -> signups.reopenSignups(guild, id), "they are open already");
    }

    @Test
    void anEventWithNoDiscordSignupClosesJustTheSame() {
        long id = signupsOpen("Website only", "{}");
        service.join(G, id, G, 501, null, true, null);
        EventSignups.Closed closed = signups.closeSignups(guild, id, 9L);
        assertEquals(0, closed.added());
        assertEquals(1, closed.active());
        Mockito.verifyNoInteractions(signupService);
    }

    @Test
    void theSweepClosesSignupsPastTheirDeadlineAsAnOrganiserWouldAndLeavesTheOthersOpen() {
        long late = signupsOpen("Past deadline", "{\"signupClosesAt\":\"2020-01-01T00:00:00Z\"}");
        long future = signupsOpen("Not yet", "{\"signupClosesAt\":\"2099-01-01T00:00:00Z\"}");
        long none = signupsOpen("No deadline", "{}");
        JDA jda = Mockito.mock(JDA.class); // plain mock: a server it hasn't been told about is null, so nothing outside this test is touched
        SelfUser self = Mockito.mock(SelfUser.class);
        Mockito.when(self.getIdLong()).thenReturn(77L);
        Mockito.when(jda.getSelfUser()).thenReturn(self);
        Mockito.when(jda.getGuildById(G)).thenReturn(guild);

        signups.sweep(jda);

        assertTrue(repository.setup(late).signupsClosed());
        assertEquals(77L, repository.setup(late).signupsClosedBy(), "the bot is who closed it");
        assertFalse(repository.setup(future).signupsClosed());
        assertFalse(repository.setup(none).signupsClosed());
        signups.sweep(jda); // a second sweep finds nothing more to do
        assertEquals(77L, repository.setup(late).signupsClosedBy());
    }
}
