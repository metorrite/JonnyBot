package com.runeclans.bot.config;

import org.slf4j.LoggerFactory;

import java.util.Locale;

/**
 * Which part of the bot this process runs ({@code BOT_ROLE}, T-670, docs/SCALING-STATE.md section 1 shape B2). The same jar runs in every
 * role:
 * <ul>
 *   <li>{@link #ALL} (the default): everything in one process, exactly the bot as it always was.</li>
 *   <li>{@link #GATEWAY}: logs in to Discord, handles events and interactions, and drains the Discord job outbox. No internal HTTP API.</li>
 *   <li>{@link #API}: the internal HTTP API for the website, with a REST-only Discord connection (no gateway login, so no events and no
 *   cache of servers or members). Jobs the website queues are run by a gateway process.</li>
 * </ul>
 * A split (two processes, one per role) also needs every scheduler lane to name its owner role, so a lane that needs the gateway's cache
 * never runs in the API process (T-702). Until then {@link #splitReady()} is false and a process started as gateway or api stops at once
 * with a plain reason instead of running half a bot.
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

    /** Whether this role serves the internal HTTP API the website calls. */
    public boolean internalApi() {
        return this != GATEWAY;
    }

    /** Whether this role runs the Discord job outbox (the jobs need the gateway's servers). */
    public boolean outbox() {
        return gateway();
    }

    /**
     * Whether the bot can be split into gateway and api processes yet: false until every scheduler lane declares its owner role (T-702),
     * because an api process would otherwise run lanes that read a missing server as "the bot left".
     */
    public static boolean splitReady() {
        return false;
    }

    /** {@code BOT_ROLE}'s value: unset or blank is {@link #ALL}; anything that isn't a role is {@link #ALL} with a warning. */
    public static BotRole parse(String raw) {
        if (raw == null || raw.isBlank()) return ALL;
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            LoggerFactory.getLogger(BotRole.class).warn("BOT_ROLE must be all, gateway or api; using all");
            return ALL;
        }
    }
}
