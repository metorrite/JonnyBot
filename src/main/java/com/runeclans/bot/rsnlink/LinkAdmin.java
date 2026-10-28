package com.runeclans.bot.rsnlink;

import com.runeclans.bot.rsnlink.LinkStore.Dispute;
import com.runeclans.bot.rsnlink.LinkStore.Link;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;

/**
 * The owner's abuse view (ticket T-118, LINK-SYSTEM.md sections 5.3, 8 and 16): counts and recent abuse events, repeat offenders, the DM
 * breaker, blocks, disputed names, and finding or removing a link. Every action is written to {@code admin_audit}. Callers have already
 * checked the asker is the bot's owner.
 * <p>
 * What it never shows: codes, tokens, device secrets or IPs. Subjects (IP, Discord, tracking id, RSN) appear only as a short fingerprint
 * prefix, so two rows about the same subject can be matched up without revealing it. A link's Discord id is decrypted only when the owner
 * asks for that one link, and the reveal is audited.
 */
public final class LinkAdmin {
    private static final Logger log = LoggerFactory.getLogger(LinkAdmin.class);
    private static final int MAX_REASON = 200;
    private static final java.util.Set<String> SUBJECTS = java.util.Set.of(LinkService.SUBJECT_IP, LinkService.SUBJECT_DISCORD, LinkService.SUBJECT_TRACKING, LinkService.SUBJECT_RSN);

    private final LinkService service;
    private final LinkStore store;
    private final LinkCrypto crypto;

    public LinkAdmin(LinkService service) {
        this.service = service;
        this.store = service.store();
        this.crypto = service.crypto();
    }

    /** Counts at a glance: events by kind (24 hours and 7 days), links, disputes, blocks, pairings and the DM breaker. */
    public DataObject overview() {
        Instant now = service.clock().instant();
        return service.tx(c -> {
            DataObject kinds = DataObject.empty();
            try (PreparedStatement s = c.prepareStatement(store.sql("""
                    SELECT kind, count(*) FILTER (WHERE at >= ?) AS day, count(*) AS week FROM {s}.abuse_event WHERE at >= ? GROUP BY kind ORDER BY kind"""))) {
                s.setTimestamp(1, Timestamp.from(now.minus(Duration.ofDays(1))));
                s.setTimestamp(2, Timestamp.from(now.minus(Duration.ofDays(7))));
                try (ResultSet rs = s.executeQuery()) {
                    while (rs.next()) kinds.put(rs.getString(1), DataObject.empty().put("day", rs.getInt(2)).put("week", rs.getInt(3)));
                }
            }
            return DataObject.empty().put("events", kinds)
                    .put("activeLinks", count(c, "SELECT count(*) FROM {s}.link WHERE status = 'active'"))
                    .put("disputedLinks", count(c, "SELECT count(*) FROM {s}.link WHERE status = 'disputed'"))
                    .put("openDisputes", count(c, "SELECT count(*) FROM {s}.dispute WHERE status = 'open'"))
                    .put("activeBlocks", countAt(c, "SELECT count(*) FROM {s}.link_block WHERE until > ?", now))
                    .put("pendingPairings", countAt(c, "SELECT count(*) FROM {s}.pairing WHERE status = 'pending' AND expires_at > ?", now))
                    .put("dmsLastHour", countAt(c, "SELECT count(*) FROM {s}.dm_send WHERE outcome = 'sent' AND at >= ?", now.minus(LinkLimits.DM_TOTAL.window())))
                    .put("dmHourlyCap", LinkLimits.DM_TOTAL.max())
                    .put("dmBreakerOpen", store.countDmSentTotal(c, now.minus(LinkLimits.DM_TOTAL.window())) >= LinkLimits.DM_TOTAL.max())
                    .put("verifiedLinks", count(c, "SELECT count(*) FROM {s}.link WHERE status IN ('active', 'disputed') AND verification <> 'implicit'"))
                    .put("verify", service.verificationOverview())
                    .put("generatedAt", now.toString());
        });
    }

    /** The latest abuse events, newest first, optionally of one kind. */
    public DataObject events(String kind, int limit) {
        int n = Math.max(1, Math.min(200, limit));
        return service.tx(c -> {
            DataArray rows = DataArray.empty();
            try (PreparedStatement s = c.prepareStatement(store.sql("""
                    SELECT at, kind, subject_kind, subject_lookup, detail::text FROM {s}.abuse_event WHERE (?::text IS NULL OR kind = ?) ORDER BY at DESC, id DESC LIMIT ?"""))) {
                s.setString(1, kind);
                s.setString(2, kind);
                s.setInt(3, n);
                try (ResultSet rs = s.executeQuery()) {
                    while (rs.next()) {
                        rows.add(DataObject.empty().put("at", rs.getTimestamp(1).toInstant().toString()).put("kind", rs.getString(2))
                                .put("subjectKind", rs.getString(3)).put("subject", prefix(rs.getBytes(4))).put("detail", rs.getString(5)));
                    }
                }
            }
            return DataObject.empty().put("events", rows);
        });
    }

    /** Subjects locked out in the last 24 hours, most first, with whether they are blocked now. */
    public DataObject offenders() {
        Instant now = service.clock().instant();
        return service.tx(c -> {
            DataArray rows = DataArray.empty();
            try (PreparedStatement s = c.prepareStatement(store.sql("""
                    SELECT e.subject_kind, e.subject_lookup, count(*), max(e.at),
                           EXISTS (SELECT 1 FROM {s}.link_block b WHERE b.subject_kind = e.subject_kind AND b.subject_lookup = e.subject_lookup AND b.until > ?)
                    FROM {s}.abuse_event e WHERE e.kind = 'lockout' AND e.at >= ? AND e.subject_lookup IS NOT NULL
                    GROUP BY e.subject_kind, e.subject_lookup ORDER BY count(*) DESC, max(e.at) DESC LIMIT 100"""))) {
                s.setTimestamp(1, Timestamp.from(now));
                s.setTimestamp(2, Timestamp.from(now.minus(LinkLimits.LOCKOUT_COUNT_WINDOW)));
                try (ResultSet rs = s.executeQuery()) {
                    while (rs.next()) {
                        rows.add(DataObject.empty().put("subjectKind", rs.getString(1)).put("subject", prefix(rs.getBytes(2))).put("lockouts", rs.getInt(3))
                                .put("lastAt", rs.getTimestamp(4).toInstant().toString()).put("blocked", rs.getBoolean(5)));
                    }
                }
            }
            return DataObject.empty().put("offenders", rows).put("lockoutsBeforeBlock", LinkLimits.LOCKOUTS_BEFORE_BLOCK);
        });
    }

    /** Blocks that are still in force. */
    public DataObject blocks() {
        Instant now = service.clock().instant();
        return service.tx(c -> {
            DataArray rows = DataArray.empty();
            try (PreparedStatement s = c.prepareStatement(store.sql("""
                    SELECT id, subject_kind, subject_lookup, reason, until, created_by, created_at FROM {s}.link_block WHERE until > ? ORDER BY created_at DESC LIMIT 200"""))) {
                s.setTimestamp(1, Timestamp.from(now));
                try (ResultSet rs = s.executeQuery()) {
                    while (rs.next()) {
                        rows.add(DataObject.empty().put("id", Long.toString(rs.getLong(1))).put("subjectKind", rs.getString(2)).put("subject", prefix(rs.getBytes(3)))
                                .put("reason", rs.getString(4)).put("until", rs.getTimestamp(5).toInstant().toString()).put("createdBy", rs.getString(6))
                                .put("createdAt", rs.getTimestamp(7).toInstant().toString()));
                    }
                }
            }
            return DataObject.empty().put("blocks", rows);
        });
    }

    /**
     * Blocks a subject the owner typed in (an IP, a Discord id, a tracking id or an RSN) for {@code hours}. The value is turned into its
     * fingerprint at once and never stored or logged as typed.
     */
    public DataObject block(String subjectKind, String value, String reason, int hours, String actor) {
        String kind = subjectKind == null ? "" : subjectKind.strip().toLowerCase(Locale.ROOT);
        if (!SUBJECTS.contains(kind)) throw LinkException.bad("bad_subject", "Choose IP, Discord id, tracking id or RSN.");
        String normal = normaliseSubject(kind, value);
        if (hours < 1 || hours > 24 * 365) throw LinkException.bad("bad_hours", "Block for 1 hour to 365 days.");
        String why = reason == null || reason.isBlank() ? "owner" : reason.strip();
        if (why.length() > MAX_REASON) why = why.substring(0, MAX_REASON);
        Instant now = service.clock().instant();
        byte[] lookup = crypto.lookup(kind, normal);
        String finalWhy = why;
        long id = service.tx(c -> {
            long made = store.insertBlock(c, kind, lookup, finalWhy, now.plus(Duration.ofHours(hours)), "owner:" + actor, now);
            store.audit(c, actor, "block", kind + " " + prefix(lookup) + " for " + hours + "h", now);
            return made;
        });
        log.info("Owner {} blocked a {} ({}) for {} hours", actor, kind, prefix(lookup), hours);
        return blocks().put("blockedId", Long.toString(id));
    }

    public DataObject unblock(String rawId, String actor) {
        long id;
        try {
            id = Long.parseLong(rawId);
        } catch (NumberFormatException e) {
            throw new LinkException(404, "not_found", "No block like that.");
        }
        Instant now = service.clock().instant();
        service.tx(c -> {
            try (PreparedStatement s = c.prepareStatement(store.sql("UPDATE {s}.link_block SET until = ? WHERE id = ? AND until > ?"))) {
                s.setTimestamp(1, Timestamp.from(now));
                s.setLong(2, id);
                s.setTimestamp(3, Timestamp.from(now));
                if (s.executeUpdate() == 0) throw new LinkException(404, "not_found", "No active block like that.");
            }
            store.audit(c, actor, "unblock", "block " + id, now);
            return null;
        });
        return blocks();
    }

    /** Names claimed by two characters (section 11), open ones first, with both sides as the owner needs them to decide. */
    public DataObject disputes(String status) {
        return service.tx(c -> {
            DataArray rows = DataArray.empty();
            for (Dispute d : store.disputes(c, status == null || status.isBlank() ? null : status, 100)) {
                rows.add(DataObject.empty().put("id", d.id().toString()).put("rsn", d.rsnDisplay()).put("status", d.status())
                        .put("openedAt", d.openedAt().toString()).put("resolvedAt", d.resolvedAt() == null ? null : d.resolvedAt().toString())
                        .put("resolvedBy", d.resolvedBy()).put("note", d.note())
                        .put("incumbent", side(c, d.incumbentLinkId())).put("claimant", side(c, d.claimantLinkId())));
            }
            return DataObject.empty().put("disputes", rows);
        });
    }

    /** Settles an open dispute: {@code winner} keeps the name, the other link is superseded. */
    public DataObject resolveDispute(String rawId, String winner, String note, String actor) {
        UUID id = LinkService.parseUuid(rawId);
        if (id == null) throw new LinkException(404, "not_found", "No dispute like that.");
        if (!"incumbent".equals(winner) && !"claimant".equals(winner)) throw LinkException.bad("bad_winner", "Choose who keeps the name.");
        Instant now = service.clock().instant();
        service.tx(c -> {
            Dispute d = store.dispute(c, id).filter(x -> x.status().equals("open")).orElseThrow(() -> new LinkException(404, "not_found", "No open dispute like that."));
            UUID keep = winner.equals("incumbent") ? d.incumbentLinkId() : d.claimantLinkId();
            UUID lose = winner.equals("incumbent") ? d.claimantLinkId() : d.incumbentLinkId();
            // Close it first, so ending the loser's link doesn't settle it a second way.
            store.resolveDispute(c, d.id(), "resolved_" + winner, "owner:" + actor, note == null ? null : note.strip(), now);
            Link loser = lose == null ? null : store.link(c, lose).orElse(null);
            if (loser != null && (loser.status().equals("active") || loser.status().equals("disputed"))) service.endLink(c, loser, "superseded", "dispute", now);
            Link kept = keep == null ? null : store.link(c, keep).orElse(null);
            if (kept != null && kept.status().equals("disputed") && store.activeLink(c, kept.characterId()).isEmpty()) store.setLinkStatus(c, kept.id(), "active");
            store.audit(c, actor, "resolve_dispute", d.id() + " -> " + winner, now);
            return null;
        });
        log.info("Owner {} settled dispute {} for the {}", actor, id, winner);
        return disputes(null);
    }

    /** Current and recent links on a name, for finding a bad one. */
    public DataObject findLinks(String rawRsn) {
        String rsn = LinkCodes.normaliseRsn(rawRsn);
        if (rsn == null) throw LinkException.bad("bad_rsn", "That isn't a RuneScape name.");
        String lookup = LinkCodes.rsnLookup(rsn);
        return service.tx(c -> {
            DataArray rows = DataArray.empty();
            try (PreparedStatement s = c.prepareStatement(store.sql("""
                    SELECT l.id FROM {s}.link l JOIN {s}.game_character ch ON ch.id = l.character_id
                    WHERE ch.rsn_lookup = ? ORDER BY l.created_at DESC LIMIT 50"""))) {
                s.setString(1, lookup);
                try (ResultSet rs = s.executeQuery()) {
                    while (rs.next()) rows.add(side(c, rs.getObject(1, UUID.class)));
                }
            }
            return DataObject.empty().put("links", rows);
        });
    }

    /** Removes a link (its tokens are revoked; the player sees it as ended on their links page). */
    public DataObject removeLink(String rawId, String reason, String actor) {
        UUID id = LinkService.parseUuid(rawId);
        if (id == null) throw new LinkException(404, "not_found", "No link like that.");
        Instant now = service.clock().instant();
        DataObject removed = service.tx(c -> {
            Link link = store.link(c, id).orElseThrow(() -> new LinkException(404, "not_found", "No link like that."));
            if (!link.status().equals("active") && !link.status().equals("disputed")) throw new LinkException(409, "already_ended", "That link has already ended.");
            service.endLink(c, link, "removed", "owner", now);
            String why = reason == null ? "" : reason.strip();
            store.audit(c, actor, "remove_link", link.id() + (why.isEmpty() ? "" : " (" + (why.length() > MAX_REASON ? why.substring(0, MAX_REASON) : why) + ")"), now);
            return side(c, link.id());
        });
        log.info("Owner {} removed link {}", actor, id);
        return DataObject.empty().put("link", removed);
    }

    /** The Discord id (and username, if known) behind one link, decrypted for the owner only. Audited. */
    public DataObject revealDiscord(String rawId, String actor) {
        UUID id = LinkService.parseUuid(rawId);
        if (id == null) throw new LinkException(404, "not_found", "No link like that.");
        Instant now = service.clock().instant();
        return service.tx(c -> {
            Link link = store.link(c, id).orElseThrow(() -> new LinkException(404, "not_found", "No link like that."));
            LinkStore.DiscordUser user = store.discordUser(c, link.discordUserId()).orElseThrow(() -> new LinkException(404, "not_found", "No link like that."));
            store.audit(c, actor, "reveal_discord", link.id().toString(), now);
            log.info("Owner {} revealed the Discord account of link {}", actor, link.id());
            return DataObject.empty().put("linkId", link.id().toString()).put("discordId", crypto.decrypt(user.enc(), user.keyVersion()))
                    .put("discordName", user.nameEnc() == null ? null : crypto.decrypt(user.nameEnc(), user.keyVersion()));
        });
    }

    /** The owner actions taken here, newest first. */
    public DataObject audit(int limit) {
        int n = Math.max(1, Math.min(200, limit));
        return service.tx(c -> {
            DataArray rows = DataArray.empty();
            try (PreparedStatement s = c.prepareStatement(store.sql("SELECT at, actor, action, target FROM {s}.admin_audit ORDER BY at DESC, id DESC LIMIT ?"))) {
                s.setInt(1, n);
                try (ResultSet rs = s.executeQuery()) {
                    while (rs.next()) {
                        rows.add(DataObject.empty().put("at", rs.getTimestamp(1).toInstant().toString()).put("actor", rs.getString(2))
                                .put("action", rs.getString(3)).put("target", rs.getString(4)));
                    }
                }
            }
            return DataObject.empty().put("audit", rows);
        });
    }

    // ---------- helpers ----------

    /** One link as the owner sees it: no Discord id (that needs {@link #revealDiscord}), no token. */
    private DataObject side(Connection c, UUID linkId) throws SQLException {
        if (linkId == null) return null;
        try (PreparedStatement s = c.prepareStatement(store.sql("""
                SELECT l.id, l.status, l.method, l.verification, l.created_at, l.ended_at, ch.rsn_display, ch.last_seen_at, ch.tracking_lookup IS NOT NULL,
                       (SELECT count(*) FROM {s}.link_token t WHERE t.link_id = l.id AND t.revoked_at IS NULL), d.discord_lookup
                FROM {s}.link l JOIN {s}.game_character ch ON ch.id = l.character_id JOIN {s}.discord_user d ON d.id = l.discord_user_id WHERE l.id = ?"""))) {
            s.setObject(1, linkId);
            try (ResultSet rs = s.executeQuery()) {
                if (!rs.next()) return DataObject.empty().put("linkId", linkId.toString()).put("status", "deleted");
                return DataObject.empty().put("linkId", rs.getObject(1, UUID.class).toString()).put("status", rs.getString(2)).put("method", rs.getString(3))
                        .put("verification", rs.getString(4)).put("linkedAt", rs.getTimestamp(5).toInstant().toString())
                        .put("endedAt", rs.getTimestamp(6) == null ? null : rs.getTimestamp(6).toInstant().toString()).put("rsn", rs.getString(7))
                        .put("lastSeenAt", rs.getTimestamp(8).toInstant().toString()).put("tracked", rs.getBoolean(9)).put("devices", rs.getInt(10))
                        .put("discord", prefix(rs.getBytes(11))).put("verify", service.verificationDetail(linkId));
            }
        }
    }

    /** The form a typed subject is fingerprinted in, matching how the service fingerprints it. */
    static String normaliseSubject(String kind, String value) {
        String v = value == null ? "" : value.strip();
        String normal = switch (kind) {
            case LinkService.SUBJECT_DISCORD -> v.matches("\\d{17,20}") ? v : null;
            case LinkService.SUBJECT_TRACKING -> LinkCodes.normaliseTrackingId(v);
            case LinkService.SUBJECT_RSN -> LinkCodes.normaliseRsn(v) == null ? null : LinkCodes.rsnLookup(LinkCodes.normaliseRsn(v));
            default -> v.matches("[0-9A-Fa-f:.]{2,45}") ? v : null;
        };
        if (normal == null) throw LinkException.bad("bad_value", "That doesn't look like a valid " + kind + ".");
        return normal;
    }

    private static String prefix(byte[] lookup) {
        return lookup == null ? null : HexFormat.of().formatHex(lookup, 0, 4);
    }

    private int count(Connection c, String sql) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(store.sql(sql)); ResultSet rs = s.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private int countAt(Connection c, String sql, Instant at) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(store.sql(sql))) {
            s.setTimestamp(1, Timestamp.from(at));
            try (ResultSet rs = s.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }
}
