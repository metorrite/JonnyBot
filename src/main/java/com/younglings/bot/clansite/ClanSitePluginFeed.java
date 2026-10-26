package com.younglings.bot.clansite;

import com.younglings.bot.member.MemberProfileRepository;
import com.younglings.bot.runeclans.RuneClansHubClient;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import net.dv8tion.jda.api.utils.data.DataType;

import java.text.NumberFormat;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What the website builder's activity widgets show (T-303): a clan's recent plugin events from the RuneClans hub, as plain lines
 * ("Skill Queen reached level 99 Necromancy"). The hub already keeps to each member's grant and consent; on top of that, for a public page:
 * <ul>
 *   <li>only the RuneScape name is shown, never an alias or a Discord id;</li>
 *   <li>online status ({@code PRESENCE}) is never shown;</li>
 *   <li>a member who hid their adventure log on the clan site is left out.</li>
 * </ul>
 * Each item says whether the member's link was {@code confirmed} (T-552), for the admin-only marker.
 * Three views: {@code activity} (levels, quests, achievements, collection log, clues), {@code drops} (drops, collection log, clues) and
 * {@code runesync} (everything but online status). One hub call per server, cached a minute.
 */
@BService
public class ClanSitePluginFeed {
    static final Duration CACHE = Duration.ofMinutes(1);
    static final int FETCH = 100;
    static final int MAX_ITEMS = 25;

    static final Map<String, Set<String>> VIEWS = Map.of(
            "activity", Set.of("LEVEL_UP", "QUEST_COMPLETE", "ACHIEVEMENT", "COLLECTION_LOG", "CLUE"),
            "drops", Set.of("DROP", "COLLECTION_LOG", "CLUE"),
            "runesync", Set.of("XP", "LEVEL_UP", "QUEST_COMPLETE", "ACHIEVEMENT", "COLLECTION_LOG", "CLUE", "DROP", "KILL"));

    private record Cached(Instant at, DataArray events) {}

    private final RuneClansHubClient hub;
    private final MemberProfileRepository profiles;
    private final Map<Long, Cached> cache = new ConcurrentHashMap<>();

    public ClanSitePluginFeed(RuneClansHubClient hub, MemberProfileRepository profiles) {
        this.hub = hub;
        this.profiles = profiles;
    }

    private boolean hidden(long guildId, String rsn) {
        return profiles.adventureLogHidden(guildId, rsn);
    }

    /**
     * {@code {available, items:[{id, who, text, at, kind}]}} for one view, newest first. {@code available} false means the bot isn't connected
     * to the hub (or the hub didn't answer), so the widget's "RuneClans hub" requirement isn't met. Null for a view that doesn't exist.
     */
    public DataObject feed(long guildId, String view, int limit) {
        Set<String> types = VIEWS.get(view);
        if (types == null) return null;
        DataArray events = events(guildId);
        DataArray items = DataArray.empty();
        if (events != null) {
            Map<String, Boolean> hiddenByRsn = new HashMap<>();
            int max = Math.max(1, Math.min(limit, MAX_ITEMS));
            for (int i = 0; i < events.length() && items.length() < max; i++) {
                if (!events.isType(i, DataType.OBJECT)) continue;
                DataObject e = events.getObject(i);
                String type = e.getString("type", "").toUpperCase(Locale.ROOT);
                if (!types.contains(type)) continue;
                String rsn = e.getString("rsn", "").strip();
                if (rsn.isEmpty() || hiddenByRsn.computeIfAbsent(rsn.toLowerCase(Locale.ROOT), k -> hidden(guildId, rsn))) continue;
                DataObject data = e.isType("data", DataType.OBJECT) ? e.getObject("data") : DataObject.empty();
                String text = text(type, data);
                if (text == null) continue;
                items.add(DataObject.empty().put("id", e.getString("id", Integer.toString(i))).put("who", rsn).put("text", text)
                        .put("at", e.getString("occurredAt", "")).put("kind", kind(type)).put("confirmed", confirmed(e)));
            }
        }
        return DataObject.empty().put("available", events != null).put("items", items);
    }

    private DataArray events(long guildId) {
        Cached c = cache.get(guildId);
        if (c != null && c.at().plus(CACHE).isAfter(Instant.now())) return c.events();
        if (!hub.enabled()) return null;
        RuneClansHubClient.Answer a = hub.guildEvents(guildId, FETCH);
        // a server the hub doesn't know yet (404) simply has nothing to show; anything else is "can't reach the hub"
        DataArray events = a == null ? null : a.status() == 404 ? DataArray.empty()
                : a.ok() && a.json() != null ? a.json().optArray("data").orElse(DataArray.empty()) : null;
        if (events != null) {
            if (cache.size() > 5_000) cache.clear();
            cache.put(guildId, new Cached(Instant.now(), events));
        }
        return events;
    }

    /**
     * Whether the member's RS Link was confirmed from public data when the hub took the event (T-552: the hub's {@code verification} stamp
     * above {@code implicit}). The website shows "not confirmed" to the site's admins only; an older hub that doesn't say counts as not.
     */
    static boolean confirmed(DataObject event) {
        String level = event.getString("verification", "implicit");
        return level.equals("public_data_corroborated") || level.equals("jagex_signed");
    }

    /** The widget's icon for a hub event type. */
    static String kind(String type) {
        return switch (type) {
            case "DROP" -> "drop";
            case "KILL" -> "boss";
            case "CLUE" -> "clue";
            case "QUEST_COMPLETE" -> "quest";
            case "LEVEL_UP", "XP" -> "level";
            default -> "milestone";
        };
    }

    /** The line shown after the member's name, or null when the event can't be put into words (it is then skipped). */
    static String text(String type, DataObject d) {
        NumberFormat n = NumberFormat.getIntegerInstance(Locale.UK);
        String skill = clean(d.getString("skill", ""));
        return switch (type) {
            case "LEVEL_UP" -> skill.isEmpty() ? null : "reached level " + d.getInt("level", 0) + " " + skill;
            case "XP" -> skill.isEmpty() || !d.hasKey("xp") ? null : "reached " + n.format(d.getLong("xp", 0)) + " " + skill + " XP";
            case "QUEST_COMPLETE" -> nonEmpty("completed the quest ", d.getString("quest", ""));
            case "ACHIEVEMENT" -> nonEmpty("unlocked the achievement ", d.getString("name", ""));
            case "COLLECTION_LOG" -> nonEmpty("added to their collection log: ", d.getString("itemName", ""));
            case "CLUE" -> {
                String tier = clean(d.getString("tier", "")).toLowerCase(Locale.ROOT);
                int count = d.getInt("count", 1);
                yield tier.isEmpty() ? null : "opened " + (count > 1 ? count + " " + tier + " clue caskets" : "a " + tier + " clue casket");
            }
            case "DROP" -> {
                String item = clean(d.getString("itemName", ""));
                int qty = d.getInt("quantity", 1);
                yield item.isEmpty() ? null : "got a drop: " + (qty > 1 ? n.format(qty) + " x " : "") + item;
            }
            case "KILL" -> {
                String boss = clean(d.getString("bossName", ""));
                int kc = d.getInt("killCount", 0);
                yield boss.isEmpty() ? null : "killed " + boss + (kc > 0 ? " (kill " + n.format(kc) + ")" : "");
            }
            default -> null;
        };
    }

    private static String nonEmpty(String prefix, String value) {
        String v = clean(value);
        return v.isEmpty() ? null : prefix + v;
    }

    /** Plugin text as one short plain line (the website renders it as text, never HTML). */
    static String clean(String raw) {
        String s = raw == null ? "" : raw.replaceAll("\\s+", " ").strip();
        return s.length() > 80 ? s.substring(0, 80) : s;
    }

    /** For tests: what is cached. */
    List<Long> cachedGuilds() {
        return List.copyOf(cache.keySet());
    }
}
