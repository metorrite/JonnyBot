package com.younglings.bot.suggestion;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import net.dv8tion.jda.api.utils.messages.MessageEditBuilder;
import net.dv8tion.jda.api.utils.messages.MessageEditData;

import java.awt.Color;
import java.util.List;

/** What a suggestion looks like in the channel: its text, who made it, the vote buttons with their counts, and the outcome once decided. */
public final class SuggestionMessage {
    private SuggestionMessage() {}

    public static Color colour(Suggestion.Status status) {
        return switch (status) {
            case PENDING -> new Color(0xD4AF37);
            case APPROVED -> new Color(0x57F287);
            case DENIED -> new Color(0xED4245);
            case IMPLEMENTED -> new Color(0x1ABC9C);
        };
    }

    /** "👍 12" and so on: the count only appears once someone has voted. */
    public static String voteLabel(int count) {
        return count <= 0 ? "" : Integer.toString(count);
    }

    public static MessageEmbed embed(Suggestion s) {
        EmbedBuilder embed = new EmbedBuilder().setTitle("Suggestion #" + s.id()).setDescription(s.content()).setColor(colour(s.status()))
                .addField("Suggested by", "<@" + s.authorId() + ">", true).addField("Status", s.status().label(), true).setTimestamp(s.createdAt());
        if (s.status() != Suggestion.Status.PENDING && s.decidedBy() != null) {
            embed.addField("Decided by", "<@" + s.decidedBy() + ">", true);
        }
        if (s.note() != null && !s.note().isBlank()) {
            embed.addField("Response", s.note(), false);
        }
        return embed.build();
    }

    private static List<ActionRow> rows(Suggestion s, int ups, int downs) {
        Button up = voteButton(SuggestionVote.buttonId("up", s.id()), "👍", ups);
        Button down = voteButton(SuggestionVote.buttonId("down", s.id()), "👎", downs);
        if (!s.open()) {
            up = up.asDisabled();
            down = down.asDisabled();
        }
        return List.of(ActionRow.of(up, down));
    }

    private static Button voteButton(String id, String emoji, int count) {
        Emoji e = Emoji.fromUnicode(emoji);
        return count <= 0 ? Button.secondary(id, e) : Button.secondary(id, voteLabel(count)).withEmoji(e);
    }

    public static MessageCreateData create(Suggestion s, int ups, int downs) {
        return new MessageCreateBuilder().setEmbeds(embed(s)).setComponents(rows(s, ups, downs)).build();
    }

    public static MessageEditData edit(Suggestion s, int ups, int downs) {
        return new MessageEditBuilder().setEmbeds(embed(s)).setComponents(rows(s, ups, downs)).setContent("").build();
    }
}
