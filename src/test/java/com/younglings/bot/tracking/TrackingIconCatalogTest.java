package com.younglings.bot.tracking;

import com.younglings.bot.runescape.IconEmojiHashRepository;
import com.younglings.bot.tracking.TrackingIconCatalog.SyncAction;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Icon;
import net.dv8tion.jda.api.entities.emoji.ApplicationEmoji;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.requests.RestAction;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TrackingIconCatalogTest {

    /** The sync rule exactly as it was before oversized files were skipped. */
    private static SyncAction previousRule(boolean exists, String storedHash, String currentHash, boolean forceRefresh) {
        if (!exists) return SyncAction.CREATE;
        if (storedHash == null && !forceRefresh) return SyncAction.REUSE_AND_RECORD_HASH;
        if (storedHash == null) storedHash = "";
        if (storedHash.equals(currentHash)) return SyncAction.REUSE;
        return SyncAction.RECREATE;
    }

    @Test
    void theSyncRuleIsUnchangedForEveryFileDiscordCanTake() {
        for (boolean exists : new boolean[]{false, true}) {
            for (String stored : Arrays.asList(null, "aa", "bb")) {
                for (boolean force : new boolean[]{false, true}) {
                    for (int size : new int[]{0, 1_000, TrackingIconCatalog.MAX_EMOJI_BYTES}) {
                        assertEquals(previousRule(exists, stored, "aa", force),
                                TrackingIconCatalog.decide(exists, stored, "aa", force, size),
                                "exists=" + exists + " stored=" + stored + " force=" + force + " size=" + size);
                    }
                }
            }
        }
    }

    @Test
    void anOversizedFileIsNeverUploadedButAnExistingEmojiIsStillReused() {
        int size = TrackingIconCatalog.MAX_EMOJI_BYTES + 1;
        for (boolean exists : new boolean[]{false, true}) {
            for (String stored : Arrays.asList(null, "aa", "bb")) {
                for (boolean force : new boolean[]{false, true}) {
                    SyncAction before = previousRule(exists, stored, "aa", force);
                    SyncAction now = TrackingIconCatalog.decide(exists, stored, exists ? "aa" : null, force, size);
                    boolean upload = before == SyncAction.CREATE || before == SyncAction.RECREATE;
                    assertEquals(upload ? SyncAction.SKIP_TOO_LARGE : before, now,
                            "exists=" + exists + " stored=" + stored + " force=" + force);
                }
            }
        }
    }

    @Test
    void noIconBytesStayOnTheHeapInStaticFields() throws Exception {
        Class.forName(TrackingIconCatalog.class.getName());
        for (Field field : TrackingIconCatalog.class.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers())) continue;
            assertNotEquals(byte[].class, field.getType(), field.getName() + " holds image bytes");
            field.setAccessible(true);
            if (field.get(null) instanceof Map<?, ?> map) {
                for (Object value : map.values()) {
                    assertTrue(value instanceof String, field.getName() + " holds " + value.getClass());
                }
            }
        }
    }

    /** name -> bytes of every icon the catalogue syncs, found the way the old static initialiser did. */
    private static Map<String, byte[]> allIcons() {
        Map<String, byte[]> icons = new LinkedHashMap<>();
        for (DropItemCatalog.DropItem item : DropItemCatalog.all()) put(icons, "d_" + item.key(), "images/drops/" + item.key() + ".png");
        for (String key : List.of("quest", "clue", "runescore", "citadel")) put(icons, "cat_" + key, "images/tracking/" + key + ".png");
        for (String key : BossCatalog.keys()) put(icons, "b_" + key, "images/bosses/" + key + ".png");
        for (int order = 0; order < 12; order++) put(icons, "rank_" + order, "images/ranks/" + order + ".png");
        put(icons, "cat_default_drop", "images/tracking/default_drop.png");
        put(icons, "cat_default_boss", "images/tracking/default_boss.png");
        return icons;
    }

    private static void put(Map<String, byte[]> icons, String name, String resource) {
        byte[] bytes = TrackingIconCatalog.readResource(resource);
        if (bytes != null) icons.put(name, bytes);
    }

    private static ApplicationEmoji emoji(String name) {
        ApplicationEmoji emoji = mock(ApplicationEmoji.class);
        when(emoji.getName()).thenReturn(name);
        when(emoji.getAsMention()).thenReturn("<:" + name + ":1>");
        return emoji;
    }

    @SuppressWarnings("unchecked")
    private static <T> RestAction<T> succeeding(T value) {
        RestAction<T> action = mock(RestAction.class);
        doAnswer(inv -> {
            ((Consumer<T>) inv.getArgument(0)).accept(value);
            return null;
        }).when(action).queue(any(), any());
        return action;
    }

    /** Runs one onReady against a fake Discord whose emoji store holds {@code existing}; returns the names it was asked to create. */
    private static List<String> sync(TrackingIconCatalog catalog, List<ApplicationEmoji> existing) {
        JDA jda = mock(JDA.class);
        ReadyEvent event = mock(ReadyEvent.class);
        when(event.getJDA()).thenReturn(jda);
        RestAction<List<ApplicationEmoji>> retrieve = succeeding(existing);
        when(jda.retrieveApplicationEmojis()).thenReturn(retrieve);
        List<String> created = new ArrayList<>();
        when(jda.createApplicationEmoji(anyString(), any(Icon.class))).thenAnswer(inv -> {
            String name = inv.getArgument(0);
            created.add(name);
            return succeeding(emoji(name));
        });
        catalog.onReady(event);
        return created;
    }

    @Test
    void anEmptyEmojiStoreGetsEveryUploadableIconAndNoOversizedOne() {
        Map<String, byte[]> icons = allIcons();
        IconEmojiHashRepository hashes = mock(IconEmojiHashRepository.class);
        when(hashes.getAllHashes()).thenReturn(new HashMap<>());
        TrackingIconCatalog catalog = new TrackingIconCatalog(hashes);

        List<String> created = sync(catalog, List.of());

        List<String> uploadable = icons.entrySet().stream()
                .filter(e -> e.getValue().length <= TrackingIconCatalog.MAX_EMOJI_BYTES).map(Map.Entry::getKey).sorted().toList();
        assertEquals(uploadable, created.stream().sorted().toList());
        assertFalse(uploadable.isEmpty());
        assertNotNull(catalog.mentionForDefaultBoss());
        assertEquals("<:cat_default_drop:1>", catalog.mentionForDefaultDrop());
        for (Map.Entry<String, byte[]> icon : icons.entrySet()) {
            if (icon.getKey().startsWith("b_") && icon.getValue().length > TrackingIconCatalog.MAX_EMOJI_BYTES) {
                // Same as before, when Discord rejected the upload: the boss shows the default icon.
                assertEquals(catalog.mentionForDefaultBoss(), catalog.mentionForBoss(icon.getKey().substring(2)));
            }
        }
    }

    @Test
    void anExistingOversizedEmojiIsReusedOrKeptButNeverDeleted() {
        Map<String, byte[]> icons = allIcons();
        List<String> oversizedBosses = icons.entrySet().stream()
                .filter(e -> e.getKey().startsWith("b_") && e.getValue().length > TrackingIconCatalog.MAX_EMOJI_BYTES)
                .map(Map.Entry::getKey).toList();
        if (oversizedBosses.size() < 2) return; // nothing oversized left in the resources to check
        String noHash = oversizedBosses.get(0);
        String staleHash = oversizedBosses.get(1);
        ApplicationEmoji legacy = emoji(noHash);
        ApplicationEmoji stale = emoji(staleHash);

        IconEmojiHashRepository hashes = mock(IconEmojiHashRepository.class);
        Map<String, String> stored = new HashMap<>();
        stored.put(staleHash, "0".repeat(64));
        when(hashes.getAllHashes()).thenReturn(stored);
        TrackingIconCatalog catalog = new TrackingIconCatalog(hashes);

        List<String> created = sync(catalog, List.of(legacy, stale));

        // No recorded hash: trusted and backfilled, exactly as before.
        assertEquals("<:" + noHash + ":1>", catalog.mentionForBoss(noHash.substring(2)));
        verify(hashes).setHash(eq(noHash), any());
        verify(legacy, never()).delete();
        // A changed oversized file: nothing is deleted or uploaded, and the boss shows the default icon,
        // which is what it showed before after the doomed re-upload failed.
        verify(stale, never()).delete();
        assertFalse(created.contains(staleHash));
        assertEquals(catalog.mentionForDefaultBoss(), catalog.mentionForBoss(staleHash.substring(2)));
    }
}
