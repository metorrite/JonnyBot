package com.younglings.bot.discord.jobs;

import net.dv8tion.jda.api.utils.data.DataObject;

import java.time.Instant;
import java.util.Locale;
import java.util.Optional;

/**
 * One Discord-bound change the website asked for (T-456), kept in {@code younglings.discord_job} until it is done: change a nickname, swap a
 * colour role, give a self-role, post a message. The website gets the job's id at once and asks for its state; the bot does the Discord part
 * in the background, waiting out rate limits instead of making a page wait.
 *
 * @param guildId  the server it is about (a job is only ever shown to that server)
 * @param actorId  who asked (a job is only ever shown to them)
 * @param dedupe   a waiting job with the same key is replaced instead of a second one queued (the newest nickname wins); null for none
 * @param attempts how many times it has been tried
 * @param waits    how many of those tries Discord answered "rate limited, wait" (they don't count against the job)
 * @param error    why the last try didn't work (shown while retrying, and once failed)
 * @param result   what the change answered, when it has something to say (e.g. the new message's id)
 */
public record DiscordJob(long id, String kind, long guildId, long actorId, String dedupe, DataObject payload, State state, int attempts, int waits, String error,
                         DataObject result, Instant nextAttemptAt, Instant createdAt, Instant updatedAt) {

    /** queued → running → done, or → retrying → running … → failed. */
    public enum State {
        QUEUED, RUNNING, RETRYING, DONE, FAILED;

        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }

        public boolean finished() {
            return this == DONE || this == FAILED;
        }

        public static Optional<State> ofKey(String key) {
            for (State s : values()) if (s.key().equals(key)) return Optional.of(s);
            return Optional.empty();
        }
    }

    /** What the website reads: the state and, when there is one, the reason. Never the payload. */
    public DataObject toJson() {
        return DataObject.empty().put("id", Long.toString(id)).put("kind", kind).put("state", state.key()).put("attempts", attempts).put("error", error)
                .put("result", result).put("nextAttemptAt", state == State.RETRYING && nextAttemptAt != null ? nextAttemptAt.toString() : null)
                .put("updatedAt", updatedAt == null ? null : updatedAt.toString());
    }

    /** The short form a route answers right after queueing, so the website can start following it. */
    public DataObject ticket() {
        return DataObject.empty().put("id", Long.toString(id)).put("state", state.key());
    }
}
