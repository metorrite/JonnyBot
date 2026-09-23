package com.younglings.bot;

import com.younglings.bot.commands.coffer.CofferInteractionListener;
import com.younglings.bot.commands.embed.EmbedInteractionListener;
import com.younglings.bot.commands.poll.PollInteractionListener;
import com.younglings.bot.commands.runescape.RsnInteractionListener;
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
import net.dv8tion.jda.api.utils.cache.CacheFlag;
import org.jspecify.annotations.NullMarked;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.TimeUnit;

@BService
@NullMarked // Everything is non-null unless @Nullable
public class Bot extends JDAService {
    private static final Logger log = LoggerFactory.getLogger(Bot.class);

    private final BotConfig botConfig;
    private final SignupInteractionListener signupInteractionListener;
    private final PollInteractionListener pollInteractionListener;
    private final CofferInteractionListener cofferInteractionListener;
    private final TeamformingInteractionListener teamformingInteractionListener;
    private final EmbedInteractionListener embedInteractionListener;
    private final RsnInteractionListener rsnInteractionListener;

    public Bot(BotConfig botConfig, SignupInteractionListener signupInteractionListener,
               PollInteractionListener pollInteractionListener,
               CofferInteractionListener cofferInteractionListener,
               TeamformingInteractionListener teamformingInteractionListener,
               EmbedInteractionListener embedInteractionListener,
               RsnInteractionListener rsnInteractionListener) {
        this.botConfig = botConfig;
        this.signupInteractionListener = signupInteractionListener;
        this.pollInteractionListener = pollInteractionListener;
        this.cofferInteractionListener = cofferInteractionListener;
        this.teamformingInteractionListener = teamformingInteractionListener;
        this.embedInteractionListener = embedInteractionListener;
        this.rsnInteractionListener = rsnInteractionListener;
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
        JDA jda = createLight(botConfig.getToken())
                .setActivity(botConfig.getActivity())
                .addEventListeners(signupInteractionListener, pollInteractionListener, cofferInteractionListener,
                        teamformingInteractionListener, embedInteractionListener, rsnInteractionListener)
                .build();

        if (botConfig.getRemoveCommandsOnShutdown()) {
            registerCommandCleanupHook(jda);
        }
    }

    /**
     * Dev-only cleanup: since the dev bot registers its commands as guild commands on the same
     * Discord server production runs on (see {@code Main}), stopping the dev process would
     * otherwise leave every one of those commands (including every {@code @Test} one) sitting on
     * that server indefinitely — Discord has no concept of "the bot that registered this went
     * offline," slash commands persist until something explicitly clears them. This registers a
     * JVM shutdown hook that does exactly that, gated behind {@code REMOVE_COMMANDS_ON_SHUTDOWN}
     * so it never runs anywhere this wasn't explicitly opted into.
     */
    private void registerCommandCleanupHook(JDA jda) {
        Long guildId = botConfig.getGuildId();
        if (guildId == null) {
            log.warn("REMOVE_COMMANDS_ON_SHUTDOWN is set but GUILD_ID isn't configured — nothing to clean up.");
            return;
        }

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            Guild guild = jda.getGuildById(guildId);
            if (guild == null) {
                log.warn("REMOVE_COMMANDS_ON_SHUTDOWN: guild {} not found at shutdown, skipping command cleanup.", guildId);
                return;
            }

            try {
                // Blocking .complete() (not .queue()) — a shutdown hook's thread is exactly the
                // kind of place an async callback might never get the chance to run before the
                // JVM exits. An empty update clears every guild-scoped command for this
                // application in this guild; global (production) commands are a separate
                // namespace entirely and are untouched either way.
                guild.updateCommands().timeout(10, TimeUnit.SECONDS).complete();
                log.info("REMOVE_COMMANDS_ON_SHUTDOWN: cleared guild-scoped commands for guild {}.", guildId);
            } catch (Exception e) {
                log.warn("REMOVE_COMMANDS_ON_SHUTDOWN: failed to clear guild commands for guild {}", guildId, e);
            }
        }, "remove-commands-on-shutdown"));
    }
}
