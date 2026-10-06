package com.younglings.bot.commands.poll;

import com.younglings.bot.poll.PollRepository;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Creating, voting in, updating and closing polls, plus the lookups the {@code /poll} panel needs (a
 * member's own active polls, or every active poll for an admin). How a poll looks is {@link PollView}'s job.
 */
@BService
public class PollService {
    private static final Logger log = LoggerFactory.getLogger(PollService.class);

    /** How many polls a regular member can have running at once — admins are unlimited. */
    public static final int MAX_ACTIVE_PER_MEMBER = 5;

    // ConcurrentHashMap: JDA/BotCommands can dispatch interaction callbacks (button clicks) from
    // a pooled executor rather than a single thread, so these maps can be read/written
    // concurrently from separate polls' interactions.
    private final Map<Long, PollSession> activePollsById = new ConcurrentHashMap<>();
    private final Map<Long, List<PollOption>> optionsByPollId = new ConcurrentHashMap<>();
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

    public void createPoll(Guild guild, GuildMessageChannel channel, String title, boolean anonymous,
                           boolean multipleVotes, List<String> optionLabels, long createdByUserId) {
        long pollId = pollRepository.createPoll(
                guild.getIdLong(), channel.getIdLong(), title, anonymous, multipleVotes, createdByUserId);

        List<PollOption> options = new ArrayList<>();
        for (int i = 0; i < optionLabels.size(); i++) {
            long optionId = pollRepository.addOption(pollId, i + 1, optionLabels.get(i));
            options.add(new PollOption(optionId, pollId, i + 1, optionLabels.get(i)));
        }

        PollSession session = new PollSession(pollId, guild.getIdLong(), channel.getIdLong(),
                null, title, anonymous, multipleVotes, "ACTIVE", createdByUserId);
        activePollsById.put(pollId, session);
        optionsByPollId.put(pollId, options);

        channel.sendMessageComponents(List.of(PollView.build(session, options, Map.of(), Map.of(), false)))
                .useComponentsV2(true)
                .queue(message -> {
                    pollRepository.saveMessageId(pollId, message.getIdLong());
                    activePollsById.put(pollId, new PollSession(pollId, guild.getIdLong(), channel.getIdLong(),
                            message.getIdLong(), title, anonymous, multipleVotes, "ACTIVE", createdByUserId));
                    log.info("Created poll {} '{}' in guild {}", pollId, title, guild.getIdLong());
                }, error -> log.warn("Failed to post poll {} '{}' in channel {}", pollId, title, channel.getIdLong(), error));
    }

    // --- Lookup ---

    public PollSession getSessionById(long pollId) {
        return activePollsById.get(pollId);
    }

    public List<PollOption> getOptions(long pollId) {
        return optionsByPollId.getOrDefault(pollId, pollRepository.getOptions(pollId));
    }

    /** Every active poll in the guild, newest first — what an admin sees in the {@code /poll} panel. */
    public List<PollSession> activePolls(long guildId) {
        return activePollsById.values().stream()
                .filter(poll -> poll.guildId() == guildId)
                .sorted(Comparator.comparingLong(PollSession::pollId).reversed())
                .toList();
    }

    /** The active polls {@code userId} started — what a member sees in the {@code /poll} panel. */
    public List<PollSession> activePollsOwnedBy(long guildId, long userId) {
        return activePolls(guildId).stream().filter(poll -> poll.createdByUserId() == userId).toList();
    }

    public int totalVotes(long pollId) {
        return pollRepository.getVoteCounts(pollId).values().stream().mapToInt(Integer::intValue).sum();
    }

    /** Whoever started a poll may end it, and so may an admin; nobody else. */
    public static boolean canManage(PollSession poll, long userId, boolean isAdmin) {
        return isAdmin || poll.createdByUserId() == userId;
    }

    /**
     * What {@code userId} has voted for in an active poll, as lines like "1️⃣ Option label" in option order
     * — empty if they haven't voted. The public message is the same for everyone, so this is how a person
     * sees *their own* picks (in their private reply, and behind the My Votes button).
     */
    public List<String> myVoteLines(long pollId, long userId) {
        java.util.Set<Long> mine = pollRepository.getUserVotes(pollId, userId);
        List<String> lines = new ArrayList<>();
        for (PollOption option : getOptions(pollId)) {
            if (mine.contains(option.optionId())) lines.add(PollView.NUMBER_EMOJIS[option.optionNumber() - 1] + " " + option.label());
        }
        return lines;
    }

    /** The option numbers {@code userId} has picked in a poll — what the website highlights as "your vote". */
    public java.util.Set<Integer> myOptionNumbers(long pollId, long userId) {
        java.util.Set<Long> mine = pollRepository.getUserVotes(pollId, userId);
        java.util.Set<Integer> numbers = new java.util.TreeSet<>();
        for (PollOption option : getOptions(pollId)) if (mine.contains(option.optionId())) numbers.add(option.optionNumber());
        return numbers;
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

        GuildMessageChannel channel = guild.getChannelById(GuildMessageChannel.class, session.channelId());
        if (channel == null) return;

        List<PollOption> options = getOptions(pollId);
        Map<Long, Integer> counts = pollRepository.getVoteCounts(pollId);
        Map<Long, List<Long>> voters = session.anonymous() ? Map.of() : pollRepository.getVotersByOption(pollId);

        channel.retrieveMessageById(session.messageId()).queue(
                msg -> replaceContent(msg, PollView.build(session, options, counts, voters, false)),
                err -> log.warn("Failed to retrieve message for poll {}", pollId));
    }

    /** Swaps a poll message's whole contents — components and the bar images they carry. */
    private void replaceContent(Message message, Container container) {
        message.editMessageComponents(List.of(container)).useComponentsV2(true).queue(
                success -> {}, error -> log.warn("Failed to update poll message {}", message.getIdLong(), error));
    }

    // --- Closing ---

    public void closePoll(Guild guild, long pollId) {
        PollSession session = activePollsById.get(pollId);
        if (session == null) return;

        pollRepository.closePoll(pollId);

        if (session.messageId() != null) {
            GuildMessageChannel channel = guild.getChannelById(GuildMessageChannel.class, session.channelId());
            if (channel != null) {
                List<PollOption> options = getOptions(pollId);
                Map<Long, Integer> counts = pollRepository.getVoteCounts(pollId);
                Map<Long, List<Long>> voters = session.anonymous() ? Map.of() : pollRepository.getVotersByOption(pollId);

                PollSession closed = new PollSession(pollId, session.guildId(), session.channelId(),
                        session.messageId(), session.title(), session.anonymous(), session.multipleVotes(), "CLOSED", session.createdByUserId());

                channel.retrieveMessageById(session.messageId()).queue(
                        msg -> replaceContent(msg, PollView.build(closed, options, counts, voters, true)),
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
            sb.append(PollView.NUMBER_EMOJIS[option.optionNumber() - 1]).append(" **").append(option.label()).append("**")
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
}
