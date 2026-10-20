package com.younglings.bot.internal;

import com.younglings.bot.highlight.HighlightConfig;
import com.younglings.bot.highlight.HighlightRepository;
import com.younglings.bot.highlight.HighlightRepository.Keyword;
import com.younglings.bot.highlight.HighlightService;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.util.Set;

/** A member's own Highlights words on the website profile; the same rules as {@code /highlight}. Called by {@link MemberApi}, which has already tied the request to the signed-in member. */
@BService
public class HighlightMemberApi {
    /** A refusal the website shows as it is. */
    public static final class Refused extends RuntimeException {
        public final int status;

        Refused(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    private final HighlightService service;
    private final HighlightRepository repository;

    public HighlightMemberApi(HighlightService service, HighlightRepository repository) {
        this.service = service;
        this.repository = repository;
    }

    private static Member member(Guild guild, long userId) {
        Member member = guild.getMemberById(userId);
        if (member != null) return member;
        try {
            return guild.retrieveMemberById(userId).complete();
        } catch (Exception e) {
            throw new Refused(403, "Join the Discord server first.");
        }
    }

    public DataObject get(Guild guild, long userId) {
        Member member = member(guild, userId);
        HighlightConfig config = repository.config(guild.getIdLong());
        String refusal = service.refusal(guild, member);
        DataArray words = DataArray.empty();
        for (Keyword k : repository.keywordsOf(guild.getIdLong(), userId)) words.add(DataObject.empty().put("id", Long.toString(k.id())).put("word", k.word()).put("wholeWord", k.wholeWord()));
        Set<Long> ignored = repository.ignoredBy(guild.getIdLong(), userId);
        DataArray skipped = DataArray.empty();
        for (long id : ignored) {
            GuildChannel channel = guild.getGuildChannelById(id);
            skipped.add(DataObject.empty().put("id", Long.toString(id)).put("name", channel == null ? "deleted channel" : channel.getName()));
        }
        DataArray channels = DataArray.empty();
        if (refusal == null) {
            for (GuildMessageChannel channel : guild.getTextChannels()) {
                if (ignored.contains(channel.getIdLong()) || config.excludedChannels().contains(channel.getIdLong()) || !member.hasAccess(channel)) continue;
                channels.add(DataObject.empty().put("id", channel.getId()).put("name", channel.getName()));
                if (channels.length() >= 200) break;
            }
        }
        return DataObject.empty().put("enabled", config.enabled()).put("usable", refusal == null).put("refusal", refusal).put("words", words)
                .put("max", config.maxKeywords()).put("maxWord", HighlightConfig.MAX_WORD).put("minWord", HighlightConfig.MIN_WORD).put("cooldownSeconds", config.cooldownSeconds())
                .put("ignored", skipped).put("maxIgnored", HighlightConfig.MAX_MEMBER_IGNORES).put("channels", channels);
    }

    public DataObject add(Guild guild, long userId, DataObject body) {
        String refusal = service.refusal(guild, member(guild, userId));
        if (refusal != null) throw new Refused(403, refusal);
        switch (service.add(guild, userId, body.getString("word", ""), body.getBoolean("wholeWord", true))) {
            case DUPLICATE -> throw new Refused(409, "You already watch that word.");
            case FULL -> throw new Refused(400, "You can watch at most " + repository.config(guild.getIdLong()).maxKeywords() + " words here. Remove one first.");
            case BAD_WORD -> throw new Refused(400, "A word must be " + HighlightConfig.MIN_WORD + " to " + HighlightConfig.MAX_WORD + " characters.");
            case ADDED -> { }
        }
        return get(guild, userId);
    }

    public DataObject remove(Guild guild, long userId, String rawId) {
        if (rawId == null || !rawId.matches("\\d{1,18}")) throw new Refused(400, "That isn't a valid word.");
        repository.removeKeyword(guild.getIdLong(), userId, Long.parseLong(rawId), null);
        return get(guild, userId);
    }

    public DataObject ignore(Guild guild, long userId, DataObject body) {
        String raw = body.getString("channelId", "");
        if (!raw.matches("\\d{1,20}")) throw new Refused(400, "That isn't a valid channel.");
        long channelId = Long.parseLong(raw);
        boolean ignored = body.getBoolean("ignored", true);
        if (ignored && repository.ignoredBy(guild.getIdLong(), userId).size() >= HighlightConfig.MAX_MEMBER_IGNORES) {
            throw new Refused(400, "You can skip at most " + HighlightConfig.MAX_MEMBER_IGNORES + " channels.");
        }
        if (ignored && guild.getGuildChannelById(channelId) == null) throw new Refused(400, "That channel doesn't exist in this server.");
        repository.setIgnored(guild.getIdLong(), userId, channelId, ignored);
        return get(guild, userId);
    }
}
