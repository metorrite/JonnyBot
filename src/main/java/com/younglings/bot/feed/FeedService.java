package com.younglings.bot.feed;

import com.younglings.bot.discord.Shards;
import com.younglings.bot.alerts.AutoPostRouter;
import com.younglings.bot.util.BotScheduler;
import io.github.freya022.botcommands.api.core.annotations.BEventListener;
import io.github.freya022.botcommands.api.core.events.InjectedJDAEvent;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Every ten minutes, reads each switched-on feed and posts what is new. Nothing runs for a server until it follows a feed on the
 * dashboard. The first read of a feed only records what is already there, so following a feed never posts its backlog. At most
 * {@link #MAX_POSTS_PER_RUN} items are posted per feed per run, a feed shared by several servers is read once per run, reads are a
 * second apart, and a feed that is down is logged briefly and tried again next run.
 */
@BService
public class FeedService {
    private static final Logger log = LoggerFactory.getLogger(FeedService.class);
    static final int MAX_POSTS_PER_RUN = 3;
    static final Permission[] NEEDED = {Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND};

    private final FeedRepository repository;
    private final FeedFetcher fetcher;
    private final ScheduledExecutorService executor = BotScheduler.lane("feeds");

    public FeedService(FeedRepository repository, FeedFetcher fetcher) {
        this.repository = repository;
        this.fetcher = fetcher;
    }

    @BEventListener
    public void onJdaReady(InjectedJDAEvent event) {
        JDA jda = event.getJda();
        executor.scheduleWithFixedDelay(() -> poll(jda), 3, 10, TimeUnit.MINUTES);
    }

    /** Whether JonnyBot can post in this channel, as words for the person setting it up (null when it can). */
    public static String problemWith(GuildMessageChannel channel) {
        Guild guild = channel.getGuild();
        if (!guild.getSelfMember().hasPermission(channel, NEEDED)) {
            return "The bot needs the View Channel and Send Messages permissions in #" + channel.getName() + " to post there.";
        }
        return null;
    }

    void poll(JDA jda) {
        try {
            Map<String, List<FeedItem>> readThisRun = new HashMap<>();
            Set<String> failedThisRun = new HashSet<>();
            boolean readAny = false;
            for (FeedSubscription sub : repository.allEnabled()) {
                Guild guild = Shards.guild(jda, sub.guildId());
                if (guild == null) continue;
                GuildMessageChannel channel = guild.getChannelById(GuildMessageChannel.class, sub.channelId());
                if (channel == null || problemWith(channel) != null) continue;
                try {
                    String key = sub.kind() + ":" + sub.source() + ":" + (sub.flair() == null ? "" : sub.flair().toLowerCase());
                    if (failedThisRun.contains(key)) continue;
                    List<FeedItem> items = readThisRun.get(key);
                    if (items == null) {
                        if (readAny) Thread.sleep(1000);
                        readAny = true;
                        try {
                            items = read(sub);
                        } catch (IOException | IllegalArgumentException e) {
                            failedThisRun.add(key);
                            log.warn("Feed {} {} could not be read: {}", sub.kind(), sub.source(), e.getMessage());
                            continue;
                        }
                        readThisRun.put(key, items);
                    }
                    GuildMessageChannel out = AutoPostRouter.route(channel, "a feed post");
                    for (String text : fresh(sub, items)) {
                        if (out == null) continue;
                        out.sendMessage(text).setAllowedMentions(Collections.emptyList())
                                .queue(null, t -> log.warn("Feed post failed in guild {}: {}", sub.guildId(), t.getMessage()));
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (RuntimeException e) {
                    log.warn("Feed {} in guild {} failed", sub.id(), sub.guildId(), e);
                }
            }
        } catch (Exception e) {
            log.error("Feed poll failed", e);
        }
    }

    List<FeedItem> read(FeedSubscription sub) throws IOException, InterruptedException {
        String body = fetcher.fetch(sub.feedUrl());
        return sub.kind() == FeedKind.YOUTUBE ? FeedParser.youtube(body) : FeedParser.reddit(body, sub.flair());
    }

    /**
     * The messages to post for this feed's items, oldest first, and records them (and everything else seen) as done. The first time
     * a feed is read this posts nothing and only records.
     */
    List<String> fresh(FeedSubscription sub, List<FeedItem> items) {
        List<String> ids = items.stream().map(FeedItem::id).toList();
        if (!repository.isPrimed(sub.id())) {
            repository.markSeen(sub.id(), ids);
            return List.of();
        }
        Set<String> seen = repository.seen(sub.id());
        List<FeedItem> unseen = new ArrayList<>(items.stream().filter(i -> !seen.contains(i.id())).toList());
        if (unseen.isEmpty()) return List.of();
        repository.markSeen(sub.id(), unseen.stream().map(FeedItem::id).toList());
        Collections.reverse(unseen); // feeds list newest first
        if (unseen.size() > MAX_POSTS_PER_RUN) unseen = unseen.subList(unseen.size() - MAX_POSTS_PER_RUN, unseen.size());
        return unseen.stream().map(sub::render).toList();
    }
}
