package com.younglings.bot.commands;

import com.younglings.bot.config.BotConfig;
import com.younglings.bot.discord.Containers;
import com.younglings.bot.runescape.ClanPointsRepository;
import com.younglings.bot.runescape.ClanSyncService;
import com.younglings.bot.tracking.ClanPointsService;
import com.younglings.bot.tracking.TrackingGroup;
import com.younglings.bot.tracking.TrackingPostingToggle;
import com.younglings.bot.tracking.TrackingService;
import io.github.freya022.botcommands.api.commands.annotations.Command;
import io.github.freya022.botcommands.api.commands.application.CommandScope;
import io.github.freya022.botcommands.api.commands.application.annotations.Test;
import io.github.freya022.botcommands.api.commands.application.slash.GuildSlashEvent;
import io.github.freya022.botcommands.api.commands.application.slash.annotations.JDASlashCommand;
import io.github.freya022.botcommands.api.commands.application.slash.annotations.TopLevelSlashCommandData;
import net.dv8tion.jda.api.entities.Guild;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Dev-only: runs the full daily points/promotion check right now ({@link ClanPointsService#runDailyPointsAndPromotionCheck}
 * — the same job {@code ClanPointsScheduler} fires at 00:10 UTC) and posts the Clan Report to the
 * channels configured for {@link TrackingGroup#CLAN_REPORT}, instead of waiting for the schedule. Unlike
 * the Tracking panel's "Send Today's Report Now" button, which only re-sends whoever is already
 * flagged, this recomputes first. Safe to run repeatedly — point awards are idempotent per day/week.
 * Reads and writes whichever database this instance is connected to, and the reply says why nothing
 * was posted when that's the case (nobody flagged, group disabled, no destination, posting toggled
 * off). {@code @Test} (with {@link CommandScope#GUILD}) keeps this out of production's command list
 * entirely, and {@link BotConfig#getLiveEnvironment()} is checked again at runtime as a second layer,
 * same pattern as {@code DevTogglePostingCommand}.
 */
@Command
public class DevClanReportCommand {
    private static final Logger log = LoggerFactory.getLogger(DevClanReportCommand.class);

    private final BotConfig botConfig;
    private final ClanPointsService clanPointsService;
    private final ClanPointsRepository clanPointsRepository;
    private final ClanSyncService clanSyncService;
    private final TrackingService trackingService;
    private final TrackingPostingToggle postingToggle;

    public DevClanReportCommand(BotConfig botConfig, ClanPointsService clanPointsService,
                                 ClanPointsRepository clanPointsRepository, ClanSyncService clanSyncService,
                                 TrackingService trackingService, TrackingPostingToggle postingToggle) {
        this.botConfig = botConfig;
        this.clanPointsService = clanPointsService;
        this.clanPointsRepository = clanPointsRepository;
        this.clanSyncService = clanSyncService;
        this.trackingService = trackingService;
        this.postingToggle = postingToggle;
    }

    @TopLevelSlashCommandData(scope = CommandScope.GUILD)
    @Test({})
    @JDASlashCommand(name = "devclanreport", description = "[Dev only] Runs the points/promotion check now and posts the Clan Report to its channels")
    public void onDevClanReport(GuildSlashEvent event) {
        if (botConfig.getLiveEnvironment()) {
            Containers.replyEphemeral(event, Containers.WARNING, "This command is dev-only.");
            return;
        }

        Guild guild = event.getGuild();
        long guildId = guild.getIdLong();
        if (clanSyncService.getClanName(guildId) == null) {
            Containers.replyEphemeral(event, Containers.WARNING, "No clan name is configured for this server yet — nothing to run.");
            return;
        }

        event.deferReply(true).queue();
        try {
            int listed = clanPointsService.runDailyPointsAndPromotionCheck(guild);

            List<String> lines = new ArrayList<>();
            lines.add("Ran the points/promotion check.");

            ClanPointsRepository.PointsSettings settings = clanPointsRepository.getSettings(guildId);
            if (settings.dailyMembershipPoints() == 0 && settings.citadelVisitPoints() == 0 && settings.citadelCapPoints() == 0) {
                lines.add("⚠️ All three point values are 0, so no points are being awarded — set them in `/rsadmin` → Configure Points & Ranks.");
            }

            if (listed == 0) {
                lines.add("Nobody is currently flagged for a promotion, so nothing was posted.");
            } else {
                lines.add("**" + listed + "** member(s) flagged for a promotion.");
                lines.add(deliveryStatus(guildId));
            }

            event.getHook().editOriginalComponents(List.of(Containers.toast(Containers.SUCCESS, lines.toArray(new String[0]))))
                    .useComponentsV2(true).queue();
        } catch (Exception e) {
            log.error("Manual clan report run failed for guild {}", guildId, e);
            event.getHook().editOriginalComponents(List.of(Containers.toast(Containers.DANGER,
                    "The run failed — check the bot's logs."))).useComponentsV2(true).queue();
        }
    }

    /** Mirrors the checks {@code TrackingEventRouter#dispatchContainer} makes, so the reply can say *why* nothing went out rather than leaving that to guesswork. */
    private String deliveryStatus(long guildId) {
        if (!postingToggle.isEnabled()) {
            return "Posting is toggled **off** on this instance (`/devtoggleposting`) — nothing was sent.";
        }
        if (!trackingService.isEnabled(guildId, TrackingGroup.CLAN_REPORT)) {
            return "The **Clan Report** group is disabled — enable it under `/configure` → Tracking → Points & Promotions. Nothing was sent.";
        }
        int destinations = trackingService.getDestinations(guildId, TrackingGroup.CLAN_REPORT).size();
        if (destinations == 0) {
            return "The **Clan Report** group has no destination channels yet — add one under `/configure` → Tracking → Points & Promotions. Nothing was sent.";
        }
        return "Posted to **" + destinations + "** channel(s).";
    }
}
