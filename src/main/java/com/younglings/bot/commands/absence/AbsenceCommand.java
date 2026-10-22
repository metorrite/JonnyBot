package com.younglings.bot.commands.absence;

import com.younglings.bot.absence.AbsenceRepository.Notice;
import com.younglings.bot.absence.AbsenceRules;
import com.younglings.bot.absence.AbsenceService;
import com.younglings.bot.discord.Containers;
import com.younglings.bot.permission.AdminRoleFilter;
import io.github.freya022.botcommands.api.commands.annotations.Command;
import io.github.freya022.botcommands.api.commands.application.slash.GuildSlashEvent;
import io.github.freya022.botcommands.api.commands.application.slash.annotations.JDASlashCommand;
import io.github.freya022.botcommands.api.commands.application.slash.annotations.Length;
import io.github.freya022.botcommands.api.commands.application.slash.annotations.LongRange;
import io.github.freya022.botcommands.api.commands.application.slash.annotations.SlashOption;
import io.github.freya022.botcommands.api.commands.application.slash.annotations.TopLevelSlashCommandData;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.utils.MarkdownSanitizer;
import org.jetbrains.annotations.Nullable;

import java.time.LocalDate;
import java.util.List;

/**
 * {@code /absence set|end|list}: a member's leave of absence (T-190). While a notice runs, the dashboard's Needs attention list doesn't call them
 * inactive or not capped. The same notice is on the member's website profile; staff see everyone's and can end one early on the dashboard (Leave
 * of absence page). Nothing works until the server switches it on there. Not the same as {@code /afk}, which clears the moment you next speak.
 */
@Command
public class AbsenceCommand {
    private static final int LIST_MAX = 25;

    private final AbsenceService service;
    private final AdminRoleFilter adminRoleFilter;

    public AbsenceCommand(AbsenceService service, AdminRoleFilter adminRoleFilter) {
        this.service = service;
        this.adminRoleFilter = adminRoleFilter;
    }

    private static String day(LocalDate date) {
        return AbsenceRules.day(date);
    }

    @TopLevelSlashCommandData(description = "Tell staff you're away for a while (holiday, busy) so you aren't flagged as inactive")
    @JDASlashCommand(name = "absence", subcommand = "set", description = "Say you're away for a number of days")
    public void onSet(GuildSlashEvent event,
                      @SlashOption(description = "How many days, counting today (1 to 90)") @LongRange(from = 1, to = AbsenceRules.MAX_DAYS) long days,
                      @SlashOption(description = "Why (optional; staff see it)") @Length(max = AbsenceRules.MAX_REASON) @Nullable String reason) {
        Guild guild = event.getGuild();
        LocalDate until = AbsenceRules.untilForDays((int) days, AbsenceService.today());
        AbsenceService.Outcome outcome = service.set(guild.getIdLong(), event.getUser().getIdLong(), until, reason);
        if (!outcome.ok()) {
            Containers.replyEphemeral(event, Containers.WARNING, outcome.problem());
            return;
        }
        Containers.replyEphemeral(event, Containers.SUCCESS, "You're on leave until the end of **" + day(until) + "**" + (outcome.notice().reason().isBlank() ? "." : ": " + MarkdownSanitizer.escape(outcome.notice().reason())),
                "-# You won't be flagged as inactive meanwhile. Back early? `/absence end`.");
    }

    @JDASlashCommand(name = "absence", subcommand = "end", description = "Say you're back")
    public void onEnd(GuildSlashEvent event) {
        Guild guild = event.getGuild();
        if (!service.enabled(guild.getIdLong())) {
            Containers.replyEphemeral(event, Containers.WARNING, "Leave of absence isn't switched on in this server.");
            return;
        }
        boolean was = service.end(guild.getIdLong(), event.getUser().getIdLong());
        Containers.replyEphemeral(event, was ? Containers.SUCCESS : Containers.INFO, was ? "Welcome back! Your leave is over." : "You weren't on leave.");
    }

    @JDASlashCommand(name = "absence", subcommand = "list", description = "See who is away and until when")
    public void onList(GuildSlashEvent event) {
        Guild guild = event.getGuild();
        if (!service.enabled(guild.getIdLong())) {
            Containers.replyEphemeral(event, Containers.WARNING, "Leave of absence isn't switched on in this server.");
            return;
        }
        Member caller = event.getMember();
        boolean staff = caller != null && (caller.hasPermission(Permission.MANAGE_SERVER) || adminRoleFilter.isAuthorized(guild, caller));
        List<Notice> notices = service.active(guild.getIdLong());
        if (notices.isEmpty()) {
            Containers.replyEphemeral(event, Containers.INFO, "Nobody is on leave right now.");
            return;
        }
        StringBuilder lines = new StringBuilder();
        for (Notice n : notices.subList(0, Math.min(LIST_MAX, notices.size()))) {
            lines.append("- <@").append(n.userId()).append("> until ").append(day(n.until()));
            // reasons are for staff: a member only sees who is away and until when
            if (staff && !n.reason().isBlank()) lines.append(" · ").append(MarkdownSanitizer.escape(n.reason()));
            lines.append('\n');
        }
        if (notices.size() > LIST_MAX) lines.append("-# …and ").append(notices.size() - LIST_MAX).append(" more (the full list is on the dashboard).");
        Containers.replyEphemeral(event, Containers.INFO, "On leave (" + notices.size() + ")", lines.toString().strip());
    }
}
