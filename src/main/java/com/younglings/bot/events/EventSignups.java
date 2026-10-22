package com.younglings.bot.events;

import com.younglings.bot.alerts.AutoPostRouter;
import com.younglings.bot.commands.signup.SignupEntry;
import com.younglings.bot.commands.signup.SignupService;
import com.younglings.bot.events.EventRepository.Event;
import com.younglings.bot.events.EventRules.Status;
import io.github.freya022.botcommands.api.core.annotations.BEventListener;
import io.github.freya022.botcommands.api.core.events.InjectedJDAEvent;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * The signup attached to an event. When an event opens signups (and has a signup channel and a staff channel chosen) the bot posts a normal
 * queue signup for it. Players who sign up there become the event's players (added the way staff add players, so the team policy and the
 * waitlist still apply). The signup closes by itself at the event's signup deadline and when the event starts, and is removed when the
 * event is done or cancelled. Staff can still add late entries by hand. A sweep every minute keeps it in step.
 * <p>
 * Closing signups ({@link #closeSignups}) is the organiser's one action for it: the event stops taking players' own joins, the Discord signup is
 * paused, and the roster is synced one last time, in that order, so nobody can slip in between.
 * <p>
 * The roster and the Discord signup are kept the same both ways until the event starts: a player who signs up in Discord is added to the roster, a player
 * who is taken off the signup in Discord is taken off the roster (and off their team), a player who joins on the website gets an entry in the signup, and a
 * player removed on the website loses their entry. Only players who came from the signup (or joined on their own) are mirrored: staff-added players have
 * no entry to follow. Someone barred from the event is kept out of the roster and their entry is taken off the signup.
 */
@BService
public class EventSignups {
    private static final Logger log = LoggerFactory.getLogger(EventSignups.class);

    /** Where an event's signup stands, for the dashboard. */
    public record State(boolean attached, boolean active, int entries, boolean channelsChosen) {}

    /** What closing signups did: how many the last sync added, where the roster stands, and who the event's rules turned away (and why). */
    public record Closed(int added, int active, int waiting, List<EventRepository.Refusal> refused) {}

    private final EventRepository repository;
    private final EventService service;
    private final SignupService signups;
    private final EventChanges changes;
    /** How often the signup and the roster are compared. */
    static final int SWEEP_SECONDS = 30;

    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor((ThreadFactory) runnable -> {
        Thread thread = new Thread(runnable, "event-signups");
        thread.setDaemon(true);
        return thread;
    });
    private volatile JDA jda;

    public EventSignups(EventRepository repository, EventService service, SignupService signups, EventChanges changes) {
        this.repository = repository;
        this.service = service;
        this.signups = signups;
        this.changes = changes;
        changes.subscribe(change -> {
            if (change.kind() == EventChanges.Kind.PLAYER_JOINED || change.kind() == EventChanges.Kind.PLAYER_LEFT) executor.execute(() -> mirror(change));
        });
    }

    @BEventListener
    public void onJdaReady(InjectedJDAEvent event) {
        JDA client = event.getJda();
        this.jda = client;
        executor.scheduleWithFixedDelay(() -> sweep(client), SWEEP_SECONDS, SWEEP_SECONDS, TimeUnit.SECONDS);
    }

    /** Copies a player who joined or left on the website (or with a command) onto the Discord signup, so the two lists stay the same. */
    void mirror(EventChanges.Change change) {
        JDA client = jda;
        if (client == null) return;
        try {
            Guild guild = client.getGuildById(change.guildId());
            Event event = repository.event(change.guildId(), change.eventId()).orElse(null);
            if (guild == null || event == null || event.signupId() == null || signups.getSessionById(event.signupId()) == null) return;
            long signupId = event.signupId();
            if (change.kind() == EventChanges.Kind.PLAYER_LEFT) {
                if (signups.removeUser(guild, signupId, change.userId())) signups.updateMessages(guild, signupId);
                return;
            }
            if (signups.getEntries(signupId).stream().anyMatch(e -> e.userId() == change.userId())) {
                repository.setSource(event.id(), change.userId(), EventRepository.RosterSource.SIGNUP);
                return;
            }
            Member member = guild.getMemberById(change.userId());
            String name = member == null ? "Website signup" : member.getEffectiveName();
            if (signups.addManualUser(guild, signupId, change.userId(), name, null, change.userId())) {
                repository.setSource(event.id(), change.userId(), EventRepository.RosterSource.SIGNUP);
                signups.updateMessages(guild, signupId);
            }
        } catch (RuntimeException e) {
            log.warn("Could not mirror a player of event {} onto its signup", change.eventId(), e);
        }
    }

    private static EventSettings settingsOf(Event event) {
        try {
            return EventSettings.parse(DataObject.fromJson(event.config()));
        } catch (RuntimeException e) {
            return EventSettings.defaults();
        }
    }

    public State stateOf(Event event) {
        EventSettings settings = settingsOf(event);
        boolean chosen = settings.signupChannelId() != null && settings.signupAdminChannelId() != null;
        if (event.signupId() == null) return new State(false, false, 0, chosen);
        return new State(true, signups.isSignupActive(event.signupId()), signups.getEntries(event.signupId()).size(), chosen);
    }

    /** Posts the event's signup in its chosen channels. Only while the event is taking signups, and only once. Returns the signup's id. */
    public long open(Guild guild, long eventId, long actorId) {
        Event event = service.event(guild.getIdLong(), eventId);
        if (event.status() != Status.SIGNUPS) throw new EventProblem(409, "Open the event's signups first.");
        if (event.signupId() != null) throw new EventProblem(409, "This event already has a signup.");
        EventSettings settings = settingsOf(event);
        if (settings.signupChannelId() == null || settings.signupAdminChannelId() == null) throw new EventProblem(409, "Choose a signup channel and a staff channel in the event's settings first.");
        TextChannel panel = guild.getTextChannelById(settings.signupChannelId());
        TextChannel staff = guild.getTextChannelById(settings.signupAdminChannelId());
        if (panel == null || staff == null) throw new EventProblem(400, "One of the signup channels no longer exists.");
        for (TextChannel channel : List.of(panel, staff)) {
            if (!guild.getSelfMember().hasPermission(channel, Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND)) {
                throw new EventProblem(400, "JonnyBot needs to see and send messages in #" + channel.getName() + ".");
            }
        }
        long signupId = signups.createQueueSignup(guild, panel, staff, event.name(), event.description().isBlank() ? null : event.description(), null, actorId);
        repository.setSignup(eventId, signupId);
        log.info("Event {} opened signup {} in guild {}", eventId, signupId, guild.getIdLong());
        return signupId;
    }

    /**
     * Closes the event's signups: marks them closed first (so the website and {@code /events join} refuse new self-signups), pauses the Discord signup,
     * then adds whoever had signed up one final time. {@code actorId} is who closed them (the bot's own id when the deadline did it).
     */
    public Closed closeSignups(Guild guild, long eventId, long actorId) {
        service.closeSignups(guild.getIdLong(), eventId, actorId);
        Event event = service.event(guild.getIdLong(), eventId);
        int added = 0;
        if (event.signupId() != null) {
            pause(guild, event.signupId());
            added = sync(guild, eventId);
        }
        return report(eventId, added);
    }

    /** Opens signups again: the event takes players' own joins and the Discord signup is switched back on. */
    public void reopenSignups(Guild guild, long eventId) {
        service.reopenSignups(guild.getIdLong(), eventId);
        Event event = service.event(guild.getIdLong(), eventId);
        if (event.signupId() != null && !signups.isSignupActive(event.signupId())) {
            signups.togglePause(event.signupId());
            signups.updateMessages(guild, event.signupId());
        }
    }

    private Closed report(long eventId, int added) {
        var players = repository.participants(eventId);
        int waiting = (int) players.stream().filter(EventRepository.Participant::waiting).count();
        return new Closed(added, players.size() - waiting, waiting, repository.refusals(eventId));
    }

    /** Adds everyone who signed up to the event's players. Returns how many were added. Someone the event's rules turn away is left out. */
    public int sync(Guild guild, long eventId) {
        Event event = service.event(guild.getIdLong(), eventId);
        if (event.signupId() == null || !EventRules.acceptsJoins(event.status())) return 0;
        Set<Long> already = new HashSet<>();
        repository.participants(eventId).forEach(p -> already.add(p.userId()));
        int added = 0;
        List<SignupEntry> entries = signups.getEntries(event.signupId());
        for (SignupEntry entry : entries) {
            var barred = repository.ban(eventId, entry.userId());
            if (barred.isPresent()) {
                // barred from this event: off the signup, and told why (once)
                if (signups.removeUser(guild, event.signupId(), entry.userId())) signups.updateMessages(guild, event.signupId());
                if (repository.recordRefusal(eventId, entry.userId(), barred.get().reason())) tellRefused(guild, event, entry.userId(), "you were barred from it: " + barred.get().reason());
                continue;
            }
            if (already.contains(entry.userId())) {
                // already on the roster; a player who is on the signup is one the signup now follows
                repository.participant(eventId, entry.userId()).filter(p -> p.source() == EventRepository.RosterSource.SELF)
                        .ifPresent(p -> repository.setSource(eventId, p.userId(), EventRepository.RosterSource.SIGNUP));
                continue;
            }
            try {
                // staff-level so the deadline doesn't refuse a signup made in time, but the player chose to sign up, so their server's seat quota applies (T-220)
                service.join(guild.getIdLong(), eventId, guild.getIdLong(), entry.userId(), null, true, null, true);
                repository.setSource(eventId, entry.userId(), EventRepository.RosterSource.SIGNUP);
                added++;
            } catch (EventProblem e) {
                log.debug("Signup entry {} wasn't added to event {}: {}", entry.userId(), eventId, e.getMessage());
                // remember why, so staff can see it on the dashboard, and tell the player once
                if (repository.recordRefusal(eventId, entry.userId(), e.getMessage())) tellRefused(guild, event, entry.userId(), e.getMessage());
            }
        }
        prune(guild, event, entries);
        return added;
    }

    /**
     * Takes players off the roster (and their team) who were on the Discord signup and are not any more, because staff removed them there. Only while the
     * event is still taking signups, only players who came from the signup, and only when the signup is really there to read: a signup that can't be
     * found would look empty and take everyone off.
     */
    private void prune(Guild guild, Event event, List<SignupEntry> entries) {
        if (event.signupId() == null || event.status() != Status.SIGNUPS || signups.getSessionById(event.signupId()) == null) return;
        Set<Long> onSignup = new HashSet<>();
        entries.forEach(e -> onSignup.add(e.userId()));
        for (var player : repository.participants(event.id())) {
            if (player.source() != EventRepository.RosterSource.SIGNUP || onSignup.contains(player.userId())) continue;
            try {
                service.leave(guild.getIdLong(), event.id(), player.userId());
                log.info("{} was taken off the roster of event {}: they are no longer on its Discord signup", player.userId(), event.id());
            } catch (EventProblem e) {
                // for instance a paid buy-in, which has to be refunded before anyone is removed: leave them and tell staff on the dashboard
                log.info("{} stays on the roster of event {} although they left the signup: {}", player.userId(), event.id(), e.getMessage());
                repository.recordRefusal(event.id(), player.userId(), "They were taken off the Discord signup but couldn't be taken off the roster: " + e.getMessage());
            }
        }
    }

    /** Takes players off the roster who left the Discord signup, without adding anyone: for an event whose signups are closed but not started. */
    public void syncRemovals(Guild guild, long eventId) {
        Event event = service.event(guild.getIdLong(), eventId);
        if (event.signupId() == null || event.status() != Status.SIGNUPS) return;
        prune(guild, event, signups.getEntries(event.signupId()));
    }

    /** DMs a player whose signup was turned away, with the reason. A closed DM is fine: the dashboard still shows it to staff. */
    private void tellRefused(Guild guild, Event event, long userId, String reason) {
        String message = "You signed up for **" + event.name() + "** but couldn't be added: " + reason + " If that looks wrong, ask an organiser to add you.";
        guild.getJDA().retrieveUserById(userId).flatMap(user -> user.openPrivateChannel()).flatMap(dm -> dm.sendMessage(message))
                .queue(ok -> { }, error -> log.debug("Could not DM {} about a refused signup: {}", userId, error.getMessage()));
    }

    /** Called when an event changes stage: starting it closes the signup, finishing or cancelling it removes the signup. */
    public void onStage(Guild guild, long eventId, Status to) {
        Event event = service.event(guild.getIdLong(), eventId);
        if (event.signupId() == null) return;
        if (to == Status.RUNNING || to == Status.SCORING) {
            sync(guild, eventId);
            pause(guild, event.signupId());
        } else if (to == Status.DONE || to == Status.CANCELLED) {
            signups.deleteSignup(guild, event.signupId());
            repository.setSignup(eventId, null);
        }
    }

    /** Closes signups at their deadline, for every event that has one (with or without a Discord signup), as an organiser pressing the button would. */
    private void closePastDeadline(JDA jda) {
        for (Event event : repository.eventsInStatus(Status.SIGNUPS)) {
            Instant closes = settingsOf(event).signupClosesAt();
            if (closes == null || Instant.now().isBefore(closes) || repository.setup(event.id()).signupsClosed()) continue;
            Guild guild = jda.getGuildById(event.guildId());
            if (guild == null) continue;
            try {
                Closed closed = closeSignups(guild, event.id(), jda.getSelfUser().getIdLong());
                log.info("Signups of event {} closed at their deadline: {} added by the last sync, {} on the roster", event.id(), closed.added(), closed.active());
            } catch (EventProblem e) {
                log.debug("Signups of event {} weren't closed at the deadline: {}", event.id(), e.getMessage());
            } catch (RuntimeException e) {
                log.warn("Closing the signups of event {} at the deadline failed", event.id(), e);
            }
        }
    }

    /**
     * Seat quotas (T-220): once an event's quota places are released (its release time has passed, or signups closed), free places go to whoever is
     * waiting, in the event's queue order. Events without quotas, or not yet released, are left alone.
     */
    void releaseQuotas() {
        Instant now = Instant.now();
        for (Event event : repository.eventsInStatus(Status.SIGNUPS)) {
            if (event.maxParticipants() <= 0) continue;
            try {
                QueueRules.Quotas quotas = service.quotas(event, settingsOf(event), now);
                if (quotas == QueueRules.Quotas.NONE || !quotas.released()) continue;
                int moved = service.fillSeats(event);
                if (moved > 0) {
                    log.info("Released seat quota places of event {}: {} waiting player(s) moved in", event.id(), moved);
                    changes.publish(EventChanges.Kind.ROSTER, event.guildId(), event.id());
                }
            } catch (RuntimeException e) {
                log.warn("Releasing the seat quotas of event {} failed", event.id(), e);
            }
        }
    }

    /** Pauses the Discord signup and redraws its panels, so they say PAUSED at once instead of when someone next clicks one. */
    private void pause(Guild guild, long signupId) {
        if (!signups.isSignupActive(signupId)) return;
        signups.togglePause(signupId);
        signups.updateMessages(guild, signupId);
    }

    void sweep(JDA jda) {
        if (AutoPostRouter.diverting()) return;
        try {
            closePastDeadline(jda);
            releaseQuotas();
            for (Event event : repository.eventsWithSignup()) {
                Guild guild = jda.getGuildById(event.guildId());
                if (guild == null) continue;
                try {
                    switch (event.status()) {
                        case SIGNUPS -> {
                            // an attached signup is kept in step until the event closes it, which the deadline sweep above does
                            // open: both ways; closed: the Discord signup is paused, but staff can still take people off it
                            if (!repository.setup(event.id()).signupsClosed()) sync(guild, event.id());
                            else syncRemovals(guild, event.id());
                        }
                        case RUNNING, SCORING -> pause(guild, event.signupId());
                        case DONE, CANCELLED -> onStage(guild, event.id(), event.status());
                        default -> { }
                    }
                } catch (RuntimeException e) {
                    log.warn("Event signup sweep failed for event {}", event.id(), e);
                }
            }
        } catch (RuntimeException e) {
            log.error("Event signup sweep failed", e);
        }
    }
}
