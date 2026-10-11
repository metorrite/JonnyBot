package com.younglings.bot.modlog;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;

import java.awt.Color;
import java.time.Instant;

/** What each moderation log entry looks like. */
public final class ModLogMessage {
    public enum Kind {
        BAN("Member banned", new Color(0xED4245)),
        UNBAN("Member unbanned", new Color(0x57F287)),
        TIMEOUT("Member timed out", new Color(0xFEE75C)),
        TIMEOUT_END("Timeout removed", new Color(0x5865F2));

        private final String title;
        private final Color colour;

        Kind(String title, Color colour) {
            this.title = title;
            this.colour = colour;
        }
    }

    public static final int MAX_REASON = 500;

    private ModLogMessage() {}

    /**
     * One entry. {@code moderatorId} and {@code reason} are null when JonnyBot could not find out (it needs the View Audit Log and Ban
     * Members permissions to). {@code until} is only used for a timeout.
     */
    public static MessageEmbed embed(Kind kind, long targetId, String targetName, Long moderatorId, String reason, Instant until, Instant when) {
        EmbedBuilder embed = new EmbedBuilder().setTitle(kind.title).setColor(kind.colour).setTimestamp(when)
                .addField("Member", "<@" + targetId + ">" + (targetName == null || targetName.isBlank() ? "" : " (" + targetName + ")"), true);
        if (moderatorId != null) embed.addField("By", "<@" + moderatorId + ">", true);
        if (kind == Kind.TIMEOUT && until != null) embed.addField("Until", "<t:" + until.getEpochSecond() + ":f> (<t:" + until.getEpochSecond() + ":R>)", false);
        if (reason != null && !reason.isBlank()) embed.addField("Reason", clip(reason.strip()), false);
        return embed.setFooter("ID " + targetId).build();
    }

    static String clip(String text) {
        return text.length() <= MAX_REASON ? text : text.substring(0, MAX_REASON - 1) + "…";
    }
}
