package com.younglings.bot.tracking;

import java.util.List;

/**
 * Named bosses the Boss Kills group recognizes a kill/defeat for — matched against an activity's raw
 * text by substring, same approach as {@link DropItemCatalog}, and checked in list order (first match
 * wins) by {@link TrackingEventClassifier}. {@code key} is this boss's icon lookup key (see
 * {@link TrackingIconCatalog#mentionForBoss}) and the file name {@code IconDownloader} saves its icon
 * under; {@code name} is both the substring matched against and the name shown in the posted message,
 * so it must stay exactly what RuneMetrics' own text uses.
 */
public final class BossCatalog {
    public record Boss(String key, String name) {}

    private static final List<Boss> BOSSES = List.of(
            new Boss("tztok_jad", "TzTok-Jad"),
            new Boss("tzkal_zuk", "TzKal-Zuk"),
            new Boss("telos", "Telos"),
            new Boss("vorago", "Vorago"),
            new Boss("nex", "Nex"),
            new Boss("kalphite_king", "Kalphite King"),
            new Boss("kalphite_queen", "Kalphite Queen"),
            new Boss("queen_black_dragon", "Queen Black Dragon"),
            new Boss("corporeal_beast", "Corporeal Beast"),
            new Boss("general_graardor", "General Graardor"),
            new Boss("kreearra", "Kree'arra"),
            new Boss("kril_tsutsaroth", "K'ril Tsutsaroth"),
            new Boss("commander_zilyana", "Commander Zilyana"),
            new Boss("vindicta", "Vindicta"),
            new Boss("helwyr", "Helwyr"),
            new Boss("gregorovic", "Gregorovic"),
            new Boss("har_aken", "Har-Aken"),
            new Boss("the_magister", "The Magister"),
            new Boss("araxxi", "Araxxi"),
            new Boss("nymora", "Nymora"),
            new Boss("avaryss", "Avaryss"),
            new Boss("arch_glacor", "Arch-Glacor"),
            // Checked after "Telos" above, which already matches any text this would too — kept for
            // the record rather than removed, but effectively unreachable as-is.
            new Boss("telos_the_warden", "Telos, the Warden")
    );

    private BossCatalog() {}

    public static List<Boss> all() {
        return BOSSES;
    }
}
