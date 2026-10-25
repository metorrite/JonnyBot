package com.younglings.bot.internal;

import com.sun.net.httpserver.HttpExchange;
import io.github.freya022.botcommands.api.core.service.annotations.InterfacedService;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

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

    /**
     * Whether the caller may use this module. The default is the internal API's {@code X-Internal-Secret}; a module called by someone other
     * than the website overrides it with a secret of its own, so that caller never holds the internal secret.
     */
    default boolean authorized(HttpExchange exchange, String internalSecret) {
        String provided = exchange.getRequestHeaders().getFirst("X-Internal-Secret");
        return provided != null && internalSecret != null
                && MessageDigest.isEqual(provided.getBytes(StandardCharsets.UTF_8), internalSecret.getBytes(StandardCharsets.UTF_8));
    }
}
