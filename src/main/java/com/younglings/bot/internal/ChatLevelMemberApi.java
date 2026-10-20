package com.younglings.bot.internal;

import com.younglings.bot.chatlevel.ChatLevelConfig;
import com.younglings.bot.chatlevel.ChatLevelRepository;
import com.younglings.bot.chatlevel.ChatLevelRepository.Standing;
import com.younglings.bot.chatlevel.ChatLevels;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.utils.data.DataObject;

/** A member's own chat level for the website profile. Called by {@link MemberApi}, which has already tied the request to the signed-in member. Read-only. */
@BService
public class ChatLevelMemberApi {
    private final ChatLevelRepository repository;

    public ChatLevelMemberApi(ChatLevelRepository repository) {
        this.repository = repository;
    }

    public DataObject get(Guild guild, long userId) {
        ChatLevelConfig config = repository.config(guild.getIdLong());
        if (!config.enabled()) return DataObject.empty().put("enabled", false);
        Standing standing = repository.standing(guild.getIdLong(), userId).orElse(new Standing(userId, 0, 0));
        int level = ChatLevels.levelOf(standing.xp());
        return DataObject.empty().put("enabled", true).put("level", level).put("xp", standing.xp()).put("intoLevel", ChatLevels.intoLevel(standing.xp()))
                .put("toNext", level >= ChatLevels.MAX_LEVEL ? 0 : ChatLevels.xpToNext(level)).put("maxed", level >= ChatLevels.MAX_LEVEL)
                .put("rank", standing.xp() == 0 ? null : repository.rankOf(guild.getIdLong(), standing.xp())).put("messages", standing.messages())
                .put("members", repository.countMembers(guild.getIdLong()));
    }
}
