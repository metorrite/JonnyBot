package com.younglings.bot;

import com.younglings.bot.commands.coffer.CofferInteractionListener;
import com.younglings.bot.commands.poll.PollInteractionListener;
import com.younglings.bot.commands.signup.SignupInteractionListener;
import com.younglings.bot.commands.teamforming.TeamformingInteractionListener;
import com.younglings.bot.config.BotConfig;
import io.github.freya022.botcommands.api.core.JDAService;
import io.github.freya022.botcommands.api.core.events.BReadyEvent;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.hooks.IEventManager;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.MemberCachePolicy;
import net.dv8tion.jda.api.utils.cache.CacheFlag;
import org.jspecify.annotations.NullMarked;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@BService
@NullMarked // Everything is non-null unless @Nullable
public class Bot extends JDAService {
    private static final Logger log = LoggerFactory.getLogger(Bot.class);

    private final BotConfig botConfig;
    private final SignupInteractionListener signupInteractionListener;
    private final PollInteractionListener pollInteractionListener;
    private final CofferInteractionListener cofferInteractionListener;
    private final TeamformingInteractionListener teamformingInteractionListener;

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
        //
        // createLight's low-memory profile defaults to a restrictive member cache policy — fine
        // for a bot that only ever looks up members it already has an ID for (signup, coffer,
        // etc.), but the internal API's online-members endpoint needs the full member list
        // chunked and cached, so it's explicitly overridden to ALL here.
        JDA jda = createLight(botConfig.getToken())
                .setActivity(botConfig.getActivity())
                .setMemberCachePolicy(MemberCachePolicy.ALL)
                .addEventListeners(signupInteractionListener, pollInteractionListener, cofferInteractionListener,
                        teamformingInteractionListener)
                .build();

        registerCommandCleanupShutdownHook(jda);
    }

    /**
     * Outside of production, if {@link BotConfig#getRemoveCommandsOnShutdown()} is enabled,
     * removes every guild slash command from {@link BotConfig#getGuildId()} on JVM shutdown —
     * normal exit, Ctrl+C, or a graceful stop from the IDE/OS (a {@code SIGTERM}-style signal) all
     * run shutdown hooks. A hard kill (task manager "End task", {@code taskkill /F}, a crashed
     * host, power loss) does not and cannot — no process, in any language, can run cleanup code
     * after being forcibly killed, so commands may occasionally survive an abrupt stop. Re-running
     * the bot re-syncs them either way (see Main's {@code forceGuildCommands} setup), so the worst
     * case is just "they're still there next time," not anything broken.
     * <p>
     * Gated on {@code !getLiveEnvironment()} independently of the flag itself, so production can
     * never wipe its own (global) commands from this, even if the flag were ever set there by
     * mistake — this only ever touches the {@code GUILD_ID} guild's commands, which production
     * doesn't use (see Main).
     */
    private void registerCommandCleanupShutdownHook(JDA jda) {
        if (botConfig.getLiveEnvironment()) return;
        if (!botConfig.getRemoveCommandsOnShutdown()) return;

        Long guildId = botConfig.getGuildId();
        if (guildId == null) {
            log.warn("REMOVE_COMMANDS_ON_SHUTDOWN is true but GUILD_ID is not set — can't remove guild commands on shutdown.");
            return;
        }

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            Guild guild = jda.getGuildById(guildId);
            if (guild == null) {
                log.warn("Guild {} not available at shutdown — could not remove its commands.", guildId);
                return;
            }

            try {
                // .complete()/.submit() rather than .queue(): the JVM won't wait around for an
                // async callback once this thread returns, so the removal has to actually finish
                // (or time out) before shutdown proceeds.
                guild.updateCommands().submit().get(10, TimeUnit.SECONDS);
                log.info("Removed all guild commands from {} on shutdown.", guildId);
            } catch (TimeoutException e) {
                log.warn("Timed out removing guild commands from {} on shutdown (10s) — they may still be present.", guildId);
            } catch (Exception e) {
                log.warn("Failed to remove guild commands from {} on shutdown.", guildId, e);
            }
        }, "guild-command-cleanup"));
    }
}
