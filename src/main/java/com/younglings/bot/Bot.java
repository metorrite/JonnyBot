package com.younglings.bot;

import com.younglings.bot.announcement.AnnouncementInteractionListener;
import com.younglings.bot.announcement.PostActionListener;
import com.younglings.bot.commands.DevEmbedListener;
import com.younglings.bot.commands.configure.PvmHelpConfigureListener;
import com.younglings.bot.commands.ticket.HelpListener;
import com.younglings.bot.commands.ticket.HelpTicketFlow;
import com.younglings.bot.commands.ticket.TicketListener;
import com.younglings.bot.commandchannel.CommandChannelConfigInteractionListener;
import com.younglings.bot.commandchannel.CommandChannelListener;
import com.younglings.bot.commands.coffer.CofferInteractionListener;
import com.younglings.bot.commands.configure.ConfigureInteractionListener;
import com.younglings.bot.commands.embed.EmbedInteractionListener;
import com.younglings.bot.commands.poll.PollInteractionListener;
import com.younglings.bot.commands.runescape.ClanPointsInteractionListener;
import com.younglings.bot.commands.runescape.PruneInteractionListener;
import com.younglings.bot.commands.runescape.RsAdminInteractionListener;
import com.younglings.bot.commands.runescape.RsChartInteractionListener;
import com.younglings.bot.commands.runescape.RsInteractionListener;
import com.younglings.bot.commands.runescape.RsnRenameInteractionListener;
import com.younglings.bot.commands.signup.SignupInteractionListener;
import com.younglings.bot.commands.teamforming.TeamformingInteractionListener;
import com.younglings.bot.config.BotConfig;
import com.younglings.bot.discord.EphemeralLifecycle;
import com.younglings.bot.runescape.SkillEmojiCatalog;
import com.younglings.bot.tracking.TrackingAuditLogListener;
import com.younglings.bot.tracking.TrackingConfigInteractionListener;
import com.younglings.bot.tracking.TrackingIconCatalog;
import com.younglings.bot.tracking.WeeklyDigestInteractionListener;
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
    private final PruneInteractionListener pruneInteractionListener;
    private final ClanPointsInteractionListener clanPointsInteractionListener;
    private final RsChartInteractionListener rsChartInteractionListener;
    private final RsnRenameInteractionListener rsnRenameInteractionListener;
    private final ConfigureInteractionListener configureInteractionListener;
    private final SkillEmojiCatalog skillEmojiCatalog;
    private final TrackingIconCatalog trackingIconCatalog;
    private final TrackingAuditLogListener trackingAuditLogListener;
    private final TrackingConfigInteractionListener trackingConfigInteractionListener;
    private final AnnouncementInteractionListener announcementInteractionListener;
    private final WeeklyDigestInteractionListener weeklyDigestInteractionListener;
    private final CommandChannelListener commandChannelListener;
    private final CommandChannelConfigInteractionListener commandChannelConfigInteractionListener;
    private final EphemeralLifecycle ephemeralLifecycle;
    private final PostActionListener postActionListener;
    private final DevEmbedListener devEmbedListener;
    private final TicketListener ticketListener;
    private final HelpListener helpListener;
    private final HelpTicketFlow helpTicketFlow;
    private final PvmHelpConfigureListener pvmHelpConfigureListener;

    public Bot(BotConfig botConfig, SignupInteractionListener signupInteractionListener,
               PollInteractionListener pollInteractionListener,
               CofferInteractionListener cofferInteractionListener,
               TeamformingInteractionListener teamformingInteractionListener,
               EmbedInteractionListener embedInteractionListener,
               RsInteractionListener rsInteractionListener,
               RsAdminInteractionListener rsAdminInteractionListener,
               PruneInteractionListener pruneInteractionListener,
               ClanPointsInteractionListener clanPointsInteractionListener,
               RsChartInteractionListener rsChartInteractionListener,
               RsnRenameInteractionListener rsnRenameInteractionListener,
               ConfigureInteractionListener configureInteractionListener,
               SkillEmojiCatalog skillEmojiCatalog,
               TrackingIconCatalog trackingIconCatalog,
               TrackingAuditLogListener trackingAuditLogListener,
               TrackingConfigInteractionListener trackingConfigInteractionListener,
               AnnouncementInteractionListener announcementInteractionListener,
               WeeklyDigestInteractionListener weeklyDigestInteractionListener,
               CommandChannelListener commandChannelListener,
               CommandChannelConfigInteractionListener commandChannelConfigInteractionListener,
               EphemeralLifecycle ephemeralLifecycle,
               PostActionListener postActionListener,
               DevEmbedListener devEmbedListener,
               TicketListener ticketListener, HelpListener helpListener, HelpTicketFlow helpTicketFlow, PvmHelpConfigureListener pvmHelpConfigureListener) {
        this.botConfig = botConfig;
        this.signupInteractionListener = signupInteractionListener;
        this.pollInteractionListener = pollInteractionListener;
        this.cofferInteractionListener = cofferInteractionListener;
        this.teamformingInteractionListener = teamformingInteractionListener;
        this.embedInteractionListener = embedInteractionListener;
        this.rsInteractionListener = rsInteractionListener;
        this.rsAdminInteractionListener = rsAdminInteractionListener;
        this.pruneInteractionListener = pruneInteractionListener;
        this.clanPointsInteractionListener = clanPointsInteractionListener;
        this.rsChartInteractionListener = rsChartInteractionListener;
        this.rsnRenameInteractionListener = rsnRenameInteractionListener;
        this.configureInteractionListener = configureInteractionListener;
        this.skillEmojiCatalog = skillEmojiCatalog;
        this.trackingIconCatalog = trackingIconCatalog;
        this.trackingAuditLogListener = trackingAuditLogListener;
        this.trackingConfigInteractionListener = trackingConfigInteractionListener;
        this.announcementInteractionListener = announcementInteractionListener;
        this.weeklyDigestInteractionListener = weeklyDigestInteractionListener;
        this.commandChannelListener = commandChannelListener;
        this.commandChannelConfigInteractionListener = commandChannelConfigInteractionListener;
        this.ephemeralLifecycle = ephemeralLifecycle;
        this.postActionListener = postActionListener;
        this.devEmbedListener = devEmbedListener;
        this.ticketListener = ticketListener;
        this.helpListener = helpListener;
        this.helpTicketFlow = helpTicketFlow;
        this.pvmHelpConfigureListener = pvmHelpConfigureListener;
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
                        rsAdminInteractionListener, pruneInteractionListener, clanPointsInteractionListener, rsChartInteractionListener, rsnRenameInteractionListener,
                        configureInteractionListener, skillEmojiCatalog, trackingIconCatalog,
                        trackingAuditLogListener, trackingConfigInteractionListener,
                        announcementInteractionListener, weeklyDigestInteractionListener,
                        commandChannelListener, commandChannelConfigInteractionListener, ephemeralLifecycle,
                        postActionListener, devEmbedListener, ticketListener, helpListener, helpTicketFlow, pvmHelpConfigureListener)
                .build();
    }
}
