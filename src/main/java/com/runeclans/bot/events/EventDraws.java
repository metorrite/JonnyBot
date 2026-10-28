package com.runeclans.bot.events;

import com.runeclans.bot.alerts.AutoPostRouter;
import com.runeclans.bot.discord.jobs.DiscordOutbox;
import com.runeclans.bot.events.EventRepository.Event;
import com.runeclans.bot.events.EventRules.Status;
import com.runeclans.bot.events.PrizeRules.AwardStatus;
import com.runeclans.bot.util.BotScheduler;
import com.runeclans.bot.discord.Shards;
import io.github.freya022.botcommands.api.core.annotations.BEventListener;
import io.github.freya022.botcommands.api.core.events.InjectedJDAEvent;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

/**
 * Prize draws (T-649): for an event won by a draw ({@link WinModes}), the bot draws the winners among everyone who qualified, by itself at the event's end
 * time (a one-minute check on its own {@link BotScheduler} lane) or when staff press "Draw now" on the website, and draws again for a winner who doesn't
 * claim ("Reroll"). Each round is saved with its seed and entries ({@link EventDrawRepository}) before anything is posted; the winners' prizes are
 * recorded as pending awards from the event's prize list (nothing is paid); the result is announced through the Discord outbox
 * ({@link EventUpdatePostJob}, no mentions, each post noted once in {@link EventUpdateRepository}). A bot that is not live draws nothing by itself and
 * posts nothing.
 */
@BService
public class EventDraws {
    private static final Logger log = LoggerFactory.getLogger(EventDraws.class);
    private static final SecureRandom SEEDS = new SecureRandom();

    private final EventRepository repository;
    private final EventService service;
    private final EventDrawRepository draws;
    private final EventUpdateRepository posts;
    private final EventBoardUpdates updates;
    private final DiscordOutbox outbox;
    private final ScheduledExecutorService executor = BotScheduler.lane("event-draws");
    private volatile JDA jda;
    /** Where seeds come from; a test can fix it. */
    volatile LongSupplier seeds = SEEDS::nextLong;

    public EventDraws(EventRepository repository, EventService service, EventDrawRepository draws, EventUpdateRepository posts, EventBoardUpdates updates,
                      DiscordOutbox outbox) {
        this.repository = repository;
        this.service = service;
        this.draws = draws;
        this.posts = posts;
        this.updates = updates;
        this.outbox = outbox;
    }

    @BEventListener
    public void onJdaReady(InjectedJDAEvent event) {
        this.jda = event.getJda();
        executor.scheduleWithFixedDelay(() -> {
            try {
                sweep(Instant.now());
            } catch (RuntimeException e) {
                log.warn("Could not run the prize draws that are due", e);
            }
        }, 45, 60, TimeUnit.SECONDS);
    }

    /** Draws every event whose end time has come and that asks the bot to draw by itself. Returns how many were drawn. */
    int sweep(Instant now) {
        if (AutoPostRouter.diverting()) return 0;
        int drawn = 0;
        for (Status status : new Status[] {Status.RUNNING, Status.SCORING, Status.DONE}) {
            for (Event event : repository.eventsInStatus(status)) {
                try {
                    WinModes.Settings win = WinModes.of(DataObject.fromJson(event.config()));
                    if (!win.draws() || event.endsAt() == null || !WinModes.autoDue(win, event.endsAt(), draws.rounds(event.id()), now)) continue;
                    Guild guild = guild(event.guildId());
                    if (guild == null) continue;
                    draw(event, null, names(guild, event.id()), now);
                    drawn++;
                } catch (RuntimeException e) {
                    log.warn("Could not draw the winners of event {}", event.id(), e);
                }
            }
        }
        if (drawn > 0) log.info("Prize draws: {} drawn at their end time", drawn);
        return drawn;
    }

    /** Staff's "Draw now": the draw, at once. {@code actorId} is who pressed it. */
    public WinModes.Round drawNow(Guild guild, long eventId, long actorId) {
        Event event = service.event(guild.getIdLong(), eventId);
        return draw(event, actorId, names(guild, eventId), Instant.now());
    }

    /** Draws again for {@code place}, whose winner didn't claim: a new winner from the same entries, never anyone drawn before. */
    public WinModes.Round reroll(Guild guild, long eventId, int place, long actorId) {
        Event event = service.event(guild.getIdLong(), eventId);
        WinModes.Settings win = WinModes.of(DataObject.fromJson(event.config()));
        if (!win.draws()) throw new EventProblem(409, "This event isn't won by a prize draw.");
        if (event.status() == Status.CANCELLED) throw new EventProblem(409, "This event was cancelled.");
        List<WinModes.Round> rounds = draws.rounds(eventId);
        WinModes.Round latest = WinModes.latestDraw(rounds);
        if (latest == null) throw new EventProblem(409, "Draw the winners first.");
        List<String> winners = WinModes.winners(rounds);
        if (place < 1 || place > winners.size() || winners.get(place - 1) == null) throw new EventProblem(400, "There is no winner in that place to draw again for.");
        List<EventRepository.AwardRow> atPlace = repository.awards(eventId).stream().filter(a -> a.place() == place).toList();
        if (atPlace.stream().anyMatch(a -> a.status() == AwardStatus.PAID || a.status() == AwardStatus.REFUNDED)) {
            throw new EventProblem(409, "That prize was already paid, so its winner can't be drawn again.");
        }
        String old = winners.get(place - 1);
        long seed = seeds.getAsLong();
        List<String> picked = WinModes.draw(latest.entrants(), 1, WinModes.alreadyDrawn(rounds), seed);
        WinModes.Round round = new WinModes.Round(rounds.size(), seed, latest.entrants(), picked, place, old, Instant.now(), actorId);
        if (!draws.add(eventId, round)) throw new EventProblem(409, "Someone else drew at the same moment. Refresh to see the result.");
        for (EventRepository.AwardRow a : atPlace) {
            if (a.status() == AwardStatus.PENDING) repository.moveAward(eventId, a.id(), AwardStatus.PENDING, AwardStatus.REVOKED, actorId, "Drawn again: the winner didn't claim.");
        }
        if (!picked.isEmpty()) recordAwards(event, onlyPlace(place, picked.get(0)));
        log.info("Event {}: place {} drawn again by {}", eventId, place, actorId);
        announce(event, "draw:" + round.round(), "DRAW", WinModes.rerollText(event.name(), round, names(guild, eventId)));
        return round;
    }

    WinModes.Round draw(Event event, Long actorId, Function<String, String> nameOf, Instant now) {
        WinModes.Settings win = WinModes.of(DataObject.fromJson(event.config()));
        if (!win.draws()) throw new EventProblem(409, "This event isn't won by a prize draw. Choose \"Prize draw\" under How it's won first.");
        if (event.status() != Status.RUNNING && event.status() != Status.SCORING && event.status() != Status.DONE) {
            throw new EventProblem(409, "The draw can happen once the event is running.");
        }
        List<WinModes.Round> rounds = draws.rounds(event.id());
        if (!WinModes.mayDraw(rounds)) throw new EventProblem(409, "The winners were already drawn. Use Reroll for a winner who doesn't claim.");
        Instant until = event.endsAt() != null && event.endsAt().isBefore(now) ? event.endsAt() : now;
        List<WinModes.Entrant> entrants = WinModes.entrants(win, service.objectives(event.id()), service.progressLog(event), until, stillIn(event));
        long seed = seeds.getAsLong();
        List<String> picked = WinModes.draw(entrants, win.winners(), Set.of(), seed);
        WinModes.Round round = new WinModes.Round(rounds.size(), seed, entrants, picked, null, null, now, actorId);
        if (!draws.add(event.id(), round)) throw new EventProblem(409, "Someone else drew at the same moment. Refresh to see the result.");
        if (!picked.isEmpty()) recordAwards(event, picked);
        log.info("Event {}: prize draw {} made ({} winner(s) from {} entries){}", event.id(), round.round(), picked.size(), round.tickets(), actorId == null ? " at its end time" : " by " + actorId);
        announce(event, "draw:" + round.round(), "DRAW", WinModes.drawText(event.name(), win, round, nameOf));
        return round;
    }

    /** Pending awards from the event's prize list for the winners, place 1 first. No prize list: nothing to record. */
    private void recordAwards(Event event, List<String> winners) {
        if (service.prizes(event.id()).isEmpty()) return;
        service.recordAwards(event.guildId(), event.id(), winners);
    }

    /**
     * The places list for recording one rerolled place: null everywhere but {@code place}, so the other places' awards (recorded at the draw, perhaps for a
     * team whose roster has changed since) are never added to.
     */
    static List<String> onlyPlace(int place, String subject) {
        List<String> places = new ArrayList<>(Collections.nCopies(place, (String) null));
        places.set(place - 1, subject);
        return places;
    }

    /** Players still in the event (not on the waitlist) and teams that still exist. */
    private Predicate<String> stillIn(Event event) {
        var participants = repository.participants(event.id());
        var teams = repository.teams(event.id());
        return subject -> {
            long id = Long.parseLong(subject.substring(2));
            if (subject.startsWith("t:")) return teams.stream().anyMatch(t -> t.id() == id);
            return participants.stream().anyMatch(p -> p.userId() == id && !p.waiting());
        };
    }

    /** Posts the result where the event's updates go (its chosen channel, its announcements, else its board), once. A bot that is not live posts nothing. */
    private void announce(Event event, String key, String kind, String text) {
        if (AutoPostRouter.diverting()) {
            log.info("Event {}: draw result not posted, this bot is not live", event.id());
            return;
        }
        Long channel = updates.channelFor(event, BoardUpdates.of(DataObject.fromJson(event.config())));
        if (channel == null) {
            log.info("Event {}: draw made but there is nowhere to post it (no announcements or board channel)", event.id());
            return;
        }
        if (!posts.claim(event.id(), key, kind, text)) return;
        outbox.enqueue(EventUpdatePostJob.KIND, event.guildId(), 0L, "event-update:" + event.id() + ":" + key, EventUpdatePostJob.payload(event.id(), channel, key, text));
    }

    public List<WinModes.Round> rounds(long eventId) {
        return draws.rounds(eventId);
    }

    private Function<String, String> names(Guild guild, long eventId) {
        var teams = repository.teams(eventId);
        return subject -> EventBoardPanels.nameOf(guild, subject, teams);
    }

    private Guild guild(long guildId) {
        JDA client = jda;
        return client == null ? null : Shards.guild(client, guildId);
    }
}
