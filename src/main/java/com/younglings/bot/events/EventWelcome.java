package com.younglings.bot.events;

import net.dv8tion.jda.api.utils.data.DataObject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The first message JonnyBot posts in an event channel it has just made (T-209): one text per kind of channel, in {@code config.channels.welcome}
 * ({@code announcements}, {@code general}, {@code teamChannel}, {@code playerChannel}). A kind with no text gets no message, and no event has any until an
 * organiser writes one, so the feature is off by default. Posted once, when the channel is made; never into a channel an organiser chose. Pure.
 */
public final class EventWelcome {
    public static final int MAX_TEXT = 1500;
    /** The kinds of channel that can have a welcome message. */
    public static final List<EventSpace.Kind> KINDS = List.of(EventSpace.Kind.ANNOUNCEMENTS, EventSpace.Kind.GENERAL, EventSpace.Kind.TEAM, EventSpace.Kind.PLAYER);

    private EventWelcome() {}

    /** The texts by kind key, only the ones written. */
    public static Map<String, String> of(DataObject config) {
        Map<String, String> texts = new LinkedHashMap<>();
        try {
            if (config == null || !config.hasKey("channels") || config.isNull("channels")) return texts;
            DataObject c = config.getObject("channels");
            if (!c.hasKey("welcome") || c.isNull("welcome")) return texts;
            DataObject w = c.getObject("welcome");
            for (EventSpace.Kind kind : KINDS) {
                if (!w.hasKey(kind.key()) || w.isNull(kind.key())) continue;
                String text = w.getString(kind.key()).strip();
                if (!text.isEmpty()) texts.put(kind.key(), text.length() > MAX_TEXT ? text.substring(0, MAX_TEXT) : text);
            }
        } catch (RuntimeException e) {
            return new LinkedHashMap<>();
        }
        return texts;
    }

    /** What is wrong with the welcome texts in {@code config}, in words. */
    public static List<String> problems(DataObject config) {
        List<String> problems = new ArrayList<>();
        try {
            if (config == null || !config.hasKey("channels") || config.isNull("channels")) return problems;
            DataObject c = config.getObject("channels");
            if (!c.hasKey("welcome") || c.isNull("welcome")) return problems;
            DataObject w = c.getObject("welcome");
            for (String key : w.keys()) {
                if (KINDS.stream().noneMatch(k -> k.key().equals(key))) problems.add("Welcome messages can only go in the announcements, general, team and player channels.");
                else if (!w.isNull(key) && !(w.get(key) instanceof String)) problems.add("A welcome message has to be text.");
                else if (!w.isNull(key) && w.getString(key).strip().length() > MAX_TEXT) problems.add("A welcome message can be at most " + MAX_TEXT + " characters.");
            }
        } catch (RuntimeException e) {
            problems.add("The welcome messages couldn't be read.");
        }
        return problems.stream().distinct().toList();
    }

    /**
     * The message with its words filled in: {@code {event}}, {@code {team}}, {@code {player}}, {@code {start}} (a Discord timestamp each reader sees in their own
     * time, or "not set yet") and {@code {rules-link}} (the event's page on the website, or nothing when the site address isn't set).
     */
    public static String render(String text, String event, String team, String player, Instant start, String rulesLink) {
        String out = text.replace("{event}", event == null ? "" : event)
                .replace("{team}", team == null ? "" : team)
                .replace("{player}", player == null ? "" : player)
                .replace("{start}", start == null ? "not set yet" : "<t:" + start.getEpochSecond() + ":F>")
                .replace("{rules-link}", rulesLink == null ? "" : rulesLink);
        return out.length() > 2000 ? out.substring(0, 2000) : out;
    }
}
