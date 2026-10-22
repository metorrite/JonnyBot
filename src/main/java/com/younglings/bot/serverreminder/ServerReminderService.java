package com.younglings.bot.serverreminder;

import com.younglings.bot.discord.Mentions;
import com.younglings.bot.alerts.AutoPostRouter;
import com.younglings.bot.serverreminder.ServerReminder.Status;
import com.younglings.bot.util.BotScheduler;
import io.github.freya022.botcommands.api.core.annotations.BEventListener;
import io.github.freya022.botcommands.api.core.events.InjectedJDAEvent;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Staff reminders posted in a channel, once or repeating, with an optional role ping. Every post mentions only the chosen role (never @everyone or anyone
 * named in the text). A reminder that cannot be posted (the channel is gone, JonnyBot lost permission) is stopped and marked failed rather than retried
 * forever. A repeating reminder that was missed while the bot was down posts once, not once per missed run. This is separate from the dashboard's
 * Scheduled posts, which are one-off, tidy-able announcements with no pings or repeats.
 */
@BService
public class ServerReminderService {
    private static final Logger log = LoggerFactory.getLogger(ServerReminderService.class);

    /** Why something couldn't be done, in words for the person who asked. */
    public static final class ReminderException extends RuntimeException {
        public ReminderException(String message) {
            super(message);
        }
    }

    private final ServerReminderRepository repository;
    private final ScheduledExecutorService executor = BotScheduler.lane("server-reminders");

    public ServerReminderService(ServerReminderRepository repository) {
        this.repository = repository;
    }

    @BEventListener
    public void onJdaReady(InjectedJDAEvent event) {
        JDA jda = event.getJda();
        executor.scheduleWithFixedDelay(() -> sweep(jda), 60, 30, TimeUnit.SECONDS);
    }

    /** Creates a reminder after checking everything about it; throws {@link ReminderException} with a reason otherwise. {@code every} null is a one-off. */
    public ServerReminder create(Guild guild, long actorId, String actorName, long channelId, String text, Long roleId, Instant firstAt, Duration every) {
        String textProblem = ServerReminder.textProblem(text);
        if (textProblem != null) throw new ReminderException(textProblem);
        String everyProblem = ServerReminder.everyProblem(every);
        if (everyProblem != null) throw new ReminderException(everyProblem);
        Instant now = Instant.now();
        if (firstAt == null || firstAt.isBefore(now.plusSeconds(30))) throw new ReminderException("Choose a time in the future.");
        if (firstAt.isAfter(now.plus(ServerReminder.MAX_AHEAD))) throw new ReminderException("The first post can be at most a year away.");

        GuildMessageChannel channel = guild.getChannelById(GuildMessageChannel.class, channelId);
        if (channel == null) throw new ReminderException("That channel doesn't exist in this server.");
        if (!channel.canTalk()) throw new ReminderException("JonnyBot can't post in #" + channel.getName() + ". It needs to see the channel and send messages there.");
        if (roleId != null) {
            Role role = guild.getRoleById(roleId);
            if (role == null) throw new ReminderException("That role doesn't exist in this server.");
            if (role.isPublicRole()) throw new ReminderException("@everyone can't be pinged by a reminder.");
            if (!role.isMentionable() && !guild.getSelfMember().hasPermission(Permission.MESSAGE_MENTION_EVERYONE)) {
                throw new ReminderException("@" + role.getName() + " can't be mentioned: make the role mentionable in Discord, or give JonnyBot the Mention Everyone permission.");
            }
        }
        if (repository.activeCount(guild.getIdLong()) >= ServerReminder.MAX_ACTIVE_PER_GUILD) {
            throw new ReminderException("A server can have at most " + ServerReminder.MAX_ACTIVE_PER_GUILD + " active reminders. Cancel one first.");
        }
        long id = repository.add(guild.getIdLong(), channelId, text.strip(), roleId, every, firstAt, actorId, actorName == null ? "" : actorName);
        return repository.get(guild.getIdLong(), id).orElseThrow();
    }

    public boolean cancel(long guildId, long id) {
        return repository.cancel(guildId, id);
    }

    public List<ServerReminder> list(long guildId) {
        return repository.forGuild(guildId, 20);
    }

    /** What is posted: the ping (if any) in front of the text. */
    public static String messageOf(ServerReminder reminder) {
        return (reminder.roleId() == null ? "" : "<@&" + reminder.roleId() + "> ") + reminder.text();
    }

    void sweep(JDA jda) {
        try {
            for (ServerReminder reminder : repository.due(Instant.now(), 20)) {
                Guild guild = jda.getGuildById(reminder.guildId());
                if (guild == null) continue; // not connected yet: the next pass tries again
                post(guild, reminder);
            }
        } catch (Exception e) {
            log.error("Server reminder sweep failed", e);
        }
    }

    void post(Guild guild, ServerReminder reminder) {
        GuildMessageChannel channel = guild.getChannelById(GuildMessageChannel.class, reminder.channelId());
        if (channel == null) {
            repository.fail(reminder.id(), "The channel no longer exists.");
            return;
        }
        if (!channel.canTalk()) {
            repository.fail(reminder.id(), "JonnyBot can't post in #" + channel.getName() + " any more.");
            return;
        }
        Instant now = Instant.now();
        // moved on before posting, so a slow Discord call can never make the same run post twice
        Instant next = reminder.repeating() ? ServerReminder.nextAfter(reminder.nextAt(), reminder.every(), now) : null;
        repository.sent(reminder.id(), now, next);
        GuildMessageChannel target = AutoPostRouter.route(channel, "a server reminder");
        if (target == null) return;
        var action = target.sendMessage(AutoPostRouter.defang(messageOf(reminder))).setAllowedMentions(EnumSet.noneOf(Message.MentionType.class));
        // only the reminder's own role pings, not other roles named in its text
        if (reminder.roleId() != null && !AutoPostRouter.noPings()) action = Mentions.onlyRoles(action, reminder.roleId());
        action.queue(ok -> {}, error -> log.warn("Couldn't post server reminder {} in guild {}: {}", reminder.id(), guild.getId(), error.getMessage()));
    }

    static boolean finished(Status status) {
        return status != Status.ACTIVE;
    }
}
