package com.runeclans.bot.internal;

import com.sun.net.httpserver.HttpExchange;
import com.runeclans.bot.clanprofile.ClanProfile;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.function.Function;

/** One request to the public directory, as a {@link DirectoryRouteModule} sees it: the path after {@code /internal/directory/}, split on "/". */
public final class DirectoryRequest {
    private final HttpExchange exchange;
    private final JDA jda;
    private final String method;
    private final String[] parts;
    private final Function<String, Optional<ClanProfile>> viewable;
    private final Function<ClanProfile, DataObject> clanJson;

    DirectoryRequest(HttpExchange exchange, JDA jda, String method, String[] parts,
                     Function<String, Optional<ClanProfile>> viewable, Function<ClanProfile, DataObject> clanJson) {
        this.exchange = exchange;
        this.jda = jda;
        this.method = method;
        this.parts = parts;
        this.viewable = viewable;
        this.clanJson = clanJson;
    }

    public HttpExchange exchange() {
        return exchange;
    }

    public JDA jda() {
        return jda;
    }

    public String method() {
        return method;
    }

    /** The path segments; {@code parts()[0]} is the module's own first segment. */
    public String[] parts() {
        return parts;
    }

    public String head() {
        return parts[0];
    }

    public void send(int status, DataObject body) throws IOException {
        InternalApiServer.sendJson(exchange, status, body);
    }

    public void sendError(int status, String error) throws IOException {
        send(status, DataObject.empty().put("error", error));
    }

    /** True for a GET; otherwise sends a 405 and returns false. */
    public boolean requireGet() throws IOException {
        return require("GET");
    }

    /** True for a POST; otherwise sends a 405 and returns false. */
    public boolean requirePost() throws IOException {
        return require("POST");
    }

    private boolean require(String expected) throws IOException {
        if (method.equals(expected)) return true;
        sendError(405, "Method not allowed");
        return false;
    }

    /** The JSON body, or null after sending a 400 with {@code unreadable} as the error. */
    public DataObject body(String unreadable) throws IOException {
        try (InputStream in = exchange.getRequestBody()) {
            return DataObject.fromJson(in);
        } catch (RuntimeException e) {
            sendError(400, unreadable);
            return null;
        }
    }

    /** A query parameter, decoded, or null. */
    public String query(String name) {
        String query = exchange.getRequestURI().getRawQuery();
        if (query == null) return null;
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(name)) return URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
        }
        return null;
    }

    /** A profile anyone may open by its address: listed, or unlisted. */
    public Optional<ClanProfile> viewable(String slug) {
        return viewable.apply(slug);
    }

    /** A clan's full public page, with every module's section, as {@code clans/{slug}} answers it. */
    public DataObject clanJson(ClanProfile profile) {
        return clanJson.apply(profile);
    }
}
