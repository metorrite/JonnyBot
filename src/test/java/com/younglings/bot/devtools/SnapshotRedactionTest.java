package com.younglings.bot.devtools;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The mandatory one: seed every kind of secret the bot handles into every place a snapshot gets text from, build the zip, and prove
 * none of them is in it, neither in a file's text nor in a file's name.
 */
class SnapshotRedactionTest {
    private static final String B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_-";

    /** A made-up secret-shaped string generated at run time, so no secret-shaped literal sits in the source for a scanner to flag. */
    private static String fake(long seed, int... lengths) {
        java.util.Random random = new java.util.Random(seed);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < lengths.length; i++) {
            if (i > 0) out.append('.');
            for (int j = 0; j < lengths[i]; j++) out.append(B64.charAt(random.nextInt(B64.length())));
        }
        return out.toString();
    }

    // Secrets the bot is told about at startup (found through BotConfig#secretValues): masked wherever they appear.
    private static final String INTERNAL_SECRET = "internal-secret-" + fake(10, 12);
    private static final String DB_PASSWORD = "pw-" + fake(11, 14) + "!";
    private static final String DB_URL = "postgres" + "ql://jonnybot:" + DB_PASSWORD + "@db.internal:5432/railway";
    private static final String BOT_TOKEN = fake(1, 24, 6, 38);
    private static final String PLUGIN_TOKEN = "plugin-token-" + fake(12, 16);
    private static final String OAUTH_SECRET = "oauth/client+secret=" + fake(13, 12);

    // Secrets the bot is NOT told about: only their shape gives them away.
    private static final String UNTOLD_DISCORD_TOKEN = fake(2, 24, 6, 32);
    private static final String UNTOLD_JWT = "eyJ" + fake(3, 17) + "." + fake(4, 28) + "." + fake(5, 30);
    private static final String UNTOLD_DB_PASSWORD = "untold-" + fake(14, 12);
    private static final String UNTOLD_BEARER = "Bearer " + fake(15, 36);

    private static final List<String> TOLD = List.of(INTERNAL_SECRET, DB_PASSWORD, DB_URL, BOT_TOKEN, PLUGIN_TOKEN, OAUTH_SECRET);
    private static final List<String> MUST_NOT_APPEAR = List.of(INTERNAL_SECRET, DB_PASSWORD, BOT_TOKEN, PLUGIN_TOKEN, OAUTH_SECRET,
            URLEncoder.encode(OAUTH_SECRET, StandardCharsets.UTF_8), UNTOLD_DISCORD_TOKEN, UNTOLD_JWT, UNTOLD_DB_PASSWORD,
            UNTOLD_BEARER.substring(7), "jonnybot:" + DB_PASSWORD);

    private static Map<String, String> readZip(byte[] zip) throws Exception {
        Map<String, String> out = new LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) out.put(entry.getName(), new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        return out;
    }

    @Test
    void noSeededSecretSurvivesInTheZip() throws Exception {
        Map<String, String> files = new LinkedHashMap<>();
        // Each file stands in for one source of text in a real snapshot.
        files.put("manifest.json", "{\"requestedBy\": \"someone\", \"note\": \"started with " + INTERNAL_SECRET + "\"}");
        files.put("environment.json", "{\"DATABASE_URL\": \"" + DB_URL + "\", \"DISCORD_TOKEN\": \"" + BOT_TOKEN + "\"}");
        files.put("guild.json", "{\"clientSecret\": \"" + OAUTH_SECRET + "\", \"link\": \"https://x/cb?secret=" + URLEncoder.encode(OAUTH_SECRET, StandardCharsets.UTF_8) + "\"}");
        files.put("logs.txt", "2026-10-18T10:00:00Z ERROR c.y.Internal - request failed with header X-Internal-Secret: " + INTERNAL_SECRET
                + "\n2026-10-18T10:00:01Z WARN d.Jda - login with " + UNTOLD_DISCORD_TOKEN + " rejected"
                + "\n2026-10-18T10:00:02Z ERROR p.Db - could not connect to jdbc:postgresql://jonnybot:" + UNTOLD_DB_PASSWORD + "@10.0.0.4/db"
                + "\n2026-10-18T10:00:03Z WARN h.Http - sent " + UNTOLD_BEARER + " and " + UNTOLD_JWT
                + "\n2026-10-18T10:00:04Z WARN plugin - token=" + PLUGIN_TOKEN);
        files.put("db/ticket.json", "[{\"close_reason\": \"pasted my token " + BOT_TOKEN + " by mistake\", \"answers\": \"password: " + DB_PASSWORD + "\"}]");
        // A secret in a file NAME must not survive either.
        files.put("db/" + PLUGIN_TOKEN + ".json", "[]");

        byte[] zip = SnapshotWriter.pack(files, new SnapshotRedactor(TOLD), DevSnapshotService.MAX_BYTES);

        Map<String, String> unpacked = readZip(zip);
        assertEquals(files.size(), unpacked.size(), "every file is still there");
        StringBuilder everything = new StringBuilder();
        unpacked.forEach((name, text) -> everything.append(name).append('\n').append(text).append('\n'));
        for (String secret : MUST_NOT_APPEAR) {
            assertFalse(everything.toString().contains(secret), "leaked into the zip: " + secret);
        }
        assertTrue(everything.toString().contains(SnapshotRedactor.MASK), "the masks are visible so a reader knows something was hidden");
        // Non-secret context is kept.
        assertTrue(unpacked.get("logs.txt").contains("request failed with header"));
        assertTrue(unpacked.get("manifest.json").contains("someone"));
    }

    @Test
    void databaseColumnsNamedLikeSecretsAreNeverCopied() {
        for (String name : List.of("token", "token_hash", "client_secret", "password", "api_key", "private_key", "gif_token", "session_id", "key", "auth_cookie")) {
            assertTrue(SnapshotRedactor.isSecretName(name), name + " should be masked");
        }
        for (String name : List.of("guild_id", "created_at", "status", "author_id", "close_reason", "answers", "keyword", "number")) {
            assertFalse(SnapshotRedactor.isSecretName(name), name + " is ordinary data");
        }
    }

    @Test
    void shortValuesAreNotTreatedAsSecrets() {
        // A blank or tiny env value would otherwise wipe out common words everywhere in the snapshot.
        SnapshotRedactor redactor = new SnapshotRedactor(List.of("", "  ", "abc", "true"));
        assertEquals("the true story of abc", redactor.scrub("the true story of abc"));
    }

    @Test
    void longestSecretIsReplacedWholeWhenOneContainsAnother() {
        SnapshotRedactor redactor = new SnapshotRedactor(List.of("secretvalue", "secretvalue-and-more"));
        assertEquals("a [REDACTED] b", redactor.scrub("a secretvalue-and-more b"));
    }

    @Test
    void packRefusesAZipOverTheCap() {
        Map<String, String> files = new LinkedHashMap<>();
        StringBuilder noisy = new StringBuilder();
        java.util.Random random = new java.util.Random(7);
        for (int i = 0; i < 20_000; i++) noisy.append(Integer.toHexString(random.nextInt())).append(' ');
        files.put("big.txt", noisy.toString());
        assertThrows(SnapshotWriter.TooLargeException.class, () -> SnapshotWriter.pack(files, new SnapshotRedactor(List.of()), 1_000));
    }

    @Test
    void realSecretsFromTheEnvironmentAreCollectedAndMasked() throws Exception {
        // Whatever the machine running the tests has set that looks like a secret must come out masked, too.
        var secrets = new ArrayList<>(com.younglings.bot.config.BotConfig.getInstance().secretValues());
        files(secrets);
    }

    private void files(List<String> secrets) throws Exception {
        Map<String, String> files = new LinkedHashMap<>();
        for (int i = 0; i < secrets.size(); i++) files.put("f" + i + ".txt", "value=" + secrets.get(i) + " end");
        byte[] zip = SnapshotWriter.pack(files, new SnapshotRedactor(secrets), DevSnapshotService.MAX_BYTES);
        String all = String.join("\n", readZip(zip).values());
        for (String secret : secrets) {
            if (secret.strip().length() >= 6) assertFalse(all.contains(secret.strip()), "an environment secret leaked");
        }
    }
}
