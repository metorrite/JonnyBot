package com.runeclans.bot.leagues;

import com.runeclans.bot.config.BotConfig;
import com.runeclans.bot.discord.Containers;
import com.runeclans.bot.i18n.Text;
import com.runeclans.bot.runeclans.RuneClansHubClient;
import com.runeclans.bot.util.BotScheduler;
import io.github.freya022.botcommands.api.commands.annotations.Command;
import io.github.freya022.botcommands.api.commands.application.slash.GuildSlashEvent;
import io.github.freya022.botcommands.api.commands.application.slash.annotations.JDASlashCommand;
import io.github.freya022.botcommands.api.commands.application.slash.annotations.TopLevelSlashCommandData;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * {@code /league standings} and {@code /league rounds} (T-760): the leagues this server's clan is in, read from the hub, answered to the
 * member alone. Anyone in the server may ask: the public page on the website shows the same. Names come from other clans, so they go
 * through {@link LeaguePostLogic#plain}.
 */
@Command
public class LeagueCommand {
    private static final Logger log = LoggerFactory.getLogger(LeagueCommand.class);
    static final int MAX_LEAGUES = 5;
    static final int MAX_ROWS = 10;

    private final LeagueHub hub;
    private final String sitesUrl;
    private final ScheduledExecutorService lane = BotScheduler.lane("leagues");

    public LeagueCommand(LeagueHub hub, BotConfig config) {
        this.hub = hub;
        this.sitesUrl = config.getClanSitesUrl();
    }

    @TopLevelSlashCommandData(description = "This clan's alliance leagues")
    @JDASlashCommand(name = "league", subcommand = "standings", description = "The standings of the leagues this clan is in")
    public void onStandings(GuildSlashEvent event) {
        run(event, true);
    }

    @JDASlashCommand(name = "league", subcommand = "rounds", description = "The rounds of the leagues this clan is in")
    public void onRounds(GuildSlashEvent event) {
        run(event, false);
    }

    private void run(GuildSlashEvent event, boolean standings) {
        long guildId = event.getGuild().getIdLong();
        event.deferReply(true).queue(hook -> lane.execute(() -> {
            Reply reply = reply(hub, guildId, standings, sitesUrl, (key, args) -> Text.of(event, key, args));
            hook.sendMessageComponents(List.of(Containers.toast(reply.accent(), reply.lines()))).useComponentsV2(true).setEphemeral(true).queue();
        }), err -> log.info("/league couldn't be acknowledged ({})", err.getClass().getSimpleName()));
    }

    /** What to tell the member: a colour and the lines. */
    record Reply(Color accent, String... lines) {}

    /** The answer, from the hub's reads. Pure apart from those, so tests can drive it. */
    static Reply reply(LeagueHub hub, long guildId, boolean standings, String sitesUrl, BiFunction<String, Object[], String> text) {
        if (!hub.enabled()) return new Reply(Containers.INFO, text.apply("league.off", new Object[0]));
        RuneClansHubClient.Answer answer = hub.guildLeagues(guildId);
        if (answer == null || answer.status() >= 500 || answer.status() == 429) return new Reply(Containers.WARNING, text.apply("league.try_again", new Object[0]));
        if (answer.status() == 404 || answer.json() == null) return new Reply(Containers.INFO, text.apply("league.no_clan", new Object[0]));
        if (!answer.ok()) return new Reply(Containers.WARNING, text.apply("league.try_again", new Object[0]));
        DataArray leagues = answer.json().optArray("leagues").orElse(DataArray.empty());
        if (leagues.isEmpty()) return new Reply(Containers.INFO, text.apply("league.none", new Object[0]));
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < Math.min(leagues.length(), MAX_LEAGUES); i++) {
            DataObject league = leagues.getObject(i);
            String id = league.getString("id", "");
            String name = LeaguePostLogic.plain(league.getString("name", "?"));
            String status = league.getString("status", "");
            lines.add(text.apply("league.heading", new Object[] {name, status}));
            if (standings) lines.addAll(standingLines(hub.league(id), text));
            else lines.addAll(roundLines(league.optArray("rounds").orElse(DataArray.empty()), text));
            if (sitesUrl != null && !sitesUrl.isBlank()) lines.add(text.apply("league.page", new Object[] {sitesUrl + "/clans/leagues/" + id}));
        }
        if (leagues.length() > MAX_LEAGUES) lines.add(text.apply("league.more", new Object[] {leagues.length() - MAX_LEAGUES}));
        return new Reply(Containers.INFO, lines.toArray(String[]::new));
    }

    /** "1. Alpha: 7 pts" per clan from the public page's standings ({@code rank}, as the hub's standings carry it), or a line saying the page couldn't be read. */
    static List<String> standingLines(DataObject page, BiFunction<String, Object[], String> text) {
        if (page == null) return List.of(text.apply("league.standings_unavailable", new Object[0]));
        DataArray standings = page.optArray("standings").orElse(DataArray.empty());
        if (standings.isEmpty()) return List.of(text.apply("league.standings_none", new Object[0]));
        List<String> out = new ArrayList<>();
        for (int i = 0; i < Math.min(standings.length(), MAX_ROWS); i++) {
            DataObject s = standings.getObject(i);
            out.add(text.apply("league.standing", new Object[] {s.getInt("rank", s.getInt("placing", i + 1)), LeaguePostLogic.plain(s.getString("name", "?")), trim(s.getDouble("points", 0))}));
        }
        return out;
    }

    /** "Round 2 · Boss night · running" per round. */
    static List<String> roundLines(DataArray rounds, BiFunction<String, Object[], String> text) {
        if (rounds.isEmpty()) return List.of(text.apply("league.rounds_none", new Object[0]));
        List<String> out = new ArrayList<>();
        for (int i = 0; i < Math.min(rounds.length(), MAX_ROWS); i++) {
            DataObject r = rounds.getObject(i);
            out.add(text.apply("league.round", new Object[] {r.getInt("number", i + 1), LeaguePostLogic.plain(r.getString("name", "?")), r.getString("status", "")}));
        }
        return out;
    }

    /** 7.0 → "7", 7.5 → "7.5". */
    static String trim(double value) {
        return value == Math.rint(value) ? Long.toString((long) value) : Double.toString(value);
    }

    /** For tests: a reader of the hub's public page. */
    interface Page extends Function<String, DataObject> {}
}
