package com.runeclans.bot.commands.role;

import com.runeclans.bot.discord.Containers;
import com.runeclans.bot.i18n.Text;
import com.runeclans.bot.roletools.RoleToolkitService;
import io.github.freya022.botcommands.api.commands.annotations.Command;
import io.github.freya022.botcommands.api.commands.application.slash.GuildSlashEvent;
import io.github.freya022.botcommands.api.commands.application.slash.annotations.JDASlashCommand;
import io.github.freya022.botcommands.api.commands.application.slash.annotations.SlashOption;
import io.github.freya022.botcommands.api.commands.application.slash.annotations.TopLevelSlashCommandData;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;

import java.util.Optional;

/**
 * {@code /role give} and {@code /role take}: change one member's role. Admins may use any role the bot can hand out; everyone else only the roles an
 * admin put on the list of a permission group they are in (dashboard, Role toolkit). Replies are private to the caller. A server can switch the command
 * off, or limit where it works, on the dashboard's Commands page. The Member / Guest roles of the clan role sync cannot be changed with it.
 */
@Command
public class RoleCommand {
    private final RoleToolkitService toolkit;

    public RoleCommand(RoleToolkitService toolkit) {
        this.toolkit = toolkit;
    }

    @TopLevelSlashCommandData(description = "Give or take a role, for the roles you have been allowed to manage")
    @JDASlashCommand(name = "role", subcommand = "give", description = "Give a member a role")
    public void onGive(GuildSlashEvent event, @SlashOption(description = "Who gets the role") Member member, @SlashOption(description = "Which role") Role role) {
        change(event, member, role, true);
    }

    @JDASlashCommand(name = "role", subcommand = "take", description = "Take a role from a member")
    public void onTake(GuildSlashEvent event, @SlashOption(description = "Who loses the role") Member member, @SlashOption(description = "Which role") Role role) {
        change(event, member, role, false);
    }

    private void change(GuildSlashEvent event, Member target, Role role, boolean give) {
        Guild guild = event.getGuild();
        Member caller = event.getMember();
        if (caller == null) return;
        Optional<String> refusal = toolkit.refuseChange(guild, caller, target, role);
        if (refusal.isPresent()) {
            Containers.replyEphemeral(event, Containers.WARNING, refusal.get());
            return;
        }
        boolean has = target.getRoles().contains(role);
        if (give == has) {
            Containers.replyEphemeral(event, Containers.INFO, Text.of(event, has ? "role.already_has" : "role.does_not_have", target.getAsMention(), role.getName()));
            return;
        }
        var action = give ? guild.addRoleToMember(target, role) : guild.removeRoleFromMember(target, role);
        action.reason("/role " + (give ? "give" : "take") + " by " + caller.getUser().getName()).queue(
                ok -> Containers.replyEphemeral(event, Containers.SUCCESS, Text.of(event, give ? "role.gave_to" : "role.took_from", role.getName(), target.getAsMention())),
                error -> Containers.replyEphemeral(event, Containers.WARNING, Text.of(event, "role.discord_refused_that_change", error.getMessage())));
    }
}
