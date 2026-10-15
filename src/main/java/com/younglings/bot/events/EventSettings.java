package com.younglings.bot.events;

import com.younglings.bot.events.PrizeRules.BuyIn;
import com.younglings.bot.events.PrizeRules.PrizeKind;
import com.younglings.bot.events.TeamRules.Policy;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The settings every event has, whatever its template, read from the event's stored settings. Keys (all optional):
 * {@code buyIn: {enabled, kind, amount, showPot}} (the buy-in is in-game gp unless a kind is given),
 * {@code teamPolicy: "everyone" | "home" | "clanVsOutsiders" | "separate"} (who may join and who may share a team),
 * {@code queue: "FIRST_COME" | "CLAN_FIRST"} (who gets a place when the event is full), and
 * {@code signupClosesAt}: an ISO date and time after which players can no longer join themselves (staff still can),
 * {@code boardChannelId}: where a bingo's live board image is posted and kept up to date, and {@code reviewChannelId}: where players' screenshots
 * go for staff to look at, and {@code organisers}: Discord user ids (up to {@link #MAX_ORGANISERS}) who may run this one event from Discord (start and
 * finish it, decide proof) without being staff.
 * Pure, so it can be tested.
 */
public record EventSettings(BuyIn buyIn, Policy teamPolicy, QueueRules.Policy queue, Instant signupClosesAt, Long signupChannelId, Long signupAdminChannelId,
                            Long boardChannelId, Long reviewChannelId, Set<Long> organisers) {
    public static final String DEFAULT_POLICY = "everyone";
    public static final int MAX_ORGANISERS = 10;

    public EventSettings {
        organisers = organisers == null ? Set.of() : java.util.Collections.unmodifiableSet(new LinkedHashSet<>(organisers));
    }

    /** Whether {@code userId} was named an organiser of this event. Staff are not listed here; they may run every event. */
    public boolean isOrganiser(long userId) {
        return organisers.contains(userId);
    }

    public static EventSettings defaults() {
        return new EventSettings(new BuyIn(false, PrizeKind.IN_GAME, 0, false), TeamRules.everyoneTogether(), QueueRules.Policy.FIRST_COME, null, null, null, null, null, Set.of());
    }

    /** Reads the settings, filling anything missing with the defaults. A value that can't be read is left at its default; {@link #problems} reports it. */
    public static EventSettings parse(DataObject config) {
        EventSettings d = defaults();
        if (config == null) return d;
        BuyIn buyIn = d.buyIn();
        if (config.hasKey("buyIn") && !config.isNull("buyIn")) {
            DataObject b = config.getObject("buyIn");
            PrizeKind kind = kind(b.getString("kind", "IN_GAME"));
            buyIn = new BuyIn(b.getBoolean("enabled", false), kind == null ? PrizeKind.IN_GAME : kind, number(b, "amount"), b.getBoolean("showPot", false));
        }
        Policy policy = policy(config.getString("teamPolicy", DEFAULT_POLICY));
        QueueRules.Policy queue = queue(config.getString("queue", "FIRST_COME"));
        Instant closes = null;
        try {
            if (config.hasKey("signupClosesAt") && !config.isNull("signupClosesAt") && !config.getString("signupClosesAt").isBlank()) closes = Instant.parse(config.getString("signupClosesAt").strip());
        } catch (DateTimeParseException | ClassCastException ignored) {
            // reported by problems()
        }
        return new EventSettings(buyIn, policy == null ? d.teamPolicy() : policy, queue == null ? d.queue() : queue, closes, id(config, "signupChannelId"), id(config, "signupAdminChannelId"),
                id(config, "boardChannelId"), id(config, "reviewChannelId"), organisersOf(config));
    }

    /** The organiser ids in the settings; entries that aren't ids are skipped here and reported by {@link #problems}. */
    public static Set<Long> organisersOf(DataObject config) {
        Set<Long> ids = new LinkedHashSet<>();
        try {
            if (config == null || !config.hasKey("organisers") || config.isNull("organisers")) return ids;
            var array = config.getArray("organisers");
            for (Object value : array.toList()) {
                String raw = value == null ? "" : String.valueOf(value).strip();
                if (raw.matches("\\d{1,20}")) ids.add(Long.parseLong(raw));
            }
        } catch (RuntimeException ignored) {
            // reported by problems()
        }
        return ids;
    }

    /** What is wrong with the settings in {@code config}, in words, or an empty list. */
    public static List<String> problems(DataObject config) {
        List<String> problems = new ArrayList<>();
        if (config == null) return problems;
        if (config.hasKey("buyIn") && !config.isNull("buyIn")) {
            DataObject b = config.getObject("buyIn");
            if (kind(b.getString("kind", "IN_GAME")) == null) problems.add("The buy-in has to be in-game gp, clan points or other.");
            EventSettings parsed = parse(config);
            problems.addAll(PrizeRules.buyInProblems(parsed.buyIn()));
        }
        if (config.hasKey("teamPolicy") && policy(config.getString("teamPolicy", DEFAULT_POLICY)) == null) problems.add("Choose who may join: everyone, home server only, clan members apart from outsiders, or all separate.");
        if (config.hasKey("queue") && queue(config.getString("queue", "FIRST_COME")) == null) problems.add("Choose how a full event fills: first come first served, or clan members first.");
        try {
            if (config.hasKey("signupClosesAt") && !config.isNull("signupClosesAt") && !config.getString("signupClosesAt").isBlank()) Instant.parse(config.getString("signupClosesAt").strip());
        } catch (DateTimeParseException | ClassCastException e) {
            problems.add("The signup deadline has to be a date and time like 2026-10-20T19:00:00Z.");
        }
        for (String key : new String[]{"signupChannelId", "signupAdminChannelId", "boardChannelId", "reviewChannelId"}) {
            if (config.hasKey(key) && !config.isNull(key) && !config.getString(key, "").isBlank() && id(config, key) == null) problems.add("Choose a channel for the signup (the channel id looks wrong).");
        }
        if (config.hasKey("organisers") && !config.isNull("organisers")) {
            try {
                var array = config.getArray("organisers");
                if (array.length() > MAX_ORGANISERS) problems.add("An event can have up to " + MAX_ORGANISERS + " organisers.");
                if (organisersOf(config).size() != array.length()) problems.add("Each organiser has to be a Discord user (and listed once).");
            } catch (RuntimeException e) {
                problems.add("The organisers have to be a list of Discord users.");
            }
        }
        boolean one = config.hasKey("signupChannelId") && id(config, "signupChannelId") != null;
        boolean other = config.hasKey("signupAdminChannelId") && id(config, "signupAdminChannelId") != null;
        if (one != other) problems.add("A signup needs both a public channel and a staff channel, or neither.");
        return problems;
    }

    private static Long id(DataObject config, String key) {
        try {
            if (!config.hasKey(key) || config.isNull(key)) return null;
            String raw = config.getString(key).strip();
            return raw.matches("\\d{1,20}") ? Long.parseLong(raw) : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Whether players can still join themselves at {@code now}; staff may always add late entries. */
    public boolean selfSignupOpen(Instant now) {
        return signupClosesAt == null || now.isBefore(signupClosesAt);
    }

    private static long number(DataObject b, String key) {
        try {
            return b.hasKey(key) && !b.isNull(key) ? b.getLong(key) : 0;
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static PrizeKind kind(String raw) {
        try {
            return PrizeKind.valueOf(raw.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static Policy policy(String raw) {
        return switch (raw.strip().toLowerCase(Locale.ROOT)) {
            case "everyone" -> TeamRules.everyoneTogether();
            case "home" -> TeamRules.homeServerOnly();
            case "clanvsoutsiders" -> TeamRules.clanVersusOutsiders();
            case "separate" -> TeamRules.allSeparate();
            default -> null;
        };
    }

    private static QueueRules.Policy queue(String raw) {
        try {
            return QueueRules.Policy.valueOf(raw.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
