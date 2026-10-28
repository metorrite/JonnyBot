package com.runeclans.bot.suggestion;

import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;

/** Posting suggestions to the server's channel, keeping the vote counts on their buttons current, and showing a decision on the message. */
@BService
public class SuggestionService {
    private static final Logger log = LoggerFactory.getLogger(SuggestionService.class);
    /** Stops one person flooding the channel; the dashboard has no such limit because staff use it. */
    static final Duration COOLDOWN = Duration.ofSeconds(30);

    /** Why something couldn't be done, in words for the person who asked. */
    public static final class SuggestionException extends RuntimeException {
        public SuggestionException(String message) {
            super(message);
        }
    }

    private final SuggestionRepository repository;
    private final Map<Long, Long> lastSubmitted = new ConcurrentHashMap<>();

    public SuggestionService(SuggestionRepository repository) {
        this.repository = repository;
    }

    /** Seconds the person must still wait, or 0 if they may submit now. Calling it counts as submitting when it returns 0. */
    long claimCooldown(long userId, long nowMillis) {
        long[] wait = {0};
        lastSubmitted.compute(userId, (id, previous) -> {
            if (previous != null && nowMillis - previous < COOLDOWN.toMillis()) {
                wait[0] = (COOLDOWN.toMillis() - (nowMillis - previous) + 999) / 1000;
                return previous;
            }
            return nowMillis;
        });
        return wait[0];
    }

    public Suggestion submit(Guild guild, long authorId, String content) {
        OptionalLong configured = repository.channel(guild.getIdLong());
        if (configured.isEmpty()) throw new SuggestionException("This server hasn't chosen a channel for suggestions yet. An admin can set one on the dashboard.");
        GuildMessageChannel channel = guild.getChannelById(GuildMessageChannel.class, configured.getAsLong());
        if (channel == null) throw new SuggestionException("The suggestions channel no longer exists. An admin needs to choose a new one on the dashboard.");
        if (!channel.canTalk()) throw new SuggestionException("The bot can't post in the suggestions channel right now. An admin needs to check its permissions.");
        long wait = claimCooldown(authorId, System.currentTimeMillis());
        if (wait > 0) throw new SuggestionException("Please wait " + wait + " second" + (wait == 1 ? "" : "s") + " before sending another suggestion.");

        long id = repository.create(guild.getIdLong(), authorId, content, channel.getIdLong());
        Suggestion s = repository.get(guild.getIdLong(), id).orElseThrow();
        try {
            Message sent = channel.sendMessage(SuggestionMessage.create(s, 0, 0)).complete();
            repository.setMessage(id, sent.getIdLong());
            return repository.get(guild.getIdLong(), id).orElseThrow();
        } catch (RuntimeException e) {
            repository.delete(guild.getIdLong(), id);
            log.warn("Couldn't post suggestion {} in channel {}", id, channel.getId(), e);
            throw new SuggestionException("Discord refused the message. Check the bot can post and embed links in #" + channel.getName() + ".");
        }
    }

    /** Re-draws the message with the current counts (or the decision). */
    public void refresh(Guild guild, Suggestion s) {
        if (s.messageId() == null || s.channelId() == null) return;
        GuildMessageChannel channel = guild.getChannelById(GuildMessageChannel.class, s.channelId());
        if (channel == null) return;
        int[] counts = repository.counts(s.id());
        channel.editMessageById(s.messageId(), SuggestionMessage.edit(s, counts[0], counts[1])).queue(ok -> {}, error -> {});
    }

    /** Records staff's decision and shows it on the message; voting closes unless it is put back to pending. */
    public Suggestion decide(Guild guild, Suggestion s, Suggestion.Status status, String note, long deciderId) {
        repository.decide(s.id(), status, note, deciderId);
        Suggestion updated = repository.get(guild.getIdLong(), s.id()).orElseThrow();
        refresh(guild, updated);
        return updated;
    }

    public void remove(Guild guild, Suggestion s) {
        if (s.messageId() != null && s.channelId() != null) {
            GuildMessageChannel channel = guild.getChannelById(GuildMessageChannel.class, s.channelId());
            if (channel != null) channel.deleteMessageById(s.messageId()).queue(ok -> {}, error -> {});
        }
        repository.delete(guild.getIdLong(), s.id());
    }
}
