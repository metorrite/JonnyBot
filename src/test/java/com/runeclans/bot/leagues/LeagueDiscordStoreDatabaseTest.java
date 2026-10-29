package com.runeclans.bot.leagues;

import com.runeclans.bot.testsupport.LocalDatabase;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.Statement;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The league channel rows (T-760): pick, change, clear and list. Runs against the local test database. */
class LeagueDiscordStoreDatabaseTest {
    private static final long A = Long.parseLong("2".repeat(18));
    private static final long B = Long.parseLong("3".repeat(18));

    private ConnectionSupplier db;
    private LeagueDiscordStore store;

    @BeforeEach
    void setUp() throws Exception {
        db = LocalDatabase.supplier();
        store = new LeagueDiscordStore(db);
        try (Connection c = db.getConnection(); Statement st = c.createStatement()) {
            st.execute("DELETE FROM younglings.league_discord WHERE guild_id IN (" + A + ", " + B + ")");
        }
    }

    @Test
    void pickChangeClearAndList() {
        assertEquals(Optional.empty(), store.channel(A));
        store.setChannel(A, 100L, 7L);
        store.setChannel(B, 200L, 7L);
        assertEquals(Optional.of(100L), store.channel(A));
        store.setChannel(A, 150L, 8L);
        assertEquals(Optional.of(150L), store.channel(A));
        Map<Long, Long> all = store.channels();
        assertEquals(150L, all.get(A));
        assertEquals(200L, all.get(B));
        store.setChannel(A, null, 8L);
        assertEquals(Optional.empty(), store.channel(A));
        assertEquals(false, store.channels().containsKey(A), "a cleared channel is not listed");
        assertEquals(200L, store.channels().get(B));
    }
}
