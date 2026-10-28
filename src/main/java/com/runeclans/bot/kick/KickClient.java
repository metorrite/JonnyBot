package com.runeclans.bot.kick;

import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Talks to Kick's public API with an app token (no one logs in). The app's id and secret come from the environment variables
 * {@value #ID_VARIABLE} and {@value #SECRET_VARIABLE}; without both, {@link #configured()} is false and nothing is ever sent to Kick. The secret is only
 * ever sent to Kick's token address and is never logged. A token that Kick refuses is dropped and fetched again once.
 */
@BService
public class KickClient {
    public static final String ID_VARIABLE = "KICK_CLIENT_ID";
    public static final String SECRET_VARIABLE = "KICK_CLIENT_SECRET";

    private static final Logger log = LoggerFactory.getLogger(KickClient.class);
    private static final String TOKEN_URL = "https://id.kick.com/oauth/token";
    private static final String API = "https://api.kick.com/public/v1/";
    /** Kick answers up to 50 channels per request. */
    private static final int BATCH = 50;

    /** A stream that is live right now. */
    public record Stream(String id, String login, String name, String title, String game, int viewers, String thumbnailTemplate) {}

    /** A Kick channel that exists. */
    public record User(String login, String name, String avatarUrl) {}

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final String clientId = read(ID_VARIABLE);
    private final String clientSecret = read(SECRET_VARIABLE);
    private volatile String token;
    private volatile long tokenExpiresAt;

    private static String read(String variable) {
        String value = System.getenv(variable);
        return value == null || value.isBlank() ? null : value.strip();
    }

    /** Whether the bot has a Kick app to ask with. */
    public boolean configured() {
        return clientId != null && clientSecret != null;
    }

    /** The streams live right now among {@code logins}, by login. Throws {@link IOException} if Kick cannot be asked. */
    public Map<String, Stream> live(List<String> logins) throws IOException, InterruptedException {
        Map<String, Stream> live = new HashMap<>();
        for (int from = 0; from < logins.size(); from += BATCH) {
            StringBuilder query = new StringBuilder("channels?");
            for (String login : logins.subList(from, Math.min(logins.size(), from + BATCH))) query.append(query.charAt(query.length() - 1) == '?' ? "" : "&").append("slug=").append(URLEncoder.encode(login, StandardCharsets.UTF_8));
            for (Stream stream : KickParser.streams(get(query.toString()))) live.put(stream.login(), stream);
        }
        return live;
    }

    /** The channel with this login, or empty if there is none. */
    public Optional<User> user(String login) throws IOException, InterruptedException {
        return KickParser.user(get("channels?slug=" + URLEncoder.encode(login, StandardCharsets.UTF_8)));
    }

    private String get(String path) throws IOException, InterruptedException {
        if (!configured()) throw new IOException("Kick is not set up on this bot");
        for (int attempt = 0; attempt < 2; attempt++) {
            HttpRequest request = HttpRequest.newBuilder(URI.create(API + path)).timeout(Duration.ofSeconds(15)).header("Accept", "application/json")
                    .header("Authorization", "Bearer " + token()).header("User-Agent", "JonnyBot/1.0").GET().build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 401 && attempt == 0) {
                token = null; // the token expired or was revoked: fetch a fresh one and try once more
                continue;
            }
            if (response.statusCode() != 200) throw new IOException("Kick answered HTTP " + response.statusCode());
            return response.body();
        }
        throw new IOException("Kick refused the token");
    }

    private synchronized String token() throws IOException, InterruptedException {
        if (token != null && System.currentTimeMillis() < tokenExpiresAt - 60_000) return token;
        String form = "client_id=" + URLEncoder.encode(clientId, StandardCharsets.UTF_8) + "&client_secret=" + URLEncoder.encode(clientSecret, StandardCharsets.UTF_8) + "&grant_type=client_credentials";
        HttpRequest request = HttpRequest.newBuilder(URI.create(TOKEN_URL)).timeout(Duration.ofSeconds(15)).header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)).build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            log.warn("Kick refused the app's credentials (HTTP {}); check {} and {}", response.statusCode(), ID_VARIABLE, SECRET_VARIABLE);
            throw new IOException("Kick refused the app's credentials");
        }
        DataObject json = DataObject.fromJson(response.body());
        token = json.getString("access_token");
        tokenExpiresAt = System.currentTimeMillis() + json.getLong("expires_in", 3600) * 1000;
        return token;
    }

    /** Reads Kick's answers; separate and static so it can be tested without the network. */
    static final class KickParser {
        private KickParser() {}

        /** The channels in the answer that are live right now. A stream is identified by when it started, so a new stream is a new id. */
        static List<Stream> streams(String json) {
            DataArray data = DataObject.fromJson(json).getArray("data");
            java.util.ArrayList<Stream> found = new java.util.ArrayList<>();
            for (int i = 0; i < data.length(); i++) {
                DataObject channel = data.getObject(i);
                if (channel.isNull("stream")) continue;
                DataObject stream = channel.getObject("stream");
                if (!stream.getBoolean("is_live", false)) continue;
                String slug = channel.getString("slug", "").toLowerCase();
                String game = channel.isNull("category") ? "" : channel.getObject("category").getString("name", "");
                String id = stream.getString("start_time", "");
                if (slug.isEmpty() || id.isEmpty()) continue;
                found.add(new Stream(id, slug, channel.getString("slug", slug), channel.getString("stream_title", ""), game, stream.getInt("viewer_count", 0), stream.getString("thumbnail", "")));
            }
            return found;
        }

        static Optional<User> user(String json) {
            DataArray data = DataObject.fromJson(json).getArray("data");
            if (data.length() == 0) return Optional.empty();
            DataObject c = data.getObject(0);
            return Optional.of(new User(c.getString("slug", "").toLowerCase(), c.getString("slug", ""), ""));
        }
    }
}
