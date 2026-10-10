package com.younglings.bot.permission;

import com.younglings.bot.discord.Containers;
import io.github.freya022.botcommands.api.commands.application.ApplicationCommandFilter;
import io.github.freya022.botcommands.api.commands.application.ApplicationCommandInfo;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.events.interaction.command.GenericCommandInteractionEvent;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Gates a command to the server's Admin group (see {@link PermissionGroupService}: one or more server roles, and by default
 * anything ranked above the lowest of them in the guild's role hierarchy) — not Discord's own "Administrator" permission bit,
 * since a role can be named "Admin" without actually carrying that bit. Opt-in per command via
 * {@code @Filter(AdminRoleFilter.class)} on a {@code @JDASlashCommand} method (not applied
 * globally). {@link #isAuthorized} is public so button/modal handlers gating a specific action
 * inside a hub command (where {@code @Filter} doesn't apply) can reuse the exact same check instead
 * of duplicating it.
 * <p>
 * Deliberately <em>not</em> what gates {@code /configure} — this role is itself one of the things
 * {@code /configure} sets, so bootstrapping a brand-new guild can't depend on it already existing.
 * {@code /configure} checks Discord's native Administrator permission instead.
 */
@BService
@NullMarked
public class AdminRoleFilter implements ApplicationCommandFilter {
    private final PermissionGroupService groups;

    public AdminRoleFilter(PermissionGroupService groups) {
        this.groups = groups;
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
     * The Support tier: holds the configured Support role, <em>or</em> is Admin tier (see
     * {@link #isAuthorized}). It's for the narrow set of tools Support exists for — reviewing and verifying
     * RSN requests — and every Support-allowed action checks this; everything else still checks
     * {@link #isAuthorized}. Holding a higher staff role (Moderator, Developer) does not by itself grant it:
     * give those people the Support role too if they should review requests.
     */
    public boolean isSupportTier(Guild guild, Member member) {
        return isAuthorized(guild, member) || groups.isMember(guild, member, PermissionGroup.SUPPORT);
    }

    /**
     * True if {@code member} is in the Admin group: holds one of its roles or, as the Admin group does by default, any role ranked
     * above the lowest of them, so the Admin role itself and anything senior to it (an Owner or Co-Owner role) both pass. Fails
     * closed (returns {@code false}) if the group has no roles, or none of them exists in this guild any more.
     */
    public boolean isAuthorized(Guild guild, Member member) {
        return groups.isMember(guild, member, PermissionGroup.ADMIN);
    }
}
