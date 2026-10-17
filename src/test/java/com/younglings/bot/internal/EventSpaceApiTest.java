package com.younglings.bot.internal;

import com.younglings.bot.events.BossNightTemplate;
import com.younglings.bot.events.CompetitionTemplate;
import com.younglings.bot.events.CustomTemplate;
import com.younglings.bot.events.EventChanges;
import com.younglings.bot.events.EventKinds;
import com.younglings.bot.events.EventRepository;
import com.younglings.bot.events.EventRules.Scope;
import com.younglings.bot.events.EventRules.ScoringMode;
import com.younglings.bot.events.EventService;
import com.younglings.bot.events.EventService.EventInput;
import com.younglings.bot.events.EventSpaceOps;
import com.younglings.bot.events.EventSpaceRepository;
import com.younglings.bot.events.EventSpaces;
import com.younglings.bot.events.EventTemplates;
import com.younglings.bot.events.TeamRules;
import com.younglings.bot.internal.TicketAdminApi.ApiError;
import com.younglings.bot.permission.PermissionGroup;
import com.younglings.bot.permission.PermissionGroupService;
import com.younglings.bot.points.PointsRepository;
import com.younglings.bot.points.PointsService;
import com.younglings.bot.points.RewardsRepository;
import com.younglings.bot.testsupport.LocalDatabase;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.SelfMember;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The roles-and-channels routes end to end: a request goes to {@link EventSpaceApi} as the dashboard sends it and the answer is read, over a real
 * {@link EventService} on the developer's local Postgres and a made-up Discord server. Skipped where there is no local database. Everything is made in a
 * made-up server and removed afterwards.
 */
class EventSpaceApiTest {
    private static final long G = 999_000_000_000_000_021L;
    private static final long ACTOR = 9L;

    private static ConnectionSupplier supplier;
    private EventRepository repository;
    private EventService service;
    private EventSpaceApi api;
    private Guild guild;
    private Member actor;

    @BeforeAll
    static void connect() throws Exception {
        supplier = LocalDatabase.supplier();
    }

    @BeforeEach
    void setUp() {
        repository = new EventRepository(supplier);
        var templates = new EventTemplates(List.of(new CustomTemplate(), new CompetitionTemplate(), new BossNightTemplate()));
        EventKinds kinds = new EventKinds(null, null) {
            @Override
            public TeamRules.Kind kindOf(long hostGuildId, long userGuildId, long userId) {
                return TeamRules.Kind.HOME_CLAN;
            }
        };
        service = new EventService(repository, templates, kinds, new PointsService(new PointsRepository(supplier), new RewardsRepository(supplier)), new EventChanges());

        guild = Mockito.mock(Guild.class);
        Mockito.when(guild.getIdLong()).thenReturn(G);
        Role everyone = Mockito.mock(Role.class);
        Role adminRole = Mockito.mock(Role.class);
        Mockito.when(adminRole.getIdLong()).thenReturn(501L);
        Mockito.when(adminRole.getId()).thenReturn("501");
        Mockito.when(adminRole.getName()).thenReturn("Admin");
        Mockito.when(adminRole.getPosition()).thenReturn(10);
        // a senior role with the Administrator permission, which sees everything anyway
        Role ownerRole = Mockito.mock(Role.class);
        Mockito.when(ownerRole.getIdLong()).thenReturn(502L);
        Mockito.when(ownerRole.getId()).thenReturn("502");
        Mockito.when(ownerRole.getName()).thenReturn("Owner");
        Mockito.when(ownerRole.getPosition()).thenReturn(20);
        Mockito.when(ownerRole.hasPermission(net.dv8tion.jda.api.Permission.ADMINISTRATOR)).thenReturn(true);
        Mockito.when(guild.getRoles()).thenReturn(List.of(ownerRole, adminRole, everyone));
        Mockito.when(guild.getRoleById(501L)).thenReturn(adminRole);
        Mockito.when(guild.getChannels()).thenReturn(List.of());
        // the bot holds every permission
        SelfMember self = Mockito.mock(SelfMember.class, invocation -> invocation.getMethod().getName().equals("hasPermission") ? Boolean.TRUE : Mockito.RETURNS_DEFAULTS.answer(invocation));
        Mockito.when(guild.getSelfMember()).thenReturn(self);
        Member member = Mockito.mock(Member.class);
        User user = Mockito.mock(User.class);
        Mockito.when(member.getUser()).thenReturn(user);
        Mockito.when(member.getEffectiveName()).thenReturn("Tester");
        Mockito.when(guild.getMemberById(ArgumentMatchers.anyLong())).thenReturn(member);
        actor = Mockito.mock(Member.class);
        Mockito.when(actor.getIdLong()).thenReturn(ACTOR);
        Mockito.when(actor.getId()).thenReturn(Long.toString(ACTOR));

        PermissionGroupService groups = Mockito.mock(PermissionGroupService.class);
        Mockito.when(groups.group(ArgumentMatchers.eq(G), ArgumentMatchers.eq(PermissionGroup.ADMIN)))
                .thenReturn(Optional.of(new PermissionGroup(1, G, PermissionGroup.ADMIN, "Admin", true, true, List.of(501L))));
        EventSpaceRepository spaceRepository = new EventSpaceRepository(supplier, repository);
        EventSpaces spaces = new EventSpaces(repository, service, spaceRepository, groups);
        api = new EventSpaceApi(service, repository, spaces, new EventSpaceOps(repository, service, spaceRepository, spaces, new EventChanges()));
    }

    @AfterEach
    void cleanUp() throws Exception {
        try (Connection c = supplier.getConnection(); PreparedStatement s = c.prepareStatement("DELETE FROM younglings.event WHERE guild_id = ?")) {
            s.setLong(1, G);
            s.executeUpdate();
        }
    }

    // ---------- helpers ----------

    private DataObject call(String method, String path, String json) {
        return api.route(guild, actor, method, path.split("/"), () -> json == null ? DataObject.empty() : DataObject.fromJson(json));
    }

    private ApiError failure(String method, String path, String json) {
        return assertThrows(ApiError.class, () -> call(method, path, json));
    }

    private long event(String template, ScoringMode mode, String config) {
        return service.createEvent(G, ACTOR, new EventInput("Boss Night Friday", "", template, Scope.PRIVATE, mode, null, null, 0, config == null ? null : DataObject.fromJson(config)));
    }

    private void join(long eventId, long userId, Long teamId) {
        service.join(G, eventId, G, userId, teamId, true, null);
    }

    private void open(long eventId) {
        service.moveStage(G, eventId, com.younglings.bot.events.EventRules.Status.SIGNUPS);
    }

    private void closeAndConfirm(long eventId) {
        service.closeSignups(G, eventId, ACTOR);
        service.confirmRoster(G, eventId, ACTOR);
    }

    private static List<String> names(DataArray items, String key) {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < items.length(); i++) names.add(items.getObject(i).getString(key));
        return names;
    }

    // ---------- the routes ----------

    @Test
    void aRouteThatIsntASpaceRouteIsLeftAlone() {
        assertNull(api.route(guild, actor, "GET", new String[]{"tickets"}, DataObject::empty));
        assertNull(api.route(guild, actor, "GET", new String[]{"events", "5", "standings"}, DataObject::empty));
        assertNull(api.route(guild, actor, "GET", new String[]{"events", "5", "space", "nonsense"}, DataObject::empty));
    }

    @Test
    void anEventWithoutChannelsSaysTheyAreSwitchedOffAndStillShowsWhatTheDefaultsWouldMake() {
        long id = event(CompetitionTemplate.KEY, ScoringMode.INDIVIDUAL, null);
        DataObject view = call("GET", "events/" + id + "/space", null);
        assertFalse(view.getObject("settings").getBoolean("enabled"));
        assertFalse(view.getObject("state").getBoolean("applied"));
        assertFalse(view.getBoolean("ready"));
        assertEquals(List.of("Roles and channels are switched off for this event.", "Open signups, take them, then close them."), strings(view.getArray("blockers")));
        assertEquals(List.of("Boss Night Friday Participant", "Boss Night Friday Organiser"), names(view.getArray("roles"), "name"));
    }

    private static List<String> strings(DataArray array) {
        List<String> list = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) list.add(array.getString(i));
        return list;
    }

    @Test
    void savingTheSettingsChangesTheNamesInThePlanAndABadOneIsRefusedWithReasons() {
        long id = event(CompetitionTemplate.KEY, ScoringMode.INDIVIDUAL, null);
        DataObject view = call("PUT", "events/" + id + "/space/settings", """
                {"enabled": true, "slots": {"staff": {"use": "none"}}, "names": {"general": "{event} Chat", "participantRole": "Playing {event}"}}
                """);
        assertTrue(view.getObject("settings").getBoolean("enabled"));
        assertTrue(names(view.getArray("channels"), "name").contains("boss-night-friday-chat"), names(view.getArray("channels"), "name").toString());
        assertFalse(names(view.getArray("channels"), "key").contains("staff"), "the staff channel was left out");
        assertEquals("Playing Boss Night Friday", view.getArray("roles").getObject(0).getString("name"));

        ApiError bad = failure("PUT", "events/" + id + "/space/settings", "{\"enabled\": true, \"names\": {\"general\": \"{evnt} Chat\"}}");
        assertEquals(400, bad.status);
        assertTrue(bad.problems.stream().anyMatch(p -> p.contains("{evnt}")), bad.problems.toString());
        assertTrue(call("GET", "events/" + id + "/space", null).getObject("settings").getBoolean("enabled"), "a refused save changes nothing");
    }

    @Test
    void aPreviewShowsTheChoicesWithoutSavingThemAndRefusesBadOnes() {
        long id = event(CompetitionTemplate.KEY, ScoringMode.INDIVIDUAL, null);
        DataObject view = call("POST", "events/" + id + "/space/preview", "{\"enabled\": true, \"names\": {\"general\": \"{event} Talk\"}}");
        assertTrue(names(view.getArray("channels"), "name").contains("boss-night-friday-talk"), names(view.getArray("channels"), "name").toString());
        assertFalse(call("GET", "events/" + id + "/space", null).getObject("settings").getBoolean("enabled"), "a preview saves nothing");

        ApiError bad = failure("POST", "events/" + id + "/space/preview", "{\"names\": {\"general\": \"{nope} Talk\"}}");
        assertEquals(400, bad.status);
        assertTrue(bad.problems.stream().anyMatch(p -> p.contains("{nope}")), bad.problems.toString());
        assertEquals(405, failure("GET", "events/" + id + "/space/preview", null).status);
    }

    @Test
    void theViewSaysWhichChoicesWereMadeAndWhichAreJustDefaults() {
        long id = event(CompetitionTemplate.KEY, ScoringMode.INDIVIDUAL, "{\"channels\": {\"enabled\": true, \"slots\": {\"staff\": {\"use\": \"none\"}}}}");
        DataArray slots = call("GET", "events/" + id + "/space", null).getObject("settings").getArray("slots");
        assertTrue(slots.getObject(0).getBoolean("explicit"), "the staff channel was chosen");
        assertEquals("none", slots.getObject(0).getString("use"));
        assertFalse(slots.getObject(1).getBoolean("explicit"), "announcements are just the default");
        assertEquals("create", slots.getObject(1).getString("use"));
    }

    @Test
    void oncePlayersAreInAndTheRosterIsConfirmedThePlanIsReadyAndCountsEveryone() {
        long id = event(CompetitionTemplate.KEY, ScoringMode.INDIVIDUAL, "{\"channels\": {\"enabled\": true}}");
        open(id);
        for (long user = 1; user <= 4; user++) join(id, 700 + user, null);
        closeAndConfirm(id);
        DataObject view = call("GET", "events/" + id + "/space", null);
        assertTrue(view.getBoolean("ready"), strings(view.getArray("blockers")).toString());
        assertEquals(4, view.getObject("summary").getInt("players"));
        assertEquals(2, view.getObject("summary").getInt("newRoles"));
        assertEquals(4, view.getObject("summary").getInt("newChannels"), "the category, staff, announcements, general");
        DataObject participants = view.getArray("roles").getObject(0);
        assertEquals("participant", participants.getString("key"));
        assertEquals(4, participants.getInt("members"));
        assertEquals("create", participants.getString("action"));
        assertEquals(1, view.getArray("staffRoles").length(), "the role with the Administrator permission needs no mention");
        assertEquals("501", view.getArray("staffRoles").getObject(0).getString("id"));
        DataObject general = null;
        for (int i = 0; i < view.getArray("channels").length(); i++) {
            if (view.getArray("channels").getObject(i).getString("key").equals("general")) general = view.getArray("channels").getObject(i);
        }
        assertEquals("participants", general.getArray("access").getObject(1).getString("who"));
        assertEquals("chat", general.getArray("access").getObject(1).getString("level"));
    }

    @Test
    void aTeamEventNeedsItsTeamsFinalAndGetsARoleAndAChannelForEach() {
        long id = event(BossNightTemplate.KEY, ScoringMode.TEAM, "{\"channels\": {\"enabled\": true}}");
        open(id);
        long red = service.addTeam(G, id, "Red", null);
        long blue = service.addTeam(G, id, "Blue", null);
        join(id, 801, red);
        join(id, 802, blue);
        closeAndConfirm(id);
        assertTrue(strings(call("GET", "events/" + id + "/space", null).getArray("blockers")).contains("Finalise the teams first."));
        service.finaliseTeams(G, id, ACTOR);
        DataObject view = call("GET", "events/" + id + "/space", null);
        assertTrue(view.getBoolean("ready"), strings(view.getArray("blockers")).toString());
        assertTrue(view.getBoolean("teamEvent"));
        assertEquals(List.of("Boss Night Friday Participant", "Boss Night Friday Organiser", "Boss Night Friday Red", "Boss Night Friday Blue"), names(view.getArray("roles"), "name"));
        assertTrue(names(view.getArray("channels"), "name").containsAll(List.of("boss-night-friday-red", "boss-night-friday-blue")), names(view.getArray("channels"), "name").toString());
    }

    @Test
    void theChecklistGainsAStepWhenChannelsAreSwitchedOnAndAGeneralSettingsSaveDoesntLoseThem() {
        long id = event(CompetitionTemplate.KEY, ScoringMode.INDIVIDUAL, "{\"channels\": {\"enabled\": true, \"names\": {\"general\": \"{event} Chat\"}}, \"setup\": {\"mode\": \"STANDARD\"}}");
        assertTrue(service.prepOf(service.event(G, id)).steps().stream().anyMatch(s -> s.key().equals("space")));

        // saving the event's other settings (as the dashboard's settings form does) leaves the channel choices alone
        service.updateEvent(G, id, new EventInput("Boss Night Friday", "", CompetitionTemplate.KEY, Scope.PRIVATE, ScoringMode.INDIVIDUAL, null, null, 0, DataObject.fromJson("{\"setup\": {\"mode\": \"STANDARD\"}}")));
        DataObject view = call("GET", "events/" + id + "/space", null);
        assertTrue(view.getObject("settings").getBoolean("enabled"));
        assertTrue(names(view.getArray("channels"), "name").contains("boss-night-friday-chat"));

        long without = event(CompetitionTemplate.KEY, ScoringMode.INDIVIDUAL, "{\"setup\": {\"mode\": \"STANDARD\"}}");
        assertTrue(service.prepOf(service.event(G, without)).steps().stream().noneMatch(s -> s.key().equals("space")));
    }

    @Test
    void anotherServersEventIsNotFound() {
        long theirs = service.createEvent(999_000_000_000_000_022L, 9L, new EventInput("Theirs", "", "custom", Scope.PRIVATE, ScoringMode.INDIVIDUAL, null, null, 0, null));
        try {
            assertEquals(404, failure("GET", "events/" + theirs + "/space", null).status);
            assertEquals(404, failure("PUT", "events/" + theirs + "/space/settings", "{\"enabled\": true}").status);
        } finally {
            service.deleteEvent(999_000_000_000_000_022L, theirs);
        }
        assertEquals(405, failure("POST", "events/" + event("custom", ScoringMode.INDIVIDUAL, null) + "/space", "{}").status);
    }
}
