package com.runeclans.bot.events;

import com.runeclans.bot.events.EventRules.ObjectiveSpec;
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

    /**
     * Whether a player needs a linked RuneScape name to take part, because the event reads their game data (XP, kill counts) rather than proof they send.
     * The events hub says so before someone joins (T-216). The default is no.
     */
    default boolean needsRsn() {
        return false;
    }

    /**
     * The options this template adds to the ones every event has: what each is called, where it is kept in the settings, its standard value and whether it is
     * required (see {@link EventOptions}). The quick, standard and guided setups are drawn from this, so a template that lists its options here gets them in
     * all three with no other edit. The default is none.
     */
    default List<EventOptions.Option> options() {
        return List.of();
    }

    /** What is wrong with these template settings, in words, or an empty list. {@code config} is never null. */
    List<String> configProblems(DataObject config);

    /** The objectives a new event of this kind starts with (may be empty). The service still validates them like any others. */
    List<ObjectiveSpec> starterObjectives(DataObject config);

    // ---------- for templates that run the teams themselves (clan vs clan, T-222); every default leaves the engine as it is ----------

    /** How every event of this kind is scored, whatever was asked for, or null to use what was asked for. */
    default EventRules.ScoringMode fixedScoringMode() {
        return null;
    }

    /** Whether this template makes and fills the teams itself, so organisers don't build them. */
    default boolean managesTeams() {
        return false;
    }

    /**
     * The team a joining player goes on, or null to leave teams to organisers. May refuse the player with an {@link EventProblem}. {@code staff} is true when an
     * organiser adds them.
     */
    default Long teamOnJoin(EventRepository.Event event, long userGuildId, long userId, boolean staff) {
        return null;
    }

    /**
     * Brings the teams this template keeps in step with the event (one per clan): called when co-hosts change, and with {@code recheck} at Close signups, which
     * also puts each player on the team they belong to now.
     */
    default void syncTeams(EventRepository.Event event, boolean recheck) {
    }

    /** The standings worked out this template's own way from the usual team standings, or null to keep the usual ones. */
    default List<EventScoring.Standing> standings(EventRepository.Event event, List<ObjectiveSpec> objectives, List<EventRepository.Progress> progress,
                                                  List<EventScoring.Standing> usual) {
        return null;
    }

    /** Adds this template's own steps and warnings to the setup checklist. */
    default void checklist(EventRepository.Event event, List<EventPrep.Step> steps, List<String> warnings) {
    }
}
