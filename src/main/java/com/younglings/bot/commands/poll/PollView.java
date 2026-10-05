package com.younglings.bot.commands.poll;

import com.younglings.bot.discord.Containers;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.container.ContainerChildComponent;
import net.dv8tion.jda.api.components.mediagallery.MediaGallery;
import net.dv8tion.jda.api.components.mediagallery.MediaGalleryItem;
import net.dv8tion.jda.api.components.separator.Separator;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.utils.FileUpload;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * How a poll looks in the channel. Each option is two rows: its button — the option's own label, pressed
 * to vote — left-aligned on the first, and a drawn progress bar ({@link PollBarRenderer}) under it with
 * the vote count and percentage written to the right of the bar. Every option has exactly this shape, so
 * the buttons sit at the same left edge and the same spacing whether an option has votes or not. Who voted
 * (when the poll isn't anonymous) goes in a small line beneath the bar. Closed polls show the same rows with
 * the buttons disabled and the winner picked out.
 * <p>
 * (Buttons are the same for everyone looking at a message, so they can't say "Vote" to one person and
 * "Remove vote" to another; pressing an option you already picked removes your vote, and the reply says so.)
 * <p>
 * Component budget: a poll can have up to 6 options, and each costs at most 5 of Discord's 40 per message
 * (button row 2, bar 2, voters line 1) — 30, plus the title, divider, footer and the My Votes row, comes to 36.
 */
final class PollView {
    private PollView() {}

    static final String[] NUMBER_EMOJIS = {"1️⃣", "2️⃣", "3️⃣", "4️⃣", "5️⃣", "6️⃣"};
    static final Color POLL_COLOR = new Color(0x5865F2);
    private static final int VOTERS_SHOWN = 5;
    private static final int BUTTON_LABEL_MAX = 70;

    static Container build(PollSession session, List<PollOption> options, Map<Long, Integer> counts,
                           Map<Long, List<Long>> voters, boolean closed) {
        return build(session, options, counts, voters, closed, PollBarRenderer.textAvailable());
    }

    /** @param numbersInImage whether the count and percentage are drawn into each bar image; when {@code false} (no fonts available) they go in a text line under the bar instead */
    static Container build(PollSession session, List<PollOption> options, Map<Long, Integer> counts,
                           Map<Long, List<Long>> voters, boolean closed, boolean numbersInImage) {
        int total = counts.values().stream().mapToInt(Integer::intValue).sum();
        int best = counts.values().stream().mapToInt(Integer::intValue).max().orElse(0);

        List<ContainerChildComponent> children = new ArrayList<>();
        children.add(TextDisplay.of("### 📊  " + session.title() + (closed ? " — Closed" : "")));

        for (PollOption option : options) {
            int votes = counts.getOrDefault(option.optionId(), 0);
            double share = total > 0 ? (double) votes / total : 0.0;
            long percent = Math.round(share * 100);
            boolean winner = closed && best > 0 && votes == best;
            String votesText = votes + (votes == 1 ? " vote" : " votes");

            children.add(ActionRow.of(voteButton(option, winner, closed)));

            PollBarRenderer.Style style = !closed ? PollBarRenderer.Style.ACTIVE : winner ? PollBarRenderer.Style.WINNER : PollBarRenderer.Style.MUTED;
            byte[] png = PollBarRenderer.render(share, style, numbersInImage ? votesText : null, numbersInImage ? percent + "%" : null);
            String name = "poll-" + session.pollId() + "-" + option.optionNumber() + "-" + votes + "of" + total + "-" + style.name().toLowerCase()
                    + (numbersInImage ? "" : "-plain") + ".png";
            children.add(MediaGallery.of(MediaGalleryItem.fromFile(FileUpload.fromData(png, name)).withDescription(votesText + ", " + percent + "%")));

            String below = belowBar(votesText, percent, total, numbersInImage, voters.getOrDefault(option.optionId(), List.of()));
            if (!below.isEmpty()) children.add(TextDisplay.of(below));
        }

        children.add(Separator.createDivider(Separator.Spacing.SMALL));
        children.add(TextDisplay.of(footer(session, total, closed)));
        if (!closed) children.add(ActionRow.of(Button.secondary("poll_mine:" + session.pollId(), "My Votes")));

        return Containers.card(closed ? Color.DARK_GRAY : POLL_COLOR, children);
    }

    private static Button voteButton(PollOption option, boolean winner, boolean closed) {
        String label = truncate(option.label(), BUTTON_LABEL_MAX);
        Button button = Button.secondary("poll_vote:" + option.pollId() + ":" + option.optionNumber(), (winner ? "🏆 " : "") + label)
                .withEmoji(Emoji.fromUnicode(NUMBER_EMOJIS[option.optionNumber() - 1]));
        return closed ? button.asDisabled() : button;
    }

    private static String belowBar(String votesText, long percent, int total, boolean numbersInImage, List<Long> voterIds) {
        StringBuilder text = new StringBuilder();
        if (!numbersInImage) {
            text.append(votesText);
            if (total > 0) text.append("  ·  **").append(percent).append("%**");
        }
        if (!voterIds.isEmpty()) {
            if (!text.isEmpty()) text.append("\n");
            text.append("↳ ");
            int shown = Math.min(voterIds.size(), VOTERS_SHOWN);
            for (int i = 0; i < shown; i++) {
                if (i > 0) text.append(", ");
                text.append("<@").append(voterIds.get(i)).append(">");
            }
            if (voterIds.size() > VOTERS_SHOWN) text.append(" *+").append(voterIds.size() - VOTERS_SHOWN).append(" more*");
        }
        return text.toString();
    }

    private static String footer(PollSession session, int total, boolean closed) {
        List<String> meta = new ArrayList<>();
        meta.add("**" + total + "** " + (total == 1 ? "vote" : "votes"));
        if (session.multipleVotes()) meta.add("multiple votes allowed");
        if (session.anonymous()) meta.add("anonymous");
        return "🗳️ *" + String.join("  ·  ", meta) + "*\n-# "
                + (closed ? "This poll has been closed." : "Press an option to vote  ·  press it again to remove your vote");
    }

    private static String truncate(String s, int max) {
        return s.length() > max ? s.substring(0, max - 1) + "…" : s;
    }
}
