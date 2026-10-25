package com.younglings.bot.clanprofile;

import com.younglings.bot.clanprofile.SlugChangeRules.State;
import com.younglings.bot.clanprofile.SlugChangeRules.Status;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * Changes a clan site's address the D4 way ({@link SlugChangeRules}): ask, wait out the hour (or lock the change in), then wait 30 days for the
 * next one. The old address keeps forwarding for 30 days, which {@link ClanProfileRepository#saveContent} already does when a slug changes.
 * Every method that changes anything also applies a request whose hour is over first, so a read never shows a stale address.
 */
@BService
public class SlugChangeService {
    private static final Logger log = LoggerFactory.getLogger(SlugChangeService.class);

    /** What a request or lock-in came to: the problem in words, or null when it worked. */
    public record Outcome(String problem) {
        public boolean ok() {
            return problem == null;
        }
    }

    private final ClanProfileRepository profiles;
    private final SlugChangeRepository changes;
    /** Replaceable so tests can move time. */
    Supplier<Instant> clock = Instant::now;

    public SlugChangeService(ClanProfileRepository profiles, SlugChangeRepository changes) {
        this.profiles = profiles;
        this.changes = changes;
    }

    /** The server's address state at this moment, after applying a request whose hour is over. */
    public Status status(long guildId) {
        applyIfDue(guildId);
        return SlugChangeRules.status(changes.get(guildId), clock.get());
    }

    /** Asks for a new address (or changes the address already asked for, keeping its hour). */
    public Outcome request(long guildId, String rawSlug) {
        applyIfDue(guildId);
        ClanProfile current = profiles.get(guildId).orElse(null);
        if (current == null) return new Outcome("This server has no clan profile yet, so there is no address to change.");
        String slug = rawSlug == null ? "" : rawSlug.strip().toLowerCase(Locale.ROOT);
        State state = changes.get(guildId);
        Instant now = clock.get();
        if (slug.equals(current.slug())) return new Outcome("That is already your address.");
        if (SlugChangeRules.coolingDown(state, now)) {
            return new Outcome("The address was changed less than " + SlugChangeRules.COOLDOWN.toDays() + " days ago. You can change it again after "
                    + SlugChangeRules.status(state, now).cooldownUntil() + ".");
        }
        String problem = addressProblem(guildId, slug);
        if (problem != null) return new Outcome(problem);
        changes.save(guildId, SlugChangeRules.requested(state, slug, now));
        log.info("Guild {} asked to change its address to {}", guildId, slug);
        return new Outcome(null);
    }

    /** "Lock in / Change now": applies the pending address at once. */
    public Outcome lockIn(long guildId) {
        applyIfDue(guildId);
        State state = changes.get(guildId);
        if (!state.hasPending()) return new Outcome("There is no address change waiting.");
        return apply(guildId, state);
    }

    /** Drops the pending request; the cooldown of an earlier change stays. */
    public void cancel(long guildId) {
        State state = changes.get(guildId);
        if (state.hasPending()) changes.save(guildId, SlugChangeRules.cancelled(state));
    }

    /** Applies every request whose hour is over; called once a minute. */
    public void applyAllDue() {
        for (long guildId : changes.dueGuilds(clock.get())) {
            try {
                applyIfDue(guildId);
            } catch (RuntimeException e) {
                log.error("Couldn't apply the address change of guild {}", guildId, e);
            }
        }
    }

    private void applyIfDue(long guildId) {
        State state = changes.get(guildId);
        if (SlugChangeRules.due(state, clock.get())) apply(guildId, state);
    }

    private Outcome apply(long guildId, State state) {
        ClanProfile current = profiles.get(guildId).orElse(null);
        String slug = state.pendingSlug();
        // checked again: someone else may have taken it during the hour
        String problem = current == null ? "This server has no clan profile." : addressProblem(guildId, slug);
        if (problem != null) {
            changes.save(guildId, SlugChangeRules.cancelled(state));
            log.info("Guild {} address change to {} dropped: {}", guildId, slug, problem);
            return new Outcome(problem + " The change was cancelled.");
        }
        profiles.saveContent(withSlug(current, slug));
        changes.save(guildId, SlugChangeRules.applied(clock.get()));
        log.info("Guild {} address changed to {}", guildId, slug);
        return new Outcome(null);
    }

    private String addressProblem(long guildId, String slug) {
        String problem = ProfileRules.slugProblem(slug);
        if (problem != null) return problem;
        if (profiles.slugTaken(slug, guildId)) {
            return "Another clan uses the address \"" + slug + "\", or gave it up less than " + ProfileRules.SLUG_GRACE.toDays() + " days ago. Choose another.";
        }
        return null;
    }

    static ClanProfile withSlug(ClanProfile p, String slug) {
        return new ClanProfile(p.guildId(), p.visibility(), slug, p.displayName(), p.tagline(), p.description(), p.logoUrl(), p.colour(), p.timezone(),
                p.focus(), p.recruiting(), p.requirements(), p.applyUrl(), p.leaderboardOptIn(), p.verifiedClanName(), p.verifiedAt(), p.verifiedBy(),
                p.suspendedAt(), p.suspendedReason(), p.updatedAt());
    }
}
