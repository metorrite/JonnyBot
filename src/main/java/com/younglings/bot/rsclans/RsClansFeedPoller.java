package com.younglings.bot.rsclans;

import com.younglings.bot.plugin.PluginEventSink;
import com.younglings.bot.plugin.PluginEventType;
import com.younglings.bot.plugin.PluginPayload;
import io.github.freya022.botcommands.api.core.annotations.BEventListener;
import io.github.freya022.botcommands.api.core.events.InjectedJDAEvent;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * With {@code RSCLANS_HUB_ENABLED} on, plugin events reach the bot from the RSClans hub instead of its own ingest (rsclans-hub HUB-DESIGN.md
 * section 7): every 30 seconds it reads JonnyBot's change feed and hands each {@code plugin.event} to the same {@link PluginEventSink}s
 * (points, event tiles) the bot's own ingest uses. It also, when the bot connects, registers the servers it is in, and when a player grants
 * JonnyBot with identity, adds them to those of its servers they are a member of.
 * <ul>
 *   <li>The cursor is stored ({@link RsClansFeedCursor}); on the very first run the feed is skipped to its end, so switching on never replays
 *       old events into points.</li>
 *   <li>An event is passed on only when the player granted JonnyBot identity (that is how the bot knows whose it is); others are skipped.</li>
 *   <li>Off by default: with the flag off nothing here runs.</li>
 * </ul>
 */
@BService
public class RsClansFeedPoller {
    private static final Logger log = LoggerFactory.getLogger(RsClansFeedPoller.class);
    static final int PAGE = 200;

    private final RsClansHubClient hub;
    private final Relay relay;
    private volatile JDA jda;
    private ScheduledExecutorService executor;

    public RsClansFeedPoller(RsClansHubClient hub, RsClansFeedCursor cursor, List<PluginEventSink> sinks) {
        this.hub = hub;
        this.relay = new Relay(hub, cursor::load, cursor::save, sinks, this::addToGuilds);
        if (hub.enabled()) start();
    }

    @BEventListener
    public void onJdaReady(InjectedJDAEvent event) {
        jda = event.getJda();
        if (!hub.enabled()) return;
        int registered = 0;
        for (Guild g : jda.getGuilds()) if (hub.putGuild(g.getIdLong(), g.getName())) registered++;
        log.info("RSClans hub: registered {} of {} servers", registered, jda.getGuilds().size());
    }

    private void start() {
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "rsclans-feed");
            t.setDaemon(true);
            return t;
        });
        executor.scheduleWithFixedDelay(() -> {
            try {
                relay.poll();
            } catch (RuntimeException e) {
                log.warn("RSClans feed poll failed: {}", e.getClass().getSimpleName());
            }
        }, 20, 30, TimeUnit.SECONDS);
    }

    /** A player granted JonnyBot identity: add them to each of the bot's servers they are a (cached) member of. */
    private void addToGuilds(Map.Entry<String, Long> aliasAndDiscordId) {
        JDA current = jda;
        if (current == null) return;
        for (Guild g : current.getGuilds()) {
            if (g.getMemberById(aliasAndDiscordId.getValue()) != null) hub.putGuildMember(g.getIdLong(), aliasAndDiscordId.getKey());
        }
    }

    /** The feed-to-sinks logic, apart from the service wiring so tests can drive it with a fake hub. */
    static final class Relay {
        private static final Duration IDS_KEPT = Duration.ofMinutes(10);

        private final RsClansHubClient hub;
        private final Supplier<Optional<String>> loadCursor;
        private final Consumer<String> saveCursor;
        private final List<PluginEventSink> sinks;
        private final Consumer<Map.Entry<String, Long>> granted;
        private final ConcurrentHashMap<String, CachedId> ids = new ConcurrentHashMap<>();

        private record CachedId(Optional<Long> id, Instant until) {}

        Relay(RsClansHubClient hub, Supplier<Optional<String>> loadCursor, Consumer<String> saveCursor, List<PluginEventSink> sinks,
              Consumer<Map.Entry<String, Long>> granted) {
            this.hub = hub;
            this.loadCursor = loadCursor;
            this.saveCursor = saveCursor;
            this.sinks = List.copyOf(sinks);
            this.granted = granted;
        }

        /** Reads the feed until it has no more. Returns how many events were passed on. */
        int poll() {
            Optional<String> stored = loadCursor.get();
            boolean firstRun = stored.isEmpty();
            String cursor = stored.orElse(null);
            int passed = 0;
            for (int pages = 0; pages < 50; pages++) {
                RsClansHubClient.Answer a = hub.feed(cursor, PAGE);
                if (a == null || !a.ok() || a.json() == null) return passed;
                DataArray data = a.json().getArray("data");
                if (!firstRun) passed += handle(data);
                cursor = a.json().getString("nextCursor", cursor);
                if (cursor != null) saveCursor.accept(cursor);
                if (!a.json().getBoolean("more", false)) break;
            }
            if (firstRun) log.info("RSClans feed: started from its current end (nothing replayed)");
            return passed;
        }

        private int handle(DataArray entries) {
            Map<String, List<PluginPayload.Event>> byPlayer = new LinkedHashMap<>();
            Map<String, Long> owner = new LinkedHashMap<>();
            Map<String, String> rsnOf = new LinkedHashMap<>();
            for (int i = 0; i < entries.length(); i++) {
                DataObject e = entries.getObject(i);
                String kind = e.getString("kind", "");
                String alias = e.getString("alias", null);
                if (alias == null) continue;
                if (kind.equals("player.grant_changed") && !e.getObject("data").getBoolean("revoked", false) && e.getObject("data").getBoolean("identity", false)) {
                    discordId(alias).ifPresent(id -> granted.accept(Map.entry(alias, id)));
                    continue;
                }
                if (!kind.equals("plugin.event")) continue;
                Optional<Long> id = discordId(alias);
                PluginPayload.Event event = toEvent(e.getObject("data"));
                if (id.isEmpty() || event == null) continue;
                String rsn = e.getObject("data").getString("rsn", null);
                String key = id.get() + "|" + rsn;
                byPlayer.computeIfAbsent(key, k -> new ArrayList<>()).add(event);
                owner.put(key, id.get());
                rsnOf.put(key, rsn);
            }
            int passed = 0;
            for (var entry : byPlayer.entrySet()) {
                for (PluginEventSink sink : sinks) {
                    try {
                        sink.accepted(owner.get(entry.getKey()), rsnOf.get(entry.getKey()), entry.getValue());
                    } catch (RuntimeException ex) {
                        log.warn("A consumer of hub plugin events failed: {}", ex.getClass().getSimpleName());
                    }
                }
                passed += entry.getValue().size();
            }
            return passed;
        }

        private Optional<Long> discordId(String alias) {
            Instant now = Instant.now();
            CachedId c = ids.get(alias);
            if (c != null && c.until().isAfter(now)) return c.id();
            Optional<Long> id = hub.discordIdOf(alias);
            ids.put(alias, new CachedId(id, now.plus(IDS_KEPT)));
            return id;
        }

        /** A hub event (rsclans-hub API.md section 4) as the bot's own ingest would have stored it; the key is the hub's event id. */
        static PluginPayload.Event toEvent(DataObject e) {
            PluginEventType type = PluginEventType.parse(e.getString("type", null));
            Instant at;
            try {
                at = Instant.parse(e.getString("occurredAt", ""));
            } catch (RuntimeException ex) {
                return null;
            }
            if (type == null || !e.hasKey("data") || e.isNull("data")) return null;
            String sourceKind = null;
            String sourceName = null;
            Long sourceId = null;
            if (e.hasKey("source") && !e.isNull("source")) {
                DataObject s = e.getObject("source");
                sourceKind = s.getString("kind", null);
                sourceName = s.getString("name", null);
                if (s.hasKey("id") && !s.isNull("id")) sourceId = s.getLong("id");
            }
            return new PluginPayload.Event("hub:" + e.getString("id", ""), type, at, sourceKind, sourceName, sourceId, e.getObject("data").toString());
        }
    }
}
