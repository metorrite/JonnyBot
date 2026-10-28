package com.runeclans.bot.internal;

import com.runeclans.bot.plugin.PluginCategory;
import com.runeclans.bot.plugin.PluginRepository;
import com.runeclans.bot.runescape.ClanMemberRepository.ClanMemberRow;
import com.runeclans.bot.runescape.ClanSyncService;
import com.runeclans.bot.runescape.PlayerLink;
import com.runeclans.bot.runescape.PlayerLinkRepository;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;

/**
 * How much of a clan reports through the plugin, as counts only ({@code GET plugin-coverage}): how many linked roster members have a plugin
 * token, how many used it in the last 30 days, and how many allow each category. It never says who, so a clan sees how well covered it is
 * without learning who has or hasn't installed anything. Reached only through {@link TicketAdminApi}, which has already checked the asker may
 * use the dashboard in this server.
 */
@BService
public class PluginCoverageAdminApi implements AdminRouteExtension {
    private static final Duration RECENT = Duration.ofDays(30);

    private final PluginRepository plugin;
    private final PlayerLinkRepository links;
    private final ClanSyncService clanSync;

    public PluginCoverageAdminApi(PluginRepository plugin, PlayerLinkRepository links, ClanSyncService clanSync) {
        this.plugin = plugin;
        this.links = links;
        this.clanSync = clanSync;
    }

    @Override
    public DataObject route(Guild guild, Member actor, String method, String[] parts, Supplier<DataObject> body) {
        if (parts.length != 1 || !parts[0].equals("plugin-coverage")) return null;
        AdminRouteExtension.requireMethod(method, "GET");
        return coverage(guild);
    }

    DataObject coverage(Guild guild) {
        Set<String> roster = new HashSet<>();
        for (ClanMemberRow row : clanSync.getRoster(guild.getIdLong(), true)) roster.add(row.rsn().toLowerCase(Locale.ROOT));
        Set<Long> users = new HashSet<>();
        for (PlayerLink link : links.getAllLinks(guild.getIdLong())) if (roster.contains(link.rsn().toLowerCase(Locale.ROOT))) users.add(link.discordUserId());

        var c = plugin.coverage(users, Instant.now().minus(RECENT));
        DataArray consent = DataArray.empty();
        for (PluginCategory category : PluginCategory.values()) {
            consent.add(DataObject.empty().put("key", category.name()).put("label", category.label()).put("players", c.consent().getOrDefault(category, 0)));
        }
        return DataObject.empty().put("rosterSize", roster.size()).put("linkedPlayers", users.size()).put("withToken", c.withToken())
                .put("reportingRecently", c.reportingRecently()).put("consent", consent).put("recentDays", (int) RECENT.toDays());
    }
}
