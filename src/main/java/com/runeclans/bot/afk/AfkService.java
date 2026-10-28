package com.runeclans.bot.afk;

import com.runeclans.bot.afk.AfkRepository.Afk;
import com.runeclans.bot.cachebus.CacheBus;
import com.runeclans.bot.cachebus.CacheInvalidator;
import io.github.freya022.botcommands.api.core.service.annotations.BService;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Who is away, per server. Every message in a server asks "is the author away? is anyone they mention?", so the list is kept in
 * memory (loaded once per server, and tiny) and the database is only touched when someone goes away or comes back.
 */
@BService
public class AfkService implements CacheInvalidator {
    /** The {@code bot_cache} area of this class's caches (docs/SCALING-STATE.md M10). */
    static final String CACHE_AREA = "afk";

    public static final int MAX_REASON = 200;

    private final AfkRepository repository;
    private final Map<Long, Map<Long, Afk>> byGuild = new ConcurrentHashMap<>();

    public AfkService(AfkRepository repository) {
        this.repository = repository;
    }

    private Map<Long, Afk> guild(long guildId) {
        return byGuild.computeIfAbsent(guildId, id -> {
            Map<Long, Afk> map = new ConcurrentHashMap<>();
            for (Afk afk : repository.forGuild(id)) map.put(afk.userId(), afk);
            return map;
        });
    }

    /** Everyone currently away in the server, longest away first. */
    public java.util.List<Afk> all(long guildId) {
        return guild(guildId).values().stream().sorted(java.util.Comparator.comparing(Afk::since)).toList();
    }

    public Optional<Afk> get(long guildId, long userId) {
        return Optional.ofNullable(guild(guildId).get(userId));
    }

    public Afk set(long guildId, long userId, String reason) {
        String clean = reason == null ? "" : reason.strip();
        Afk afk = new Afk(guildId, userId, clean.length() > MAX_REASON ? clean.substring(0, MAX_REASON) : clean, Instant.now());
        repository.set(afk);
        guild(guildId).put(userId, afk);
        CacheBus.changed(CACHE_AREA, guildId);
        return afk;
    }

    /** Whether the person was away (and so is now back). */
    public boolean clear(long guildId, long userId) {
        boolean was = guild(guildId).remove(userId) != null;
        if (was) {
            repository.clear(guildId, userId);
            CacheBus.changed(CACHE_AREA, guildId);
        }
        return was;
    }

    /** What to say about someone who is away. Mentions of them are written as their name so saying it never pings anyone. */
    public static String line(String name, Afk afk) {
        String why = afk.reason().isBlank() ? "" : ": " + afk.reason();
        return "**" + name + "** is away" + why + " (since <t:" + afk.since().getEpochSecond() + ":R>)";
    }

    // ---------- cross-process invalidation (CacheBus) ----------

    @Override
    public String cacheArea() {
        return CACHE_AREA;
    }

    @Override
    public void evictCached(long guildId) {
        byGuild.remove(guildId);
    }

    @Override
    public void evictAllCached() {
        byGuild.clear();
    }
}
