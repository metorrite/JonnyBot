package com.younglings.bot.commands.warn;

import com.younglings.bot.discord.Containers;
import com.younglings.bot.warning.AutopunishService;
import com.younglings.bot.warning.WarnPolicy;
import com.younglings.bot.warning.WarningRepository;
import com.younglings.bot.warning.WarningRepository.Warning;
import io.github.freya022.botcommands.api.commands.annotations.Command;
import io.github.freya022.botcommands.api.commands.application.slash.GuildSlashEvent;
import io.github.freya022.botcommands.api.commands.application.slash.annotations.JDASlashCommand;
import io.github.freya022.botcommands.api.commands.application.slash.annotations.SlashOption;
import io.github.freya022.botcommands.api.commands.application.slash.annotations.TopLevelSlashCommandData;
import net.dv8tion.jda.api.entities.Member;

import java.util.List;

/**
 * {@code /warn}: a record of warnings given to members. {@code add} records one (and tells the member by DM when it can),
 * {@code list} shows a member's history, {@code remove} takes one back. Replies are private to the moderator. By default only
 * admins can use it; a server can open it to moderators from the dashboard's Commands page.
 */
@Command
public class WarnCommand {
    private final WarningRepository repository;
    private final AutopunishService autopunish;

    public WarnCommand(WarningRepository repository, AutopunishService autopunish) {
        this.repository = repository;
        this.autopunish = autopunish;
    }

    @TopLevelSlashCommandData(description = "Warn members and keep a record of it")
    @JDASlashCommand(name = "warn", subcommand = "add", description = "Warn a member")
    public void onAdd(GuildSlashEvent event,
                      @SlashOption(description = "Who to warn") Member member,
                      @SlashOption(description = "Why") String reason) {
        Member moderator = event.getMember();
        String refusal = WarnPolicy.refusal(member.getIdLong() == event.getUser().getIdLong(), member.getUser().isBot(),
                moderator != null && moderator.canInteract(member), reason);
        if (refusal != null) {
            Containers.replyEphemeral(event, Containers.WARNING, refusal);
            return;
        }
        String text = reason.strip();
        long guildId = event.getGuild().getIdLong();
        long id = repository.add(guildId, member.getIdLong(), event.getUser().getIdLong(), text);
        int total = repository.countFor(guildId, member.getIdLong());
        member.getUser().openPrivateChannel()
                .flatMap(channel -> channel.sendMessage("You have been warned in **" + event.getGuild().getName() + "**: " + text))
                .queue(ok -> {}, error -> {});
        String punished = autopunish.applyFor(event.getGuild(), member, total);
        var lines = new java.util.ArrayList<String>();
        lines.add("Warned " + member.getAsMention() + " (warning #" + id + ").");
        lines.add("-# They now have " + WarnPolicy.count(total) + ". They were told by DM if their DMs are open.");
        if (punished != null) lines.add(punished);
        Containers.replyEphemeral(event, Containers.SUCCESS, lines.toArray(String[]::new));
    }

    @JDASlashCommand(name = "warn", subcommand = "list", description = "See a member's warnings")
    public void onList(GuildSlashEvent event, @SlashOption(description = "Whose warnings") Member member) {
        List<Warning> warnings = repository.forUser(event.getGuild().getIdLong(), member.getIdLong());
        if (warnings.isEmpty()) {
            Containers.replyEphemeral(event, Containers.INFO, member.getAsMention() + " has no warnings.");
            return;
        }
        List<String> lines = new java.util.ArrayList<>();
        lines.add("### " + WarnPolicy.count(warnings.size()) + " for " + member.getEffectiveName());
        for (Warning w : warnings.stream().limit(10).toList()) {
            String reason = w.reason().length() > 150 ? w.reason().substring(0, 150) + "…" : w.reason();
            lines.add("`#" + w.id() + "` <t:" + w.createdAt().getEpochSecond() + ":d> by <@" + w.moderatorId() + ">: " + reason);
        }
        if (warnings.size() > 10) lines.add("-# Showing the latest 10. The dashboard has the full history.");
        Containers.replyEphemeral(event, Containers.INFO, lines.toArray(String[]::new));
    }

    @JDASlashCommand(name = "warn", subcommand = "remove", description = "Take back a warning")
    public void onRemove(GuildSlashEvent event, @SlashOption(description = "Its number, from /warn list") long id) {
        if (repository.delete(event.getGuild().getIdLong(), id)) {
            Containers.replyEphemeral(event, Containers.SUCCESS, "Removed warning #" + id + ".");
        } else {
            Containers.replyEphemeral(event, Containers.WARNING, "This server has no warning #" + id + ".");
        }
    }
}
