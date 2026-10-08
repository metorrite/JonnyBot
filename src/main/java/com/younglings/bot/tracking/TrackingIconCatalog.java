package com.younglings.bot.tracking;

import com.younglings.bot.runescape.IconEmojiHashRepository;
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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * The tracking system's icons (every {@link DropItemCatalog} item, every {@link BossCatalog} boss,
 * plus a handful of category icons for quests/clues/RuneScore/the Clan Citadel) bundled locally under
 * {@code src/main/resources/images/drops}, {@code images/bosses}, and {@code images/tracking},
 * uploaded once as Discord application emojis — same pattern, same reasoning as
 * {@link com.younglings.bot.runescape.SkillEmojiCatalog} (an inline emoji mention is the only way to put an icon at the start of a
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
 * <p>
 * Clan rank badges ({@link #mentionForRank}) come from the wiki's own Clan Chat rank table
 * ({@code runescape.wiki/w/RuneScape:Clan_Chat#Ranks}, {@code "{RankName}_clan_rank.png"}) — a
 * different filename pattern than the first guess ({@code "{RankName}_icon.png"}), which 404'd for
 * every tier. 12 standard tiers, not the 11 first assumed (an "Overseer" rank between Coordinator and
 * Deputy Owner was missed originally) — see {@code IconDownloader#RANK_SOURCES} and
 * {@code ClanPointsRepository#STANDARD_RANK_NAMES}, which must stay in the same order (index = rank_order).
 * <p>
 * An emoji already matched by name is reused as-is on every restart, normally — but that alone means a
 * corrected icon (re-running {@code IconDownloader} after fixing a wrong source) does nothing: the
 * stale emoji under that same name just keeps getting reused forever, exactly what happened twice this
 * way (a wrong quest icon survived a source-URL fix on one bot, then the same thing again on a second,
 * completely separate bot application with its own isolated emoji store). {@link #syncEmoji} now hashes
 * each local file and compares it against what {@link IconEmojiHashRepository} recorded for that name
 * last time — a mismatch deletes the stale emoji and recreates it from the current file, instead of
 * silently trusting whatever's already there.
 */
@BService
public class TrackingIconCatalog extends ListenerAdapter {
    private static final Logger log = LoggerFactory.getLogger(TrackingIconCatalog.class);

    // "archaeology" isn't here — an archaeological mystery reuses the Archaeology *skill* icon
    // (already covered by SkillEmojiCatalog) instead of its own downloaded one; the "arch cape" guess
    // that used to live here didn't read as archaeology-related at all. "runescore" is the generic
    // "something happened" fallback for any group with no icon system of its own (Minigame/Misc, Clan
    // Joins/Leaves, ...) — a loot beam doesn't fit an event that isn't a drop. "citadel" is
    // Citadel Activity's own default, distinct from both.
    private static final List<String> CATEGORY_KEYS = List.of("quest", "clue", "runescore", "citadel");

    // Standard Jagex clan rank ladder has a fixed 12 tiers (0 = Recruit .. 11 = Owner) — matches
    // ClanPointsRepository's own seed order exactly, keyed by that stable rank_order rather than a
    // rank's (admin-editable) name, so renaming a rank never breaks its icon lookup.
    private static final int RANK_COUNT = 12;

    private static final Map<String, byte[]> DROP_BYTES = new HashMap<>();
    private static final Map<String, byte[]> CATEGORY_BYTES = new HashMap<>();
    private static final Map<String, byte[]> BOSS_BYTES = new HashMap<>();
    private static final Map<Integer, byte[]> RANK_BYTES = new HashMap<>();
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
        List<String> bossKeys = BossCatalog.keys();
        for (String bossKey : bossKeys) {
            byte[] bytes = readResource("images/bosses/" + bossKey + ".png");
            if (bytes != null) {
                BOSS_BYTES.put(bossKey, bytes);
                bossesFound++;
            }
        }
        int ranksFound = 0;
        for (int order = 0; order < RANK_COUNT; order++) {
            byte[] bytes = readResource("images/ranks/" + order + ".png");
            if (bytes != null) {
                RANK_BYTES.put(order, bytes);
                ranksFound++;
            }
        }
        DEFAULT_DROP_BYTES = readResource("images/tracking/default_drop.png");
        DEFAULT_BOSS_BYTES = readResource("images/tracking/default_boss.png");
        log.info("Loaded {}/{} drop icon images, {}/{} category icon images, {}/{} boss icon images, {}/{} rank icon images, default drop icon: {}, default boss icon: {}.",
                found, DropItemCatalog.all().size(), CATEGORY_BYTES.size(), CATEGORY_KEYS.size(),
                bossesFound, bossKeys.size(), ranksFound, RANK_COUNT,
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

    private final IconEmojiHashRepository hashRepository;

    private final Map<String, String> dropMentions = new ConcurrentHashMap<>();
    private final Map<String, String> categoryMentions = new ConcurrentHashMap<>();
    private final Map<String, String> bossMentions = new ConcurrentHashMap<>();
    private final Map<Integer, String> rankMentions = new ConcurrentHashMap<>();
    private volatile String defaultDropMention;
    private volatile String defaultBossMention;

    public TrackingIconCatalog(IconEmojiHashRepository hashRepository) {
        this.hashRepository = hashRepository;
    }

    @Override
    public void onReady(ReadyEvent event) {
        JDA jda = event.getJDA();
        Map<String, String> storedHashes = hashRepository.getAllHashes();

        jda.retrieveApplicationEmojis().queue(existing -> {
            Map<String, ApplicationEmoji> byName = existing.stream()
                    .collect(Collectors.toMap(ApplicationEmoji::getName, e -> e, (a, b) -> a));

            DROP_BYTES.forEach((key, bytes) ->
                    syncEmoji(jda, byName, storedHashes, "d_" + key, bytes, mention -> dropMentions.put(key, mention)));
            CATEGORY_BYTES.forEach((key, bytes) ->
                    syncEmoji(jda, byName, storedHashes, "cat_" + key, bytes, mention -> categoryMentions.put(key, mention)));
            BOSS_BYTES.forEach((key, bytes) ->
                    syncEmoji(jda, byName, storedHashes, "b_" + key, bytes, mention -> bossMentions.put(key, mention)));
            RANK_BYTES.forEach((order, bytes) ->
                    syncEmoji(jda, byName, storedHashes, "rank_" + order, bytes, mention -> rankMentions.put(order, mention)));
            syncEmoji(jda, byName, storedHashes, "cat_default_drop", DEFAULT_DROP_BYTES, mention -> defaultDropMention = mention);
            syncEmoji(jda, byName, storedHashes, "cat_default_boss", DEFAULT_BOSS_BYTES, mention -> defaultBossMention = mention);
        }, error -> log.warn("Failed to retrieve application emojis for tracking icons", error));
    }

    // One-time exception to the "no recorded hash yet = trust it" rule below, for the specific name
    // already confirmed wrong on more than one bot application (a stale "book" guess surviving a
    // source-URL fix, since nothing before this ever noticed an existing emoji's file had changed) —
    // forces that one name through the recreate path on its very first post-deploy boot even with no
    // hash history, instead of needing someone to hand-delete it from each bot's own emoji store the
    // way this had to be fixed manually before this check existed. Safe to remove once every bot
    // application has gone through one boot with this change.
    private static final java.util.Set<String> FORCE_REFRESH_ONCE = java.util.Set.of("cat_quest");

    /**
     * An existing emoji under this name is reused as-is if its recorded hash matches the current file
     * (the common case — nothing changed), recreated from scratch if it doesn't (the file was corrected
     * since this emoji was last uploaded), or just trusted and backfilled with today's hash if there's
     * no recorded hash at all yet (a legacy emoji from before this check existed — assumed correct
     * rather than mass-recreating every already-fine emoji the first time this ships) — unless it's in
     * {@link #FORCE_REFRESH_ONCE}.
     */
    private void syncEmoji(JDA jda, Map<String, ApplicationEmoji> existing, Map<String, String> storedHashes,
                            String name, byte[] bytes, Consumer<String> onMention) {
        if (bytes == null) return;
        ApplicationEmoji found = existing.get(name);
        String currentHash = sha256Hex(bytes);

        if (found == null) {
            createEmoji(jda, name, bytes, currentHash, onMention);
            return;
        }

        String storedHash = storedHashes.get(name);
        if (storedHash == null && !FORCE_REFRESH_ONCE.contains(name)) {
            onMention.accept(found.getAsMention());
            hashRepository.setHash(name, currentHash);
            return;
        }
        if (storedHash == null) storedHash = ""; // forced refresh — never matches currentHash, falls through to recreate below
        if (storedHash.equals(currentHash)) {
            onMention.accept(found.getAsMention());
            return;
        }

        log.info("Icon file for emoji '{}' changed since it was last uploaded — recreating it.", name);
        found.delete().queue(
                success -> createEmoji(jda, name, bytes, currentHash, onMention),
                error -> log.warn("Failed to delete stale application emoji '{}' for replacement", name, error));
    }

    private void createEmoji(JDA jda, String name, byte[] bytes, String contentHash, Consumer<String> onMention) {
        jda.createApplicationEmoji(name, Icon.from(bytes)).queue(
                created -> {
                    onMention.accept(created.getAsMention());
                    hashRepository.setHash(name, contentHash);
                },
                error -> log.warn("Failed to create application emoji '{}'", name, error));
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 should always be available", e);
        }
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

    /** {@code null} if this category has no icon file yet — no default fallback here, unlike drops/bosses; a fixed single-icon category either has its one icon or it doesn't. Valid keys: {@code quest}, {@code clue}, {@code runescore}, {@code citadel}. */
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

    /**
     * A clan rank's own badge icon, by its stable {@code rank_order} (0 = Recruit .. 11 = Owner —
     * see {@code ClanPointsRepository}). Falls back to {@link #mentionForDefaultBoss()} if that rank
     * has no dedicated icon file yet (e.g. {@code IconDownloader} hasn't been re-run since this was
     * added) — {@code null} if even that hasn't synced.
     */
    public String mentionForRank(int rankOrder) {
        String mention = rankMentions.get(rankOrder);
        return mention != null ? mention : defaultBossMention;
    }
}
