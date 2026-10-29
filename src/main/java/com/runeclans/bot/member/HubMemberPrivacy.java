package com.runeclans.bot.member;

import com.runeclans.bot.config.BotConfig;
import com.runeclans.bot.member.MemberProfileRepository.PrivacyDefaults;
import com.runeclans.bot.runeclans.HubWriteQueue;
import com.runeclans.bot.runeclans.RuneClansHubClient;
import io.github.freya022.botcommands.api.core.service.annotations.BConfiguration;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.BiFunction;

/**
 * Member privacy in the RuneClans hub (T-750, runeclans-hub docs/MEMBER-PRIVACY.md). With {@code RUNECLANS_HUB_MEMBER_PRIVACY=on} and the hub
 * client on, every member privacy change the bot saves (a member's own toggles, Site Admin > Member privacy) is also sent to the hub, so the
 * hub's XP rankings and member pages hide the same people. The bot is the one place these are edited (the website saves them through the
 * bot), so its tables stay the ones it reads and the hub gets each change after it. A write the hub can't take right now waits in the hub
 * client's queue (up to 5 minutes). What the hub had before the switch came on is moved with the hub's move tool ({@code member-privacy}).
 * Off, nothing is sent.
 */
@BConfiguration
public class HubMemberPrivacy {
    private static final Logger log = LoggerFactory.getLogger(HubMemberPrivacy.class);
    public static final String SETTING_ENV = "RUNECLANS_HUB_MEMBER_PRIVACY";

    /** Sends a {@code PUT path body} through the hub client's write queue; null when off. */
    private final BiFunction<String, String, HubWriteQueue.Result> writer;

    /** The bot's instance. A factory, because the framework needs a service class to have exactly one constructor and tests use it. */
    @BService
    public static HubMemberPrivacy hubMemberPrivacy(BotConfig config, RuneClansHubClient hub) {
        boolean on = on(config.getRuneClansSetting(SETTING_ENV));
        if (on && !hub.enabled()) log.warn("{} is on but the hub client is off (RUNECLANS_HUB_ENABLED): member privacy stays in the bot only.", SETTING_ENV);
        else if (on) log.info("Member privacy changes are sent to the RuneClans hub.");
        return new HubMemberPrivacy(on && hub.enabled() ? (path, body) -> hub.write("PUT", path, body, HubMemberPrivacy::sent) : null);
    }

    HubMemberPrivacy(BiFunction<String, String, HubWriteQueue.Result> writer) {
        this.writer = writer;
    }

    /** Sends nothing: the switch off (for tests). */
    public static HubMemberPrivacy off() {
        return new HubMemberPrivacy(null);
    }

    static boolean on(String raw) {
        return raw != null && (raw.strip().equalsIgnoreCase("on") || raw.strip().equalsIgnoreCase("true"));
    }

    public boolean on() {
        return writer != null;
    }

    /**
     * A member saved their own toggles on a server (so they chose them): all three are sent, as the bot now has them, and the hub marks the
     * member chosen. Nothing is sent for a save that touched no privacy toggle while the member hadn't chosen.
     */
    public void memberSaved(long guildId, long userId, boolean hideFromLeaderboards, boolean hideAdventureLog, boolean hideDiscordLink) {
        send(memberPath(guildId, userId), toggles(hideFromLeaderboards, hideAdventureLog, hideDiscordLink));
    }

    /** A site admin saved the server's defaults. */
    public void defaultsSaved(long guildId, PrivacyDefaults d) {
        send(defaultsPath(guildId), toggles(d.hideFromLeaderboards(), d.hideAdventureLog(), d.hideDiscordLink()));
    }

    static String memberPath(long guildId, long userId) {
        return "/v1/guilds/" + guildId + "/member-privacy/members/" + userId;
    }

    static String defaultsPath(long guildId) {
        return "/v1/guilds/" + guildId + "/member-privacy/defaults";
    }

    static String toggles(boolean hideFromLeaderboards, boolean hideAdventureLog, boolean hideDiscordLink) {
        return DataObject.empty().put("hideFromLeaderboards", hideFromLeaderboards).put("hideAdventureLog", hideAdventureLog).put("hideDiscordLink", hideDiscordLink)
                .toString();
    }

    private void send(String path, String body) {
        if (writer == null) return;
        try {
            sent(writer.apply(path, body));
        } catch (RuntimeException e) {
            // the bot's own save already happened; the hub copy is caught up by the next change or the move tool
            log.warn("A member privacy change couldn't be sent to the hub: {}", e.getClass().getSimpleName());
        }
    }

    /** How a write ended; only a refusal is worth a line (never the ids in it). */
    private static void sent(HubWriteQueue.Result r) {
        if (r != null && r.outcome() == HubWriteQueue.Outcome.REFUSED)
            log.warn("The hub refused a member privacy change ({}).", r.answer() == null ? "no answer" : r.answer().status());
    }
}
