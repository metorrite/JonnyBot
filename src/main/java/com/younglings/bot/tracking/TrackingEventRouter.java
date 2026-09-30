package com.younglings.bot.tracking;

import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * The shared last mile every source (RuneMetrics polling, the clan roster diff, Discord's own admin
 * log) funnels through: given a already-classified, already-rendered {@link ClassifiedEntry}, look up
 * this guild's configured destinations for its group and post the line, plain text, to each — a
 * destination is just a channel id, and {@link GuildMessageChannel} covers a regular text channel and
 * a forum thread identically (JDA sends to a thread exactly like any other channel), so no branching
 * is needed for "is this a thread". Every post has push/desktop notifications suppressed — this is a
 * passive activity feed, not something anyone it mentions (an admin-log actor, say) needs to be pinged
 * over, so every mention in it renders silently.
 */
@BService
public class TrackingEventRouter {
    private static final Logger log = LoggerFactory.getLogger(TrackingEventRouter.class);

    private final TrackingRepository repository;
    private final TrackingPostingToggle postingToggle;

    public TrackingEventRouter(TrackingRepository repository, TrackingPostingToggle postingToggle) {
        this.repository = repository;
        this.postingToggle = postingToggle;
    }

    public void dispatch(Guild guild, ClassifiedEntry entry) {
        dispatchAll(guild, List.of(entry));
    }

    /** Batches the destination lookups into one query per guild rather than one per entry. */
    public void dispatchAll(Guild guild, List<ClassifiedEntry> entries) {
        if (entries.isEmpty() || !postingToggle.isEnabled()) return;

        long guildId = guild.getIdLong();
        Map<String, List<TrackingRepository.Destination>> destinationsByGroup = repository.getAllDestinations(guildId);

        for (ClassifiedEntry entry : entries) {
            String groupKey = entry.group().name();
            if (!repository.isEnabled(guildId, groupKey)) continue;

            List<TrackingRepository.Destination> destinations = destinationsByGroup.get(groupKey);
            if (destinations == null || destinations.isEmpty()) continue;

            for (TrackingRepository.Destination destination : destinations) {
                GuildMessageChannel channel = guild.getChannelById(GuildMessageChannel.class, destination.channelId());
                if (channel == null) continue;

                channel.sendMessage(entry.line()).setSuppressedNotifications(true).queue(success -> {},
                        error -> log.warn("Failed to post tracking entry to channel {} in guild {}", destination.channelId(), guildId, error));
            }
        }
    }

    /**
     * Same destination lookup/enabled check as {@link #dispatchAll}, but for a group whose message is a
     * full Components V2 {@link Container} (a rich, possibly-interactive layout) rather than a single
     * plain-text line — the weekly digest groups in particular, which need multiple sections and a
     * button, not something {@link ClassifiedEntry#line()} can express.
     */
    public void dispatchContainer(Guild guild, TrackingGroup group, Container container) {
        if (!postingToggle.isEnabled()) return;

        long guildId = guild.getIdLong();
        String groupKey = group.name();
        if (!repository.isEnabled(guildId, groupKey)) return;

        List<TrackingRepository.Destination> destinations = repository.getDestinations(guildId, groupKey);
        for (TrackingRepository.Destination destination : destinations) {
            GuildMessageChannel channel = guild.getChannelById(GuildMessageChannel.class, destination.channelId());
            if (channel == null) continue;

            channel.sendMessageComponents(List.of(container)).useComponentsV2(true).setSuppressedNotifications(true)
                    .queue(success -> {}, error -> log.warn("Failed to post weekly digest to channel {} in guild {}", destination.channelId(), guildId, error));
        }
    }
}
