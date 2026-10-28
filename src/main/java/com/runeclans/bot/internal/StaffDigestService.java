package com.runeclans.bot.internal;

import com.runeclans.bot.discord.ShardRouting;
import com.runeclans.bot.discord.Shards;
import com.runeclans.bot.alerts.AutoPostRouter;
import com.runeclans.bot.discord.Containers;
import com.runeclans.bot.staffdigest.StaffDigestRepository;
import com.runeclans.bot.staffdigest.StaffDigestRepository.Settings;
import com.runeclans.bot.staffdigest.StaffDigestRules;
import com.runeclans.bot.staffdigest.StaffDigestRules.EventLine;
import com.runeclans.bot.staffdigest.StaffDigestRules.Facts;
import com.runeclans.bot.ticket.TicketRepository;
import com.runeclans.bot.util.BotScheduler;
import io.github.freya022.botcommands.api.core.annotations.BEventListener;
import io.github.freya022.botcommands.api.core.events.InjectedJDAEvent;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.ScheduledEvent;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.utils.MarkdownSanitizer;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The weekly staff digest (T-193): once a week, on the day and hour a server chose (UTC), one post in a staff channel saying what needs doing:
 * the Needs attention counts, open tickets and the server's Discord events in the next seven days. Off until the dashboard's Staff digest page
 * switches it on and picks a channel. Nobody is pinged. Like every unprompted post it goes through {@link AutoPostRouter}, so a development bot never
 * posts it in a real server. In the internal package because it reads the same figures as the dashboard's Needs attention page.
 */
@BService
public class StaffDigestService {
    private static final Logger log = LoggerFactory.getLogger(StaffDigestService.class);

    private final StaffDigestRepository repository;
    private final AdminOpsApi ops;
    private final TicketRepository tickets;
    private final AtomicBoolean started = new AtomicBoolean();
    private final ScheduledExecutorService executor = BotScheduler.lane("staff-digest");

    public StaffDigestService(StaffDigestRepository repository, AdminOpsApi ops, TicketRepository tickets) {
        this.repository = repository;
        this.ops = ops;
        this.tickets = tickets;
    }

    Settings settings(long guildId) {
        return repository.settings(guildId);
    }

    StaffDigestRepository repository() {
        return repository;
    }

    /** What this week's digest would say for this server, now. */
    Facts facts(Guild guild) {
        DataObject attention = ops.attention(guild);
        Integer open = null;
        Integer escalated = null;
        try {
            var stats = tickets.stats(guild.getIdLong());
            // same rule as the Overview: a server that never used tickets gets no ticket line
            if (stats.open() + stats.closed() > 0 || !stats.byPanel().isEmpty()) {
                open = stats.open();
                escalated = stats.escalated();
            }
        } catch (Exception e) {
            log.warn("Could not read ticket figures for the staff digest of {}", guild.getId(), e);
        }
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime until = now.plus(Duration.ofDays(7));
        List<EventLine> events = guild.getScheduledEvents().stream()
                .filter(e -> e.getStatus() == ScheduledEvent.Status.SCHEDULED || e.getStatus() == ScheduledEvent.Status.ACTIVE)
                .filter(e -> e.getStartTime().isBefore(until) && (e.getStatus() == ScheduledEvent.Status.ACTIVE || e.getStartTime().isAfter(now)))
                .sorted(Comparator.comparing(ScheduledEvent::getStartTime))
                .map(e -> new EventLine(MarkdownSanitizer.escape(e.getName()), e.getStartTime()))
                .toList();
        return new Facts(attention.getInt("total", 0), attention.getArray("inactive").length(), attention.getArray("unverified").length(),
                attention.getArray("promotions").length(), attention.getArray("stale").length(), open, escalated, events);
    }

    static Container post(Facts facts) {
        return Containers.card(Containers.PRIMARY, StaffDigestRules.lines(facts).stream().map(TextDisplay::of).toList());
    }

    /** What "Send now" on the dashboard does: posts the digest in the chosen channel at once. Null when sent, otherwise why not. */
    String sendNow(Guild guild) {
        Settings s = repository.settings(guild.getIdLong());
        if (s.channelId() == null) return "Pick a channel first.";
        GuildMessageChannel channel = guild.getChannelById(GuildMessageChannel.class, s.channelId());
        if (channel == null || !channel.canTalk()) return "The bot can't post in that channel. Check the channel and its permissions.";
        channel.sendMessageComponents(List.of(post(facts(guild)))).useComponentsV2(true).setAllowedMentions(List.of())
                .queue(ok -> {}, e -> log.warn("Could not post the staff digest in {}", guild.getId(), e));
        return null;
    }

    @BEventListener
    public void onJdaReady(InjectedJDAEvent event) {
        if (!started.compareAndSet(false, true)) return;
        JDA jda = event.getJda();
        executor.scheduleWithFixedDelay(() -> runDue(jda), 3, 15, TimeUnit.MINUTES);
    }

    private void runDue(JDA jda) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        for (long guildId : repository.enabledGuilds()) {
            // a server on another process's shard is that process's to post (and mark) (T-702)
            if (!ShardRouting.owns(jda, guildId)) continue;
            try {
                Settings s = repository.settings(guildId);
                if (!s.enabled() || s.channelId() == null || !StaffDigestRules.due(now, s.weekday(), s.hour(), s.lastPostedOn())) continue;
                // marked first: a failed send is not retried every 15 minutes all day
                repository.markPosted(guildId, now.toLocalDate());
                Guild guild = Shards.guild(jda, guildId);
                if (guild == null) continue;
                GuildMessageChannel target = AutoPostRouter.route(guild.getChannelById(GuildMessageChannel.class, s.channelId()), "Staff digest for " + guild.getName());
                if (target == null || !target.canTalk()) continue;
                target.sendMessageComponents(List.of(post(facts(guild)))).useComponentsV2(true).setAllowedMentions(List.of())
                        .queue(ok -> log.info("Posted the staff digest in {}", guildId), e -> log.warn("Could not post the staff digest in {}", guildId, e));
            } catch (Exception e) {
                log.warn("The staff digest failed for {}", guildId, e);
            }
        }
    }
}
