package com.runeclans.bot.fun;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.random.RandomGenerator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The pure parts of the fun commands (dice, 8-ball, rock-paper-scissors, choose), kept apart from Discord so they can be tested. */
public final class FunLogic {
    private FunLogic() {}

    public static final int MAX_DICE = 20;
    public static final int MAX_SIDES = 1000;
    public static final int MAX_MODIFIER = 1000;
    public static final int MAX_CHOICES = 20;

    private static final Pattern DICE = Pattern.compile("^(\\d{0,2})d(\\d{1,4})\\s*(?:([+-])\\s*(\\d{1,4}))?$");

    /** One roll: each die, the modifier and the total. */
    public record Roll(int dice, int sides, int modifier, List<Integer> results, int total) {
        public String describe() {
            return dice + "d" + sides + (modifier == 0 ? "" : (modifier > 0 ? "+" : "-") + Math.abs(modifier));
        }
    }

    /**
     * Rolls dice written as {@code 2d6}, {@code d20}, {@code 3d8+2} or {@code 4d4-1}; a bare number such as {@code 20} is one die with that
     * many sides. Empty if the text isn't dice notation or asks for too many dice or sides.
     */
    public static Optional<Roll> roll(String notation, RandomGenerator random) {
        String text = notation.strip().toLowerCase(Locale.ROOT);
        if (text.matches("\\d{1,4}")) text = "d" + text;
        Matcher m = DICE.matcher(text);
        if (!m.matches()) return Optional.empty();
        int dice = m.group(1).isEmpty() ? 1 : Integer.parseInt(m.group(1));
        int sides = Integer.parseInt(m.group(2));
        int modifier = m.group(3) == null ? 0 : Integer.parseInt(m.group(4)) * (m.group(3).equals("-") ? -1 : 1);
        if (dice < 1 || dice > MAX_DICE || sides < 2 || sides > MAX_SIDES || Math.abs(modifier) > MAX_MODIFIER) return Optional.empty();
        List<Integer> results = new ArrayList<>();
        int total = modifier;
        for (int i = 0; i < dice; i++) {
            int value = random.nextInt(sides) + 1;
            results.add(value);
            total += value;
        }
        return Optional.of(new Roll(dice, sides, modifier, List.copyOf(results), total));
    }

    public static boolean flip(RandomGenerator random) {
        return random.nextBoolean();
    }

    public static final List<String> EIGHT_BALL = List.of(
            "It is certain.", "It is decidedly so.", "Without a doubt.", "Yes, definitely.", "You may rely on it.",
            "As I see it, yes.", "Most likely.", "Outlook good.", "Yes.", "Signs point to yes.",
            "Reply hazy, try again.", "Ask again later.", "Better not tell you now.", "Cannot predict now.", "Concentrate and ask again.",
            "Don't count on it.", "My reply is no.", "My sources say no.", "Outlook not so good.", "Very doubtful.");

    public static String eightBall(RandomGenerator random) {
        return EIGHT_BALL.get(random.nextInt(EIGHT_BALL.size()));
    }

    public enum Move {
        ROCK("Rock", "🪨"), PAPER("Paper", "📄"), SCISSORS("Scissors", "✂️");

        public final String label;
        public final String emoji;

        Move(String label, String emoji) {
            this.label = label;
            this.emoji = emoji;
        }

        boolean beats(Move other) {
            return (this == ROCK && other == SCISSORS) || (this == PAPER && other == ROCK) || (this == SCISSORS && other == PAPER);
        }
    }

    public enum Outcome { WIN, LOSE, DRAW }

    public static Outcome rps(Move mine, Move theirs) {
        if (mine == theirs) return Outcome.DRAW;
        return mine.beats(theirs) ? Outcome.WIN : Outcome.LOSE;
    }

    public static Move randomMove(RandomGenerator random) {
        return Move.values()[random.nextInt(Move.values().length)];
    }

    /**
     * Splits what a member typed into options: on commas, semicolons, vertical bars or the word "or" (so {@code pizza or pasta, soup} is
     * three). Blanks are dropped; the caller checks the count.
     */
    public static List<String> options(String text) {
        List<String> out = new ArrayList<>();
        for (String part : text.split("\\s*(?:[,;|]|\\bor\\b)\\s*", -1)) {
            String option = part.strip();
            if (!option.isEmpty()) out.add(option);
        }
        return out;
    }
}
