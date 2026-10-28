package com.runeclans.bot.messagelog;

import com.runeclans.bot.messagelog.MessageLogRepository.Cached;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;

import java.awt.Color;
import java.time.Instant;
import java.util.List;

/** What each message and voice log entry looks like. */
public final class MessageLogMessage {
    private static final int FIELD = 1000;
    private static final int BULK_LINES = 10;

    private MessageLogMessage() {}

    static String clip(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }

    private static String shown(String content) {
        return content == null || content.isBlank() ? "*(no text)*" : clip(content, FIELD);
    }

    public static MessageEmbed edited(long authorId, long channelId, long messageId, String guildId, String before, String after, String attachments) {
        EmbedBuilder embed = new EmbedBuilder().setTitle("Message edited").setColor(new Color(0xFEE75C)).setTimestamp(Instant.now())
                .addField("Author", "<@" + authorId + ">", true).addField("Channel", "<#" + channelId + ">", true)
                .addField("Before", shown(before), false).addField("After", shown(after), false)
                .addField("Link", "https://discord.com/channels/" + guildId + "/" + channelId + "/" + messageId, false);
        if (attachments != null && !attachments.isBlank()) embed.addField("Attachments", clip(attachments, FIELD), false);
        return embed.setFooter("Message " + messageId).build();
    }

    public static MessageEmbed deleted(Cached message) {
        EmbedBuilder embed = new EmbedBuilder().setTitle("Message deleted").setColor(new Color(0xED4245)).setTimestamp(Instant.now())
                .addField("Author", "<@" + message.authorId() + ">", true).addField("Channel", "<#" + message.channelId() + ">", true)
                .addField("Content", shown(message.content()), false);
        if (message.attachments() != null && !message.attachments().isBlank()) embed.addField("Attachments", clip(message.attachments(), FIELD), false);
        return embed.setFooter("Message " + message.messageId()).build();
    }

    public static MessageEmbed bulkDeleted(long channelId, int total, List<Cached> known) {
        EmbedBuilder embed = new EmbedBuilder().setTitle(total + " messages deleted at once").setColor(new Color(0xED4245)).setTimestamp(Instant.now())
                .addField("Channel", "<#" + channelId + ">", true).addField("Remembered", known.size() + " of " + total, true);
        StringBuilder lines = new StringBuilder();
        for (int i = 0; i < Math.min(BULK_LINES, known.size()); i++) {
            Cached c = known.get(i);
            lines.append("<@").append(c.authorId()).append(">: ").append(clip(c.content().replaceAll("\\s+", " "), 80)).append('\n');
        }
        if (known.size() > BULK_LINES) lines.append("…and ").append(known.size() - BULK_LINES).append(" more");
        if (!lines.isEmpty()) embed.addField("What was said", clip(lines.toString(), FIELD), false);
        return embed.build();
    }

    public static MessageEmbed voice(long memberId, Long left, Long joined) {
        String text;
        Color colour;
        if (left == null && joined != null) {
            text = "<@" + memberId + "> joined <#" + joined + ">";
            colour = new Color(0x57F287);
        } else if (joined == null && left != null) {
            text = "<@" + memberId + "> left <#" + left + ">";
            colour = new Color(0xED4245);
        } else {
            text = "<@" + memberId + "> moved from <#" + left + "> to <#" + joined + ">";
            colour = new Color(0x5865F2);
        }
        return new EmbedBuilder().setDescription(text).setColor(colour).setTimestamp(Instant.now()).setFooter("Voice").build();
    }
}
