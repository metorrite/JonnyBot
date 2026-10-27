package com.younglings.bot.rsnlink;

import com.younglings.bot.rsnlink.LinkStore.DiscordUser;
import com.younglings.bot.rsnlink.LinkStore.Pairing;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;

/**
 * The DM fallback (LINK-SYSTEM.md section 4.2, ticket T-115): the player types their Discord username or id into the plugin, JonnyBot DMs
 * them a code, and they type the code back into the plugin.
 * <p>
 * <b>Same answer every time</b> (Drake's decision Q3): {@code POST /v1/pair/dm} always answers 202 with a pairing id and device secret,
 * whether the account exists, shares a server with the bot, accepts DMs, has opted out or has hit a cap. The lookup and the send happen on
 * a background thread after the answer, so not even the response time tells them apart. Only the asker's own limits (per IP, per tracking
 * id) answer 429, because those are about the asker, not the target.
 * <p>
 * <b>Anti-spam</b> (section 8): per target 1 DM per 10 minutes and 3 per day, none while the target opted out ("Stop these messages",
 * 90 days), and at most 300 DMs an hour across the whole service (a circuit breaker the owner sees in T-118). Counts are in the database so
 * a restart doesn't reset them. A DM always names the RSN that asked and says how to ignore it.
 * <p>
 * The typed Discord id is stored only sealed (AES-GCM) on the pairing, which is deleted 24 hours after it ends; a {@code discord_user} row
 * is made only when the person who got the DM confirms the code, or presses "Stop these messages".
 */
public final class LinkDm {
    private static final Logger log = LoggerFactory.getLogger(LinkDm.class);

    private final LinkService service;
    private final LinkStore store;
    private final LinkCrypto crypto;
    private final Executor background;

    // Shared by every replica through the link database (SCALING-STATE.md M7).
    private final SharedWindowLimiter dmPerIp;
    private final SharedWindowLimiter dmPerTracking;
    private volatile Instant breakerReportedAt = Instant.EPOCH;

    LinkDm(LinkService service, Executor background) {
        this.service = service;
        this.store = service.store();
        this.crypto = service.crypto();
        this.background = background;
        this.dmPerIp = new SharedWindowLimiter(store.schema(), "dm_ip", LinkLimits.DM_PER_IP);
        this.dmPerTracking = new SharedWindowLimiter(store.schema(), "dm_tracking", LinkLimits.DM_PER_TRACKING);
    }

    /** What the player typed: a Discord id, or a username to resolve among the servers the bot shares. */
    record Target(String id, String username) {}

    /**
     * Section 4.2's input rules: all digits and 17 to 20 long is an id; otherwise a username, trimmed, a leading {@code @} dropped, lower
     * case, 2 to 32 of {@code a-z 0-9 . _}. Old {@code name#1234} tags are refused with a hint. The refusal only judges the shape of the
     * input, so it reveals nothing about any account.
     */
    static Target parse(String raw) {
        String input = raw == null ? "" : raw.strip();
        if (input.matches("\\d{17,20}")) return new Target(input, null);
        if (input.matches(".*#\\d{4}$")) throw LinkException.bad("old_tag", "Discord names with #1234 are gone. Type your new username instead.");
        if (input.startsWith("@")) input = input.substring(1);
        String name = input.toLowerCase(Locale.ROOT);
        if (!name.matches("[a-z0-9._]{2,32}")) throw LinkException.bad("bad_discord", "Type your Discord username (like someone.name) or your user id.");
        return new Target(null, name);
    }

    /** {@code POST /v1/pair/dm}: always 202 with a pairing id and device secret. */
    public DataObject start(String rawRsn, String rawTracking, String discordInput, String client, String ip) {
        String rsn = LinkCodes.normaliseRsn(rawRsn);
        if (rsn == null) throw LinkException.bad("bad_rsn", "That isn't a RuneScape name (1 to 12 letters, digits, spaces, - or _).");
        String tracking = rawTracking == null || rawTracking.isBlank() ? null : LinkCodes.normaliseTrackingId(rawTracking);
        if (rawTracking != null && !rawTracking.isBlank() && tracking == null) throw LinkException.bad("bad_tracking_id", "The tracking id must be a number.");
        Target target = parse(discordInput);
        Instant now = service.clock().instant();
        byte[] ipLookup = crypto.lookup(LinkService.SUBJECT_IP, ip == null || ip.isBlank() ? "unknown" : ip.strip());
        byte[] trackingLookup = tracking == null ? null : crypto.lookup(LinkService.SUBJECT_TRACKING, tracking);
        String rsnKey = LinkCodes.rsnLookup(rsn);

        String[] code = new String[1];
        DataObject answer = service.tx(c -> {
            service.refuseIfBlocked(c, now, LinkService.SUBJECT_IP, ipLookup);
            if (trackingLookup != null) service.refuseIfBlocked(c, now, LinkService.SUBJECT_TRACKING, trackingLookup);
            requesterLimit(c, dmPerIp, "ip:" + hex(ipLookup), now, LinkService.SUBJECT_IP, ipLookup);
            if (trackingLookup != null) requesterLimit(c, dmPerTracking, "tr:" + hex(trackingLookup), now, LinkService.SUBJECT_TRACKING, trackingLookup);
            String deviceSecret = LinkCodes.newDeviceSecret(service.random());
            UUID id = UUID.randomUUID();
            code[0] = service.insertWithFreshCode(c, id, LinkService.METHOD_DM, deviceSecret, rsn, rsnKey, trackingLookup, null, client, ipLookup, now);
            return DataObject.empty().put("pairingId", id.toString()).put("deviceSecret", deviceSecret)
                    .put("expiresIn", LinkLimits.PAIRING_LIFETIME.toSeconds());
        });
        UUID pairingId = UUID.fromString(answer.getString("pairingId"));
        log.info("Link pairing {} started (DM fallback)", pairingId);
        background.execute(() -> deliver(pairingId, target, rsn, code[0]));
        return LinkHttp.withStatus(answer, 202);
    }

    /** The background half: find the account, check every cap, send. Each outcome is recorded; none is ever told to the asker. */
    void deliver(UUID pairingId, Target target, String rsnDisplay, String userCode) {
        try {
            String discordId = target.id() != null ? target.id() : service.dm().resolveUsername(target.username()).orElse(null);
            if (discordId == null) {
                log.info("Link pairing {}: DM not sent (no unique account found)", pairingId);
                return;
            }
            byte[] lookup = crypto.lookup(LinkService.SUBJECT_DISCORD, discordId);
            Instant now = service.clock().instant();
            String skip = service.tx(c -> {
                Pairing p = store.pairing(c, pairingId, true).orElse(null);
                if (p == null || !p.status().equals("pending")) return "gone";
                store.setDmTarget(c, pairingId, lookup, crypto.encrypt(discordId), LinkCrypto.CURRENT_KEY_VERSION);
                String why = capReason(c, lookup, now);
                store.dmSend(c, lookup, why == null ? "sending" : why, now);
                return why;
            });
            if (skip != null) {
                log.info("Link pairing {}: DM not sent ({})", pairingId, skip);
                return;
            }
            boolean sent = service.dm().sendCode(discordId, rsnDisplay, userCode, pairingId.toString(), LinkLimits.PAIRING_LIFETIME.toMinutes());
            service.tx(c -> {
                try (var s = c.prepareStatement(store.sql("UPDATE {s}.dm_send SET outcome = ? WHERE id = (SELECT max(id) FROM {s}.dm_send WHERE target_lookup = ? AND outcome = 'sending')"))) {
                    s.setString(1, sent ? "sent" : "failed");
                    s.setBytes(2, lookup);
                    s.executeUpdate();
                }
                return null;
            });
            log.info("Link pairing {}: DM {}", pairingId, sent ? "sent" : "refused by Discord");
        } catch (RuntimeException e) {
            log.warn("Link pairing {}: DM delivery failed ({})", pairingId, e.getClass().getSimpleName());
        }
    }

    /** Why this target must not get a DM now, or null if it may. */
    private String capReason(Connection c, byte[] lookup, Instant now) throws SQLException {
        Optional<DiscordUser> user = store.discordUser(c, lookup);
        if (user.isPresent() && user.get().dmOptoutUntil() != null && user.get().dmOptoutUntil().isAfter(now)) return "opted_out";
        if (store.blocked(c, LinkService.SUBJECT_DISCORD, lookup, now)) return "blocked";
        if (store.countDmSent(c, lookup, now.minus(LinkLimits.DM_PER_TARGET_SHORT.window())) >= LinkLimits.DM_PER_TARGET_SHORT.max()) return "target_cooldown";
        if (store.countDmSent(c, lookup, now.minus(LinkLimits.DM_PER_TARGET_DAY.window())) >= LinkLimits.DM_PER_TARGET_DAY.max()) return "target_daily_cap";
        if (store.countDmSentTotal(c, now.minus(LinkLimits.DM_TOTAL.window())) >= LinkLimits.DM_TOTAL.max()) {
            if (breakerReportedAt.isBefore(now.minus(LinkLimits.DM_TOTAL.window()))) {
                breakerReportedAt = now;
                store.abuseEvent(c, "dm_breaker", "service", null, null, now);
                log.warn("Link DM circuit breaker is open: {} DMs in the last hour", LinkLimits.DM_TOTAL.max());
            }
            return "breaker";
        }
        return null;
    }

    /** Whether the service-wide DM cap is reached right now (for the owner's abuse view). */
    public boolean breakerOpen() {
        Instant now = service.clock().instant();
        return service.tx(c -> store.countDmSentTotal(c, now.minus(LinkLimits.DM_TOTAL.window())) >= LinkLimits.DM_TOTAL.max());
    }

    /** {@code POST /v1/pair/dm/confirm}: the code from the DM, typed into the plugin. Returns the token once. */
    public DataObject confirm(String rawPairingId, String deviceSecret, String typedCode) {
        UUID id = LinkService.parseUuid(rawPairingId);
        if (id == null || !LinkCodes.looksLikeDeviceSecret(deviceSecret)) throw new LinkException(410, "expired_or_unknown", "That request has ended. Start again.");
        Instant now = service.clock().instant();
        return service.tx(c -> {
            Pairing p = store.pairing(c, id, true).orElse(null);
            if (p == null || !p.method().equals(LinkService.METHOD_DM) || !MessageDigest.isEqual(p.deviceSecretHash(), LinkCrypto.sha256(deviceSecret))) {
                throw new LinkException(410, "expired_or_unknown", "That request has ended. Start again.");
            }
            if (p.status().equals("locked")) throw new LinkException(423, "locked", "Too many wrong codes. Start again.");
            if (!p.status().equals("pending")) throw new LinkException(410, "expired_or_unknown", "That request has ended. Start again.");
            if (p.expiresAt().isBefore(now)) {
                store.endPairing(c, p.id(), "expired", null, now);
                throw new LinkException(410, "expired_or_unknown", "That code has expired. Start again.");
            }
            String code = LinkCodes.normaliseCode(typedCode);
            Object[] target = store.dmTarget(c, p.id());
            boolean right = code != null && target != null && MessageDigest.isEqual(p.codeLookup(), crypto.lookup("code", code));
            if (!right) {
                int attempts = store.addAttempt(c, p.id());
                if (attempts >= LinkLimits.WRONG_CODES_PER_PAIRING) {
                    store.endPairing(c, p.id(), "locked", null, now);
                    service.lockout(c, LinkService.SUBJECT_IP, p.ipLookup(), "dm_wrong_codes", now);
                    if (p.trackingLookup() != null) service.lockout(c, LinkService.SUBJECT_TRACKING, p.trackingLookup(), "dm_wrong_codes", now);
                    throw new LinkException(423, "locked", "Too many wrong codes. Start again.");
                }
                throw new LinkException(400, "wrong_code", "That code isn't right.", null,
                        java.util.Map.of("attemptsLeft", LinkLimits.WRONG_CODES_PER_PAIRING - attempts));
            }
            String discordId = crypto.decrypt((byte[]) target[0], (Short) target[1]);
            UUID userId = service.upsertDiscord(c, discordId, null, now);
            LinkService.Outcome outcome = service.applyLink(c, p, userId, LinkService.METHOD_DM, now);
            String token = service.issueTokenFor(c, outcome.link().id(), p.client(), now);
            store.endPairing(c, p.id(), "consumed", null, now);
            log.info("Link pairing {} confirmed by DM code (link {})", p.id(), outcome.link().id());
            return service.linkedAnswer(c, outcome.link(), token);
        });
    }

    /** The DM's "Not me" button, pressed by {@code clickerId}. Only the person the DM went to can deny it. */
    public boolean notMe(String rawPairingId, String clickerId) {
        UUID id = LinkService.parseUuid(rawPairingId);
        if (id == null) return false;
        Instant now = service.clock().instant();
        byte[] lookup = crypto.lookup(LinkService.SUBJECT_DISCORD, LinkService.requireDiscordId(clickerId));
        return service.tx(c -> {
            Pairing p = store.pairing(c, id, true).orElse(null);
            if (p == null || p.targetDiscordLookup() == null || !MessageDigest.isEqual(p.targetDiscordLookup(), lookup)) return false;
            if (p.status().equals("pending")) store.endPairing(c, p.id(), "denied", "not_me", now);
            store.abuseEvent(c, "dm_not_me", LinkService.SUBJECT_IP, p.ipLookup(), null, now);
            if (p.trackingLookup() != null) store.abuseEvent(c, "dm_not_me", LinkService.SUBJECT_TRACKING, p.trackingLookup(), null, now);
            log.info("Link pairing {} denied from the DM", p.id());
            return true;
        });
    }

    /** The DM's "Stop these messages" button: no more code DMs to this account for 90 days (pressing it again renews). */
    public Instant stop(String rawPairingId, String clickerId) {
        String discordId = LinkService.requireDiscordId(clickerId);
        notMe(rawPairingId, discordId);
        Instant now = service.clock().instant();
        Instant until = now.plus(LinkLimits.DM_OPT_OUT);
        service.tx(c -> {
            UUID userId = service.upsertDiscord(c, discordId, null, now);
            store.setDmOptOut(c, userId, until);
            return null;
        });
        log.info("A Discord account opted out of link code DMs for 90 days");
        return until;
    }

    private void requesterLimit(Connection c, SharedWindowLimiter limiter, String key, Instant now, String kind, byte[] lookup) throws SQLException {
        int wait = limiter.tryAcquire(c, key, now.toEpochMilli());
        if (wait == 0) return;
        store.abuseEvent(c, "rate_limited", kind, lookup, DataObject.empty().put("route", "dm").toString(), now);
        throw LinkException.slowDown(wait);
    }

    private static String hex(byte[] bytes) {
        return java.util.HexFormat.of().formatHex(bytes);
    }
}
