package com.younglings.bot.premium;

import com.younglings.bot.discord.Shards;
import com.younglings.bot.clansite.BotlessSiteRepository;
import com.younglings.bot.config.BotConfig;
import com.younglings.bot.premium.BillingRules.Decision;
import com.younglings.bot.premium.BillingRules.SitePlan;
import com.younglings.bot.premium.BillingRules.Subscription;
import com.younglings.bot.premium.PremiumGuildRepository.Grant;
import com.younglings.bot.retention.GuildRetentionRepository;
import com.younglings.bot.util.BotScheduler;
import io.github.freya022.botcommands.api.core.annotations.BEventListener;
import io.github.freya022.botcommands.api.core.events.InjectedJDAEvent;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The billing link (T-366): paid subscriptions become premium grants, and clan websites learn their plan from here. The website owns the Stripe
 * keys and forwards each subscription change to {@code POST /internal/billing/subscription} ({@code internal/BillingApi}); the bot never talks to
 * Stripe. Rules are in {@link BillingRules}.
 * <ul>
 *   <li>{@link #apply}: idempotent on the event id; an event older than the stored state is recorded and ignored, so replays and out-of-order
 *   events end in the same grant. A manual grant is never overwritten.</li>
 *   <li>{@link #plan} / {@link #planJson}: the plan a site has, counting the 14 days after the bot leaves a server.</li>
 *   <li>Once a day, servers the bot left 14+ days ago whose subscription still renews go to the website's {@code /api/billing/reconcile}
 *   ({@code BILLING_RECONCILE_URL}, the internal secret), which sets them to cancel at the period end. Unset, nothing is sent.</li>
 * </ul>
 */
@BService
public class Billing {
    private static final Logger log = LoggerFactory.getLogger(Billing.class);
    private static final Duration RECONCILE_EVERY = Duration.ofDays(1);
    private static final Duration FIRST_RECONCILE_AFTER = Duration.ofMinutes(20);
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    /** What happened to an event: taken, already seen, or older than what is stored. */
    public enum Outcome { APPLIED, DUPLICATE, STALE }

    public record Result(Outcome outcome, String why) {}

    static volatile Billing instance;

    private final BillingRepository repository;
    private final PremiumGuildRepository grants;
    private final Premium premium;
    private final GuildRetentionRepository retention;
    private final BotlessSiteRepository botless;
    private final String reconcileUrl;
    private final String secret;
    private final ScheduledExecutorService executor = BotScheduler.lane("billing-reconcile");
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    private volatile JDA jda;

    public Billing(BillingRepository repository, PremiumGuildRepository grants, Premium premium, GuildRetentionRepository retention, BotConfig config,
                   BotlessSiteRepository botless) {
        this.botless = botless;
        this.repository = repository;
        this.grants = grants;
        this.premium = premium;
        this.retention = retention;
        this.reconcileUrl = config.getBillingReconcileUrl();
        this.secret = config.getInternalApiSecret();
        instance = this;
    }

    @BEventListener
    public void onJdaReady(InjectedJDAEvent event) {
        jda = event.getJda();
        if (reconcileUrl == null || secret == null) {
            log.info("BILLING_RECONCILE_URL or INTERNAL_API_SECRET is not set: subscriptions of servers the bot left are not cancelled automatically.");
            return;
        }
        executor.scheduleWithFixedDelay(() -> reconcile(Instant.now()), FIRST_RECONCILE_AFTER.toSeconds(), RECONCILE_EVERY.toSeconds(), TimeUnit.SECONDS);
    }

    /**
     * Takes one subscription change. {@code label} is the owner's reminder on a new grant (the site's address); an existing grant keeps its own.
     * Synchronized: one bot process, and two events for one server must not interleave between the check and the write.
     */
    public synchronized Result apply(Subscription sub, String label, Instant now) {
        if (repository.seen(sub.eventId())) return new Result(Outcome.DUPLICATE, "already taken");
        Subscription stored = repository.subscription(sub.guildId()).orElse(null);
        if (stored != null && !BillingRules.newer(sub, stored.created(), stored.eventId())) {
            boolean recorded = repository.record(sub, false, Outcome.STALE.name());
            return new Result(recorded ? Outcome.STALE : Outcome.DUPLICATE, "older than the stored state");
        }
        Grant current = grants.grant(sub.guildId());
        Decision decision = BillingRules.decide(sub, current, now);
        if (decision.action() == BillingRules.Action.SET) {
            String keptLabel = current != null && !current.label().isBlank() ? current.label() : label == null ? "" : label;
            premium.grantAutomatic(sub.guildId(), keptLabel, PremiumFeatures.TIER_PREMIUM, decision.expiresAt(), "Stripe " + sub.subscriptionId() + " (" + sub.status() + ")",
                    BillingRules.SOURCE_STRIPE);
            log.info("Billing: server {} plan set to end {} ({})", sub.guildId(), decision.expiresAt(), decision.why());
        }
        boolean recorded = repository.record(sub, true, Outcome.APPLIED.name());
        return new Result(recorded ? Outcome.APPLIED : Outcome.DUPLICATE, decision.why());
    }

    /**
     * The plan a clan website has. {@code botPresent}: whether the bot is in the server right now; false with no departure on record (the bot's
     * guild cache is still loading, say) counts as present, so a site never loses its plan on a restart. A bot-less site (run without the bot,
     * {@link BotlessSiteRepository}) always counts as present: the bot was never meant to be there, so the leave grace never starts.
     */
    public SitePlan plan(long guildId, boolean botPresent, Instant now) {
        Grant grant = grants.grant(guildId);
        if (grant == null) return SitePlan.FREE;
        Instant leftAt = botPresent || botless.contains(guildId) ? null : retention.leftAt(guildId);
        Subscription sub = BillingRules.SOURCE_STRIPE.equals(grant.source()) ? repository.subscription(guildId).orElse(null) : null;
        return BillingRules.plan(grant, sub, leftAt, now);
    }

    /** Whether the bot is in the server; true while Discord isn't connected yet (see {@link #plan}). */
    public boolean botPresent(long guildId) {
        JDA current = jda;
        return current == null || Shards.guild(current, guildId) != null;
    }

    /**
     * Whether this person may buy or manage the server's plan: its Discord owner (docs/BUILDER-V3.md decision Q4). False when the bot can't see the
     * server. Site roles (T-368) may widen this to the person who claimed the site.
     */
    public boolean mayManage(long guildId, long userId) {
        JDA current = jda;
        Guild guild = current == null ? null : Shards.guild(current, guildId);
        return guild != null && guild.getOwnerIdLong() == userId;
    }

    /** The subscription the server has on record, for the website's Plan page. */
    public java.util.Optional<Subscription> subscription(long guildId) {
        return repository.subscription(guildId);
    }

    /** The plan as the website reads it: {@code {tier, until, graceUntil, source}}, tier "free" with nulls when it has none. */
    public static DataObject json(SitePlan plan) {
        return DataObject.empty().put("tier", plan.tier()).put("until", plan.until() == null ? null : plan.until().toString())
                .put("graceUntil", plan.graceUntil() == null ? null : plan.graceUntil().toString()).put("source", plan.source());
    }

    /**
     * The plan of a server's website, for the site lookups ({@code sites/{slug}}, the layout GET). Static so those routes need one line and no new
     * constructor argument; free when billing isn't built (tests) or the lookup fails, so a site always renders.
     */
    public static DataObject planJson(long guildId, boolean botPresent) {
        Billing current = instance;
        if (current == null) return json(SitePlan.FREE);
        try {
            return json(current.plan(guildId, botPresent, Instant.now()));
        } catch (RuntimeException e) {
            log.warn("Could not work out the plan of guild {}: {}", guildId, e.getClass().getSimpleName());
            return json(SitePlan.FREE);
        }
    }

    /** Subscriptions that should be set to cancel at the period end: the bot has been gone from their server for the whole grace. */
    List<Subscription> toReconcile(Instant now) {
        List<Subscription> due = new ArrayList<>();
        for (Subscription sub : repository.all()) {
            if (botless.contains(sub.guildId())) continue; // never "left": a bot-less site's plan isn't cancelled for the bot's absence
            if (BillingRules.shouldReconcile(sub, retention.leftAt(sub.guildId()), now)) due.add(sub);
        }
        return due;
    }

    /** Sends the due subscriptions to the website. Never throws: a failure is logged and tried again tomorrow. Returns how many were sent. */
    int reconcile(Instant now) {
        try {
            List<Subscription> due = toReconcile(now);
            if (due.isEmpty() || reconcileUrl == null || secret == null) return 0;
            DataArray list = DataArray.empty();
            for (Subscription sub : due) list.add(DataObject.empty().put("guildId", Long.toString(sub.guildId())).put("subscriptionId", sub.subscriptionId()));
            HttpRequest request = HttpRequest.newBuilder(URI.create(reconcileUrl)).timeout(TIMEOUT)
                    .header("Content-Type", "application/json").header("X-Internal-Secret", secret)
                    .POST(HttpRequest.BodyPublishers.ofString(DataObject.empty().put("guilds", list).toString())).build();
            HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() != 200) log.warn("Billing reconcile: the website answered {}", response.statusCode());
            else log.info("Billing reconcile: {} subscription(s) of servers the bot left were sent to be cancelled at the period end.", due.size());
            return due.size();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 0;
        } catch (Exception e) {
            log.error("The billing reconcile failed: {}", e.getClass().getSimpleName());
            return 0;
        }
    }
}
