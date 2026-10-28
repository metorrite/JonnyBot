package com.runeclans.bot.events;

import com.runeclans.bot.alerts.AutoPostRouter;
import com.runeclans.bot.discord.jobs.DiscordOutbox;
import com.runeclans.bot.events.EventRepository.Event;
import com.runeclans.bot.events.EventRepository.Participant;
import com.runeclans.bot.events.EventRules.ScoringMode;
import com.runeclans.bot.events.EventRules.Status;
import com.runeclans.bot.events.SkillWeekRepository.Mode;
import com.runeclans.bot.events.SkillWeekRepository.Options;
import com.runeclans.bot.events.SkillWeekRepository.Settings;
import com.runeclans.bot.events.SkillWeekRepository.Week;
import com.runeclans.bot.member.MemberProfileRepository;
import com.runeclans.bot.plugin.PluginVisibilityRepository;
import com.runeclans.bot.runeclans.RuneClansHubClient;
import com.runeclans.bot.runescape.PlayerLinkService;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Skill of the week's options (T-711): the members' vote, the announcement posts and auto-enrol, each off by default. */
class SkillWeekOptionsTest {
    private static final long GUILD = Long.parseLong("7".repeat(18));
    private static final long ADMIN = Long.parseLong("5".repeat(18));
    private static final long CHANNEL = Long.parseLong("6".repeat(18));
    private static final long ALICE = Long.parseLong("1".repeat(18));
    private static final long BOB = Long.parseLong("2".repeat(18));
    private static final long CAROL = Long.parseLong("3".repeat(18));
    private static final Instant NOW = Instant.parse("2026-10-27T13:00:00Z");
    private static final Instant MONDAY = Instant.parse("2026-10-26T00:00:00Z");
    private static final Instant NEXT_MONDAY = Instant.parse("2026-11-02T00:00:00Z");

    private final SkillWeekRepository weeks = Mockito.mock(SkillWeekRepository.class);
    private final EventService events = Mockito.mock(EventService.class);
    private final EventRepository repository = Mockito.mock(EventRepository.class);
    private final RuneClansHubClient hub = Mockito.mock(RuneClansHubClient.class);
    private final MemberProfileRepository profiles = Mockito.mock(MemberProfileRepository.class);
    private final DiscordOutbox outbox = Mockito.mock(DiscordOutbox.class);
    private final SkillWeekService service = new SkillWeekService(weeks, events, repository, Mockito.mock(PluginXpTracker.class), hub, Mockito.mock(PlayerLinkService.class),
            profiles, Mockito.mock(PluginVisibilityRepository.class), outbox).withClock(Clock.fixed(NOW, ZoneOffset.UTC));

    @AfterEach
    void routerOff() {
        AutoPostRouter.reset();
    }

    private static Settings settings(Options options) {
        return new Settings(true, List.of(9, 15, 25), 1, ADMIN, options);
    }

    private static Event running(long id) {
        return event(id, Status.RUNNING);
    }

    private static Event event(long id, Status status) {
        return new Event(id, GUILD, CompetitionTemplate.KEY, "Skill of the week", "", status, null, ScoringMode.INDIVIDUAL, MONDAY, SkillWeekRules.weekEnd(MONDAY), 0,
                "{}", ADMIN, MONDAY, null, false);
    }

    private static Participant player(long userId) {
        return new Participant(42, userId, GUILD, null, null, false, null, MONDAY, null, null, null, null, null, null);
    }

    private static RuneClansHubClient.Answer gainers(String... discordIds) {
        DataArray players = DataArray.empty();
        for (String id : discordIds) players.add(DataObject.empty().put("alias", "a1_" + id).put("xp", 100).put("discordId", id));
        players.add(DataObject.empty().put("alias", "a1_private").put("xp", 100)); // their grant doesn't share their Discord id
        return new RuneClansHubClient.Answer(200, DataObject.empty().put("players", players));
    }

    // ---------- the vote ----------

    @Test
    void theVoteIsWonByTheMostVotesAmongTheRotasSkills() {
        List<Integer> rota = List.of(9, 15, 25);
        assertEquals(25, SkillWeekRules.voteWinner(rota, 0, Map.of(9, 1, 25, 3)));
        assertEquals(15, SkillWeekRules.voteWinner(rota, 1, Map.of()), "no votes: the rota's next");
        assertEquals(25, SkillWeekRules.voteWinner(rota, 2, Map.of(9, 2, 25, 2)), "a tie goes to the tied skill next in the rota's order");
        assertEquals(9, SkillWeekRules.voteWinner(rota, 0, Map.of(9, 2, 25, 2)));
        assertEquals(15, SkillWeekRules.voteWinner(rota, 1, Map.of(3, 10)), "a skill off the ballot doesn't count");
        assertEquals(-1, SkillWeekRules.voteWinner(List.of(), 0, Map.of(3, 1)));
        assertEquals(NEXT_MONDAY, SkillWeekRules.votingFor(NOW), "members vote for next week's skill");
    }

    @Test
    void membersVoteForNextWeekAmongTheRotaOnlyWhenTheServerVotes() {
        when(weeks.settings(GUILD)).thenReturn(settings(new Options(Mode.VOTE, null, false)));
        when(weeks.votes(GUILD, NEXT_MONDAY)).thenReturn(Map.of(25, 2));
        when(weeks.vote(GUILD, NEXT_MONDAY, ALICE)).thenReturn(OptionalInt.of(25));

        DataObject view = service.castVote(GUILD, ALICE, 25);
        verify(weeks).vote(GUILD, NEXT_MONDAY, ALICE, 25);
        assertTrue(view.getBoolean("open"));
        assertEquals(NEXT_MONDAY.toString(), view.getString("week"));
        assertEquals(25, view.getInt("mine"));
        DataArray options = view.getArray("options");
        assertEquals(3, options.length(), "the rota is the ballot, in its order");
        assertEquals(9, options.getObject(0).getInt("skillId"));
        assertEquals(2, options.getObject(2).getInt("votes"));

        EventProblem offBallot = assertThrows(EventProblem.class, () -> service.castVote(GUILD, ALICE, 3));
        assertEquals(400, offBallot.status());
        when(weeks.settings(GUILD)).thenReturn(settings(Options.OFF));
        EventProblem rota = assertThrows(EventProblem.class, () -> service.castVote(GUILD, ALICE, 9));
        assertEquals(409, rota.status());
        assertFalse(service.voteView(GUILD, ALICE).getBoolean("open"), "a rota server has no vote");
        verify(weeks, Mockito.times(1)).vote(anyLong(), any(), anyLong(), anyInt());
    }

    @Test
    void theClanWebsiteSeesTheVoteButNotTheStaffOptions() {
        when(weeks.settings(GUILD)).thenReturn(settings(new Options(Mode.VOTE, CHANNEL, true)));
        when(weeks.recent(GUILD, 4)).thenReturn(List.of());
        when(weeks.votes(GUILD, NEXT_MONDAY)).thenReturn(Map.of());
        when(profiles.hiddenDiscordUsers(GUILD)).thenReturn(Set.of());
        when(profiles.hiddenRsns(GUILD, true)).thenReturn(Set.of());

        DataObject staff = service.view(GUILD, 10);
        assertEquals("vote", staff.getString("mode"));
        assertEquals(String.valueOf(CHANNEL), staff.getString("announceChannelId"));
        assertTrue(staff.getBoolean("autoEnrol"));
        DataObject site = service.publicView(GUILD, 10);
        assertTrue(site.getObject("vote").getBoolean("open"));
        assertFalse(site.hasKey("announceChannelId"));
        assertFalse(site.hasKey("autoEnrol"));
    }

    // ---------- announcements ----------

    @Test
    void theWeeksSkillIsPostedInTheChosenChannelThroughTheOutbox() {
        when(weeks.settings(GUILD)).thenReturn(settings(new Options(Mode.ROTA, CHANNEL, false)));
        when(weeks.week(GUILD, MONDAY)).thenReturn(Optional.empty());
        when(weeks.claim(GUILD, MONDAY)).thenReturn(OptionalInt.of(15));
        when(events.createWholeSpanEvent(eq(GUILD), eq(ADMIN), any())).thenReturn(42L);

        service.open(GUILD, NOW);
        ArgumentCaptor<DataObject> payload = ArgumentCaptor.forClass(DataObject.class);
        verify(outbox).enqueue(eq(EventUpdatePostJob.KIND), eq(GUILD), eq(0L), eq("skill-week:" + GUILD + ":open:" + MONDAY), payload.capture());
        assertEquals(String.valueOf(CHANNEL), payload.getValue().getString("channelId"));
        assertTrue(payload.getValue().getString("text").contains("Skill of the week: Herblore"), payload.getValue().getString("text"));
        assertFalse(payload.getValue().getString("text").contains("\n\n"));
    }

    @Test
    void noPostWithoutAChannelOrWhileThisBotIsntLive() {
        when(weeks.week(GUILD, MONDAY)).thenReturn(Optional.empty());
        when(weeks.claim(GUILD, MONDAY)).thenReturn(OptionalInt.of(15));
        when(events.createWholeSpanEvent(eq(GUILD), eq(ADMIN), any())).thenReturn(42L);
        when(weeks.settings(GUILD)).thenReturn(settings(Options.OFF));
        service.open(GUILD, NOW);

        com.runeclans.bot.tracking.TrackingPostingToggle posting = Mockito.mock(com.runeclans.bot.tracking.TrackingPostingToggle.class);
        when(posting.isEnabled()).thenReturn(false);
        new AutoPostRouter(Mockito.mock(com.runeclans.bot.config.BotConfig.class), posting);
        when(weeks.settings(GUILD)).thenReturn(settings(new Options(Mode.ROTA, CHANNEL, false)));
        service.open(GUILD, NOW);
        verify(outbox, never()).enqueue(anyString(), anyLong(), anyLong(), anyString(), any());
    }

    @Test
    void theWinnersArePostedOnceWhenTheWeekCloses() {
        Week week = new Week(GUILD, MONDAY, 15, 42L, null, null);
        when(repository.event(GUILD, 42L)).thenReturn(Optional.of(event(42, Status.DONE)));
        when(repository.progress(42L)).thenReturn(List.of());
        when(weeks.close(eq(GUILD), eq(MONDAY), anyString())).thenReturn(true, false);
        when(weeks.settings(GUILD)).thenReturn(settings(new Options(Mode.ROTA, CHANNEL, false)));
        when(profiles.hiddenDiscordUsers(GUILD)).thenReturn(Set.of());
        when(profiles.hiddenRsns(GUILD, true)).thenReturn(Set.of());

        service.close(week);
        service.close(week);
        ArgumentCaptor<DataObject> payload = ArgumentCaptor.forClass(DataObject.class);
        verify(outbox, Mockito.times(1)).enqueue(eq(EventUpdatePostJob.KIND), eq(GUILD), eq(0L), eq("skill-week:" + GUILD + ":winners:" + MONDAY), payload.capture());
        assertTrue(payload.getValue().getString("text").contains("Nobody gained any Herblore XP that week."), payload.getValue().getString("text"));
    }

    @Test
    void theWinnersTextNamesOnlyWhatTheClanWebsiteWould() {
        String text = SkillWeekService.winnersText(15, MONDAY, List.of(DataObject.empty().put("rank", 1).put("name", "Zezima").put("xp", 1_234_567L),
                DataObject.empty().put("rank", 2).put("name", "A member").put("xp", (Object) null)));
        assertTrue(text.contains("1. Zezima (1,234,567 XP)"), text);
        assertTrue(text.contains("2. A member") && !text.contains("2. A member ("), text);
        assertFalse(text.contains("<@"), "names only, never mentions");
    }

    // ---------- auto-enrol ----------

    @Test
    void membersWhoShareRuneSyncXpAreEnrolledOnceEach() {
        when(weeks.settings(GUILD)).thenReturn(settings(new Options(Mode.ROTA, null, true)));
        when(weeks.week(GUILD, MONDAY)).thenReturn(Optional.of(new Week(GUILD, MONDAY, 15, 42L, null, null)));
        when(repository.event(GUILD, 42L)).thenReturn(Optional.of(running(42)));
        when(repository.participants(42L)).thenReturn(List.of(player(ALICE)));
        when(weeks.enrolled(GUILD, MONDAY)).thenReturn(new java.util.HashSet<>(Set.of(CAROL))); // left after being enrolled once
        when(weeks.markEnrolled(eq(GUILD), eq(MONDAY), anyLong())).thenReturn(true);
        when(hub.xpGainers(GUILD, LocalDate.parse("2026-10-26"))).thenReturn(gainers(String.valueOf(BOB)));
        when(hub.xpGainers(GUILD, LocalDate.parse("2026-10-27"))).thenReturn(gainers(String.valueOf(ALICE), String.valueOf(BOB), String.valueOf(CAROL)));

        assertEquals(1, service.autoEnrol(GUILD, NOW));
        verify(events).join(GUILD, 42L, GUILD, BOB, null, true, null);
        verify(events, never()).join(anyLong(), anyLong(), anyLong(), eq(ALICE), any(), anyBoolean(), any());
        verify(events, never()).join(anyLong(), anyLong(), anyLong(), eq(CAROL), any(), anyBoolean(), any());
        verify(weeks, Mockito.times(1)).markEnrolled(GUILD, MONDAY, BOB);
    }

    @Test
    void autoEnrolIsOffByDefaultAndWaitsForTheHub() {
        when(weeks.settings(GUILD)).thenReturn(settings(Options.OFF));
        assertEquals(0, service.autoEnrol(GUILD, NOW));
        verify(hub, never()).xpGainers(anyLong(), any());

        when(weeks.settings(GUILD)).thenReturn(settings(new Options(Mode.ROTA, null, true)));
        when(weeks.week(GUILD, MONDAY)).thenReturn(Optional.of(new Week(GUILD, MONDAY, 15, 42L, null, null)));
        when(repository.event(GUILD, 42L)).thenReturn(Optional.of(running(42)));
        when(repository.participants(42L)).thenReturn(List.of());
        when(weeks.enrolled(GUILD, MONDAY)).thenReturn(new java.util.HashSet<>());
        when(hub.xpGainers(anyLong(), any())).thenReturn(null);
        assertEquals(0, service.autoEnrol(GUILD, NOW));
        verify(events, never()).join(anyLong(), anyLong(), anyLong(), anyLong(), any(), anyBoolean(), any());
    }
}
