package com.younglings.bot.lookup;

import com.younglings.bot.config.BotConfig;
import com.younglings.bot.tracking.TrackingPostingToggle;
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

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Once a minute, posts the RuneScape timer reminders servers switched on (see {@link ReminderKind}). Each reminder remembers the occasion it last posted, so
 * a restart or a late tick never posts twice, and a source that is down simply means no post this time. Like the activity announcements it never posts
 * from a dev instance unless posting was turned on (see {@link TrackingPostingToggle}).
 */
@BService
public class LookupReminderScheduler {
    private static final Logger log = LoggerFactory.getLogger(LookupReminderScheduler.class);

    private final LookupReminderRepository reminders;
    private final VosService vos;
    private final TrackingPostingToggle posting;
    private final BotConfig botConfig;
    private final ScheduledExecutorService executor = BotScheduler.lane("lookup-reminders");

    public LookupReminderScheduler(LookupReminderRepository reminders, VosService vos, TrackingPostingToggle posting, BotConfig botConfig) {
        this.reminders = reminders;
        this.vos = vos;
        this.posting = posting;
        this.botConfig = botConfig;
    }

    @BEventListener
    public void onJdaReady(InjectedJDAEvent event) {
        if (!botConfig.getRunescapeAutoPollEnabled()) {
            log.info("RuneScape auto-poll is disabled, so the timer reminders will not run.");
            return;
        }
        JDA jda = event.getJda();
        long toNextMinute = 60 - (System.currentTimeMillis() / 1000 % 60);
        executor.scheduleAtFixedRate(() -> {
            try {
                tick(jda, Instant.now());
            } catch (Exception e) {
                log.error("Timer reminders failed", e);
            }
        }, toNextMinute + 5, 60, TimeUnit.SECONDS);
        log.info("Timer reminders scheduler started.");
    }

    void tick(JDA jda, Instant now) {
        if (!posting.isEnabled()) return;
        for (ReminderKind kind : ReminderKind.values()) {
            String key = kind.dueKey(now);
            if (key == null) continue;
            List<LookupReminder> due = reminders.enabled(kind).stream().filter(r -> !key.equals(r.lastKey())).toList();
            if (due.isEmpty()) continue;
            String text = textFor(kind, now);
            if (text == null) continue; // the source is down or behind: try again next minute, inside the grace period
            for (LookupReminder reminder : due) post(jda, reminder, key, text);
        }
    }

    private String textFor(ReminderKind kind, Instant now) {
        return switch (kind) {
            case VOS -> vos.fetch().filter(v -> v.current(now)).map(v -> vosText(v, now)).orElse(null);
            case DAILY_RESET -> resetText("Daily reset", "daily challenges and daily D&Ds", ResetTimes.nextDaily(now));
            case WEEKLY_RESET -> resetText("Weekly reset", "weekly D&Ds", ResetTimes.nextWeekly(now));
            case MONTHLY_RESET -> resetText("Monthly reset", "monthly D&Ds", ResetTimes.nextMonthly(now));
        };
    }

    static String resetText(String title, String what, Instant next) {
        return "**" + title + "** has happened: " + what + " are available again.\n-# The next one is <t:" + next.getEpochSecond() + ":R>";
    }

    static String vosText(VosService.Vos v, Instant now) {
        return "**Voice of Seren:** " + v.district1() + " and " + v.district2() + "\n-# Changes again <t:" + VosService.nextChange(now).getEpochSecond() + ":R>";
    }

    private void post(JDA jda, LookupReminder reminder, String key, String text) {
        Guild guild = jda.getGuildById(reminder.guildId());
        if (guild == null || reminder.channelId() == null) return;
        GuildMessageChannel channel = guild.getChannelById(GuildMessageChannel.class, reminder.channelId());
        if (channel == null || !channel.canTalk()) return;
        Role role = reminder.roleId() == null ? null : guild.getRoleById(reminder.roleId());
        boolean ping = role != null && (role.isMentionable() || guild.getSelfMember().hasPermission(channel, Permission.MESSAGE_MENTION_EVERYONE));
        String content = (ping ? role.getAsMention() + " " : "") + text;
        channel.sendMessage(content).setAllowedMentions(ping ? EnumSet.of(Message.MentionType.ROLE) : EnumSet.noneOf(Message.MentionType.class))
                .queue(ok -> reminders.markPosted(reminder.guildId(), reminder.kind(), key),
                        err -> log.warn("Could not post the {} reminder in guild {}: {}", reminder.kind().key(), reminder.guildId(), err.toString()));
    }
}
