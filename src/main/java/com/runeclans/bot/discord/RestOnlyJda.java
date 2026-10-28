package com.runeclans.bot.discord;

import io.github.freya022.botcommands.api.core.JDAService;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.audio.AudioModuleConfig;
import net.dv8tion.jda.api.hooks.IEventManager;
import net.dv8tion.jda.internal.JDAImpl;
import net.dv8tion.jda.internal.utils.config.AuthorizationConfig;
import net.dv8tion.jda.internal.utils.config.MetaConfig;
import net.dv8tion.jda.internal.utils.config.SessionConfig;
import net.dv8tion.jda.internal.utils.config.ThreadingConfig;

/**
 * A Discord connection for the api role (T-670, {@code BOT_ROLE=api}): REST requests only, never a gateway login. It can send messages,
 * edit roles and members and read anything Discord answers over REST, but it receives no events and holds no cache of servers, channels or
 * members, so {@code Shards.guild(...)} answers null for every server.
 *
 * <p>JDA has no public REST-only builder, so this builds the same {@link JDAImpl} {@code JDABuilder} would and does what its login does
 * before the gateway: start the thread pools and the request queue (with BotCommands' rate limiter, as every JDA of this bot), then check the
 * token with {@code GET /users/@me}, which also fills {@link JDA#getSelfUser()}. Last it reports itself connected through the event manager,
 * which is what makes BotCommands hand the JDA to every service ({@code InjectedJDAEvent}) exactly as after a gateway login.
 *
 * <p>Built on JDA internals ({@code JDAImpl} and its config classes), which can change in any JDA release: {@code RestOnlyJdaTest} is the
 * check, so a JDA upgrade must keep it green.
 */
public final class RestOnlyJda {
    private RestOnlyJda() {}

    /** Builds the connection and checks the token (a bad token throws {@code InvalidTokenException}, as a failed login does). */
    public static JDA create(String token, IEventManager eventManager) {
        JDAImpl jda = build(token, eventManager);
        jda.verifyToken();
        start(jda);
        return jda;
    }

    /** The connection with its request queue running, before the token check. Package-private for the tests (no network). */
    static JDAImpl build(String token, IEventManager eventManager) {
        ThreadingConfig threading = ThreadingConfig.getDefault();
        JDAImpl jda = new JDAImpl(new AuthorizationConfig(token), SessionConfig.getDefault(), threading, MetaConfig.getDefault(),
                JDAService.Companion.getDefaultRestConfig(), new AudioModuleConfig());
        threading.init(() -> "RestOnly");
        jda.setEventManager(eventManager);
        jda.initRequester();
        return jda;
    }

    /** Reports the connection ready: the same status changes a gateway login goes through, so BotCommands injects it. */
    static void start(JDAImpl jda) {
        jda.setStatus(JDA.Status.CONNECTING_TO_WEBSOCKET);
        jda.setStatus(JDA.Status.CONNECTED);
    }
}
