package com.younglings.bot.events;

import com.younglings.bot.events.EventRules.ObjectiveSpec;
import io.github.freya022.botcommands.api.core.service.annotations.InterfacedService;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.util.List;
import java.util.Set;

/**
 * A recipe for one kind of event (bingo, competition, ...): the settings it understands, the objectives a new event of this kind starts
 * with, and how its settings are checked. Everything an event does after that (joining, progress, claims, standings) is the same engine for
 * every template. Add a template by writing a service that implements this; the registry picks it up with no other edit.
 */
@InterfacedService(acceptMultiple = true)
public interface EventTemplate {
    /** The template's stable key, stored with every event of this kind (lower case words and dashes). */
    String key();

    /** The name shown when choosing a template. */
    String label();

    /** One or two sentences on what this kind of event is. */
    String description();

    /**
     * Gives a new event its settings before they are checked and stored: a template can add what it generates (a bingo board and its bonuses).
     * The default changes nothing.
     */
    default DataObject prepare(DataObject config) {
        return config;
    }

    /**
     * The settings this template builds the event's objectives from. They are chosen when the event is created and kept as they are when the
     * event's other settings are saved later, so a save that leaves one out can't strip a bingo board or leave the objectives out of step.
     */
    default Set<String> fixedSettings() {
        return Set.of();
    }

    /** What is wrong with these template settings, in words, or an empty list. {@code config} is never null. */
    List<String> configProblems(DataObject config);

    /** The objectives a new event of this kind starts with (may be empty). The service still validates them like any others. */
    List<ObjectiveSpec> starterObjectives(DataObject config);
}
