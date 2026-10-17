package com.younglings.bot.events;

import com.younglings.bot.events.EventRepository.Event;
import com.younglings.bot.events.EventRepository.Participant;
import com.younglings.bot.events.EventRules.ScoringMode;
import com.younglings.bot.events.EventSpace.Choice;
import com.younglings.bot.events.EventSpace.Facts;
import com.younglings.bot.events.EventSpace.HeldChannel;
import com.younglings.bot.events.EventSpace.HeldRole;
import com.younglings.bot.events.EventSpace.Kind;
import com.younglings.bot.events.EventSpace.Plan;
import com.younglings.bot.events.EventSpace.PlayerRef;
import com.younglings.bot.events.EventSpace.Readiness;
import com.younglings.bot.events.EventSpace.Room;
import com.younglings.bot.events.EventSpace.TeamRef;
import com.younglings.bot.events.EventSpace.Use;
import com.younglings.bot.permission.PermissionGroup;
import com.younglings.bot.permission.PermissionGroupService;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Reads everything an event's roles and channels depend on (the roster, teams and organisers; what JonnyBot made before and what is still there; which roles
 * are staff; how much room and which permissions the server has) and hands it to {@link EventSpace} to work out what to do. This class only reads: it shows
 * what setting up the space would do and changes nothing on Discord.
 */
@BService
public class EventSpaces {
    private static final Logger log = LoggerFactory.getLogger(EventSpaces.class);

    private final EventRepository events;
    private final EventService service;
    private final EventSpaceRepository space;
    private final PermissionGroupService groups;

    public EventSpaces(EventRepository events, EventService service, EventSpaceRepository space, PermissionGroupService groups) {
        this.events = events;
        this.service = service;
        this.space = space;
        this.groups = groups;
    }

    /** What the plan was made from, kept so the page can say who isn't in the server and which roles count as staff. */
    public record Gathered(Facts facts, Map<Long, Member> present, List<Role> staffRoles, EventSettings settings) {}

    /** What setting up the space would do right now: the plan, plus a note on players who haven't joined the server. */
    public record Preview(Plan plan, Gathered gathered) {}

    public Preview preview(Guild guild, Event event) {
        Gathered gathered = gather(guild, event);
        Plan plan = EventSpace.plan(gathered.facts());
        long absent = gathered.facts().players().stream().filter(p -> !p.inServer()).count();
        if (absent > 0) {
            plan = plan.withWarning(absent == 1 ? "1 player hasn't joined this Discord server yet. They get their role as soon as they do." : absent + " players haven't joined this Discord server yet. They get their role as soon as they do.");
        }
        return new Preview(plan, gathered);
    }

    /** The facts for {@code event} in {@code guild}, read now. */
    public Gathered gather(Guild guild, Event event) {
        EventSettings settings = service.settings(event);
        List<Participant> counted = service.countedPlayers(event);
        int excluded = events.participants(event.id()).size() - counted.size();
        List<EventRepository.Team> teams = events.teams(event.id());
        Set<Long> teamIds = new HashSet<>();
        teams.forEach(t -> teamIds.add(t.id()));
        boolean teamScored = event.scoringMode() == ScoringMode.TEAM;

        Set<Long> ask = new LinkedHashSet<>();
        counted.forEach(p -> ask.add(p.userId()));
        ask.addAll(settings.organisers());
        Presence presence = presenceOf(guild, ask);
        Map<Long, Member> present = presence.members();
        List<PlayerRef> players = new ArrayList<>();
        for (Participant p : counted) {
            Member member = present.get(p.userId());
            Long team = p.teamId() != null && teamIds.contains(p.teamId()) ? p.teamId() : null;
            // when Discord couldn't be asked, nobody is assumed to be missing
            players.add(new PlayerRef(p.userId(), member != null ? member.getEffectiveName() : "player " + shortId(p.userId()), team, member != null || !presence.complete()));
        }

        Map<String, HeldRole> heldRoles = new LinkedHashMap<>();
        for (EventSpaceRepository.RoleRow row : space.roles(event.id())) {
            Role role = guild.getRoleById(row.roleId());
            if (role != null) heldRoles.put(row.key(), new HeldRole(role.getIdLong(), role.getName()));
        }
        Map<String, HeldChannel> heldChannels = new LinkedHashMap<>();
        for (EventSpaceRepository.ChannelRow row : space.channels(event.id())) {
            if (!row.owned()) continue;
            GuildChannel channel = guild.getGuildChannelById(row.channelId());
            if (channel != null) heldChannels.put(row.key(), new HeldChannel(channel.getIdLong(), channel.getName(), row.parent()));
        }

        EventRepository.Setup setup = events.setup(event.id());
        Readiness readiness = new Readiness(event.status(), teamScored, setup.signupsClosed(), setup.rosterConfirmed(), setup.teamsFinalised());
        EventSpace.Setting setting = settings.channels();
        Facts facts = new Facts(event.name(), event.template(), teamScored, setting, settings.boardChannelId(), settings.reviewChannelId(),
                teams.stream().map(t -> new TeamRef(t.id(), t.name())).toList(), players, settings.organisers(), excluded, heldRoles, heldChannels,
                pickProblems(guild, event, settings), roomOf(guild), readiness);
        return new Gathered(facts, present, staffRoles(guild), settings);
    }

    private static String shortId(long id) {
        String text = Long.toString(id);
        return text.length() <= 4 ? text : text.substring(text.length() - 4);
    }

    /** Who is in the server among those asked about, and whether Discord could be asked (if not, those missing from {@code members} may be in the server after all). */
    record Presence(Map<Long, Member> members, boolean complete) {}

    /** The members among {@code ids} who are in the server: from the cache, and for the rest by asking Discord (100 at a time). */
    Presence presenceOf(Guild guild, Collection<Long> ids) {
        Map<Long, Member> found = new HashMap<>();
        List<Long> missing = new ArrayList<>();
        for (long id : ids) {
            Member cached = guild.getMemberById(id);
            if (cached != null) found.put(id, cached);
            else missing.add(id);
        }
        boolean complete = true;
        for (int i = 0; i < missing.size(); i += 100) {
            List<Long> chunk = missing.subList(i, Math.min(missing.size(), i + 100));
            try {
                CompletableFuture<List<Member>> asked = new CompletableFuture<>();
                guild.retrieveMembersByIds(chunk).onSuccess(asked::complete).onError(asked::completeExceptionally);
                for (Member m : asked.get(30, TimeUnit.SECONDS)) found.put(m.getIdLong(), m);
            } catch (Exception e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                log.warn("Could not check which players are in the server", e);
                complete = false;
            }
        }
        return new Presence(found, complete);
    }

    /** The roles that see everything in every event: the Admin group, and when it includes higher roles, every role above its lowest (bots' own roles aside). */
    public List<Role> staffRoles(Guild guild) {
        Optional<PermissionGroup> group = groups.group(guild.getIdLong(), PermissionGroup.ADMIN);
        if (group.isEmpty()) return List.of();
        List<Role> configured = new ArrayList<>();
        for (long id : group.get().roleIds()) {
            Role role = guild.getRoleById(id);
            if (role != null) configured.add(role);
        }
        Set<Role> staff = new LinkedHashSet<>(configured);
        if (group.get().includeHigher() && !configured.isEmpty()) {
            int lowest = configured.stream().mapToInt(Role::getPosition).min().orElse(Integer.MAX_VALUE);
            for (Role role : guild.getRoles()) {
                if (!role.isPublicRole() && !role.isManaged() && role.getPosition() >= lowest) staff.add(role);
            }
        }
        return List.copyOf(staff);
    }

    /** How many more roles and channels the server can take and what JonnyBot lacks permission for. */
    Room roomOf(Guild guild) {
        List<String> missing = new ArrayList<>();
        for (Permission p : EventSpace.BOT_NEEDS) {
            if (!guild.getSelfMember().hasPermission(p)) missing.add(p.getName());
        }
        return new Room(EventSpace.MAX_ROLES - guild.getRoles().size(), EventSpace.MAX_CHANNELS - guild.getChannels().size(), missing);
    }

    /** Why a channel an organiser chose for a slot can't be used, per slot. */
    private Map<Kind, String> pickProblems(Guild guild, Event event, EventSettings settings) {
        Map<Kind, String> problems = new EnumMap<>(Kind.class);
        for (Kind kind : Kind.slots()) {
            Choice choice = EventSpace.choice(settings.channels(), kind, event.template(), settings.boardChannelId(), settings.reviewChannelId());
            if (choice == null || choice.use() != Use.EXISTING) continue;
            String problem = pickProblem(guild, kind, choice.channelId());
            if (problem != null) problems.put(kind, problem);
        }
        return problems;
    }

    private static String pickProblem(Guild guild, Kind kind, long channelId) {
        String what = kind.label().toLowerCase(java.util.Locale.ROOT);
        GuildChannel channel = guild.getGuildChannelById(channelId);
        if (channel == null) return "The channel chosen for the " + what + " can't be found. It may have been deleted.";
        if (channel.getType() != ChannelType.TEXT && channel.getType() != ChannelType.NEWS) return "#" + channel.getName() + ", chosen for the " + what + ", isn't a text channel.";
        if (!guild.getSelfMember().hasPermission(channel, Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND, Permission.MESSAGE_ATTACH_FILES, Permission.MESSAGE_EMBED_LINKS)) {
            return "JonnyBot needs to see, send messages, attach files and embed links in #" + channel.getName() + ", chosen for the " + what + ".";
        }
        return null;
    }
}
