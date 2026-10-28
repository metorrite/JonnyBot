package com.younglings.bot.cachebus;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CacheDispatcherTest {
    private static final long GUILD = Long.parseLong("2".repeat(18));

    /** A cache that writes down what it was told to drop. */
    static class Recording implements CacheInvalidator {
        final String area;
        final List<Long> evicted = new CopyOnWriteArrayList<>();
        volatile int cleared;

        Recording(String area) {
            this.area = area;
        }

        @Override
        public String cacheArea() {
            return area;
        }

        @Override
        public void evictCached(long guildId) {
            evicted.add(guildId);
        }

        @Override
        public void evictAllCached() {
            cleared++;
        }
    }

    @Test
    void aSignalReachesEveryCacheOfItsAreaOnly() {
        Recording automod = new Recording("automod");
        Recording automodToo = new Recording("automod");
        Recording afk = new Recording("afk");
        CacheDispatcher dispatcher = new CacheDispatcher(List.of(automod, automodToo, afk));

        assertTrue(dispatcher.dispatch("automod:" + GUILD));
        assertEquals(List.of(GUILD), automod.evicted);
        assertEquals(List.of(GUILD), automodToo.evicted);
        assertEquals(List.of(), afk.evicted);
        assertEquals(List.of("afk", "automod"), dispatcher.areas());
    }

    @Test
    void unknownAreasAndJunkAreIgnored() {
        Recording automod = new Recording("automod");
        CacheDispatcher dispatcher = new CacheDispatcher(List.of(automod));
        assertFalse(dispatcher.dispatch("highlight:" + GUILD));
        assertFalse(dispatcher.dispatch("automod"));
        assertFalse(dispatcher.dispatch(null));
        assertEquals(List.of(), automod.evicted);
    }

    @Test
    void oneFailingCacheDoesNotStopTheOthers() {
        CacheInvalidator broken = new Recording("automod") {
            @Override
            public void evictCached(long guildId) {
                throw new IllegalStateException("broken");
            }

            @Override
            public void evictAllCached() {
                throw new IllegalStateException("broken");
            }
        };
        Recording fine = new Recording("automod");
        CacheDispatcher dispatcher = new CacheDispatcher(List.of(broken, fine));
        assertTrue(dispatcher.dispatch("automod:" + GUILD));
        assertEquals(List.of(GUILD), fine.evicted);
        dispatcher.evictAll();
        assertEquals(1, fine.cleared);
    }

    @Test
    void evictingTwiceIsHarmless() {
        Recording automod = new Recording("automod");
        CacheDispatcher dispatcher = new CacheDispatcher(List.of(automod));
        dispatcher.dispatch("automod:" + GUILD);
        dispatcher.dispatch("automod:" + GUILD);
        assertEquals(List.of(GUILD, GUILD), automod.evicted);
    }

    @Test
    void aCacheWithABadAreaIsRefusedAtStart() {
        assertThrows(IllegalArgumentException.class, () -> new CacheDispatcher(List.of(new Recording("Auto Mod"))));
    }
}
