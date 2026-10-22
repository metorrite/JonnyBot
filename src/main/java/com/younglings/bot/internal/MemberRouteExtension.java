package com.younglings.bot.internal;

import io.github.freya022.botcommands.api.core.service.annotations.InterfacedService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.utils.data.DataObject;

/**
 * A feature's own routes under {@code /internal/me/} (what a signed-in member does to their own record from the website). {@link MemberApi}
 * has already read the member's id (which the website takes only from the verified login session) and applied the per-member rate limits, so an
 * extension only decides whether the route is its own and answers it, touching only that member's rows. Implement this on a {@code @BService}
 * and the route is picked up with no edit to {@code MemberApi}, so features added side by side never touch its constructor or its switch.
 */
@InterfacedService(acceptMultiple = true)
public interface MemberRouteExtension {
    /**
     * The answer, or {@code null} if {@code route} (the path after {@code /internal/me/}, no trailing slash) with {@code method} isn't this
     * extension's. {@code body} is the JSON body for POST and PUT, empty otherwise. Throw {@link #refuse} to answer with an error.
     */
    DataObject route(Guild guild, long userId, String method, String route, DataObject body);

    /** A refusal the website shows as it is. */
    final class Refused extends RuntimeException {
        public final int status;

        Refused(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    static RuntimeException refuse(int status, String message) {
        return new Refused(status, message);
    }
}
