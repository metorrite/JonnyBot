package com.runeclans.bot.clansite;

import com.runeclans.bot.testsupport.LocalDatabase;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Discord on a clan website over real SQL (T-365): a member's opt-out toggles per server. Skipped without a local database. */
class SiteDiscordSettingsDatabaseTest {
    private static final long GUILD = Long.parseLong("9".repeat(17)) + 365;
    private static final long OTHER = GUILD + 1;
    private static final long USER = Long.parseLong("4".repeat(18));
    private static ConnectionSupplier supplier;
    private SiteDiscordSettings settings;

    @BeforeAll
    static void connect() throws Exception {
        supplier = LocalDatabase.supplier();
    }

    @BeforeEach
    void setUp() {
        settings = new SiteDiscordSettings(supplier);
    }

    @AfterEach
    void cleanUp() throws Exception {
        try (Connection c = supplier.getConnection()) {
            for (String sql : new String[]{"DELETE FROM younglings.site_discord_setting WHERE guild_id IN (?, ?)",
                    "DELETE FROM younglings.site_online_hidden WHERE guild_id IN (?, ?)"}) {
                try (PreparedStatement s = c.prepareStatement(sql)) {
                    s.setLong(1, GUILD);
                    s.setLong(2, OTHER);
                    s.executeUpdate();
                }
            }
        }
    }

    @Test
    void aMemberCanHideAndShowAgainInOneServerOnly() {
        assertFalse(settings.isHidden(GUILD, USER));

        settings.setHidden(GUILD, USER, true);
        settings.setHidden(GUILD, USER, true); // twice is fine

        assertTrue(settings.isHidden(GUILD, USER));
        assertEquals(Set.of(USER), settings.hiddenMembers(GUILD));
        assertTrue(settings.hiddenMembers(OTHER).isEmpty());

        settings.setHidden(GUILD, USER, false);
        assertFalse(settings.isHidden(GUILD, USER));
    }
}
