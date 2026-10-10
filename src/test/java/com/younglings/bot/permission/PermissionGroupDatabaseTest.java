package com.younglings.bot.permission;

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
 * Runs the permission group SQL against a real Postgres. Skipped unless {@code JONNYBOT_TEST_DB_URL} points at an <em>empty
 * scratch</em> database it may fill and wipe: never point it at a database that holds real data.
 */
@EnabledIfEnvironmentVariable(named = "JONNYBOT_TEST_DB_URL", matches = ".+")
class PermissionGroupDatabaseTest {
    private static final long GUILD = 1L;
    private static final long OTHER_GUILD = 2L;

    private static PermissionGroupRepository repository;

    @BeforeAll
    static void connect() throws SQLException {
        String url = System.getenv("JONNYBOT_TEST_DB_URL");
        ConnectionSupplier connections = Mockito.mock(ConnectionSupplier.class);
        Mockito.when(connections.getConnection()).thenAnswer(call -> DriverManager.getConnection(url));
        repository = new PermissionGroupRepository(connections, new PermissionDatabaseInitializer(connections));
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
            s.execute("TRUNCATE younglings.permission_group RESTART IDENTITY CASCADE");
        }
    }

    private static PermissionGroup builtin(String key, boolean includeHigher, Long... roles) {
        return new PermissionGroup(0, GUILD, key, key, true, includeHigher, List.of(roles));
    }

    private static PermissionGroup custom(String key, String name, Long... roles) {
        return new PermissionGroup(0, GUILD, key, name, false, false, List.of(roles));
    }

    @Test
    void createMissingMakesTheBuiltInsOnceAndNeverOverwritesOnesThatExist() {
        repository.createMissing(GUILD, List.of(builtin("admin", true, 100L), builtin("support", false), builtin("developer", false)));
        repository.createMissing(GUILD, List.of(builtin("admin", true, 999L)));

        List<PermissionGroup> groups = repository.list(GUILD);
        assertEquals(List.of("admin", "support", "developer"), groups.stream().map(PermissionGroup::key).toList());
        assertEquals(List.of(100L), groups.getFirst().roleIds(), "the second call did not touch the existing group");
        assertTrue(groups.getFirst().includeHigher());
    }

    @Test
    void replaceAllCreatesUpdatesAndDeletesTheServersOwnGroupsButNeverTheBuiltIns() {
        repository.createMissing(GUILD, List.of(builtin("admin", true, 100L), builtin("support", false), builtin("developer", false)));
        repository.replaceAll(GUILD, List.of(builtin("admin", true, 100L), builtin("support", false), builtin("developer", false),
                custom("cweb", "Web Dev", 300L, 301L), custom("cdisc", "Discord Dev", 400L)));
        assertEquals(5, repository.list(GUILD).size());

        // rename one, change another's roles, drop the third
        repository.replaceAll(GUILD, List.of(builtin("admin", true, 100L, 101L), builtin("support", false), builtin("developer", false),
                custom("cweb", "Website Dev", 300L)));

        List<PermissionGroup> groups = repository.list(GUILD);
        assertEquals(List.of("admin", "support", "developer", "cweb"), groups.stream().map(PermissionGroup::key).toList());
        assertEquals(List.of(100L, 101L), groups.get(0).roleIds());
        assertEquals("Website Dev", groups.get(3).name());
        assertEquals(List.of(300L), groups.get(3).roleIds());

        repository.replaceAll(GUILD, List.of(builtin("admin", true), builtin("support", false), builtin("developer", false)));
        assertEquals(3, repository.list(GUILD).size(), "an empty save deletes the custom groups and keeps the built-ins");
    }

    @Test
    void oneServersGroupsAreNeverAnotherServers() {
        repository.createMissing(GUILD, List.of(builtin("admin", true, 100L)));
        repository.createMissing(OTHER_GUILD, List.of(new PermissionGroup(0, OTHER_GUILD, "admin", "admin", true, true, List.of(200L))));
        repository.replaceAll(OTHER_GUILD, List.of(new PermissionGroup(0, OTHER_GUILD, "admin", "admin", true, true, List.of())));

        assertEquals(List.of(100L), repository.list(GUILD).getFirst().roleIds());
        assertEquals(List.of(), repository.list(OTHER_GUILD).getFirst().roleIds());
    }

    @Test
    void twoGroupsInOneServerCannotShareAName() {
        repository.createMissing(GUILD, List.of(builtin("admin", true)));
        boolean refused = false;
        try {
            repository.replaceAll(GUILD, List.of(builtin("admin", true), custom("c1", "Web", 1L), custom("c2", "web", 2L)));
        } catch (RuntimeException e) {
            refused = true;
        }

        assertTrue(refused);
        assertEquals(1, repository.list(GUILD).size(), "the failed save rolled back completely");
    }
}
