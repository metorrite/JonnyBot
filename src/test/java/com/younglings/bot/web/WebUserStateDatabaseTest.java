package com.younglings.bot.web;

import com.younglings.bot.testsupport.LocalDatabase;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A person's website settings (T-407) against the local Postgres (skipped where there isn't one), with made-up ids. */
class WebUserStateDatabaseTest {
    private static final long BASE = Long.parseLong("9".repeat(17));
    private static final long ME = BASE + 51;
    private static final long SOMEONE_ELSE = BASE + 52;
    private static final String SITE = "site:" + (BASE + 53);
    private ConnectionSupplier db;
    private WebUserStateRepository state;

    @BeforeEach
    void setUp() throws Exception {
        db = LocalDatabase.supplier();
        state = new WebUserStateRepository(db);
        state.deleteAll(ME);
        state.deleteAll(SOMEONE_ELSE);
    }

    @AfterEach
    void tearDown() {
        if (state != null) {
            state.deleteAll(ME);
            state.deleteAll(SOMEONE_ELSE);
        }
    }

    @Test
    void putGetAndDelete() {
        assertEquals(WebUserStateRepository.Put.SAVED, state.put(ME, WebUserState.GLOBAL, "tour.edit-mode-basics",
                "{\"version\":1,\"step\":3,\"status\":\"in-progress\"}"));
        assertEquals(WebUserStateRepository.Put.SAVED, state.put(ME, WebUserState.GLOBAL, "editor.mode", "\"edit\""));
        Map<String, String> all = state.get(ME, WebUserState.GLOBAL, List.of());
        assertEquals(List.of("editor.mode", "tour.edit-mode-basics"), List.copyOf(all.keySet()));
        assertEquals("\"edit\"", all.get("editor.mode"));
        assertTrue(all.get("tour.edit-mode-basics").contains("\"step\": 3"), "jsonb gives the value back as JSON: " + all);

        assertEquals(WebUserStateRepository.Put.SAVED, state.put(ME, WebUserState.GLOBAL, "editor.mode", "\"quick\""), "changing a value");
        assertEquals(Map.of("editor.mode", "\"quick\""), state.get(ME, WebUserState.GLOBAL, List.of("editor.mode")), "only the keys asked for");

        assertTrue(state.delete(ME, WebUserState.GLOBAL, "editor.mode"));
        assertFalse(state.delete(ME, WebUserState.GLOBAL, "editor.mode"), "already gone");
        assertEquals(1, state.get(ME, WebUserState.GLOBAL, List.of()).size());
    }

    @Test
    void scopesAndPeopleAreKeptApart() {
        state.put(ME, WebUserState.GLOBAL, "theme", "\"dark\"");
        state.put(ME, SITE, "theme", "\"parchment\"");
        state.put(SOMEONE_ELSE, SITE, "theme", "\"minimal\"");
        assertEquals(Map.of("theme", "\"dark\""), state.get(ME, WebUserState.GLOBAL, List.of()));
        assertEquals(Map.of("theme", "\"parchment\""), state.get(ME, SITE, List.of()));
        assertEquals(Map.of("theme", "\"minimal\""), state.get(SOMEONE_ELSE, SITE, List.of()), "another person's value is never mixed in");
        assertTrue(state.get(SOMEONE_ELSE, WebUserState.GLOBAL, List.of()).isEmpty());
    }

    @Test
    void fiftyKeysAScope() {
        for (int i = 0; i < WebUserState.MAX_KEYS_PER_SCOPE; i++) {
            assertEquals(WebUserStateRepository.Put.SAVED, state.put(ME, WebUserState.GLOBAL, "k" + i, String.valueOf(i)));
        }
        assertEquals(WebUserStateRepository.Put.TOO_MANY_KEYS, state.put(ME, WebUserState.GLOBAL, "one-more", "1"));
        assertEquals(WebUserStateRepository.Put.SAVED, state.put(ME, WebUserState.GLOBAL, "k7", "70"), "an existing key can still change");
        assertEquals(WebUserStateRepository.Put.SAVED, state.put(ME, SITE, "one-more", "1"), "the limit is per scope");
        assertEquals(WebUserState.MAX_KEYS_PER_SCOPE, state.get(ME, WebUserState.GLOBAL, List.of()).size());
    }

    @Test
    void deleteMyDataRemovesEveryScope() {
        state.put(ME, WebUserState.GLOBAL, "a", "1");
        state.put(ME, SITE, "b", "2");
        state.put(SOMEONE_ELSE, SITE, "b", "3");
        assertEquals(2, state.deleteAll(ME));
        assertTrue(state.get(ME, WebUserState.GLOBAL, List.of()).isEmpty());
        assertTrue(state.get(ME, SITE, List.of()).isEmpty());
        assertEquals(1, state.get(SOMEONE_ELSE, SITE, List.of()).size(), "nobody else's");
    }
}
