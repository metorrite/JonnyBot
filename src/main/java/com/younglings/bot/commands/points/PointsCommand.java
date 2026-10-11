package com.younglings.bot.commands.points;

import com.younglings.bot.discord.Containers;
import com.younglings.bot.points.PointsRepository;
import com.younglings.bot.points.PointsRepository.Balance;
import com.younglings.bot.points.PointsRepository.Settings;
import com.younglings.bot.points.PointsRules;
import com.younglings.bot.points.PointsRules.Category;
import com.younglings.bot.points.PointsService;
import io.github.freya022.botcommands.api.commands.annotations.Command;
import io.github.freya022.botcommands.api.commands.application.slash.GuildSlashEvent;
import io.github.freya022.botcommands.api.commands.application.slash.annotations.JDASlashCommand;
import io.github.freya022.botcommands.api.commands.application.slash.annotations.SlashOption;
import io.github.freya022.botcommands.api.commands.application.slash.annotations.TopLevelSlashCommandData;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Member;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code /points}: a server's points. {@code balance} and {@code top} are open to everyone; {@code award} (which also takes points
 * back with a negative amount) is for members who can manage the server, and always needs a reason so the history reads clearly.
 */
@Command
public class PointsCommand {
    private final PointsRepository repository;
    private final PointsService service;

    public PointsCommand(PointsRepository repository, PointsService service) {
        this.repository = repository;
        this.service = service;
    }

    @TopLevelSlashCommandData(description = "Your points in this server")
    @JDASlashCommand(name = "points", subcommand = "balance", description = "See your points, or another member's")
    public void onBalance(GuildSlashEvent event, @SlashOption(description = "Whose points (you, by default)") @Nullable Member member) {
        Settings settings = repository.settings(event.getGuild().getIdLong());
        if (!enabled(event, settings)) return;
        Member who = member != null ? member : event.getMember();
        long balance = repository.balance(event.getGuild().getIdLong(), who.getIdLong());
        List<String> lines = new ArrayList<>();
        lines.add("### " + who.getEffectiveName() + ": " + PointsRules.format(balance, settings.unit()));
        for (var e : repository.history(event.getGuild().getIdLong(), who.getIdLong(), 5)) {
            String sign = e.points() > 0 ? "+" : "";
            lines.add("`" + sign + e.points() + "` <t:" + e.createdAt().getEpochSecond() + ":d> " + trim(e.reason()));
        }
        Containers.replyEphemeral(event, Containers.INFO, lines.toArray(String[]::new));
    }

    @JDASlashCommand(name = "points", subcommand = "top", description = "The members with the most points")
    public void onTop(GuildSlashEvent event) {
        Settings settings = repository.settings(event.getGuild().getIdLong());
        if (!enabled(event, settings)) return;
        List<Balance> top = repository.top(event.getGuild().getIdLong(), 10);
        if (top.isEmpty()) {
            Containers.replyEphemeral(event, Containers.INFO, "Nobody has any " + settings.unit() + " yet.");
            return;
        }
        List<String> lines = new ArrayList<>();
        lines.add("### Top " + settings.unit());
        int rank = 1;
        for (Balance b : top) lines.add(rank++ + ". <@" + b.userId() + "> " + PointsRules.format(b.points(), settings.unit()));
        Containers.replyEphemeral(event, Containers.INFO, lines.toArray(String[]::new));
    }

    @JDASlashCommand(name = "points", subcommand = "award", description = "Give points to a member (a negative amount takes them back)")
    public void onAward(GuildSlashEvent event,
                        @SlashOption(description = "Who to give points to") Member member,
                        @SlashOption(description = "How many; negative to take back") long amount,
                        @SlashOption(description = "Why, for the history") String reason) {
        Member staff = event.getMember();
        if (staff == null || !staff.hasPermission(Permission.MANAGE_SERVER)) {
            Containers.replyEphemeral(event, Containers.WARNING, "You need the Manage Server permission to award points.");
            return;
        }
        Settings settings = repository.settings(event.getGuild().getIdLong());
        if (!enabled(event, settings)) return;
        if (member.getUser().isBot()) {
            Containers.replyEphemeral(event, Containers.WARNING, "Bots can't hold points.");
            return;
        }
        var result = service.apply(event.getGuild().getIdLong(), member.getIdLong(), amount, reason, Category.MANUAL, null, event.getUser().getIdLong());
        if (result.outcome() != PointsService.Outcome.DONE) {
            Containers.replyEphemeral(event, Containers.WARNING, result.message());
            return;
        }
        String verb = amount > 0 ? "Gave " : "Took ";
        Containers.replyEphemeral(event, Containers.SUCCESS,
                verb + PointsRules.format(Math.abs(amount), settings.unit()) + (amount > 0 ? " to " : " from ") + member.getAsMention() + ".",
                "-# They now have " + PointsRules.format(result.balance(), settings.unit()) + ".");
    }

    private static boolean enabled(GuildSlashEvent event, Settings settings) {
        if (settings.enabled()) return true;
        Containers.replyEphemeral(event, Containers.INFO, "Points aren't turned on in this server yet. An admin can switch them on from the dashboard.");
        return false;
    }

    private static String trim(String s) {
        return s.length() > 80 ? s.substring(0, 80) + "…" : s;
    }
}
