package com.younglings.bot.permission;

import com.younglings.bot.config.BotConfig;
import com.younglings.bot.discord.Containers;
import io.github.freya022.botcommands.api.commands.application.ApplicationCommandFilter;
import io.github.freya022.botcommands.api.commands.application.ApplicationCommandInfo;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.events.interaction.command.GenericCommandInteractionEvent;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Gates a command to the configured Admin role (see {@link BotConfig#getAdminRoleId()}) and
 * anything ranked above it in the guild's role hierarchy — not Discord's own "Administrator"
 * permission bit, since a role can be named "Admin" without actually carrying that bit. Opt-in
 * per command via {@code @Filter(AdminRoleFilter.class)} on a {@code @JDASlashCommand} method
 * (not applied globally). {@link #isAuthorized} is public so button/modal handlers gating a
 * specific action inside a hub command (where {@code @Filter} doesn't apply) can reuse the exact
 * same check instead of duplicating it.
 */
@BService
@NullMarked
public class AdminRoleFilter implements ApplicationCommandFilter {
    private final BotConfig botConfig;

    public AdminRoleFilter(BotConfig botConfig) {
        this.botConfig = botConfig;
    }

    @Override
    public boolean getGlobal() {
        return false;
    }

    @Override
    public @Nullable String check(GenericCommandInteractionEvent event, ApplicationCommandInfo commandInfo) {
        Guild guild = event.getGuild();
        Member member = event.getMember();

        if (guild == null || member == null) {
            Containers.replyEphemeral(event, Containers.WARNING, "This command can only be used in a server.");
            return "Not used in a guild";
        }

        if (isAuthorized(guild, member)) {
            return null;
        }

        Containers.replyEphemeral(event, Containers.WARNING, "You need the Admin role (or higher) to use this command.");
        return "Member lacks the Admin role or higher";
    }

    /**
     * True if {@code member}'s highest role sits at or above the configured Admin role in the
     * guild's role hierarchy ({@link Role#getPosition()}: higher value = more senior) — so the
     * Admin role itself, and any role ranked above it (e.g. an Owner/Co-Owner role), both pass.
     * Fails closed (returns {@code false}) if no Admin role is configured, it no longer exists in
     * this guild, or the member holds no roles above {@code @everyone}.
     */
    public boolean isAuthorized(Guild guild, Member member) {
        Long adminRoleId = botConfig.getAdminRoleId();
        if (adminRoleId == null) return false;

        Role adminRole = guild.getRoleById(adminRoleId);
        if (adminRole == null) return false;

        List<Role> memberRoles = member.getRoles(); // highest role first, see Member#getRoles()
        return !memberRoles.isEmpty() && memberRoles.getFirst().getPosition() >= adminRole.getPosition();
    }
}
