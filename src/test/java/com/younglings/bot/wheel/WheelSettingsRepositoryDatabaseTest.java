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

    // ---------- the Citadel prize defaults (#449) ----------

    @Test
    void thePrizeDefaultsAreOffAndEmptyForAServerThatNeverSavedAnything() {
        assertEquals(WheelSettingsRepository.PrizeDefaults.none(), repository.getPrizeDefaults(GUILD));
        assertEquals(false, repository.getPrizeDefaults(GUILD).autoRecord());
    }

    @Test
    void savingThePrizeDefaultsFirstGivesTheServerTheDefaultAnnouncement() {
        repository.savePrizeDefaults(GUILD, new WheelSettingsRepository.PrizeDefaults(true, 100_000_000L, "Weekly cap prize"));
        assertEquals(new WheelSettingsRepository.PrizeDefaults(true, 100_000_000L, "Weekly cap prize"), repository.getPrizeDefaults(GUILD));
        WheelSettingsRepository.Settings announcement = repository.get(GUILD);
        assertEquals(false, announcement.autoPost(), "saving a prize default never turns auto posting on");
        assertNull(announcement.channelId());
        assertEquals(WheelMessage.DEFAULT, announcement.message());
        assertEquals(false, announcement.video());
    }

    @Test
    void thePrizeDefaultsAndTheAnnouncementSettingsNeverOverwriteEachOther() {
        repository.save(GUILD, new WheelSettingsRepository.Settings(true, 42L, "Hi {winner}", true));
        repository.savePrizeDefaults(GUILD, new WheelSettingsRepository.PrizeDefaults(true, 5_000_000L, "Prize"));
        assertEquals(new WheelSettingsRepository.Settings(true, 42L, "Hi {winner}", true), repository.get(GUILD), "the announcement is kept");

        repository.save(GUILD, new WheelSettingsRepository.Settings(false, null, "Bye {mention}"));
        assertEquals(new WheelSettingsRepository.PrizeDefaults(true, 5_000_000L, "Prize"), repository.getPrizeDefaults(GUILD), "the prize defaults are kept");
    }

    @Test
    void anAmountAndNoteCanBeClearedAgain() {
        repository.savePrizeDefaults(GUILD, new WheelSettingsRepository.PrizeDefaults(true, 5_000_000L, "Prize"));
        repository.savePrizeDefaults(GUILD, new WheelSettingsRepository.PrizeDefaults(false, null, null));
        assertEquals(WheelSettingsRepository.PrizeDefaults.none(), repository.getPrizeDefaults(GUILD));
    }
}
