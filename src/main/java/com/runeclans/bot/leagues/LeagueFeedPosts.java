package com.runeclans.bot.leagues;

import com.runeclans.bot.config.BotConfig;
import com.runeclans.bot.discord.jobs.DiscordOutbox;
import com.runeclans.bot.i18n.Text;
import com.runeclans.bot.runeclans.HubFeedListener;
import com.runeclans.bot.runeclans.RuneClansHubClient;
import com.runeclans.bot.util.BotScheduler;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Turns the hub's league feed into channel posts (T-760): for every server that picked a league channel ({@link LeagueDiscordStore}) and whose
 * clan is in the league (the hub's {@code GET /v1/guilds/{g}/leagues}, cached), one outbox job per change ({@link LeaguePostJobs}), so the
 * post runs in the process that holds the server's shard, waits out Discord's rate limits and respects the dev-bot posting switch. A newer
 * change about the same round or version replaces one still waiting (the dedupe key). A post that can't be queued (the outbox or the
 * database away, the hub not answering for a server) is logged and lost, not retried: the feed has moved on, the posts are informational
 * and the website shows the same.
 */
@BService
public class LeagueFeedPosts implements HubFeedListener {
    private static final Logger log = LoggerFactory.getLogger(LeagueFeedPosts.class);

    private final LeagueHub hub;
    private final LeagueDiscordStore store;
    private final DiscordOutbox outbox;
    private final String sitesUrl;
    private final ScheduledExecutorService lane = BotScheduler.lane("leagues");

    public LeagueFeedPosts(LeagueHub hub, LeagueDiscordStore store, DiscordOutbox outbox, BotConfig config) {
        this.hub = hub;
        this.store = store;
        this.outbox = outbox;
        this.sitesUrl = config.getClanSitesUrl();
    }

    @Override
    public void onHubFeed(String kind, DataObject data) {
        Optional<LeaguePostLogic.Plan> plan = LeaguePostLogic.plan(kind, data);
        if (plan.isEmpty() || !hub.enabled()) return;
        lane.execute(() -> {
            try {
                fanOut(plan.get());
            } catch (RuntimeException e) {
                log.warn("League post for {} couldn't be queued: {}", plan.get().leagueId(), e.toString());
            }
        });
    }

    /** One job per server that has a channel and is in the league. Returns how many were queued. */
    int fanOut(LeaguePostLogic.Plan plan) {
        int queued = 0;
        for (Map.Entry<Long, Long> e : store.channels().entrySet()) {
            long guildId = e.getKey();
            RuneClansHubClient.Answer answer = hub.guildLeagues(guildId);
            if (answer == null || !answer.ok() || !LeaguePostLogic.concerns(answer.json(), plan.leagueId())) continue;
            String text = LeaguePostLogic.text(plan, sitesUrl, (key, args) -> Text.of(guildId, key, args));
            DataObject payload = DataObject.empty().put("channelId", Long.toString(e.getValue())).put("leagueId", plan.leagueId()).put("text", text);
            try {
                outbox.enqueue(LeaguePostJobs.KIND, guildId, 0L, plan.dedupe() + ":" + guildId, payload);
                queued++;
            } catch (RuntimeException ex) {
                log.warn("League post for server {} couldn't be queued: {}", guildId, ex.toString());
            }
        }
        return queued;
    }
}
