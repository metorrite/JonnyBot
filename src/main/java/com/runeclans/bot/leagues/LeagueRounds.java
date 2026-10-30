package com.runeclans.bot.leagues;

import com.runeclans.bot.events.EventRepository.Participant;
import com.runeclans.bot.events.EventScoring.Standing;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import net.dv8tion.jda.api.utils.data.DataType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * The pure rules of an event round (T-759, RuneClan docs/ALLIANCE-LEAGUE.md 5 "event" kind): which league round a bot event is attached to,
 * what each participating clan scored (its clan-vs-clan team's points, or how many of its players took part for the {@code participation}
 * metric), and the report the hub takes. No Discord, no database, so the tests run anywhere.
 */
public final class LeagueRounds {
    public static final String METRIC_PLACING = "placing";
    public static final String METRIC_PARTICIPATION = "participation";

    private LeagueRounds() {}

    /** A league round the server's clan hosts that a bot event is attached to, as {@code GET /v1/guilds/{guildId}/leagues} describes it. */
    public record Attached(String leagueId, String leagueName, int number, String kind, String metric, String status, String resultSource,
                           boolean isHost, boolean needsBot) {
        /** The round takes no result any more (final or void). */
        public boolean over() {
            return status.equals("final") || status.equals("void");
        }
    }

    /** One clan's result for the round: its server, its raw score, and how many of its players took part. */
    public record ClanResult(long guildId, long raw, int participants) {}

    /** The round whose {@code eventRef.eventId} is {@code eventId}, in any of the server's leagues; empty when the event is attached nowhere. */
    public static Optional<Attached> attached(DataObject guildLeagues, long eventId) {
        if (guildLeagues == null) return Optional.empty();
        String wanted = Long.toString(eventId);
        DataArray leagues = guildLeagues.optArray("leagues").orElse(DataArray.empty());
        for (int i = 0; i < leagues.length(); i++) {
            if (!leagues.isType(i, DataType.OBJECT)) continue;
            DataObject league = leagues.getObject(i);
            DataArray rounds = league.optArray("rounds").orElse(DataArray.empty());
            for (int j = 0; j < rounds.length(); j++) {
                if (!rounds.isType(j, DataType.OBJECT)) continue;
                DataObject round = rounds.getObject(j);
                DataObject ref = round.optObject("eventRef").orElse(null);
                if (ref == null || !wanted.equals(ref.getString("eventId", ""))) continue;
                return Optional.of(new Attached(league.getString("id", ""), league.getString("name", ""), round.getInt("number", 0), round.getString("kind", ""),
                        metricOf(round.optObject("config").orElse(DataObject.empty())), round.getString("status", ""),
                        round.isNull("resultSource") ? null : round.getString("resultSource", null), round.getBoolean("isHost", false), round.getBoolean("needsBot", false)));
            }
        }
        return Optional.empty();
    }

    /** The round's metric from its config: {@code participation} (players per clan) or {@code placing} (the event's clan standings, the default). */
    public static String metricOf(DataObject config) {
        String raw = config == null ? "" : config.getString("metric", "").strip().toLowerCase(Locale.ROOT);
        return raw.equals(METRIC_PARTICIPATION) ? METRIC_PARTICIPATION : METRIC_PLACING;
    }

    /**
     * Each participating clan's result. {@code teamsByGuild} is the clan-vs-clan settings' server → team map; {@code standings} the event's team
     * standings (subjects {@code t:<teamId>}); {@code participants} the event's roster (a waiting player doesn't count). A clan whose team scored
     * nothing is still in, with 0.
     */
    public static List<ClanResult> results(String metric, List<Standing> standings, Map<Long, Long> teamsByGuild, List<Participant> participants) {
        Map<String, Long> points = new HashMap<>();
        for (Standing s : standings) points.put(s.subject(), s.points());
        Map<Long, Integer> playersByTeam = new HashMap<>();
        for (Participant p : participants) {
            if (p.teamId() == null || p.waiting()) continue;
            playersByTeam.merge(p.teamId(), 1, Integer::sum);
        }
        List<ClanResult> out = new ArrayList<>();
        for (Map.Entry<Long, Long> e : teamsByGuild.entrySet()) {
            int players = playersByTeam.getOrDefault(e.getValue(), 0);
            long score = Math.max(0, points.getOrDefault("t:" + e.getValue(), 0L));
            out.add(new ClanResult(e.getKey(), METRIC_PARTICIPATION.equals(metric) ? players : score, players));
        }
        out.sort((a, b) -> Long.compare(a.guildId(), b.guildId()));
        return out;
    }

    /**
     * The {@code clans} array of the hub's results body, every clan named by its hub clan id ({@code clanIdOf}, from the server's site record).
     * Empty when a participating clan has no clan on the hub: the hub wants one entry per clan, so the organisers enter that round by hand.
     */
    public static Optional<DataArray> clansJson(List<ClanResult> results, Function<Long, Optional<String>> clanIdOf) {
        DataArray out = DataArray.empty();
        for (ClanResult r : results) {
            Optional<String> clanId = clanIdOf.apply(r.guildId());
            if (clanId.isEmpty()) return Optional.empty();
            out.add(DataObject.empty().put("clanId", clanId.get()).put("raw", r.raw()).put("participants", r.participants()));
        }
        return Optional.of(out);
    }
}
