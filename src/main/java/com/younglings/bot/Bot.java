package com.younglings.bot;

import com.younglings.bot.commands.coffer.CofferInteractionListener;
import com.younglings.bot.commands.configure.ConfigureInteractionListener;
import com.younglings.bot.commands.embed.EmbedInteractionListener;
import com.younglings.bot.commands.poll.PollInteractionListener;
import com.younglings.bot.commands.runescape.RsAdminInteractionListener;
import com.younglings.bot.commands.runescape.RsChartInteractionListener;
import com.younglings.bot.commands.runescape.RsInteractionListener;
import com.younglings.bot.commands.runescape.RsnRenameInteractionListener;
import com.younglings.bot.commands.signup.SignupInteractionListener;
import com.younglings.bot.commands.teamforming.TeamformingInteractionListener;
import com.younglings.bot.config.BotConfig;
import com.younglings.bot.runescape.SkillEmojiCatalog;
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
    private final RsInteractionListener rsInteractionListener;
    private final RsAdminInteractionListener rsAdminInteractionListener;
    private final RsChartInteractionListener rsChartInteractionListener;
    private final RsnRenameInteractionListener rsnRenameInteractionListener;
    private final ConfigureInteractionListener configureInteractionListener;
    private final SkillEmojiCatalog skillEmojiCatalog;

    public Bot(BotConfig botConfig, SignupInteractionListener signupInteractionListener,
               PollInteractionListener pollInteractionListener,
               CofferInteractionListener cofferInteractionListener,
               TeamformingInteractionListener teamformingInteractionListener,
               EmbedInteractionListener embedInteractionListener,
               RsInteractionListener rsInteractionListener,
               RsAdminInteractionListener rsAdminInteractionListener,
               RsChartInteractionListener rsChartInteractionListener,
               RsnRenameInteractionListener rsnRenameInteractionListener,
               ConfigureInteractionListener configureInteractionListener,
               SkillEmojiCatalog skillEmojiCatalog) {
        this.botConfig = botConfig;
        this.signupInteractionListener = signupInteractionListener;
        this.pollInteractionListener = pollInteractionListener;
        this.cofferInteractionListener = cofferInteractionListener;
        this.teamformingInteractionListener = teamformingInteractionListener;
        this.embedInteractionListener = embedInteractionListener;
        this.rsInteractionListener = rsInteractionListener;
        this.rsAdminInteractionListener = rsAdminInteractionListener;
        this.rsChartInteractionListener = rsChartInteractionListener;
        this.rsnRenameInteractionListener = rsnRenameInteractionListener;
        this.configureInteractionListener = configureInteractionListener;
        this.skillEmojiCatalog = skillEmojiCatalog;
    }

    // If you use Spring, you can return values provided by JDAConfiguration in the getters below
    //
    // Only ONLINE_STATUS and SCHEDULED_EVENTS are ever actually read anywhere in this codebase —
    // see InternalApiServer's /online-members (member.getOnlineStatus()) and /events
    // (guild.getScheduledEvents()) endpoints. The other 9 CacheFlag.values() (ACTIVITY, VOICE_STATE,
    // EMOJI, STICKER, SOUNDBOARD_SOUNDS, CLIENT_STATUS, MEMBER_OVERRIDES, ROLE_TAGS, FORUM_TAGS) were
    // being cached, guild-wide, for zero actual use — each one adds its own per-member or per-guild
    // structure that JDA otherwise never has to populate or hold onto.
    @Override
    public Set<CacheFlag> getCacheFlags() {
        return Set.of(CacheFlag.ONLINE_STATUS, CacheFlag.SCHEDULED_EVENTS);
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
                // createLight's low-memory profile defaults to a restrictive member cache policy.
                // The internal API's online-members endpoint (InternalApiServer#buildOnlineMembers)
                // only ever needs members who currently AREN'T offline, so ONLINE is the exact right
                // policy for it — MemberCachePolicy.ALL was retaining every member of the guild
                // (online or not, active or long gone) for the life of the process, which is the
                // single largest driver of this bot's memory footprint. The internal API's other
                // endpoints (member lookup, nickname, color-role) look up an arbitrary member by ID
                // and used to rely on that same full cache; they now fall back to a one-off REST
                // fetch on a cache miss instead — see InternalApiServer#resolveMember. Those are
                // low-frequency, admin-triggered calls, so trading an occasional ~100-300ms REST
                // round trip for a much smaller resident cache is a clear win, not a regression.
                .setMemberCachePolicy(MemberCachePolicy.ONLINE)
                .addEventListeners(signupInteractionListener, pollInteractionListener, cofferInteractionListener,
                        teamformingInteractionListener, embedInteractionListener, rsInteractionListener,
                        rsAdminInteractionListener, rsChartInteractionListener, rsnRenameInteractionListener,
                        configureInteractionListener, skillEmojiCatalog)
                .build();
    }
}
