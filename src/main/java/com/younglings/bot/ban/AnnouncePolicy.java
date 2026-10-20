package com.younglings.bot.ban;

import net.dv8tion.jda.api.entities.Message;

import java.util.EnumSet;
import java.util.List;

/**
 * The rules for {@code /announce} (and the dashboard's "Send an announcement"), kept apart from Discord so they can be tested without
 * ever posting or pinging: what may be mentioned, and exactly what the message and its allowed mentions are.
 * <p>
 * Only the ping the sender chose can notify anybody. The body never can: an {@code @everyone} or {@code @here} typed in it is defused
 * with a zero-width space, and the allowed mentions are limited to the one chosen role or to everyone/here. {@code @everyone} and
 * {@code @here} are refused unless the sender may mention everyone in that channel; a role is refused unless it is mentionable or the
 * sender may mention everyone (Discord's own rule).
 */
public final class AnnouncePolicy {
    public static final int MAX_MESSAGE = 1800;
    private static final String ZERO_WIDTH_SPACE = "​";

    private AnnouncePolicy() {}

    public enum Ping { NONE, ROLE, EVERYONE, HERE }

    /** What to send: {@code refusal} is non-null when nothing may be sent, otherwise {@code content} with the exact mentions allowed. */
    public record Plan(String refusal, String content, EnumSet<Message.MentionType> parse, List<Long> roleIds) {
        public boolean ok() {
            return refusal == null;
        }

        static Plan refuse(String why) {
            return new Plan(why, null, EnumSet.noneOf(Message.MentionType.class), List.of());
        }
    }

    /** Which ping the sender asked for, or {@code null} if they asked for more than one. */
    public static Ping choose(boolean everyone, boolean here, boolean hasRole) {
        int asked = (everyone ? 1 : 0) + (here ? 1 : 0) + (hasRole ? 1 : 0);
        if (asked > 1) return null;
        if (everyone) return Ping.EVERYONE;
        if (here) return Ping.HERE;
        return hasRole ? Ping.ROLE : Ping.NONE;
    }

    /**
     * @param ping                the ping asked for ({@link #choose})
     * @param roleId              the role to ping, for {@link Ping#ROLE}
     * @param roleMentionable     whether that role is set to be mentionable by anyone
     * @param mayMentionEveryone  whether the sender has Mention Everyone in the channel
     */
    public static Plan plan(String message, Ping ping, Long roleId, boolean roleMentionable, boolean mayMentionEveryone) {
        if (ping == null) return Plan.refuse("Choose only one thing to ping: a role, @everyone or @here.");
        String body = message == null ? "" : message.replace("\\n", "\n").strip();
        if (body.isEmpty()) return Plan.refuse("Write the announcement first.");
        if (body.length() > MAX_MESSAGE) return Plan.refuse("The announcement can be at most " + MAX_MESSAGE + " characters (it is " + body.length() + ").");

        // the body can never ping: defuse @everyone and @here, and (below) allow only the chosen role
        body = body.replace("@everyone", "@" + ZERO_WIDTH_SPACE + "everyone").replace("@here", "@" + ZERO_WIDTH_SPACE + "here");

        switch (ping) {
            case EVERYONE, HERE -> {
                if (!mayMentionEveryone) return Plan.refuse("You can't ping @" + (ping == Ping.EVERYONE ? "everyone" : "here") + " here: it needs the Mention Everyone permission in that channel.");
                String prefix = ping == Ping.EVERYONE ? "@everyone" : "@here";
                return new Plan(null, prefix + " " + body, EnumSet.of(Message.MentionType.EVERYONE), List.of());
            }
            case ROLE -> {
                if (roleId == null) return Plan.refuse("Choose the role to ping.");
                if (!roleMentionable && !mayMentionEveryone) return Plan.refuse("That role can't be pinged: it isn't mentionable, and you don't have the Mention Everyone permission.");
                return new Plan(null, "<@&" + roleId + "> " + body, EnumSet.noneOf(Message.MentionType.class), List.of(roleId));
            }
            default -> {
                return new Plan(null, body, EnumSet.noneOf(Message.MentionType.class), List.of());
            }
        }
    }
}
