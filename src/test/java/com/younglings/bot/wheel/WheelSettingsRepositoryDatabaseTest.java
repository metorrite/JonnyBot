package com.younglings.bot.wheel;

import com.younglings.bot.testsupport.LocalDatabase;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Runs the repository's SQL against the developer's local Postgres (skipped where there isn't one). */
class WheelSettingsRepositoryDatabaseTest {
    private static final long GUILD = 987654321012345L;
    private ConnectionSupplier db;
    private WheelSettingsRepository repository;

    @BeforeEach
    void setUp() throws Exception {
        db = LocalDatabase.supplier();
        repository = new WheelSettingsRepository(db);
        clean();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (db != null) clean();
    }

    private void clean() throws Exception {
        try (Connection c = db.getConnection(); PreparedStatement s = c.prepareStatement("DELETE FROM younglings.citadel_wheel_settings WHERE guild_id = ?")) {
            s.setLong(1, GUILD);
            s.executeUpdate();
        }
    }

    @Test
    void aServerThatNeverSavedGetsTheDefaults() {
        WheelSettingsRepository.Settings settings = repository.get(GUILD);
        assertEquals(false, settings.autoPost());
        assertNull(settings.channelId());
        assertEquals(WheelMessage.DEFAULT, settings.message());
    }

    @Test
    void savedSettingsComeBackAndASecondSaveReplacesThem() {
        repository.save(GUILD, new WheelSettingsRepository.Settings(true, 42L, "Hi {winner}"));
        assertEquals(new WheelSettingsRepository.Settings(true, 42L, "Hi {winner}"), repository.get(GUILD));

        repository.save(GUILD, new WheelSettingsRepository.Settings(false, null, "Bye {mention}"));
        assertEquals(new WheelSettingsRepository.Settings(false, null, "Bye {mention}"), repository.get(GUILD));
    }
}
