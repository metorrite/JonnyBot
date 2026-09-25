package com.younglings.bot.commands.runescape;

import com.younglings.bot.discord.Containers;
import com.younglings.bot.permission.AdminRoleFilter;
import io.github.freya022.botcommands.api.commands.annotations.Command;
import io.github.freya022.botcommands.api.commands.application.CommandScope;
import io.github.freya022.botcommands.api.commands.application.annotations.Test;
import io.github.freya022.botcommands.api.commands.application.slash.GuildSlashEvent;
import io.github.freya022.botcommands.api.commands.application.slash.annotations.JDASlashCommand;
import io.github.freya022.botcommands.api.commands.application.slash.annotations.TopLevelSlashCommandData;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;

import java.util.List;

/**
 * Admin-only control panel for the RS3 tracking system — bulk actions (poll everyone, sync the clan
 * roster), admin-only per-player tools, and the full verification queue. See
 * {@link RsAdminInteractionListener} for the panel's buttons, and {@link RsCommand} for the
 * member-facing side (linking, verification status, and a member's own profile — no longer shown
 * here, since {@code /rs} is the one place that renders it now).
 * <p>
 * {@code @Test} keeps this dev-guild-only, same as {@link RsCommand} itself, since the whole RS3
 * system is still work in progress; separately, this also checks the Admin role at runtime, since
 * unlike {@code /rs}'s own admin-adjacent action, this entire command is admin-only rather than one
 * branch of a member-facing hub.
 */
@Command
public class RsAdminCommand {
    private final AdminRoleFilter adminRoleFilter;
    private final RsAdminInteractionListener interactionListener;

    public RsAdminCommand(AdminRoleFilter adminRoleFilter, RsAdminInteractionListener interactionListener) {
        this.adminRoleFilter = adminRoleFilter;
        this.interactionListener = interactionListener;
    }

    @TopLevelSlashCommandData(scope = CommandScope.GUILD)
    @Test({})
    @JDASlashCommand(name = "rsadmin", description = "[Dev only] RS3 tracking admin panel — bulk actions, player lookup, verification queue")
    public void onRsAdmin(GuildSlashEvent event) {
        Guild guild = event.getGuild();
        Member member = event.getMember();

        if (guild == null || member == null) {
            event.reply("This command can only be used in a server.").setEphemeral(true).queue();
            return;
        }

        if (!adminRoleFilter.isAuthorized(guild, member)) {
            Containers.replyEphemeral(event, Containers.WARNING, "You need the Admin role (or higher) to use this.");
            return;
        }

        Container panel = interactionListener.buildPanel(guild);
        event.replyComponents(List.of(panel)).useComponentsV2(true).setEphemeral(true).queue();
    }
}
