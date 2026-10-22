package com.younglings.bot.internal;

import com.younglings.bot.alliance.AllianceRepository;
import com.younglings.bot.alliance.AllianceRules;
import com.younglings.bot.clanprofile.ClanProfile;
import com.younglings.bot.clanprofile.ClanProfileService;
import com.younglings.bot.configure.GuildSettingsService;
import com.younglings.bot.events.EventListing;
import com.younglings.bot.events.EventListing.Access;
import com.younglings.bot.events.EventRepository;
import com.younglings.bot.events.EventRepository.Event;
import com.younglings.bot.events.EventRules.Status;
import com.younglings.bot.events.EventScoring.Standing;
import com.younglings.bot.events.EventService;
import com.younglings.bot.events.EventTemplate;
import com.younglings.bot.events.EventTemplates;
import com.younglings.bot.events.HubModerationRepository;
import com.younglings.bot.events.PrizePlan;
import com.younglings.bot.runescape.PlayerLink;
import com.younglings.bot.runescape.PlayerLinkService;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The events hub's engine events (T-216, design {@code docs/EVENTS-HUB.md}), reached through {@link DirectoryApi} ({@code /internal/directory/hub-events}),
 * so only the shared secret is checked: the website says who is looking ({@code viewer}, a Discord id it signed in, or nothing). Everyone sees events their
 * host opened to everyone; members of an allied server also see alliance events; members of the host server see their own server's open events marked as
 * theirs. Never a player's name or Discord id, apart from standings an event shows on purpose (RuneScape names only).
 */
@BService
public class HubEventsApi {
    private static final long LIST_TTL_MILLIS = 30_000;
    private static final int MAX_SHARED = 200;
    private static final int STANDINGS_SHOWN = 10;

    private final EventRepository events;
    private final EventService service;
    private final EventTemplates templates;
    private final GuildSettingsService guildSettings;
    private final ClanProfileService profiles;
    private final AllianceRepository alliances;
    private final PlayerLinkService links;
    private final HubModerationRepository moderation;
    private final TtlCache cache = new TtlCache();
    static final int MIN_REASON = 10;
    static final int MAX_REASON = 500;
    static final int MAX_REPORTS_PER_EVENT_PER_DAY = 20;

    public HubEventsApi(EventRepository events, EventService service, EventTemplates templates, GuildSettingsService guildSettings, ClanProfileService profiles,
                        AllianceRepository alliances, PlayerLinkService links, HubModerationRepository moderation) {
        this.moderation = moderation;
        this.events = events;
        this.service = service;
        this.templates = templates;
        this.guildSettings = guildSettings;
        this.profiles = profiles;
        this.alliances = alliances;
        this.links = links;
    }

    /** Every listed event the viewer may see, soonest first, each with how they may see it. */
    public DataObject list(JDA jda, String viewer) {
        List<Event> shared = cache.get("shared", LIST_TTL_MILLIS, () -> {
            Set<Long> barred = moderation.barredIds();
            return events.sharedBeyondServer(MAX_SHARED).stream().filter(e -> EventListing.listed(e, Instant.now()) && !barred.contains(e.guildId())).toList();
        });
        Long viewerId = idOf(viewer);
        Set<Long> viewerGuilds = guildsOf(jda, viewerId);
        DataArray out = DataArray.empty();
        Map<Long, Boolean> alliedCache = new LinkedHashMap<>();
        for (Event e : shared) {
            Access access = accessOf(e, viewerGuilds, alliedCache);
            if (access == null) continue;
            out.add(card(jda, e).put("access", access.name()));
        }
        return DataObject.empty().put("events", out).put("generatedAt", Instant.now().toString());
    }

    /** One listed event, with its full description, prizes and (when the host allowed it) standings; null when the viewer may not see it. */
    public DataObject one(JDA jda, String rawId, String viewer) {
        if (rawId == null || !rawId.matches("\\d{1,18}")) return null;
        Optional<Event> found = events.eventById(Long.parseLong(rawId));
        if (found.isEmpty() || !listedNow(found.get())) return null;
        Event e = found.get();
        Access access = accessOf(e, guildsOf(jda, idOf(viewer)), new LinkedHashMap<>());
        if (access == null) return null;
        DataObject card = card(jda, e).put("access", access.name()).put("description", e.description() == null ? "" : e.description());
        DataArray prizes = DataArray.empty();
        for (PrizePlan.Prize p : service.prizes(e.id())) {
            prizes.add(DataObject.empty().put("place", p.place()).put("placeTo", p.placeTo()).put("kind", p.kind().name()).put("description", p.description()).put("amount", p.amount()));
        }
        card.put("prizes", prizes);
        boolean scored = e.status() == Status.RUNNING || e.status() == Status.SCORING || e.status() == Status.DONE;
        if (scored && EventListing.standingsShown(e)) card.put("standings", standings(e));
        // a clan-vs-clan event shows its clans' standings (clan names only) whenever it is open to everyone (T-223)
        else if (scored && EventListing.clanStandingsShown(e)) card.put("standings", standings(e));
        card.put("clanVsClan", com.younglings.bot.events.ClanVsClan.is(e.template()));
        return card;
    }

    /** An answer with its HTTP status, for the routes that change something. */
    public record Answer(int status, DataObject body) {}

    /**
     * Joins a listed event as {@code rawUser} (the Discord id the website signed in), by the same rules as joining from the host's own pages: the person has
     * to be in the host's Discord (D10), so someone who isn't gets 403 with {@code notInServer} and the host's invite link to try again after joining.
     */
    public Answer join(JDA jda, String rawId, String rawUser) {
        Long userId = idOf(rawUser);
        if (userId == null) return new Answer(400, DataObject.empty().put("error", "Sign in with Discord first."));
        if (rawId == null || !rawId.matches("\\d{1,18}")) return new Answer(404, DataObject.empty().put("error", "No such event"));
        Optional<Event> found = events.eventById(Long.parseLong(rawId));
        if (found.isEmpty() || !listedNow(found.get())) return new Answer(404, DataObject.empty().put("error", "No such event"));
        Event e = found.get();
        Guild host = jda == null ? null : jda.getGuildById(e.guildId());
        if (host == null) return new Answer(503, DataObject.empty().put("error", "The host's server can't be reached right now. Try again in a minute."));
        // an alliance event is only for members of allied servers (or of the host): the same rule as seeing it
        if (accessOf(e, guildsOf(jda, userId), new LinkedHashMap<>()) == null && !inServer(host, userId)) return new Answer(404, DataObject.empty().put("error", "No such event"));
        if (!inServer(host, userId)) {
            String invite = host(jda, e.guildId()).getString("inviteUrl", null);
            return new Answer(403, DataObject.empty().put("notInServer", true).put("inviteUrl", invite)
                    .put("error", "To take part you need to be in the " + host.getName() + " Discord server. Join it, then press Join again."));
        }
        try {
            var joined = service.join(e.guildId(), e.id(), e.guildId(), userId, null, false, null);
            return new Answer(200, DataObject.empty().put("joined", true).put("waiting", joined.waiting()).put("position", joined.position()));
        } catch (com.younglings.bot.events.EventProblem p) {
            return new Answer(p.status() >= 400 && p.status() < 500 ? p.status() : 409, DataObject.empty().put("error", p.getMessage()));
        }
    }

    /** Whether someone is in a server, asking Discord when they aren't in the cache. */
    private static boolean inServer(Guild guild, long userId) {
        if (guild.getMemberById(userId) != null) return true;
        try {
            return guild.retrieveMemberById(userId).complete() != null;
        } catch (RuntimeException ex) {
            return false;
        }
    }

    /** Whether an event is on the hub right now: listed, and its server not barred from listing. */
    private boolean listedNow(Event e) {
        return EventListing.listed(e, Instant.now()) && !moderation.barredIds().contains(e.guildId());
    }

    /**
     * Records a report about a listed event for the bot's owner (T-218). Anyone who can see the hub may report; {@code reporter} is whatever the website chose
     * to record (the signed-in name, or nothing). A flood guard keeps it to {@value #MAX_REPORTS_PER_EVENT_PER_DAY} a day per event.
     */
    public Answer report(JDA jda, String rawId, DataObject body) {
        if (rawId == null || !rawId.matches("\\d{1,18}")) return new Answer(404, DataObject.empty().put("error", "No such event"));
        Optional<Event> found = events.eventById(Long.parseLong(rawId));
        if (found.isEmpty() || !listedNow(found.get())) return new Answer(404, DataObject.empty().put("error", "No such event"));
        String reason = body.getString("reason", "").strip();
        if (reason.length() < MIN_REASON || reason.length() > MAX_REASON) {
            return new Answer(400, DataObject.empty().put("error", "Say what is wrong, in " + MIN_REASON + " to " + MAX_REASON + " characters."));
        }
        String reporter = body.hasKey("reporter") && !body.isNull("reporter") ? body.getString("reporter", "").strip() : null;
        if (reporter != null && reporter.length() > 100) reporter = reporter.substring(0, 100);
        Event e = found.get();
        if (!moderation.addReport(e.id(), e.guildId(), reason, reporter == null || reporter.isEmpty() ? null : reporter, MAX_REPORTS_PER_EVENT_PER_DAY)) {
            return new Answer(429, DataObject.empty().put("error", "This event has been reported a lot today already. It will be looked at."));
        }
        return new Answer(202, DataObject.empty().put("ok", true));
    }

    /** How the viewer may see a listed event, or null. */
    private Access accessOf(Event e, Set<Long> viewerGuilds, Map<Long, Boolean> alliedCache) {
        if (viewerGuilds.contains(e.guildId())) return Access.MINE;
        boolean allied = !viewerGuilds.isEmpty() && alliedCache.computeIfAbsent(e.guildId(), host -> allied(host, viewerGuilds));
        if (!EventListing.visible(e, allied)) return null;
        return e.scope() == com.younglings.bot.events.EventRules.Scope.PUBLIC ? Access.PUBLIC : Access.ALLIANCE;
    }

    /** Whether one of the viewer's servers shares an active alliance with the host. */
    private boolean allied(long host, Set<Long> viewerGuilds) {
        for (AllianceRepository.Alliance a : alliances.alliancesOf(host)) {
            List<AllianceRepository.Membership> members = alliances.membersOf(a.id());
            boolean hostActive = members.stream().anyMatch(m -> m.guildId() == host && m.status() == AllianceRules.Status.ACTIVE);
            if (!hostActive) continue;
            if (members.stream().anyMatch(m -> m.status() == AllianceRules.Status.ACTIVE && m.guildId() != host && viewerGuilds.contains(m.guildId()))) return true;
        }
        return false;
    }

    /** The servers JonnyBot shares with the viewer (from what Discord has told it), or none for nobody signed in. */
    private static Set<Long> guildsOf(JDA jda, Long viewerId) {
        Set<Long> ids = new HashSet<>();
        if (viewerId == null || jda == null) return ids;
        User user = jda.getUserById(viewerId);
        if (user != null) user.getMutualGuilds().forEach(g -> ids.add(g.getIdLong()));
        return ids;
    }

    private static Long idOf(String raw) {
        return raw != null && raw.matches("\\d{1,20}") ? Long.parseUnsignedLong(raw) : null;
    }

    /** What a listing card shows: the event and who hosts it, never who is in it. */
    private DataObject card(JDA jda, Event e) {
        EventTemplate template = templates.byKey(e.template()).orElse(null);
        int players = (int) events.participants(e.id()).stream().filter(p -> !p.waiting()).count();
        boolean signupsOpen = e.status() == Status.SIGNUPS && !events.setup(e.id()).signupsClosed();
        return DataObject.empty().put("id", Long.toString(e.id())).put("name", e.name()).put("template", e.template())
                .put("templateLabel", template == null ? e.template() : template.label()).put("status", e.status().name()).put("scope", e.scope().name())
                .put("scoringMode", e.scoringMode().name())
                .put("startsAt", e.startsAt() == null ? null : e.startsAt().toString()).put("endsAt", e.endsAt() == null ? null : e.endsAt().toString())
                .put("signupsOpen", signupsOpen).put("players", players).put("maxParticipants", e.maxParticipants())
                .put("summary", EventListing.shortDescription(e.description())).put("needsRsn", template != null && template.needsRsn())
                .put("host", host(jda, e.guildId()));
    }

    /**
     * The host as the hub shows it: its listed clan profile when there is one, else the server's own name and icon; and where to join it.
     * Never the server's Discord id: the hub is public, and the directory shares no Discord ids (T-182).
     */
    DataObject host(JDA jda, long guildId) {
        Guild guild = jda == null ? null : jda.getGuildById(guildId);
        Optional<ClanProfile> listed = profiles.publicView(guildId, false);
        // the server's invite link (Server setup), else its clan profile's apply link: the same as the "join the Discord first" message
        String invite = guildSettings.getEffective(guildId).inviteUrl();
        if (invite == null || invite.isBlank()) invite = listed.map(ClanProfile::applyUrl).filter(u -> u != null && !u.isBlank()).orElse(null);
        DataObject host = DataObject.empty().put("inviteUrl", invite);
        if (listed.isPresent()) {
            ClanProfile p = listed.get();
            return host.put("name", p.displayName()).put("iconUrl", p.logoUrl()).put("slug", p.slug());
        }
        return host.put("name", guild == null ? "A JonnyBot server" : guild.getName()).put("iconUrl", guild == null ? null : guild.getIconUrl()).put("slug", null);
    }

    /** The top of the standings with RuneScape names only (a team's name for a team); a player with no linked name is "Player n". */
    private DataArray standings(Event e) {
        List<Standing> all = service.standings(e.guildId(), e.id());
        Map<Long, String> teams = new LinkedHashMap<>();
        events.teams(e.id()).forEach(t -> teams.put(t.id(), t.name()));
        DataArray rows = DataArray.empty();
        int rank = 0;
        int unnamed = 0;
        for (Standing s : new ArrayList<>(all).subList(0, Math.min(STANDINGS_SHOWN, all.size()))) {
            rank++;
            long id = Long.parseLong(s.subject().substring(2));
            String name;
            if (s.subject().startsWith("t:")) {
                name = teams.getOrDefault(id, "A team");
            } else {
                List<PlayerLink> mine = links.getLinksForUser(e.guildId(), id);
                name = mine.isEmpty() ? "Player " + (++unnamed) : mine.get(0).rsn();
            }
            rows.add(DataObject.empty().put("rank", rank).put("name", name).put("points", s.points()));
        }
        return rows;
    }
}
