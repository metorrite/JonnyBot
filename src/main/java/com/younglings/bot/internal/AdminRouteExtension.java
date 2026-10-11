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
     * {@link TicketAdminApi.ApiError} to refuse. {@code body} reads the request's JSON body when asked, so a GET never parses one.
     */
    DataObject route(Guild guild, Member actor, String method, String[] parts, Supplier<DataObject> body);

    /** Throws a 405 unless {@code actual} is {@code expected}. */
    static void requireMethod(String actual, String expected) {
        if (!actual.equals(expected)) throw new TicketAdminApi.ApiError(405, "Method not allowed");
    }
}
