package com.younglings.bot.internal;

import com.younglings.bot.discord.Shards;
import com.sun.net.httpserver.HttpExchange;
import com.younglings.bot.config.BotConfig;
import io.github.freya022.botcommands.api.core.annotations.BEventListener;
import io.github.freya022.botcommands.api.core.events.InjectedJDAEvent;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.io.IOException;

/**
 * The home server's site data by server id (T-519), under {@code /internal/guild-site/}, so the home server's own website can read through the
 * same per-site answers clan sites use instead of the home-only {@code /internal/site/} routes:
 * <ul>
 *   <li>{@code GET /internal/guild-site/home} → {@code {"guildId": "..."}}: which server is the home server, so the website needs no setting</li>
 *   <li>{@code GET /internal/guild-site/{guildId}/{route}} → {@link SiteApi#home}: every route in {@link SiteApi#TENANT_ROUTES} (the clan-site
 *   answer, with its privacy rules) plus {@link SiteApi#HOME_ROUTES} (news, options, me, online, events), with the same query parameters</li>
 * </ul>
 * Only the home server answers here; any other id is 404. Clan sites ask by server id through the directory's guild alias, which needs a
 * published site; the home server has none, which is why it has this route.
 */
@BService
public class GuildSiteRoutes implements InternalRouteModule {
    static final String PREFIX = "/internal/guild-site/";

    private final SiteApi siteApi;
    private final BotConfig config;
    private volatile JDA jda;

    public GuildSiteRoutes(SiteApi siteApi, BotConfig config) {
        this.siteApi = siteApi;
        this.config = config;
    }

    @BEventListener
    public void onJdaReady(InjectedJDAEvent event) {
        this.jda = event.getJda();
    }

    @Override
    public String prefix() {
        return PREFIX;
    }

    record Answer(int status, DataObject body) {}

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        Answer answer = route(exchange.getRequestMethod(), exchange.getRequestURI().getPath(), exchange);
        InternalApiServer.sendJson(exchange, answer.status(), answer.body());
    }

    Answer route(String method, String path, HttpExchange exchange) {
        if (!"GET".equalsIgnoreCase(method)) return new Answer(405, error("Method not allowed"));
        String rest = path.length() > PREFIX.length() ? path.substring(PREFIX.length()) : "";
        if (rest.endsWith("/")) rest = rest.substring(0, rest.length() - 1);
        Long home = config.getGuildId();
        if (home == null) return new Answer(404, error("Not found"));
        if (rest.equals("home")) return new Answer(200, DataObject.empty().put("guildId", Long.toString(home)));

        int slash = rest.indexOf('/');
        if (slash <= 0 || !rest.substring(0, slash).equals(Long.toString(home))) return new Answer(404, error("Not found"));
        String route = rest.substring(slash + 1);
        Guild guild = jda == null ? null : Shards.guild(jda, home);
        if (guild == null) return new Answer(503, error("Guild not available yet"));
        DataObject data = siteApi.home(guild, route, exchange);
        return data == null ? new Answer(404, error("Not found")) : new Answer(200, data);
    }

    /** For tests: the JDA the routes look the home server up in. */
    void jda(JDA jda) {
        this.jda = jda;
    }

    private static DataObject error(String message) {
        return DataObject.empty().put("error", message);
    }
}
