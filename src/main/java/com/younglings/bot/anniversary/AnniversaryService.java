package com.younglings.bot.anniversary;

import com.younglings.bot.alerts.AutoPostRouter;
import com.younglings.bot.anniversary.AnniversaryRepository.Settings;
import com.younglings.bot.anniversary.AnniversaryRules.Anniversary;
import com.younglings.bot.discord.Containers;
import com.younglings.bot.internal.SiteStatsRepository;
import io.github.freya022.botcommands.api.core.annotations.BEventListener;
import io.github.freya022.botcommands.api.core.events.InjectedJDAEvent;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.utils.MarkdownSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Clan anniversaries (T-194): once a week, on the day and hour a server chose (UTC), posts the members who reach a whole number of years in the
 * clan during the coming seven days, from the roster's in-game clan join dates. Off until the dashboard's Clan anniversaries page switches it on and
 * picks a channel. Names are plain text: nobody is pinged. Like every unprompted post it goes through {@link AutoPostRouter}, so a development bot
 * never posts it in a real server. A week with nobody to celebrate posts nothing.
 */
@BService
public class AnniversaryService {
    private static final Logger log = LoggerFactory.getLogger(AnniversaryService.class);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH);
    /** Names in one post; the rest are counted. Keeps the post well inside Discord's message size. */
    static final int MAX_LINES = 40;

    private final AnniversaryRepository repository;
    private final SiteStatsRepository stats;
    private final AtomicBoolean started = new AtomicBoolean();
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "clan-anniversaries");
        thread.setDaemon(true);
        return thread;
    });

    public AnniversaryService(AnniversaryRepository repository, SiteStatsRepository stats) {
        this.repository = repository;
        this.stats = stats;
    }

    public Settings settings(long guildId) {
        return repository.settings(guildId);
    }

    public void save(long guildId, boolean enabled, Long channelId, java.time.DayOfWeek weekday, int hour, int minYears) throws SQLException {
        repository.save(guildId, enabled, channelId, weekday, hour, minYears);
    }

    public static LocalDate today() {
        return LocalDate.now(ZoneOffset.UTC);
    }

    /** The anniversaries in the seven days from {@code from}, for this server's roster. */
    public List<Anniversary> week(long guildId, LocalDate from, int minYears) {
        List<AnniversaryRules.Member> members = stats.members(guildId).stream().map(m -> new AnniversaryRules.Member(m.rsn(), m.clanJoinedAt())).toList();
        return AnniversaryRules.week(members, from, minYears);
    }

    /** Whether this server has a roster at all (anniversaries need one). */
    public boolean hasRoster(long guildId) {
        return !stats.members(guildId).isEmpty();
    }

    /** The post itself. */
    static Container post(List<Anniversary> items) {
        StringBuilder lines = new StringBuilder();
        for (Anniversary a : items.subList(0, Math.min(MAX_LINES, items.size()))) {
            lines.append("- **").append(MarkdownSanitizer.escape(a.rsn())).append("** · ").append(a.years()).append(a.years() == 1 ? " year" : " years")
                    .append(" · ").append(DAY.format(a.date())).append('\n');
        }
        if (items.size() > MAX_LINES) lines.append("-# …and ").append(items.size() - MAX_LINES).append(" more.");
        return Containers.card(Containers.PRIMARY, TextDisplay.of("### 🎉 Clan anniversaries this week"),
                TextDisplay.of(lines.toString().strip()), TextDisplay.of("-# Thank you for sticking with the clan!"));
    }

    /** What "Send now" on the dashboard does: posts this week's list in the chosen channel at once. The number posted, or why not. */
    public String sendNow(Guild guild) {
        Settings s = repository.settings(guild.getIdLong());
        if (s.channelId() == null) return "Pick a channel first.";
        GuildMessageChannel channel = guild.getChannelById(GuildMessageChannel.class, s.channelId());
        if (channel == null || !channel.canTalk()) return "JonnyBot can't post in that channel. Check the channel and its permissions.";
        List<Anniversary> items = week(guild.getIdLong(), today(), s.minYears());
        if (items.isEmpty()) return "Nobody reaches an anniversary in the next seven days, so there is nothing to post.";
        channel.sendMessageComponents(List.of(post(items))).useComponentsV2(true).setAllowedMentions(List.of())
                .queue(ok -> {}, e -> log.warn("Could not post clan anniversaries in {}", guild.getId(), e));
        return null;
    }

    @BEventListener
    public void onJdaReady(InjectedJDAEvent event) {
        if (!started.compareAndSet(false, true)) return;
        JDA jda = event.getJda();
        executor.scheduleWithFixedDelay(() -> runDue(jda), 2, 15, TimeUnit.MINUTES);
    }

    private void runDue(JDA jda) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        for (long guildId : repository.enabledGuilds()) {
            try {
                Settings s = repository.settings(guildId);
                if (!s.enabled() || s.channelId() == null || !AnniversaryRules.due(now, s.weekday(), s.hour(), s.lastPostedOn())) continue;
                // marked first: a failed send is not retried every 15 minutes all day
                repository.markPosted(guildId, now.toLocalDate());
                Guild guild = jda.getGuildById(guildId);
                if (guild == null) continue;
                List<Anniversary> items = week(guildId, now.toLocalDate(), s.minYears());
                if (items.isEmpty()) continue;
                GuildMessageChannel target = AutoPostRouter.route(guild.getChannelById(GuildMessageChannel.class, s.channelId()), "Clan anniversaries for " + guild.getName());
                if (target == null || !target.canTalk()) continue;
                target.sendMessageComponents(List.of(post(items))).useComponentsV2(true).setAllowedMentions(List.of())
                        .queue(ok -> log.info("Posted {} clan anniversaries in {}", items.size(), guildId), e -> log.warn("Could not post clan anniversaries in {}", guildId, e));
            } catch (Exception e) {
                log.warn("Clan anniversaries failed for {}", guildId, e);
            }
        }
    }
}
