package com.runeclans.bot.highlight;

import com.runeclans.bot.highlight.HighlightRepository.Keyword;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.attribute.IThreadContainer;
import net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel;
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;

import java.util.ArrayList;
import java.util.List;

/** What a member may do with Highlights, shared by the {@code /highlight} command and the website profile so both give the same answers. */
@BService
public class HighlightService {
    private final HighlightRepository repository;

    public HighlightService(HighlightRepository repository) {
        this.repository = repository;
    }

    public HighlightRepository repository() {
        return repository;
    }

    /** Why this member cannot use Highlights here, or null when they can. */
    public String refusal(Guild guild, Member member) {
        HighlightConfig config = repository.config(guild.getIdLong());
        if (!config.enabled()) return "Highlights aren't switched on in this server.";
        if (!config.allowedRoles().isEmpty() && member.getRoles().stream().noneMatch(r -> config.allowedRoles().contains(r.getIdLong()))) {
            return "Highlights are only for members with " + roleNames(guild, config) + ".";
        }
        return null;
    }

    private static String roleNames(Guild guild, HighlightConfig config) {
        List<String> names = new ArrayList<>();
        for (long id : config.allowedRoles()) {
            Role role = guild.getRoleById(id);
            if (role != null) names.add("**" + role.getName() + "**");
        }
        return names.isEmpty() ? "a certain role" : String.join(" or ", names);
    }

    public enum AddResult { ADDED, DUPLICATE, FULL, BAD_WORD }

    public AddResult add(Guild guild, long userId, String rawWord, boolean wholeWord) {
        String word = HighlightMatcher.clean(rawWord);
        if (word.length() < HighlightConfig.MIN_WORD || word.length() > HighlightConfig.MAX_WORD) return AddResult.BAD_WORD;
        HighlightConfig config = repository.config(guild.getIdLong());
        List<Keyword> mine = repository.keywordsOf(guild.getIdLong(), userId);
        if (mine.stream().anyMatch(k -> k.word().equalsIgnoreCase(word))) return AddResult.DUPLICATE;
        if (mine.size() >= config.maxKeywords()) return AddResult.FULL;
        return repository.addKeyword(guild.getIdLong(), userId, word, wholeWord) ? AddResult.ADDED : AddResult.DUPLICATE;
    }

    /**
     * The channel a message in {@code channel} counts as for exclusions and ignores: a thread counts as the channel it belongs to.
     */
    public static long rootChannelId(GuildChannel channel) {
        return channel instanceof ThreadChannel thread && thread.getParentChannel() != null ? thread.getParentChannel().getIdLong() : channel.getIdLong();
    }

    /**
     * Whether the member can read this channel right now. A direct message about a channel they can't see would leak what was said there,
     * so anything uncertain says no: private threads are never shown, and a thread follows the access to its parent channel.
     */
    public static boolean canSee(Member member, GuildChannel channel) {
        if (channel instanceof ThreadChannel thread) {
            if (!thread.isPublic()) return false;
            IThreadContainer parent = thread.getParentChannel();
            return parent != null && member.hasAccess(parent);
        }
        return member.hasAccess(channel);
    }

    public static boolean isMessageChannel(GuildChannel channel) {
        return channel instanceof GuildMessageChannel;
    }
}
