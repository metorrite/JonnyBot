package com.younglings.bot.internal;

import com.younglings.bot.plugin.PluginVisibilityRepository;
import com.younglings.bot.plugin.PluginVisibilityRepository.Shown;
import com.younglings.bot.runescape.ClanMemberRepository.ClanMemberRow;
import com.younglings.bot.runescape.ClanSyncService;
import com.younglings.bot.runescape.PlayerLink;
import com.younglings.bot.runescape.PlayerLinkRepository;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The has-plugin list on the dashboard's Clan plugin page ({@code GET plugin-users}, T-140, PLUGIN-ERA-FEATURES.md section 1, Drake's Q1
 * default: staff on the dashboard only). It names only roster members who switched on "Show my clan that I use RuneSync" on their profile,
 * with their RuneScape name, the day RuneSync last checked in and its version. Everyone else stays in the coverage counts. Reached only
 * through {@link TicketAdminApi}, which has already checked the asker may use the dashboard in this server.
 */
@BService
public class PluginUsersAdminApi implements AdminRouteExtension {
    private final PluginVisibilityRepository visibility;
    private final PlayerLinkRepository links;
    private final ClanSyncService clanSync;

    public PluginUsersAdminApi(PluginVisibilityRepository visibility, PlayerLinkRepository links, ClanSyncService clanSync) {
        this.visibility = visibility;
        this.links = links;
        this.clanSync = clanSync;
    }

    @Override
    public DataObject route(Guild guild, Member actor, String method, String[] parts, Supplier<DataObject> body) {
        if (parts.length != 1 || !parts[0].equals("plugin-users")) return null;
        AdminRouteExtension.requireMethod(method, "GET");
        return users(guild.getIdLong());
    }

    DataObject users(long guildId) {
        Set<String> roster = new HashSet<>();
        for (ClanMemberRow row : clanSync.getRoster(guildId, true)) roster.add(row.rsn().toLowerCase(Locale.ROOT));
        // A member may have several linked names on the roster: show them all on their one row.
        Map<Long, String> names = new HashMap<>();
        for (PlayerLink link : links.getAllLinks(guildId)) {
            if (!roster.contains(link.rsn().toLowerCase(Locale.ROOT))) continue;
            names.merge(link.discordUserId(), link.rsn(), (a, b) -> a + ", " + b);
        }
        DataArray list = DataArray.empty();
        for (Shown s : visibility.shown(names.keySet())) {
            list.add(DataObject.empty().put("rsn", names.get(s.discordUserId())).put("client", s.client())
                    .put("lastSeenDay", s.lastSeen() == null ? null : s.lastSeen().atZone(ZoneOffset.UTC).toLocalDate().toString()));
        }
        return DataObject.empty().put("users", list).put("linkedPlayers", names.size());
    }
}
