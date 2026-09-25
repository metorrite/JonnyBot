package com.younglings.bot.runescape;

import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Icon;
import net.dv8tion.jda.api.entities.emoji.ApplicationEmoji;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A guild's own server icon, uploaded as a Discord application emoji so it can sit inline at the
 * very start of a text line — same reasoning as {@link SkillEmojiCatalog}: a {@code Section}'s
 * thumbnail accessory always renders on the right of its text (verified live, no configuration
 * changes that), so there's no way to put an image left-of-text in Components V2 except an inline
 * emoji mention, which can go anywhere in a string.
 * <p>
 * Unlike the skill icons (bundled, fixed, all uploaded once at boot), a guild's icon is fetched over
 * HTTP and only known once a guild is actually in play, so this uploads lazily on first need per
 * guild rather than eagerly for every guild at startup. Matched by name against existing application
 * emojis first, so a restart doesn't re-upload — and, same trade-off {@link SkillEmojiCatalog}
 * already makes, doesn't re-check for a changed server icon after that first upload.
 */
@BService
public class GuildIconEmojiCatalog {
    private static final Logger log = LoggerFactory.getLogger(GuildIconEmojiCatalog.class);

    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final Map<Long, String> mentionsByGuildId = new ConcurrentHashMap<>();
    private final Map<Long, Boolean> uploadInFlight = new ConcurrentHashMap<>();

    /**
     * This guild's icon as an inline emoji mention, or {@code null} if the guild has no icon set, or
     * it hasn't finished uploading yet — briefly, only the first time a given guild needs this after
     * a boot, same "not ready yet" window {@link SkillEmojiCatalog} has right after startup.
     */
    public String mentionFor(Guild guild) {
        String cached = mentionsByGuildId.get(guild.getIdLong());
        if (cached != null) return cached;

        if (guild.getIconUrl() != null) ensureUploaded(guild);
        return null;
    }

    private void ensureUploaded(Guild guild) {
        long guildId = guild.getIdLong();
        if (uploadInFlight.putIfAbsent(guildId, true) != null) return; // already fetching/uploading

        JDA jda = guild.getJDA();
        String emojiName = "clanicon_" + guildId;

        jda.retrieveApplicationEmojis().queue(existing -> {
            ApplicationEmoji found = existing.stream().filter(e -> e.getName().equals(emojiName)).findFirst().orElse(null);
            if (found != null) {
                mentionsByGuildId.put(guildId, found.getAsMention());
                uploadInFlight.remove(guildId);
                return;
            }

            byte[] bytes = fetchIconBytes(guild);
            if (bytes == null) {
                uploadInFlight.remove(guildId);
                return;
            }

            jda.createApplicationEmoji(emojiName, Icon.from(bytes)).queue(
                    created -> {
                        mentionsByGuildId.put(guildId, created.getAsMention());
                        uploadInFlight.remove(guildId);
                    },
                    error -> {
                        log.warn("Failed to create application emoji for guild {}'s icon", guildId, error);
                        uploadInFlight.remove(guildId);
                    });
        }, error -> {
            log.warn("Failed to retrieve application emojis while syncing guild {}'s icon", guildId, error);
            uploadInFlight.remove(guildId);
        });
    }

    private byte[] fetchIconBytes(Guild guild) {
        try {
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(guild.getIconUrl() + "?size=128")).GET().build();
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            return response.statusCode() / 100 == 2 ? response.body() : null;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.warn("Failed to fetch guild icon for guild {}", guild.getIdLong(), e);
            return null;
        }
    }
}
