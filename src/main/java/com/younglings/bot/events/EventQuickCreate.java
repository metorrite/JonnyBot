package com.younglings.bot.events;

import com.younglings.bot.events.EventRules.Scope;
import com.younglings.bot.events.EventRules.ScoringMode;
import com.younglings.bot.events.EventService.EventInput;
import com.younglings.bot.runescape.RuneScapeSkillCatalog;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

/**
 * Turns the few things asked for by {@code /events create} (the kind of event, a name, how long it runs, and one optional detail) into a full
 * event with the template's usual settings, so a leader can start something from Discord and fine-tune it on the dashboard afterwards.
 * Pure, so it can be tested. {@link IllegalArgumentException}s carry a message that is safe to show.
 */
public final class EventQuickCreate {
    public static final int MAX_DAYS = 90;
    public static final int MAX_START_HOURS = 24 * 30;

    /** The kinds of event that can be made this way, with what the optional {@code detail} means for each. */
    public enum Kind {
        BINGO(BingoTemplate.KEY, "Bingo", "Board size, 3 to 7 (default 5)"),
        COMPETITION(CompetitionTemplate.KEY, "XP competition", "Skill name, or overall (default overall)"),
        BOSS_NIGHT(BossNightTemplate.KEY, "Boss night", "The boss (required)"),
        SCAVENGER_HUNT(ScavengerHuntTemplate.KEY, "Scavenger hunt", "How many items to find, 3 to 30 (default 8)");

        public final String template;
        public final String label;
        public final String detailHelp;

        Kind(String template, String label, String detailHelp) {
            this.template = template;
            this.label = label;
            this.detailHelp = detailHelp;
        }
    }

    public record Plan(EventInput input, String summary) {}

    private EventQuickCreate() {}

    /** The skill a name stands for ({@code overall} included), or {@code null}. Case and spaces don't matter. */
    static Integer skillByName(String text) {
        String wanted = text.strip().toLowerCase(Locale.ROOT);
        if (wanted.equals("overall") || wanted.equals("total") || wanted.isEmpty()) return CompetitionTemplate.OVERALL;
        for (int id = 0; id < RuneScapeSkillCatalog.skillCount(); id++) {
            if (RuneScapeSkillCatalog.nameFor(id).toLowerCase(Locale.ROOT).equals(wanted)) return id;
        }
        return null;
    }

    private static int whole(String detail, int fallback, int min, int max, String what) {
        if (detail == null || detail.isBlank()) return fallback;
        try {
            int value = Integer.parseInt(detail.strip());
            if (value < min || value > max) throw new IllegalArgumentException(what + " has to be between " + min + " and " + max + ".");
            return value;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(what + " has to be a number between " + min + " and " + max + ".");
        }
    }

    /**
     * @param days         how long the event runs once it starts
     * @param startInHours when it is planned to start, counted from {@code now}
     * @param detail       the kind's one optional setting; see {@link Kind#detailHelp}
     */
    public static Plan plan(Kind kind, String name, int days, int startInHours, String detail, Instant now) {
        if (days < 1 || days > MAX_DAYS) throw new IllegalArgumentException("An event can run for 1 to " + MAX_DAYS + " days.");
        if (startInHours < 0 || startInHours > MAX_START_HOURS) throw new IllegalArgumentException("It can start from now up to " + MAX_START_HOURS + " hours away.");
        DataObject config = DataObject.empty();
        String about;
        switch (kind) {
            case BINGO -> {
                int size = whole(detail, BingoTemplate.DEFAULT_SIZE, 3, 7, "The board size");
                config.put("board", DataObject.empty().put("size", size));
                about = size + "x" + size + " board";
            }
            case COMPETITION -> {
                Integer skill = skillByName(detail == null ? "" : detail);
                if (skill == null) throw new IllegalArgumentException("I don't know the skill \"" + detail.strip() + "\". Use a skill name like Slayer, or overall.");
                config.put("skillId", skill);
                about = CompetitionTemplate.skillName(skill) + " XP";
            }
            case BOSS_NIGHT -> {
                String boss = detail == null ? "" : detail.strip();
                if (boss.isEmpty()) throw new IllegalArgumentException("Say which boss the night is for.");
                config.put("boss", boss);
                about = boss;
            }
            case SCAVENGER_HUNT -> {
                int items = whole(detail, 8, ScavengerHuntTemplate.MIN_STEPS, ScavengerHuntTemplate.MAX_STEPS, "The number of items");
                config.put("steps", items);
                about = items + " items to find";
            }
            default -> throw new IllegalArgumentException("That kind of event can't be made here.");
        }
        Instant starts = now.plus(Duration.ofHours(startInHours));
        Instant ends = starts.plus(Duration.ofDays(days));
        EventInput input = new EventInput(name == null ? "" : name.strip(), "", kind.template, Scope.PRIVATE, ScoringMode.INDIVIDUAL, starts, ends, 0, config);
        return new Plan(input, kind.label + " · " + about + " · " + days + (days == 1 ? " day" : " days"));
    }
}
