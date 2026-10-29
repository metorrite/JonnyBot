package com.runeclans.bot.clansite;

import net.dv8tion.jda.api.utils.data.DataObject;

import java.util.function.Predicate;

/** Test access to {@link ClanSitePluginFeed#raceOf} from other packages. */
public final class ClanSitePluginFeedRaceAccess {
    private ClanSitePluginFeedRaceAccess() {}

    public static DataObject raceOf(DataObject answer, Predicate<String> shown) {
        return ClanSitePluginFeed.raceOf(answer, shown);
    }
}
