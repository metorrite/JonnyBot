package com.younglings.bot.events;

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
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor((ThreadFactory) runnable -> {
        Thread thread = new Thread(runnable, "event-signups");
        thread.setDaemon(true);
        return thread;
    });

    public EventSignups(EventRepository repository, EventService service, SignupService signups) {
        this.repository = repository;
        this.service = service;
        this.signups = signups;
    }

    @BEventListener
    public void onJdaReady(InjectedJDAEvent event) {
        JDA jda = event.getJda();
        executor.scheduleWithFixedDelay(() -> sweep(jda), 60, 60, TimeUnit.SECONDS);
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
            pause(event.signupId());
            added = sync(guild, eventId);
        }
        return report(eventId, added);
    }

    /** Opens signups again: the event takes players' own joins and the Discord signup is switched back on. */
    public void reopenSignups(Guild guild, long eventId) {
        service.reopenSignups(guild.getIdLong(), eventId);
        Event event = service.event(guild.getIdLong(), eventId);
        if (event.signupId() != null && !signups.isSignupActive(event.signupId())) signups.togglePause(event.signupId());
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
        for (SignupEntry entry : signups.getEntries(event.signupId())) {
            if (already.contains(entry.userId())) continue;
            try {
                service.join(guild.getIdLong(), eventId, guild.getIdLong(), entry.userId(), null, true, null);
                added++;
            } catch (EventProblem e) {
                log.debug("Signup entry {} wasn't added to event {}: {}", entry.userId(), eventId, e.getMessage());
                // remember why, so staff can see it on the dashboard, and tell the player once
                if (repository.recordRefusal(eventId, entry.userId(), e.getMessage())) tellRefused(guild, event, entry.userId(), e.getMessage());
            }
        }
        return added;
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
            pause(event.signupId());
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

    private void pause(long signupId) {
        if (signups.isSignupActive(signupId)) signups.togglePause(signupId);
    }

    void sweep(JDA jda) {
        try {
            closePastDeadline(jda);
            for (Event event : repository.eventsWithSignup()) {
                Guild guild = jda.getGuildById(event.guildId());
                if (guild == null) continue;
                try {
                    switch (event.status()) {
                        case SIGNUPS -> {
                            // an attached signup is kept in step until the event closes it, which the deadline sweep above does
                            if (!repository.setup(event.id()).signupsClosed()) sync(guild, event.id());
                        }
                        case RUNNING, SCORING -> pause(event.signupId());
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
