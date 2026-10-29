package com.runeclans.bot.internal;

import com.runeclans.bot.runescape.PlayerLinkRepository;
import com.runeclans.bot.runescape.PlayerLinkRepository.StatsSnapshotRow;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * The latest stats of a batch of players by name (T-736, plan C-8): {@code GET player-stats?rsns=a,b,c} answers
 * {@code {players: [{rsn, snapshotAt, totalLevel, totalXp, combatLevel}]}}, one entry per name the bot has polled (names it never polled are
 * left out). It is what a community site shows next to its members, since a community is no bot server and its members' stats come from
 * the same poll tiers as everyone else's (the hub's polling list).
 * <p>
 * Names in, numbers out: it never lists a name it was not asked about, and it reads no Discord identity. The website asks it only for the
 * characters the hub lists as members of a community the signed-in Site Admin runs. At most {@value #MAX_NAMES} names; a name that isn't a
 * RuneScape name is skipped. Its own first path segment, so a player called "stats" still reaches {@code players/{rsn}}.
 */
@BService
public class DirectoryPlayerStatsRoutes implements DirectoryRouteModule {
    static final int MAX_NAMES = 100;

    private final Function<List<String>, Map<String, StatsSnapshotRow>> latest;

    public DirectoryPlayerStatsRoutes(PlayerLinkRepository links) {
        this.latest = links::getLatestSnapshots;
    }

    @Override
    public boolean route(DirectoryRequest request) throws IOException {
        if (!request.head().equals("player-stats") || request.parts().length != 1) return false;
        if (!request.requireGet()) return true;
        List<String> names = names(request.query("rsns"));
        if (names == null) {
            request.sendError(400, "Ask for 1 to " + MAX_NAMES + " names.");
            return true;
        }
        request.send(200, answer(names, latest));
        return true;
    }

    /** The asked names, cleaned and once each (first spelling), or null when there are none or too many. */
    static List<String> names(String raw) {
        if (raw == null) return null;
        Map<String, String> out = new LinkedHashMap<>();
        for (String part : raw.split(",")) {
            String rsn = DirectoryPlayerLogic.cleanRsn(part);
            if (rsn != null) out.putIfAbsent(rsn.toLowerCase(Locale.ROOT), rsn);
        }
        return out.isEmpty() || out.size() > MAX_NAMES ? null : List.copyOf(out.values());
    }

    static DataObject answer(List<String> names, Function<List<String>, Map<String, StatsSnapshotRow>> latest) {
        Map<String, StatsSnapshotRow> rows = latest.apply(names);
        List<DataObject> players = new ArrayList<>();
        for (String rsn : names) {
            StatsSnapshotRow row = rows.get(rsn.toLowerCase(Locale.ROOT));
            if (row == null) continue;
            players.add(DataObject.empty().put("rsn", rsn).put("snapshotAt", row.snapshotAt().toInstant().toString()).put("totalLevel", row.totalLevel())
                    .put("totalXp", row.totalXp()).put("combatLevel", row.combatLevel()));
        }
        return DataObject.empty().put("players", DataArray.fromCollection(players));
    }
}
