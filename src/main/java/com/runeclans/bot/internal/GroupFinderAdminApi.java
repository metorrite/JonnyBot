package com.runeclans.bot.internal;

import com.runeclans.bot.events.QuickGroupRepository.Settings;
import com.runeclans.bot.events.QuickGroupService;
import com.runeclans.bot.internal.TicketAdminApi.ApiError;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Supplier;

/**
 * The dashboard's Group finder page (T-589): GET/PUT {@code group-finder}, the switch that lets members post quick groups on the clan site (off by
 * default) and the channel the bot posts them in (none by default: nothing is posted). Reached only through {@link TicketAdminApi}, which has already
 * checked the asker may use the dashboard here and audit-logs every change.
 */
@BService
public class GroupFinderAdminApi implements AdminRouteExtension {
    private static final Logger log = LoggerFactory.getLogger(GroupFinderAdminApi.class);

    private final QuickGroupService service;

    public GroupFinderAdminApi(QuickGroupService service) {
        this.service = service;
    }

    @Override
    public DataObject route(Guild guild, Member actor, String method, String[] parts, Supplier<DataObject> body) {
        if (parts.length != 1 || !parts[0].equals("group-finder")) return null;
        if (method.equals("GET")) return view(guild);
        AdminRouteExtension.requireMethod(method, "PUT");
        Settings saved = settingsFrom(guild, body.get());
        service.saveSettings(guild.getIdLong(), saved, actor.getIdLong());
        log.info("Dashboard: {} set the group finder in {} to {} (channel {})", actor.getId(), guild.getId(), saved.enabled() ? "on" : "off", saved.channelId());
        return view(guild);
    }

    DataObject view(Guild guild) {
        Settings s = service.settings(guild.getIdLong());
        return DataObject.empty().put("enabled", s.enabled()).put("channelId", s.channelId() == null ? null : Long.toString(s.channelId()))
                .put("openGroups", s.enabled() ? service.open(guild.getIdLong()).size() : 0);
    }

    /** The settings asked for, checked: a channel must be a text channel of this server the bot can post embeds in. */
    static Settings settingsFrom(Guild guild, DataObject body) {
        boolean enabled = body.getBoolean("enabled", false);
        String raw = body.isNull("channelId") ? null : body.getString("channelId", null);
        Long channelId = null;
        if (raw != null && !raw.isBlank()) {
            try {
                channelId = Long.parseUnsignedLong(raw.strip());
            } catch (NumberFormatException e) {
                throw new ApiError(400, "That channel isn't in this server.");
            }
            GuildMessageChannel channel = guild.getChannelById(GuildMessageChannel.class, channelId);
            if (channel == null) throw new ApiError(400, "That channel isn't in this server.");
            if (!channel.canTalk() || !guild.getSelfMember().hasPermission(channel, Permission.MESSAGE_EMBED_LINKS))
                throw new ApiError(400, "The bot can't post in #" + channel.getName() + ". Give it Send Messages and Embed Links there, or choose another channel.");
        }
        return new Settings(enabled, channelId);
    }
}
