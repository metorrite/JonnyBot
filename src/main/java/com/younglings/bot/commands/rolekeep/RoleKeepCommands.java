package com.younglings.bot.commands.rolekeep;

import com.younglings.bot.discord.Containers;
import com.younglings.bot.rolekeep.RoleKeepRepository;
import com.younglings.bot.rolekeep.RoleKeepRepository.Kept;
import com.younglings.bot.rolekeep.RoleKeepRules;
import com.younglings.bot.rolekeep.RoleKeepService;
import io.github.freya022.botcommands.api.commands.annotations.Command;
import io.github.freya022.botcommands.api.commands.application.slash.GuildSlashEvent;
import io.github.freya022.botcommands.api.commands.application.slash.annotations.JDASlashCommand;
import io.github.freya022.botcommands.api.commands.application.slash.annotations.SlashOption;
import io.github.freya022.botcommands.api.commands.application.slash.annotations.TopLevelSlashCommandData;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * {@code /rolepersist add|remove|list} keeps a role on a member so it is given back whenever they rejoin; {@code /temprole} gives a role for a set time.
 * Both are for admins by default (a server can change who on the dashboard's Commands page), replies are private, and both refuse the roles the clan
 * role sync owns and the server's staff permission roles. The server-wide sticky list is edited on the dashboard.
 */
@Command
public class RoleKeepCommands {
    private final RoleKeepService service;
    private final RoleKeepRepository repository;

    public RoleKeepCommands(RoleKeepService service, RoleKeepRepository repository) {
        this.service = service;
        this.repository = repository;
    }

    @TopLevelSlashCommandData(description = "Keep a role on a member so it comes back when they rejoin")
    @JDASlashCommand(name = "rolepersist", subcommand = "add", description = "Keep a role on a member through leaving and rejoining")
    public void onAdd(GuildSlashEvent event, @SlashOption(description = "Whose role") Member member, @SlashOption(description = "Which role") Role role) {
        String problem = service.persist(event.getGuild(), event.getUser().getIdLong(), member, role);
        if (problem != null) {
            Containers.replyEphemeral(event, Containers.WARNING, problem);
            return;
        }
        Containers.replyEphemeral(event, Containers.SUCCESS, "**" + role.getName() + "** is now kept on " + member.getAsMention() + ": it is given back whenever they rejoin.",
                "-# Use /rolepersist remove to stop. Taking the role off them now does not stop it coming back.");
    }

    @JDASlashCommand(name = "rolepersist", subcommand = "remove", description = "Stop keeping a role on a member")
    public void onRemove(GuildSlashEvent event, @SlashOption(description = "Whose role") Member member, @SlashOption(description = "Which role") Role role) {
        boolean was = service.unpersist(event.getGuild(), member.getIdLong(), role.getIdLong());
        Containers.replyEphemeral(event, was ? Containers.SUCCESS : Containers.INFO,
                was ? "**" + role.getName() + "** is no longer kept on " + member.getAsMention() + ". The role they hold now is unchanged." : "That role was not being kept on " + member.getAsMention() + ".");
    }

    @JDASlashCommand(name = "rolepersist", subcommand = "list", description = "See the roles kept on a member")
    public void onList(GuildSlashEvent event, @SlashOption(description = "Whose roles") Member member) {
        List<Kept> kept = repository.keptFor(event.getGuild().getIdLong(), member.getIdLong());
        if (kept.isEmpty()) {
            Containers.replyEphemeral(event, Containers.INFO, "Nothing is kept on " + member.getAsMention() + ".");
            return;
        }
        List<String> lines = new ArrayList<>();
        lines.add("### Kept on " + member.getEffectiveName());
        for (Kept k : kept) lines.add("<@&" + k.roleId() + "> " + (k.source() == RoleKeepRules.Source.MANUAL ? "(set by <@" + k.addedBy() + ">)" : "(from the sticky list, will be given back once)"));
        Containers.replyEphemeral(event, Containers.INFO, lines.toArray(String[]::new));
    }

    @TopLevelSlashCommandData(description = "Give a member a role for a set time")
    @JDASlashCommand(name = "temprole", description = "Give a member a role that is taken off by itself after a time")
    public void onTemp(GuildSlashEvent event,
                       @SlashOption(description = "Who gets the role") Member member,
                       @SlashOption(description = "Which role") Role role,
                       @SlashOption(description = "For how long: 30m, 2h, 7d, 1w (1 minute to 365 days)") String duration) {
        Optional<Duration> length = RoleKeepRules.parseDuration(duration);
        if (length.isEmpty()) {
            Containers.replyEphemeral(event, Containers.WARNING, "I could not read that length of time. Use something like 30m, 2h, 7d or 1w (from 1 minute to 365 days).");
            return;
        }
        String problem = service.giveTemp(event.getGuild(), event.getUser().getIdLong(), member, role, length.get());
        if (problem != null) {
            Containers.replyEphemeral(event, Containers.WARNING, problem);
            return;
        }
        long ends = java.time.Instant.now().plus(length.get()).getEpochSecond();
        Containers.replyEphemeral(event, Containers.SUCCESS, member.getAsMention() + " has **" + role.getName() + "** until <t:" + ends + ":f> (<t:" + ends + ":R>).");
    }
}
