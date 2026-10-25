package com.younglings.bot.internal;

import io.github.freya022.botcommands.api.core.service.annotations.InterfacedService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.util.function.Supplier;

/**
 * A feature's own routes under {@code /internal/admin/}. {@link TicketAdminApi} has already checked the asker may use the dashboard in
 * this server, applied the write rate limit, and audit-logs every write, so an extension only decides whether the path is its own and
 * answers it. Implement this on the feature's admin API class (a {@code @BService}) and the route is picked up with no edit to
 * {@code TicketAdminApi}, so features added side by side never touch the same lines.
 */
@InterfacedService(acceptMultiple = true)
public interface AdminRouteExtension {
    /**
     * The answer, or {@code null} if {@code parts} (the path split on "/", after {@code /internal/admin/}) isn't this extension's. Throw
     * {@link TicketAdminApi.ApiError} to refuse. {@code body} reads the request's JSON body when asked, so a GET never parses one; for a GET
     * it answers the query string's parameters instead ({@link #queryOf}).
     */
    DataObject route(Guild guild, Member actor, String method, String[] parts, Supplier<DataObject> body);

    /**
     * Whether this extension lets {@code actor}, who has NO dashboard access in this server, use this exact path (e.g. a clan website editor
     * using the site editor's routes, T-368). Only for paths {@link #route} answers itself, which must then check the person's own rights on
     * every request; nothing else in the dashboard opens up. The rate limit and audit log still apply. Default: nobody.
     */
    default boolean admits(Guild guild, Member actor, String method, String[] parts) {
        return false;
    }

    /** A refusal with the status and message the website should see, for extensions outside this package (which can't reach {@code ApiError}). */
    static RuntimeException refuse(int status, String message) {
        return new TicketAdminApi.ApiError(status, message);
    }

    /**
     * The request's query string as an object of strings (a name given twice keeps its first value), which a GET's {@code body} supplier
     * answers instead of a JSON body, so a route can read {@code ?q=} without the exchange.
     */
    static DataObject queryOf(java.net.URI uri) {
        DataObject out = DataObject.empty();
        String raw = uri.getRawQuery();
        if (raw == null || raw.isEmpty()) return out;
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            String name = java.net.URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), java.nio.charset.StandardCharsets.UTF_8);
            String value = eq < 0 ? "" : java.net.URLDecoder.decode(pair.substring(eq + 1), java.nio.charset.StandardCharsets.UTF_8);
            if (!name.isEmpty() && !out.hasKey(name)) out.put(name, value);
        }
        return out;
    }

    /** Throws a 405 unless {@code actual} is {@code expected}. */
    static void requireMethod(String actual, String expected) {
        if (!actual.equals(expected)) throw new TicketAdminApi.ApiError(405, "Method not allowed");
    }
}
