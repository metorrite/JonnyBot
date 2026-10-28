package com.runeclans.bot.twitch;

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
 * Talks to Twitch's Helix API with an app token (no one logs in). The app's id and secret come from the environment variables
 * {@value #ID_VARIABLE} and {@value #SECRET_VARIABLE}; without both, {@link #configured()} is false and nothing is ever sent to Twitch. The secret is only
 * ever sent to Twitch's token address and is never logged. A token that Twitch refuses is dropped and fetched again once.
 */
@BService
public class TwitchClient {
    public static final String ID_VARIABLE = "TWITCH_CLIENT_ID";
    public static final String SECRET_VARIABLE = "TWITCH_CLIENT_SECRET";

    private static final Logger log = LoggerFactory.getLogger(TwitchClient.class);
    private static final String TOKEN_URL = "https://id.twitch.tv/oauth2/token";
    private static final String API = "https://api.twitch.tv/helix/";
    private static final int BATCH = 100;

    /** A stream that is live right now. */
    public record Stream(String id, String login, String name, String title, String game, int viewers, String thumbnailTemplate) {}

    /** A Twitch channel that exists. */
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

    /** Whether the bot has a Twitch app to ask with. */
    public boolean configured() {
        return clientId != null && clientSecret != null;
    }

    /** The streams live right now among {@code logins}, by login. Throws {@link IOException} if Twitch cannot be asked. */
    public Map<String, Stream> live(List<String> logins) throws IOException, InterruptedException {
        Map<String, Stream> live = new HashMap<>();
        for (int from = 0; from < logins.size(); from += BATCH) {
            StringBuilder query = new StringBuilder("streams?first=100");
            for (String login : logins.subList(from, Math.min(logins.size(), from + BATCH))) query.append("&user_login=").append(URLEncoder.encode(login, StandardCharsets.UTF_8));
            for (Stream stream : TwitchParser.streams(get(query.toString()))) live.put(stream.login(), stream);
        }
        return live;
    }

    /** The channel with this login, or empty if there is none. */
    public Optional<User> user(String login) throws IOException, InterruptedException {
        return TwitchParser.user(get("users?login=" + URLEncoder.encode(login, StandardCharsets.UTF_8)));
    }

    private String get(String path) throws IOException, InterruptedException {
        if (!configured()) throw new IOException("Twitch is not set up on this bot");
        for (int attempt = 0; attempt < 2; attempt++) {
            HttpRequest request = HttpRequest.newBuilder(URI.create(API + path)).timeout(Duration.ofSeconds(15)).header("Client-Id", clientId)
                    .header("Authorization", "Bearer " + token()).header("User-Agent", "JonnyBot/1.0").GET().build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 401 && attempt == 0) {
                token = null; // the token expired or was revoked: fetch a fresh one and try once more
                continue;
            }
            if (response.statusCode() != 200) throw new IOException("Twitch answered HTTP " + response.statusCode());
            return response.body();
        }
        throw new IOException("Twitch refused the token");
    }

    private synchronized String token() throws IOException, InterruptedException {
        if (token != null && System.currentTimeMillis() < tokenExpiresAt - 60_000) return token;
        String form = "client_id=" + URLEncoder.encode(clientId, StandardCharsets.UTF_8) + "&client_secret=" + URLEncoder.encode(clientSecret, StandardCharsets.UTF_8) + "&grant_type=client_credentials";
        HttpRequest request = HttpRequest.newBuilder(URI.create(TOKEN_URL)).timeout(Duration.ofSeconds(15)).header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)).build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            log.warn("Twitch refused the app's credentials (HTTP {}); check {} and {}", response.statusCode(), ID_VARIABLE, SECRET_VARIABLE);
            throw new IOException("Twitch refused the app's credentials");
        }
        DataObject json = DataObject.fromJson(response.body());
        token = json.getString("access_token");
        tokenExpiresAt = System.currentTimeMillis() + json.getLong("expires_in", 3600) * 1000;
        return token;
    }

    /** Reads Twitch's answers; separate and static so it can be tested without the network. */
    static final class TwitchParser {
        private TwitchParser() {}

        static List<Stream> streams(String json) {
            DataArray data = DataObject.fromJson(json).getArray("data");
            java.util.ArrayList<Stream> found = new java.util.ArrayList<>();
            for (int i = 0; i < data.length(); i++) {
                DataObject s = data.getObject(i);
                if (!"live".equals(s.getString("type", ""))) continue;
                found.add(new Stream(s.getString("id"), s.getString("user_login", "").toLowerCase(), s.getString("user_name", s.getString("user_login", "")), s.getString("title", ""),
                        s.getString("game_name", ""), s.getInt("viewer_count", 0), s.getString("thumbnail_url", "")));
            }
            return found;
        }

        static Optional<User> user(String json) {
            DataArray data = DataObject.fromJson(json).getArray("data");
            if (data.length() == 0) return Optional.empty();
            DataObject u = data.getObject(0);
            return Optional.of(new User(u.getString("login", "").toLowerCase(), u.getString("display_name", u.getString("login", "")), u.getString("profile_image_url", "")));
        }
    }
}
