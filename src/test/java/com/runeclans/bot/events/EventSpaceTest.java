package com.runeclans.bot.events;

import com.runeclans.bot.events.EventRules.Status;
import com.runeclans.bot.events.EventSpace.Action;
import com.runeclans.bot.events.EventSpace.ChannelItem;
import com.runeclans.bot.events.EventSpace.Choice;
import com.runeclans.bot.events.EventSpace.Facts;
import com.runeclans.bot.events.EventSpace.HeldChannel;
import com.runeclans.bot.events.EventSpace.HeldRole;
import com.runeclans.bot.events.EventSpace.Kind;
import com.runeclans.bot.events.EventSpace.Level;
import com.runeclans.bot.events.EventSpace.Overwrite;
import com.runeclans.bot.events.EventSpace.Plan;
import com.runeclans.bot.events.EventSpace.PlayerRef;
import com.runeclans.bot.events.EventSpace.Principal;
import com.runeclans.bot.events.EventSpace.Readiness;
import com.runeclans.bot.events.EventSpace.Room;
import com.runeclans.bot.events.EventSpace.RoleItem;
import com.runeclans.bot.events.EventSpace.Setting;
import com.runeclans.bot.events.EventSpace.TeamRef;
import com.runeclans.bot.events.EventSpace.Who;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventSpaceTest {
    private static final Readiness READY = new Readiness(Status.SIGNUPS, false, true, true, false);
    private static final Readiness READY_TEAMS = new Readiness(Status.SIGNUPS, true, true, true, true);
    private static final Setting ON = new Setting(true, true, false, Map.of(), Map.of());

    private static Facts solo(String template, Setting setting, List<PlayerRef> players) {
        return new Facts("Christmas Bingo", template, false, setting, null, null, List.of(), players, Set.of(), 0, Map.of(), Map.of(), Map.of(), Room.UNLIMITED, READY);
    }

    private static List<PlayerRef> players(int n, Long teamId) {
        List<PlayerRef> list = new ArrayList<>();
        for (int i = 1; i <= n; i++) list.add(new PlayerRef(1000 + i, "Player " + i, teamId));
        return list;
    }

    private static List<String> names(Plan plan, Action action) {
        return plan.channels().stream().filter(c -> c.action() == action).map(ChannelItem::name).toList();
    }

    // ---------- settings ----------

    @Test
    void anEventWithNoChannelSettingsHasThemSwitchedOff() {
        assertFalse(EventSpace.parse(DataObject.fromJson("{}")).enabled());
        assertFalse(EventSpace.parse(null).enabled());
        assertTrue(EventSpace.problems(DataObject.fromJson("{}")).isEmpty());
    }

    @Test
    void theSettingsAreReadWithTheirChoicesAndPatterns() {
        Setting s = EventSpace.parse(DataObject.fromJson("""
                {"channels": {"enabled": true, "teamChannels": false, "playerChannels": true,
                  "slots": {"staff": {"use": "none"}, "general": {"use": "existing", "channelId": "123456789012345678"}, "board": {"use": "create"}},
                  "names": {"general": "{event} Chat", "teamRole": "{event} - {team}", "category": "  "}}}
                """));
        assertTrue(s.enabled());
        assertFalse(s.teamChannels());
        assertTrue(s.playerChannels());
        assertEquals(Choice.NONE, s.slots().get(Kind.STAFF));
        assertEquals(Choice.existing(123456789012345678L), s.slots().get(Kind.GENERAL));
        assertEquals("{event} Chat", s.pattern(Kind.GENERAL));
        assertEquals("{event} - {team}", s.pattern(EventSpace.RoleName.TEAM));
        assertEquals("{event}", s.pattern(Kind.CATEGORY), "a blank pattern is the default");
        assertEquals("{event} Announcements", s.pattern(Kind.ANNOUNCEMENTS));
    }

    @Test
    void settingsThatCantBeUnderstoodAreReportedAndReadAsOff() {
        DataObject bad = DataObject.fromJson("""
                {"channels": {"enabled": "yes", "slots": {"staff": {"use": "maybe"}, "general": {"use": "existing"}, "lobby": {"use": "create"}},
                  "names": {"general": "{evnt} chat", "voice": "x"}}}
                """);
        List<String> problems = EventSpace.problems(bad);
        assertTrue(problems.stream().anyMatch(p -> p.contains("on or off")), problems.toString());
        assertTrue(problems.stream().anyMatch(p -> p.contains("staff channel") && p.contains("leave it out")), problems.toString());
        assertTrue(problems.stream().anyMatch(p -> p.contains("Choose which channel to use for the general chat")), problems.toString());
        assertTrue(problems.stream().anyMatch(p -> p.contains("\"lobby\"")), problems.toString());
        assertTrue(problems.stream().anyMatch(p -> p.contains("{evnt}")), problems.toString());
        assertTrue(problems.stream().anyMatch(p -> p.contains("\"voice\"")), problems.toString());
        assertFalse(EventSpace.parse(bad).enabled());
        assertFalse(EventSpace.problems(DataObject.fromJson("{\"channels\": \"on\"}")).isEmpty());
    }

    @Test
    void anOwnOnlyBingoGetsNoSharedBoardChannelWhileItsBoardsHaveChannelsOfTheirOwn() {
        Setting teamChannels = new Setting(true, true, false, Map.of(), Map.of());
        Setting noChannels = new Setting(true, false, false, Map.of(), Map.of());
        assertEquals(Choice.NONE, EventSpace.choice(teamChannels, Kind.BOARD, BingoTemplate.KEY, null, null, false));
        assertEquals(Choice.CREATE, EventSpace.choice(teamChannels, Kind.BOARD, BingoTemplate.KEY, null, null, true), "a shared or both display needs it");
        assertEquals(Choice.CREATE, EventSpace.choice(noChannels, Kind.BOARD, BingoTemplate.KEY, null, null, false), "the fallback stays when boards have no channels of their own");
        assertEquals(Choice.existing(55), EventSpace.choice(teamChannels, Kind.BOARD, BingoTemplate.KEY, 55L, null, false), "a channel the event names is still used");
        Setting chose = new Setting(true, true, false, Map.of(Kind.BOARD, Choice.CREATE), Map.of());
        assertEquals(Choice.CREATE, EventSpace.choice(chose, Kind.BOARD, BingoTemplate.KEY, null, null, false), "what the organiser chose wins");
    }

    @Test
    void theBoardAndProofChannelsTheEventAlreadyNamesAreUsedUnlessTheOrganiserChoseOtherwise() {
        assertEquals(Choice.existing(55), EventSpace.choice(ON, Kind.BOARD, BingoTemplate.KEY, 55L, null));
        assertEquals(Choice.existing(66), EventSpace.choice(ON, Kind.REVIEW, BingoTemplate.KEY, null, 66L));
        assertEquals(Choice.CREATE, EventSpace.choice(ON, Kind.BOARD, BingoTemplate.KEY, null, null));
        Setting chose = new Setting(true, true, false, Map.of(Kind.BOARD, Choice.NONE), Map.of());
        assertEquals(Choice.NONE, EventSpace.choice(chose, Kind.BOARD, BingoTemplate.KEY, 55L, null));
        assertNull(EventSpace.choice(ON, Kind.BOARD, CompetitionTemplate.KEY, null, null), "only a bingo has a board");
        assertNull(EventSpace.choice(ON, Kind.REVIEW, CompetitionTemplate.KEY, null, null), "a competition has no proof to review");
    }

    // ---------- who can see what ----------

    private static Map<Long, Overwrite> overwritesOf(Kind kind, long ref) {
        Map<Who, List<Long>> ids = new EnumMap<>(Who.class);
        ids.put(Who.EVERYONE, List.of(1L));
        ids.put(Who.STAFF, List.of(2L, 3L));
        ids.put(Who.ORGANISERS, List.of(4L));
        ids.put(Who.PARTICIPANTS, List.of(5L));
        ids.put(Who.TEAM, List.of(6L));
        ids.put(Who.PLAYER, List.of(7L));
        ids.put(Who.BOT, List.of(8L));
        Map<Long, Overwrite> byId = new HashMap<>();
        EventSpace.overwrites(EventSpace.access(kind, ref), p -> ids.getOrDefault(p.who(), List.of())).forEach(o -> byId.put(o.id(), o));
        return byId;
    }

    private static boolean allows(Overwrite o, Permission p) {
        return (o.allow() & p.getRawValue()) != 0;
    }

    private static boolean denies(Overwrite o, Permission p) {
        return (o.deny() & p.getRawValue()) != 0;
    }

    @Test
    void everyChannelIsHiddenFromEveryoneAndTheBotAndStaffCanAlwaysManageIt() {
        for (Kind kind : Kind.values()) {
            Map<Long, Overwrite> o = overwritesOf(kind, 9);
            assertTrue(denies(o.get(1L), Permission.VIEW_CHANNEL), kind + " is hidden from everyone");
            assertTrue(o.get(1L).isRole());
            assertTrue(allows(o.get(2L), Permission.VIEW_CHANNEL) && allows(o.get(3L), Permission.MESSAGE_MANAGE), kind + " is open to every staff role");
            assertTrue(allows(o.get(4L), Permission.VIEW_CHANNEL), kind + " is open to organisers");
            assertTrue(allows(o.get(8L), Permission.VIEW_CHANNEL) && allows(o.get(8L), Permission.MESSAGE_SEND) && !o.get(8L).isRole(), kind + " is open to the bot, which is a member");
        }
    }

    @Test
    void playersReadTheAnnouncementsAndTheBoardButCantWriteThere() {
        for (Kind kind : List.of(Kind.ANNOUNCEMENTS, Kind.BOARD)) {
            Overwrite players = overwritesOf(kind, 0).get(5L);
            assertTrue(allows(players, Permission.VIEW_CHANNEL) && allows(players, Permission.MESSAGE_HISTORY), kind.toString());
            assertTrue(denies(players, Permission.MESSAGE_SEND), kind.toString());
            assertFalse(allows(players, Permission.MESSAGE_SEND), kind.toString());
        }
    }

    @Test
    void playersChatInGeneralButSeeNothingOfTheStaffOrProofChannels() {
        Overwrite general = overwritesOf(Kind.GENERAL, 0).get(5L);
        assertTrue(allows(general, Permission.MESSAGE_SEND) && allows(general, Permission.MESSAGE_ATTACH_FILES));
        assertFalse(overwritesOf(Kind.STAFF, 0).containsKey(5L));
        assertFalse(overwritesOf(Kind.REVIEW, 0).containsKey(5L));
    }

    @Test
    void aTeamsChannelIsOpenOnlyToThatTeamAndAPlayersOnlyToThemAsAMember() {
        Map<Long, Overwrite> team = overwritesOf(Kind.TEAM, 9);
        assertTrue(allows(team.get(6L), Permission.MESSAGE_SEND));
        assertTrue(team.get(6L).isRole());
        assertFalse(team.containsKey(5L), "the other players are not let in");
        assertFalse(team.containsKey(7L));
        Map<Long, Overwrite> player = overwritesOf(Kind.PLAYER, 9);
        assertTrue(allows(player.get(7L), Permission.MESSAGE_SEND));
        assertFalse(player.get(7L).isRole(), "a player is let in as a member, not through a role");
        assertFalse(player.containsKey(6L));
    }

    @Test
    void nothingIsBothAllowedAndDeniedAndAPrincipalWithNoIdIsSkipped() {
        for (Kind kind : Kind.values()) {
            overwritesOf(kind, 9).values().forEach(o -> assertEquals(0, o.allow() & o.deny(), kind + " " + o));
        }
        List<Overwrite> none = EventSpace.overwrites(EventSpace.access(Kind.GENERAL, 0), p -> p.who() == Who.EVERYONE ? List.of(1L) : List.of());
        assertEquals(1, none.size());
    }

    @Test
    void twoGroupsWithTheSameIdAreMergedAndDenyingWins() {
        List<Overwrite> merged = EventSpace.overwrites(List.of(new EventSpace.Grant(Principal.of(Who.PARTICIPANTS), Level.READ), new EventSpace.Grant(Principal.of(Who.ORGANISERS), Level.CHAT)),
                p -> List.of(5L));
        assertEquals(1, merged.size());
        assertTrue(denies(merged.get(0), Permission.MESSAGE_SEND));
        assertFalse(allows(merged.get(0), Permission.MESSAGE_SEND));
        assertTrue(allows(merged.get(0), Permission.VIEW_CHANNEL));
    }

    @Test
    void theBotNeedsEveryPermissionItHandsOutAndTheRightToManageRolesAndChannels() {
        assertTrue(EventSpace.BOT_NEEDS.containsAll(List.of(Permission.MANAGE_ROLES, Permission.MANAGE_CHANNEL, Permission.MESSAGE_MANAGE, Permission.MESSAGE_MENTION_EVERYONE)));
        for (Level level : Level.values()) {
            assertTrue(EventSpace.BOT_NEEDS.containsAll(EventSpace.allowed(level)), level + " hands out something the bot may not hold");
        }
    }

    // ---------- when it can be set up ----------

    @Test
    void theSpaceWaitsForSignupsToCloseTheRosterToBeConfirmedAndTheTeamsToBeFinal() {
        assertEquals(List.of("Open signups, take them, then close them."), EventSpace.notReady(new Readiness(Status.DRAFT, false, false, false, false)));
        assertEquals(List.of("Close signups first."), EventSpace.notReady(new Readiness(Status.SIGNUPS, false, false, false, false)));
        assertEquals(List.of("Confirm the roster first."), EventSpace.notReady(new Readiness(Status.SIGNUPS, false, true, false, false)));
        assertEquals(List.of("Finalise the teams first."), EventSpace.notReady(new Readiness(Status.SIGNUPS, true, true, true, false)));
        assertEquals(List.of("Close signups first.", "Finalise the teams first."), EventSpace.notReady(new Readiness(Status.SIGNUPS, true, false, false, false)));
        assertTrue(EventSpace.notReady(READY).isEmpty());
        assertTrue(EventSpace.notReady(READY_TEAMS).isEmpty());
        assertTrue(EventSpace.notReady(new Readiness(Status.RUNNING, true, false, false, false)).isEmpty(), "an event that has started has been through all that");
        assertEquals(List.of("This event is over."), EventSpace.notReady(new Readiness(Status.DONE, false, true, true, false)));
        assertEquals(List.of("This event is over."), EventSpace.notReady(new Readiness(Status.CANCELLED, false, true, true, false)));
    }

    // ---------- the plan ----------

    @Test
    void aBingoGetsACategoryAndTheStaffAnnouncementsGeneralBoardAndProofChannels() {
        Plan plan = EventSpace.plan(solo(BingoTemplate.KEY, ON, players(3, null)));
        assertTrue(plan.blockers().isEmpty(), plan.blockers().toString());
        assertEquals(List.of("Christmas Bingo"), plan.channels().stream().filter(c -> c.kind() == Kind.CATEGORY).map(ChannelItem::name).toList());
        assertEquals(List.of("christmas-bingo-staff", "christmas-bingo-announcements", "christmas-bingo-general", "christmas-bingo-live-board", "christmas-bingo-staff-review"),
                plan.channels().stream().filter(c -> c.kind() != Kind.CATEGORY).map(ChannelItem::name).toList());
        assertTrue(plan.channels().stream().filter(c -> c.kind() != Kind.CATEGORY).allMatch(c -> c.action() == Action.CREATE && "category".equals(c.parent())));
        assertEquals(List.of("Christmas Bingo Participant", "Christmas Bingo Organiser"), plan.roles().stream().map(RoleItem::name).toList());
        assertEquals(3, plan.roles().get(0).members());
        assertEquals(2, plan.newRoles());
        assertEquals(6, plan.newChannels());
        assertFalse(plan.complete());
    }

    @Test
    void aCompetitionHasNoBoardOrProofChannel() {
        Plan plan = EventSpace.plan(solo(CompetitionTemplate.KEY, ON, players(2, null)));
        assertEquals(List.of("christmas-bingo-staff", "christmas-bingo-announcements", "christmas-bingo-general"),
                plan.channels().stream().filter(c -> c.kind() != Kind.CATEGORY).map(ChannelItem::name).toList());
    }

    @Test
    void aChannelLeftOutIsNotMadeAndAnExistingOneIsUsedAsItIs() {
        Setting setting = new Setting(true, true, false, Map.of(Kind.STAFF, Choice.NONE, Kind.GENERAL, Choice.existing(777)), Map.of());
        Plan plan = EventSpace.plan(solo(BingoTemplate.KEY, setting, players(2, null)));
        assertEquals(List.of("christmas-bingo-announcements", "christmas-bingo-live-board", "christmas-bingo-staff-review"), names(plan, Action.CREATE).stream().filter(n -> !n.equals("Christmas Bingo")).toList());
        ChannelItem general = plan.channels().stream().filter(c -> c.kind() == Kind.GENERAL).findFirst().orElseThrow();
        assertEquals(Action.USE_EXISTING, general.action());
        assertEquals(777L, general.id());
        assertTrue(general.access().isEmpty(), "who sees a channel an organiser chose is theirs to decide");
        assertTrue(plan.channels().stream().noneMatch(c -> c.kind() == Kind.STAFF));
    }

    @Test
    void ifNothingIsMadeThereIsNoCategory() {
        Map<Kind, Choice> all = new EnumMap<>(Kind.class);
        all.put(Kind.STAFF, Choice.existing(1));
        all.put(Kind.ANNOUNCEMENTS, Choice.existing(2));
        all.put(Kind.GENERAL, Choice.existing(3));
        Plan plan = EventSpace.plan(solo(CompetitionTemplate.KEY, new Setting(true, true, false, all, Map.of()), players(2, null)));
        assertTrue(plan.channels().stream().noneMatch(c -> c.kind() == Kind.CATEGORY));
        assertEquals(3, plan.channels().stream().filter(c -> c.action() == Action.USE_EXISTING).count());
    }

    @Test
    void anExistingChannelThatCantBeUsedOrIsChosenTwiceStopsTheSetup() {
        Setting setting = new Setting(true, true, false, Map.of(Kind.STAFF, Choice.existing(9), Kind.GENERAL, Choice.existing(9), Kind.ANNOUNCEMENTS, Choice.existing(10)), Map.of());
        Facts facts = new Facts("X Event", CompetitionTemplate.KEY, false, setting, null, null, List.of(), players(1, null), Set.of(), 0, Map.of(), Map.of(),
                Map.of(Kind.ANNOUNCEMENTS, "JonnyBot can't send messages in #announcements."), Room.UNLIMITED, READY);
        Plan plan = EventSpace.plan(facts);
        assertTrue(plan.blockers().contains("JonnyBot can't send messages in #announcements."), plan.blockers().toString());
        assertTrue(plan.blockers().stream().anyMatch(b -> b.contains("same channel is chosen for two things") && b.contains("general chat")), plan.blockers().toString());
    }

    @Test
    void theNamePatternsAreUsedAndAClashBetweenTwoNamesIsResolved() {
        Setting setting = new Setting(true, true, false, Map.of(), Map.of("general", "{event} General Channel", "staff", "{event} General Channel", "participantRole", "Playing in {event}"));
        Plan plan = EventSpace.plan(solo(CompetitionTemplate.KEY, setting, players(1, null)));
        assertEquals(List.of("christmas-bingo-general-channel", "christmas-bingo-announcements", "christmas-bingo-general-channel-2"),
                plan.channels().stream().filter(c -> c.kind() != Kind.CATEGORY).map(ChannelItem::name).toList());
        assertEquals("Playing in Christmas Bingo", plan.roles().get(0).name());
    }

    @Test
    void aTeamEventGetsARoleAndAPrivateChannelForEachTeam() {
        List<TeamRef> teams = List.of(new TeamRef(11, "Red Rangers"), new TeamRef(12, "Blue Bandits"));
        List<PlayerRef> players = new ArrayList<>(players(2, 11L));
        players.add(new PlayerRef(2001, "Cy", 12L));
        players.add(new PlayerRef(2002, "Di", null));
        Facts facts = new Facts("Boss Night", BossNightTemplate.KEY, true, ON, null, null, teams, players, Set.of(5L), 1, Map.of(), Map.of(), Map.of(), Room.UNLIMITED, READY_TEAMS);
        Plan plan = EventSpace.plan(facts);
        assertEquals(List.of("Boss Night Participant", "Boss Night Organiser", "Boss Night Red Rangers", "Boss Night Blue Bandits"), plan.roles().stream().map(RoleItem::name).toList());
        assertEquals(List.of(4, 1, 2, 1), plan.roles().stream().map(RoleItem::members).toList());
        assertTrue(plan.channels().stream().anyMatch(c -> c.key().equals("team:11") && c.name().equals("boss-night-red-rangers") && c.kind() == Kind.TEAM));
        ChannelItem red = plan.channels().stream().filter(c -> c.key().equals("team:11")).findFirst().orElseThrow();
        assertTrue(red.access().contains(new EventSpace.Grant(new Principal(Who.TEAM, 11), Level.CHAT)));
        assertTrue(plan.warnings().stream().anyMatch(w -> w.contains("1 player on the roster isn't counted yet")), plan.warnings().toString());
    }

    @Test
    void withoutTeamChannelsATeamEventHasNoTeamRolesEither() {
        Facts facts = new Facts("Boss Night", BossNightTemplate.KEY, true, new Setting(true, false, false, Map.of(), Map.of()), null, null, List.of(new TeamRef(11, "Red")), players(2, 11L), Set.of(), 0,
                Map.of(), Map.of(), Map.of(), Room.UNLIMITED, READY_TEAMS);
        Plan plan = EventSpace.plan(facts);
        assertEquals(List.of("Boss Night Participant", "Boss Night Organiser"), plan.roles().stream().map(RoleItem::name).toList());
        assertTrue(plan.channels().stream().noneMatch(c -> c.kind() == Kind.TEAM));
    }

    @Test
    void aSoloEventCanGiveEveryPlayerTheirOwnChannel() {
        Plan plan = EventSpace.plan(solo(CompetitionTemplate.KEY, new Setting(true, true, true, Map.of(), Map.of()), players(3, null)));
        assertEquals(List.of("christmas-bingo-player-1", "christmas-bingo-player-2", "christmas-bingo-player-3"),
                plan.channels().stream().filter(c -> c.kind() == Kind.PLAYER).map(ChannelItem::name).toList());
        assertTrue(plan.channels().stream().filter(c -> c.key().equals("player:1002")).findFirst().orElseThrow().access().contains(new EventSpace.Grant(new Principal(Who.PLAYER, 1002), Level.CHAT)));
    }

    @Test
    void channelsThatDontFitInOneCategoryGoInAnotherAndTheEventIsToldSo() {
        Plan plan = EventSpace.plan(solo(CompetitionTemplate.KEY, new Setting(true, true, true, Map.of(), Map.of()), players(60, null)));
        List<ChannelItem> categories = plan.channels().stream().filter(c -> c.kind() == Kind.CATEGORY).toList();
        assertEquals(List.of("Christmas Bingo", "Christmas Bingo (2)"), categories.stream().map(ChannelItem::name).toList());
        assertEquals(List.of("category", "category:2"), categories.stream().map(ChannelItem::key).toList());
        Map<String, Long> perCategory = plan.channels().stream().filter(c -> c.kind() != Kind.CATEGORY).collect(Collectors.groupingBy(ChannelItem::parent, Collectors.counting()));
        assertEquals(50L, perCategory.get("category"));
        assertEquals(13L, perCategory.get("category:2"));
        assertTrue(plan.warnings().stream().anyMatch(w -> w.contains("don't fit in one category")), plan.warnings().toString());
    }

    @Test
    void settingUpAgainKeepsWhatIsThereAndMakesOnlyWhatIsMissing() {
        Map<String, HeldChannel> held = new HashMap<>();
        held.put("category", new HeldChannel(100, "Christmas Bingo", null));
        held.put("staff", new HeldChannel(101, "christmas-bingo-staff", "category"));
        held.put("announcements", new HeldChannel(102, "christmas-bingo-announcements", "category"));
        held.put("general", new HeldChannel(103, "christmas-bingo-general", "category"));
        Map<String, HeldRole> roles = Map.of("participant", new HeldRole(201, "Christmas Bingo Participant"), "organiser", new HeldRole(202, "Christmas Bingo Organiser"));
        Facts facts = new Facts("Christmas Bingo", CompetitionTemplate.KEY, false, ON, null, null, List.of(), players(2, null), Set.of(), 0, roles, held, Map.of(), Room.UNLIMITED, READY);
        Plan plan = EventSpace.plan(facts);
        assertTrue(plan.complete(), plan.missing().toString());
        assertEquals(0, plan.count(Action.REMOVE));
        assertEquals(4, plan.channels().stream().filter(c -> c.action() == Action.KEEP).count());
        assertEquals(2, plan.roles().stream().filter(r -> r.action() == Action.KEEP).count());

        // a team added afterwards, and a role someone deleted on Discord
        Map<String, HeldRole> lost = Map.of("participant", new HeldRole(201, "Christmas Bingo Participant"));
        Plan again = EventSpace.plan(new Facts("Christmas Bingo", BossNightTemplate.KEY, true, ON, null, null, List.of(new TeamRef(11, "Red")), players(2, 11L), Set.of(), 0, lost, held, Map.of(),
                Room.UNLIMITED, READY_TEAMS));
        assertEquals(List.of("the role Christmas Bingo Organiser", "the role Christmas Bingo Red", "the channel christmas-bingo-staff-review", "the channel christmas-bingo-red"),
                again.missing().subList(0, 4));
        // the channels that let the organisers in are put back so the organiser role made again gets in too
        ChannelItem staff = again.channels().stream().filter(c -> c.key().equals("staff")).findFirst().orElseThrow();
        assertEquals(Action.REPAIR, staff.action());
        assertTrue(staff.note().contains("role that is being made again"), staff.note());
        assertEquals("category", again.channels().stream().filter(c -> c.key().equals("team:11")).findFirst().orElseThrow().parent());
    }

    @Test
    void newChannelsFillWhatRoomIsLeftInTheCategoriesAlreadyMade() {
        Map<String, HeldChannel> held = new HashMap<>();
        held.put("category", new HeldChannel(100, "Christmas Bingo", null));
        for (int i = 0; i < 49; i++) held.put("player:" + i, new HeldChannel(300 + i, "christmas-bingo-p" + i, "category"));
        Facts facts = new Facts("Christmas Bingo", CompetitionTemplate.KEY, false, new Setting(true, true, true, Map.of(Kind.STAFF, Choice.NONE, Kind.ANNOUNCEMENTS, Choice.NONE, Kind.GENERAL, Choice.NONE), Map.of()),
                null, null, List.of(), List.of(new PlayerRef(0, "P0", null), new PlayerRef(500, "Five", null), new PlayerRef(501, "Six", null)), Set.of(), 0,
                Map.of(), held, Map.of(), Room.UNLIMITED, READY);
        Plan plan = EventSpace.plan(facts);
        assertEquals(Action.REPAIR, plan.channels().stream().filter(c -> c.key().equals("category")).findFirst().orElseThrow().action(), "kept (not made again), and put back so the roles being made get in");
        assertEquals("category", plan.channels().stream().filter(c -> c.key().equals("player:500")).findFirst().orElseThrow().parent());
        assertEquals("category:2", plan.channels().stream().filter(c -> c.key().equals("player:501")).findFirst().orElseThrow().parent());
        assertEquals(Action.CREATE, plan.channels().stream().filter(c -> c.key().equals("category:2")).findFirst().orElseThrow().action());
    }

    @Test
    void whatIsNoLongerWantedIsListedForRemovalButNeverRemovedByThePlan() {
        Map<String, HeldChannel> held = new HashMap<>();
        held.put("category", new HeldChannel(100, "Boss Night", null));
        held.put("team:11", new HeldChannel(111, "boss-night-red", "category"));
        held.put("team:12", new HeldChannel(112, "boss-night-blue", "category"));
        Map<String, HeldRole> roles = Map.of("participant", new HeldRole(201, "Boss Night Participant"), "organiser", new HeldRole(202, "Boss Night Organiser"),
                "team:11", new HeldRole(211, "Boss Night Red"), "team:12", new HeldRole(212, "Boss Night Blue"));
        Facts facts = new Facts("Boss Night", BossNightTemplate.KEY, true, new Setting(true, true, false, Map.of(Kind.STAFF, Choice.NONE, Kind.ANNOUNCEMENTS, Choice.NONE, Kind.GENERAL, Choice.NONE, Kind.REVIEW, Choice.NONE), Map.of()),
                null, null, List.of(new TeamRef(11, "Red")), players(2, 11L), Set.of(), 0, roles, held, Map.of(), Room.UNLIMITED, READY_TEAMS);
        Plan plan = EventSpace.plan(facts);
        assertEquals(List.of("boss-night-blue"), names(plan, Action.REMOVE));
        assertEquals(List.of("Boss Night Blue"), plan.roles().stream().filter(r -> r.action() == Action.REMOVE).map(RoleItem::name).toList());
        assertTrue(plan.complete());
    }

    // ---------- putting back what was changed on Discord (T-210) ----------

    private static final EventSpace.Ids IDS = new EventSpace.Ids(1, 77, List.of(501L));

    /** A competition (no board) with only the general channel: easy to reason about. */
    private static Facts generalOnly(Map<String, HeldRole> roles, Map<String, HeldChannel> held, EventSpace.Ids ids) {
        Setting setting = new Setting(true, true, false, Map.of(Kind.STAFF, Choice.NONE, Kind.ANNOUNCEMENTS, Choice.NONE, Kind.REVIEW, Choice.NONE), Map.of());
        return new Facts("Christmas Bingo", CompetitionTemplate.KEY, false, setting, null, null, List.of(), players(2, null), Set.of(), 0, roles, held, Map.of(), Room.UNLIMITED, READY, true, ids);
    }

    private static final Map<String, HeldRole> ROLES = Map.of("participant", new HeldRole(201, "Christmas Bingo Participant"), "organiser", new HeldRole(202, "Christmas Bingo Organiser"));

    /** The overwrites the plan wants for a channel of {@code kind}, with the roles above. */
    private static List<Overwrite> planned(Kind kind) {
        return EventSpace.overwrites(EventSpace.access(kind, 0), who -> switch (who.who()) {
            case EVERYONE -> List.of(1L);
            case STAFF -> List.of(501L);
            case ORGANISERS -> List.of(202L);
            case PARTICIPANTS -> List.of(201L);
            case BOT -> List.of(77L);
            default -> List.of();
        });
    }

    private static Map<String, HeldChannel> held(String generalName, List<Overwrite> generalOverwrites) {
        Map<String, HeldChannel> held = new HashMap<>();
        held.put("category", new HeldChannel(100, "Christmas Bingo", null, planned(Kind.CATEGORY)));
        held.put("general", new HeldChannel(103, generalName, "category", generalOverwrites));
        return held;
    }

    private static ChannelItem item(Plan plan, String key) {
        return plan.channels().stream().filter(c -> c.key().equals(key)).findFirst().orElseThrow();
    }

    @Test
    void whatMatchesThePlanIsKeptAndTheSetupIsComplete() {
        Plan plan = EventSpace.plan(generalOnly(ROLES, held("christmas-bingo-general", planned(Kind.GENERAL)), IDS));
        assertEquals(Action.KEEP, item(plan, "general").action());
        assertEquals(Action.KEEP, item(plan, "category").action());
        assertTrue(plan.complete(), plan.missing().toString());
    }

    @Test
    void aRenamedChannelOrRoleIsPutBackUnderItsPlannedName() {
        Map<String, HeldRole> renamed = Map.of("participant", new HeldRole(201, "Bingo peeps"), "organiser", new HeldRole(202, "Christmas Bingo Organiser"));
        Plan plan = EventSpace.plan(generalOnly(renamed, held("chit-chat", planned(Kind.GENERAL)), IDS));
        ChannelItem general = item(plan, "general");
        assertEquals(Action.REPAIR, general.action());
        assertEquals("christmas-bingo-general", general.name(), "the name it gets back");
        assertTrue(general.note().contains("#chit-chat"), general.note());
        RoleItem participant = plan.roles().stream().filter(r -> r.key().equals("participant")).findFirst().orElseThrow();
        assertEquals(Action.REPAIR, participant.action());
        assertEquals("Christmas Bingo Participant", participant.name());
        assertFalse(plan.complete(), "something to put back means 'Bring them up to date' is offered");
        assertTrue(plan.missing().contains("putting #christmas-bingo-general back"), plan.missing().toString());
    }

    @Test
    void changedAccessIsPutBackButOnlyWhenItWasLookedAt() {
        List<Overwrite> opened = new ArrayList<>(planned(Kind.GENERAL));
        opened.add(new Overwrite(999, true, Permission.VIEW_CHANNEL.getRawValue(), 0)); // someone let another role in
        Plan plan = EventSpace.plan(generalOnly(ROLES, held("christmas-bingo-general", opened), IDS));
        assertEquals(Action.REPAIR, item(plan, "general").action());
        assertTrue(item(plan, "general").note().contains("who can see or write"), item(plan, "general").note());
        // without the server's ids (nothing was looked up) the access isn't judged
        assertEquals(Action.KEEP, item(EventSpace.plan(generalOnly(ROLES, held("christmas-bingo-general", opened), null)), "general").action());
    }

    @Test
    void aPlayersChannelKeepsWhateverItIsCalled() {
        Map<String, HeldChannel> held = new HashMap<>();
        held.put("category", new HeldChannel(100, "Christmas Bingo", null));
        held.put("player:1001", new HeldChannel(301, "old-nickname", "category"));
        Setting setting = new Setting(true, true, true, Map.of(Kind.STAFF, Choice.NONE, Kind.ANNOUNCEMENTS, Choice.NONE, Kind.GENERAL, Choice.NONE, Kind.REVIEW, Choice.NONE), Map.of());
        Plan plan = EventSpace.plan(new Facts("Christmas Bingo", CompetitionTemplate.KEY, false, setting, null, null, List.of(), players(1, null), Set.of(), 0, ROLES, held, Map.of(), Room.UNLIMITED, READY));
        assertEquals(Action.KEEP, item(plan, "player:1001").action());
    }

    @Test
    void driftSetsPlannedOverwritesAndOnlyTakesOffExtrasThatLetSomeoneSee() {
        long view = Permission.VIEW_CHANNEL.getRawValue();
        long send = Permission.MESSAGE_SEND.getRawValue();
        List<Overwrite> want = List.of(new Overwrite(1, true, 0, view), new Overwrite(201, true, view | send, 0));
        List<Overwrite> have = List.of(new Overwrite(1, true, 0, view), new Overwrite(201, true, view, 0), new Overwrite(999, true, view, 0), new Overwrite(888, true, 0, send));
        EventSpace.Drift drift = EventSpace.drift(want, have);
        assertEquals(List.of(new Overwrite(201, true, view | send, 0)), drift.put());
        assertEquals(List.of(999L), drift.remove(), "a deny someone added (888) is left alone");
        assertTrue(EventSpace.drift(want, want).none());
    }

    @Test
    void notEnoughRoomOnTheServerOrMissingPermissionsStopTheSetupWithAReason() {
        Facts crowded = new Facts("Christmas Bingo", BingoTemplate.KEY, false, ON, null, null, List.of(), players(2, null), Set.of(), 0, Map.of(), Map.of(), Map.of(), new Room(1, 3, List.of()), READY);
        Plan plan = EventSpace.plan(crowded);
        assertTrue(plan.blockers().stream().anyMatch(b -> b.contains("needs 2 new roles") && b.contains("room for 1 more")), plan.blockers().toString());
        assertTrue(plan.blockers().stream().anyMatch(b -> b.contains("needs 6 new channels and categories") && b.contains("room for 3 more")), plan.blockers().toString());
        Facts unable = new Facts("Christmas Bingo", BingoTemplate.KEY, false, ON, null, null, List.of(), players(2, null), Set.of(), 0, Map.of(), Map.of(), Map.of(), new Room(100, 100, List.of("Manage Roles")), READY);
        assertTrue(EventSpace.plan(unable).blockers().stream().anyMatch(b -> b.contains("Manage Roles")));
    }

    @Test
    void anEventThatIsntReadyOrIsSwitchedOffReportsThatAndStillShowsThePlan() {
        Plan early = EventSpace.plan(new Facts("Christmas Bingo", BingoTemplate.KEY, false, ON, null, null, List.of(), players(2, null), Set.of(), 0, Map.of(), Map.of(), Map.of(), Room.UNLIMITED,
                new Readiness(Status.SIGNUPS, false, false, false, false)));
        assertEquals(List.of("Close signups first."), early.blockers());
        assertEquals(6, early.newChannels());
        Plan off = EventSpace.plan(solo(BingoTemplate.KEY, Setting.OFF, players(2, null)));
        assertEquals(List.of("Roles and channels are switched off for this event."), off.blockers());
    }

    // ---------- who holds which role ----------

    @Test
    void everyoneOnTheRosterHoldsTheParticipantRoleAndTeamMembersTheirTeamsRole() {
        List<PlayerRef> players = List.of(new PlayerRef(1, "A", 11L), new PlayerRef(2, "B", 11L), new PlayerRef(3, "C", 12L), new PlayerRef(4, "D", null));
        Map<String, Set<Long>> wanted = EventSpace.wantedMembers(true, true, players, Set.of(9L));
        assertEquals(Set.of(1L, 2L, 3L, 4L), wanted.get("participant"));
        assertEquals(Set.of(9L), wanted.get("organiser"));
        assertEquals(Set.of(1L, 2L), wanted.get("team:11"));
        assertEquals(Set.of(3L), wanted.get("team:12"));
        assertFalse(EventSpace.wantedMembers(true, false, players, Set.of()).containsKey("team:11"));
        assertFalse(EventSpace.wantedMembers(false, true, players, Set.of()).containsKey("team:11"));
    }

    @Test
    void theChangesGiveRolesToNewPlayersTakeThemFromThoseWhoLeftAndEmptyARoleThatIsGone() {
        Map<String, Set<Long>> wanted = Map.of("participant", Set.of(1L, 2L, 3L), "organiser", Set.of(9L), "team:11", Set.of(1L));
        Map<String, Set<Long>> granted = Map.of("participant", Set.of(2L, 4L), "team:11", Set.of(1L), "team:12", Set.of(5L));
        EventSpace.Changes changes = EventSpace.changes(wanted, granted, Set.of("participant", "organiser", "team:11"));
        assertEquals(Map.of("participant", Set.of(1L, 3L), "organiser", Set.of(9L)), changes.add());
        assertEquals(Map.of("participant", Set.of(4L), "team:12", Set.of(5L)), changes.remove());
        assertEquals(5, changes.count());
        assertTrue(EventSpace.changes(wanted, wanted, Set.of("participant", "organiser", "team:11")).isEmpty());
        assertTrue(EventSpace.changes(wanted, Map.of(), Set.of()).isEmpty(), "nothing is given out for a role that doesn't exist");
    }

    @Test
    void theWebsiteIsGivenEveryChoiceWithItsDefault() {
        DataObject json = EventSpace.settingJson(new Setting(true, true, false, Map.of(Kind.GENERAL, Choice.existing(42)), Map.of("general", "{event} Chat")), CompetitionTemplate.KEY, null, null);
        assertTrue(json.getBoolean("enabled"));
        var slots = json.getArray("slots");
        assertEquals(5, slots.length());
        DataObject general = slots.getObject(2);
        assertEquals("general", general.getString("key"));
        assertEquals("existing", general.getString("use"));
        assertEquals("42", general.getString("channelId"));
        assertEquals("{event} Chat", general.getString("pattern"));
        assertEquals("{event} General", general.getString("defaultPattern"));
        assertFalse(slots.getObject(3).getBoolean("applies"), "a competition has no board");
        assertEquals(6, json.getArray("names").length());
    }
}
