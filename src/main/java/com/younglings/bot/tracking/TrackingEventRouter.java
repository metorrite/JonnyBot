package com.younglings.bot.tracking;

import io.github.freya022.botcommands.api.core.service.annotations.BService;
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
 * is needed for "is this a thread".
 */
@BService
public class TrackingEventRouter {
    private static final Logger log = LoggerFactory.getLogger(TrackingEventRouter.class);

    private final TrackingRepository repository;

    public TrackingEventRouter(TrackingRepository repository) {
        this.repository = repository;
    }

    public void dispatch(Guild guild, ClassifiedEntry entry) {
        dispatchAll(guild, List.of(entry));
    }

    /** Batches the destination lookups into one query per guild rather than one per entry. */
    public void dispatchAll(Guild guild, List<ClassifiedEntry> entries) {
        if (entries.isEmpty()) return;

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

                channel.sendMessage(entry.line()).queue(success -> {},
                        error -> log.warn("Failed to post tracking entry to channel {} in guild {}", destination.channelId(), guildId, error));
            }
        }
    }
}
