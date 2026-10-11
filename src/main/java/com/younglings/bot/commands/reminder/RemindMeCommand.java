package com.younglings.bot.commands.reminder;

import com.younglings.bot.discord.Containers;
import com.younglings.bot.reminder.ReminderParser;
import com.younglings.bot.reminder.ReminderRepository;
import com.younglings.bot.reminder.ReminderRepository.Reminder;
import io.github.freya022.botcommands.api.commands.annotations.Command;
import io.github.freya022.botcommands.api.commands.application.slash.GuildSlashEvent;
import io.github.freya022.botcommands.api.commands.application.slash.annotations.JDASlashCommand;
import io.github.freya022.botcommands.api.commands.application.slash.annotations.SlashOption;
import io.github.freya022.botcommands.api.commands.application.slash.annotations.TopLevelSlashCommandData;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * {@code /remindme}: personal reminders. {@code add} takes how long to wait (10m, 2h30m, 1d, 3 days) and what to say; {@code list} shows
 * your own; {@code cancel} removes one. Everything is private to the person using it, and each person can hold {@value ReminderRepository#MAX_PER_USER}.
 */
@Command
public class RemindMeCommand {
    private static final int MAX_TEXT = 500;

    private final ReminderRepository repository;

    public RemindMeCommand(ReminderRepository repository) {
        this.repository = repository;
    }

    @TopLevelSlashCommandData(description = "Personal reminders: set one, list yours, or cancel one")
    @JDASlashCommand(name = "remindme", subcommand = "add", description = "Be reminded of something later")
    public void onAdd(GuildSlashEvent event,
                      @SlashOption(description = "How long from now, like 10m, 2h30m, 1d or 3 days") String when,
                      @SlashOption(description = "What to remind you of") String what) {
        var delay = ReminderParser.parse(when);
        if (delay.isEmpty()) {
            Containers.replyEphemeral(event, Containers.WARNING, "I couldn't read **" + when.strip() + "** as a length of time. Try something like `10m`, `2h30m`, `1d` or `3 days` (from 1 minute to 1 year).");
            return;
        }
        String text = what.strip();
        if (text.isEmpty() || text.length() > MAX_TEXT) {
            Containers.replyEphemeral(event, Containers.WARNING, "Say what to remind you of, in at most " + MAX_TEXT + " characters.");
            return;
        }
        long user = event.getUser().getIdLong();
        if (repository.countFor(user) >= ReminderRepository.MAX_PER_USER) {
            Containers.replyEphemeral(event, Containers.WARNING, "You already have " + ReminderRepository.MAX_PER_USER + " reminders. Cancel one with `/remindme cancel`, or wait for one to arrive.");
            return;
        }
        Instant due = Instant.now().plus(delay.get());
        long id = repository.add(event.getGuild().getIdLong(), user, event.getChannel().getIdLong(), text, due);
        Containers.replyEphemeral(event, Containers.SUCCESS, "Okay, I'll remind you <t:" + due.getEpochSecond() + ":R>: " + text,
                "-# Reminder #" + id + ". It arrives by DM (or here, if your DMs are closed).");
    }

    @JDASlashCommand(name = "remindme", subcommand = "list", description = "See your reminders")
    public void onList(GuildSlashEvent event) {
        List<Reminder> mine = repository.forUser(event.getUser().getIdLong());
        if (mine.isEmpty()) {
            Containers.replyEphemeral(event, Containers.INFO, "You have no reminders. Set one with `/remindme add`.");
            return;
        }
        String[] lines = mine.stream()
                .map(r -> "`#" + r.id() + "` <t:" + r.dueAt().getEpochSecond() + ":R>: " + (r.text().length() > 100 ? r.text().substring(0, 100) + "…" : r.text()))
                .toArray(String[]::new);
        Containers.replyEphemeral(event, Containers.INFO, lines);
    }

    @JDASlashCommand(name = "remindme", subcommand = "cancel", description = "Cancel one of your reminders")
    public void onCancel(GuildSlashEvent event, @SlashOption(description = "Its number, from /remindme list") long id) {
        if (repository.delete(id, event.getUser().getIdLong())) {
            Containers.replyEphemeral(event, Containers.SUCCESS, "Cancelled reminder #" + id + ".");
        } else {
            Containers.replyEphemeral(event, Containers.WARNING, "You have no reminder #" + id + ". See `/remindme list` for yours.");
        }
    }
}
