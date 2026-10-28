package com.runeclans.bot.rsnlink.verify;

import com.runeclans.bot.rsnlink.LinkVerifier;
import com.runeclans.bot.rsnlink.Verification;
import com.runeclans.bot.rsnlink.verify.VerifyFakes.FakePublicData;
import com.runeclans.bot.rsnlink.verify.VerifyFakes.MutableClock;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Parsing Jagex's answers (from text, no network), the settings, the adapter onto the link service's hook, and the module's boundary. */
class VerifyPiecesTest {

    // ---------- Hiscores lite ----------

    private static String hiscores(Map<Integer, String> lines) {
        StringBuilder s = new StringBuilder("1234,2500,500000000\n");
        for (int id = 0; id < Skills.COUNT; id++) s.append(lines.getOrDefault(id, "-1,1,-1")).append('\n');
        s.append("-1,-1\n-1,-1\n"); // activity rows follow the skills
        return s.toString();
    }

    @Test
    void hiscoresLiteIsReadBySkillIdAndUnrankedIsMinusOne() {
        PublicData.Hiscores h = HttpPublicData.parseHiscores(hiscores(Map.of(10, "52011,99,13034431", 7, "100,120,200000000")));
        long[] xp = assertInstanceOf(PublicData.Hiscores.Found.class, h).xp();
        assertEquals(13_034_431L, xp[10]);
        assertEquals(200_000_000L, xp[7]);
        assertEquals(Skills.UNRANKED, xp[0]);
        assertEquals(Skills.UNRANKED, xp[28]);
    }

    @Test
    void anythingOddInHiscoresIsAFailureNeverUnranked() {
        assertInstanceOf(PublicData.Hiscores.Failed.class, HttpPublicData.parseHiscores("<html>busy</html>"));
        assertInstanceOf(PublicData.Hiscores.Failed.class, HttpPublicData.parseHiscores(null));
        assertInstanceOf(PublicData.Hiscores.Failed.class, HttpPublicData.parseHiscores(hiscores(Map.of(3, "1,2,lots"))));
        assertInstanceOf(PublicData.Hiscores.Failed.class, HttpPublicData.parseHiscores(hiscores(Map.of(3, "1,2"))));
    }

    @Test
    void hiscoresWithWindowsLineEndingsStillRead() {
        PublicData.Hiscores h = HttpPublicData.parseHiscores(hiscores(Map.of(10, "1,99,13034431")).replace("\n", "\r\n"));
        assertEquals(13_034_431L, assertInstanceOf(PublicData.Hiscores.Found.class, h).xp()[10]);
    }

    // ---------- RuneMetrics ----------

    @Test
    void runeMetricsSkillValuesAreTenths() {
        String json = "{\"name\":\"Zezima\",\"loggedIn\":\"false\",\"skillvalues\":[{\"level\":99,\"xp\":130344315,\"rank\":52011,\"id\":10},"
                + "{\"level\":1,\"xp\":0,\"id\":28}]}";
        long[] xp10 = assertInstanceOf(PublicData.RuneMetrics.Public.class, HttpPublicData.parseRuneMetrics(json)).xp10();
        assertEquals(130_344_315L, xp10[10]);
        assertEquals(0L, xp10[28]);
        assertEquals(Skills.UNRANKED, xp10[0], "a skill not listed counts as not shown");
    }

    @Test
    void runeMetricsErrorsMeanNothingIsShown() {
        assertInstanceOf(PublicData.RuneMetrics.Private.class, HttpPublicData.parseRuneMetrics("{\"error\":\"PROFILE_PRIVATE\",\"loggedIn\":\"false\"}"));
        assertInstanceOf(PublicData.RuneMetrics.NotFound.class, HttpPublicData.parseRuneMetrics("{\"error\":\"NO_PROFILE\",\"loggedIn\":\"false\"}"));
        assertInstanceOf(PublicData.RuneMetrics.NotFound.class, HttpPublicData.parseRuneMetrics("{\"error\":\"NOT_A_MEMBER\"}"));
    }

    @Test
    void unreadableRuneMetricsIsAFailure() {
        assertInstanceOf(PublicData.RuneMetrics.Failed.class, HttpPublicData.parseRuneMetrics("not json"));
        assertInstanceOf(PublicData.RuneMetrics.Failed.class, HttpPublicData.parseRuneMetrics("{\"name\":\"x\"}"));
    }

    @Test
    void switchedOffRuneMetricsIsNeverAsked() {
        assertInstanceOf(PublicData.RuneMetrics.Off.class, new HttpPublicData("test", () -> false).runeMetrics("Zezima"));
    }

    // ---------- settings ----------

    @Test
    void offUnlessSwitchedOnWithSaneDefaults() {
        VerifySettings unset = VerifySettings.fromEnv(k -> null, "ua");
        assertFalse(unset.enabled());
        assertEquals(2, unset.minMatches());
        assertEquals(100, unset.minGainXp());
        assertFalse(VerifySettings.fromEnv(Map.of("LINK_VERIFY_ENABLED", "yes")::get, "ua").enabled(), "only the word true switches it on");
        assertTrue(VerifySettings.fromEnv(Map.of("LINK_VERIFY_ENABLED", " TRUE ")::get, "ua").enabled());
    }

    @Test
    void numbersAreClampedAndJunkFallsBack() {
        VerifySettings s = VerifySettings.fromEnv(Map.of("LINK_VERIFY_MIN_MATCHES", "0", "LINK_VERIFY_CHECKS_PER_MINUTE", "100000",
                "LINK_VERIFY_MIN_GAIN_XP", "lots")::get, "ua");
        assertEquals(1, s.minMatches());
        assertEquals(600, s.checksPerMinute());
        assertEquals(100, s.minGainXp());
    }

    // ---------- the adapter onto the link service's hook ----------

    @Test
    void theAdapterQueuesReportsAndRaisesThroughTheService() {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-22T12:00:00Z"));
        FakePublicData data = new FakePublicData().main(Map.of(10, 1_000_000L, 7, 500_000L));
        List<Runnable> queued = new ArrayList<>();
        XpLinkVerifier adapter = new XpLinkVerifier(VerifyFakes.on(), new MemoryVerifyStore(), data, clock, queued::add, null);
        List<LinkVerifier.VerificationResult> raised = new ArrayList<>();
        adapter.attach((linkId, result) -> raised.add(result));
        String linkId = UUID.randomUUID().toString();

        Map<String, Object> report = new HashMap<>();
        report.put("kind", "xp");
        report.put("state", "game");
        report.put("xp10", Map.of(10, 10_012_345L, 7, 5_005_000L));
        LinkVerifier.LinkContext ctx = new LinkVerifier.LinkContext(linkId, "c", "zezima", "Zezima", true, Verification.IMPLICIT, report);
        assertTrue(adapter.check(ctx).isEmpty(), "it never answers on the spot");
        assertTrue(data.calls.isEmpty(), "nothing is read while the plugin's request waits");
        assertEquals(1, queued.size());
        queued.removeFirst().run();
        assertEquals(1, data.count("MAIN"));

        clock.advance(Duration.ofMinutes(1));
        Map<String, Object> lobby = new HashMap<>(report);
        lobby.put("state", "lobby");
        lobby.put("xp10", Map.of());
        adapter.check(new LinkVerifier.LinkContext(linkId, "c", "zezima", "Zezima", true, Verification.IMPLICIT, lobby));
        queued.removeFirst().run();
        data.main(Map.of(10, 1_001_234L, 7, 500_500L));
        clock.advance(Duration.ofMinutes(3));
        adapter.tick();
        assertEquals(1, raised.size());
        assertEquals(Verification.PUBLIC_DATA_CORROBORATED, raised.getFirst().level());
        assertEquals(XpLinkVerifier.METHOD, raised.getFirst().method());
        assertEquals("corroborated", adapter.describe(linkId).get("state"));
    }

    @Test
    void theAdapterOnlyWantsReportsForImplicitLinksAndOnlyWhenOn() {
        XpLinkVerifier on = new XpLinkVerifier(VerifyFakes.on(), new MemoryVerifyStore(), new FakePublicData(), fixedClock(), Runnable::run, null);
        assertTrue(on.wantsXpReports(Verification.IMPLICIT));
        assertFalse(on.wantsXpReports(Verification.PUBLIC_DATA_CORROBORATED));
        XpLinkVerifier off = new XpLinkVerifier(VerifySettings.off(), new MemoryVerifyStore(), new FakePublicData(), fixedClock(), Runnable::run, null);
        assertFalse(off.wantsXpReports(Verification.IMPLICIT));
        Map<String, Object> report = Map.of("kind", "xp", "xp10", Map.of(10, 10_012_345L));
        assertTrue(on.check(new LinkVerifier.LinkContext(UUID.randomUUID().toString(), "c", "z", "Z", true, Verification.PUBLIC_DATA_CORROBORATED, report)).isEmpty());
        assertTrue(off.check(new LinkVerifier.LinkContext(UUID.randomUUID().toString(), "c", "z", "Z", true, Verification.IMPLICIT, report)).isEmpty());
    }

    @Test
    void malformedXpMapsAreDropped() {
        Map<Object, Object> raw = new HashMap<>();
        raw.put(10, 5L);
        raw.put("7", 5L);
        raw.put(8, "five");
        assertEquals(Map.of(10, 5L), XpLinkVerifier.xpOf(raw));
        assertTrue(XpLinkVerifier.xpOf("nope").isEmpty());
    }

    private static MutableClock fixedClock() {
        return new MutableClock(Instant.parse("2026-10-22T12:00:00Z"));
    }

    // ---------- the boundary: this module moves to rsclans-hub unchanged ----------

    @Test
    void theModuleImportsOnlyTheJdkLoggingJsonAndTheLinkHook() throws IOException {
        List<String> allowed = List.of("import java.", "import org.slf4j.", "import net.dv8tion.jda.api.utils.data.", "import com.runeclans.bot.rsnlink.verify.",
                "import com.runeclans.bot.rsnlink.LinkVerifier", "import com.runeclans.bot.rsnlink.Verification;", "import static ");
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java/com/runeclans/bot/rsnlink/verify"))) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                for (String line : Files.readAllLines(f)) {
                    if (line.startsWith("import ") && allowed.stream().noneMatch(line::startsWith)) offenders.add(f.getFileName() + ": " + line);
                }
            }
        }
        assertEquals(List.of(), offenders);
    }

    @Test
    void skillHelpers() {
        assertEquals("Necromancy", Skills.name(28));
        assertEquals("Skill 29", Skills.name(29));
        assertEquals(1_001_234L, Skills.wholeXp(10_012_349L));
    }
}
