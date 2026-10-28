package com.runeclans.bot.linkedroles;

import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

/**
 * The few Discord calls Linked Roles needs, over plain HTTP (JDA has no role-connection API). Tokens go only to Discord and are never logged:
 * a failure logs the route name and status. A 429 waits Discord's {@code retry_after} (at most a minute) and tries again, up to three times.
 */
public interface DiscordRoleApi {
    record Tokens(String accessToken, String refreshToken, long expiresInSeconds) {}

    /** The person took the app's access away (or the refresh token is no longer valid): forget their tokens. */
    final class Revoked extends Exception {
        public Revoked(String route) {
            super(route + " was refused: the authorization is gone");
        }
    }

    /** Discord could not be reached or answered something else; try again later. */
    final class Failure extends Exception {
        public Failure(String message) {
            super(message);
        }
    }

    Tokens refresh(String refreshToken) throws Revoked, Failure;

    void putConnection(String accessToken, DataObject body) throws Revoked, Failure;

    void putSchema(String botToken, DataArray records) throws Failure;

    /** Best effort: revokes a token (unlink). Never throws. */
    void revoke(String token);

    static DiscordRoleApi http(LinkedRolesSettings settings) {
        return new Http(settings);
    }

    final class Http implements DiscordRoleApi {
        private static final Logger log = LoggerFactory.getLogger(DiscordRoleApi.class);
        private static final int TRIES = 3;
        private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        private final LinkedRolesSettings settings;

        Http(LinkedRolesSettings settings) {
            this.settings = settings;
        }

        @Override
        public Tokens refresh(String refreshToken) throws Revoked, Failure {
            HttpResponse<String> r = send("token refresh", form("/oauth2/token", "grant_type=refresh_token&refresh_token=" + enc(refreshToken)));
            // Only invalid_grant means this person's grant is gone; invalid_client (a wrong secret on our side) must not wipe everyone's tokens.
            if (r.statusCode() == 400 && "invalid_grant".equals(oauthError(r.body()))) throw new Revoked("token refresh");
            if (r.statusCode() != 200) throw new Failure("token refresh answered " + r.statusCode() + " " + oauthError(r.body()));
            try {
                DataObject json = DataObject.fromJson(r.body());
                return new Tokens(json.getString("access_token"), json.getString("refresh_token"), json.getLong("expires_in", 604800));
            } catch (RuntimeException e) {
                throw new Failure("token refresh answer couldn't be read");
            }
        }

        @Override
        public void putConnection(String accessToken, DataObject body) throws Revoked, Failure {
            HttpRequest.Builder b = request("/users/@me/applications/" + settings.clientId() + "/role-connection")
                    .header("Authorization", "Bearer " + accessToken).header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(body.toString()));
            HttpResponse<String> r = send("role connection", b);
            if (r.statusCode() == 401 || r.statusCode() == 403) throw new Revoked("role connection");
            if (r.statusCode() / 100 != 2) throw new Failure("role connection answered " + r.statusCode());
        }

        @Override
        public void putSchema(String botToken, DataArray records) throws Failure {
            HttpRequest.Builder b = request("/applications/" + settings.clientId() + "/role-connections/metadata")
                    .header("Authorization", "Bot " + botToken).header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(records.toString()));
            HttpResponse<String> r = send("metadata schema", b);
            if (r.statusCode() / 100 != 2) throw new Failure("metadata schema answered " + r.statusCode());
        }

        @Override
        public void revoke(String token) {
            try {
                send("token revoke", form("/oauth2/token/revoke", "token=" + enc(token) + "&token_type_hint=refresh_token"));
            } catch (Failure e) {
                log.info("Revoking a linked-roles token failed ({}); it is forgotten here anyway", e.getMessage());
            }
        }

        private HttpRequest.Builder form(String path, String body) {
            String basic = Base64.getEncoder().encodeToString((settings.clientId() + ":" + settings.clientSecret()).getBytes(StandardCharsets.UTF_8));
            return request(path).header("Authorization", "Basic " + basic).header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body));
        }

        private HttpRequest.Builder request(String path) {
            return HttpRequest.newBuilder(URI.create(settings.apiBase() + path)).timeout(Duration.ofSeconds(10))
                    .header("User-Agent", "DiscordBot (https://runeclans.com, linked-roles)");
        }

        private HttpResponse<String> send(String route, HttpRequest.Builder builder) throws Failure {
            HttpRequest request = builder.build();
            for (int attempt = 1; ; attempt++) {
                HttpResponse<String> response;
                try {
                    response = client.send(request, HttpResponse.BodyHandlers.ofString());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new Failure(route + " interrupted");
                } catch (Exception e) {
                    throw new Failure(route + " couldn't reach Discord (" + e.getClass().getSimpleName() + ")");
                }
                if (response.statusCode() != 429) return response;
                double wait = retryAfter(response);
                if (attempt >= TRIES || wait > 60) throw new Failure(route + " is rate limited for " + Math.round(wait) + "s");
                log.info("Discord rate-limited the {} call; waiting {}s", route, wait);
                try {
                    Thread.sleep((long) Math.ceil(wait * 1000));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new Failure(route + " interrupted");
                }
            }
        }

        static double retryAfter(HttpResponse<String> response) {
            try {
                return Math.max(0.1, DataObject.fromJson(response.body()).getDouble("retry_after"));
            } catch (RuntimeException e) {
                String header = response.headers().firstValue("Retry-After").orElse("1");
                try {
                    return Math.max(0.1, Double.parseDouble(header));
                } catch (NumberFormatException n) {
                    return 1;
                }
            }
        }

        /** The OAuth error code of an answer (a fixed word like invalid_grant, safe to log), or "" when there is none. */
        static String oauthError(String body) {
            try {
                String code = DataObject.fromJson(body).getString("error", "");
                return code.matches("[a-z_]{1,40}") ? code : "";
            } catch (RuntimeException e) {
                return "";
            }
        }

        private static String enc(String value) {
            return URLEncoder.encode(value, StandardCharsets.UTF_8);
        }
    }
}
