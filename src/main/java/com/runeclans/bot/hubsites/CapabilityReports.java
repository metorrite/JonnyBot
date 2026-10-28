package com.runeclans.bot.hubsites;

import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.util.Collection;

/**
 * What RuneClan Bot tells the hub's switchboard it can do (PLATFORM-DESIGN 4.2, T-497): {@code PUT /v1/apps/me/capabilities} for all
 * servers, and {@code PUT /v1/guilds/{guildId}/capabilities} for each server it is in, which is what makes the hub count the bot as
 * connected there. A server the bot leaves gets an empty list, so its features go to "paused, bot missing" at once.
 */
final class CapabilityReports {
    static final String APP_PATH = "/v1/apps/me/capabilities";

    /** Sends one {@code PUT} body to the hub. */
    interface Writer {
        void put(String path, String body);
    }

    private final Writer writer;

    CapabilityReports(Writer writer) {
        this.writer = writer;
    }

    /** On start: the all-servers list, then the same list for every server the bot is in. */
    void start(Collection<Long> guildIds, boolean presence, boolean messageContent) {
        String body = capabilities(presence, messageContent).toString();
        writer.put(APP_PATH, body);
        for (long guildId : guildIds) writer.put(guildPath(guildId), body);
    }

    /** The bot joined a server. */
    void joined(long guildId, boolean presence, boolean messageContent) {
        writer.put(guildPath(guildId), capabilities(presence, messageContent).toString());
    }

    /** The bot left a server (or was removed): nothing is offered there any more. */
    void left(long guildId) {
        writer.put(guildPath(guildId), DataObject.empty().put("capabilities", DataArray.empty()).toString());
    }

    static String guildPath(long guildId) {
        return "/v1/guilds/" + guildId + "/capabilities";
    }

    /**
     * RuneClan Bot's capabilities, by the hub's fixed names (PLATFORM-DESIGN 4.2), plus {@code discord.feed} (reading the channels a site
     * picks for its news feed) and the three the clan sites' Discord answers need ({@code SiteDiscordService}); presence and message
     * reports only while their privileged intents are on.
     */
    static DataObject capabilities(boolean presence, boolean messageContent) {
        DataArray names = DataArray.empty().add("discord.post").add("discord.buttons").add("discord.roles.assign").add("discord.roles.report");
        if (presence) names.add("discord.presence");
        names.add("discord.feed").add("discord.channels.report");
        if (messageContent) names.add("discord.messages.report");
        names.add("discord.events.report");
        return DataObject.empty().put("capabilities", names);
    }
}
