package com.runeclans.bot.plugin;

import com.runeclans.bot.discord.Shards;
import com.runeclans.bot.plugin.PluginPointsRules.Rule;
import com.runeclans.bot.points.PointsRepository;
import com.runeclans.bot.points.PointsRules.Category;
import com.runeclans.bot.points.PointsService;
import io.github.freya022.botcommands.api.core.annotations.BEventListener;
import io.github.freya022.botcommands.api.core.events.InjectedJDAEvent;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Pays clan points for what the plugin reports (T-061), by each clan's own rules ({@link PluginPointsRules}; all off until staff set them on
 * the dashboard). For a newly stored report it looks at the servers JonnyBot shares with the member, and in each one that uses points and
 * has a rule for that type, pays the rule's points through {@link PointsService}, so every points rule applies as for any other route.
 * <p>
 * <b>Never twice:</b> each payment's source key is {@code plugin:<TYPE>:<user>:<report id>}, and the ledger pays a key once per server, so a
 * re-sent batch pays nothing. A daily cap per type (UTC day) is checked before paying; an award that would pass it is skipped, never cut.
 */
@BService
public class PluginPointsSink implements PluginEventSink {
    private static final Logger log = LoggerFactory.getLogger(PluginPointsSink.class);

    private final PluginPointsRepository rules;
    private final PointsRepository points;
    private final PointsService pointsService;
    private volatile JDA jda;

    public PluginPointsSink(PluginPointsRepository rules, PointsRepository points, PointsService pointsService) {
        this.rules = rules;
        this.points = points;
        this.pointsService = pointsService;
    }

    @BEventListener
    public void onJdaReady(InjectedJDAEvent event) {
        this.jda = event.getJda();
    }

    static String keyPrefix(PluginEventType type) {
        return "plugin:" + type.name() + ":";
    }

    @Override
    public void accepted(long userId, String rsn, List<PluginPayload.Event> events) {
        JDA current = jda;
        if (current == null || events.isEmpty()) return;
        User user = Shards.user(current, userId);
        if (user == null) return;
        List<Long> guilds = new ArrayList<>();
        for (Guild guild : Shards.mutualGuilds(user)) guilds.add(guild.getIdLong());
        pay(userId, guilds, events, Instant.now());
    }

    /** Pays the member in each of these servers for each report, by that server's rules. */
    void pay(long userId, List<Long> guildIds, List<PluginPayload.Event> events, Instant now) {
        Instant today = LocalDate.ofInstant(now, ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC).toInstant();
        for (long guildId : guildIds) {
            try {
                List<Rule> clanRules = rules.rules(guildId);
                if (clanRules.isEmpty() || !points.settings(guildId).enabled()) continue;
                for (PluginPayload.Event reported : events) {
                    Rule rule = clanRules.stream().filter(r -> r.type() == reported.type()).findFirst().orElse(null);
                    if (rule == null) continue;
                    DataObject data = dataOf(reported);
                    if (!PluginPointsRules.pays(rule, data)) continue;
                    int amount = PluginPointsRules.afterCap(rule, rule.dailyCap() > 0 ? rules.paidSince(guildId, userId, rule.type(), today) : 0);
                    if (amount <= 0) continue;
                    String key = keyPrefix(rule.type()) + userId + ":" + reported.key();
                    var result = pointsService.apply(guildId, userId, amount, PluginPointsRules.reason(rule.type(), data), Category.PLUGIN, key, null);
                    if (result.outcome() == PointsService.Outcome.INVALID) {
                        log.debug("Plugin points for {} in {} not paid: {}", reported.type().name().toLowerCase(Locale.ROOT), guildId, result.message());
                    }
                }
            } catch (RuntimeException e) {
                // one server's points never stop another's, nor the upload
                log.warn("Couldn't pay plugin points for user {} in server {}", userId, guildId, e);
            }
        }
    }

    private static DataObject dataOf(PluginPayload.Event reported) {
        try {
            return DataObject.fromJson(reported.dataJson());
        } catch (RuntimeException e) {
            return DataObject.empty();
        }
    }
}
