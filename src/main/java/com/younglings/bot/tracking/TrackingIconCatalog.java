package com.younglings.bot.tracking;

import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Icon;
import net.dv8tion.jda.api.entities.emoji.ApplicationEmoji;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * The tracking system's icons (every {@link DropItemCatalog} item, plus a handful of category icons
 * for quests/clues/pets/archaeology) bundled locally under {@code src/main/resources/images/drops}
 * and {@code images/tracking}, uploaded once as Discord application emojis — same pattern, same
 * reasoning as {@link SkillEmojiCatalog} (an inline emoji mention is the only way to put an icon at
 * the start of a plain text line in Components V2 or a plain message).
 * <p>
 * Not every item has an icon file (a couple of the wiki's file names didn't match any pattern tried —
 * see {@link DropItemCatalog}'s javadoc) and not every category has one either (Citadel, Boss Kills,
 * and Minigame Misc post without an icon prefix for now) — {@link #mentionForDrop} /
 * {@link #mentionForCategory} just return {@code null} for those, same "not ready yet" contract
 * {@code SkillEmojiCatalog} already has for the brief window right after boot.
 */
@BService
public class TrackingIconCatalog extends ListenerAdapter {
    private static final Logger log = LoggerFactory.getLogger(TrackingIconCatalog.class);

    private static final List<String> CATEGORY_KEYS = List.of("quest", "clue", "pet", "archaeology");

    private static final Map<String, byte[]> DROP_BYTES = new HashMap<>();
    private static final Map<String, byte[]> CATEGORY_BYTES = new HashMap<>();

    static {
        int found = 0;
        for (DropItemCatalog.DropItem item : DropItemCatalog.all()) {
            byte[] bytes = readResource("images/drops/" + item.key() + ".png");
            if (bytes != null) {
                DROP_BYTES.put(item.key(), bytes);
                found++;
            }
        }
        for (String key : CATEGORY_KEYS) {
            byte[] bytes = readResource("images/tracking/" + key + ".png");
            if (bytes != null) CATEGORY_BYTES.put(key, bytes);
        }
        log.info("Loaded {}/{} drop icon images, {}/{} category icon images.",
                found, DropItemCatalog.all().size(), CATEGORY_BYTES.size(), CATEGORY_KEYS.size());
    }

    private static byte[] readResource(String resource) {
        try (InputStream stream = TrackingIconCatalog.class.getClassLoader().getResourceAsStream(resource)) {
            if (stream == null) return null;
            return stream.readAllBytes();
        } catch (IOException e) {
            log.warn("Failed to load icon resource '{}'", resource, e);
            return null;
        }
    }

    private final Map<String, String> dropMentions = new ConcurrentHashMap<>();
    private final Map<String, String> categoryMentions = new ConcurrentHashMap<>();

    @Override
    public void onReady(ReadyEvent event) {
        JDA jda = event.getJDA();
        jda.retrieveApplicationEmojis().queue(existing -> {
            Map<String, ApplicationEmoji> byName = existing.stream()
                    .collect(Collectors.toMap(ApplicationEmoji::getName, e -> e, (a, b) -> a));

            DROP_BYTES.forEach((key, bytes) ->
                    syncEmoji(jda, byName, "d_" + key, bytes, mention -> dropMentions.put(key, mention)));
            CATEGORY_BYTES.forEach((key, bytes) ->
                    syncEmoji(jda, byName, "cat_" + key, bytes, mention -> categoryMentions.put(key, mention)));
        }, error -> log.warn("Failed to retrieve application emojis for tracking icons", error));
    }

    private void syncEmoji(JDA jda, Map<String, ApplicationEmoji> existing, String name, byte[] bytes, Consumer<String> onMention) {
        ApplicationEmoji found = existing.get(name);
        if (found != null) {
            onMention.accept(found.getAsMention());
            return;
        }
        if (bytes == null) return;

        jda.createApplicationEmoji(name, Icon.from(bytes)).queue(
                created -> onMention.accept(created.getAsMention()),
                error -> log.warn("Failed to create application emoji '{}'", name, error));
    }

    /** {@code null} if this item has no icon (see class doc) or hasn't synced yet, briefly, right after boot. */
    public String mentionForDrop(String key) {
        return dropMentions.get(key);
    }

    /** {@code null} if this category has no icon or hasn't synced yet. Valid keys: {@code quest}, {@code clue}, {@code pet}, {@code archaeology}. */
    public String mentionForCategory(String key) {
        return categoryMentions.get(key);
    }
}
