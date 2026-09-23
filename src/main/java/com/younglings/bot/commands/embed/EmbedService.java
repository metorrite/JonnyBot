package com.younglings.bot.commands.embed;

import com.younglings.bot.commands.teamforming.TeamformingService;
import com.younglings.bot.embed.EmbedRepository;
import com.younglings.bot.embed.PostedEmbed;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.function.Consumer;

/** Posts/removes the pre-designed embeds registered in {@link EmbedType}, tracking each in {@link EmbedRepository}. */
@BService
public class EmbedService {
    private static final Logger log = LoggerFactory.getLogger(EmbedService.class);

    private final EmbedRepository repository;
    private final TeamformingService teamformingService;

    public EmbedService(EmbedRepository repository, TeamformingService teamformingService) {
        this.repository = repository;
        this.teamformingService = teamformingService;
    }

    /**
     * Builds and posts {@code type}'s message to {@code channel}, records it, then calls {@code
     * onDone} with the created-roles summary (empty for types that don't create roles) on success,
     * or {@code null} on failure (already logged).
     */
    public void postEmbed(Guild guild, TextChannel channel, EmbedType type, Consumer<List<String>> onDone) {
        MessageCreateData message = switch (type) {
            case TEAMFORMING -> teamformingService.buildPublicPanelMessage();
        };

        List<String> createdRoles = switch (type) {
            case TEAMFORMING -> teamformingService.ensureAllRolesExist(guild);
        };

        channel.sendMessage(message).queue(
                sent -> {
                    repository.recordPosted(guild.getIdLong(), channel.getIdLong(), sent.getIdLong(), type.name());
                    onDone.accept(createdRoles);
                },
                failure -> {
                    log.error("Failed to post {} embed in channel {}", type, channel.getIdLong(), failure);
                    onDone.accept(null);
                }
        );
    }

    public List<PostedEmbed> getPostedInGuild(long guildId) {
        return repository.getPostedInGuild(guildId);
    }

    /** True if a tracked embed of this type existed in this channel and was removed. */
    public boolean removeOne(Guild guild, long channelId, EmbedType type) {
        PostedEmbed tracked = repository.findOne(guild.getIdLong(), channelId, type.name());
        if (tracked == null) return false;

        TextChannel channel = guild.getTextChannelById(channelId);
        if (channel != null) {
            channel.deleteMessageById(tracked.messageId()).queue(
                    null, failure -> log.warn("Could not delete posted embed message {} (already gone?)",
                            tracked.messageId(), failure));
        }

        repository.delete(tracked.postedEmbedId());
        return true;
    }

    /** Deletes every tracked posted embed's message (best-effort) and clears the tracking table. Returns how many. */
    public int removeAll(Guild guild) {
        List<PostedEmbed> all = repository.getPostedInGuild(guild.getIdLong());

        for (PostedEmbed posted : all) {
            TextChannel channel = guild.getTextChannelById(posted.channelId());
            if (channel != null) {
                channel.deleteMessageById(posted.messageId()).queue(
                        null, failure -> log.warn("Could not delete posted embed message {} (already gone?)",
                                posted.messageId(), failure));
            }
        }

        repository.deleteAllInGuild(guild.getIdLong());
        return all.size();
    }
}
