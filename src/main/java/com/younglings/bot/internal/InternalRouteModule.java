package com.younglings.bot.internal;

import com.sun.net.httpserver.HttpExchange;
import io.github.freya022.botcommands.api.core.service.annotations.InterfacedService;

import java.io.IOException;

/**
 * A self-contained module's own routes on the internal API, under a prefix of its own (e.g. {@code /internal/rsnlink/}).
 * {@link InternalApiServer} checks {@code X-Internal-Secret} and then hands the whole exchange over, so a module needs no guild and no edit
 * of the server to add routes. Used by modules that are meant to become separate services later, so they keep their own HTTP handling.
 */
@InterfacedService(acceptMultiple = true)
public interface InternalRouteModule {
    /** The path prefix, starting and ending with "/". */
    String prefix();

    /** Answers the request; the secret has been checked. */
    void handle(HttpExchange exchange) throws IOException;
}
