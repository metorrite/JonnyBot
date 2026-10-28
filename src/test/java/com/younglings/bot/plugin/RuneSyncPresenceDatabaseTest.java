package com.younglings.bot.plugin;

import com.younglings.bot.testsupport.LocalDatabase;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** T-586 over real SQL: "in the game now" is a recent check-in, the Presence category switched on, and RuneSync not paused. */
class RuneSyncPresenceDatabaseTest {
    private static final long ON = Long.parseLong("7".repeat(17) + "1");
    private static final long NO_CONSENT = Long.parseLong("7".repeat(17) + "2");
    private static final long PAUSED = Long.parseLong("7".repeat(17) + "3");
    private static final long LONG_AGO = Long.parseLong("7".repeat(17) + "4");
    private static final List<Long> USERS = List.of(ON, NO_CONSENT, PAUSED, LONG_AGO);
    private static final Instant NOW = Instant.parse("2026-10-27T12:00:00Z");
    private static ConnectionSupplier supplier;
    private RuneSyncRepository repository;
    private PluginRepository plugins;

    @BeforeAll
    static void connect() throws Exception {
        supplier = LocalDatabase.supplier();
    }

    @BeforeEach
    void setUp() throws Exception {
        plugins = new PluginRepository(supplier);
        repository = new RuneSyncRepository(supplier);
        cleanUp();
    }

    @AfterEach
    void cleanUp() throws Exception {
        try (Connection c = supplier.getConnection()) {
            for (String table : List.of("plugin_client_status", "plugin_consent")) {
                try (PreparedStatement s = c.prepareStatement("DELETE FROM younglings." + table + " WHERE discord_user_id = ANY (?)")) {
                    s.setArray(1, c.createArrayOf("bigint", USERS.toArray(new Long[0])));
                    s.executeUpdate();
                }
            }
        }
    }

    @Test
    void onlyRecentConsentingUnpausedMembers() {
        Set<PluginCategory> presence = EnumSet.of(PluginCategory.PRESENCE, PluginCategory.XP);
        plugins.setConsent(ON, presence);
        plugins.setConsent(NO_CONSENT, EnumSet.of(PluginCategory.XP));
        plugins.setConsent(PAUSED, presence);
        plugins.setConsent(LONG_AGO, presence);
        repository.recordCheckIn(ON, "RuneSync 0.0.9", "Skill Queen", false, NOW.minusSeconds(180));
        repository.recordCheckIn(ON, "RuneSync 0.0.9", false, NOW.minusSeconds(120)); // a check-in without a RuneLink name keeps the name
        repository.recordCheckIn(NO_CONSENT, "RuneSync 0.0.9", false, NOW.minusSeconds(60));
        repository.recordCheckIn(PAUSED, "RuneSync 0.0.9", true, NOW.minusSeconds(60));
        repository.recordCheckIn(LONG_AGO, "RuneSync 0.0.9", false, NOW.minusSeconds(60 * 30));

        List<RuneSyncRepository.Present> present = repository.presentSince(NOW.minusSeconds(11 * 60)).stream()
                .filter(p -> USERS.contains(p.discordUserId())).toList();
        assertEquals(List.of(ON), present.stream().map(RuneSyncRepository.Present::discordUserId).toList());
        assertEquals("Skill Queen", present.get(0).rsn());
        assertEquals(NOW.minusSeconds(120), present.get(0).lastSeen());
    }
}
