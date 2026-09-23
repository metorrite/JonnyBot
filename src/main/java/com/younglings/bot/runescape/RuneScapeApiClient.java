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
    private static final String HISCORES_URL = "https://secure.runescape.com/m=hiscore/index_lite.ws?player=%s";

    // NORMAL is required: every one of these endpoints (avatar image especially) responds with an
    // HTTP redirect rather than the resource directly (verified live), and HttpClient's default
    // policy when unset is Redirect.NEVER — without this, every avatar fetch silently "failed"
    // with a bare 302 and no body, never actually reaching the image.
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
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

            List<PlayerActivity> activities = new ArrayList<>();
            if (json.hasKey("activities")) {
                DataArray activityArray = json.getArray("activities");
                for (int i = 0; i < activityArray.length(); i++) {
                    DataObject activity = activityArray.getObject(i);
                    activities.add(new PlayerActivity(
                            activity.getString("date", ""),
                            activity.getString("text", ""),
                            activity.getString("details", "")));
                }
            }

            return Optional.of(new RuneScapeProfile(
                    json.getString("name"),
                    json.getInt("totalskill"),
                    json.getLong("totalxp"),
                    json.getInt("combatlevel"),
                    json.getInt("questscomplete"),
                    json.getInt("questsstarted"),
                    json.getInt("questsnotstarted"),
                    skills,
                    activities
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
     * The player's current chat-head avatar (reflects their actual in-game customization, not
     * achievements or activity) — see {@link AvatarResult} for the three possible outcomes. An
     * invalid/unrecognized RSN redirects to the same shared placeholder as a real account that's
     * never set a custom look, so those two report as the same {@link AvatarResult.NotCustomized}
     * outcome — this method can't tell them apart, and callers should say so rather than imply the
     * name was necessarily wrong.
     */
    public AvatarResult fetchAvatarImage(String rsn) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(AVATAR_URL.formatted(encode(rsn))))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();

            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() / 100 != 2) {
                log.warn("Avatar image request for '{}' returned HTTP {}", rsn, response.statusCode());
                return new AvatarResult.Unavailable();
            }

            if (response.uri().getPath().endsWith("default_chat.png")) {
                return new AvatarResult.NotCustomized();
            }

            return new AvatarResult.Found(response.body());

        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.warn("Failed to fetch avatar image for '{}'", rsn, e);
            return new AvatarResult.Unavailable();
        }
    }

    /**
     * The "Overall" line plus all 29 individual skill lines from the classic hiscores CSV — a
     * separate, older endpoint from RuneMetrics, kept independently toggleable by players, so it's
     * a useful fallback for a player whose RuneMetrics profile is private but hiscores aren't.
     * <p>
     * Line order (0 = Overall, 1-29 = skills in {@link RuneScapeSkillCatalog} order) confirmed live
     * by cross-referencing a real account's per-line rank against RuneMetrics' {@code skillvalues}
     * ranks for the same account — every one of the 29 skills matched by rank in sequence, not
     * assumed from documentation. Lines after the 30th (minigames/bosses/clue scrolls) aren't
     * parsed — that list is far less stable and wasn't verified with the same confidence.
     */
    public Optional<HiscoresOverall> fetchHiscoresOverall(String rsn) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(HISCORES_URL.formatted(encode(rsn))))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                log.warn("Hiscores request for '{}' returned HTTP {}", rsn, response.statusCode());
                return Optional.empty();
            }

            List<String> lines = response.body().lines().toList();
            if (lines.isEmpty()) return Optional.empty();

            String[] overallParts = lines.getFirst().split(",");
            if (overallParts.length < 3) return Optional.empty();

            long rank = Long.parseLong(overallParts[0].trim());
            int totalLevel = Integer.parseInt(overallParts[1].trim());
            long totalXp = Long.parseLong(overallParts[2].trim());

            // -1 is the hiscores sentinel for "unranked" (also used for a nonexistent player).
            if (rank < 0) return Optional.empty();

            List<SkillValue> skills = new ArrayList<>();
            int skillLines = Math.min(RuneScapeSkillCatalog.skillCount(), lines.size() - 1);
            for (int skillId = 0; skillId < skillLines; skillId++) {
                String[] parts = lines.get(skillId + 1).split(",");
                if (parts.length < 3) continue;

                int skillRank = Integer.parseInt(parts[0].trim());
                int skillLevel = Integer.parseInt(parts[1].trim());
                long skillXp = Long.parseLong(parts[2].trim());
                skills.add(new SkillValue(skillId, skillLevel, skillXp, skillRank));
            }

            return Optional.of(new HiscoresOverall(rank, totalLevel, totalXp, skills));

        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.warn("Failed to fetch hiscores for '{}'", rsn, e);
            return Optional.empty();
        } catch (Exception e) {
            log.warn("Failed to parse hiscores for '{}'", rsn, e);
            return Optional.empty();
        }
    }

    private static String encode(String rsn) {
        return URLEncoder.encode(rsn, StandardCharsets.UTF_8);
    }
}
