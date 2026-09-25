package com.younglings.bot.commands.runescape;

import com.younglings.bot.discord.Containers;
import com.younglings.bot.permission.AdminRoleFilter;
import com.younglings.bot.runescape.PlayerLinkService;
import com.younglings.bot.runescape.RuneScapeStatsService;
import com.younglings.bot.runescape.SkillEmojiCatalog;
import io.github.freya022.botcommands.api.commands.annotations.Command;
import io.github.freya022.botcommands.api.commands.application.CommandScope;
import io.github.freya022.botcommands.api.commands.application.annotations.Test;
import io.github.freya022.botcommands.api.commands.application.slash.GuildSlashEvent;
import io.github.freya022.botcommands.api.commands.application.slash.annotations.JDASlashCommand;
import io.github.freya022.botcommands.api.commands.application.slash.annotations.TopLevelSlashCommandData;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.components.container.Container;

import java.util.List;

/**
 * Admin-only control panel for the RS3 tracking system, meant for messing around with/inspecting
 * the system while it's built out — see {@link RsnAdminInteractionListener} for the panel's
 * buttons (poll, view history/skills/activity, pagination). {@code @Test} keeps this dev-guild-only,
 * same as {@link RsnCommand} itself, since the whole RS3 system is still work in progress;
 * separately, this also checks the Admin role at runtime, since unlike {@code /rsn}'s own admin
 * action ({@code Review Pending}), this entire command is admin-only rather than one branch of a
 * member-facing hub.
 */
@Command
public class RsnAdminCommand {
    private final PlayerLinkService linkService;
    private final RuneScapeStatsService statsService;
    private final AdminRoleFilter adminRoleFilter;
    private final SkillEmojiCatalog skillEmojiCatalog;

    public RsnAdminCommand(PlayerLinkService linkService, RuneScapeStatsService statsService,
                            AdminRoleFilter adminRoleFilter, SkillEmojiCatalog skillEmojiCatalog) {
        this.linkService = linkService;
        this.statsService = statsService;
        this.adminRoleFilter = adminRoleFilter;
        this.skillEmojiCatalog = skillEmojiCatalog;
    }

    @TopLevelSlashCommandData(scope = CommandScope.GUILD)
    @Test({})
    @JDASlashCommand(name = "rsnadmin", description = "[Dev only] RS3 tracking admin panel — list linked players, poll, inspect history")
    public void onRsnAdmin(GuildSlashEvent event) {
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

        Container panel = RsnAdminInteractionListener.buildPanel(linkService, statsService, skillEmojiCatalog, guild, member.getIdLong());
        event.replyComponents(List.of(panel)).useComponentsV2(true).setEphemeral(true).queue();
    }
}
