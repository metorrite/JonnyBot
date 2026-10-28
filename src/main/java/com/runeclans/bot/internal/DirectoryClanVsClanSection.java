package com.runeclans.bot.internal;

import com.runeclans.bot.clanprofile.ClanProfile;
import com.runeclans.bot.clanprofile.ClanProfileRepository;
import com.runeclans.bot.events.ClanVsClan;
import com.runeclans.bot.events.EventListing;
import com.runeclans.bot.events.EventRepository;
import com.runeclans.bot.events.EventRepository.Event;
import com.runeclans.bot.events.EventScoring.Standing;
import com.runeclans.bot.events.EventService;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * The "Clan vs clan" history on a clan's hub page (T-223, design {@code docs/EVENTS-CLAN-VS-CLAN.md} section 5): the finished clan-vs-clan events this
 * clan took part in, newest first, with its place. Only events that were open to everyone and weren't taken off the hub, and only on a clan page that is
 * listed. Clan names only, never a player. It has no routes of its own.
 */
@BService
public class DirectoryClanVsClanSection implements DirectoryRouteModule {
    private static final Logger log = LoggerFactory.getLogger(DirectoryClanVsClanSection.class);
    static final int SHOWN = 10;
    private static final long TTL_MILLIS = 5 * 60_000L;

    private final EventRepository events;
    private final EventService service;
    private final ClanProfileRepository profiles;
    private final TtlCache cache = new TtlCache();

    public DirectoryClanVsClanSection(EventRepository events, EventService service, ClanProfileRepository profiles) {
        this.events = events;
        this.service = service;
        this.profiles = profiles;
    }

    @Override
    public void addToClan(ClanProfile profile, DataObject json) {
        if (profile.visibility() != ClanProfile.Visibility.PUBLIC) return; // an unlisted clan's page says nothing about its events
        try {
            json.put("clanVsClan", cache.get("cvc:" + profile.guildId(), TTL_MILLIS, () -> history(profile.guildId())));
        } catch (RuntimeException e) {
            log.warn("Couldn't read the clan-vs-clan history of server {}", profile.guildId(), e);
        }
    }

    /** The clan's finished open clan-vs-clan events, with its place among the clans (null when the host keeps the standings to staff). */
    DataArray history(long guildId) {
        DataArray out = DataArray.empty();
        for (Event e : events.finishedClanVsClan(guildId, SHOWN * 3)) {
            if (out.length() >= SHOWN) break;
            DataObject config = DataObject.fromJson(e.config());
            if (EventListing.takenDown(config)) continue;
            Map<Long, Long> teams = ClanVsClan.teams(config);
            Long team = teams.get(guildId);
            if (team == null) continue;
            Integer place = null;
            int clans = teams.size();
            if (EventListing.clanStandingsShown(e)) {
                List<Standing> standings = service.standings(e.guildId(), e.id());
                for (int i = 0; i < standings.size(); i++) if (standings.get(i).subject().equals("t:" + team)) place = i + 1;
                clans = Math.max(clans, standings.size());
            }
            String host = profiles.get(e.guildId()).filter(p -> p.visibility() == ClanProfile.Visibility.PUBLIC).map(ClanProfile::displayName).orElse(null);
            out.add(DataObject.empty().put("eventId", Long.toString(e.id())).put("name", e.name()).put("game", ClanVsClan.game(e))
                    .put("endedAt", e.endsAt() == null ? null : e.endsAt().toString()).put("place", place).put("clans", clans).put("host", host)
                    .put("hosted", e.guildId() == guildId));
        }
        return out;
    }
}
