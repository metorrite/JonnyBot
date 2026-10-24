package com.younglings.bot.premium;

import com.younglings.bot.premium.PremiumGuildRepository.Grant;
import com.younglings.bot.util.BotScheduler;
import io.github.freya022.botcommands.api.core.annotations.BEventListener;
import io.github.freya022.botcommands.api.core.events.InjectedJDAEvent;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The one place that answers "does this server have that?" ({@link #allows}, {@link #limit}) and the one place plans are granted, changed and revoked,
 * each with a line in the history. Once a day it removes grants that have run out (logging EXPIRE). Nothing is ever sent to a server about its plan:
 * an ending plan simply stops, and the owner's list shows what is ending soon. Payments arrive through {@link Billing} as just another
 * {@code source} ("stripe"); no prices or payment details live in the bot.
 */
@BService
public class Premium {
    private static final Logger log = LoggerFactory.getLogger(Premium.class);

    public static final String GRANT = "GRANT";
    public static final String CHANGE = "CHANGE";
    public static final String REVOKE = "REVOKE";
    public static final String EXPIRE = "EXPIRE";
    private static final Duration SWEEP_EVERY = Duration.ofDays(1);
    private static final Duration FIRST_SWEEP_AFTER = Duration.ofMinutes(15);

    private final PremiumGuildRepository repository;
    private final ScheduledExecutorService executor = BotScheduler.lane("premium-expiry");

    public Premium(PremiumGuildRepository repository) {
        this.repository = repository;
    }

    @BEventListener
    public void onJdaReady(InjectedJDAEvent event) {
        executor.scheduleWithFixedDelay(() -> sweep(Instant.now()), FIRST_SWEEP_AFTER.toSeconds(), SWEEP_EVERY.toSeconds(), TimeUnit.SECONDS);
    }

    /** The tier of the server's plan, or null if it has none (or it has run out). */
    public String tierOf(long guildId) {
        Grant grant = repository.activeGrant(guildId);
        return grant == null ? null : grant.tier();
    }

    /** Website features ({@code site.*}) are unlocked for everyone while {@link PremiumSwitch} is off (T-422). */
    public boolean allows(long guildId, String featureKey) {
        return PremiumFeatures.allows(PremiumSwitch.tierFor(tierOf(guildId), featureKey), featureKey);
    }

    /** The number for a limit on this server's plan; empty if {@code featureKey} isn't a limit. Website limits follow {@link PremiumSwitch}. */
    public Optional<Long> limit(long guildId, String featureKey) {
        return PremiumFeatures.limit(PremiumSwitch.tierFor(tierOf(guildId), featureKey), featureKey);
    }

    /** The servers whose plan unlocks the feature right now. */
    public List<Long> guildsAllowing(String featureKey) {
        Instant now = Instant.now();
        return repository.grants().stream().filter(g -> g.inForce(now) && PremiumFeatures.allows(g.tier(), featureKey)).map(Grant::guildId).toList();
    }

    /** Grants (or changes) a server's plan and records it. Returns true if the server had a plan already. */
    public boolean grant(long guildId, String label, String tier, Instant expiresAt, String note, String source, long byUser) {
        boolean existed = repository.grant(guildId) != null;
        repository.upsert(guildId, label, tier, expiresAt, note, source, byUser);
        repository.log(guildId, existed ? CHANGE : GRANT, tier, expiresAt, byUser, note);
        return existed;
    }

    /** The same for a grant nobody typed in (a payment, see {@link Billing}): no user in the history. */
    public boolean grantAutomatic(long guildId, String label, String tier, Instant expiresAt, String note, String source) {
        boolean existed = repository.grant(guildId) != null;
        repository.upsertBy(guildId, label, tier, expiresAt, note, source, null);
        repository.log(guildId, existed ? CHANGE : GRANT, tier, expiresAt, null, note);
        return existed;
    }

    /** Removes a server's plan and records it. False if it had none. */
    public boolean revoke(long guildId, long byUser) {
        Grant before = repository.grant(guildId);
        if (before == null || !repository.delete(guildId)) return false;
        repository.log(guildId, REVOKE, before.tier(), before.expiresAt(), byUser, before.note());
        return true;
    }

    /** Removes every grant that ended before {@code now}, logging each as EXPIRE. Returns how many. Never throws: a failure is logged and tried again tomorrow. */
    public int sweep(Instant now) {
        try {
            List<Grant> expired = repository.expired(now);
            for (Grant grant : expired) {
                if (repository.delete(grant.guildId())) repository.log(grant.guildId(), EXPIRE, grant.tier(), grant.expiresAt(), null, grant.note());
            }
            if (!expired.isEmpty()) log.info("Premium expiry: {} grant(s) ended and were removed.", expired.size());
            return expired.size();
        } catch (Exception e) {
            log.error("The premium expiry sweep failed", e);
            return 0;
        }
    }
}
