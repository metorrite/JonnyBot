package com.younglings.bot.rsnlink;

import com.younglings.bot.rsnlink.LinkStore.Character;
import com.younglings.bot.rsnlink.LinkStore.DiscordUser;
import com.younglings.bot.rsnlink.LinkStore.Link;
import com.younglings.bot.rsnlink.LinkStore.Pairing;
import com.younglings.bot.rsnlink.LinkStore.TokenAuth;
import com.younglings.bot.rsnlink.LinkVerifier.LinkContext;
import com.younglings.bot.rsnlink.LinkVerifier.VerificationResult;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The rules of the RSN link service (LINK-SYSTEM.md sections 4, 7 to 13). Every public method is one route of section 5; the HTTP layer
 * ({@link LinkHttp}) only parses and answers. Refusals are {@link LinkException}s with player-facing words.
 * <p>
 * <b>What is never logged or returned to the wrong party:</b> user codes, device secrets, tokens, Discord ids, tracking ids and IPs. Logs
 * carry the pairing or link id and at most a 4-byte prefix of a lookup. A test runs every flow with a capturing logger to hold this.
 * <p>
 * <b>Names claimed twice</b> (section 11 plus the 2026-10-20 research): when a tracked claim meets a name another tracked character
 * reported within 30 days, the newcomer is <em>not</em> refused. Both links are kept, the new one as {@code disputed}, and a
 * {@code dispute} row is opened for the owner's abuse view (T-118) and, later, the automatic tie-breaker (T-122). A link stronger than
 * {@link Verification#IMPLICIT} can't be disputed by an implicit one; that claim is refused instead.
 */
public final class LinkService {
    private static final Logger log = LoggerFactory.getLogger(LinkService.class);

    public static final String SUBJECT_IP = "ip";
    public static final String SUBJECT_DISCORD = "discord";
    public static final String SUBJECT_TRACKING = "tracking";
    public static final String SUBJECT_RSN = "rsn";

    static final String METHOD_OAUTH = "oauth";
    static final String METHOD_DM = "dm";

    private final LinkSettings settings;
    private final LinkDatabase database;
    private final LinkStore store;
    private final LinkCrypto crypto;
    private final AliasSigner signer;
    private final LinkDmSender dm;
    private final List<LinkVerifier> verifiers;
    private final Clock clock;
    private final SecureRandom random;

    private final WindowLimiter startPerIp = new WindowLimiter(LinkLimits.START_PER_IP);
    private final WindowLimiter startPerTracking = new WindowLimiter(LinkLimits.START_PER_TRACKING);
    private final WindowLimiter startPerRsn = new WindowLimiter(LinkLimits.START_PER_RSN);
    private final WindowLimiter codePerIpMinute = new WindowLimiter(LinkLimits.WEB_CODE_PER_IP_MINUTE);
    private final WindowLimiter codePerIpHour = new WindowLimiter(LinkLimits.WEB_CODE_PER_IP_HOUR);
    private final WindowLimiter wrongCodesPerDiscord = new WindowLimiter(LinkLimits.WRONG_CODES_PER_DISCORD);
    private final WindowLimiter tokenRoutes = new WindowLimiter(LinkLimits.TOKEN_ROUTES_PER_TOKEN);
    private final WindowLimiter aliasRoute = new WindowLimiter(LinkLimits.ALIAS_PER_TOKEN);
    private final WindowLimiter webAccount = new WindowLimiter(LinkLimits.WEB_ACCOUNT_PER_DISCORD);

    public LinkService(LinkSettings settings, LinkDatabase database, LinkDmSender dm, List<LinkVerifier> verifiers, Clock clock, SecureRandom random) {
        this.settings = settings;
        this.database = database;
        this.store = new LinkStore(settings.schema());
        this.crypto = new LinkCrypto(settings, random);
        this.signer = new AliasSigner(settings.signingSeed(), settings.publicBaseUrl());
        this.dm = dm;
        this.verifiers = List.copyOf(verifiers);
        this.clock = clock;
        this.random = random;
    }

    /** Creates or upgrades the schema. Call once before serving. */
    public void start() {
        try (Connection c = database.connection()) {
            LinkSchema.migrate(c, settings.schema());
        } catch (SQLException e) {
            throw new IllegalStateException("The link database couldn't be prepared: " + e.getSQLState(), e);
        }
    }

    // =====================================================================================================================
    // Plugin routes (section 5.1)
    // =====================================================================================================================

    /** {@code POST /v1/pair/start}: a new sign-in pairing. Returns the code to show and the device secret to poll with. */
    public DataObject startPairing(String rawRsn, String rawTracking, String client, String ip) {
        String rsn = requireRsn(rawRsn);
        String tracking = optionalTracking(rawTracking);
        Instant now = clock.instant();
        byte[] ipLookup = crypto.lookup(SUBJECT_IP, ipOrUnknown(ip));
        byte[] trackingLookup = tracking == null ? null : crypto.lookup(SUBJECT_TRACKING, tracking);
        String rsnKey = LinkCodes.rsnLookup(rsn);

        return tx(c -> {
            refuseIfBlocked(c, now, SUBJECT_IP, ipLookup);
            if (trackingLookup != null) refuseIfBlocked(c, now, SUBJECT_TRACKING, trackingLookup);
            refuseIfBlocked(c, now, SUBJECT_RSN, crypto.lookup(SUBJECT_RSN, rsnKey));
            limit(c, startPerIp, "ip:" + hex(ipLookup), now, SUBJECT_IP, ipLookup);
            if (trackingLookup != null) limit(c, startPerTracking, "tr:" + hex(trackingLookup), now, SUBJECT_TRACKING, trackingLookup);
            limit(c, startPerRsn, "rsn:" + rsnKey, now, SUBJECT_RSN, crypto.lookup(SUBJECT_RSN, rsnKey));

            String deviceSecret = LinkCodes.newDeviceSecret(random);
            UUID id = UUID.randomUUID();
            String userCode = insertWithFreshCode(c, id, METHOD_OAUTH, deviceSecret, rsn, rsnKey, trackingLookup, null, client, ipLookup, now);
            log.info("Link pairing {} started (sign-in, tracking id {})", id, trackingLookup == null ? "absent" : "present");
            return DataObject.empty().put("pairingId", id.toString()).put("deviceSecret", deviceSecret).put("userCode", userCode)
                    .put("verifyUrl", settings.publicBaseUrl()).put("expiresIn", LinkLimits.PAIRING_LIFETIME.toSeconds())
                    .put("interval", LinkLimits.POLL_INTERVAL_SECONDS);
        });
    }

    /** {@code POST /v1/pair/poll}. The token is in exactly one answer, the first after approval. */
    public DataObject poll(String rawPairingId, String deviceSecret) {
        UUID id = parseUuid(rawPairingId);
        if (id == null || !LinkCodes.looksLikeDeviceSecret(deviceSecret)) return status("expired_or_unknown");
        Instant now = clock.instant();
        return tx(c -> {
            Optional<Pairing> found = store.pairing(c, id, true);
            if (found.isEmpty() || !MessageDigest.isEqual(found.get().deviceSecretHash(), LinkCrypto.sha256(deviceSecret))) return status("expired_or_unknown");
            Pairing p = found.get();
            if (!p.method().equals(METHOD_OAUTH)) return status("expired_or_unknown");
            if (isLive(p) && p.expiresAt().isBefore(now)) {
                store.endPairing(c, p.id(), "expired", null, now);
                return status("expired_or_unknown");
            }
            if (p.lastPollAt() != null && p.lastPollAt().plusSeconds(p.pollInterval()).isAfter(now.plusMillis(500))) {
                int next = Math.min(LinkLimits.MAX_POLL_INTERVAL_SECONDS, p.pollInterval() + LinkLimits.SLOW_DOWN_STEP_SECONDS);
                store.recordPoll(c, p.id(), next, now);
                return status("slow_down").put("interval", next);
            }
            store.recordPoll(c, p.id(), p.pollInterval(), now);
            return switch (p.status()) {
                case "pending" -> status("pending");
                case "denied" -> "refused".equals(p.outcome())
                        ? status("refused").put("message", "This name can't be linked right now. If it is yours, try again later or allow tracking in the plugin.")
                        : status("denied");
                case "approved" -> {
                    Link link = store.link(c, p.linkId()).orElse(null);
                    if (link == null || !(link.status().equals("active") || link.status().equals("disputed"))) {
                        store.endPairing(c, p.id(), "consumed", null, now);
                        yield status("expired_or_unknown");
                    }
                    String token = issueToken(c, link.id(), p.client(), now);
                    store.endPairing(c, p.id(), "consumed", null, now);
                    log.info("Link pairing {} handed its token over (link {})", p.id(), link.id());
                    yield linkedAnswer(c, link, token);
                }
                default -> status("expired_or_unknown");
            };
        });
    }

    /** {@code POST /v1/session}: once per login. Reports the name (renames are followed) and answers whether the link still stands. */
    public DataObject session(String token, String rawRsn, String rawTracking) {
        String rsn = requireRsn(rawRsn);
        String tracking = optionalTracking(rawTracking);
        Instant now = clock.instant();
        return tx(c -> {
            TokenAuth auth = authenticate(c, token, tokenRoutes, now);
            Character ch = auth.character();
            byte[] trackingLookup = tracking == null ? null : crypto.lookup(SUBJECT_TRACKING, tracking);
            String rsnKey = LinkCodes.rsnLookup(rsn);
            if (ch.tracked()) {
                if (trackingLookup != null && !MessageDigest.isEqual(ch.trackingLookup(), trackingLookup)) {
                    store.abuseEvent(c, "character_mismatch", SUBJECT_TRACKING, trackingLookup, null, now);
                    throw new LinkException(409, "character_mismatch", "This link belongs to another character. Link this character on its own.");
                }
                if (trackingLookup == null && !ch.rsnLookup().equals(rsnKey)) {
                    throw new LinkException(409, "character_mismatch", "This link belongs to another character. Link this character on its own.");
                }
                // Same tracking id: a new name is a rename, which the link and its aliases survive.
                if (!ch.rsnDisplay().equals(rsn)) store.renameCharacter(c, ch, rsn, rsnKey, now);
                else store.touchCharacter(c, ch.id(), now);
            } else {
                if (!ch.rsnLookup().equals(rsnKey)) {
                    // Without a tracking id there is no way to tell a rename from another character using this token.
                    throw new LinkException(409, "character_mismatch", "This link belongs to another character. Link this character on its own.");
                }
                if (trackingLookup != null && store.characterByTracking(c, trackingLookup).isEmpty()) store.setTracking(c, ch.id(), trackingLookup);
                if (!ch.rsnDisplay().equals(rsn)) store.renameCharacter(c, ch, rsn, rsnKey, now);
                else store.touchCharacter(c, ch.id(), now);
            }
            store.touchToken(c, auth.tokenId(), now);
            Link link = runVerifiers(c, auth.link(), ch, Map.of("rsn", rsnKey), now);
            boolean disputed = !store.openDisputesFor(c, link.id()).isEmpty() || link.status().equals("disputed");
            return DataObject.empty().put("linked", link.status().equals("active")).put("disputed", disputed).put("verification", link.verification())
                    .put("discordName", discordName(c, link.discordUserId()));
        });
    }

    /** {@code POST /v1/alias}: the per-plugin alias and a signed assertion of it (section 13). */
    public DataObject alias(String token, String rawPlugin) {
        String plugin = LinkCodes.normalisePluginName(rawPlugin);
        if (plugin == null) throw LinkException.bad("bad_name", "Plugin names are 1 to 64 letters, digits, spaces, '.', '_' or '-'.");
        Instant now = clock.instant();
        return tx(c -> {
            TokenAuth auth = authenticate(c, token, aliasRoute, now);
            if (!auth.link().status().equals("active")) {
                throw new LinkException(403, "not_linked", "This character's link is waiting for a review, so plugins can't use it yet.");
            }
            store.touchToken(c, auth.tokenId(), now);
            String alias = crypto.alias(auth.link().id().toString(), plugin);
            boolean disputed = !store.openDisputesFor(c, auth.link().id()).isEmpty();
            String assertion = signer.assertion(plugin, alias, auth.character().rsnLookup(), auth.link().verification(), disputed, now);
            return DataObject.empty().put("alias", alias).put("assertion", assertion);
        });
    }

    /** {@code POST /v1/unlink}: ends this character's link and revokes every token on it. */
    public DataObject unlink(String token) {
        Instant now = clock.instant();
        return tx(c -> {
            TokenAuth auth = authenticate(c, token, tokenRoutes, now);
            endLink(c, auth.link(), "unlinked", "player", now);
            log.info("Link {} unlinked from the plugin", auth.link().id());
            return DataObject.empty().put("unlinked", true);
        });
    }

    /** {@code POST /v1/forget-me}: deletes this character, its links, tokens and name history at once. */
    public DataObject forgetMe(String token, String confirm) {
        if (!"DELETE".equals(confirm)) throw LinkException.bad("confirm_required", "Send \"confirm\": \"DELETE\" to delete this character's data.");
        Instant now = clock.instant();
        return tx(c -> {
            TokenAuth auth = authenticate(c, token, tokenRoutes, now);
            endLink(c, auth.link(), "unlinked", "player", now);
            store.deleteCharacter(c, auth.character().id());
            log.info("A character's link data was deleted from the plugin (link {})", auth.link().id());
            return DataObject.empty().put("deleted", true);
        });
    }

    /**
     * The plugins whose own server is this bot, so the link service may tell it who an alias belongs to (RUNESYNC-DESIGN.md section 3).
     * Every other plugin only ever gets an alias. Names compare in lower case.
     */
    static final java.util.Set<String> FIRST_PARTY_PLUGINS = java.util.Set.of("runesync");

    /**
     * {@code POST /resolve}, first-party only and never reachable through the website: the Discord account and RSN behind a valid assertion
     * made for a first-party plugin. The assertion must be signed by this service, unexpired, for a plugin in {@link #FIRST_PARTY_PLUGINS}, and
     * its alias must still match an active link on its RSN; otherwise 401 {@code invalid_assertion}, which says nothing about why.
     */
    public DataObject resolve(String assertion) {
        Instant now = clock.instant();
        DataObject claims = signer.claimsIfValid(assertion, now);
        String plugin = claims == null ? null : claims.getString("plugin", null);
        String alias = claims == null ? null : claims.getString("alias", null);
        String rsnLookup = claims == null ? null : claims.getString("rsn", null);
        if (plugin == null || alias == null || rsnLookup == null || !FIRST_PARTY_PLUGINS.contains(plugin)) {
            throw new LinkException(401, "invalid_assertion", "That assertion can't be used.");
        }
        return tx(c -> {
            for (TokenAuth holder : store.activeHoldersOfName(c, rsnLookup)) {
                if (!java.security.MessageDigest.isEqual(crypto.alias(holder.link().id().toString(), plugin).getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                        alias.getBytes(java.nio.charset.StandardCharsets.US_ASCII))) continue;
                DiscordUser user = store.discordUser(c, holder.link().discordUserId()).orElse(null);
                if (user == null) break;
                boolean disputed = !store.openDisputesFor(c, holder.link().id()).isEmpty();
                return DataObject.empty().put("discordId", crypto.decrypt(user.enc(), user.keyVersion())).put("rsn", holder.character().rsnDisplay())
                        .put("verification", holder.link().verification()).put("disputed", disputed);
            }
            throw new LinkException(401, "invalid_assertion", "That assertion can't be used.");
        });
    }

    /** {@code GET /v1/keys}. */
    public DataObject keys() {
        return signer.keySet();
    }

    // =====================================================================================================================
    // Sign-in page routes (section 5.2). The website has verified the Discord sign-in; it passes the id and an opaque browser session.
    // =====================================================================================================================

    /** {@code POST /web/code}: checks a typed code and binds its pairing to this browser. The RSN is not revealed until after sign-in. */
    public DataObject webCode(String typedCode, String browserSession, String discordIdIfSignedIn, String ip) {
        byte[] sessionHash = requireSession(browserSession);
        Instant now = clock.instant();
        byte[] ipLookup = crypto.lookup(SUBJECT_IP, ipOrUnknown(ip));
        byte[] discordLookup = discordIdIfSignedIn == null ? null : crypto.lookup(SUBJECT_DISCORD, requireDiscordId(discordIdIfSignedIn));
        return tx(c -> {
            refuseIfBlocked(c, now, SUBJECT_IP, ipLookup);
            if (discordLookup != null) {
                refuseIfBlocked(c, now, SUBJECT_DISCORD, discordLookup);
                if (!wrongCodesPerDiscord.wouldAllow("d:" + hex(discordLookup), now.toEpochMilli())) throw LinkException.slowDown(30 * 60);
            }
            limit(c, codePerIpMinute, "ip:" + hex(ipLookup), now, SUBJECT_IP, ipLookup);
            limit(c, codePerIpHour, "ip:" + hex(ipLookup), now, SUBJECT_IP, ipLookup);

            String code = LinkCodes.normaliseCode(typedCode);
            Optional<Pairing> found = code == null ? Optional.empty() : store.pendingPairingByCode(c, crypto.lookup("code", code), METHOD_OAUTH);
            Pairing p = found.orElse(null);
            if (p != null && p.expiresAt().isBefore(now)) {
                store.endPairing(c, p.id(), "expired", null, now);
                throw new LinkException(410, "expired", "That code has expired. Start again in the plugin to get a new one.");
            }
            if (p == null || (p.browserSessionHash() != null && !MessageDigest.isEqual(p.browserSessionHash(), sessionHash))) {
                store.abuseEvent(c, "wrong_code", SUBJECT_IP, ipLookup, null, now);
                if (discordLookup != null && wrongCodesPerDiscord.tryAcquire("d:" + hex(discordLookup), now.toEpochMilli()) == 0
                        && !wrongCodesPerDiscord.wouldAllow("d:" + hex(discordLookup), now.toEpochMilli())) {
                    lockout(c, SUBJECT_DISCORD, discordLookup, "wrong_codes", now);
                }
                if (p != null) {
                    throw new LinkException(409, "code_in_use", "That code is already being used in another browser. Start again in the plugin.");
                }
                throw new LinkException(404, "wrong_code", "That code isn't right. Check the code in the plugin and try again.");
            }
            store.bindSession(c, p.id(), sessionHash);
            return DataObject.empty().put("accepted", true).put("expiresIn", Math.max(0, p.expiresAt().getEpochSecond() - now.getEpochSecond()));
        });
    }

    /** {@code GET /web/pairing}: what the confirm screen shows, only once signed in. */
    public DataObject webPairing(String browserSession, String discordId) {
        byte[] sessionHash = requireSession(browserSession);
        requireDiscordId(discordId);
        Instant now = clock.instant();
        return tx(c -> {
            Pairing p = store.pairingBySession(c, sessionHash, false).orElse(null);
            if (p == null || p.expiresAt().isBefore(now)) {
                throw new LinkException(410, "expired", "That code has expired. Start again in the plugin to get a new one.");
            }
            return DataObject.empty().put("rsn", p.rsnDisplay()).put("expiresIn", Math.max(0, p.expiresAt().getEpochSecond() - now.getEpochSecond()))
                    .put("tracked", p.trackingLookup() != null);
        });
    }

    /**
     * {@code POST /web/confirm}: the player pressed Link or "This isn't me". Answers {@code {"result": "linked" | "disputed" | "denied"}},
     * or refuses with {@code name_unavailable} / {@code too_many_links}.
     */
    public DataObject webConfirm(String browserSession, String discordId, String discordName, String decision) {
        byte[] sessionHash = requireSession(browserSession);
        String id = requireDiscordId(discordId);
        if (!"link".equals(decision) && !"deny".equals(decision)) throw LinkException.bad("bad_decision", "Choose Link or This isn't me.");
        Instant now = clock.instant();
        byte[] discordLookup = crypto.lookup(SUBJECT_DISCORD, id);
        return tx(c -> {
            refuseIfBlocked(c, now, SUBJECT_DISCORD, discordLookup);
            Pairing p = store.pairingBySession(c, sessionHash, true).orElse(null);
            if (p == null || p.expiresAt().isBefore(now)) {
                throw new LinkException(410, "expired", "That code has expired. Start again in the plugin to get a new one.");
            }
            if (decision.equals("deny")) {
                store.endPairing(c, p.id(), "denied", "not_me", now);
                store.abuseEvent(c, "denied", SUBJECT_IP, p.ipLookup(), null, now);
                if (p.trackingLookup() != null) store.abuseEvent(c, "denied", SUBJECT_TRACKING, p.trackingLookup(), null, now);
                log.info("Link pairing {} denied by the Discord account it was shown to", p.id());
                return DataObject.empty().put("result", "denied");
            }
            UUID userId = upsertDiscord(c, id, discordName, now);
            Outcome outcome = applyLink(c, p, userId, METHOD_OAUTH, now);
            return DataObject.empty().put("result", outcome.result()).put("rsn", p.rsnDisplay());
        });
    }

    /** {@code GET /web/links}: the signed-in Discord's links, current and recently ended. */
    public DataObject webLinks(String discordId) {
        String id = requireDiscordId(discordId);
        Instant now = clock.instant();
        byte[] lookup = crypto.lookup(SUBJECT_DISCORD, id);
        if (webAccount.tryAcquire("d:" + hex(lookup), now.toEpochMilli()) > 0) throw LinkException.slowDown(10);
        return tx(c -> {
            DataArray links = DataArray.empty();
            Optional<DiscordUser> user = store.discordUser(c, lookup);
            if (user.isPresent()) {
                for (TokenAuth row : store.linksOf(c, user.get().id(), now.minus(LinkLimits.ENDED_LINK_KEPT))) {
                    Link l = row.link();
                    links.add(DataObject.empty().put("linkId", l.id().toString()).put("rsn", row.character().rsnDisplay()).put("status", l.status())
                            .put("method", l.method()).put("verification", l.verification()).put("linkedAt", l.createdAt().toString())
                            .put("lastSeenAt", row.character().lastSeenAt().toString()).put("endedAt", l.endedAt() == null ? null : l.endedAt().toString())
                            .put("tracked", row.character().tracked()).put("devices", store.activeTokens(c, l.id()))
                            .put("previousNames", DataArray.fromCollection(store.history(c, row.character().id()))));
                }
            }
            return DataObject.empty().put("links", links);
        });
    }

    /** {@code POST /web/unlink}. */
    public DataObject webUnlink(String discordId, String rawLinkId) {
        String id = requireDiscordId(discordId);
        UUID linkId = parseUuid(rawLinkId);
        Instant now = clock.instant();
        byte[] lookup = crypto.lookup(SUBJECT_DISCORD, id);
        if (webAccount.tryAcquire("d:" + hex(lookup), now.toEpochMilli()) > 0) throw LinkException.slowDown(10);
        tx(c -> {
            DiscordUser user = store.discordUser(c, lookup).orElse(null);
            Link link = linkId == null || user == null ? null : store.link(c, linkId).orElse(null);
            if (link == null || !link.discordUserId().equals(user.id()) || !(link.status().equals("active") || link.status().equals("disputed"))) {
                throw new LinkException(404, "not_found", "You have no current link like that.");
            }
            endLink(c, link, "unlinked", "player", now);
            log.info("Link {} unlinked on the web", link.id());
            return null;
        });
        return webLinks(discordId);
    }

    /** {@code POST /web/delete-my-data}: everything about this Discord account, except an active block (kept as a fingerprint until it ends). */
    public DataObject webDeleteMyData(String discordId, String confirm) {
        if (!"DELETE".equals(confirm)) throw LinkException.bad("confirm_required", "Type DELETE to confirm.");
        String id = requireDiscordId(discordId);
        Instant now = clock.instant();
        byte[] lookup = crypto.lookup(SUBJECT_DISCORD, id);
        return tx(c -> {
            DiscordUser user = store.discordUser(c, lookup).orElse(null);
            if (user != null) {
                for (TokenAuth row : store.linksOf(c, user.id(), Instant.EPOCH)) {
                    if (row.link().status().equals("active") || row.link().status().equals("disputed")) endLink(c, row.link(), "unlinked", "player", now);
                }
                for (UUID characterId : store.charactersOnlyLinkedTo(c, user.id())) store.deleteCharacter(c, characterId);
                store.deleteDiscordUser(c, user.id());
            }
            store.deleteAbuseEventsFor(c, SUBJECT_DISCORD, lookup);
            log.info("A Discord account's link data was deleted on the web");
            return DataObject.empty().put("deleted", true);
        });
    }

    // =====================================================================================================================
    // Retention (section 7)
    // =====================================================================================================================

    /** Expires, revokes and deletes what has outlived its time. Run hourly. */
    public int[] sweep() {
        Instant now = clock.instant();
        int[] counts = tx(c -> store.sweep(c, now));
        log.info("Link retention sweep: expired {} pairings, deleted {} pairings, {} links, revoked {} unused tokens, deleted {} events, {} blocks, {} characters",
                counts[0], counts[1], counts[2], counts[3], counts[4], counts[5], counts[6]);
        return counts;
    }

    // =====================================================================================================================
    // Link rules (sections 9.2 and 11)
    // =====================================================================================================================

    record Outcome(String result, Link link) {}

    /**
     * Makes or reuses the link a pairing asked for, under a lock on the name so two claims can't race. Marks the pairing approved (or
     * denied with outcome {@code refused}).
     */
    Outcome applyLink(Connection c, Pairing p, UUID discordUserId, String method, Instant now) throws SQLException {
        lockName(c, p.rsnLookup());
        Character ch = p.trackingLookup() != null ? store.characterByTracking(c, p.trackingLookup()).orElse(null) : store.untrackedCharacter(c, p.rsnLookup()).orElse(null);

        // Same character, same Discord: reuse the link (same id, so the same aliases); the poll hands out a new token.
        Link mine = ch == null ? null : store.currentLink(c, ch.id(), discordUserId).orElse(null);
        if (mine != null) {
            store.renameCharacter(c, ch, p.rsnDisplay(), p.rsnLookup(), now);
            store.approvePairing(c, p.id(), discordUserId, mine.id(), "reused");
            log.info("Link pairing {} approved: reusing link {}", p.id(), mine.id());
            return new Outcome(mine.status().equals("disputed") ? "disputed" : "linked", mine);
        }
        if (store.countCurrentLinks(c, discordUserId) >= LinkLimits.MAX_ACTIVE_LINKS_PER_DISCORD) {
            throw new LinkException(409, "too_many_links", "This Discord account already has " + LinkLimits.MAX_ACTIVE_LINKS_PER_DISCORD
                    + " linked characters. Unlink one first.");
        }

        // First decide, then write: a refusal must leave nothing half-done behind (refusals still commit their abuse record).
        List<TokenAuth> holders = store.activeHoldersOfName(c, p.rsnLookup()).stream()
                .filter(h -> ch == null || !h.character().id().equals(ch.id())).toList();
        boolean tracked = p.trackingLookup() != null;
        Instant recycledBefore = now.minus(LinkLimits.RECYCLE_AFTER);
        for (TokenAuth h : holders) {
            // A claim without a tracking id can never take a name that is linked with one; nor can an implicit claim displace a verified link.
            if (h.character().tracked() && (!tracked || (!h.character().lastSeenAt().isBefore(recycledBefore)
                    && Verification.of(h.link().verification()).strongerThan(Verification.IMPLICIT)))) {
                return refuse(c, p, now);
            }
        }

        Link incumbentToDispute = null;
        for (TokenAuth h : holders) {
            if (!h.character().tracked()) {
                // A tracked claim takes over a tracking-less link for the same name (after the confirm step, which this is).
                endLink(c, h.link(), "superseded", "tracked_claim", now);
            } else if (h.character().lastSeenAt().isBefore(recycledBefore)) {
                // The old character hasn't reported this name for 30 days: it is clearly no longer theirs.
                endLink(c, h.link(), "released", "recycled", now);
                log.info("Link {} released: its name was claimed by another character after 30 quiet days", h.link().id());
            } else {
                // Reported within 30 days by another tracked character: neither side can be proven yet, so hold the newcomer for review
                // instead of locking them out (a squatter with an invented tracking id could otherwise keep the real owner out forever).
                incumbentToDispute = h.link();
            }
        }

        Character character = ch;
        if (character == null) {
            character = new Character(UUID.randomUUID(), p.trackingLookup(), p.rsnDisplay(), p.rsnLookup(), now, now);
            store.insertCharacter(c, character);
        } else {
            store.renameCharacter(c, character, p.rsnDisplay(), p.rsnLookup(), now);
        }

        Link activeOther = store.activeLink(c, character.id()).orElse(null);
        if (activeOther != null) {
            if (character.tracked()) {
                // Only someone running the plugin as this character can present its tracking id: the owner moving to another Discord.
                endLink(c, activeOther, "superseded", "moved_discord", now);
            } else {
                // The tracking-less character is shared by every claim without a tracking id, so another Discord's link there is a dispute.
                incumbentToDispute = activeOther;
            }
        }

        String status = incumbentToDispute == null ? "active" : "disputed";
        Link link = new Link(UUID.randomUUID(), character.id(), discordUserId, status, method, Verification.IMPLICIT.key(), now, null);
        store.insertLink(c, link);
        if (incumbentToDispute != null) {
            UUID disputeId = UUID.randomUUID();
            store.insertDispute(c, disputeId, p.rsnLookup(), p.rsnDisplay(), incumbentToDispute.id(), link.id(), now);
            store.abuseEvent(c, "dispute_opened", SUBJECT_RSN, crypto.lookup(SUBJECT_RSN, p.rsnLookup()),
                    DataObject.empty().put("disputeId", disputeId.toString()).toString(), now);
            log.info("Link pairing {}: name already claimed by link {}, new link {} held in dispute {}", p.id(), incumbentToDispute.id(), link.id(), disputeId);
        } else {
            log.info("Link pairing {} approved: new link {}", p.id(), link.id());
        }
        store.approvePairing(c, p.id(), discordUserId, link.id(), status.equals("active") ? "linked" : "disputed");
        link = runVerifiers(c, link, character, null, now);
        return new Outcome(status.equals("active") ? "linked" : "disputed", link);
    }

    private Outcome refuse(Connection c, Pairing p, Instant now) throws SQLException {
        store.endPairing(c, p.id(), "denied", "refused", now);
        store.abuseEvent(c, "name_refused", SUBJECT_RSN, crypto.lookup(SUBJECT_RSN, p.rsnLookup()), null, now);
        log.info("Link pairing {} refused: the name is held by a tracked or verified link", p.id());
        throw new LinkException(409, "name_unavailable", "This name can't be linked right now. If it is yours, try again later or use the plugin with tracking allowed.");
    }

    /**
     * Ends a link and settles any dispute it is in: if the incumbent leaves, the waiting claimant becomes the link; if the claimant
     * leaves, the dispute is withdrawn.
     */
    void endLink(Connection c, Link link, String status, String why, Instant now) throws SQLException {
        store.endLink(c, link.id(), status, now);
        for (LinkStore.Dispute d : store.openDisputesFor(c, link.id())) {
            if (link.id().equals(d.incumbentLinkId()) && d.claimantLinkId() != null) {
                Link claimant = store.link(c, d.claimantLinkId()).orElse(null);
                if (claimant != null && claimant.status().equals("disputed") && store.activeLink(c, claimant.characterId()).isEmpty()) {
                    store.setLinkStatus(c, claimant.id(), "active");
                }
                store.resolveDispute(c, d.id(), "resolved_claimant", "system", "incumbent " + why, now);
            } else {
                store.resolveDispute(c, d.id(), "withdrawn", "system", "claimant " + why, now);
            }
        }
    }

    /** Runs every {@link LinkVerifier}; the strongest result above the current level is stored. Returns the link as it now stands. */
    private Link runVerifiers(Connection c, Link link, Character ch, Map<String, Object> report, Instant now) throws SQLException {
        Verification current = Verification.of(link.verification());
        VerificationResult best = null;
        for (LinkVerifier v : verifiers) {
            try {
                Optional<VerificationResult> r = v.check(new LinkContext(link.id().toString(), ch.id().toString(), ch.rsnLookup(), ch.tracked(), current, report));
                if (r.isPresent() && r.get().level().strongerThan(best == null ? current : best.level())) best = r.get();
            } catch (RuntimeException e) {
                log.warn("Link verifier {} failed on link {}: {}", v.method(), link.id(), e.getClass().getSimpleName());
            }
        }
        if (best == null) return link;
        String evidence = DataObject.empty().put("method", best.method()).put("evidence", best.evidence()).toString();
        store.setVerification(c, link.id(), best.level().key(), evidence, now);
        log.info("Link {} raised to {} by {}", link.id(), best.level().key(), best.method());
        return new Link(link.id(), link.characterId(), link.discordUserId(), link.status(), link.method(), best.level().key(), link.createdAt(), link.endedAt());
    }

    // =====================================================================================================================
    // Helpers
    // =====================================================================================================================

    String insertWithFreshCode(Connection c, UUID id, String method, String deviceSecret, String rsn, String rsnKey, byte[] trackingLookup,
                               byte[] targetDiscordLookup, String client, byte[] ipLookup, Instant now) throws SQLException {
        for (int attempt = 0; attempt < 5; attempt++) {
            String userCode = LinkCodes.newUserCode(random);
            Pairing p = new Pairing(id, method, crypto.lookup("code", LinkCodes.normaliseCode(userCode)), LinkCrypto.sha256(deviceSecret), rsn, rsnKey,
                    trackingLookup, targetDiscordLookup, null, null, "pending", null, 0, LinkLimits.POLL_INTERVAL_SECONDS, null, null,
                    cleanClient(client), ipLookup, now, now.plus(LinkLimits.PAIRING_LIFETIME));
            var savepoint = c.setSavepoint();
            try {
                store.insertPairing(c, p);
                c.releaseSavepoint(savepoint);
                return userCode;
            } catch (SQLException e) {
                // 23505: another pending pairing drew the same code (about 1 in 10^11). Draw again.
                if (!"23505".equals(e.getSQLState())) throw e;
                c.rollback(savepoint);
            }
        }
        throw new IllegalStateException("Couldn't draw a free code");
    }

    private String issueToken(Connection c, UUID linkId, String client, Instant now) throws SQLException {
        String token = LinkCodes.newToken(random);
        store.insertToken(c, UUID.randomUUID(), linkId, LinkCrypto.sha256(token), client, now);
        store.trimTokens(c, linkId, LinkLimits.MAX_ACTIVE_TOKENS_PER_LINK, now);
        return token;
    }

    DataObject linkedAnswer(Connection c, Link link, String token) throws SQLException {
        boolean disputed = link.status().equals("disputed");
        DataObject answer = status("linked").put("token", token).put("verification", link.verification()).put("disputed", disputed);
        if (disputed) answer.put("message", "Another character has claimed this name recently. Your link is kept and waits for a review.");
        return answer;
    }

    private TokenAuth authenticate(Connection c, String token, WindowLimiter limiter, Instant now) throws SQLException {
        if (!LinkCodes.looksLikeToken(token)) throw new LinkException(401, "token_revoked", "This link has ended. Link again from the plugin.");
        byte[] hash = LinkCrypto.sha256(token);
        int wait = limiter.tryAcquire("t:" + hex(hash), now.toEpochMilli());
        if (wait > 0) throw LinkException.slowDown(wait);
        return store.tokenAuth(c, hash).orElseThrow(() -> new LinkException(401, "token_revoked", "This link has ended. Link again from the plugin."));
    }

    UUID upsertDiscord(Connection c, String discordId, String discordName, Instant now) throws SQLException {
        byte[] lookup = crypto.lookup(SUBJECT_DISCORD, discordId);
        String name = discordName == null || discordName.isBlank() ? null : discordName.strip();
        if (name != null && name.length() > 64) name = name.substring(0, 64);
        return store.upsertDiscordUser(c, lookup, crypto.encrypt(discordId), LinkCrypto.CURRENT_KEY_VERSION, name == null ? null : crypto.encrypt(name), now);
    }

    private String discordName(Connection c, UUID discordUserId) throws SQLException {
        DiscordUser user = store.discordUser(c, discordUserId).orElse(null);
        return user == null || user.nameEnc() == null ? null : crypto.decrypt(user.nameEnc(), user.keyVersion());
    }

    /** Refuses with a 429 (indistinguishable from a rate limit) while the subject is blocked. */
    void refuseIfBlocked(Connection c, Instant now, String kind, byte[] lookup) throws SQLException {
        if (store.blocked(c, kind, lookup, now)) throw LinkException.slowDown(60 * 60);
    }

    /** Counts one action; when the window is full, records a lockout (three in a day block the subject) and refuses. */
    private void limit(Connection c, WindowLimiter limiter, String key, Instant now, String kind, byte[] lookup) throws SQLException {
        int wait = limiter.tryAcquire(key, now.toEpochMilli());
        if (wait == 0) return;
        store.abuseEvent(c, "rate_limited", kind, lookup, null, now);
        if (store.countEvents(c, "rate_limited", kind, lookup, now.minusSeconds(wait + 1L)) == 1) lockout(c, kind, lookup, "rate_limited", now);
        throw LinkException.slowDown(wait);
    }

    /** One lockout of a subject; the third within 24 hours blocks it automatically for 24 hours (section 8, repeat offenders). */
    void lockout(Connection c, String kind, byte[] lookup, String why, Instant now) throws SQLException {
        store.abuseEvent(c, "lockout", kind, lookup, DataObject.empty().put("why", why).toString(), now);
        int recent = store.countEvents(c, "lockout", kind, lookup, now.minus(LinkLimits.LOCKOUT_COUNT_WINDOW));
        if (recent >= LinkLimits.LOCKOUTS_BEFORE_BLOCK && !store.blocked(c, kind, lookup, now)) {
            store.insertBlock(c, kind, lookup, "automatic: " + recent + " lockouts in 24 hours", now.plus(LinkLimits.AUTO_BLOCK), "auto", now);
            store.abuseEvent(c, "auto_block", kind, lookup, null, now);
            log.warn("Link service blocked a {} ({}) for 24 hours after {} lockouts", kind, LinkCrypto.logPrefix(lookup), recent);
        }
    }

    private void lockName(Connection c, String rsnLookup) throws SQLException {
        try (var s = c.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?, 1414))")) {
            s.setString(1, settings.schema() + "|" + rsnLookup);
            s.execute();
        }
    }

    private static boolean isLive(Pairing p) {
        return p.status().equals("pending") || p.status().equals("approved");
    }

    private static String requireRsn(String raw) {
        String rsn = LinkCodes.normaliseRsn(raw);
        if (rsn == null) throw LinkException.bad("bad_rsn", "That isn't a RuneScape name (1 to 12 letters, digits, spaces, - or _).");
        return rsn;
    }

    private static String optionalTracking(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String id = LinkCodes.normaliseTrackingId(raw);
        if (id == null) throw LinkException.bad("bad_tracking_id", "The tracking id must be a number.");
        return id;
    }

    static String requireDiscordId(String raw) {
        if (raw == null || !raw.strip().matches("\\d{17,20}")) throw new LinkException(401, "not_signed_in", "Sign in with Discord first.");
        return raw.strip();
    }

    private static byte[] requireSession(String browserSession) {
        if (browserSession == null || browserSession.length() < 32 || browserSession.length() > 128) {
            throw new LinkException(401, "no_session", "Your session has ended. Open the page again.");
        }
        return LinkCrypto.sha256("session|" + browserSession);
    }

    private static String ipOrUnknown(String ip) {
        return ip == null || ip.isBlank() ? "unknown" : ip.strip();
    }

    private static String cleanClient(String client) {
        if (client == null) return null;
        String c = client.strip().replaceAll("[^A-Za-z0-9 ._-]", "");
        return c.length() > 60 ? c.substring(0, 60) : c;
    }

    static UUID parseUuid(String raw) {
        try {
            return raw == null ? null : UUID.fromString(raw.strip());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static DataObject status(String status) {
        return DataObject.empty().put("status", status);
    }

    private static String hex(byte[] bytes) {
        return java.util.HexFormat.of().formatHex(bytes);
    }

    // ---------- transactions ----------

    @FunctionalInterface
    interface SqlWork<T> {
        T run(Connection c) throws SQLException;
    }

    /**
     * Runs work in one transaction. A {@link LinkException} still commits what was written before it (abuse events, lockouts, a pairing
     * marked refused), because those are the record of the refusal; any other failure rolls everything back.
     */
    <T> T tx(SqlWork<T> work) {
        try (Connection c = database.connection()) {
            c.setAutoCommit(false);
            try {
                T result = work.run(c);
                c.commit();
                return result;
            } catch (LinkException e) {
                c.commit();
                throw e;
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Link database error " + e.getSQLState(), e);
        }
    }

    // ---------- for the bridge and the abuse view ----------

    LinkStore store() {
        return store;
    }

    LinkCrypto crypto() {
        return crypto;
    }

    Clock clock() {
        return clock;
    }

    LinkDmSender dm() {
        return dm;
    }

    SecureRandom random() {
        return random;
    }

    LinkSettings settings() {
        return settings;
    }
}
