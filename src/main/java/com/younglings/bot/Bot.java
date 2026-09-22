package com.younglings.bot;

import com.younglings.bot.commands.coffer.CofferInteractionListener;
import com.younglings.bot.commands.poll.PollInteractionListener;
import com.younglings.bot.commands.signup.SignupInteractionListener;
import com.younglings.bot.commands.teamforming.TeamformingInteractionListener;
import com.younglings.bot.config.BotConfig;
import io.github.freya022.botcommands.api.core.JDAService;
import io.github.freya022.botcommands.api.core.events.BReadyEvent;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.SelfUser;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.hooks.IEventManager;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.MemberCachePolicy;
import net.dv8tion.jda.api.utils.cache.CacheFlag;
import org.jspecify.annotations.NullMarked;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Set;

@BService
@NullMarked // Everything is non-null unless @Nullable
public class Bot extends JDAService {
    private static final Logger log = LoggerFactory.getLogger(Bot.class);
    private static final String DISCORD_API_BASE = "https://discord.com/api/v10";

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
            // Wait for JDA's own ReadyEvent (not just BReadyEvent, which is BotCommands' own
            // "go ahead and build JDA now" signal — selfUser isn't populated yet at that point,
            // only after the gateway session actually completes) so getSelfUser() is safe to call.
            builder.addEventListeners(new ListenerAdapter() {
                @Override
                public void onReady(ReadyEvent readyEvent) {
                    registerCommandCleanupShutdownHook(readyEvent.getJDA().getSelfUser());
                }
            });
        }

        builder.build();
    }

    /**
     * Removes every guild slash command from {@link BotConfig#getGuildId()} on JVM shutdown —
     * normal exit, Ctrl+C, or a graceful stop from the IDE/OS (a {@code SIGTERM}-style signal) all
     * run shutdown hooks. A hard kill (task manager "End task", {@code taskkill /F}, a crashed
     * host, power loss) does not and cannot — no process, in any language, can run cleanup code
     * after being forcibly killed, so commands may occasionally survive an abrupt stop. Re-running
     * the bot re-syncs them either way (see Main's {@code forceGuildCommands} setup), so the worst
     * case is just "they're still there next time," not anything broken.
     * <p>
     * <b>Deliberately bypasses JDA entirely</b> — sends a raw HTTPS request with the JDK's own
     * {@link HttpClient} instead of {@code guild.updateCommands()}. Both JDA and BotCommands
     * register their own shutdown hooks by default that tear down JDA's REST machinery on exit,
     * with no ordering guarantee against a custom hook; disabling either one (tried both, in two
     * separate attempts) surfaced a new failure each time — a race against JDA's own hook, then a
     * race against BotCommands' framework-level one, then a crash from a {@code @Lazy} service
     * resolution edge case in the framework when that second hook is disabled. Not depending on
     * either hook's internals at all sidesteps every one of those failure modes at once, and both
     * built-in hooks are left fully enabled, exactly as they ship by default.
     */
    private void registerCommandCleanupShutdownHook(SelfUser selfUser) {
        long applicationId = selfUser.getApplicationIdLong();
        long guildId = botConfig.getGuildId(); // known non-null: shouldManageOwnShutdown() already checked
        String token = botConfig.getToken();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                HttpClient client = HttpClient.newHttpClient();
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(DISCORD_API_BASE + "/applications/" + applicationId + "/guilds/" + guildId + "/commands"))
                        .header("Authorization", "Bot " + token)
                        .header("Content-Type", "application/json")
                        .timeout(Duration.ofSeconds(10))
                        .PUT(HttpRequest.BodyPublishers.ofString("[]")) // bulk-overwrite with an empty list = remove all
                        .build();

                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() / 100 == 2) {
                    log.info("Removed all guild commands from {} on shutdown.", guildId);
                } else {
                    log.warn("Discord returned {} removing guild commands from {} on shutdown: {}",
                            response.statusCode(), guildId, response.body());
                }
            } catch (IOException e) {
                log.warn("Failed to remove guild commands from {} on shutdown.", guildId, e);
            } catch (InterruptedException e) {
                log.warn("Interrupted while removing guild commands from {} on shutdown.", guildId, e);
                Thread.currentThread().interrupt();
            }
        }, "guild-command-cleanup"));
    }
}
