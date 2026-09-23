package com.younglings.bot;

import com.younglings.bot.commands.coffer.CofferInteractionListener;
import com.younglings.bot.commands.embed.EmbedInteractionListener;
import com.younglings.bot.commands.poll.PollInteractionListener;
import com.younglings.bot.commands.runescape.RsnAdminInteractionListener;
import com.younglings.bot.commands.runescape.RsnInteractionListener;
import com.younglings.bot.commands.signup.SignupInteractionListener;
import com.younglings.bot.commands.teamforming.TeamformingInteractionListener;
import com.younglings.bot.config.BotConfig;
import io.github.freya022.botcommands.api.core.JDAService;
import io.github.freya022.botcommands.api.core.events.BReadyEvent;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.hooks.IEventManager;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.MemberCachePolicy;
import net.dv8tion.jda.api.utils.cache.CacheFlag;
import org.jspecify.annotations.NullMarked;

import java.util.Set;

@BService
@NullMarked // Everything is non-null unless @Nullable
public class Bot extends JDAService {
    private final BotConfig botConfig;
    private final SignupInteractionListener signupInteractionListener;
    private final PollInteractionListener pollInteractionListener;
    private final CofferInteractionListener cofferInteractionListener;
    private final TeamformingInteractionListener teamformingInteractionListener;
    private final EmbedInteractionListener embedInteractionListener;
    private final RsnInteractionListener rsnInteractionListener;
    private final RsnAdminInteractionListener rsnAdminInteractionListener;

    public Bot(BotConfig botConfig, SignupInteractionListener signupInteractionListener,
               PollInteractionListener pollInteractionListener,
               CofferInteractionListener cofferInteractionListener,
               TeamformingInteractionListener teamformingInteractionListener,
               EmbedInteractionListener embedInteractionListener,
               RsnInteractionListener rsnInteractionListener,
               RsnAdminInteractionListener rsnAdminInteractionListener) {
        this.botConfig = botConfig;
        this.signupInteractionListener = signupInteractionListener;
        this.pollInteractionListener = pollInteractionListener;
        this.cofferInteractionListener = cofferInteractionListener;
        this.teamformingInteractionListener = teamformingInteractionListener;
        this.embedInteractionListener = embedInteractionListener;
        this.rsnInteractionListener = rsnInteractionListener;
        this.rsnAdminInteractionListener = rsnAdminInteractionListener;
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
        createLight(botConfig.getToken())
                .setActivity(botConfig.getActivity())
                // createLight's low-memory profile defaults to a restrictive member cache policy —
                // fine for a bot that only ever looks up members it already has an ID for (signup,
                // coffer, etc.), but the internal API's online-members endpoint needs the full
                // member list chunked and cached, so it's explicitly overridden to ALL here.
                .setMemberCachePolicy(MemberCachePolicy.ALL)
                .addEventListeners(signupInteractionListener, pollInteractionListener, cofferInteractionListener,
                        teamformingInteractionListener, embedInteractionListener, rsnInteractionListener,
                        rsnAdminInteractionListener)
                .build();
    }
}
