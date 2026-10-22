package com.younglings.bot.commands.chatlevel;

import com.younglings.bot.chatlevel.ChatLevelRepository;
import com.younglings.bot.chatlevel.ChatLevelRepository.Standing;
import com.younglings.bot.chatlevel.ChatLevels;
import com.younglings.bot.discord.Containers;
import com.younglings.bot.i18n.Text;
import io.github.freya022.botcommands.api.commands.annotations.Command;
import io.github.freya022.botcommands.api.commands.application.slash.GuildSlashEvent;
import io.github.freya022.botcommands.api.commands.application.slash.annotations.JDASlashCommand;
import io.github.freya022.botcommands.api.commands.application.slash.annotations.SlashOption;
import io.github.freya022.botcommands.api.commands.application.slash.annotations.TopLevelSlashCommandData;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.User;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * {@code /level [user]} and {@code /levels top}: chat levels (XP for chatting), separate from the clan points. Nothing answers until the server switches chat
 * levels on (dashboard, Chat levels page).
 */
@Command
public class LevelCommand {
    private final ChatLevelRepository repository;

    public LevelCommand(ChatLevelRepository repository) {
        this.repository = repository;
    }

    private boolean refuseIfOff(GuildSlashEvent event) {
        if (repository.config(event.getGuild().getIdLong()).enabled()) return false;
        Containers.replyEphemeral(event, Containers.INFO, Text.of(event, "chatlevel.chat_levels_aren_t_switched_on"));
        return true;
    }

    @JDASlashCommand(name = "level", description = "See your chat level, or another member's")
    public void onLevel(GuildSlashEvent event, @SlashOption(description = "Whose level to see (yours if left empty)") @Nullable User user) {
        if (refuseIfOff(event)) return;
        Guild guild = event.getGuild();
        User who = user == null ? event.getUser() : user;
        if (who.isBot()) {
            Containers.replyEphemeral(event, Containers.INFO, Text.of(event, "chatlevel.bots_don_t_earn_levels"));
            return;
        }
        Standing standing = repository.standing(guild.getIdLong(), who.getIdLong()).orElse(new Standing(who.getIdLong(), 0, 0));
        int level = ChatLevels.levelOf(standing.xp());
        String next = level >= ChatLevels.MAX_LEVEL ? Text.of(event, "chatlevel.top_level_reached")
                : Text.of(event, "chatlevel.xp_to_level", ChatLevels.intoLevel(standing.xp()), ChatLevels.xpToNext(level), level + 1);
        long rank = standing.xp() == 0 ? 0 : repository.rankOf(guild.getIdLong(), standing.xp());
        Containers.reply(event, Containers.PRIMARY, false,
                Text.of(event, "chatlevel.level", who.getEffectiveName(), level, (rank > 0 ? Text.of(event, "chatlevel.rank_suffix", rank) : Text.of(event, "chatlevel.not_ranked_suffix"))),
                ChatLevels.bar(standing.xp(), 12) + "  " + next,
                Text.of(event, "chatlevel.xp_in_total_messages_that_earned", standing.xp(), standing.messages()));
    }

    @TopLevelSlashCommandData(description = "Chat levels across the server")
    @JDASlashCommand(name = "levels", subcommand = "top", description = "The most active chatters")
    public void onTop(GuildSlashEvent event) {
        if (refuseIfOff(event)) return;
        List<Standing> top = repository.top(event.getGuild().getIdLong(), 10);
        if (top.isEmpty()) {
            Containers.replyEphemeral(event, Containers.INFO, Text.of(event, "chatlevel.nobody_has_earned_xp_yet"));
            return;
        }
        StringBuilder lines = new StringBuilder();
        int place = 1;
        for (Standing s : top) lines.append(place++).append(". <@").append(s.userId()).append("> · level ").append(ChatLevels.levelOf(s.xp())).append(" · ").append(s.xp()).append(" XP\n");
        Containers.replyEphemeral(event, Containers.PRIMARY, Text.of(event, "chatlevel.top_chatters"), lines.toString().stripTrailing());
    }
}
