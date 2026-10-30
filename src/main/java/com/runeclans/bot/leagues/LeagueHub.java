package com.runeclans.bot.leagues;

import com.runeclans.bot.runeclans.HubWriteQueue;
import com.runeclans.bot.runeclans.RuneClansHubClient;
import io.github.freya022.botcommands.api.core.service.annotations.BConfiguration;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.time.Duration;
import java.util.Optional;
import java.util.function.Function;

/**
 * The bot's side of the hub's alliance league routes (T-759, RuneClan docs/ALLIANCE-LEAGUE.md 8 and 10; hub T-756 {@code LeagueAppRoutes}),
 * with the bot's own first-party key (scope {@code leagues:report}). Reads go through the hub client's cache under the {@code leagues} tag
 * (a {@code league.*} feed change empties them, and so does every write of this bot's own, so the next read is never the copy from before
 * it); writes go through its queue, so a hub that is briefly down gets them a little later. Off (the hub client off) asks nothing and
 * refuses every write.
 */
@BConfiguration
public class LeagueHub {
    static final Duration FRESH = Duration.ofSeconds(60);
    /** The cache tag of every league answer. */
    public static final String TAG = "leagues";
    static final int MAX_NOTE = 500;

    /** A hub write ({@link RuneClansHubClient#write}). */
    public interface Writer {
        HubWriteQueue.Result write(String method, String path, String body);
    }

    private final Function<String, RuneClansHubClient.Read> reader;
    private final Writer writer;
    private final Runnable afterWrite;

    /** The bot's instance. A factory, because the framework needs a service class to have exactly one constructor and tests use it. */
    @BService
    public static LeagueHub leagueHub(RuneClansHubClient client) {
        if (!client.enabled()) return off();
        return new LeagueHub(path -> client.read(path, FRESH, TAG), (method, path, body) -> client.write(method, path, body, null), () -> client.forget(TAG));
    }

    LeagueHub(Function<String, RuneClansHubClient.Read> reader, Writer writer, Runnable afterWrite) {
        this.reader = reader;
        this.writer = writer;
        this.afterWrite = afterWrite == null ? () -> { } : afterWrite;
    }

    /** The hub client is off: nothing is asked, every write is refused. */
    public static LeagueHub off() {
        return new LeagueHub(null, null, null);
    }

    /** A stub hub (for tests). */
    public static LeagueHub on(Function<String, RuneClansHubClient.Read> reader, Writer writer) {
        return new LeagueHub(reader, writer, null);
    }

    /** A stub hub whose {@code afterWrite} is watched (for tests). */
    public static LeagueHub on(Function<String, RuneClansHubClient.Read> reader, Writer writer, Runnable afterWrite) {
        return new LeagueHub(reader, writer, afterWrite);
    }

    public boolean enabled() {
        return reader != null && writer != null;
    }

    /**
     * {@code GET /v1/guilds/{guildId}/leagues}: the server's clan's leagues with their rounds (the hub resolves the server to its clan through
     * the server's site). Null when off or the hub can't be reached; a 404 answer when the server has no clan on the hub.
     */
    public RuneClansHubClient.Answer guildLeagues(long guildId) {
        if (!enabled()) return null;
        RuneClansHubClient.Read read = reader.apply("/v1/guilds/" + guildId + "/leagues");
        return read == null ? null : read.answer();
    }

    /**
     * {@code GET /v1/leagues/{id}}: the league's public page (clans, rounds with results, standings), which any key may read; null when off,
     * when the hub can't be reached, or when the league isn't one the bot may see.
     */
    public DataObject league(String leagueId) {
        if (!enabled() || leagueId == null || !leagueId.matches("[0-9a-fA-F-]{36}")) return null;
        RuneClansHubClient.Read read = reader.apply("/v1/leagues/" + leagueId);
        if (read == null || read.answer() == null || !read.answer().ok()) return null;
        return read.answer().json();
    }

    /** The hub clan behind a Discord server, from its site record ({@code GET /v1/guilds/{guildId}/site}); empty when there is none or the hub can't say. */
    public Optional<String> clanIdOf(long guildId) {
        if (!enabled()) return Optional.empty();
        RuneClansHubClient.Read read = reader.apply("/v1/guilds/" + guildId + "/site");
        if (read == null || read.answer() == null || !read.answer().ok() || read.answer().json() == null) return Optional.empty();
        DataObject json = read.answer().json();
        String id = json.hasKey("clanId") && !json.isNull("clanId") ? json.getString("clanId", "") : "";
        return id.isBlank() ? Optional.empty() : Optional.of(id);
    }

    /**
     * {@code POST /v1/leagues/{id}/rounds/{n}/event {guildId, eventId, name?, url?}}: attaches the bot event the host clan made for the round;
     * a null {@code eventId} detaches.
     */
    public HubWriteQueue.Result attach(String leagueId, int round, long guildId, Long eventId, String name, String url) {
        if (!enabled()) return new HubWriteQueue.Result(HubWriteQueue.Outcome.REFUSED, null);
        DataObject body = DataObject.empty().put("guildId", Long.toString(guildId)).put("eventId", eventId == null ? null : Long.toString(eventId));
        if (eventId != null && name != null && !name.isBlank()) body.put("name", name.strip());
        if (eventId != null && url != null && url.startsWith("https://")) body.put("url", url);
        return written(writer.write("POST", "/v1/leagues/" + leagueId + "/rounds/" + round + "/event", body.toString()));
    }

    /**
     * {@code POST /v1/leagues/{id}/rounds/{n}/results {guildId, clans: [{clanId, raw, participants?}], note?}}: the event's result for every
     * clan, which the hub puts on the table as proposed by the host clan ({@code resultSource: bot}).
     */
    public HubWriteQueue.Result report(String leagueId, int round, long guildId, DataArray clans, String note) {
        if (!enabled()) return new HubWriteQueue.Result(HubWriteQueue.Outcome.REFUSED, null);
        DataObject body = DataObject.empty().put("guildId", Long.toString(guildId)).put("clans", clans);
        if (note != null && !note.isBlank()) body.put("note", note.strip().length() > MAX_NOTE ? note.strip().substring(0, MAX_NOTE) : note.strip());
        return written(writer.write("POST", "/v1/leagues/" + leagueId + "/rounds/" + round + "/results", body.toString()));
    }

    /** A write that was sent or queued changes the server's leagues: the cached league answers go, so the next read asks the hub. */
    private HubWriteQueue.Result written(HubWriteQueue.Result result) {
        if (result != null && result.outcome() != HubWriteQueue.Outcome.REFUSED) afterWrite.run();
        return result;
    }

    /** The hub's message for a refused write, for the dashboard. */
    public static String refusal(HubWriteQueue.Result result, String fallback) {
        RuneClansHubClient.Answer a = result == null ? null : result.answer();
        if (a == null || a.json() == null) return fallback;
        String message = a.json().getString("message", "");
        if (!message.isBlank()) return message;
        String error = a.json().getString("error", "");
        return error.isBlank() ? fallback : error;
    }
}
