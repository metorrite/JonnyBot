package com.runeclans.bot.discord;

import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.utils.messages.MessageRequest;

import java.util.EnumSet;

/**
 * Who a message may ping (T-136). Members, staff and other servers write much of what JonnyBot posts (tags, forms, event names, stream titles,
 * appeals...), so nothing it sends pings @everyone, @here or a role unless the feature says so:
 * <ul>
 *   <li>{@link #installDefault()} makes that the default for every message (JDA's own default lets text ping everyone and every role);</li>
 *   <li>a feature meant to ping (a reminder's or a stream alert's role, an announcement's ping) names exactly what it pings with
 *       {@link #onlyRoles} or an explicit allowed-mentions set, so other role mentions in the same text still don't ping.</li>
 * </ul>
 * User mentions still ping: someone named in a message is told, as Discord does everywhere.
 */
public final class Mentions {
    /** The default for every message: users, channels, emoji and commands link; @everyone, @here and roles never ping. */
    public static final EnumSet<Message.MentionType> SAFE_DEFAULT = EnumSet.of(Message.MentionType.USER, Message.MentionType.CHANNEL, Message.MentionType.EMOJI,
            Message.MentionType.SLASH_COMMAND);
    /** Nothing pings at all. */
    public static final EnumSet<Message.MentionType> NONE = EnumSet.noneOf(Message.MentionType.class);

    private Mentions() {}

    /** Makes {@link #SAFE_DEFAULT} the default for every message JonnyBot sends. Called once, before JDA is built. */
    public static void installDefault() {
        MessageRequest.setDefaultMentions(SAFE_DEFAULT);
    }

    /** Lets exactly these roles ping and nothing else (no users, no @everyone, no other role named in the text). */
    public static <R extends MessageRequest<R>> R onlyRoles(R request, long... roleIds) {
        R r = request.setAllowedMentions(NONE);
        return roleIds.length == 0 ? r : r.mentionRoles(roleIds);
    }
}
