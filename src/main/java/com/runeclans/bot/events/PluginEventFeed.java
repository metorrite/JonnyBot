package com.runeclans.bot.events;

import com.runeclans.bot.events.EventRepository.Event;
import com.runeclans.bot.events.EventRules.ObjectiveSpec;
import com.runeclans.bot.events.EventRules.Source;
import com.runeclans.bot.plugin.PluginEventSink;
import com.runeclans.bot.plugin.PluginPayload;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;

/**
 * Feeds what the plugin reports into the events a player is in. For each newly stored plugin event it looks at the player's running events
 * (in every server they are in), asks {@link PluginObjectiveMatcher} what it counts towards, and records that through the engine's own
 * {@link EventService#recordProgress}, so every rule (running, in the event, not on the waitlist, evidence allowed, prerequisites, repeat limit)
 * applies exactly as for any other source. Each progress entry is keyed by the plugin event, so a re-sent batch can't count twice, and only
 * things that happened while the event was running count.
 */
@BService
public class PluginEventFeed implements PluginEventSink {
    private static final Logger log = LoggerFactory.getLogger(PluginEventFeed.class);

    private final EventRepository repository;
    private final EventService service;
    private final BingoTiles tiles;

    public PluginEventFeed(EventRepository repository, EventService service, BingoTiles tiles) {
        this.repository = repository;
        this.service = service;
        this.tiles = tiles;
    }

    @Override
    public void accepted(long userId, String rsn, List<PluginPayload.Event> events) {
        try {
            if (events.isEmpty()) return;
            List<Event> running = repository.runningEventsForUser(userId);
            if (running.isEmpty()) return;
            for (Event event : running) {
                List<ObjectiveSpec> objectives = repository.objectives(event.id());
                for (PluginPayload.Event reported : events) {
                    if (!during(event, reported.at())) continue;
                    DataObject data = dataOf(reported);
                    for (var hit : PluginObjectiveMatcher.match(ClanVsClan.game(event), event.config(), objectives, reported.type(), reported.sourceName(), data, tiles::resolve)) {
                        record(event, userId, rsn, hit, reported);
                    }
                }
            }
        } catch (RuntimeException e) {
            log.warn("Couldn't feed plugin events into events for user {}", userId, e);
        }
    }

    private void record(Event event, long userId, String rsn, PluginObjectiveMatcher.Hit hit, PluginPayload.Event reported) {
        try {
            service.recordProgress(event.guildId(), event.id(), hit.objectiveKey(), userId, hit.amount(), Source.AUTO, "plugin:" + userId + ":" + reported.key(), null,
                    "Plugin: " + reported.type().name().toLowerCase().replace('_', ' ') + " (" + rsn + ")");
        } catch (EventProblem problem) {
            // already counted, complete, locked behind a prerequisite, on the waitlist...: the engine says why, and none of it is an error here
            log.debug("Plugin event {} not counted for event {}: {}", reported.key(), event.id(), problem.getMessage());
        }
    }

    /** Whether something that happened at {@code at} falls inside the event's own start and end, when it has them. */
    static boolean during(Event event, Instant at) {
        if (at == null) return false;
        if (event.startsAt() != null && at.isBefore(event.startsAt())) return false;
        return event.endsAt() == null || !at.isAfter(event.endsAt());
    }

    private static DataObject dataOf(PluginPayload.Event reported) {
        try {
            return DataObject.fromJson(reported.dataJson());
        } catch (RuntimeException e) {
            return DataObject.empty();
        }
    }
}
