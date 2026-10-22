package com.younglings.bot.internal;

import com.younglings.bot.alliance.AllianceRepository;
import com.younglings.bot.clanprofile.ClanProfileService;
import com.younglings.bot.clanprofile.JoinLinks;
import com.younglings.bot.events.CustomTemplate;
import com.younglings.bot.events.EventChanges;
import com.younglings.bot.events.EventCohostRepository;
import com.younglings.bot.events.EventKinds;
import com.younglings.bot.events.EventRepository;
import com.younglings.bot.events.EventRules;
import com.younglings.bot.events.EventService;
import com.younglings.bot.events.EventTemplates;
import com.younglings.bot.events.HubModerationRepository;
import com.younglings.bot.events.TeamRules;
import com.younglings.bot.permission.DashboardAccess;
import com.younglings.bot.points.PointsRepository;
import com.younglings.bot.points.PointsService;
import com.younglings.bot.points.RewardsRepository;
import com.younglings.bot.runescape.PlayerLinkService;
import com.younglings.bot.testsupport.LocalDatabase;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.utils.data.DataArray;
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
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What RuneClans reads from the events hub (T-335..T-337): a server's "Only this server" events for its own members only, a player's own place in an
 * event, "My events" across servers, and the dashboard link only for someone who may manage the event. Runs over the developer's local Postgres
 * (skipped without one); Discord is mocked.
 */
class HubEventsRuneClansTest {
    private static final long HOST = 999_000_000_000_000_335L;
    private static final long OTHER = 999_000_000_000_000_336L;
    private static final long PLAYER = 999_000_000_000_000_501L;
    private static final long STRANGER = 999_000_000_000_000_502L;
    private static ConnectionSupplier supplier;
    private EventService service;
    private HubEventsApi api;
    private DashboardAccess access;
    private JDA jda;
    private Guild host;

    @BeforeAll
    static void connect() throws Exception {
        supplier = LocalDatabase.supplier();
    }

    @BeforeEach
    void setUp() {
        EventRepository repository = new EventRepository(supplier);
        EventKinds kinds = new EventKinds(null, null) {
            @Override
            public TeamRules.Kind kindOf(long hostGuildId, long userGuildId, long userId) {
                return TeamRules.Kind.HOME_CLAN;
            }
        };
        service = new EventService(repository, new EventTemplates(List.of(new CustomTemplate())), kinds,
                new PointsService(new PointsRepository(supplier), new RewardsRepository(supplier)), new EventChanges());
        ClanProfileService profiles = Mockito.mock(ClanProfileService.class);
        Mockito.when(profiles.publicView(Mockito.anyLong(), Mockito.anyBoolean())).thenReturn(Optional.empty());
        HubModerationRepository moderation = Mockito.mock(HubModerationRepository.class);
        Mockito.when(moderation.barredIds()).thenReturn(java.util.Set.of());
        EventCohostRepository cohosts = Mockito.mock(EventCohostRepository.class);
        Mockito.when(cohosts.forEvent(Mockito.anyLong())).thenReturn(List.of());
        access = Mockito.mock(DashboardAccess.class);
        Mockito.when(access.tierOf(Mockito.any(), Mockito.any())).thenReturn(DashboardAccess.Tier.NONE);
        api = new HubEventsApi(repository, service, new EventTemplates(List.of(new CustomTemplate())), Mockito.mock(JoinLinks.class), profiles,
                Mockito.mock(AllianceRepository.class), Mockito.mock(PlayerLinkService.class), moderation, cohosts, access, Mockito.mock(MemberEventsApi.class));

        jda = Mockito.mock(JDA.class);
        host = Mockito.mock(Guild.class);
        Mockito.when(host.getIdLong()).thenReturn(HOST);
        Mockito.when(host.getId()).thenReturn(Long.toString(HOST));
        Mockito.when(host.getName()).thenReturn("Host server");
        Mockito.when(jda.getGuildById(HOST)).thenReturn(host);
        Member member = Mockito.mock(Member.class);
        Mockito.when(host.getMemberById(PLAYER)).thenReturn(member);
        User player = Mockito.mock(User.class);
        Mockito.when(player.getMutualGuilds()).thenReturn(List.of(host));
        Mockito.when(jda.getUserById(PLAYER)).thenReturn(player);
        User stranger = Mockito.mock(User.class);
        Mockito.when(stranger.getMutualGuilds()).thenReturn(List.of());
        Mockito.when(jda.getUserById(STRANGER)).thenReturn(stranger);
    }

    @AfterEach
    void cleanUp() throws Exception {
        try (Connection c = supplier.getConnection(); PreparedStatement s = c.prepareStatement("DELETE FROM younglings.event WHERE guild_id IN (?, ?)")) {
            s.setLong(1, HOST);
            s.setLong(2, OTHER);
            s.executeUpdate();
        }
    }

    private long event(long guild, String name, EventRules.Scope scope, EventRules.Status to) {
        long id = service.createEvent(guild, 9L, new EventService.EventInput(name, "Rules here", "custom", scope, EventRules.ScoringMode.INDIVIDUAL, null, null, 0,
                DataObject.empty()));
        if (to != EventRules.Status.DRAFT) service.moveStage(guild, id, EventRules.Status.SIGNUPS);
        if (to == EventRules.Status.RUNNING) service.moveStage(guild, id, EventRules.Status.RUNNING);
        return id;
    }

    private static List<String> names(DataObject answer) {
        DataArray events = answer.getArray("events");
        List<String> out = new ArrayList<>();
        for (int i = 0; i < events.length(); i++) out.add(events.getObject(i).getString("name"));
        return out;
    }

    @Test
    void aServersOwnEventsAreOnlyForItsMembers() {
        long own = event(HOST, "Members only", EventRules.Scope.PRIVATE, EventRules.Status.SIGNUPS);
        event(HOST, "Still a draft", EventRules.Scope.PRIVATE, EventRules.Status.DRAFT);

        assertTrue(names(api.list(jda, Long.toString(PLAYER))).contains("Members only"));
        assertFalse(names(api.list(jda, Long.toString(PLAYER))).contains("Still a draft"));
        assertFalse(names(api.list(jda, Long.toString(STRANGER))).contains("Members only"));
        assertFalse(names(api.list(jda, null)).contains("Members only"));

        DataObject seen = api.one(jda, Long.toString(own), Long.toString(PLAYER));
        assertNotNull(seen);
        assertEquals("MINE", seen.getString("access"));
        assertNull(api.one(jda, Long.toString(own), Long.toString(STRANGER)), "a stranger is told it doesn't exist");
        assertFalse(seen.toString().contains(Long.toString(HOST)), "no server id for someone who can't manage it");
    }

    @Test
    void aPlayerSeesTheirOwnPlaceAndMyEventsSpansServers() {
        long here = event(HOST, "Here", EventRules.Scope.PUBLIC, EventRules.Status.SIGNUPS);
        long there = event(OTHER, "There", EventRules.Scope.PRIVATE, EventRules.Status.SIGNUPS);
        event(OTHER, "Not joined", EventRules.Scope.PUBLIC, EventRules.Status.SIGNUPS);
        service.join(HOST, here, HOST, PLAYER, null, true, null);
        service.join(OTHER, there, OTHER, PLAYER, null, true, null);

        DataObject one = api.one(jda, Long.toString(here), Long.toString(PLAYER));
        DataObject me = one.getObject("me");
        assertFalse(me.getBoolean("waiting"));
        assertEquals("NOT_REQUIRED", me.getString("verification"));
        assertTrue(api.one(jda, Long.toString(here), Long.toString(STRANGER)).isNull("me"), "nothing about anyone else");

        List<String> mine = names(api.mine(jda, Long.toString(PLAYER)));
        assertEquals(2, mine.size(), mine.toString());
        assertTrue(mine.containsAll(List.of("Here", "There")));
        assertEquals(List.of(), names(api.mine(jda, Long.toString(STRANGER))));
        assertEquals(List.of(), names(api.mine(jda, "not-an-id")));
    }

    @Test
    void theManageLinkIsOnlyForSomeoneWhoMayUseTheEventManager() {
        long id = event(HOST, "Managed", EventRules.Scope.PUBLIC, EventRules.Status.SIGNUPS);
        assertTrue(api.one(jda, Long.toString(id), Long.toString(PLAYER)).isNull("manage"));

        Mockito.when(access.tierOf(Mockito.any(), Mockito.any())).thenReturn(DashboardAccess.Tier.ADMIN);
        DataObject manage = api.one(jda, Long.toString(id), Long.toString(PLAYER)).getObject("manage");
        assertEquals(Long.toString(HOST), manage.getString("guildId"));
        assertEquals(Long.toString(id), manage.getString("eventId"));
        assertTrue(api.one(jda, Long.toString(id), Long.toString(STRANGER)).isNull("manage"));
    }
}
