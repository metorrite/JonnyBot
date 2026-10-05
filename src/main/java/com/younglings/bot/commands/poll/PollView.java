package com.younglings.bot.commands.poll;

import com.younglings.bot.discord.Containers;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.container.ContainerChildComponent;
import net.dv8tion.jda.api.components.mediagallery.MediaGallery;
import net.dv8tion.jda.api.components.mediagallery.MediaGalleryItem;
import net.dv8tion.jda.api.components.section.Section;
import net.dv8tion.jda.api.components.separator.Separator;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import net.dv8tion.jda.api.utils.FileUpload;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * How a poll looks in the channel. Each option is a row — its label and tally on the left, its Vote
 * button on the right ({@link Section}) — with a smooth drawn progress bar ({@link PollBarRenderer})
 * directly underneath. Closed polls show the same rows with the buttons disabled and the winner picked out.
 * <p>
 * Component budget: a poll can have up to 6 options, and each costs 5 of Discord's 40 per message (the
 * row is 3, the bar is 2) — 30, plus the title, divider and footer, comes to 34.
 */
final class PollView {
    private PollView() {}

    static final String[] NUMBER_EMOJIS = {"1️⃣", "2️⃣", "3️⃣", "4️⃣", "5️⃣", "6️⃣"};
    static final Color POLL_COLOR = new Color(0x5865F2);
    private static final int VOTERS_SHOWN = 5;

    static Container build(PollSession session, List<PollOption> options, Map<Long, Integer> counts,
                           Map<Long, List<Long>> voters, boolean closed) {
        int total = counts.values().stream().mapToInt(Integer::intValue).sum();
        int best = counts.values().stream().mapToInt(Integer::intValue).max().orElse(0);

        List<ContainerChildComponent> children = new ArrayList<>();
        children.add(TextDisplay.of("### 📊  " + session.title() + (closed ? " — Closed" : "")));

        for (PollOption option : options) {
            int votes = counts.getOrDefault(option.optionId(), 0);
            double share = total > 0 ? (double) votes / total : 0.0;
            boolean winner = closed && best > 0 && votes == best;

            children.add(Section.of(voteButton(session, option, closed), TextDisplay.of(optionText(option, votes, share, total, winner, voters))));

            PollBarRenderer.Style style = !closed ? PollBarRenderer.Style.ACTIVE : winner ? PollBarRenderer.Style.WINNER : PollBarRenderer.Style.MUTED;
            String name = "poll-" + session.pollId() + "-" + option.optionNumber() + "-" + Math.round(share * 1000) + "-" + style.name().toLowerCase() + ".png";
            children.add(MediaGallery.of(MediaGalleryItem.fromFile(FileUpload.fromData(PollBarRenderer.render(share, style), name))
                    .withDescription(Math.round(share * 100) + "%")));
        }

        children.add(Separator.createDivider(Separator.Spacing.SMALL));
        children.add(TextDisplay.of(footer(session, total, closed)));

        return Containers.card(closed ? Color.DARK_GRAY : POLL_COLOR, children);
    }

    private static Button voteButton(PollSession session, PollOption option, boolean closed) {
        Button button = Button.secondary("poll_vote:" + option.pollId() + ":" + option.optionNumber(), "Vote");
        return closed ? button.asDisabled() : button;
    }

    private static String optionText(PollOption option, int votes, double share, int total, boolean winner, Map<Long, List<Long>> voters) {
        StringBuilder text = new StringBuilder();
        text.append(NUMBER_EMOJIS[option.optionNumber() - 1]).append(" **").append(option.label()).append("**");
        if (winner) text.append("  🏆");
        text.append("\n").append(votes).append(votes == 1 ? " vote" : " votes");
        if (total > 0) text.append("  ·  **").append(Math.round(share * 100)).append("%**");

        List<Long> voterIds = voters.getOrDefault(option.optionId(), List.of());
        if (!voterIds.isEmpty()) {
            text.append("\n↳ ");
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
                + (closed ? "This poll has been closed." : "Press a Vote button  ·  press it again to remove your vote");
    }
}
