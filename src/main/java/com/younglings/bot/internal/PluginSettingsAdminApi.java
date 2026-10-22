package com.younglings.bot.internal;

import com.younglings.bot.plugin.RuneSyncPanelService;
import com.younglings.bot.plugin.RuneSyncRepository;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.utils.data.DataObject;
import net.dv8tion.jda.api.utils.data.DataType;

import java.time.Instant;
import java.util.function.Supplier;

/**
 * A clan's plugin switches for the dashboard's Clan plugin page ({@code GET plugin-settings}, {@code PUT plugin-settings}). Today that is
 * one switch, {@code clanPanel}: "Allow the in-game clan panel" (T-206), off until staff turn it on. When it is on, RuneSync shows members
 * of this server the clan's open and running events and their own points and progress. Reached only through {@link TicketAdminApi},
 * which has already checked the asker may use the dashboard in this server and audit-logs the change.
 */
@BService
public class PluginSettingsAdminApi implements AdminRouteExtension {
    private final RuneSyncRepository repository;
    private final RuneSyncPanelService panel;

    public PluginSettingsAdminApi(RuneSyncRepository repository, RuneSyncPanelService panel) {
        this.repository = repository;
        this.panel = panel;
    }

    @Override
    public DataObject route(Guild guild, Member actor, String method, String[] parts, Supplier<DataObject> body) {
        if (parts.length != 1 || !parts[0].equals("plugin-settings")) return null;
        long guildId = guild.getIdLong();
        if (method.equals("PUT")) {
            DataObject input = body.get();
            if (!input.hasKey("clanPanel") || !input.isType("clanPanel", DataType.BOOLEAN)) {
                throw AdminRouteExtension.refuse(400, "Say whether the in-game clan panel is allowed (clanPanel: true or false).");
            }
            repository.setClanPanel(guildId, input.getBoolean("clanPanel"), actor.getIdLong(), Instant.now());
            panel.forget();
        } else {
            AdminRouteExtension.requireMethod(method, "GET");
        }
        return DataObject.empty().put("clanPanel", repository.clanPanelOn(guildId));
    }
}
