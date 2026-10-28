package com.runeclans.bot.discord;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.requests.ErrorResponse;
import net.dv8tion.jda.api.requests.RestAction;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.LongSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Asking Discord who someone is, from a website request, without ever waiting long. The bot caches only online members, so the website's
 * pages often have to ask Discord about a person; when Discord rate-limits that kind of question it makes the asker wait ~30 s, far past the
 * website's 8 s patience, and every request thread that waits is one fewer for everyone else. So every question here:
 * <ul>
 *   <li>tries JDA's cache, then a small cache of recent answers (names for 6 h, "no such user" for an hour, "not in that server" for a
 *       minute). Members themselves are not kept, so a page never shows roles or a nickname older than JDA's own view;</li>
 *   <li>otherwise asks Discord and waits at most {@link #BUDGET}. A question still waiting keeps going in the background and fills the cache,
 *       so the next page load knows; the same question is never asked twice at once;</li>
 *   <li>says {@link Outcome#SLOW} when Discord didn't answer in time. Display code shows "unknown"; a check that decides access throws
 *       {@link Slow} (the internal API answers 503 "Discord is slow, try again"), so slowness never becomes a wrong yes or no.</li>
 * </ul>
 * Never call this while holding a database connection.
 */
public final class DiscordLookups {
    /** The longest one question waits for Discord. */
    public static final Duration BUDGET = Duration.ofMillis(1500);
    static final long USER_TTL_MS = Duration.ofHours(6).toMillis();
    static final long UNKNOWN_TTL_MS = Duration.ofHours(1).toMillis();
    /** How long "not in that server" is believed. */
    static final long NOT_MEMBER_TTL_MS = Duration.ofMinutes(1).toMillis();
    /** A question Discord hasn't answered in this long is asked afresh rather than waited on. */
    static final long STALE_IN_FLIGHT_MS = Duration.ofMinutes(2).toMillis();
    static final int MAX_ENTRIES = 5_000;

    public enum Outcome { FOUND, ABSENT, SLOW }

    /** What the cache keeps about a user: enough to show them. */
    public record UserInfo(long id, String name, String avatarUrl) {
        static UserInfo of(User user) {
            return new UserInfo(user.getIdLong(), user.getName(), user.getEffectiveAvatarUrl());
        }
    }

    public record UserLookup(Outcome outcome, UserInfo user) {
        public String name() {
            return user == null ? null : user.name();
        }

        public String avatarUrl() {
            return user == null ? null : user.avatarUrl();
        }
    }

    public record MemberLookup(Outcome outcome, Member member) {}

    /** Discord didn't answer in time; the internal API turns this into a 503. */
    public static final class Slow extends RuntimeException {
        public static final String MESSAGE = "Discord is slow right now, so this couldn't be checked. Try again in a moment.";

        public Slow() {
            super(MESSAGE, null, false, false);
        }
    }

    private record Entry<V>(V value, long expiresAt) {}
    private record MemberKey(long guildId, long userId) {}
    private record InFlight<V>(CompletableFuture<V> future, long startedAt) {}

    private static final Map<Long, Entry<UserInfo>> users = lru();
    /** Only "not in that server" answers: a {@code null} value. */
    private static final Map<MemberKey, Entry<Member>> notMembers = lru();
    private static final Map<Long, InFlight<User>> usersAsked = new ConcurrentHashMap<>();
    private static final Map<MemberKey, InFlight<Member>> membersAsked = new ConcurrentHashMap<>();
    /** Replaceable so tests can move time. */
    static volatile LongSupplier clock = System::currentTimeMillis;

    private DiscordLookups() {}

    private static <K, V> Map<K, V> lru() {
        return Collections.synchronizedMap(new LinkedHashMap<>(256, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                return size() > MAX_ENTRIES;
            }
        });
    }

    // ---------- users ----------

    /** What is known about a user without asking Discord: JDA's cache, then ours. {@code null} when nothing is (or they don't exist). */
    public static UserInfo cachedUser(JDA jda, long userId) {
        User user = Shards.user(jda, userId);
        if (user != null) return UserInfo.of(user);
        Entry<UserInfo> known = fresh(users, userId);
        return known == null ? null : known.value();
    }

    /** A user's name and avatar, waiting for Discord at most {@code budget}. */
    public static UserLookup user(JDA jda, long userId, Duration budget) {
        User cached = Shards.user(jda, userId);
        if (cached != null) return new UserLookup(Outcome.FOUND, UserInfo.of(cached));
        Entry<UserInfo> known = fresh(users, userId);
        if (known != null) return new UserLookup(known.value() == null ? Outcome.ABSENT : Outcome.FOUND, known.value());
        CompletableFuture<User> asked = askUser(jda, userId);
        if (asked == null) return new UserLookup(Outcome.ABSENT, null);
        try {
            User user = wait(asked, budget);
            return user == null ? new UserLookup(Outcome.ABSENT, null) : new UserLookup(Outcome.FOUND, UserInfo.of(user));
        } catch (TimeoutException e) {
            return new UserLookup(Outcome.SLOW, null);
        } catch (ExecutionException e) {
            return new UserLookup(isUnknown(e.getCause()) ? Outcome.ABSENT : Outcome.SLOW, null);
        }
    }

    /** A user's name, or {@code null} when Discord doesn't know them or didn't answer within {@link #BUDGET}. */
    public static String userName(JDA jda, long userId) {
        return user(jda, userId, BUDGET).name();
    }

    /** Asks Discord about a user without waiting; {@code then} gets the answer when one comes (never on a failure), on JDA's thread. */
    public static void refreshUser(JDA jda, long userId, Consumer<UserInfo> then) {
        if (fresh(users, userId) != null) return; // answered recently (even "no such user"): nothing to ask
        CompletableFuture<User> asked = askUser(jda, userId);
        if (asked == null) return;
        asked.thenAccept(user -> {
            if (user != null) then.accept(UserInfo.of(user));
        });
    }

    private static CompletableFuture<User> askUser(JDA jda, long userId) {
        CompletableFuture<User> future = ask(usersAsked, userId, () -> jda.retrieveUserById(userId));
        if (future == null) return null;
        return future.whenComplete((user, error) -> {
            if (user != null) users.put(userId, new Entry<>(UserInfo.of(user), clock.getAsLong() + USER_TTL_MS));
            else if (error != null && isUnknown(error)) users.put(userId, new Entry<>(null, clock.getAsLong() + UNKNOWN_TTL_MS));
        });
    }

    // ---------- members ----------

    /** Someone as a member of a server, waiting for Discord at most {@code budget}. */
    public static MemberLookup member(Guild guild, long userId, Duration budget) {
        Member cached = guild.getMemberById(userId);
        if (cached != null) return new MemberLookup(Outcome.FOUND, cached);
        MemberKey key = new MemberKey(guild.getIdLong(), userId);
        if (fresh(notMembers, key) != null) return new MemberLookup(Outcome.ABSENT, null);
        return await(askMember(guild, key), budget);
    }

    /** The member, or {@code null} when they aren't in the server; throws {@link Slow} when Discord didn't answer in time. For access checks. */
    public static Member memberOrSlow(Guild guild, long userId) {
        MemberLookup found = member(guild, userId, BUDGET);
        if (found.outcome() == Outcome.SLOW) throw new Slow();
        return found.member();
    }

    /**
     * The same person in several servers, asked all at once and waited on together for at most {@code budget} in total, so a long server
     * list costs one wait rather than one per server. Answers come back in the order of {@code guilds}.
     */
    public static List<MemberLookup> memberInEach(List<Guild> guilds, long userId, Duration budget) {
        List<Object> pending = new ArrayList<>(guilds.size());
        for (Guild guild : guilds) {
            Member cached = guild.getMemberById(userId);
            if (cached != null) {
                pending.add(new MemberLookup(Outcome.FOUND, cached));
                continue;
            }
            MemberKey key = new MemberKey(guild.getIdLong(), userId);
            if (fresh(notMembers, key) != null) pending.add(new MemberLookup(Outcome.ABSENT, null));
            else pending.add(askMember(guild, key));
        }
        long deadline = System.nanoTime() + budget.toNanos();
        List<MemberLookup> out = new ArrayList<>(guilds.size());
        for (Object p : pending) {
            if (p instanceof MemberLookup done) {
                out.add(done);
                continue;
            }
            @SuppressWarnings("unchecked")
            CompletableFuture<Member> future = (CompletableFuture<Member>) p;
            out.add(await(future, Duration.ofNanos(Math.max(0, deadline - System.nanoTime()))));
        }
        return out;
    }

    private static MemberLookup await(CompletableFuture<Member> asked, Duration budget) {
        if (asked == null) return new MemberLookup(Outcome.ABSENT, null);
        try {
            Member member = wait(asked, budget);
            return new MemberLookup(member == null ? Outcome.ABSENT : Outcome.FOUND, member);
        } catch (TimeoutException e) {
            return new MemberLookup(Outcome.SLOW, null);
        } catch (ExecutionException e) {
            return new MemberLookup(isUnknown(e.getCause()) ? Outcome.ABSENT : Outcome.SLOW, null);
        }
    }

    private static CompletableFuture<Member> askMember(Guild guild, MemberKey key) {
        CompletableFuture<Member> future = ask(membersAsked, key, () -> guild.retrieveMemberById(key.userId()));
        if (future == null) return null;
        return future.whenComplete((member, error) -> {
            long now = clock.getAsLong();
            if (member != null) users.put(key.userId(), new Entry<>(UserInfo.of(member.getUser()), now + USER_TTL_MS));
            else if (error != null && isUnknown(error)) notMembers.put(key, new Entry<>(null, now + NOT_MEMBER_TTL_MS));
        });
    }

    // ---------- shared ----------

    /**
     * The question already on its way, or a new one. {@code null} when it can't even be asked (a malformed id, or a test double with no
     * answer), which counts as "no such person" and is not remembered.
     */
    private static <K, V> CompletableFuture<V> ask(Map<K, InFlight<V>> asked, K key, Supplier<RestAction<V>> action) {
        long now = clock.getAsLong();
        InFlight<V> running = asked.get(key);
        if (running != null && !running.future().isDone() && now - running.startedAt() < STALE_IN_FLIGHT_MS) return running.future();
        CompletableFuture<V> future;
        try {
            RestAction<V> request = action.get();
            future = request == null ? null : request.submit();
        } catch (RuntimeException e) {
            return null;
        }
        if (future == null) return null;
        InFlight<V> mine = new InFlight<>(future, now);
        asked.put(key, mine);
        future.whenComplete((v, e) -> asked.remove(key, mine));
        return future;
    }

    private static <V> V wait(CompletableFuture<V> future, Duration budget) throws TimeoutException, ExecutionException {
        if (future.isDone() || budget.isZero() || budget.isNegative()) {
            if (!future.isDone()) throw new TimeoutException();
            try {
                return future.join();
            } catch (CompletionException e) {
                throw new ExecutionException(e.getCause());
            } catch (CancellationException e) {
                throw new ExecutionException(e);
            }
        }
        try {
            return future.get(budget.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TimeoutException();
        } catch (CancellationException e) {
            throw new ExecutionException(e);
        }
    }

    /** Discord said there's no such person (or they aren't in that server), as opposed to not answering. */
    static boolean isUnknown(Throwable error) {
        Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
        return cause instanceof ErrorResponseException e
                && (e.getErrorResponse() == ErrorResponse.UNKNOWN_MEMBER || e.getErrorResponse() == ErrorResponse.UNKNOWN_USER);
    }

    private static <K, V> Entry<V> fresh(Map<K, Entry<V>> cache, K key) {
        Entry<V> entry = cache.get(key);
        if (entry == null) return null;
        if (entry.expiresAt() <= clock.getAsLong()) {
            cache.remove(key, entry);
            return null;
        }
        return entry;
    }

    /** Forgets every remembered answer. For tests, and for nothing else. */
    public static void forgetAll() {
        users.clear();
        notMembers.clear();
        usersAsked.clear();
        membersAsked.clear();
        clock = System::currentTimeMillis;
    }
}
