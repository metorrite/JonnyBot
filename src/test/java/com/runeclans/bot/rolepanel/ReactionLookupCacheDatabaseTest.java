package com.runeclans.bot.rolepanel;

import com.runeclans.bot.rolepanel.RolePanel.Mode;
import com.runeclans.bot.rolepanel.RolePanel.RoleButton;
import com.runeclans.bot.rolepanel.RolePanel.Rule;
import com.runeclans.bot.rolepanel.RolePanel.Style;
import com.runeclans.bot.starboard.StarboardConfig;
import com.runeclans.bot.starboard.StarboardRepository;
import com.runeclans.bot.testsupport.LocalDatabase;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two per-reaction lookups (starboard config, role panel by message) are cached per server. Over a real database (skipped without one):
 * the cached answers match a fresh, uncached read after every kind of write, and a burst of reactions costs one query per server, not one each.
 */
class ReactionLookupCacheDatabaseTest {
    private static final long G = Long.parseLong("9".repeat(15) + "471");
    private static final long OTHER = G + 1;
    private static final long CHANNEL = Long.parseLong("8".repeat(15) + "100");
    private static final long MSG_BUTTONS = CHANNEL + 1, MSG_REACTIONS = CHANNEL + 2, MSG_ATTACHED = CHANNEL + 3, MSG_NONE = CHANNEL + 4;
    private static final List<Long> MESSAGES = List.of(MSG_BUTTONS, MSG_REACTIONS, MSG_ATTACHED, MSG_NONE);

    private static ConnectionSupplier db;
    private static final AtomicInteger connections = new AtomicInteger();
    private static ConnectionSupplier counting;

    private RolePanelRepository panels;
    private StarboardRepository starboard;

    @BeforeAll
    static void connect() throws Exception {
        db = LocalDatabase.supplier();
        counting = Mockito.mock(ConnectionSupplier.class);
        Mockito.when(counting.getConnection()).thenAnswer(i -> {
            connections.incrementAndGet();
            return db.getConnection();
        });
    }

    @BeforeEach
    void setUp() {
        panels = new RolePanelRepository(counting);
        starboard = new StarboardRepository(counting);
    }

    @AfterEach
    void cleanUp() throws Exception {
        for (String table : List.of("role_panel", "starboard_config")) {
            try (Connection c = db.getConnection(); PreparedStatement s = c.prepareStatement("DELETE FROM younglings." + table + " WHERE guild_id IN (?, ?)")) {
                s.setLong(1, G);
                s.setLong(2, OTHER);
                s.executeUpdate();
            }
        }
    }

    private static RolePanel panel(long guild, String name, Style style) {
        return new RolePanel(null, guild, name, "", "", null, Mode.TOGGLE, null, null, List.of(new RoleButton(CHANNEL + 50, "x", "⭐", "primary")),
                style, Rule.NORMAL, false);
    }

    /** What the listener did before the cache: ask byMessage on a fresh repository and keep only a reactions panel. */
    private static Optional<Long> before(long guild, long message) {
        return new RolePanelRepository(db).byMessage(guild, message).filter(p -> p.style() == Style.REACTIONS).map(RolePanel::id);
    }

    /** What it does now: the cached gate first, then byMessage only on a hit. */
    private Optional<Long> after(long guild, long message) {
        if (!panels.mayHaveReactionPanel(guild, message)) return Optional.empty();
        return panels.byMessage(guild, message).filter(p -> p.style() == Style.REACTIONS).map(RolePanel::id);
    }

    private void assertSameDecisions() {
        for (long guild : List.of(G, OTHER)) {
            for (long message : MESSAGES) assertEquals(before(guild, message), after(guild, message), "guild " + guild + " message " + message);
        }
    }

    @Test
    void theRolePanelGateGivesTheSameAnswerAsByMessageAfterEveryWrite() {
        assertSameDecisions(); // nothing stored (and the empty set is now cached)

        RolePanel buttons = panels.save(panel(G, "buttons", Style.BUTTONS));
        RolePanel reactions = panels.save(panel(G, "reactions", Style.REACTIONS));
        RolePanel attached = panels.save(panel(G, "attached", Style.REACTIONS));
        RolePanel unposted = panels.save(panel(G, "unposted", Style.REACTIONS));
        assertSameDecisions();

        panels.setPosted(G, buttons.id(), CHANNEL, MSG_BUTTONS);
        assertSameDecisions();
        panels.setPosted(G, reactions.id(), CHANNEL, MSG_REACTIONS);
        panels.setPosted(G, attached.id(), CHANNEL, MSG_ATTACHED, true);
        assertSameDecisions();
        assertTrue(after(G, MSG_REACTIONS).isPresent());
        assertTrue(after(G, MSG_ATTACHED).isPresent());
        assertTrue(after(OTHER, MSG_REACTIONS).isEmpty(), "another server's reaction on the same message id is not this panel");

        // style changes both ways
        panels.save(new RolePanel(buttons.id(), G, "buttons", "", "", null, Mode.TOGGLE, null, null, buttons.buttons(), Style.REACTIONS, Rule.NORMAL, false));
        assertSameDecisions();
        assertTrue(after(G, MSG_BUTTONS).isPresent());
        panels.save(new RolePanel(reactions.id(), G, "reactions", "", "", null, Mode.TOGGLE, null, null, reactions.buttons(), Style.DROPDOWN, Rule.NORMAL, false));
        assertSameDecisions();
        assertTrue(after(G, MSG_REACTIONS).isEmpty());

        // moved, taken down, deleted
        panels.setPosted(G, unposted.id(), CHANNEL, MSG_NONE);
        assertSameDecisions();
        panels.setPosted(G, attached.id(), null, null);
        assertSameDecisions();
        panels.delete(G, buttons.id());
        assertSameDecisions();
        assertTrue(after(G, MSG_BUTTONS).isEmpty());
    }

    @Test
    void aBurstOfReactionsOnOrdinaryMessagesCostsOneQueryPerServer() {
        RolePanel reactions = panels.save(panel(G, "reactions", Style.REACTIONS));
        panels.setPosted(G, reactions.id(), CHANNEL, MSG_REACTIONS);
        connections.set(0);
        for (int i = 0; i < 1_000; i++) after(i % 2 == 0 ? G : OTHER, MSG_NONE + i);
        assertEquals(2, connections.get(), "one load per server, then nothing");
    }

    @Test
    void theStarboardConfigIsReadOnceAndAlwaysMatchesTheTable() throws Exception {
        connections.set(0);
        for (int i = 0; i < 1_000; i++) starboard.get(G);
        assertEquals(1, connections.get(), "one read, then the cache");
        assertEquals(new StarboardRepository(db).get(G), starboard.get(G));
        assertEquals(StarboardConfig.defaults(G), starboard.get(G));

        StarboardConfig saved = new StarboardConfig(G, true, CHANNEL, "⭐", 4, true, Map.of(CHANNEL + 9, 2), true, Set.of(CHANNEL + 7));
        starboard.save(saved);
        assertEquals(new StarboardRepository(db).get(G), starboard.get(G), "a save drops the cached config");
        assertEquals(saved, starboard.get(G));

        starboard.save(new StarboardConfig(G, false, null, "🔥", 3, false));
        assertEquals(new StarboardRepository(db).get(G), starboard.get(G));
        assertEquals(StarboardConfig.defaults(OTHER), starboard.get(OTHER));

        // a delete outside the repository (the retention purge) is picked up once evicted
        try (Connection c = db.getConnection(); PreparedStatement s = c.prepareStatement("DELETE FROM younglings.starboard_config WHERE guild_id = ?")) {
            s.setLong(1, G);
            s.executeUpdate();
        }
        starboard.evict(G);
        assertEquals(StarboardConfig.defaults(G), starboard.get(G));
    }

    @Test
    void anEvictedRolePanelSetIsReadAgain() throws Exception {
        RolePanel reactions = panels.save(panel(G, "reactions", Style.REACTIONS));
        panels.setPosted(G, reactions.id(), CHANNEL, MSG_REACTIONS);
        assertTrue(panels.mayHaveReactionPanel(G, MSG_REACTIONS));
        try (Connection c = db.getConnection(); PreparedStatement s = c.prepareStatement("DELETE FROM younglings.role_panel WHERE guild_id = ?")) {
            s.setLong(1, G);
            s.executeUpdate();
        }
        panels.evict(G);
        assertSameDecisions();
        assertTrue(!panels.mayHaveReactionPanel(G, MSG_REACTIONS));
    }
}
