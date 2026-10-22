package com.younglings.bot.plugin;

import com.younglings.bot.events.BingoBoardView;
import com.younglings.bot.events.BingoTemplate;
import com.younglings.bot.events.EventRepository;
import com.younglings.bot.events.EventRepository.Event;
import com.younglings.bot.events.EventRepository.Participant;
import com.younglings.bot.events.EventRules.ScoringMode;
import com.younglings.bot.events.EventRules.Status;
import com.younglings.bot.events.EventScoring.Standing;
import com.younglings.bot.events.EventService;
import com.younglings.bot.points.PointsRepository;
import io.github.freya022.botcommands.api.core.annotations.BEventListener;
import io.github.freya022.botcommands.api.core.events.InjectedJDAEvent;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The data behind RuneSync's optional in-game clan panel (RUNESYNC-DESIGN.md sections 7 and 9, ticket T-206): for each of the member's
 * clans that has switched the panel on (dashboard, Clan plugin page; off by default), its upcoming and running events, the member's own
 * points, and the member's own progress in the running events they are in.
 * <p>
 * Data flows only <b>into</b> the client, and only what the member could already see: events are listed only for servers whose Discord
 * the member is in (the rule the profile's event list uses), and the only points and progress in it are the member's own. No other
 * player is named. Answers are cached 60 seconds per member, so a panel left open costs the bot almost nothing.
 */
@BService
public class RuneSyncPanelService {
    private static final Logger log = LoggerFactory.getLogger(RuneSyncPanelService.class);
    public static final Duration CACHE = Duration.ofSeconds(60);
    static final int EVENTS_PER_CLAN = 10;

    private final RuneSyncRepository repository;
    private final EventRepository events;
    private final EventService eventService;
    private final PointsRepository points;
    private final Map<Long, Cached> cache = new ConcurrentHashMap<>();
    private volatile JDA jda;

    private record Cached(Instant at, DataObject answer) {}

    public RuneSyncPanelService(RuneSyncRepository repository, EventRepository events, EventService eventService, PointsRepository points) {
        this.repository = repository;
        this.events = events;
        this.eventService = eventService;
        this.points = points;
    }

    @BEventListener
    public void onJdaReady(InjectedJDAEvent event) {
        this.jda = event.getJda();
    }

    /** Whether any of the member's servers allows the panel: RuneSync's config says {@code panelAvailable} from this. */
    public boolean available(long userId) {
        return !clans(userId).isEmpty();
    }

    /** The member's clans (servers JonnyBot shares with them) that have switched the in-game panel on. */
    List<Guild> clans(long userId) {
        JDA current = jda;
        if (current == null) return List.of();
        User user = current.getUserById(userId);
        if (user == null) return List.of();
        List<Guild> on = new ArrayList<>();
        for (Guild guild : user.getMutualGuilds()) {
            if (repository.clanPanelOn(guild.getIdLong())) on.add(guild);
        }
        return on;
    }

    /** The panel's data for this member, at most 60 seconds old. */
    public DataObject panel(long userId, Instant now) {
        Cached hit = cache.get(userId);
        if (hit != null && hit.at().plus(CACHE).isAfter(now)) return hit.answer();
        DataArray clans = DataArray.empty();
        for (Guild guild : clans(userId)) {
            try {
                clans.add(clan(guild.getIdLong(), guild.getName(), userId));
            } catch (RuntimeException e) {
                log.warn("Couldn't build the RuneSync panel for server {}", guild.getId(), e);
            }
        }
        DataObject answer = DataObject.empty().put("available", !clans.isEmpty()).put("clans", clans).put("generatedAt", now.toString())
                .put("cacheSeconds", CACHE.toSeconds());
        cache.put(userId, new Cached(now, answer));
        if (cache.size() > 5_000) cache.entrySet().removeIf(e -> e.getValue().at().plus(CACHE).isBefore(now));
        return answer;
    }

    /** One clan: its open and running events, the member's own points, and the member's own progress. */
    DataObject clan(long guildId, String name, long userId) {
        DataArray list = DataArray.empty();
        DataArray progress = DataArray.empty();
        for (Event event : events.events(guildId, List.of(Status.RUNNING, Status.SIGNUPS), EVENTS_PER_CLAN)) {
            if (event.archived()) continue;
            Participant me = events.participant(event.id(), userId).orElse(null);
            boolean joined = me != null && !me.waiting();
            list.add(DataObject.empty().put("id", Long.toString(event.id())).put("name", event.name()).put("template", event.template())
                    .put("status", event.status().name()).put("startsAt", event.startsAt() == null ? null : event.startsAt().toString())
                    .put("endsAt", event.endsAt() == null ? null : event.endsAt().toString()).put("joined", joined));
            if (joined && event.status() == Status.RUNNING) progress.add(progress(guildId, event, me));
        }
        PointsRepository.Settings pointSettings = points.settings(guildId);
        DataObject own = pointSettings.enabled()
                ? DataObject.empty().put("enabled", true).put("balance", points.balance(guildId, userId)).put("unit", pointSettings.unit())
                : DataObject.empty().put("enabled", false);
        return DataObject.empty().put("name", name).put("events", list).put("points", own).put("progress", progress);
    }

    /** The member's own place in one running event: their (or their team's) points, objectives done, and bingo tiles done. */
    private DataObject progress(long guildId, Event event, Participant me) {
        boolean team = event.scoringMode() == ScoringMode.TEAM;
        String subject = team ? (me.teamId() == null ? null : "t:" + me.teamId()) : "u:" + me.userId();
        Standing mine = subject == null ? null
                : eventService.standings(guildId, event.id()).stream().filter(s -> s.subject().equals(subject)).findFirst().orElse(null);
        var objectives = eventService.objectives(event.id());
        int done = 0;
        for (var o : objectives) if (mine != null && mine.completions().getOrDefault(o.key(), 0) > 0) done++;
        DataObject out = DataObject.empty().put("eventId", Long.toString(event.id())).put("name", event.name()).put("team", team)
                .put("points", mine == null ? 0 : mine.points()).put("objectivesDone", done).put("objectivesTotal", objectives.size());
        if (BingoTemplate.KEY.equals(event.template())) {
            var tiles = BingoBoardView.personal(BingoBoardView.boardKeys(DataObject.fromJson(event.config())), objectives, mine);
            out.put("bingo", DataObject.empty().put("tilesDone", (int) tiles.stream().filter(t -> t.done()).count()).put("tiles", tiles.size()));
        } else {
            out.put("bingo", (DataObject) null);
        }
        return out;
    }

    /** The clan switch changed: cached answers for anyone may be wrong now. */
    public void forget() {
        cache.clear();
    }
}
