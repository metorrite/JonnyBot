package com.younglings.bot.events;

import io.github.freya022.botcommands.api.core.service.annotations.BService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * A tiny announcement board for things that happen to events, so the parts that talk to Discord (the live bingo board, a player's "your submission
 * was approved" message) can react without the engine knowing about Discord. The engine publishes; whoever cares subscribes. A subscriber that
 * throws never affects the change that was announced.
 */
@BService
public class EventChanges {
    private static final Logger log = LoggerFactory.getLogger(EventChanges.class);

    public enum Kind {
        /** Someone's progress changed (a tile was completed, taken back, or tracked). */
        PROGRESS,
        /** The event moved to another stage. */
        STAGE,
        /** The event's settings, objectives or board changed. */
        SETUP,
        /** A player's proof was approved or denied. */
        CLAIM_DECIDED,
        /** The roster changed: someone joined or left, signups were closed or reopened, the roster was confirmed, or a player's verification was decided. */
        ROSTER,
        /** The teams changed: one was added, renamed or removed, a player was moved, a captain was named, or the teams were made final or taken back. */
        TEAMS
    }

    /** {@code claimId}, {@code userId}, {@code approved} and {@code note} only mean something for {@link Kind#CLAIM_DECIDED}. */
    public record Change(Kind kind, long guildId, long eventId, long claimId, long userId, boolean approved, String note) {}

    private final List<Consumer<Change>> subscribers = new CopyOnWriteArrayList<>();

    public void subscribe(Consumer<Change> subscriber) {
        subscribers.add(subscriber);
    }

    public void publish(Change change) {
        for (Consumer<Change> subscriber : subscribers) {
            try {
                subscriber.accept(change);
            } catch (RuntimeException e) {
                log.warn("An event change subscriber failed for event {}", change.eventId(), e);
            }
        }
    }

    public void publish(Kind kind, long guildId, long eventId) {
        publish(new Change(kind, guildId, eventId, 0, 0, false, ""));
    }
}
