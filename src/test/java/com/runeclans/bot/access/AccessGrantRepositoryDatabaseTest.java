package com.runeclans.bot.access;

import com.runeclans.bot.testsupport.LocalDatabase;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The name stored with a user grant over real SQL: none at first, then what Discord said, on every grant of that person. Skipped without a local database. */
class AccessGrantRepositoryDatabaseTest {
    private static final long PERSON = 999_000_000_000_000_501L;
    private static ConnectionSupplier supplier;
    private AccessGrantRepository repository;

    @BeforeAll
    static void connect() throws Exception {
        supplier = LocalDatabase.supplier();
    }

    @BeforeEach
    void setUp() {
        repository = new AccessGrantRepository(supplier);
    }

    @AfterEach
    void cleanUp() throws Exception {
        try (Connection c = supplier.getConnection();
             PreparedStatement s = c.prepareStatement("DELETE FROM younglings.access_grant WHERE user_id = ?")) {
            s.setLong(1, PERSON);
            s.executeUpdate();
        }
    }

    private AccessGrant find(long id) {
        return repository.all().stream().filter(g -> g.id() == id).findFirst().orElseThrow();
    }

    @Test
    void aPersonsNameIsStoredOnAllOfTheirGrants() {
        long hub = repository.add(AccessArea.HUB, PERSON, null, null, "", 7L, null);
        long site = repository.add(AccessArea.JONNYBOT, PERSON, null, null, "", 7L, null);
        assertNull(find(hub).userName());

        repository.rememberUser(PERSON, "tester", "https://cdn.example/t.png");

        assertEquals("tester", find(hub).userName());
        assertEquals("https://cdn.example/t.png", find(site).avatarUrl());
        repository.rememberUser(PERSON, "renamed", null);
        assertEquals("renamed", find(site).userName());
        assertNull(find(hub).avatarUrl());
    }
}
