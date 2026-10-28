package com.runeclans.bot.commands.poll;

import com.runeclans.bot.discord.Containers;
import com.runeclans.bot.i18n.Text;
import com.runeclans.bot.permission.AdminRoleFilter;
import com.runeclans.bot.permission.MemberAccess;
import io.github.freya022.botcommands.api.commands.annotations.Command;
import io.github.freya022.botcommands.api.commands.application.slash.GuildSlashEvent;
import io.github.freya022.botcommands.api.commands.application.slash.annotations.JDASlashCommand;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;

import java.util.List;

/**
 * One command, one panel: {@code /poll} opens a private panel with a Create New Poll button and the
 * active polls you may end (see {@link PollPanel}). Members and above can use it; the access check lives
 * here and in {@link PollInteractionListener}, so it holds whatever the Integrations page allows.
 */
@Command
public class PollCommand {
    private final PollPanel pollPanel;
    private final MemberAccess memberAccess;
    private final AdminRoleFilter adminRoleFilter;

    public PollCommand(PollPanel pollPanel, MemberAccess memberAccess, AdminRoleFilter adminRoleFilter) {
        this.pollPanel = pollPanel;
        this.memberAccess = memberAccess;
        this.adminRoleFilter = adminRoleFilter;
    }

    @JDASlashCommand(name = "poll", description = "Create a poll, or end one you started")
    public void onPoll(GuildSlashEvent event) {
        Guild guild = event.getGuild();
        Member member = event.getMember();
        if (guild == null || member == null) {
            Containers.replyEphemeral(event, Containers.WARNING, Text.of(event, "poll.this_command_can_only_be_used"));
            return;
        }
        if (!memberAccess.isMemberTier(guild, member)) {
            Containers.replyEphemeral(event, Containers.WARNING, Text.of(event, "poll.polls_are_for_clan_members_link"));
            return;
        }

        boolean admin = adminRoleFilter.isAuthorized(guild, member);
        event.replyComponents(List.of(pollPanel.build(guild.getIdLong(), member.getIdLong(), admin, 0, null)))
                .useComponentsV2(true).setEphemeral(true).queue();
    }
}
