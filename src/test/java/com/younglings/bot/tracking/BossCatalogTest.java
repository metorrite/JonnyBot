package com.younglings.bot.tracking;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BossCatalogTest {
    /** What the classifier does with a kill line: the first boss whose name the text contains. */
    private static Optional<BossCatalog.Boss> bossOf(String text) {
        return BossCatalog.all().stream().filter(b -> text.contains(b.name())).findFirst();
    }

    @Test
    void everyBossHasAPictureInTheBotsResources() {
        List<String> keys = BossCatalog.keys();
        assertTrue(keys.size() >= 55, "the catalogued bosses and the extra named ones: " + keys.size());
        for (String key : keys) {
            assertNotNull(BossCatalogTest.class.getClassLoader().getResource("images/bosses/" + key + ".png"), key + " has no picture in images/bosses");
        }
    }

    @Test
    void theBossesFromTheClanActivityFeedAreRecognisedNotLeftToTheDefaultIcon() {
        assertEquals("kezalam", bossOf("I killed 2 Kezalam, the Wanderer.").orElseThrow().key());
        assertEquals("nakatra", bossOf("I killed Nakatra, Devourer Eternal.").orElseThrow().key());
        assertEquals("vermyx", bossOf("I killed Vermyx, Brood Mother.").orElseThrow().key());
        assertEquals("arch_glacor", bossOf("I killed 18 Arch-Glacors.").orElseThrow().key());
        assertEquals("yakamaru", bossOf("I killed Yakamaru.").orElseThrow().key());
    }

    @Test
    void theMostSpecificNameWins() {
        assertEquals("Telos, the Warden", bossOf("I killed Telos, the Warden.").orElseThrow().name());
        assertEquals("telos", bossOf("I killed Telos, the Warden.").orElseThrow().key());
        assertEquals("Telos", bossOf("I killed Telos.").orElseThrow().name());
        List<Integer> lengths = BossCatalog.all().stream().map(b -> b.name().length()).toList();
        assertEquals(lengths.stream().sorted(java.util.Comparator.reverseOrder()).toList(), lengths, "longest first");
    }

    @Test
    void bossesTheBotAlwaysKnewStillMatchAndKeepTheirKeys() {
        assertEquals("tztok_jad", bossOf("I killed TzTok-Jad.").orElseThrow().key());
        assertEquals("kreearra", bossOf("I killed Kree'arra.").orElseThrow().key());
        assertEquals("kril_tsutsaroth", bossOf("I killed K'ril Tsutsaroth.").orElseThrow().key());
        assertEquals("the_twin_furies", bossOf("I killed Nymora, the Vengeful.").orElseThrow().key());
        assertEquals("vindicta_gorvek", bossOf("I killed Vindicta.").orElseThrow().key());
    }

    @Test
    void aNonBossLineMatchesNothing() {
        assertTrue(bossOf("I found a Dragon claw.").isEmpty());
    }

    @Test
    void theFileIsReadAliasByAliasLongestFirst() {
        var bosses = BossCatalog.parse("""
                {"bosses":[{"key":"a","name":"A","aliases":["Al","Alpha"],"image":"/bosses/a.png"},{"key":"b","name":"B","aliases":["Bee"],"image":"/bosses/b.png"}]}
                """);
        assertEquals(List.of("Alpha", "Bee", "Al"), bosses.stream().map(BossCatalog.Boss::name).toList());
        assertEquals(List.of("a", "b", "a"), bosses.stream().map(BossCatalog.Boss::key).toList());
    }
}
