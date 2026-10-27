package com.younglings.bot.testsupport;

import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import org.junit.jupiter.api.Assumptions;
import org.mockito.Mockito;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A connection to the developer's own local Postgres, for tests that need real SQL. The address is read from the {@code RUNECLAN_BOT_TEST_DATABASE_URL}
 * environment variable (old name {@code JONNYBOT_TEST_DATABASE_URL}), or failing that from the DATABASE_URL in the IDE's run configuration ({@code .idea/workspace.xml}). It only ever connects to
 * 127.0.0.1 or localhost, and a test that can't find one is skipped rather than failed, so a build machine (which has neither) just skips them.
 * Tests using it must clean up after themselves and use made-up server ids.
 */
public final class LocalDatabase {
    private LocalDatabase() {}

    /** A supplier for the local database, or the calling test is skipped. */
    public static ConnectionSupplier supplier() throws Exception {
        String raw = TestDatabaseUrl.uriOrNull();
        if (raw == null || raw.isBlank()) {
            Path workspace = Path.of(".idea/workspace.xml");
            Assumptions.assumeTrue(Files.exists(workspace), "no local database configured");
            Matcher m = Pattern.compile("name=\"DATABASE_URL\" value=\"([^\"]+)\"").matcher(Files.readString(workspace));
            Assumptions.assumeTrue(m.find(), "no DATABASE_URL in the IDE run configuration");
            raw = m.group(1);
        }
        URI uri = URI.create(raw);
        Assumptions.assumeTrue("127.0.0.1".equals(uri.getHost()) || "localhost".equals(uri.getHost()), "refusing a database that isn't local");
        Assumptions.assumeTrue(uri.getUserInfo() != null, "the database address has no user");
        String[] user = uri.getUserInfo().split(":", 2);
        String url = "jdbc:postgresql://" + uri.getHost() + ":" + uri.getPort() + uri.getPath();
        ConnectionSupplier supplier = Mockito.mock(ConnectionSupplier.class);
        Mockito.when(supplier.getConnection()).thenAnswer(i -> DriverManager.getConnection(url, user.length > 1 ? user[0] : user[0], user.length > 1 ? user[1] : ""));
        try (Connection c = supplier.getConnection()) {
            Assumptions.assumeTrue(c.isValid(2), "the local database didn't answer");
        } catch (Exception e) {
            Assumptions.assumeTrue(false, "the local database isn't running: " + e.getMessage());
        }
        return supplier;
    }
}
