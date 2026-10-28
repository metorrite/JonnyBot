package com.younglings.bot.events;

import com.younglings.bot.commands.signup.SignupEntry;
import com.younglings.bot.commands.signup.SignupService;
import com.younglings.bot.commands.signup.SignupSession;
import com.younglings.bot.events.EventRepository.RosterSource;
import com.younglings.bot.points.PointsRepository;
import com.younglings.bot.points.PointsService;
import com.younglings.bot.points.RewardsRepository;
import com.younglings.bot.testsupport.LocalDatabase;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import io.github.freya022.botcommands.api.core.events.InjectedJDAEvent;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The roster and the event's Discord signup kept the same both ways, and players barred from an event, over a real database (skipped without one) with the
 * Discord signup pretended: who signs up lands on the roster, who is taken off the signup is taken off the roster, who joins or leaves on the website joins or
 * leaves the signup, staff-added players are left alone, and a barred player can't get back in by any road.
 */
class EventRosterSyncTest {
    private static final long G = 999_000_000_000_000_081L;
    private static final long SIGNUP = 7777L;
    private static ConnectionSupplier supplier;

    private EventRepository repository;
    private EventService service;
    private EventChanges changes;
    private SignupService signupService;
    private EventSignups signups;
    private Guild guild;
    private List<SignupEntry> entries;

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
        changes = new EventChanges();
        service = new EventService(repository, new EventTemplates(List.of(new CustomTemplate())), kinds, new PointsService(new PointsRepository(supplier), new RewardsRepository(supplier)), changes);
        // the website-to-signup mirror runs on the event-signups lane while the test thread reads this list
        entries = new CopyOnWriteArrayList<>();
        signupService = Mockito.mock(SignupService.class);
        Mockito.when(signupService.getEntries(SIGNUP)).thenAnswer(inv -> List.copyOf(entries));
        Mockito.when(signupService.getSessionById(SIGNUP)).thenReturn(Mockito.mock(SignupSession.class));
        Mockito.when(signupService.removeUser(ArgumentMatchers.any(), ArgumentMatchers.eq(SIGNUP), ArgumentMatchers.anyLong())).thenAnswer(inv -> entries.removeIf(e -> e.userId() == (long) inv.getArgument(2)));
        Mockito.when(signupService.addManualUser(ArgumentMatchers.any(), ArgumentMatchers.eq(SIGNUP), ArgumentMatchers.anyLong(), ArgumentMatchers.anyString(), ArgumentMatchers.any(), ArgumentMatchers.anyLong()))
                .thenAnswer(inv -> entries.add(new SignupEntry(inv.getArgument(2), inv.getArgument(3), null)));
        signups = new EventSignups(repository, service, signupService, changes);

        guild = Mockito.mock(Guild.class);
        JDA jda = Mockito.mock(JDA.class, Mockito.RETURNS_DEEP_STUBS);
        Mockito.when(guild.getIdLong()).thenReturn(G);
        Mockito.when(guild.getJDA()).thenReturn(jda);
        // a stand-alone JDA (deep stubs would otherwise hand back an empty mock ShardManager, T-668)
        Mockito.when(jda.getShardManager()).thenReturn(null);
        Mockito.when(jda.getGuildById(G)).thenReturn(guild);
        Member member = Mockito.mock(Member.class);
        Mockito.when(member.getEffectiveName()).thenReturn("Someone");
        Mockito.when(guild.getMemberById(ArgumentMatchers.anyLong())).thenReturn(member);
        InjectedJDAEvent ready = Mockito.mock(InjectedJDAEvent.class);
        Mockito.when(ready.getJda()).thenReturn(jda);
        signups.onJdaReady(ready);
    }

    @AfterEach
    void cleanUp() throws Exception {
        try (Connection c = supplier.getConnection(); PreparedStatement s = c.prepareStatement("DELETE FROM younglings.event WHERE guild_id = ?")) {
            s.setLong(1, G);
            s.executeUpdate();
        }
    }

    private long event(String name) {
        long id = service.createEvent(G, 9L, new EventService.EventInput(name, "", "custom", EventRules.Scope.PRIVATE, EventRules.ScoringMode.TEAM, null, null, 0, DataObject.fromJson("{}")));
        service.moveStage(G, id, EventRules.Status.SIGNUPS);
        repository.setSignup(id, SIGNUP);
        return id;
    }

    private SignupEntry entry(long userId) {
        return new SignupEntry(userId, "name" + userId, null);
    }

    private boolean onRoster(long id, long userId) {
        return repository.participant(id, userId).isPresent();
    }

    // ---------- Discord -> roster ----------

    @Test
    void whoSignsUpInDiscordIsAddedAndFollowsTheSignup() {
        long id = event("Follow");
        entries.add(entry(501));
        assertEquals(1, signups.sync(guild, id));
        assertEquals(RosterSource.SIGNUP, repository.participant(id, 501).orElseThrow().source());

        entries.removeIf(e -> e.userId() == 501);
        signups.sync(guild, id);
        assertFalse(onRoster(id, 501), "taken off the signup in Discord, taken off the roster");
    }

    @Test
    void aTeamMemberTakenOffTheSignupLeavesTheirTeamToo() {
        long id = event("Team");
        long red = service.addTeam(G, id, "Red", null);
        entries.add(entry(501));
        signups.sync(guild, id);
        service.setTeam(G, id, 501, red);
        entries.clear();
        signups.sync(guild, id);
        assertFalse(onRoster(id, 501));
        assertTrue(repository.participants(id).stream().noneMatch(p -> red == (p.teamId() == null ? -1 : p.teamId())), "nobody is left on the team");
    }

    @Test
    void staffAddedPlayersHaveNoSignupEntryAndAreLeftAlone() {
        long id = event("Staff");
        service.join(G, id, G, 601, null, true, null);
        assertEquals(RosterSource.STAFF, repository.participant(id, 601).orElseThrow().source());
        entries.add(entry(501));
        signups.sync(guild, id);
        assertTrue(onRoster(id, 601), "not on the signup, but staff put them there");
        assertTrue(onRoster(id, 501));
    }

    @Test
    void aSignupThatCannotBeReadDoesNotEmptyTheRoster() {
        long id = event("Missing");
        entries.add(entry(501));
        signups.sync(guild, id);
        entries.clear();
        Mockito.when(signupService.getSessionById(SIGNUP)).thenReturn(null);
        signups.sync(guild, id);
        assertTrue(onRoster(id, 501), "a signup that isn't loaded looks empty: nobody is taken off because of that");
    }

    @Test
    void onceTheEventHasStartedTheSignupNoLongerChangesTheRoster() {
        long id = event("Started");
        entries.add(entry(501));
        signups.sync(guild, id);
        service.setObjectives(G, id, List.of(new EventRules.ObjectiveSpec("kill", "Kill it", "", 10, EventRules.Evidence.EITHER, 1, 1, List.of())));
        service.moveStage(G, id, EventRules.Status.RUNNING);
        entries.clear();
        signups.sync(guild, id);
        assertTrue(onRoster(id, 501));
    }

    @Test
    void closedSignupsStillFollowRemovalsButTakeNoNewPlayers() {
        long id = event("Closed");
        entries.add(entry(501));
        entries.add(entry(502));
        signups.sync(guild, id);
        service.closeSignups(G, id, 9L);
        entries.removeIf(e -> e.userId() == 501);
        signups.syncRemovals(guild, id);
        assertFalse(onRoster(id, 501));
        assertTrue(onRoster(id, 502));
    }

    @Test
    void aPlayerWhoPaidTheirBuyInStaysAndStaffAreToldWhy() {
        long id = service.createEvent(G, 9L, new EventService.EventInput("Paid", "", "custom", EventRules.Scope.PRIVATE, EventRules.ScoringMode.INDIVIDUAL, null, null, 0,
                DataObject.fromJson("{\"buyIn\":{\"enabled\":true,\"kind\":\"IN_GAME\",\"amount\":1000}}")));
        service.moveStage(G, id, EventRules.Status.SIGNUPS);
        repository.setSignup(id, SIGNUP);
        entries.add(entry(501));
        signups.sync(guild, id);
        service.setBuyIn(G, id, 501, PrizeRules.BuyInStatus.PAID);
        entries.clear();
        signups.sync(guild, id);
        assertTrue(onRoster(id, 501), "a paid buy-in has to be refunded before anyone is removed");
        assertEquals(1, repository.refusals(id).size());
        assertTrue(repository.refusals(id).get(0).reason().contains("buy-in"), repository.refusals(id).get(0).reason());
    }

    // ---------- website -> signup ----------

    @Test
    void aPlayerWhoJoinsOnTheWebsiteGetsAnEntryAndOneWhoLeavesLosesIt() throws Exception {
        long id = event("Web");
        service.join(G, id, G, 701, null, false, null);
        assertEquals(RosterSource.SELF, repository.participant(id, 701).orElseThrow().source());
        Mockito.verify(signupService, Mockito.timeout(15_000)).addManualUser(ArgumentMatchers.eq(guild), ArgumentMatchers.eq(SIGNUP), ArgumentMatchers.eq(701L), ArgumentMatchers.anyString(), ArgumentMatchers.any(), ArgumentMatchers.eq(701L));
        waitFor(() -> repository.participant(id, 701).orElseThrow().source() == RosterSource.SIGNUP);
        assertTrue(entries.stream().anyMatch(e -> e.userId() == 701));

        service.leave(G, id, 701);
        Mockito.verify(signupService, Mockito.timeout(15_000)).removeUser(guild, SIGNUP, 701L);
        waitFor(() -> entries.stream().noneMatch(e -> e.userId() == 701));
    }

    @Test
    void aStaffAddIsNotCopiedOntoTheSignup() throws Exception {
        long id = event("Staff add");
        service.join(G, id, G, 601, null, true, null);
        Thread.sleep(300);
        Mockito.verify(signupService, Mockito.never()).addManualUser(ArgumentMatchers.any(), ArgumentMatchers.anyLong(), ArgumentMatchers.anyLong(), ArgumentMatchers.anyString(), ArgumentMatchers.any(), ArgumentMatchers.anyLong());
    }

    private static void waitFor(java.util.function.BooleanSupplier condition) throws InterruptedException {
        for (int i = 0; i < 300 && !condition.getAsBoolean(); i++) Thread.sleep(50);
        assertTrue(condition.getAsBoolean());
    }

    // ---------- bans ----------

    @Test
    void aBarredPlayerIsRemovedAndCannotJoinOrBeAddedUntilTheBanIsLifted() {
        long id = event("Barred");
        service.join(G, id, G, 801, null, true, null);
        service.ban(G, id, 801, "  spamming the signup  ", 9L);
        assertFalse(onRoster(id, 801));
        assertEquals("spamming the signup", repository.ban(id, 801).orElseThrow().reason());

        EventProblem own = assertThrows(EventProblem.class, () -> service.join(G, id, G, 801, null, false, null));
        assertEquals(403, own.status());
        assertTrue(own.getMessage().contains("spamming the signup"), own.getMessage());
        EventProblem byStaff = assertThrows(EventProblem.class, () -> service.join(G, id, G, 801, null, true, null));
        assertTrue(byStaff.getMessage().contains("Lift the ban"), byStaff.getMessage());

        service.unban(G, id, 801);
        service.join(G, id, G, 801, null, false, null);
        assertTrue(onRoster(id, 801));
        assertEquals(404, assertThrows(EventProblem.class, () -> service.unban(G, id, 801)).status(), "nothing left to lift");
    }

    @Test
    void aBarredPlayerWhoSignsUpInDiscordIsNotAddedAndTheirEntryGoes() {
        long id = event("Barred signup");
        service.ban(G, id, 802, "left a mess last time", 9L);
        entries.add(entry(802));
        entries.add(entry(803));
        assertEquals(1, signups.sync(guild, id), "only the other one");
        assertFalse(onRoster(id, 802));
        assertTrue(onRoster(id, 803));
        assertTrue(entries.stream().noneMatch(e -> e.userId() == 802), "their entry was taken off the signup");
        assertEquals(1, repository.refusals(id).size());
    }

    @Test
    void banningNeedsAReasonAndWorksOnSomeoneWhoHasNotJoined() {
        long id = event("Reason");
        assertEquals(400, assertThrows(EventProblem.class, () -> service.ban(G, id, 804, "   ", 9L)).status());
        assertEquals(400, assertThrows(EventProblem.class, () -> service.ban(G, id, 804, "x".repeat(EventService.MAX_BAN_REASON + 1), 9L)).status());
        service.ban(G, id, 804, "known troublemaker", 9L);
        assertEquals(1, service.bans(id).size());
        assertEquals(804L, service.bans(id).get(0).userId());
    }

    @Test
    void aPlayerWhoPaidTheirBuyInCannotBeBarredUntilRefunded() {
        long id = service.createEvent(G, 9L, new EventService.EventInput("Paid ban", "", "custom", EventRules.Scope.PRIVATE, EventRules.ScoringMode.INDIVIDUAL, null, null, 0,
                DataObject.fromJson("{\"buyIn\":{\"enabled\":true,\"kind\":\"IN_GAME\",\"amount\":1000}}")));
        service.moveStage(G, id, EventRules.Status.SIGNUPS);
        service.join(G, id, G, 805, null, true, null);
        service.setBuyIn(G, id, 805, PrizeRules.BuyInStatus.PAID);
        EventProblem paid = assertThrows(EventProblem.class, () -> service.ban(G, id, 805, "reason", 9L));
        assertEquals(409, paid.status());
        assertTrue(onRoster(id, 805));
        assertTrue(repository.ban(id, 805).isEmpty(), "nothing half done");
    }
}
