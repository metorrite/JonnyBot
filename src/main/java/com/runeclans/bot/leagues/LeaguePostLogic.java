package com.runeclans.bot.leagues;

import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.util.Optional;
import java.util.function.BiFunction;

/**
 * What the bot posts about a league from the hub's change feed (T-760, RuneClan docs/ALLIANCE-LEAGUE.md 10): which feed entries become a
 * post, the post's words, and the key that lets a newer entry about the same thing replace one still waiting. Pure, so tests can drive it.
 * The feed's entry is {@code {item, id, rev, origin, actor, data}} with {@code data} the hub's payload: {@code leagueId, allianceId, name}
 * plus the change's own keys ({@code event, status, number, version, clanId, note, how}).
 */
public final class LeaguePostLogic {
    private LeaguePostLogic() {}

    /** One post to make: for the league, with the dedupe key and the message key with its arguments beyond the league's name. */
    public record Plan(String leagueId, String leagueName, String dedupe, String key, Object[] args) {}

    /**
     * The post for a feed entry, or empty for an entry nobody needs to hear about (a round attached, a count run, a corrected result...).
     * {@code kind} is the feed kind ({@code league.changed}, {@code league.terms}, {@code league.round}, {@code league.results}).
     */
    public static Optional<Plan> plan(String kind, DataObject entry) {
        if (kind == null || !kind.startsWith("league.") || entry == null) return Optional.empty();
        DataObject data = entry.optObject("data").orElse(null);
        if (data == null) return Optional.empty();
        String leagueId = data.getString("leagueId", "");
        if (!leagueId.matches("[0-9a-fA-F-]{36}")) return Optional.empty();
        String name = plain(data.getString("name", ""));
        if (name.isBlank()) name = "the league";
        String event = data.getString("event", "");
        int number = data.getInt("number", 0);
        int version = data.getInt("version", 0);
        String stem = "league-post:" + leagueId + ":" + kind + ":" + event;
        switch (kind) {
            case "league.changed" -> {
                return switch (event) {
                    case "proposed" -> Optional.of(new Plan(leagueId, name, stem, "league.post.proposed", new Object[] {name}));
                    case "running" -> Optional.of(new Plan(leagueId, name, stem, "league.post.running", new Object[] {name}));
                    case "finished" -> Optional.of(new Plan(leagueId, name, stem, "league.post.finished", new Object[] {name}));
                    case "cancelled" -> Optional.of(new Plan(leagueId, name, stem, "league.post.cancelled", new Object[] {name}));
                    default -> Optional.empty();
                };
            }
            case "league.terms" -> {
                return switch (event) {
                    case "changed" -> Optional.of(new Plan(leagueId, name, stem + ":" + version, "league.post.terms_changed", new Object[] {version, name}));
                    case "agreed" -> Optional.of(new Plan(leagueId, name, stem + ":" + version, "league.post.terms_agreed", new Object[] {name}));
                    case "declined" -> Optional.of(new Plan(leagueId, name, stem + ":" + version, "league.post.terms_declined", new Object[] {name}));
                    default -> Optional.empty();
                };
            }
            case "league.round" -> {
                if (!event.equals("started") || number < 1) return Optional.empty();
                return Optional.of(new Plan(leagueId, name, stem + ":" + number, "league.post.round_started", new Object[] {number, name}));
            }
            case "league.results" -> {
                if (number < 1) return Optional.empty();
                return switch (event) {
                    case "proposed" -> Optional.of(new Plan(leagueId, name, stem + ":" + number, "league.post.result_proposed", new Object[] {number, name}));
                    case "disputed" -> Optional.of(new Plan(leagueId, name, stem + ":" + number, "league.post.result_disputed", new Object[] {number, name}));
                    case "final" -> Optional.of(new Plan(leagueId, name, stem + ":" + number, "league.post.round_final", new Object[] {number, name}));
                    default -> Optional.empty();
                };
            }
            default -> {
                return Optional.empty();
            }
        }
    }

    /** Every character Discord reads as markdown in a message; each gets a backslash, paired or not. */
    private static final String MARKDOWN = "\\*_~`|[]";

    /**
     * A name from another clan as Discord may show it: every markdown character escaped (bold, italics, spoilers, code, the brackets of a
     * masked link; paired or alone, so a lone {@code **} can't join the template's own), pings defanged, line breaks folded into one line (no
     * line of a post starts with a heading or a list the name brought). A league, clan or round called
     * {@code [Claim your prize](https://evil.example)} or {@code **x**} is shown as typed. The hub keeps names to plain words, and this
     * holds here whatever the hub lets through.
     */
    public static String plain(String name) {
        if (name == null) return "";
        String oneLine = name.replaceAll("\\s+", " ").strip();
        StringBuilder out = new StringBuilder(oneLine.length() + 8);
        for (int i = 0; i < oneLine.length(); i++) {
            char c = oneLine.charAt(i);
            if (MARKDOWN.indexOf(c) >= 0) out.append('\\');
            out.append(c);
            if (c == '@') out.append('\u200B');
        }
        return out.toString();
    }

    /** Whether the hub's answer for a server ({@code GET /v1/guilds/{g}/leagues}) lists this league: only then is the post for that server. */
    public static boolean concerns(DataObject guildLeagues, String leagueId) {
        if (guildLeagues == null) return false;
        DataArray leagues = guildLeagues.optArray("leagues").orElse(DataArray.empty());
        for (int i = 0; i < leagues.length(); i++) {
            if (leagueId.equalsIgnoreCase(leagues.getObject(i).getString("id", ""))) return true;
        }
        return false;
    }

    /** The post's text: the message plus the league's page on the website (the public page for most, the manager for terms to answer). */
    public static String text(Plan plan, String sitesUrl, BiFunction<String, Object[], String> text) {
        String line = text.apply(plan.key(), plan.args());
        if (sitesUrl == null || sitesUrl.isBlank()) return line;
        boolean manager = plan.key().equals("league.post.proposed") || plan.key().equals("league.post.terms_changed");
        String page = sitesUrl + (manager ? "/account/clan/leagues/" : "/clans/leagues/") + plan.leagueId();
        return line + "\n" + text.apply(manager ? "league.post.answer_link" : "league.post.page_link", new Object[] {page});
    }
}
