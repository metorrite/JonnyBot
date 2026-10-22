package com.younglings.bot.commands.afk;

import com.younglings.bot.afk.AfkNicknames;
import com.younglings.bot.afk.AfkService;
import com.younglings.bot.discord.Containers;
import com.younglings.bot.hub.HubService;
import com.younglings.bot.i18n.Text;
import com.younglings.bot.permission.AdminRoleFilter;
import io.github.freya022.botcommands.api.commands.annotations.Command;
import io.github.freya022.botcommands.api.commands.application.slash.GuildSlashEvent;
import io.github.freya022.botcommands.api.commands.application.slash.annotations.JDASlashCommand;
import io.github.freya022.botcommands.api.commands.application.slash.annotations.SlashOption;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.User;
import org.jetbrains.annotations.Nullable;

/**
 * {@code /afk [reason]}: says you are away. Anyone who mentions you is told, and you are taken off the list the moment you next speak.
 * Running it again changes the reason. Staff can add {@code user} to take someone else off the list instead. If the server turned the nickname tag on
 * (dashboard, Commands page), "[AFK] " goes in front of your name while you are away.
 */
@Command
public class AfkCommand {
    private final AfkService service;
    private final HubService hub;
    private final AdminRoleFilter adminRoleFilter;

    public AfkCommand(AfkService service, HubService hub, AdminRoleFilter adminRoleFilter) {
        this.service = service;
        this.hub = hub;
        this.adminRoleFilter = adminRoleFilter;
    }

    @JDASlashCommand(name = "afk", description = "Mark yourself away: people who mention you are told, and it clears when you next speak")
    public void onAfk(GuildSlashEvent event,
                      @SlashOption(description = "Why you're away (optional)") @Nullable String reason,
                      @SlashOption(description = "Staff only: take this member off the away list instead") @Nullable User user) {
        Guild guild = event.getGuild();
        if (user != null) {
            clearFor(event, guild, user);
            return;
        }
        var afk = service.set(guild.getIdLong(), event.getUser().getIdLong(), reason);
        if (hub.afkPolicy(guild.getIdLong()).nicknameTag() && event.getMember() != null) AfkNicknames.markAway(guild, event.getMember());
        Containers.replyEphemeral(event, Containers.SUCCESS, Text.of(event, "afk.you_re_marked_away", (afk.reason().isBlank() ? "." : ": " + afk.reason())),
                Text.of(event, "afk.it_clears_the_next_time_you"));
    }

    private void clearFor(GuildSlashEvent event, Guild guild, User user) {
        Member caller = event.getMember();
        boolean staff = caller != null && (caller.hasPermission(Permission.MANAGE_SERVER) || adminRoleFilter.isAuthorized(guild, caller));
        if (!staff) {
            Containers.replyEphemeral(event, Containers.WARNING, Text.of(event, "afk.only_staff_can_take_someone_else"));
            return;
        }
        boolean was = service.clear(guild.getIdLong(), user.getIdLong());
        Member target = guild.getMember(user);
        if (was && target != null) AfkNicknames.markBack(guild, target);
        Containers.replyEphemeral(event, was ? Containers.SUCCESS : Containers.INFO,
                was ? Text.of(event, "afk.is_off_the_away_list", user.getAsMention()) : Text.of(event, "afk.wasn_t_marked_away", user.getAsMention()));
    }
}
