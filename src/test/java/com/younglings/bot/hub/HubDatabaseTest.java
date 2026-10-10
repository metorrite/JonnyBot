package com.younglings.bot.hub;

import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mockito.Mockito;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the Hub SQL against a real Postgres. Skipped unless {@code JONNYBOT_TEST_DB_URL} points at an <em>empty scratch</em> database
 * it may fill and wipe: never point it at a database that holds real data.
 */
@EnabledIfEnvironmentVariable(named = "JONNYBOT_TEST_DB_URL", matches = ".+")
class HubDatabaseTest {
    private static HubRepository repository;

    @BeforeAll
    static void connect() throws SQLException {
        String url = System.getenv("JONNYBOT_TEST_DB_URL");
        ConnectionSupplier connections = Mockito.mock(ConnectionSupplier.class);
        Mockito.when(connections.getConnection()).thenAnswer(call -> DriverManager.getConnection(url));
        repository = new HubRepository(connections, new HubDatabaseInitializer(connections));
    }

    @BeforeEach
    void empty() throws SQLException {
        clean();
    }

    @AfterAll
    static void wipe() throws SQLException {
        clean();
    }

    private static void clean() throws SQLException {
        try (Connection c = DriverManager.getConnection(System.getenv("JONNYBOT_TEST_DB_URL")); Statement s = c.createStatement()) {
            s.execute("TRUNCATE younglings.hub_command_setting");
        }
    }

    @Test
    void aServerWithNothingSavedHasNoRows() {
        assertTrue(repository.all(1L).isEmpty());
    }

    @Test
    void settingsRoundTripIncludingTheirListsAndExtras() {
        var saved = new HubSettings(1L, "signup", false, true, List.of("group:cweb", "role:9"), List.of(60L, 61L), "{\"lockAdminChannel\":true}");

        repository.save(saved);

        assertEquals(saved, repository.all(1L).get("signup"));
    }

    @Test
    void savingAgainReplacesTheRowAndOneServersRowsAreNeverAnothers() {
        repository.save(new HubSettings(1L, "poll", true, false, List.of(), List.of(), "{}"));
        repository.save(new HubSettings(1L, "poll", false, true, List.of("role:3"), List.of(70L), "{}"));
        repository.save(new HubSettings(2L, "poll", true, false, List.of(), List.of(), "{}"));

        assertEquals(1, repository.all(1L).size());
        assertEquals(List.of("role:3"), repository.all(1L).get("poll").allowedRefs());
        assertTrue(repository.all(2L).get("poll").enabled());
    }

    @Test
    void emptyListsComeBackEmptyNotNull() {
        repository.save(HubSettings.defaults(1L, "ca"));

        HubSettings loaded = repository.all(1L).get("ca");

        assertTrue(loaded.allowedRefs().isEmpty());
        assertTrue(loaded.channelIds().isEmpty());
    }
}
