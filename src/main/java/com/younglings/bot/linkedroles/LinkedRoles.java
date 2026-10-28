package com.younglings.bot.linkedroles;

import com.younglings.bot.linkedroles.DiscordRoleApi.Revoked;
import com.younglings.bot.linkedroles.DiscordRoleApi.Tokens;
import com.younglings.bot.linkedroles.LinkedRolesStore.Row;
import com.younglings.bot.linkedroles.RoleMetadata.Profile;
import com.younglings.bot.util.BotScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongPredicate;

/**
 * Discord Linked Roles (T-LR). A server admin makes a role that needs, say, "Total level at least 2000"; a member clicks the role's link, signs
 * in on the website's {@code /linked-roles} page, and the website hands the OAuth tokens here ({@link LinkedRolesApi}). This service:
 * <ul>
 *   <li>keeps the tokens sealed ({@link TokenBox}) and sends the person's metadata to Discord right away;</li>
 *   <li>every hour, asks the RuneClans hub for each connected person's current RuneLink name ({@link RsLinkSource}, T-349), works out their
 *       values from that and what the bot already keeps, and sends them again when they changed (a level-up, a new clan, a RuneLink unlink)
 *       or when a day has passed since the last send, one person at a time with a pause between calls;</li>
 *   <li>forgets the tokens when Discord says the grant is gone, or when the person unlinks (their values are cleared first, so the roles
 *       drop);</li>
 *   <li>registers the metadata schema with Discord when the bot starts (a PUT, so running it twice changes nothing).</li>
 * </ul>
 * Off unless {@code LINKED_ROLES_ENABLED=true} and the key and application are set ({@link LinkedRolesSettings}). Built by
 * {@link LinkedRolesService} (the framework's bean); kept apart so the tests can build one with a fake Discord.
 */
public class LinkedRoles {
    private static final Logger log = LoggerFactory.getLogger(LinkedRoles.class);
    static final Duration TICK = Duration.ofHours(1);
    static final Duration RESEND_AFTER = Duration.ofHours(24);
    static final Duration EARLY_REFRESH = Duration.ofMinutes(5);
    /** Pause between two people's sends, well under Discord's per-route limits. */
    static final long PAUSE_MS = 1_000;
    static final int MAX_PER_TICK = 3_000;

    /**
     * What was shared with Discord, for the website's result page. {@code values} are the metadata exactly as sent. {@code unconfirmedRsLink}
     * is the person's RuneLink name when it isn't counted because the bot requires confirmed links and the hub hasn't confirmed it yet
     * (T-552), else null.
     */
    public record Shared(Profile profile, java.util.Map<String, String> values, Instant pushedAt, String unconfirmedRsLink) {}

    /** Why a connect or push didn't happen, in words the website can show. */
    public static final class Problem extends Exception {
        private final int status;
        private final String code;

        public Problem(int status, String code, String message) {
            super(message);
            this.status = status;
            this.code = code;
        }

        public int status() {
            return status;
        }

        public String code() {
            return code;
        }
    }

    private final LinkedRolesSettings settings;
    private final LinkedRolesStore store;
    private final DiscordRoleApi discord;
    private final TokenBox box;
    private final LongPredicate listedClan;
    private final RsLinkSource rsLinks;
    private final Clock clock;
    private final long pauseMs;
    private final String botToken;
    private ScheduledExecutorService executor;

    /** The real one: Discord over HTTP, the system clock, a pause between sends. */
    static LinkedRoles live(LinkedRolesSettings settings, LinkedRolesStore store, LongPredicate listed, RsLinkSource rsLinks, String botToken) {
        return new LinkedRoles(settings, store, null, listed, rsLinks, Clock.systemUTC(), PAUSE_MS, botToken);
    }

    /** The tests' way in: any settings, a fake Discord, a fixed clock, no pause. */
    static LinkedRoles forTest(LinkedRolesSettings settings, LinkedRolesStore store, DiscordRoleApi discord, LongPredicate listed, Clock clock) {
        return forTest(settings, store, discord, listed, RsLinkSource.NONE, clock);
    }

    /** The same with a stand-in for the hub's RuneLink answers. */
    static LinkedRoles forTest(LinkedRolesSettings settings, LinkedRolesStore store, DiscordRoleApi discord, LongPredicate listed, RsLinkSource rsLinks,
                               Clock clock) {
        return new LinkedRoles(settings, store, discord, listed, rsLinks, clock, 0, "bot-token-for-tests");
    }

    private LinkedRoles(LinkedRolesSettings settings, LinkedRolesStore store, DiscordRoleApi discord, LongPredicate listed, RsLinkSource rsLinks,
                        Clock clock, long pauseMs, String botToken) {
        this.settings = settings;
        this.store = store;
        this.discord = discord != null ? discord : settings.enabled() ? DiscordRoleApi.http(settings) : null;
        this.box = settings.enabled() ? new TokenBox(settings.tokenKey()) : null;
        this.listedClan = listed;
        this.rsLinks = rsLinks == null ? RsLinkSource.NONE : rsLinks;
        this.clock = clock;
        this.pauseMs = pauseMs;
        this.botToken = botToken;
    }

    public boolean enabled() {
        return settings.enabled();
    }

    /** Starts the hourly pass and the schema registration, once the bot is connected. Does nothing while off. */
    void start() {
        if (!settings.enabled()) {
            if (!"LINKED_ROLES_ENABLED is not true".equals(settings.problem())) log.warn("Linked Roles stays off: {}", settings.problem());
            return;
        }
        executor = BotScheduler.lane("linked-roles");
        if (settings.registerOnStart()) executor.execute(this::registerSchema);
        executor.scheduleWithFixedDelay(this::tickSafely, 5, TICK.toMinutes(), TimeUnit.MINUTES);
        log.info("Linked Roles is on: metadata re-sent hourly when it changes, daily at least");
    }

    /** Registers the five metadata records with Discord. Idempotent: Discord replaces the list with the same list. */
    public boolean registerSchema() {
        if (!settings.enabled() || botToken == null) return false;
        try {
            discord.putSchema(botToken, RoleMetadata.schemaJson());
            log.info("Linked Roles metadata schema registered ({} records)", RoleMetadata.SCHEMA.size());
            return true;
        } catch (DiscordRoleApi.Failure e) {
            log.warn("Registering the Linked Roles metadata schema failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * A person just verified on the website: keep their tokens and send their values now. {@code rsLinkRsn} is their active RuneLink name from
     * runeclans-hub, if the website found one (used only when the bot has no linked name of its own for them).
     */
    public Shared connect(long userId, String accessToken, String refreshToken, long expiresInSeconds, String rsLinkRsn, Instant rsLinkSince) throws Problem {
        requireOn();
        if (accessToken == null || accessToken.isBlank() || refreshToken == null || refreshToken.isBlank()) throw new Problem(400, "bad_tokens", "Sign-in didn't finish. Try again.");
        Instant now = clock.instant();
        String rs = cleanRsn(rsLinkRsn);
        store.saveTokens(userId, box.seal(accessToken, userId), box.seal(refreshToken, userId), now.plusSeconds(Math.max(60, expiresInSeconds)), rs,
                rs == null ? null : rsLinkSince == null ? now : rsLinkSince);
        Row row = store.find(userId).orElseThrow(() -> new Problem(500, "not_saved", "Something went wrong. Try again."));
        if (settings.requireConfirmed()) {
            // the website doesn't say whether the link is confirmed: ask the hub now rather than wait for the hourly pass
            Optional<Map<Long, RsLinkSource.Current>> current = askRsLink(List.of(userId));
            if (current.isPresent() && updateRsLink(userId, current.get().get(userId), now)) row = store.find(userId).orElse(row);
        }
        return push(row, true).orElseThrow(() -> new Problem(502, "discord", "Discord didn't take the update. Try again in a minute."));
    }

    /** What the person has shared, worked out fresh (no call to Discord), or empty when they aren't connected. */
    public Optional<Shared> status(long userId) throws Problem {
        requireOn();
        Optional<Row> row = store.find(userId);
        if (row.isEmpty()) return Optional.empty();
        Profile p = profile(row.get());
        return Optional.of(new Shared(p, RoleMetadata.values(p), row.get().lastPushedAt(), unconfirmed(row.get())));
    }

    /** Unlink: clear the values on Discord (the roles drop), revoke the token and forget it. True when there was something to forget. */
    public boolean unlink(long userId) throws Problem {
        requireOn();
        Optional<Row> found = store.find(userId);
        if (found.isEmpty()) return false;
        Row row = found.get();
        String refresh = box.open(row.refreshEnc(), userId);
        try {
            String access = freshAccess(row);
            if (access != null) discord.putConnection(access, RoleMetadata.clearedJson());
        } catch (Revoked e) {
            // already gone on Discord's side
        } catch (DiscordRoleApi.Failure e) {
            log.info("Clearing a linked-roles connection before unlinking failed ({}); the token is revoked and forgotten anyway", e.getMessage());
        }
        if (refresh != null) discord.revoke(refresh);
        store.delete(userId);
        log.info("Linked Roles: a person unlinked");
        return true;
    }

    /** A RuneScape name as RuneLink shows it (1 to 12 letters, digits, spaces, _ or -), stripped; null when it isn't one. */
    static String cleanRsn(String raw) {
        String rs = raw == null || raw.isBlank() ? null : raw.strip();
        return rs != null && rs.matches("[A-Za-z0-9 _-]{1,12}") ? rs : null;
    }

    /**
     * One hourly pass: bring each person's RuneLink name up to date from the hub, then send everyone whose values changed, or who hasn't been
     * sent for a day.
     */
    int tick() {
        if (!settings.enabled()) return 0;
        Instant now = clock.instant();
        int sent = 0;
        List<Long> userIds = store.allUserIds(MAX_PER_TICK);
        int updated = 0;
        for (int from = 0; from < userIds.size(); from += RsLinkSource.BATCH) {
            List<Long> batch = userIds.subList(from, Math.min(userIds.size(), from + RsLinkSource.BATCH));
            Optional<Map<Long, RsLinkSource.Current>> current = askRsLink(batch);
            if (current.isEmpty()) break;
            for (long userId : batch) {
                if (updateRsLink(userId, current.get().get(userId), now)) updated++;
            }
        }
        if (updated > 0) log.info("Linked Roles: {} RuneLink name(s) changed since sign-in", updated);
        for (long userId : userIds) {
            Optional<Row> found = store.find(userId);
            if (found.isEmpty()) continue;
            Row row = found.get();
            Profile p = profile(row);
            boolean changed = !RoleMetadata.fingerprint(p).equals(row.lastFingerprint());
            boolean stale = row.lastPushedAt() == null || row.lastPushedAt().plus(RESEND_AFTER).isBefore(now);
            if (!changed && !stale) continue;
            if (push(row, false).isPresent()) sent++;
            pause();
        }
        return sent;
    }

    private Optional<Map<Long, RsLinkSource.Current>> askRsLink(List<Long> batch) {
        try {
            return rsLinks.current(batch);
        } catch (RuntimeException e) {
            log.info("Linked Roles: asking the hub for RuneLink names failed ({}); keeping the names from sign-in", e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    /**
     * Stores the person's current RuneLink name when it differs from the one kept ({@code current} null: no link any more). A new name with no
     * link date counts from {@code now}; the same name keeps its date unless the hub gives another. True when something changed.
     */
    private boolean updateRsLink(long userId, RsLinkSource.Current current, Instant now) {
        Optional<Row> found = store.find(userId);
        if (found.isEmpty()) return false;
        Row row = found.get();
        String rsn = current == null ? null : current.rsn();
        boolean confirmed = current != null && current.confirmed();
        Instant since;
        if (rsn == null) since = null;
        else if (current.since() != null) since = current.since();
        else since = rsn.equals(row.rsLinkRsn()) && row.rsLinkSince() != null ? row.rsLinkSince() : now;
        if (java.util.Objects.equals(rsn, row.rsLinkRsn()) && java.util.Objects.equals(since, row.rsLinkSince()) && confirmed == row.rsLinkConfirmed()) return false;
        store.updateRsLink(userId, rsn, since, confirmed);
        return true;
    }

    private void tickSafely() {
        try {
            int sent = tick();
            if (sent > 0) log.info("Linked Roles: sent {} updated connection(s)", sent);
        } catch (RuntimeException e) {
            log.warn("Linked Roles' hourly pass failed: {}", e.getClass().getSimpleName(), e);
        }
    }

    /** Sends one person's values; refreshes the access token when it is about to run out. Empty when it didn't happen. */
    private Optional<Shared> push(Row row, boolean justConnected) {
        long userId = row.discordUserId();
        Profile p = profile(row);
        try {
            String access = freshAccess(row);
            if (access == null) {
                forget(userId, "its tokens no longer open (was the key changed?)");
                return Optional.empty();
            }
            try {
                discord.putConnection(access, RoleMetadata.connectionJson(p));
            } catch (Revoked e) {
                if (justConnected) throw e;
                // the access token may have been revoked on its own: one refresh, then once more
                access = refreshed(row);
                discord.putConnection(access, RoleMetadata.connectionJson(p));
            }
            Instant at = clock.instant();
            store.markPushed(userId, RoleMetadata.fingerprint(p), at);
            return Optional.of(new Shared(p, RoleMetadata.values(p), at, unconfirmed(row)));
        } catch (Revoked e) {
            forget(userId, "Discord says the authorization is gone");
            return Optional.empty();
        } catch (DiscordRoleApi.Failure e) {
            int failures = store.markFailed(userId);
            log.info("Linked Roles: a send failed ({}), {} in a row", e.getMessage(), failures);
            return Optional.empty();
        }
    }

    /** The access token, refreshed first when it runs out within five minutes. Null when the sealed tokens can't be opened. */
    private String freshAccess(Row row) throws Revoked, DiscordRoleApi.Failure {
        if (row.accessExpiresAt().isAfter(clock.instant().plus(EARLY_REFRESH))) return box.open(row.accessEnc(), row.discordUserId());
        return refreshed(row);
    }

    private String refreshed(Row row) throws Revoked, DiscordRoleApi.Failure {
        String refresh = box.open(row.refreshEnc(), row.discordUserId());
        if (refresh == null) return null;
        Tokens t = discord.refresh(refresh);
        if (t.accessToken() == null || t.refreshToken() == null) throw new DiscordRoleApi.Failure("token refresh gave no tokens");
        store.updateTokens(row.discordUserId(), box.seal(t.accessToken(), row.discordUserId()), box.seal(t.refreshToken(), row.discordUserId()),
                clock.instant().plusSeconds(Math.max(60, t.expiresInSeconds())));
        return t.accessToken();
    }

    private void forget(long userId, String why) {
        store.delete(userId);
        log.info("Linked Roles: forgot a person's tokens: {}", why);
    }

    /** With {@code LINKED_ROLES_REQUIRE_CONFIRMED}, a RuneLink name counts only once the hub has confirmed it (T-552). */
    private Profile profile(Row row) {
        String rsLink = settings.requireConfirmed() && !row.rsLinkConfirmed() ? null : row.rsLinkRsn();
        return store.profile(row.discordUserId(), rsLink, rsLink == null ? null : row.rsLinkSince(), listedClan);
    }

    private String unconfirmed(Row row) {
        return settings.requireConfirmed() && !row.rsLinkConfirmed() ? row.rsLinkRsn() : null;
    }

    /** Whether RuneLink names count only once confirmed ({@code LINKED_ROLES_REQUIRE_CONFIRMED}). */
    public boolean requireConfirmed() {
        return settings.requireConfirmed();
    }

    private void requireOn() throws Problem {
        if (!settings.enabled()) throw new Problem(404, "off", "Linked Roles isn't switched on.");
    }

    private void pause() {
        if (pauseMs <= 0) return;
        try {
            Thread.sleep(pauseMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
