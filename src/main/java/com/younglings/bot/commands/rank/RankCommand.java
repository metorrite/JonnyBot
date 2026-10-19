package com.younglings.bot.commands.rank;

import com.younglings.bot.discord.Containers;
import com.younglings.bot.internal.MemberApi;
import com.younglings.bot.member.MemberProfileRepository;
import com.younglings.bot.member.MemberProfileRepository.SelfRole;
import io.github.freya022.botcommands.api.commands.annotations.Command;
import io.github.freya022.botcommands.api.commands.application.slash.GuildSlashEvent;
import io.github.freya022.botcommands.api.commands.application.slash.annotations.JDASlashCommand;
import io.github.freya022.botcommands.api.commands.application.slash.annotations.SlashOption;
import io.github.freya022.botcommands.api.commands.application.slash.annotations.TopLevelSlashCommandData;
import io.github.freya022.botcommands.api.commands.application.slash.autocomplete.AutocompleteMode;
import io.github.freya022.botcommands.api.commands.application.slash.autocomplete.annotations.AutocompleteHandler;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code /rank list|join|leave}: the self-assignable roles from Discord. It is the same list the website's self-roles page edits and the website profile
 * toggles, with the same refusals: a role that is not on the list, is managed, is above JonnyBot's own role or carries powerful permissions is never
 * given ({@link MemberApi#safeToAssign}). The self-role list has no "one at a time" groups, so there is nothing of that kind to enforce.
 */
@Command
public class RankCommand {
    static final String RANKS = "rank-self-roles";

    private final MemberProfileRepository repository;

    public RankCommand(MemberProfileRepository repository) {
        this.repository = repository;
    }

    /** One role members may take: what the list shows it as, and its real name. */
    record Option(long roleId, String label, String name) {}

    /** The option the member meant by {@code typed}: its label or role name (any case) or the role id; null when none matches or the text is blank. */
    static Option find(List<Option> options, String typed) {
        if (typed == null || typed.isBlank()) return null;
        String wanted = typed.strip().toLowerCase(Locale.ROOT);
        for (Option option : options) {
            if (Long.toString(option.roleId()).equals(wanted) || option.label().toLowerCase(Locale.ROOT).equals(wanted) || option.name().toLowerCase(Locale.ROOT).equals(wanted)) return option;
        }
        return null;
    }

    /** The configured roles that still exist and are safe to give; the same filter the website profile uses. */
    private List<Option> options(Guild guild) {
        List<Option> found = new ArrayList<>();
        for (SelfRole configured : repository.selfRoles(guild.getIdLong())) {
            Role role = guild.getRoleById(configured.roleId());
            if (role == null || !MemberApi.safeToAssign(guild, role)) continue;
            String label = configured.label() == null || configured.label().isBlank() ? role.getName() : configured.label();
            found.add(new Option(role.getIdLong(), label, role.getName()));
        }
        return found;
    }

    @TopLevelSlashCommandData(description = "Join or leave the roles this server lets you pick for yourself")
    @JDASlashCommand(name = "rank", subcommand = "list", description = "See the roles you can pick for yourself, and which you have")
    public void onList(GuildSlashEvent event) {
        Guild guild = event.getGuild();
        Member member = event.getMember();
        List<Option> options = options(guild);
        if (options.isEmpty()) {
            Containers.replyEphemeral(event, Containers.INFO, "This server has no self-assignable roles yet.");
            return;
        }
        StringBuilder lines = new StringBuilder();
        for (Option option : options) {
            Role role = guild.getRoleById(option.roleId());
            boolean has = member != null && role != null && member.getRoles().contains(role);
            lines.append(has ? "✅ " : "▫️ ").append("<@&").append(option.roleId()).append(">");
            if (!option.label().equals(option.name())) lines.append(" (").append(option.label()).append(')');
            lines.append('\n');
        }
        Containers.replyEphemeral(event, Containers.INFO, "**Roles you can pick**", lines.toString().stripTrailing(), "-# `/rank join` to take one, `/rank leave` to drop it.");
    }

    @JDASlashCommand(name = "rank", subcommand = "join", description = "Take one of the self-assignable roles")
    public void onJoin(GuildSlashEvent event, @SlashOption(description = "The role to take", autocomplete = RANKS) String role) {
        change(event, role, true);
    }

    @JDASlashCommand(name = "rank", subcommand = "leave", description = "Drop one of the self-assignable roles")
    public void onLeave(GuildSlashEvent event, @SlashOption(description = "The role to drop", autocomplete = RANKS) String role) {
        change(event, role, false);
    }

    private void change(GuildSlashEvent event, String typed, boolean join) {
        Guild guild = event.getGuild();
        Member member = event.getMember();
        Option option = find(options(guild), typed);
        Role role = option == null ? null : guild.getRoleById(option.roleId());
        if (option == null || role == null || member == null) {
            Containers.replyEphemeral(event, Containers.WARNING, "That isn't a role you can pick for yourself. Use `/rank list` to see the ones you can.");
            return;
        }
        boolean has = member.getRoles().contains(role);
        if (join == has) {
            Containers.replyEphemeral(event, Containers.INFO, join ? "You already have " + role.getAsMention() + "." : "You don't have " + role.getAsMention() + ".");
            return;
        }
        event.deferReply(true).queue();
        var action = join ? guild.addRoleToMember(member, role).reason("Self-assigned with /rank") : guild.removeRoleFromMember(member, role).reason("Removed with /rank");
        action.queue(
                ok -> event.getHook().editOriginal((join ? "You now have " : "You no longer have ") + role.getAsMention() + ".").setAllowedMentions(List.of()).queue(),
                error -> event.getHook().editOriginal("Discord wouldn't change that role just now. Try again in a moment.").queue());
    }

    @AutocompleteHandler(value = RANKS, mode = AutocompleteMode.FUZZY, showUserInput = false)
    public List<String> suggest(CommandAutoCompleteInteractionEvent event) {
        Guild guild = event.getGuild();
        if (guild == null) return List.of();
        return options(guild).stream().map(Option::label).limit(25).toList();
    }
}
