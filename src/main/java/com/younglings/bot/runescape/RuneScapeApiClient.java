package com.younglings.bot.runescape;

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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Thin client for RuneScape 3's public web endpoints — no API key, no OSRS. Both endpoints are
 * undocumented-but-stable public JSON/image APIs used by the RuneScape website itself; RuneMetrics'
 * own website was retired but its API endpoints keep working as of this writing, so failures are
 * handled as "this data just isn't available right now" rather than something to crash over.
 */
@BService
public class RuneScapeApiClient {
    private static final Logger log = LoggerFactory.getLogger(RuneScapeApiClient.class);

    private static final String PROFILE_URL = "https://apps.runescape.com/runemetrics/profile/profile?user=%s&activities=1";
    private static final String AVATAR_URL = "https://secure.runescape.com/m=avatar-rs/%s/chat.png";

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /** Empty if the player doesn't exist, has their profile set to private, or the request failed. */
    public Optional<RuneScapeProfile> fetchProfile(String rsn) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(PROFILE_URL.formatted(encode(rsn))))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                log.warn("RuneMetrics profile request for '{}' returned HTTP {}", rsn, response.statusCode());
                return Optional.empty();
            }

            DataObject json = DataObject.fromJson(response.body());
            if (json.hasKey("error")) {
                log.info("RuneMetrics profile for '{}' unavailable: {}", rsn, json.getString("error", "unknown"));
                return Optional.empty();
            }

            List<SkillValue> skills = new ArrayList<>();
            DataArray skillArray = json.getArray("skillvalues");
            for (int i = 0; i < skillArray.length(); i++) {
                DataObject skill = skillArray.getObject(i);
                skills.add(new SkillValue(skill.getInt("id"), skill.getInt("level"), skill.getLong("xp"), skill.getInt("rank")));
            }

            return Optional.of(new RuneScapeProfile(
                    json.getString("name"),
                    json.getInt("totalskill"),
                    json.getLong("totalxp"),
                    json.getInt("combatlevel"),
                    json.getInt("questscomplete"),
                    json.getInt("questsstarted"),
                    json.getInt("questsnotstarted"),
                    skills
            ));

        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.warn("Failed to fetch RuneMetrics profile for '{}'", rsn, e);
            return Optional.empty();
        } catch (Exception e) {
            log.warn("Failed to parse RuneMetrics profile for '{}'", rsn, e);
            return Optional.empty();
        }
    }

    /**
     * Raw PNG bytes of the player's current chat-head avatar (reflects their actual in-game
     * customization, not achievements or activity). Note: an invalid/unrecognized name still
     * returns HTTP 200 with a generic placeholder image rather than an error — this method can't
     * tell that apart from a real avatar, so callers relying on it for verification should have a
     * human look at the result rather than trust presence alone.
     */
    public Optional<byte[]> fetchAvatarImage(String rsn) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(AVATAR_URL.formatted(encode(rsn))))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();

            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() / 100 != 2) {
                log.warn("Avatar image request for '{}' returned HTTP {}", rsn, response.statusCode());
                return Optional.empty();
            }

            return Optional.of(response.body());

        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.warn("Failed to fetch avatar image for '{}'", rsn, e);
            return Optional.empty();
        }
    }

    private static String encode(String rsn) {
        return URLEncoder.encode(rsn, StandardCharsets.UTF_8);
    }
}
