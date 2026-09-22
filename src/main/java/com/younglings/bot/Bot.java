package com.younglings.bot;

import com.younglings.bot.commands.coffer.CofferInteractionListener;
import com.younglings.bot.commands.poll.PollInteractionListener;
import com.younglings.bot.commands.signup.SignupInteractionListener;
import com.younglings.bot.commands.teamforming.TeamformingInteractionListener;
import com.younglings.bot.config.BotConfig;
import io.github.freya022.botcommands.api.core.BContext;
import io.github.freya022.botcommands.api.core.JDAService;
import io.github.freya022.botcommands.api.core.annotations.BEventListener;
import io.github.freya022.botcommands.api.core.events.BReadyEvent;
import io.github.freya022.botcommands.api.core.events.InjectedJDAEvent;
import io.github.freya022.botcommands.api.core.events.PostLoadEvent;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.hooks.IEventManager;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.MemberCachePolicy;
import net.dv8tion.jda.api.utils.cache.CacheFlag;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

@BService
@NullMarked // Everything is non-null unless @Nullable
public class Bot extends JDAService {
    private static final Logger log = LoggerFactory.getLogger(Bot.class);

    private final BotConfig botConfig;
    private final SignupInteractionListener signupInteractionListener;
    private final PollInteractionListener pollInteractionListener;
    private final CofferInteractionListener cofferInteractionListener;
    private final TeamformingInteractionListener teamformingInteractionListener;

    // Populated by onJdaReady/onPostLoad respectively — see registerCommandCleanupShutdownHookIfReady.
    private volatile @Nullable JDA jda;
    private volatile @Nullable BContext context;
    private final AtomicBoolean shutdownHookRegistered = new AtomicBoolean(false);

    public Bot(BotConfig botConfig, SignupInteractionListener signupInteractionListener,
               PollInteractionListener pollInteractionListener,
               CofferInteractionListener cofferInteractionListener,
               TeamformingInteractionListener teamformingInteractionListener) {
        this.botConfig = botConfig;
        this.signupInteractionListener = signupInteractionListener;
        this.pollInteractionListener = pollInteractionListener;
        this.cofferInteractionListener = cofferInteractionListener;
        this.teamformingInteractionListener = teamformingInteractionListener;
    }

    // If you use Spring, you can return values provided by JDAConfiguration in the getters below
    @Override
    public Set<CacheFlag> getCacheFlags() {
        return Set.of(CacheFlag.values());
    }

    @Override
    public Set<GatewayIntent> getIntents() {
        return defaultIntents(GatewayIntent.values());
    }

    @Override
    public void createJDA(BReadyEvent event, IEventManager eventManager) {
        // This uses JDABuilder#createLight, with the intents and the additional cache flags set above
        // It also sets the EventManager and a special rate limiter
        var builder = createLight(botConfig.getToken())
                .setActivity(botConfig.getActivity())
                // createLight's low-memory profile defaults to a restrictive member cache policy —
                // fine for a bot that only ever looks up members it already has an ID for (signup,
                // coffer, etc.), but the internal API's online-members endpoint needs the full
                // member list chunked and cached, so it's explicitly overridden to ALL here.
                .setMemberCachePolicy(MemberCachePolicy.ALL)
                .addEventListeners(signupInteractionListener, pollInteractionListener, cofferInteractionListener,
                        teamformingInteractionListener);

        if (botConfig.shouldManageOwnShutdown()) {
            // JDA registers its own shutdown hook by default (literally just
            // `new Thread(this::shutdownNow, "JDA Shutdown Hook")`, per JDAImpl) that closes its
            // REST requester. Disabling it here is necessary but not sufficient on its own — see
            // Main, which disables BotCommands' own separate framework-level shutdown hook too.
            // Both would otherwise race our custom one below for the same "remove guild commands"
            // REST call, and either one winning that race breaks it (confirmed live, twice, one
            // error each — RejectedExecutionException from JDA's, then InterruptedIOException from
            // BotCommands' after only disabling JDA's).
            builder.setEnableShutdownHook(false);
        }

        this.jda = builder.build();
        registerCommandCleanupShutdownHookIfReady();
    }

    /**
     * BotCommands fires this once the framework has finished its own startup, independently of
     * (and, as far as observed, before) {@link InjectedJDAEvent} — captured here purely to get a
     * {@link BContext} reference for {@link #registerCommandCleanupShutdownHookIfReady}, so our
     * shutdown hook can call the framework's own full shutdown ({@link BContext#shutdownNow()})
     * instead of reimplementing a partial version of it by calling {@code jda.shutdownNow()}
     * directly — {@code BContext.shutdownNow()} already does that internally, plus its own
     * additional cleanup (coroutine-backed executors) that calling JDA's shutdown alone would skip.
     */
    @BEventListener
    public void onPostLoad(PostLoadEvent event) {
        this.context = event.getContext();
        registerCommandCleanupShutdownHookIfReady();
    }

    /**
     * Registers the cleanup hook once both {@link #jda} (from {@link #createJDA}) and
     * {@link #context} (from {@link #onPostLoad}) are available — order-independent, since which
     * of those two fires first isn't documented/guaranteed. Only actually registers anything if
     * {@link BotConfig#shouldManageOwnShutdown()} is true; otherwise this is a no-op every time
     * it's called, and both of JDA's/BotCommands' built-in shutdown hooks are left enabled as normal.
     */
    private void registerCommandCleanupShutdownHookIfReady() {
        JDA jdaInstance = this.jda;
        BContext contextInstance = this.context;
        if (jdaInstance == null || contextInstance == null) return;

        if (!botConfig.shouldManageOwnShutdown()) return;
        if (!shutdownHookRegistered.compareAndSet(false, true)) return; // already registered

        long guildId = botConfig.getGuildId(); // known non-null: shouldManageOwnShutdown() already checked

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                Guild guild = jdaInstance.getGuildById(guildId);
                if (guild == null) {
                    log.warn("Guild {} not available at shutdown — could not remove its commands.", guildId);
                } else {
                    // .submit() rather than .queue(): the JVM won't wait around for an async
                    // callback once this thread returns, so the removal has to actually finish
                    // (or time out) before shutdown proceeds.
                    guild.updateCommands().submit().get(10, TimeUnit.SECONDS);
                    log.info("Removed all guild commands from {} on shutdown.", guildId);
                }
            } catch (TimeoutException e) {
                log.warn("Timed out removing guild commands from {} on shutdown (10s) — they may still be present.", guildId);
            } catch (Exception e) {
                log.warn("Failed to remove guild commands from {} on shutdown.", guildId, e);
            } finally {
                // We disabled both JDA's and BotCommands' own shutdown hooks to avoid racing them
                // for the call above, so we're responsible for a full shutdown ourselves now that
                // it's done. context.shutdownNow() (not jda.shutdownNow()) because it already
                // calls jda.shutdownNow() internally, plus BotCommands' own additional cleanup —
                // this is the actual call BotCommands' own (now-disabled) hook would have made.
                contextInstance.shutdownNow();
            }
        }, "guild-command-cleanup"));
    }
}
