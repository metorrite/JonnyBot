package com.younglings.bot.events;

import com.younglings.bot.clanprofile.ClanProfile;
import com.younglings.bot.clanprofile.ClanProfileRepository;
import com.younglings.bot.events.EventRepository.Event;
import com.younglings.bot.events.EventRepository.Participant;
import com.younglings.bot.events.EventRules.ObjectiveSpec;
import com.younglings.bot.events.EventRules.ScoringMode;
import com.younglings.bot.events.EventRules.Status;
import com.younglings.bot.i18n.Text;
import com.younglings.bot.runescape.ClanMemberRepository.ClanMemberRow;
import com.younglings.bot.runescape.ClanSyncService;
import com.younglings.bot.runescape.PlayerLink;
import com.younglings.bot.runescape.PlayerLinkService;
import io.github.freya022.botcommands.api.core.annotations.BEventListener;
import io.github.freya022.botcommands.api.core.events.InjectedJDAEvent;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;

/**
 * The clan-vs-clan template (T-222, design {@code docs/EVENTS-CLAN-VS-CLAN.md}): a team event where every team is a clan. The host's clan and every co-host
 * that accepted (T-219) and has a clan set up get a team, named after the clan. A player's team is the participating clan whose in-game roster has one of
 * their linked names; someone on no participating roster can't join. The clans play an inner game that the existing template runs (its settings, objectives
 * and checks are that template's), and the clans are scored fairly across sizes ({@link ClanVsClan.Scoring}). No Discord quick-create: inviting clans is done
 * on the dashboard.
 */
@BService
public class ClanVsClanTemplate implements EventTemplate {
    private static final Logger log = LoggerFactory.getLogger(ClanVsClanTemplate.class);

    private final Map<String, EventTemplate> games = new LinkedHashMap<>();
    private final EventRepository repository;
    private final EventCohostRepository cohosts;
    private final PlayerLinkService links;
    private final ClanSyncService clanSync;
    private final ClanProfileRepository profiles;
    private volatile JDA jda;

    public ClanVsClanTemplate(BingoTemplate bingo, CompetitionTemplate competition, BossNightTemplate bossNight, ScavengerHuntTemplate scavenger, EventRepository repository,
                              EventCohostRepository cohosts, PlayerLinkService links, ClanSyncService clanSync, ClanProfileRepository profiles) {
        for (EventTemplate t : List.of(bingo, competition, bossNight, scavenger)) games.put(t.key(), t);
        this.repository = repository;
        this.cohosts = cohosts;
        this.links = links;
        this.clanSync = clanSync;
        this.profiles = profiles;
    }

    @BEventListener
    public void onJdaReady(InjectedJDAEvent event) {
        this.jda = event.getJda();
    }

    private EventTemplate inner(DataObject config) {
        return games.get(ClanVsClan.settings(config).game());
    }

    // ---------- the template ----------

    @Override
    public String key() {
        return ClanVsClan.KEY;
    }

    @Override
    public String label() {
        return "Clan vs clan";
    }

    @Override
    public String description() {
        return "Your clan against other clans from your alliances. Each clan is a team, players are put on their clan's team by its in-game list, and the clans play a bingo, "
                + "competition, boss night or scavenger hunt. Invite the clans on the event's Co-hosts card.";
    }

    @Override
    public Set<String> fixedSettings() {
        Set<String> fixed = new LinkedHashSet<>(List.of(ClanVsClan.GAME, ClanVsClan.TEAMS));
        for (EventTemplate t : games.values()) fixed.addAll(t.fixedSettings());
        return fixed;
    }

    /** Teams come from the clans' in-game lists, read through players' linked names. */
    @Override
    public boolean needsRsn() {
        return true;
    }

    @Override
    public ScoringMode fixedScoringMode() {
        return ScoringMode.TEAM;
    }

    @Override
    public boolean managesTeams() {
        return true;
    }

    @Override
    public List<EventOptions.Option> options() {
        List<EventOptions.Option> list = new ArrayList<>();
        list.add(EventOptions.Option.choice("config." + ClanVsClan.GAME, "What the clans play", "Each clan plays it as one team. A bingo gives every clan its own board with the same tiles.",
                EventOptions.Step.RULES, BingoTemplate.KEY, List.of(new EventOptions.Choice(BingoTemplate.KEY, "Bingo"), new EventOptions.Choice(CompetitionTemplate.KEY, "XP competition"),
                        new EventOptions.Choice(BossNightTemplate.KEY, "Boss night"), new EventOptions.Choice(ScavengerHuntTemplate.KEY, "Scavenger hunt"))));
        list.add(EventOptions.Option.choice("config.cvc.scoring", "How clans are scored", "So a big clan doesn't win just by size.", EventOptions.Step.RULES, ClanVsClan.Scoring.TOP_N.name(),
                List.of(new EventOptions.Choice(ClanVsClan.Scoring.TOP_N.name(), "Best players", "Each clan's best players' points add up. Bonuses still count once for the clan."),
                        new EventOptions.Choice(ClanVsClan.Scoring.AVERAGE.name(), "Average", "Each clan's points divided by the players who scored."),
                        new EventOptions.Choice(ClanVsClan.Scoring.TOTAL.name(), "Total", "Everything the clan scored. The biggest clan usually wins."))));
        list.add(EventOptions.Option.number("config.cvc.topN", "Best players counted", "How many of each clan's best players count.", EventOptions.Step.RULES, ClanVsClan.DEFAULT_TOP_N, 1, ClanVsClan.MAX_TOP_N)
                .showWhen(EventOptions.When.is("config.cvc.scoring", ClanVsClan.Scoring.TOP_N.name())));
        list.add(EventOptions.Option.number("config.cvc.minPlayers", "Fewest players a clan needs", "A clan with fewer at Close signups is pointed out on the checklist, so you can drop it.",
                EventOptions.Step.PLAYERS, ClanVsClan.DEFAULT_MIN_PLAYERS, 0, EventRules.MAX_PARTICIPANTS));
        list.add(EventOptions.Option.of("config.cvc.reviewByOtherClan", "Clans review each other's proof", "Organisers can't decide proof from their own clan, only from the others. For when the clans would rather not trust one host.",
                EventOptions.Step.RULES, EventOptions.Type.BOOL, false));
        Set<String> seen = new HashSet<>();
        list.forEach(o -> seen.add(o.path()));
        // each inner game's own options, shown (and required) only when it is the one chosen
        for (EventTemplate game : games.values()) {
            EventOptions.When chosen = EventOptions.When.is("config." + ClanVsClan.GAME, game.key());
            for (EventOptions.Option o : game.options()) {
                if (!seen.add(o.path())) continue;
                EventOptions.Option scoped = o.showWhen() == null ? o.showWhen(chosen) : o;
                if (scoped.required().always()) scoped = scoped.requiredWhen(chosen);
                list.add(scoped);
            }
        }
        return list;
    }

    @Override
    public DataObject prepare(DataObject config) {
        DataObject prepared = inner(config).prepare(config);
        prepared.put(ClanVsClan.GAME, ClanVsClan.settings(config).game());
        if (BingoTemplate.KEY.equals(ClanVsClan.settings(config).game())) {
            // every clan works through its own board with the same tiles
            if (prepared.hasKey("board") && !prepared.isNull("board")) prepared.getObject("board").put("display", "OWN");
        }
        return prepared;
    }

    @Override
    public List<String> configProblems(DataObject config) {
        List<String> problems = new ArrayList<>(ClanVsClan.problems(config));
        problems.addAll(inner(config).configProblems(config));
        return problems;
    }

    @Override
    public List<ObjectiveSpec> starterObjectives(DataObject config) {
        return inner(config).starterObjectives(config);
    }

    // ---------- the clans and their teams ----------

    /** The servers taking part: the host, then each co-host that accepted and has a clan set up. */
    List<Long> participating(Event event) {
        List<Long> clans = new ArrayList<>();
        clans.add(event.guildId());
        for (EventCohostRepository.Cohost c : cohosts.forEvent(event.id())) {
            if (c.status() == EventCohostRepository.Status.ACCEPTED && clanSync.getClanName(c.guildId()) != null) clans.add(c.guildId());
        }
        return clans;
    }

    /** What a clan's team is called: its clan profile's name, else its server's, else its in-game clan's. */
    String teamName(long guildId) {
        String name = profiles.get(guildId).map(ClanProfile::displayName).filter(n -> n != null && !n.isBlank()).orElse(null);
        if (name == null && jda != null) {
            Guild guild = jda.getGuildById(guildId);
            if (guild != null) name = guild.getName();
        }
        if (name == null) name = clanSync.getClanName(guildId);
        if (name == null || name.isBlank()) name = "Clan " + guildId;
        name = name.strip();
        return name.length() > EventRules.MAX_TEAM_NAME ? name.substring(0, EventRules.MAX_TEAM_NAME) : name;
    }

    /**
     * Makes a team for every participating clan that has none, and takes away the team of a clan that no longer takes part while the event is being set up and
     * nobody is on it (a clan that leaves later keeps its team, frozen). Returns the teams of the clans taking part now, by server.
     */
    Map<Long, Long> ensureTeams(Event event) {
        DataObject config = DataObject.fromJson(event.config());
        Map<Long, Long> teams = ClanVsClan.teams(config);
        Set<Long> existing = new HashSet<>();
        repository.teams(event.id()).forEach(t -> existing.add(t.id()));
        teams.values().removeIf(t -> !existing.contains(t)); // a team someone deleted by hand is made again
        List<Long> taking = participating(event);
        boolean editable = EventRules.editable(event.status());
        boolean changed = false;
        if (editable) {
            for (long clan : taking) {
                if (teams.containsKey(clan)) continue;
                OptionalLong team = addTeam(event.id(), teamName(clan));
                if (team.isPresent()) {
                    teams.put(clan, team.getAsLong());
                    changed = true;
                }
            }
            List<Participant> players = repository.participants(event.id());
            for (var it = teams.entrySet().iterator(); it.hasNext(); ) {
                var t = it.next();
                if (taking.contains(t.getKey())) continue;
                long teamId = t.getValue();
                if (players.stream().anyMatch(p -> p.teamId() != null && p.teamId() == teamId)) continue;
                repository.removeTeam(event.id(), teamId);
                it.remove();
                changed = true;
            }
        }
        if (changed || !ClanVsClan.teams(config).equals(teams)) {
            DataObject map = DataObject.empty();
            teams.forEach((clan, team) -> map.put(Long.toString(clan), team));
            config.put(ClanVsClan.TEAMS, map);
            repository.setConfig(event.guildId(), event.id(), config.toString());
        }
        Map<Long, Long> current = new LinkedHashMap<>();
        for (long clan : taking) if (teams.containsKey(clan)) current.put(clan, teams.get(clan));
        return current;
    }

    private OptionalLong addTeam(long eventId, String name) {
        OptionalLong team = repository.addTeam(eventId, name, null, EventRules.MAX_TEAMS);
        for (int n = 2; team.isEmpty() && n <= 5; n++) {
            String suffix = " (" + n + ")";
            String base = name.length() + suffix.length() > EventRules.MAX_TEAM_NAME ? name.substring(0, EventRules.MAX_TEAM_NAME - suffix.length()) : name;
            team = repository.addTeam(eventId, base + suffix, null, EventRules.MAX_TEAMS);
        }
        if (team.isEmpty()) log.warn("Couldn't make the team of a clan in event {} (name taken or too many teams)", eventId);
        return team;
    }

    /** The participating clans (servers) whose in-game list has one of the player's linked names. */
    Set<Long> clansOf(Event event, long userGuildId, long userId, Iterable<Long> clans) {
        Set<String> names = new HashSet<>();
        Set<Long> where = new LinkedHashSet<>();
        where.add(event.guildId());
        where.add(userGuildId);
        clans.forEach(where::add);
        for (long g : where) for (PlayerLink l : links.getLinksForUser(g, userId)) names.add(l.rsn().toLowerCase(Locale.ROOT));
        Set<Long> mine = new LinkedHashSet<>();
        if (names.isEmpty()) return mine;
        for (long clan : clans) {
            for (ClanMemberRow row : clanSync.getRoster(clan, true)) {
                if (names.contains(row.rsn().toLowerCase(Locale.ROOT))) {
                    mine.add(clan);
                    break;
                }
            }
        }
        return mine;
    }

    @Override
    public Long teamOnJoin(Event event, long userGuildId, long userId, boolean staff) {
        Map<Long, Long> teams = ensureTeams(event);
        ClanVsClan.TeamChoice choice = ClanVsClan.teamFor(clansOf(event, userGuildId, userId, teams.keySet()), userGuildId, teams);
        return switch (choice) {
            case ClanVsClan.TeamChoice.One one -> one.teamId();
            case ClanVsClan.TeamChoice.StaffChoose s -> null;
            case ClanVsClan.TeamChoice.NoClan n -> {
                List<String> names = new ArrayList<>();
                for (long clan : teams.keySet()) names.add(teamName(clan));
                throw new EventProblem(403, Text.of(event.guildId(), "events.problem.only_members_of_can_join_this", String.join(", ", names)));
            }
        };
    }

    @Override
    public void syncTeams(Event event, boolean recheck) {
        Map<Long, Long> teams = ensureTeams(event);
        if (!recheck) return;
        for (Participant p : repository.participants(event.id())) {
            if (p.waiting()) continue;
            if (ClanVsClan.teamFor(clansOf(event, p.guildId(), p.userId(), teams.keySet()), p.guildId(), teams) instanceof ClanVsClan.TeamChoice.One one
                    && (p.teamId() == null || p.teamId() != one.teamId())) {
                repository.setTeam(event.id(), p.userId(), one.teamId());
            }
        }
    }

    @Override
    public List<EventScoring.Standing> standings(Event event, List<ObjectiveSpec> objectives, List<EventRepository.Progress> progress, List<EventScoring.Standing> usual) {
        ClanVsClan.Settings settings = ClanVsClan.settings(DataObject.fromJson(event.config()));
        if (settings.scoring() == ClanVsClan.Scoring.TOTAL) return null;
        return ClanVsClan.standings(settings, usual, objectives, ClanVsClan.contributions(objectives, reports(progress)));
    }

    /** The progress log as clan-vs-clan reports: entries with a team only. */
    public static List<ClanVsClan.Report> reports(List<EventRepository.Progress> progress) {
        List<ClanVsClan.Report> reports = new ArrayList<>();
        for (EventRepository.Progress p : progress) if (p.teamId() != null) reports.add(new ClanVsClan.Report(p.userId(), p.teamId(), p.objectiveKey(), p.amount(), p.createdAt()));
        return reports;
    }

    @Override
    public void checklist(Event event, List<EventPrep.Step> steps, List<String> warnings) {
        if (event.status() == Status.CANCELLED) return;
        List<Long> clans = participating(event);
        boolean started = event.status() == Status.RUNNING || event.status() == Status.SCORING || event.status() == Status.DONE;
        String detail = clans.size() < 2 ? "Invite at least one other clan on the Co-hosts card. Each clan that accepts gets a team." : clans.size() + " clans are taking part.";
        steps.add(0, new EventPrep.Step("clans", "Invite clans", clans.size() >= 2 || started ? EventPrep.State.DONE : EventPrep.State.ACTIVE, true, detail, List.of()));
        if (clans.size() < 2 && !started) warnings.add("Only your clan is taking part so far. Invite other clans on the Co-hosts card.");
        int min = ClanVsClan.settings(DataObject.fromJson(event.config())).minPlayers();
        if (min <= 0 || started) return;
        Map<Long, Long> teams = ClanVsClan.teams(DataObject.fromJson(event.config()));
        List<Participant> players = repository.participants(event.id());
        for (long clan : clans) {
            Long team = teams.get(clan);
            long count = team == null ? 0 : players.stream().filter(p -> !p.waiting() && p.teamId() != null && p.teamId().longValue() == team).count();
            if (count < min) warnings.add(teamName(clan) + " has " + count + " player" + (count == 1 ? "" : "s") + ", fewer than the " + min + " a clan needs. You can remove it as a co-host before signups close.");
        }
    }
}
