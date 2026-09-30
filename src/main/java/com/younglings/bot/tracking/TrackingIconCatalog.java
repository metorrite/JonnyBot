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
 * The tracking system's icons (every {@link DropItemCatalog} item, every {@link BossCatalog} boss,
 * plus a handful of category icons for quests/clues/archaeology) bundled locally under
 * {@code src/main/resources/images/drops}, {@code images/bosses}, and {@code images/tracking},
 * uploaded once as Discord application emojis — same pattern, same reasoning as
 * {@link SkillEmojiCatalog} (an inline emoji mention is the only way to put an icon at the start of a
 * plain text line in Components V2 or a plain message). Kept in sync with the RS3 wiki by
 * {@code IconDownloader} — a standalone tool run manually, never by the bot itself.
 * <p>
 * No generic "pet" icon — see {@code IconDownloader}'s class doc for why a pet's name can't be
 * catalogued the way an item or boss can. {@link TrackingEventClassifier} shows the relevant skill's
 * own icon for a skilling pet, or a specific/default boss icon for a non-skilling one, instead of
 * asking this class for a "pet" icon at all.
 * <p>
 * Not every item/boss has an icon file yet — {@link #mentionForDrop} falls back to
 * {@link #mentionForDefaultDrop()}, {@link #mentionForBoss} to {@link #mentionForDefaultBoss()} — both
 * real wiki icons picked for exactly this purpose (a generic "something dropped"/"something defeated"
 * image), not a generated placeholder. Either default mention is itself {@code null} for the brief
 * window right after boot before it's synced, same "not ready yet" contract {@code SkillEmojiCatalog}
 * already has — and {@code null} for good if {@code IconDownloader} has never been run at all.
 */
@BService
public class TrackingIconCatalog extends ListenerAdapter {
    private static final Logger log = LoggerFactory.getLogger(TrackingIconCatalog.class);

    private static final List<String> CATEGORY_KEYS = List.of("quest", "clue", "archaeology");

    private static final Map<String, byte[]> DROP_BYTES = new HashMap<>();
    private static final Map<String, byte[]> CATEGORY_BYTES = new HashMap<>();
    private static final Map<String, byte[]> BOSS_BYTES = new HashMap<>();
    private static final byte[] DEFAULT_DROP_BYTES;
    private static final byte[] DEFAULT_BOSS_BYTES;

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
        int bossesFound = 0;
        for (BossCatalog.Boss boss : BossCatalog.all()) {
            byte[] bytes = readResource("images/bosses/" + boss.key() + ".png");
            if (bytes != null) {
                BOSS_BYTES.put(boss.key(), bytes);
                bossesFound++;
            }
        }
        DEFAULT_DROP_BYTES = readResource("images/tracking/default_drop.png");
        DEFAULT_BOSS_BYTES = readResource("images/tracking/default_boss.png");
        log.info("Loaded {}/{} drop icon images, {}/{} category icon images, {}/{} boss icon images, default drop icon: {}, default boss icon: {}.",
                found, DropItemCatalog.all().size(), CATEGORY_BYTES.size(), CATEGORY_KEYS.size(),
                bossesFound, BossCatalog.all().size(),
                DEFAULT_DROP_BYTES != null ? "found" : "missing — run IconDownloader",
                DEFAULT_BOSS_BYTES != null ? "found" : "missing — run IconDownloader");
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
    private final Map<String, String> bossMentions = new ConcurrentHashMap<>();
    private volatile String defaultDropMention;
    private volatile String defaultBossMention;

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
            BOSS_BYTES.forEach((key, bytes) ->
                    syncEmoji(jda, byName, "b_" + key, bytes, mention -> bossMentions.put(key, mention)));
            syncEmoji(jda, byName, "cat_default_drop", DEFAULT_DROP_BYTES, mention -> defaultDropMention = mention);
            syncEmoji(jda, byName, "cat_default_boss", DEFAULT_BOSS_BYTES, mention -> defaultBossMention = mention);
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

    /** Falls back to {@link #mentionForDefaultDrop()} if this item has no icon file yet — {@code null} only if even the default hasn't synced, briefly, right after boot. */
    public String mentionForDrop(String key) {
        String mention = dropMentions.get(key);
        return mention != null ? mention : defaultDropMention;
    }

    /** Falls back to {@link #mentionForDefaultBoss()} if this boss has no icon file yet. */
    public String mentionForBoss(String key) {
        String mention = bossMentions.get(key);
        return mention != null ? mention : defaultBossMention;
    }

    /** {@code null} if this category has no icon file yet — no default fallback here, unlike drops/bosses; a fixed single-icon category either has its one icon or it doesn't. Valid keys: {@code quest}, {@code clue}, {@code archaeology}. */
    public String mentionForCategory(String key) {
        return categoryMentions.get(key);
    }

    /** The generic "something dropped" icon used wherever a specific item icon isn't available — {@code null} if it hasn't synced yet, or {@code IconDownloader} has never been run. */
    public String mentionForDefaultDrop() {
        return defaultDropMention;
    }

    /** The generic "something defeated" icon used wherever a specific boss icon isn't available. */
    public String mentionForDefaultBoss() {
        return defaultBossMention;
    }
}
