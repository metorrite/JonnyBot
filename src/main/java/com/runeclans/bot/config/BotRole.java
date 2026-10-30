package com.runeclans.bot.config;

import org.slf4j.LoggerFactory;

import java.util.Locale;

/**
 * Which part of the bot this process runs ({@code BOT_ROLE}, T-670, docs/SCALING-STATE.md section 1 shape B2). The same jar runs in every
 * role:
 * <ul>
 *   <li>{@link #ALL} (the default): everything in one process, exactly the bot as it always was.</li>
 *   <li>{@link #GATEWAY}: logs in to Discord, handles events and interactions, and drains the Discord job outbox. Its internal HTTP API
 *   answers the api process on the private network.</li>
 *   <li>{@link #API}: the internal HTTP API the website calls, with a REST-only Discord connection (no gateway login, so no events and no
 *   cache of servers or members). Routes that need servers or members are passed on to the gateway ({@code GATEWAY_INTERNAL_URL},
 *   {@code internal/GatewayForward}, T-746); jobs the website queues are run by a gateway process.</li>
 * </ul>
 * Every scheduler lane names the role that runs it ({@code util/LaneOwners}, T-702). A split needs both processes to hear each other's cache
 * changes ({@code BOT_CACHE_NOTIFY}) and the api process to know where the gateway is; {@link #checkedForStart} stops a process at once,
 * with a plain reason, when either is missing.
 * <p>
 * An api process has no server cache: code that isn't role-aware reads every server as missing, and some of it (poll closing, delayed
 * autoroles, departed-server cleanup, stream alerts) deletes rows for a missing server. {@code ShardRouting} and the lane owners keep that
 * work on the gateway, and the internal API passes such routes on; anything new that acts on a missing server must check the role too.
 */
public enum BotRole {
    ALL,
    GATEWAY,
    API;

    /** Set once at start-up from {@code BOT_ROLE}; {@link #ALL} until then (and in tests). */
    private static volatile BotRole current = ALL;

    /** This process's role. */
    public static BotRole current() {
        return current;
    }

    /** Records this process's role (once, at start-up). */
    public static void install(BotRole role) {
        current = role;
    }

    /** Whether this role logs in to the Discord gateway (events, interactions, the server and member cache). */
    public boolean gateway() {
        return this != API;
    }

    /** Whether this role's internal API passes routes that need servers or members on to the gateway process (T-746). */
    public boolean forwardsToGateway() {
        return this == API;
    }

    /** Whether this role runs the Discord job outbox (the jobs need the gateway's servers). */
    public boolean outbox() {
        return gateway();
    }

    /**
     * Whether the bot can be split into gateway and api processes: every scheduler lane names its owner role (T-702, {@code util/LaneOwners})
     * and the api role's internal API asks the gateway for servers and members (T-746). Kept as a kill switch: setting it back to false
     * makes a split process refuse to start, and makes a mistyped {@code BOT_ROLE} fall back to all again.
     */
    public static boolean splitReady() {
        return true;
    }

    /**
     * The role a process may start with: {@code role} itself, or a plain refusal when a split process lacks what it needs. Both split roles
     * need the cache signals on ({@code BOT_CACHE_NOTIFY}), or each would keep serving its own stale copy of what the other changed, and
     * the internal API set up ({@code INTERNAL_API_SECRET} and {@code GUILD_ID}), since the website reaches the api process through it and
     * the api process reaches the gateway the same way; the api role needs the gateway's address ({@code GATEWAY_INTERNAL_URL}, an http(s)
     * address). The reasons name settings, never their values.
     */
    public static BotRole checkedForStart(BotRole role, boolean gatewayAddressValid, boolean cacheSignalsOn, boolean internalApiSetUp) {
        if (role == ALL) return role;
        String name = "BOT_ROLE=" + role.name().toLowerCase(Locale.ROOT);
        if (!splitReady()) throw new IllegalStateException(name + " can't run yet. Leave BOT_ROLE unset (all) for now.");
        if (!cacheSignalsOn) {
            throw new IllegalStateException(name + " needs BOT_CACHE_NOTIFY=true in every bot process, so each hears what the others change.");
        }
        if (!internalApiSetUp) {
            throw new IllegalStateException(name + " needs INTERNAL_API_SECRET and GUILD_ID: the processes talk through the internal API.");
        }
        if (role == API && !gatewayAddressValid) {
            throw new IllegalStateException(name + " needs GATEWAY_INTERNAL_URL set to the gateway process's internal API, an http(s) address"
                    + " on the private network (for example http://bot-gateway.railway.internal:8081).");
        }
        return role;
    }

    /**
     * {@code BOT_ROLE}'s value: unset or blank is {@link #ALL}. Anything that isn't a role is {@link #ALL} with a warning while a split isn't
     * possible; once it is, a typo stops the process, since falling back to all would run everything twice next to the real processes.
     */
    public static BotRole parse(String raw) {
        return parse(raw, splitReady());
    }

    static BotRole parse(String raw, boolean splitReady) {
        if (raw == null || raw.isBlank()) return ALL;
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            if (splitReady) throw new IllegalStateException("BOT_ROLE='" + raw.trim() + "' isn't a role: use all, gateway or api");
            LoggerFactory.getLogger(BotRole.class).warn("BOT_ROLE='{}' isn't a role (all, gateway or api); using all", raw.trim());
            return ALL;
        }
    }
}
