package com.younglings.bot.retention;

import com.younglings.bot.discord.Shards;
import com.younglings.bot.config.BotConfig;
import com.younglings.bot.rolepanel.RolePanelRepository;
import com.younglings.bot.starboard.StarboardRepository;
import com.younglings.bot.util.BotScheduler;
import io.github.freya022.botcommands.api.core.annotations.BEventListener;
import io.github.freya022.botcommands.api.core.events.InjectedJDAEvent;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.JDA;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Deletes a server's data once the bot has been gone from it for a while. A server's data is kept when the bot is removed, so
 * adding it back picks up where it left off; after {@link BotConfig#getGuildDataRetentionDays()} days without it coming back, the
 * data is removed for good, so JonnyBot isn't holding on to a stranger's server forever (which a public bot should not do).
 * <p>
 * Safeguards: never the home server ({@code GUILD_ID}), never a server the bot is in again, never the trusted/premium server lists,
 * and a sweep with {@code GUILD_DATA_RETENTION_DRY_RUN=true} only logs what it would remove. Setting the retention to 0 turns it off.
 */
@BService
public class GuildRetentionService {
    private static final Logger log = LoggerFactory.getLogger(GuildRetentionService.class);

    private static final Duration CHECK_INTERVAL = Duration.ofHours(24);
    private static final Duration INITIAL_DELAY = Duration.ofMinutes(10);

    private final GuildRetentionRepository repository;
    private final BotConfig botConfig;
    /** Repositories that cache a server's rows in memory: told to forget the server after a purge deletes its rows behind their back. */
    private final StarboardRepository starboard;
    private final RolePanelRepository rolePanels;
    private final ScheduledExecutorService executor = BotScheduler.lane("guild-retention");

    public GuildRetentionService(GuildRetentionRepository repository, BotConfig botConfig, StarboardRepository starboard, RolePanelRepository rolePanels) {
        this.repository = repository;
        this.botConfig = botConfig;
        this.starboard = starboard;
        this.rolePanels = rolePanels;
    }

    @BEventListener
    public void onJdaReady(InjectedJDAEvent event) {
        long days = botConfig.getGuildDataRetentionDays();
        if (days <= 0) {
            log.info("Server data retention is off (GUILD_DATA_RETENTION_DAYS=0): a removed server's data is kept forever.");
            return;
        }
        JDA jda = event.getJda();
        executor.scheduleWithFixedDelay(() -> sweep(jda, days), INITIAL_DELAY.toSeconds(), CHECK_INTERVAL.toSeconds(), TimeUnit.SECONDS);
    }

    public void onLeft(long guildId) {
        repository.recordDeparture(guildId);
    }

    public void onJoined(long guildId) {
        repository.cancelDeparture(guildId);
    }

    void sweep(JDA jda, long days) {
        try {
            boolean dryRun = botConfig.getGuildDataRetentionDryRun();
            Long home = botConfig.getGuildId();
            List<Long> due = repository.departedBefore(Instant.now().minus(Duration.ofDays(days)));
            for (long guildId : due) {
                if (Shards.guild(jda, guildId) != null) { // it came back without us noticing: not gone
                    repository.cancelDeparture(guildId);
                    continue;
                }
                if (home != null && home == guildId) continue;
                purge(guildId, dryRun);
            }
        } catch (Exception e) {
            log.error("Server data retention sweep failed", e);
        }
    }

    private void purge(long guildId, boolean dryRun) {
        List<String> tables = repository.serverTables();
        if (dryRun) {
            int rows = 0;
            for (String table : tables) {
                if (GuildPurge.KEEP.contains(table)) continue;
                try {
                    rows += repository.count(table, guildId);
                } catch (Exception e) {
                    log.warn("Dry run: couldn't count {} for server {}", table, guildId, e);
                }
            }
            log.info("Retention dry run: server {} left more than the retention period ago; {} rows would be deleted.", guildId, rows);
            return;
        }
        GuildPurge.Result result;
        try {
            result = GuildPurge.purge(tables, guildId, repository::delete);
        } finally {
            starboard.evict(guildId);
            rolePanels.evict(guildId);
        }
        if (result.complete()) {
            repository.finish(guildId);
            log.info("Retention: deleted {} rows of data for server {} (the bot has been gone from it for over the retention period).", result.totalRows(), guildId);
        } else {
            log.warn("Retention: server {} was only partly deleted ({} rows); these tables refused and will be retried: {}", guildId, result.totalRows(), result.refused());
        }
    }
}
