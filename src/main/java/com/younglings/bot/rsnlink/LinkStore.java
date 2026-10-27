package com.younglings.bot.rsnlink;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Row-level SQL for the link database. No rules live here (those are in {@link LinkService}); every method takes the connection so the
 * service can group several into one transaction. {@code {s}} in a statement is the schema. Times always come from the service's clock,
 * never {@code now()}, so tests can move time.
 */
final class LinkStore {
    private final String schema;

    String schema() {
        return schema;
    }

    LinkStore(String schema) {
        this.schema = schema;
    }

    // ---------- rows ----------

    record Pairing(UUID id, String method, byte[] codeLookup, byte[] deviceSecretHash, String rsnDisplay, String rsnLookup, byte[] trackingLookup,
                   byte[] targetDiscordLookup, UUID discordUserId, byte[] browserSessionHash, String status, String outcome, int attempts,
                   int pollInterval, Instant lastPollAt, UUID linkId, String client, byte[] ipLookup, Instant createdAt, Instant expiresAt) {}

    record Character(UUID id, byte[] trackingLookup, String rsnDisplay, String rsnLookup, Instant lastSeenAt, Instant createdAt) {
        boolean tracked() {
            return trackingLookup != null;
        }
    }

    record Link(UUID id, UUID characterId, UUID discordUserId, String status, String method, String verification, Instant createdAt, Instant endedAt) {}

    record DiscordUser(UUID id, byte[] lookup, byte[] enc, short keyVersion, byte[] nameEnc, Instant dmOptoutUntil) {}

    /** A token that is still valid, with what it belongs to. */
    record TokenAuth(UUID tokenId, Link link, Character character) {}

    record Dispute(UUID id, String rsnLookup, String rsnDisplay, UUID incumbentLinkId, UUID claimantLinkId, String status, Instant openedAt,
                   Instant resolvedAt, String resolvedBy, String note) {}

    // ---------- pairings ----------

    void insertPairing(Connection c, Pairing p) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(sql("""
                INSERT INTO {s}.pairing (id, method, code_lookup, device_secret_hash, rsn_display, rsn_lookup, tracking_lookup, target_discord_lookup,
                                         status, attempts, poll_interval, client, ip_lookup, created_at, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?, ?, ?, ?)"""))) {
            s.setObject(1, p.id());
            s.setString(2, p.method());
            s.setBytes(3, p.codeLookup());
            s.setBytes(4, p.deviceSecretHash());
            s.setString(5, p.rsnDisplay());
            s.setString(6, p.rsnLookup());
            s.setBytes(7, p.trackingLookup());
            s.setBytes(8, p.targetDiscordLookup());
            s.setString(9, p.status());
            s.setInt(10, p.pollInterval());
            s.setString(11, p.client());
            s.setBytes(12, p.ipLookup());
            s.setTimestamp(13, ts(p.createdAt()));
            s.setTimestamp(14, ts(p.expiresAt()));
            s.executeUpdate();
        }
    }

    Optional<Pairing> pairing(Connection c, UUID id, boolean forUpdate) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(sql("SELECT * FROM {s}.pairing WHERE id = ?" + (forUpdate ? " FOR UPDATE" : "")))) {
            s.setObject(1, id);
            try (ResultSet rs = s.executeQuery()) {
                return rs.next() ? Optional.of(pairing(rs)) : Optional.empty();
            }
        }
    }

    Optional<Pairing> pendingPairingByCode(Connection c, byte[] codeLookup, String method) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(sql("SELECT * FROM {s}.pairing WHERE code_lookup = ? AND status = 'pending' AND method = ? FOR UPDATE"))) {
            s.setBytes(1, codeLookup);
            s.setString(2, method);
            try (ResultSet rs = s.executeQuery()) {
                return rs.next() ? Optional.of(pairing(rs)) : Optional.empty();
            }
        }
    }

    Optional<Pairing> pairingBySession(Connection c, byte[] sessionHash, boolean forUpdate) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(sql("SELECT * FROM {s}.pairing WHERE browser_session_hash = ? AND status = 'pending' ORDER BY created_at DESC LIMIT 1"
                + (forUpdate ? " FOR UPDATE" : "")))) {
            s.setBytes(1, sessionHash);
            try (ResultSet rs = s.executeQuery()) {
                return rs.next() ? Optional.of(pairing(rs)) : Optional.empty();
            }
        }
    }

    void bindSession(Connection c, UUID pairingId, byte[] sessionHash) throws SQLException {
        update(c, "UPDATE {s}.pairing SET browser_session_hash = ? WHERE id = ?", sessionHash, pairingId);
    }

    /** Ends a pairing: {@code status} is denied, consumed, expired or locked; {@code outcome} an optional detail (refused, disputed). */
    void endPairing(Connection c, UUID id, String status, String outcome, Instant now) throws SQLException {
        update(c, "UPDATE {s}.pairing SET status = ?, outcome = coalesce(?, outcome), ended_at = ? WHERE id = ?", status, outcome, ts(now), id);
    }

    void approvePairing(Connection c, UUID id, UUID discordUserId, UUID linkId, String outcome) throws SQLException {
        update(c, "UPDATE {s}.pairing SET status = 'approved', discord_user_id = ?, link_id = ?, outcome = ? WHERE id = ?", discordUserId, linkId, outcome, id);
    }

    void recordPoll(Connection c, UUID id, int interval, Instant now) throws SQLException {
        update(c, "UPDATE {s}.pairing SET poll_interval = ?, last_poll_at = ? WHERE id = ?", interval, ts(now), id);
    }

    int addAttempt(Connection c, UUID id) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(sql("UPDATE {s}.pairing SET attempts = attempts + 1 WHERE id = ? RETURNING attempts"))) {
            s.setObject(1, id);
            try (ResultSet rs = s.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    // ---------- characters ----------

    Optional<Character> characterByTracking(Connection c, byte[] trackingLookup) throws SQLException {
        return oneCharacter(c, "SELECT * FROM {s}.game_character WHERE tracking_lookup = ? FOR UPDATE", trackingLookup);
    }

    /** The one tracking-less character row for a name (claims without a tracking id share it). */
    Optional<Character> untrackedCharacter(Connection c, String rsnLookup) throws SQLException {
        return oneCharacter(c, "SELECT * FROM {s}.game_character WHERE tracking_lookup IS NULL AND rsn_lookup = ? ORDER BY created_at LIMIT 1 FOR UPDATE", rsnLookup);
    }

    Optional<Character> character(Connection c, UUID id) throws SQLException {
        return oneCharacter(c, "SELECT * FROM {s}.game_character WHERE id = ?", id);
    }

    void insertCharacter(Connection c, Character ch) throws SQLException {
        update(c, "INSERT INTO {s}.game_character (id, tracking_lookup, rsn_display, rsn_lookup, last_seen_at, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                ch.id(), ch.trackingLookup(), ch.rsnDisplay(), ch.rsnLookup(), ts(ch.lastSeenAt()), ts(ch.createdAt()));
    }

    /** A rename (or a first sighting with new spelling): keeps the old name in the history the owner can see. */
    void renameCharacter(Connection c, Character ch, String newDisplay, String newLookup, Instant now) throws SQLException {
        if (!ch.rsnDisplay().equals(newDisplay)) {
            update(c, "INSERT INTO {s}.rsn_history (character_id, rsn_display, seen_from, seen_to) VALUES (?, ?, ?, ?)",
                    ch.id(), ch.rsnDisplay(), ts(ch.createdAt()), ts(now));
        }
        update(c, "UPDATE {s}.game_character SET rsn_display = ?, rsn_lookup = ?, last_seen_at = ? WHERE id = ?", newDisplay, newLookup, ts(now), ch.id());
    }

    void touchCharacter(Connection c, UUID id, Instant now) throws SQLException {
        update(c, "UPDATE {s}.game_character SET last_seen_at = ? WHERE id = ?", ts(now), id);
    }

    void setTracking(Connection c, UUID id, byte[] trackingLookup) throws SQLException {
        update(c, "UPDATE {s}.game_character SET tracking_lookup = ? WHERE id = ?", trackingLookup, id);
    }

    boolean deleteCharacter(Connection c, UUID id) throws SQLException {
        return update(c, "DELETE FROM {s}.game_character WHERE id = ?", id) > 0;
    }

    List<String> history(Connection c, UUID characterId) throws SQLException {
        List<String> names = new ArrayList<>();
        try (PreparedStatement s = c.prepareStatement(sql("SELECT rsn_display FROM {s}.rsn_history WHERE character_id = ? ORDER BY seen_to DESC LIMIT 20"))) {
            s.setObject(1, characterId);
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) names.add(rs.getString(1));
            }
        }
        return names;
    }

    // ---------- links ----------

    /** Active links on any character currently called {@code rsnLookup}, with their characters. */
    List<TokenAuth> activeHoldersOfName(Connection c, String rsnLookup) throws SQLException {
        List<TokenAuth> out = new ArrayList<>();
        try (PreparedStatement s = c.prepareStatement(sql("""
                SELECT l.*, ch.id AS ch_id, ch.tracking_lookup, ch.rsn_display, ch.rsn_lookup, ch.last_seen_at, ch.created_at AS ch_created
                FROM {s}.link l JOIN {s}.game_character ch ON ch.id = l.character_id
                WHERE ch.rsn_lookup = ? AND l.status = 'active' FOR UPDATE OF l"""))) {
            s.setString(1, rsnLookup);
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) out.add(new TokenAuth(null, link(rs), joinedCharacter(rs)));
            }
        }
        return out;
    }

    /** Every link on the name that is active or waiting in a dispute: anyone with a current claim to it. */
    List<TokenAuth> currentHoldersOfName(Connection c, String rsnLookup) throws SQLException {
        List<TokenAuth> out = new ArrayList<>();
        try (PreparedStatement s = c.prepareStatement(sql("""
                SELECT l.*, ch.id AS ch_id, ch.tracking_lookup, ch.rsn_display, ch.rsn_lookup, ch.last_seen_at, ch.created_at AS ch_created
                FROM {s}.link l JOIN {s}.game_character ch ON ch.id = l.character_id
                WHERE ch.rsn_lookup = ? AND l.status IN ('active', 'disputed') FOR UPDATE OF l"""))) {
            s.setString(1, rsnLookup);
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) out.add(new TokenAuth(null, link(rs), joinedCharacter(rs)));
            }
        }
        return out;
    }

    /** The character's current link that is active or waiting in a dispute, if any. */
    Optional<Link> currentLink(Connection c, UUID characterId, UUID discordUserId) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(sql("SELECT * FROM {s}.link WHERE character_id = ? AND status IN ('active', 'disputed')"
                + (discordUserId == null ? "" : " AND discord_user_id = ?") + " ORDER BY created_at DESC LIMIT 1 FOR UPDATE"))) {
            s.setObject(1, characterId);
            if (discordUserId != null) s.setObject(2, discordUserId);
            try (ResultSet rs = s.executeQuery()) {
                return rs.next() ? Optional.of(link(rs)) : Optional.empty();
            }
        }
    }

    Optional<Link> activeLink(Connection c, UUID characterId) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(sql("SELECT * FROM {s}.link WHERE character_id = ? AND status = 'active' FOR UPDATE"))) {
            s.setObject(1, characterId);
            try (ResultSet rs = s.executeQuery()) {
                return rs.next() ? Optional.of(link(rs)) : Optional.empty();
            }
        }
    }

    Optional<Link> link(Connection c, UUID id) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(sql("SELECT * FROM {s}.link WHERE id = ? FOR UPDATE"))) {
            s.setObject(1, id);
            try (ResultSet rs = s.executeQuery()) {
                return rs.next() ? Optional.of(link(rs)) : Optional.empty();
            }
        }
    }

    void insertLink(Connection c, Link l) throws SQLException {
        update(c, "INSERT INTO {s}.link (id, character_id, discord_user_id, status, method, verification, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                l.id(), l.characterId(), l.discordUserId(), l.status(), l.method(), l.verification(), ts(l.createdAt()));
    }

    /** Ends a link ({@code unlinked}, {@code released} or {@code superseded}) and revokes every token on it. */
    void endLink(Connection c, UUID id, String status, Instant now) throws SQLException {
        update(c, "UPDATE {s}.link SET status = ?, ended_at = ? WHERE id = ?", status, ts(now), id);
        update(c, "UPDATE {s}.link_token SET revoked_at = ? WHERE link_id = ? AND revoked_at IS NULL", ts(now), id);
    }

    void setLinkStatus(Connection c, UUID id, String status) throws SQLException {
        update(c, "UPDATE {s}.link SET status = ? WHERE id = ?", status, id);
    }

    void setVerification(Connection c, UUID linkId, String level, String evidence, Instant now) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(sql("UPDATE {s}.link SET verification = ?, verification_data = ?::jsonb, verified_at = ? WHERE id = ?"))) {
            s.setString(1, level);
            s.setString(2, evidence);
            s.setTimestamp(3, ts(now));
            s.setObject(4, linkId);
            s.executeUpdate();
        }
    }

    int countCurrentLinks(Connection c, UUID discordUserId) throws SQLException {
        return count(c, "SELECT count(*) FROM {s}.link WHERE discord_user_id = ? AND status IN ('active', 'disputed')", discordUserId);
    }

    /** A Discord user's links that are current or ended within {@code since}, newest first, with their characters. */
    List<TokenAuth> linksOf(Connection c, UUID discordUserId, Instant since) throws SQLException {
        List<TokenAuth> out = new ArrayList<>();
        try (PreparedStatement s = c.prepareStatement(sql("""
                SELECT l.*, ch.id AS ch_id, ch.tracking_lookup, ch.rsn_display, ch.rsn_lookup, ch.last_seen_at, ch.created_at AS ch_created
                FROM {s}.link l JOIN {s}.game_character ch ON ch.id = l.character_id
                WHERE l.discord_user_id = ? AND (l.status IN ('active', 'disputed') OR l.ended_at >= ?)
                ORDER BY l.created_at DESC"""))) {
            s.setObject(1, discordUserId);
            s.setTimestamp(2, ts(since));
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) out.add(new TokenAuth(null, link(rs), joinedCharacter(rs)));
            }
        }
        return out;
    }

    // ---------- tokens ----------

    void insertToken(Connection c, UUID id, UUID linkId, byte[] tokenHash, String client, Instant now) throws SQLException {
        update(c, "INSERT INTO {s}.link_token (id, link_id, token_hash, client, created_at) VALUES (?, ?, ?, ?, ?)", id, linkId, tokenHash, client, ts(now));
    }

    /** Revokes the oldest active tokens of a link so at most {@code keep} remain. */
    int trimTokens(Connection c, UUID linkId, int keep, Instant now) throws SQLException {
        return update(c, """
                UPDATE {s}.link_token SET revoked_at = ? WHERE id IN (
                    SELECT id FROM {s}.link_token WHERE link_id = ? AND revoked_at IS NULL ORDER BY created_at DESC, id OFFSET ?)""",
                ts(now), linkId, keep);
    }

    Optional<TokenAuth> tokenAuth(Connection c, byte[] tokenHash) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(sql("""
                SELECT t.id AS token_id, l.*, ch.id AS ch_id, ch.tracking_lookup, ch.rsn_display, ch.rsn_lookup, ch.last_seen_at, ch.created_at AS ch_created
                FROM {s}.link_token t JOIN {s}.link l ON l.id = t.link_id JOIN {s}.game_character ch ON ch.id = l.character_id
                WHERE t.token_hash = ? AND t.revoked_at IS NULL AND l.status IN ('active', 'disputed')"""))) {
            s.setBytes(1, tokenHash);
            try (ResultSet rs = s.executeQuery()) {
                return rs.next() ? Optional.of(new TokenAuth(rs.getObject("token_id", UUID.class), link(rs), joinedCharacter(rs))) : Optional.empty();
            }
        }
    }

    void touchToken(Connection c, UUID tokenId, Instant now) throws SQLException {
        update(c, "UPDATE {s}.link_token SET last_used_at = ? WHERE id = ?", ts(now), tokenId);
    }

    int activeTokens(Connection c, UUID linkId) throws SQLException {
        return count(c, "SELECT count(*) FROM {s}.link_token WHERE link_id = ? AND revoked_at IS NULL", linkId);
    }

    // ---------- Discord users ----------

    Optional<DiscordUser> discordUser(Connection c, byte[] lookup) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(sql("SELECT * FROM {s}.discord_user WHERE discord_lookup = ?"))) {
            s.setBytes(1, lookup);
            try (ResultSet rs = s.executeQuery()) {
                return rs.next() ? Optional.of(discordUser(rs)) : Optional.empty();
            }
        }
    }

    Optional<DiscordUser> discordUser(Connection c, UUID id) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(sql("SELECT * FROM {s}.discord_user WHERE id = ?"))) {
            s.setObject(1, id);
            try (ResultSet rs = s.executeQuery()) {
                return rs.next() ? Optional.of(discordUser(rs)) : Optional.empty();
            }
        }
    }

    /** Creates the row or refreshes the stored (encrypted) name; returns the row's id. */
    UUID upsertDiscordUser(Connection c, byte[] lookup, byte[] enc, short keyVersion, byte[] nameEnc, Instant now) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(sql("""
                INSERT INTO {s}.discord_user (id, discord_lookup, discord_enc, key_version, name_enc, created_at) VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (discord_lookup) DO UPDATE SET name_enc = coalesce(EXCLUDED.name_enc, {s}.discord_user.name_enc)
                RETURNING id"""))) {
            s.setObject(1, UUID.randomUUID());
            s.setBytes(2, lookup);
            s.setBytes(3, enc);
            s.setShort(4, keyVersion);
            s.setBytes(5, nameEnc);
            s.setTimestamp(6, ts(now));
            try (ResultSet rs = s.executeQuery()) {
                rs.next();
                return rs.getObject(1, UUID.class);
            }
        }
    }

    void setDmOptOut(Connection c, UUID id, Instant until) throws SQLException {
        update(c, "UPDATE {s}.discord_user SET dm_optout_until = ? WHERE id = ?", ts(until), id);
    }

    boolean deleteDiscordUser(Connection c, UUID id) throws SQLException {
        return update(c, "DELETE FROM {s}.discord_user WHERE id = ?", id) > 0;
    }

    /**
     * Whether a Discord user other than this one has a link on the character: only a current one (active or disputed) when
     * {@code currentOnly}, otherwise any, ended ones included.
     */
    boolean otherDiscordHasLinkOn(Connection c, UUID characterId, UUID discordUserId, boolean currentOnly) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(sql("SELECT 1 FROM {s}.link WHERE character_id = ? AND discord_user_id <> ?"
                + (currentOnly ? " AND status IN ('active', 'disputed')" : "") + " LIMIT 1"))) {
            s.setObject(1, characterId);
            s.setObject(2, discordUserId);
            try (ResultSet rs = s.executeQuery()) {
                return rs.next();
            }
        }
    }

    /** Deletes this Discord user's links on one character (their tokens go with them; disputes and pairings keep a null reference). */
    int deleteLinksOf(Connection c, UUID characterId, UUID discordUserId) throws SQLException {
        return update(c, "DELETE FROM {s}.link WHERE character_id = ? AND discord_user_id = ?", characterId, discordUserId);
    }

    /** Characters whose every link belongs to this Discord user (deleted with the user's data: nobody else has a claim on them). */
    List<UUID> charactersOnlyLinkedTo(Connection c, UUID discordUserId) throws SQLException {
        List<UUID> ids = new ArrayList<>();
        try (PreparedStatement s = c.prepareStatement(sql("""
                SELECT DISTINCT l.character_id FROM {s}.link l WHERE l.discord_user_id = ?
                AND NOT EXISTS (SELECT 1 FROM {s}.link o WHERE o.character_id = l.character_id AND o.discord_user_id <> ?)"""))) {
            s.setObject(1, discordUserId);
            s.setObject(2, discordUserId);
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) ids.add(rs.getObject(1, UUID.class));
            }
        }
        return ids;
    }

    // ---------- disputes ----------

    void insertDispute(Connection c, UUID id, String rsnLookup, String rsnDisplay, UUID incumbent, UUID claimant, Instant now) throws SQLException {
        update(c, "INSERT INTO {s}.dispute (id, rsn_lookup, rsn_display, incumbent_link_id, claimant_link_id, status, opened_at) VALUES (?, ?, ?, ?, ?, 'open', ?)",
                id, rsnLookup, rsnDisplay, incumbent, claimant, ts(now));
    }

    /** Open disputes a link is part of, on either side. */
    List<Dispute> openDisputesFor(Connection c, UUID linkId) throws SQLException {
        return disputes(c, "SELECT * FROM {s}.dispute WHERE status = 'open' AND (incumbent_link_id = ? OR claimant_link_id = ?) FOR UPDATE", linkId, linkId);
    }

    List<Dispute> disputes(Connection c, String status, int limit) throws SQLException {
        return disputes(c, "SELECT * FROM {s}.dispute WHERE (?::text IS NULL OR status = ?) ORDER BY opened_at DESC LIMIT ?", status, status, limit);
    }

    Optional<Dispute> dispute(Connection c, UUID id) throws SQLException {
        List<Dispute> found = disputes(c, "SELECT * FROM {s}.dispute WHERE id = ? FOR UPDATE", id);
        return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
    }

    void resolveDispute(Connection c, UUID id, String status, String by, String note, Instant now) throws SQLException {
        update(c, "UPDATE {s}.dispute SET status = ?, resolved_by = ?, note = ?, resolved_at = ? WHERE id = ?", status, by, note, ts(now), id);
    }

    // ---------- abuse, blocks, audit ----------

    void abuseEvent(Connection c, String kind, String subjectKind, byte[] subjectLookup, String detailJson, Instant now) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(sql("INSERT INTO {s}.abuse_event (at, kind, subject_kind, subject_lookup, detail) VALUES (?, ?, ?, ?, ?::jsonb)"))) {
            s.setTimestamp(1, ts(now));
            s.setString(2, kind);
            s.setString(3, subjectKind);
            s.setBytes(4, subjectLookup);
            s.setString(5, detailJson);
            s.executeUpdate();
        }
    }

    int countEvents(Connection c, String kind, String subjectKind, byte[] subjectLookup, Instant since) throws SQLException {
        return count(c, "SELECT count(*) FROM {s}.abuse_event WHERE kind = ? AND subject_kind = ? AND subject_lookup = ? AND at >= ?",
                kind, subjectKind, subjectLookup, ts(since));
    }

    boolean blocked(Connection c, String subjectKind, byte[] subjectLookup, Instant now) throws SQLException {
        return count(c, "SELECT count(*) FROM {s}.link_block WHERE subject_kind = ? AND subject_lookup = ? AND until > ?", subjectKind, subjectLookup, ts(now)) > 0;
    }

    long insertBlock(Connection c, String subjectKind, byte[] subjectLookup, String reason, Instant until, String by, Instant now) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(sql("""
                INSERT INTO {s}.link_block (subject_kind, subject_lookup, reason, until, created_by, created_at) VALUES (?, ?, ?, ?, ?, ?) RETURNING id"""))) {
            s.setString(1, subjectKind);
            s.setBytes(2, subjectLookup);
            s.setString(3, reason);
            s.setTimestamp(4, ts(until));
            s.setString(5, by);
            s.setTimestamp(6, ts(now));
            try (ResultSet rs = s.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    void audit(Connection c, String actor, String action, String target, Instant now) throws SQLException {
        update(c, "INSERT INTO {s}.admin_audit (at, actor, action, target) VALUES (?, ?, ?, ?)", ts(now), actor, action, target);
    }

    int deleteAbuseEventsFor(Connection c, String subjectKind, byte[] subjectLookup) throws SQLException {
        return update(c, "DELETE FROM {s}.abuse_event WHERE subject_kind = ? AND subject_lookup = ?", subjectKind, subjectLookup);
    }

    // ---------- DM fallback (T-115) ----------

    void setDmTarget(Connection c, UUID pairingId, byte[] lookup, byte[] enc, short keyVersion) throws SQLException {
        update(c, "UPDATE {s}.pairing SET target_discord_lookup = ?, target_discord_enc = ?, target_key_version = ? WHERE id = ?", lookup, enc, keyVersion, pairingId);
    }

    /** The sealed Discord id a DM pairing was sent to and its key version, or null when it was never sent. */
    Object[] dmTarget(Connection c, UUID pairingId) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(sql("SELECT target_discord_enc, target_key_version FROM {s}.pairing WHERE id = ?"))) {
            s.setObject(1, pairingId);
            try (ResultSet rs = s.executeQuery()) {
                if (!rs.next() || rs.getBytes(1) == null) return null;
                return new Object[]{rs.getBytes(1), rs.getShort(2)};
            }
        }
    }

    void dmSend(Connection c, byte[] targetLookup, String outcome, Instant now) throws SQLException {
        update(c, "INSERT INTO {s}.dm_send (target_lookup, at, outcome) VALUES (?, ?, ?)", targetLookup, ts(now), outcome);
    }

    int countDmSent(Connection c, byte[] targetLookup, Instant since) throws SQLException {
        return count(c, "SELECT count(*) FROM {s}.dm_send WHERE target_lookup = ? AND outcome = 'sent' AND at >= ?", targetLookup, ts(since));
    }

    int countDmSentTotal(Connection c, Instant since) throws SQLException {
        return count(c, "SELECT count(*) FROM {s}.dm_send WHERE outcome = 'sent' AND at >= ?", ts(since));
    }

    // ---------- retention (section 7) ----------

    /** Runs the clean-up and returns how many rows each step touched, in order. */
    int[] sweep(Connection c, Instant now) throws SQLException {
        int expired = update(c, "UPDATE {s}.pairing SET status = 'expired', ended_at = ? WHERE status IN ('pending', 'approved') AND expires_at < ?", ts(now), ts(now));
        int pairings = update(c, "DELETE FROM {s}.pairing WHERE ended_at < ?", ts(now.minus(LinkLimits.ENDED_PAIRING_KEPT)));
        int links = update(c, "DELETE FROM {s}.link WHERE status NOT IN ('active', 'disputed') AND ended_at < ?", ts(now.minus(LinkLimits.ENDED_LINK_KEPT)));
        int stale = update(c, "UPDATE {s}.link_token SET revoked_at = ? WHERE revoked_at IS NULL AND coalesce(last_used_at, created_at) < ?",
                ts(now), ts(now.minus(LinkLimits.TOKEN_UNUSED_REVOKE)));
        int events = update(c, "DELETE FROM {s}.abuse_event WHERE at < ?", ts(now.minus(LinkLimits.ABUSE_EVENT_KEPT)));
        int blocks = update(c, "DELETE FROM {s}.link_block WHERE until < ?", ts(now));
        int orphans = update(c, "DELETE FROM {s}.game_character ch WHERE NOT EXISTS (SELECT 1 FROM {s}.link l WHERE l.character_id = ch.id) AND ch.last_seen_at < ?",
                ts(now.minus(LinkLimits.ENDED_LINK_KEPT)));
        int dms = update(c, "DELETE FROM {s}.dm_send WHERE at < ?", ts(now.minus(java.time.Duration.ofDays(2))));
        int windows = SharedWindowLimiter.sweep(c, schema, now.toEpochMilli());
        return new int[]{expired, pairings, links, stale, events, blocks, orphans, dms, windows};
    }

    // ---------- helpers ----------

    String sql(String template) {
        return template.replace("{s}", schema);
    }

    private int update(Connection c, String template, Object... args) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(sql(template))) {
            bind(s, args);
            return s.executeUpdate();
        }
    }

    private int count(Connection c, String template, Object... args) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(sql(template))) {
            bind(s, args);
            try (ResultSet rs = s.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private Optional<Character> oneCharacter(Connection c, String template, Object arg) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(sql(template))) {
            bind(s, arg);
            try (ResultSet rs = s.executeQuery()) {
                return rs.next() ? Optional.of(new Character(rs.getObject("id", UUID.class), rs.getBytes("tracking_lookup"), rs.getString("rsn_display"),
                        rs.getString("rsn_lookup"), instant(rs, "last_seen_at"), instant(rs, "created_at"))) : Optional.empty();
            }
        }
    }

    private List<Dispute> disputes(Connection c, String template, Object... args) throws SQLException {
        List<Dispute> out = new ArrayList<>();
        try (PreparedStatement s = c.prepareStatement(sql(template))) {
            bind(s, args);
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) {
                    out.add(new Dispute(rs.getObject("id", UUID.class), rs.getString("rsn_lookup"), rs.getString("rsn_display"),
                            rs.getObject("incumbent_link_id", UUID.class), rs.getObject("claimant_link_id", UUID.class), rs.getString("status"),
                            instant(rs, "opened_at"), instant(rs, "resolved_at"), rs.getString("resolved_by"), rs.getString("note")));
                }
            }
        }
        return out;
    }

    private static void bind(PreparedStatement s, Object... args) throws SQLException {
        for (int i = 0; i < args.length; i++) {
            Object a = args[i];
            if (a == null) s.setNull(i + 1, Types.NULL);
            else if (a instanceof byte[] b) s.setBytes(i + 1, b);
            else s.setObject(i + 1, a);
        }
    }

    private static Pairing pairing(ResultSet rs) throws SQLException {
        return new Pairing(rs.getObject("id", UUID.class), rs.getString("method"), rs.getBytes("code_lookup"), rs.getBytes("device_secret_hash"),
                rs.getString("rsn_display"), rs.getString("rsn_lookup"), rs.getBytes("tracking_lookup"), rs.getBytes("target_discord_lookup"),
                rs.getObject("discord_user_id", UUID.class), rs.getBytes("browser_session_hash"), rs.getString("status"), rs.getString("outcome"),
                rs.getInt("attempts"), rs.getInt("poll_interval"), instant(rs, "last_poll_at"), rs.getObject("link_id", UUID.class),
                rs.getString("client"), rs.getBytes("ip_lookup"), instant(rs, "created_at"), instant(rs, "expires_at"));
    }

    private static Link link(ResultSet rs) throws SQLException {
        return new Link(rs.getObject("id", UUID.class), rs.getObject("character_id", UUID.class), rs.getObject("discord_user_id", UUID.class),
                rs.getString("status"), rs.getString("method"), rs.getString("verification"), instant(rs, "created_at"), instant(rs, "ended_at"));
    }

    private static Character joinedCharacter(ResultSet rs) throws SQLException {
        return new Character(rs.getObject("ch_id", UUID.class), rs.getBytes("tracking_lookup"), rs.getString("rsn_display"), rs.getString("rsn_lookup"),
                instant(rs, "last_seen_at"), instant(rs, "ch_created"));
    }

    private static DiscordUser discordUser(ResultSet rs) throws SQLException {
        return new DiscordUser(rs.getObject("id", UUID.class), rs.getBytes("discord_lookup"), rs.getBytes("discord_enc"), rs.getShort("key_version"),
                rs.getBytes("name_enc"), instant(rs, "dm_optout_until"));
    }

    static Timestamp ts(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }
}
