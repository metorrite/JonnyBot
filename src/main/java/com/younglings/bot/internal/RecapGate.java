package com.younglings.bot.internal;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.younglings.bot.member.MemberProfileRepository;
import com.younglings.bot.member.MemberProfileRepository.RecapOwner;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.regex.Pattern;

/**
 * Who may see one member's recap (one RuneScape name over one period), so a recap is never a spoiler:
 * <ul>
 *   <li>the member it belongs to always does: the viewer has that name linked on this server ({@code player_link}). Showing it to them records
 *   that they opened that period ({@link RecapPeriod#key()});</li>
 *   <li>anyone else (other members, site admins, signed-out visitors) only once that member has opened that very period, when they haven't
 *   turned recaps off ({@code hideRecap}, off unless they say so) and the name is linked at all;</li>
 *   <li>otherwise nobody, with no exception for admins. A bot-less site has no links, so its member recaps are nobody's to open.</li>
 * </ul>
 * The rules that come first still apply first: a member hidden from the rankings has no recap at all (404), and a hidden adventure log is
 * left out of the recap itself. The decision is made per request after the shared {@link SiteCache}, never cached: a new mark or a changed
 * choice shows on the very next read.
 * <p>
 * The viewer is the {@value #VIEWER_HEADER} header: the Discord user id the website takes from its own signed-in session (never from anything
 * the browser sends), trusted as the {@code /me} routes trust their {@code userId}, behind the internal secret. No header, or anything but
 * digits, is a signed-out visitor.
 */
public final class RecapGate {
    private static final Logger log = LoggerFactory.getLogger(RecapGate.class);
    /** The request header with the viewer's Discord user id. Never part of a cache key. */
    public static final String VIEWER_HEADER = "X-RC-Viewer";
    private static final Pattern DIGITS = Pattern.compile("\\d{1,20}");

    public enum Outcome {
        /** the viewer is the member the recap is for */
        SUBJECT,
        /** someone else, and the member's recap may be shown to them */
        SHOWN,
        /** someone else, and it may not */
        PRIVATE
    }

    /** {@code sharedWithClan} (for {@link Outcome#SUBJECT}): after this view, others can see it too. */
    public record Decision(Outcome outcome, boolean sharedWithClan) {
        static final Decision HIDDEN = new Decision(Outcome.PRIVATE, false);
        static final Decision OPEN = new Decision(Outcome.SHOWN, false);
    }

    private final MemberProfileRepository profiles;

    public RecapGate(MemberProfileRepository profiles) {
        this.profiles = profiles;
    }

    /**
     * The rule, with everything already looked up.
     *
     * @param botless   the site's server has no bot (no links, so nobody can be the member)
     * @param viewerId  the viewer's Discord user id, null for a signed-out visitor
     * @param owner     the member the name is linked to on this server, null when nobody linked it
     * @param periodKey the period's {@link RecapPeriod#key()}, null when it has none
     */
    static Decision decide(boolean botless, Long viewerId, RecapOwner owner, String periodKey) {
        if (botless || owner == null) return Decision.HIDDEN;
        if (viewerId != null && viewerId == owner.userId()) return new Decision(Outcome.SUBJECT, !owner.hideRecap() && periodKey != null);
        if (owner.hideRecap() || periodKey == null || !owner.seen()) return Decision.HIDDEN;
        return Decision.OPEN;
    }

    /** {@link #decide} for {@code rsn} on this server; when the viewer is its member, records that they opened this period. */
    public Decision check(long guildId, boolean botless, String rsn, String periodKey, Long viewerId) {
        if (botless || rsn == null || rsn.isBlank()) return Decision.HIDDEN;
        RecapOwner owner = profiles.recapOwner(guildId, rsn, periodKey);
        Decision decision = decide(false, viewerId, owner, periodKey);
        if (decision.outcome() == Outcome.SUBJECT && periodKey != null && !owner.seen()) {
            try {
                profiles.markRecapSeen(guildId, rsn, periodKey, owner.userId());
            } catch (RuntimeException e) {
                // still the member's own recap; it just stays theirs until a later view is recorded
                log.warn("Couldn't record an opened recap for server {}", guildId, e);
                return new Decision(Outcome.SUBJECT, false);
            }
        }
        return decision;
    }

    /** {@link #check} for {@code /wrapped}: one of the command's period tokens, as of now. */
    public Decision check(long guildId, String rsn, String periodToken, long viewerId) {
        String key = RecapPeriod.parse(periodToken, OffsetDateTime.now(ZoneOffset.UTC), null).flatMap(RecapPeriod::key).orElse(null);
        return check(guildId, false, rsn, key, viewerId);
    }

    /**
     * A member recap answer (from the shared cache, never changed here) as this viewer may see it: for the member themselves a copy with
     * {@code "you": true} and {@code sharedWithClan}; for anyone else allowed, the answer itself; for anyone else {@link #privateAnswer()}.
     * {@code null} stays {@code null} (no such member or period: 404).
     */
    DataObject answer(long guildId, boolean botless, DataObject recap, Long viewerId) {
        if (recap == null) return null;
        String rsn = recap.optObject("subject").map(s -> s.getString("name", null)).orElse(null);
        Decision decision = check(guildId, botless, rsn, periodKey(recap), viewerId);
        return switch (decision.outcome()) {
            case SUBJECT -> DataObject.fromJson(recap.toJson()).put("you", true).put("sharedWithClan", decision.sharedWithClan());
            case SHOWN -> recap;
            case PRIVATE -> privateAnswer();
        };
    }

    /** What anyone but the member gets for a recap they may not see: it exists, and nothing else (not even why). */
    static DataObject privateAnswer() {
        return DataObject.empty().put("private", true);
    }

    /** The key of the period a recap answer was built for, from its own {@code period}; null when it has none or can't be read. */
    static String periodKey(DataObject recap) {
        DataObject period = recap.optObject("period").orElse(null);
        if (period == null) return null;
        try {
            return RecapPeriod.key(period.getString("token", null), OffsetDateTime.parse(period.getString("from")), OffsetDateTime.parse(period.getString("to")),
                    period.getBoolean("toDate", false)).orElse(null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The viewer of this request ({@value #VIEWER_HEADER}), or null for a signed-out visitor. */
    static Long viewer(HttpExchange exchange) {
        Headers headers = exchange.getRequestHeaders();
        return headers == null ? null : viewer(headers.getFirst(VIEWER_HEADER));
    }

    /** A Discord user id made of digits only, or null. */
    static Long viewer(String raw) {
        if (raw == null) return null;
        String id = raw.strip();
        if (!DIGITS.matcher(id).matches()) return null;
        try {
            return Long.parseLong(id);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
