package com.younglings.bot.internal;

import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.util.function.Supplier;

/**
 * The dashboard's "Discord on your website" page (T-365, T-370): {@code GET|PUT site-discord} reads and saves the online widget's mode and
 * the channel feed's channels. Reached only through {@link TicketAdminApi}, which has already checked the asker may use the dashboard in
 * this server, rate-limits writes and audit-logs them.
 */
@BService
public class SiteDiscordAdminApi implements AdminRouteExtension {
    private final SiteDiscordService discord;

    public SiteDiscordAdminApi(SiteDiscordService discord) {
        this.discord = discord;
    }

    @Override
    public DataObject route(Guild guild, Member actor, String method, String[] parts, Supplier<DataObject> body) {
        if (parts.length != 1 || !parts[0].equals("site-discord")) return null;
        if (method.equals("GET")) return discord.adminState(guild);
        AdminRouteExtension.requireMethod(method, "PUT");
        return discord.save(guild, actor, body.get());
    }
}
