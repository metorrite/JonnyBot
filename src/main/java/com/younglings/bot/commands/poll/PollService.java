package com.younglings.bot.commands.poll;

import com.younglings.bot.poll.PollRepository;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;
import java.util.*;
import java.util.List;

@BService
public class PollService {
    private static final Logger log = LoggerFactory.getLogger(PollService.class);
    private static final int BAR_WIDTH = 18;
    private static final String[] PARTIAL_BLOCKS = {"", "▏", "▎", "▍", "▌", "▋", "▊", "▉"};
    private static final String[] NUMBER_EMOJIS = {"1️⃣", "2️⃣", "3️⃣", "4️⃣", "5️⃣", "6️⃣"};

    private final Map<Long, PollSession> activePollsById = new HashMap<>();
    private final Map<Long, List<PollOption>> optionsByPollId = new HashMap<>();
    private final PollRepository pollRepository;

    public PollService(PollRepository pollRepository) {
        this.pollRepository = pollRepository;
        loadActivePolls();
    }

    private void loadActivePolls() {
        try {
            List<PollSession> polls = pollRepository.getAllActivePolls();
            for (PollSession poll : polls) {
                activePollsById.put(poll.pollId(), poll);
                optionsByPollId.put(poll.pollId(), pollRepository.getOptions(poll.pollId()));
                log.info("Loaded poll {} '{}' for guild {}", poll.pollId(), poll.title(), poll.guildId());
            }
        } catch (Exception e) {
            log.error("Failed to load active polls from database — starting with empty state", e);
        }
    }

    // --- Creation ---

    public void createPoll(Guild guild, TextChannel channel, String title, boolean anonymous,
                            boolean multipleVotes, List<String> optionLabels, long createdByUserId) {
        long pollId = pollRepository.createPoll(
                guild.getIdLong(), channel.getIdLong(), title, anonymous, multipleVotes, createdByUserId);

        List<PollOption> options = new ArrayList<>();
        for (int i = 0; i < optionLabels.size(); i++) {
            long optionId = pollRepository.addOption(pollId, i + 1, optionLabels.get(i));
            options.add(new PollOption(optionId, pollId, i + 1, optionLabels.get(i)));
        }

        PollSession session = new PollSession(pollId, guild.getIdLong(), channel.getIdLong(),
                null, title, anonymous, multipleVotes, "ACTIVE");
        activePollsById.put(pollId, session);
        optionsByPollId.put(pollId, options);

        channel.sendMessageEmbeds(buildEmbed(session, options, Map.of(), Map.of()).build())
                .addComponents(buildVoteRows(options, false))
                .queue(message -> {
                    pollRepository.saveMessageId(pollId, message.getIdLong());
                    activePollsById.put(pollId, new PollSession(pollId, guild.getIdLong(), channel.getIdLong(),
                            message.getIdLong(), title, anonymous, multipleVotes, "ACTIVE"));
                    log.info("Created poll {} '{}' in guild {}", pollId, title, guild.getIdLong());
                });
    }

    // --- Lookup ---

    public PollSession getSessionById(long pollId) {
        return activePollsById.get(pollId);
    }

    public List<PollOption> getOptions(long pollId) {
        return optionsByPollId.getOrDefault(pollId, pollRepository.getOptions(pollId));
    }

    public PollSession findByTitle(long guildId, String query) {
        for (PollSession session : activePollsById.values()) {
            if (session.guildId() == guildId
                    && session.title().toLowerCase().contains(query.toLowerCase())) {
                return session;
            }
        }
        return pollRepository.findPollByTitle(guildId, query);
    }

    // --- Voting ---

    public enum VoteResult { ADDED, REMOVED, SWITCHED, POLL_CLOSED }

    public VoteResult toggleVote(long pollId, long optionId, long userId) {
        PollSession session = activePollsById.get(pollId);
        if (session == null || !"ACTIVE".equalsIgnoreCase(session.status())) return VoteResult.POLL_CLOSED;

        boolean alreadyVotedThis = pollRepository.hasVotedForOption(pollId, optionId, userId);

        if (alreadyVotedThis) {
            pollRepository.removeVote(pollId, optionId, userId);
            return VoteResult.REMOVED;
        }

        if (!session.multipleVotes()) {
            boolean hadPriorVote = pollRepository.hasVotedInPoll(pollId, userId);
            pollRepository.removeAllVotesForUser(pollId, userId);
            pollRepository.addVote(pollId, optionId, userId);
            return hadPriorVote ? VoteResult.SWITCHED : VoteResult.ADDED;
        }

        pollRepository.addVote(pollId, optionId, userId);
        return VoteResult.ADDED;
    }

    // --- Message updates ---

    public void updateMessage(Guild guild, long pollId) {
        PollSession session = activePollsById.get(pollId);
        if (session == null || session.messageId() == null) return;

        TextChannel channel = guild.getTextChannelById(session.channelId());
        if (channel == null) return;

        List<PollOption> options = getOptions(pollId);
        Map<Long, Integer> counts = pollRepository.getVoteCounts(pollId);
        Map<Long, List<Long>> voters = session.anonymous() ? Map.of() : pollRepository.getVotersByOption(pollId);

        channel.retrieveMessageById(session.messageId()).queue(
                msg -> msg.editMessageEmbeds(buildEmbed(session, options, counts, voters).build())
                          .setComponents(buildVoteRows(options, false))
                          .queue(),
                err -> log.warn("Failed to retrieve message for poll {}", pollId));
    }

    // --- Closing ---

    public void closePoll(Guild guild, long pollId) {
        PollSession session = activePollsById.get(pollId);
        if (session == null) return;

        pollRepository.closePoll(pollId);

        if (session.messageId() != null) {
            TextChannel channel = guild.getTextChannelById(session.channelId());
            if (channel != null) {
                List<PollOption> options = getOptions(pollId);
                Map<Long, Integer> counts = pollRepository.getVoteCounts(pollId);
                Map<Long, List<Long>> voters = session.anonymous() ? Map.of() : pollRepository.getVotersByOption(pollId);

                PollSession closed = new PollSession(pollId, session.guildId(), session.channelId(),
                        session.messageId(), session.title(), session.anonymous(), session.multipleVotes(), "CLOSED");

                channel.retrieveMessageById(session.messageId()).queue(
                        msg -> msg.editMessageEmbeds(buildClosedEmbed(closed, options, counts, voters).build())
                                  .setComponents(buildVoteRows(options, true))
                                  .queue(),
                        err -> log.warn("Failed to retrieve message to close poll {}", pollId));
            }
        }

        activePollsById.remove(pollId);
        optionsByPollId.remove(pollId);
        log.info("Closed poll {} '{}'", pollId, session.title());
    }

    // --- Results DM ---

    public String buildResultsSummary(long pollId) {
        PollSession session = activePollsById.getOrDefault(pollId, pollRepository.getPollById(pollId));
        if (session == null) return "Poll not found.";

        List<PollOption> options = getOptions(pollId);
        Map<Long, Integer> counts = pollRepository.getVoteCounts(pollId);
        Map<Long, List<Long>> voters = pollRepository.getVotersByOption(pollId);

        int total = counts.values().stream().mapToInt(Integer::intValue).sum();

        StringBuilder sb = new StringBuilder();
        sb.append("**📊 Full Results: ").append(session.title()).append("**\n");
        sb.append("Total votes: **").append(total).append("**\n\n");

        for (PollOption option : options) {
            int votes = counts.getOrDefault(option.optionId(), 0);
            double pct = total > 0 ? votes * 100.0 / total : 0.0;
            sb.append(NUMBER_EMOJIS[option.optionNumber() - 1]).append(" **").append(option.label()).append("**")
              .append(" — ").append(votes).append(votes == 1 ? " vote" : " votes")
              .append(String.format(" (%.1f%%)", pct)).append("\n");

            List<Long> voterIds = voters.getOrDefault(option.optionId(), List.of());
            if (voterIds.isEmpty()) {
                sb.append("*No votes*\n");
            } else {
                for (long uid : voterIds) sb.append("• <@").append(uid).append(">\n");
            }
            sb.append("\n");
        }

        return sb.toString().trim();
    }

    // --- Embed builders ---

    EmbedBuilder buildEmbed(PollSession session, List<PollOption> options,
                             Map<Long, Integer> counts, Map<Long, List<Long>> voters) {
        int total = counts.values().stream().mapToInt(Integer::intValue).sum();
        StringBuilder desc = new StringBuilder();

        for (PollOption option : options) {
            int votes = counts.getOrDefault(option.optionId(), 0);
            double pct = total > 0 ? (double) votes / total : 0.0;

            desc.append(NUMBER_EMOJIS[option.optionNumber() - 1])
                .append(" **").append(option.label()).append("**\n");

            desc.append(buildBar(pct)).append("  ").append(votes)
                .append(votes == 1 ? " vote" : " votes");
            if (total > 0) desc.append("  **").append(String.format("%.0f%%", pct * 100)).append("**");
            desc.append("\n");

            if (!voters.isEmpty()) {
                List<Long> voterIds = voters.getOrDefault(option.optionId(), List.of());
                if (!voterIds.isEmpty()) {
                    desc.append("↳ ");
                    int shown = Math.min(voterIds.size(), 5);
                    for (int i = 0; i < shown; i++) {
                        if (i > 0) desc.append(", ");
                        desc.append("<@").append(voterIds.get(i)).append(">");
                    }
                    if (voterIds.size() > 5) desc.append(" *+").append(voterIds.size() - 5).append(" more*");
                    desc.append("\n");
                }
            }

            desc.append("\n");
        }

        desc.append("───────────────────────\n");

        List<String> meta = new ArrayList<>();
        meta.add("**" + total + "** " + (total == 1 ? "vote" : "votes"));
        if (session.multipleVotes()) meta.add("multiple votes allowed");
        if (session.anonymous()) meta.add("anonymous");
        desc.append("🗳️ *").append(String.join("  ·  ", meta)).append("*");

        return new EmbedBuilder()
                .setTitle("📊  " + session.title())
                .setDescription(desc.toString())
                .setColor(new Color(0x5865F2))
                .setFooter("Vote using the numbered buttons below  ·  Tap again to remove your vote");
    }

    private EmbedBuilder buildClosedEmbed(PollSession session, List<PollOption> options,
                                           Map<Long, Integer> counts, Map<Long, List<Long>> voters) {
        return buildEmbed(session, options, counts, voters)
                .setTitle("📊  " + session.title() + " — Closed")
                .setColor(Color.DARK_GRAY)
                .setFooter("This poll has been closed.");
    }

    private String buildBar(double pct) {
        int totalUnits = BAR_WIDTH * 8;
        int filledUnits = (int) Math.round(pct * totalUnits);
        int fullChars  = filledUnits / 8;
        int partial    = filledUnits % 8;
        int emptyChars = BAR_WIDTH - fullChars - (partial > 0 ? 1 : 0);

        return "█".repeat(fullChars)
                + (partial > 0 ? PARTIAL_BLOCKS[partial] : "")
                + "░".repeat(Math.max(0, emptyChars));
    }

    // --- Action row builders ---

    private List<ActionRow> buildVoteRows(List<PollOption> options, boolean disabled) {
        List<Button> buttons = new ArrayList<>();
        for (PollOption option : options) {
            Button button = Button.secondary(
                    "poll_vote:" + option.pollId() + ":" + option.optionNumber(),
                    NUMBER_EMOJIS[option.optionNumber() - 1] + " " + truncate(option.label(), 16)
            );
            buttons.add(disabled ? button.asDisabled() : button);
        }

        List<ActionRow> rows = new ArrayList<>();
        for (int i = 0; i < buttons.size(); i += 5) {
            rows.add(ActionRow.of(buttons.subList(i, Math.min(i + 5, buttons.size()))));
        }
        return rows;
    }

    private String truncate(String s, int max) {
        return s.length() > max ? s.substring(0, max - 1) + "…" : s;
    }
}
