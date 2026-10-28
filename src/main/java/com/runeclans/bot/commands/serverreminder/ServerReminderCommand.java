package com.runeclans.bot.commands.serverreminder;

import com.runeclans.bot.discord.Containers;
import com.runeclans.bot.i18n.Text;
import com.runeclans.bot.reminder.ReminderParser;
import com.runeclans.bot.serverreminder.ServerReminder;
import com.runeclans.bot.serverreminder.ServerReminderService;
import com.runeclans.bot.serverreminder.ServerReminderService.ReminderException;
import io.github.freya022.botcommands.api.commands.annotations.Command;
import io.github.freya022.botcommands.api.commands.application.slash.GuildSlashEvent;
import io.github.freya022.botcommands.api.commands.application.slash.annotations.JDASlashCommand;
import io.github.freya022.botcommands.api.commands.application.slash.annotations.SlashOption;
import io.github.freya022.botcommands.api.commands.application.slash.annotations.TopLevelSlashCommandData;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code /serverreminder add|list|cancel}: staff reminders posted in a channel, once or repeating, with an optional role ping. (A personal reminder to yourself
 * is {@code /remindme}.) The same list is managed on the dashboard's Server reminders page. Admins only unless the server changes that on the Commands page.
 */
@Command
public class ServerReminderCommand {
    private final ServerReminderService service;

    public ServerReminderCommand(ServerReminderService service) {
        this.service = service;
    }

    @TopLevelSlashCommandData(description = "Reminders posted in a channel for everyone, once or repeating")
    @JDASlashCommand(name = "serverreminder", subcommand = "add", description = "Post a reminder in a channel after a wait, once or repeating")
    public void onAdd(GuildSlashEvent event,
                      @SlashOption(description = "Where to post it") TextChannel channel,
                      @SlashOption(description = "What to say") String text,
                      @SlashOption(description = "How long until the first post, like 2h30m or 1d") String when,
                      @SlashOption(description = "Then repeat every, like 1d or 1w (at least 1h); leave out to post once") @Nullable String every,
                      @SlashOption(description = "A role to ping") @Nullable Role ping) {
        Duration wait = ReminderParser.parse(when).orElse(null);
        if (wait == null) {
            Containers.replyEphemeral(event, Containers.WARNING, Text.of(event, "serverreminder.i_couldn_t_read_as_a", when));
            return;
        }
        Duration repeat = null;
        if (every != null && !every.isBlank()) {
            repeat = ReminderParser.parse(every).orElse(null);
            if (repeat == null) {
                Containers.replyEphemeral(event, Containers.WARNING, Text.of(event, "serverreminder.i_couldn_t_read_as_a_2", every));
                return;
            }
        }
        try {
            ServerReminder made = service.create(event.getGuild(), event.getUser().getIdLong(), event.getMember() == null ? event.getUser().getName() : event.getMember().getEffectiveName(),
                    channel.getIdLong(), text, ping == null ? null : ping.getIdLong(), Instant.now().plus(wait), repeat);
            Containers.replyEphemeral(event, Containers.SUCCESS, Text.of(event, "serverreminder.reminder_will_post_in_t_r", made.id(), channel.getAsMention(), made.nextAt().getEpochSecond(), (repeat == null ? "." : Text.of(event, "serverreminder.then_every", describe(repeat)))), Text.of(event, "serverreminder.serverreminder_cancel_stops_it_the_dashboard"));
        } catch (ReminderException e) {
            Containers.replyEphemeral(event, Containers.WARNING, e.getMessage());
        }
    }

    @JDASlashCommand(name = "serverreminder", subcommand = "list", description = "See the active reminders")
    public void onList(GuildSlashEvent event) {
        List<String> lines = new ArrayList<>();
        for (ServerReminder r : service.list(event.getGuild().getIdLong())) {
            if (r.status() != ServerReminder.Status.ACTIVE) continue;
            lines.add("**#" + r.id() + "** <#" + r.channelId() + "> <t:" + r.nextAt().getEpochSecond() + ":R>" + (r.repeating() ? " · every " + describe(r.every()) : " · once")
                    + (r.roleId() == null ? "" : " · pings <@&" + r.roleId() + ">") + "\n" + shorten(r.text()));
        }
        if (lines.isEmpty()) {
            Containers.replyEphemeral(event, Containers.INFO, Text.of(event, "serverreminder.there_are_no_active_reminders"));
            return;
        }
        Containers.replyEphemeral(event, Containers.INFO, Text.of(event, "serverreminder.active_reminders"), String.join("\n\n", lines));
    }

    @JDASlashCommand(name = "serverreminder", subcommand = "cancel", description = "Stop a reminder")
    public void onCancel(GuildSlashEvent event, @SlashOption(description = "The reminder's number from /serverreminder list") long id) {
        boolean done = service.cancel(event.getGuild().getIdLong(), id);
        Containers.replyEphemeral(event, done ? Containers.SUCCESS : Containers.WARNING, done ? Text.of(event, "serverreminder.reminder_is_cancelled", id) : Text.of(event, "serverreminder.there_is_no_active_reminder", id));
    }

    static String describe(Duration every) {
        long minutes = every.toMinutes();
        if (minutes % (7 * 24 * 60) == 0) return (minutes / (7 * 24 * 60) == 1 ? "week" : minutes / (7 * 24 * 60) + " weeks");
        if (minutes % (24 * 60) == 0) return (minutes / (24 * 60) == 1 ? "day" : minutes / (24 * 60) + " days");
        if (minutes % 60 == 0) return (minutes / 60 == 1 ? "hour" : minutes / 60 + " hours");
        return minutes + " minutes";
    }

    private static String shorten(String text) {
        String one = text.replace('\n', ' ');
        return one.length() > 120 ? one.substring(0, 119) + "…" : one;
    }
}
