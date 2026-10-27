package com.younglings.bot;

import com.younglings.bot.testsupport.HeadlessStartup;
import com.younglings.bot.testsupport.TestDatabaseUrl;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the bot would start: boots the real BotCommands wiring (every {@code @BService}, every command and its parameter
 * resolvers) in a child JVM with {@link Bot#HEADLESS_PROPERTY} set, so Discord is never contacted and no token is needed. It catches
 * the crashes that otherwise only show on a launch: "No service or factories found", a missing {@code SlashParameterResolver}, an
 * interface service without {@code @InterfacedService}, a bad constructor, a SQL error in a repository's table setup.
 *
 * <p>It needs a Postgres to run the repositories' idempotent {@code CREATE TABLE IF NOT EXISTS}: CI provides a throwaway one
 * ({@code STARTUP_CHECK_DATABASE_URL}); on a developer machine the local dev database ({@code RUNECLAN_BOT_TEST_DATABASE_URL}, old name {@code JONNYBOT_TEST_DATABASE_URL}) is used
 * and the test is skipped when neither is set. It only ever connects to 127.0.0.1/localhost.
 *
 * <p>What it cannot cover: anything that needs a live gateway (command registration with Discord, listener behaviour). The listener
 * registration in {@code Bot.createJDA} is checked statically by {@link ListenerRegistrationTest}.
 */
class StartupWiringTest {
    @Test
    void theWholeWiringBootsWithoutDiscord() throws Exception {
        String url = System.getenv("STARTUP_CHECK_DATABASE_URL");
        if (url == null || url.isBlank()) url = TestDatabaseUrl.uriOrNull();
        if (url == null || url.isBlank()) {
            // Where the check is required (the GitHub workflow sets this) a missing database must fail loudly instead of silently
            // skipping the very check this test exists for.
            assertTrue(System.getenv("STARTUP_CHECK_REQUIRED") == null, "STARTUP_CHECK_REQUIRED is set but STARTUP_CHECK_DATABASE_URL is not");
            Assumptions.assumeTrue(false, "no local database configured (set RUNECLAN_BOT_TEST_DATABASE_URL)");
        }
        String host = URI.create(url.trim()).getHost();
        Assumptions.assumeTrue("127.0.0.1".equals(host) || "localhost".equals(host), "refusing a database that isn't local");

        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        List<String> command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-D" + Bot.HEADLESS_PROPERTY + "=true", "-cp", classpath, HeadlessStartup.class.getName()));
        ProcessBuilder pb = new ProcessBuilder(command).redirectErrorStream(true);
        // A clean environment: nothing from the developer's real run configuration (token, secrets) can leak in or be used.
        pb.environment().clear();
        for (String keep : new String[]{"SystemRoot", "PATH", "TEMP", "TMP", "HOME", "USERPROFILE", "APPDATA", "LOCALAPPDATA", "XDG_CACHE_HOME"}) {
            String v = System.getenv(keep);
            if (v != null) pb.environment().put(keep, v);
        }
        pb.environment().put("LIVE_ENV", "false");
        pb.environment().put("DISCORD_TOKEN", "headless-check-not-a-real-token");
        pb.environment().put("DATABASE_URL", url.trim());
        pb.directory(new File(System.getProperty("java.io.tmpdir")));

        Process process = pb.start();
        byte[] out = process.getInputStream().readAllBytes();
        boolean finished = process.waitFor(5, TimeUnit.MINUTES);
        String log = new String(out, StandardCharsets.UTF_8);
        if (!finished) process.destroyForcibly();
        assertTrue(finished, "the headless startup did not finish in 5 minutes:\n" + tail(log));
        assertEquals(0, process.exitValue(), "the bot's wiring failed to start:\n" + tail(log));
        assertTrue(log.contains("HEADLESS_STARTUP_OK"), "no success marker in the startup output:\n" + tail(log));
    }

    private static String tail(String log) {
        return log.length() <= 8000 ? log : "..." + log.substring(log.length() - 8000);
    }
}
