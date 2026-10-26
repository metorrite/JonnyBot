package com.younglings.bot.rsnlink.verify;

/**
 * The port to Jagex's public player data. The real one is {@link HttpPublicData}; tests stub it, so no test ever calls Jagex. Calls block, so
 * they are only made from the verifier's background worker, never while a plugin request waits.
 */
public interface PublicData {

    /** Which Hiscores table. Ironmen are on {@link #MAIN} too, but a skill outside the main top 2 million can still rank on their own table. */
    enum Board {
        MAIN("hiscore"), IRONMAN("hiscore_ironman"), HARDCORE("hiscore_hardcore_ironman");

        private final String path;

        Board(String path) {
            this.path = path;
        }

        /** The {@code m=} part of the address. */
        public String path() {
            return path;
        }
    }

    /** Hiscores lite for one player on one table. */
    Hiscores hiscores(Board board, String rsn);

    /** The RuneMetrics profile (exact XP in tenths when public). */
    RuneMetrics runeMetrics(String rsn);

    /** A Hiscores answer. */
    sealed interface Hiscores {
        /** Whole XP per skill id, {@link Skills#UNRANKED} where the skill isn't ranked. */
        record Found(long[] xp) implements Hiscores {}

        /** 404: the player isn't on this table at all (new, low, banned, hidden, or not this account type). Every skill counts as unranked. */
        record NotListed() implements Hiscores {}

        /** Couldn't tell (timeout, 5xx, rate limited, unreadable). Never treated as "not public". */
        record Failed(String why) implements Hiscores {}
    }

    /** A RuneMetrics answer. */
    sealed interface RuneMetrics {
        /** XP in tenths per skill id; RuneMetrics lists every skill, ranked or not. */
        record Public(long[] xp10) implements RuneMetrics {}

        /** {@code PROFILE_PRIVATE}: nothing is visible here, so nothing here can be "already public". */
        record Private() implements RuneMetrics {}

        /** {@code NO_PROFILE} or similar. */
        record NotFound() implements RuneMetrics {}

        /** Couldn't tell. Never treated as "not public". */
        record Failed(String why) implements RuneMetrics {}

        /** Not asked: RuneMetrics is retired or switched off ({@code RUNEMETRICS}). The Hiscores alone decide. */
        record Off() implements RuneMetrics {}
    }
}
