package com.younglings.bot.events;

import com.younglings.bot.events.TeamRules.Kind;
import com.younglings.bot.runescape.ClanMemberRepository.ClanMemberRow;
import com.younglings.bot.runescape.ClanSyncService;
import com.younglings.bot.runescape.PlayerLink;
import com.younglings.bot.runescape.PlayerLinkService;
import io.github.freya022.botcommands.api.core.service.annotations.BService;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Works out what kind of player someone is for an event: a member of the host's in-game clan, someone else in the host's server, or from a partner server. */
@BService
public class EventKinds {
    private final PlayerLinkService links;
    private final ClanSyncService clanSync;

    public EventKinds(PlayerLinkService links, ClanSyncService clanSync) {
        this.links = links;
        this.clanSync = clanSync;
    }

    public Kind kindOf(long hostGuildId, long userGuildId, long userId) {
        if (userGuildId != hostGuildId) return Kind.PARTNER;
        List<PlayerLink> mine = links.getLinksForUser(hostGuildId, userId);
        if (mine.isEmpty()) return Kind.HOME_OTHER;
        Set<String> roster = new HashSet<>();
        for (ClanMemberRow row : clanSync.getRoster(hostGuildId, true)) roster.add(row.rsn().toLowerCase(Locale.ROOT));
        return mine.stream().anyMatch(l -> roster.contains(l.rsn().toLowerCase(Locale.ROOT))) ? Kind.HOME_CLAN : Kind.HOME_OTHER;
    }
}
