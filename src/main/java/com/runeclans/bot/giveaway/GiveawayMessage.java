package com.runeclans.bot.giveaway;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.utils.messages.MessageEditData;

import java.awt.Color;
import java.util.List;

/** The giveaway's message: while it runs, and once it has ended. */
public final class GiveawayMessage {
    private GiveawayMessage() {}

    private static final Color GOLD = new Color(0xD4AF37);
    private static final Color GREY = new Color(0x4E5058);

    public static MessageEmbed embed(Giveaway g, int entries) {
        EmbedBuilder embed = new EmbedBuilder().setTitle("🎉 " + g.prize());
        if (g.open()) {
            StringBuilder text = new StringBuilder(g.reactionEntry() ? "Press **Enter** or react with " + Giveaway.ENTRY_EMOJI + " to join. Press it again (or remove your reaction) to leave.\n\n"
                    : "Press **Enter** to join. Press it again to leave.\n\n");
            text.append("Ends <t:").append(g.endsAt().getEpochSecond()).append(":R> (<t:").append(g.endsAt().getEpochSecond()).append(":f>)\n");
            text.append("Hosted by <@").append(g.hostId()).append(">\n");
            text.append("Winners: **").append(g.winnerCount()).append("**");
            if (g.requiredRoleId() != null) text.append("\nOnly for <@&").append(g.requiredRoleId()).append(">");
            if (g.winnerRoleId() != null) text.append("\nWinners get <@&").append(g.winnerRoleId()).append(">");
            embed.setDescription(text).setColor(GOLD).setFooter(entries + " entered");
        } else {
            String winners = g.winners().isEmpty() ? "Nobody entered, so there is no winner." : "Winner" + (g.winners().size() == 1 ? "" : "s") + ": " + GiveawayDraw.mentions(g.winners());
            embed.setDescription("This giveaway has ended.\n" + winners + "\nHosted by <@" + g.hostId() + ">").setColor(GREY).setFooter(entries + " entered");
        }
        return embed.build();
    }

    /** The edit that shows the current count on the button (or, once ended, a disabled button). */
    public static MessageEditData edit(Giveaway g, int entries) {
        Button enter = Button.success(GiveawayDraw.buttonId(g.id()), GiveawayDraw.enterLabel(entries)).withEmoji(net.dv8tion.jda.api.entities.emoji.Emoji.fromUnicode("🎉"));
        if (!g.open()) enter = enter.asDisabled();
        return new net.dv8tion.jda.api.utils.messages.MessageEditBuilder().setEmbeds(embed(g, entries)).setComponents(List.of(ActionRow.of(enter))).setContent("").build();
    }
}
