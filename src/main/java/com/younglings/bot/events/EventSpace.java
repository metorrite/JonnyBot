package com.younglings.bot.events;

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * The Discord space of an event: the roles that follow who is in it, and the category and channels they work in. An organiser switches it on per event,
 * chooses for each channel whether JonnyBot makes it or an existing channel is used (or it is left out), and can change what everything is called. Once
 * signups are over, the roster is confirmed and (for teams) the teams are final, the space can be set up.
 * <p>
 * This class only works out <em>what</em>: the settings ({@code config.channels}), who can see and write in each kind of channel, and a {@link Plan} of
 * what has to be created, kept or removed, with Discord's limits checked. Making it happen is {@code EventSpaces}' job. Nothing here knows about Discord
 * beyond the names of its permissions, so all of it can be tested.
 * <p>
 * The rule behind the access: a channel of an event is hidden from everyone, and each role is let back in as far as it needs. Players read the
 * announcements and the board and chat in general; a team's role is the only one in its channel; organisers and staff can see and manage all of it.
 */
public final class EventSpace {
    /** Discord's limits that decide whether a space fits. */
    public static final int MAX_CHANNELS_PER_CATEGORY = 50;
    public static final int MAX_CHANNELS = 500;
    public static final int MAX_ROLES = 250;
    public static final int MAX_OVERWRITES = 100;

    public static final String CATEGORY_KEY = "category";
    public static final String PARTICIPANT_ROLE = "participant";
    public static final String ORGANISER_ROLE = "organiser";

    private EventSpace() {}

    // ---------- the settings ----------

    /** The kinds of channel an event can have. The first six are single channels an organiser chooses; teams and players each get their own. */
    public enum Kind {
        CATEGORY("category", "Category", "The category everything sits in. More are added if it fills up (Discord allows 50 channels in one).", "{event}", false),
        STAFF("staff", "Staff channel", "Where organisers and staff talk. Players can't see it.", "{event} Staff", true),
        ANNOUNCEMENTS("announcements", "Announcements", "Organisers post here. Players can read it but not reply.", "{event} Announcements", true),
        GENERAL("general", "General chat", "Where the players talk.", "{event} General", true),
        BOARD("board", "Live board", "The bingo's live board. Players can read it.", "{event} Live Board", true),
        REVIEW("review", "Proof to review", "Where players' proof lands for staff to approve. Players can't see it.", "{event} Staff Review", true),
        TEAM("teamChannel", "Team channel", "A private channel for each team.", "{event} {team}", false),
        PLAYER("playerChannel", "Player channel", "A private channel for each player of a solo event.", "{event} {player}", false);

        private final String key;
        private final String label;
        private final String hint;
        private final String pattern;
        private final boolean slot;

        Kind(String key, String label, String hint, String pattern, boolean slot) {
            this.key = key;
            this.label = label;
            this.hint = hint;
            this.pattern = pattern;
            this.slot = slot;
        }

        public String key() {
            return key;
        }

        public String label() {
            return label;
        }

        public String hint() {
            return hint;
        }

        /** What the channel is called unless the event says otherwise. */
        public String defaultPattern() {
            return pattern;
        }

        /** Whether this is one of the single channels an organiser chooses for (make it, use an existing one, or leave it out). */
        public boolean isSlot() {
            return slot;
        }

        /** Whether an event made from {@code template} has this channel: the board is a bingo's, and proof is reviewed in every kind but a competition. */
        public boolean appliesTo(String template) {
            return switch (this) {
                case BOARD -> BingoTemplate.KEY.equals(template);
                case REVIEW -> !CompetitionTemplate.KEY.equals(template);
                default -> true;
            };
        }

        public static List<Kind> slots() {
            return java.util.Arrays.stream(values()).filter(Kind::isSlot).toList();
        }

        static Kind byKey(String key) {
            for (Kind k : values()) if (k.key.equals(key)) return k;
            return null;
        }
    }

    /** What the roles are called unless the event says otherwise. */
    public enum RoleName {
        PARTICIPANT("participantRole", "Participant role", "{event} Participant"),
        ORGANISER("organiserRole", "Organiser role", "{event} Organiser"),
        TEAM("teamRole", "Team role", "{event} {team}");

        private final String key;
        private final String label;
        private final String pattern;

        RoleName(String key, String label, String pattern) {
            this.key = key;
            this.label = label;
            this.pattern = pattern;
        }

        public String key() {
            return key;
        }

        public String label() {
            return label;
        }

        public String defaultPattern() {
            return pattern;
        }
    }

    /** How a single channel is provided: made by JonnyBot, an existing channel, or left out. */
    public enum Use { CREATE, EXISTING, NONE }

    public record Choice(Use use, Long channelId) {
        public static final Choice CREATE = new Choice(Use.CREATE, null);
        public static final Choice NONE = new Choice(Use.NONE, null);

        public static Choice existing(long channelId) {
            return new Choice(Use.EXISTING, channelId);
        }
    }

    /**
     * An event's choices. {@code slots} holds only what was chosen; a channel not mentioned is made if it applies (or uses the board or review channel
     * the event already names). {@code names} holds only the patterns changed from the defaults. Off unless an organiser switches it on.
     */
    public record Setting(boolean enabled, boolean teamChannels, boolean playerChannels, Map<Kind, Choice> slots, Map<String, String> names) {
        public static final Setting OFF = new Setting(false, true, false, Map.of(), Map.of());

        public Setting {
            Map<Kind, Choice> chosen = new EnumMap<>(Kind.class);
            if (slots != null) chosen.putAll(slots);
            slots = java.util.Collections.unmodifiableMap(chosen);
            names = names == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(names));
        }

        /** The name pattern for a channel kind. */
        public String pattern(Kind kind) {
            String own = names.get(kind.key());
            return own == null || own.isBlank() ? kind.defaultPattern() : own.strip();
        }

        public String pattern(RoleName role) {
            String own = names.get(role.key());
            return own == null || own.isBlank() ? role.defaultPattern() : own.strip();
        }
    }

    /** The space settings in an event's settings ({@code config.channels}); off when there are none or they can't be read ({@link #problems} reports the latter). */
    public static Setting parse(DataObject config) {
        try {
            if (config == null || !config.hasKey("channels") || config.isNull("channels")) return Setting.OFF;
            DataObject c = config.getObject("channels");
            Map<Kind, Choice> slots = new EnumMap<>(Kind.class);
            if (c.hasKey("slots") && !c.isNull("slots")) {
                DataObject s = c.getObject("slots");
                for (Kind kind : Kind.slots()) {
                    if (!s.hasKey(kind.key()) || s.isNull(kind.key())) continue;
                    Choice choice = choiceOf(s.getObject(kind.key()));
                    if (choice != null) slots.put(kind, choice);
                }
            }
            Map<String, String> names = new LinkedHashMap<>();
            if (c.hasKey("names") && !c.isNull("names")) {
                DataObject n = c.getObject("names");
                for (String key : nameKeys()) {
                    if (n.hasKey(key) && !n.isNull(key) && !n.getString(key).isBlank()) names.put(key, n.getString(key).strip());
                }
            }
            return new Setting(c.getBoolean("enabled", false), c.getBoolean("teamChannels", true), c.getBoolean("playerChannels", false), slots, names);
        } catch (RuntimeException e) {
            return Setting.OFF;
        }
    }

    private static Choice choiceOf(DataObject o) {
        String use = o.getString("use", "create").strip().toLowerCase(Locale.ROOT);
        return switch (use) {
            case "create" -> Choice.CREATE;
            case "none" -> Choice.NONE;
            case "existing" -> {
                Long id = idOf(o, "channelId");
                yield id == null ? null : Choice.existing(id);
            }
            default -> null;
        };
    }

    private static Long idOf(DataObject o, String key) {
        try {
            if (!o.hasKey(key) || o.isNull(key)) return null;
            String raw = o.getString(key).strip();
            return raw.matches("\\d{1,20}") ? Long.parseLong(raw) : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static List<String> nameKeys() {
        List<String> keys = new ArrayList<>();
        for (Kind k : Kind.values()) keys.add(k.key());
        for (RoleName r : RoleName.values()) keys.add(r.key());
        return keys;
    }

    /** The variables a name pattern may use: every name has {@code {event}}, a team's has {@code {team}}, a player's {@code {player}}. */
    static Set<String> variablesOf(String key) {
        if (key.equals(Kind.TEAM.key()) || key.equals(RoleName.TEAM.key())) return Set.of(EventNaming.EVENT, EventNaming.TEAM);
        if (key.equals(Kind.PLAYER.key())) return Set.of(EventNaming.EVENT, EventNaming.PLAYER);
        return Set.of(EventNaming.EVENT);
    }

    /** What is wrong with the space settings in {@code config}, in words, or an empty list. */
    public static List<String> problems(DataObject config) {
        List<String> problems = new ArrayList<>();
        if (config == null || !config.hasKey("channels") || config.isNull("channels")) return problems;
        DataObject c;
        try {
            c = config.getObject("channels");
        } catch (RuntimeException e) {
            return List.of("The roles and channels settings couldn't be read.");
        }
        for (String flag : new String[]{"enabled", "teamChannels", "playerChannels"}) {
            if (c.hasKey(flag) && !c.isNull(flag) && !(c.get(flag) instanceof Boolean)) {
                problems.add("The roles and channels switches have to be on or off.");
                break;
            }
        }
        try {
            if (c.hasKey("slots") && !c.isNull("slots")) {
                DataObject s = c.getObject("slots");
                for (String key : s.keys()) {
                    Kind kind = Kind.byKey(key);
                    if (kind == null || !kind.isSlot()) {
                        problems.add("\"" + key + "\" isn't a channel an event has.");
                        continue;
                    }
                    if (s.isNull(key)) continue;
                    DataObject o = s.getObject(key);
                    String use = o.getString("use", "create").strip().toLowerCase(Locale.ROOT);
                    if (!Set.of("create", "existing", "none").contains(use)) problems.add("For the " + kind.label().toLowerCase(Locale.ROOT) + ", choose to make it, use an existing channel, or leave it out.");
                    else if (use.equals("existing") && idOf(o, "channelId") == null) problems.add("Choose which channel to use for the " + kind.label().toLowerCase(Locale.ROOT) + ".");
                }
            }
        } catch (RuntimeException e) {
            problems.add("The channel choices couldn't be read.");
        }
        try {
            if (c.hasKey("names") && !c.isNull("names")) {
                DataObject n = c.getObject("names");
                for (String key : n.keys()) {
                    if (!nameKeys().contains(key)) {
                        problems.add("\"" + key + "\" isn't something that has a name pattern.");
                    } else if (!n.isNull(key) && !n.getString(key).isBlank()) {
                        problems.addAll(EventNaming.problems(n.getString(key), variablesOf(key), label(key)));
                    }
                }
            }
        } catch (RuntimeException e) {
            problems.add("The name patterns couldn't be read.");
        }
        return problems;
    }

    private static String label(String key) {
        for (Kind k : Kind.values()) if (k.key().equals(key)) return k.label().toLowerCase(Locale.ROOT);
        for (RoleName r : RoleName.values()) if (r.key().equals(key)) return r.label().toLowerCase(Locale.ROOT);
        return key;
    }

    /**
     * How the channel of {@code kind} is provided for an event of {@code template}: what the organiser chose, else the board or review channel the event
     * already names, else made by JonnyBot. Null when the channel doesn't apply to this kind of event.
     */
    public static Choice choice(Setting setting, Kind kind, String template, Long boardChannelId, Long reviewChannelId) {
        if (!kind.appliesTo(template)) return null;
        Choice chosen = setting.slots().get(kind);
        if (chosen != null) return chosen;
        if (kind == Kind.BOARD && boardChannelId != null) return Choice.existing(boardChannelId);
        if (kind == Kind.REVIEW && reviewChannelId != null) return Choice.existing(reviewChannelId);
        return Choice.CREATE;
    }

    // ---------- who can see and write ----------

    /** Who an access rule is about. {@code TEAM} and {@code PLAYER} name one team or one player by {@code ref}; the others are whole groups. */
    public enum Who { EVERYONE, STAFF, ORGANISERS, PARTICIPANTS, TEAM, PLAYER, BOT }

    public record Principal(Who who, long ref) {
        public static Principal of(Who who) {
            return new Principal(who, 0);
        }
    }

    /** How far a group is let into a channel. {@code BOT} is what JonnyBot itself needs to keep the channel. */
    public enum Level { HIDDEN, READ, CHAT, MANAGE, BOT }

    public record Grant(Principal who, Level level) {}

    /** The access rule for a channel of {@code kind}; {@code ref} is the team's id or the player's user id for those kinds. */
    public static List<Grant> access(Kind kind, long ref) {
        List<Grant> grants = new ArrayList<>();
        grants.add(new Grant(Principal.of(Who.EVERYONE), Level.HIDDEN));
        switch (kind) {
            case ANNOUNCEMENTS, BOARD -> grants.add(new Grant(Principal.of(Who.PARTICIPANTS), Level.READ));
            case GENERAL -> grants.add(new Grant(Principal.of(Who.PARTICIPANTS), Level.CHAT));
            case TEAM -> grants.add(new Grant(new Principal(Who.TEAM, ref), Level.CHAT));
            case PLAYER -> grants.add(new Grant(new Principal(Who.PLAYER, ref), Level.CHAT));
            case CATEGORY, STAFF, REVIEW -> { }
        }
        grants.add(new Grant(Principal.of(Who.ORGANISERS), Level.MANAGE));
        grants.add(new Grant(Principal.of(Who.STAFF), Level.MANAGE));
        grants.add(new Grant(Principal.of(Who.BOT), Level.BOT));
        return grants;
    }

    private static final EnumSet<Permission> READING = EnumSet.of(Permission.VIEW_CHANNEL, Permission.MESSAGE_HISTORY, Permission.MESSAGE_ADD_REACTION);
    private static final EnumSet<Permission> WRITING = EnumSet.of(Permission.MESSAGE_SEND, Permission.MESSAGE_SEND_IN_THREADS, Permission.CREATE_PUBLIC_THREADS,
            Permission.CREATE_PRIVATE_THREADS);
    private static final EnumSet<Permission> CHATTING = EnumSet.of(Permission.VIEW_CHANNEL, Permission.MESSAGE_HISTORY, Permission.MESSAGE_SEND, Permission.MESSAGE_ATTACH_FILES,
            Permission.MESSAGE_EMBED_LINKS, Permission.MESSAGE_ADD_REACTION, Permission.MESSAGE_EXT_EMOJI, Permission.USE_APPLICATION_COMMANDS);
    private static final EnumSet<Permission> MANAGING = EnumSet.of(Permission.VIEW_CHANNEL, Permission.MESSAGE_HISTORY, Permission.MESSAGE_SEND, Permission.MESSAGE_ATTACH_FILES,
            Permission.MESSAGE_EMBED_LINKS, Permission.MESSAGE_ADD_REACTION, Permission.MESSAGE_EXT_EMOJI, Permission.USE_APPLICATION_COMMANDS, Permission.MESSAGE_MANAGE,
            Permission.MESSAGE_MENTION_EVERYONE);
    private static final EnumSet<Permission> KEEPING = EnumSet.of(Permission.VIEW_CHANNEL, Permission.MESSAGE_HISTORY, Permission.MESSAGE_SEND, Permission.MESSAGE_ATTACH_FILES,
            Permission.MESSAGE_EMBED_LINKS, Permission.MANAGE_CHANNEL, Permission.MESSAGE_MANAGE);

    /** The permissions a level allows. */
    public static Set<Permission> allowed(Level level) {
        return switch (level) {
            case HIDDEN -> EnumSet.noneOf(Permission.class);
            case READ -> EnumSet.copyOf(READING);
            case CHAT -> EnumSet.copyOf(CHATTING);
            case MANAGE -> EnumSet.copyOf(MANAGING);
            case BOT -> EnumSet.copyOf(KEEPING);
        };
    }

    /** The permissions a level denies: being hidden denies seeing the channel, and reading denies writing in it. */
    public static Set<Permission> denied(Level level) {
        return switch (level) {
            case HIDDEN -> EnumSet.of(Permission.VIEW_CHANNEL);
            case READ -> EnumSet.copyOf(WRITING);
            case CHAT, MANAGE, BOT -> EnumSet.noneOf(Permission.class);
        };
    }

    /** One permission overwrite as Discord stores it: for a role or a member, with the raw allowed and denied bits. */
    public record Overwrite(long id, boolean isRole, long allow, long deny) {}

    /**
     * The permission overwrites for {@code grants}. {@code idsOf} says which Discord ids stand for a principal (the staff group is several roles, a team is
     * one role, a player is one member, the bot is a member); a principal with none is skipped. Principals that end up with the same id are merged, and
     * where they disagree about a permission, denying it wins.
     */
    public static List<Overwrite> overwrites(List<Grant> grants, Function<Principal, List<Long>> idsOf) {
        Map<Long, long[]> merged = new LinkedHashMap<>();
        Map<Long, Boolean> role = new LinkedHashMap<>();
        for (Grant g : grants) {
            long allow = Permission.getRaw(allowed(g.level()));
            long deny = Permission.getRaw(denied(g.level()));
            for (long id : idsOf.apply(g.who())) {
                long[] bits = merged.computeIfAbsent(id, k -> new long[2]);
                bits[0] |= allow;
                bits[1] |= deny;
                role.putIfAbsent(id, isRole(g.who()));
            }
        }
        List<Overwrite> result = new ArrayList<>();
        merged.forEach((id, bits) -> result.add(new Overwrite(id, role.get(id), bits[0] & ~bits[1], bits[1])));
        return result;
    }

    /** Whether a principal is a role (everyone, staff, organisers, participants, a team) rather than a member (a player, the bot). */
    public static boolean isRole(Principal who) {
        return who.who() != Who.PLAYER && who.who() != Who.BOT;
    }

    /**
     * What JonnyBot needs to be allowed to do on the server to make and run a space: manage roles and channels, and hold every permission it hands out in a
     * channel (Discord refuses to let someone give a permission they don't have).
     */
    public static final List<Permission> BOT_NEEDS = botNeeds();

    private static List<Permission> botNeeds() {
        EnumSet<Permission> needs = EnumSet.of(Permission.MANAGE_ROLES);
        needs.addAll(READING);
        needs.addAll(CHATTING);
        needs.addAll(MANAGING);
        needs.addAll(KEEPING);
        return List.copyOf(needs);
    }

    // ---------- when it can be set up ----------

    /** What an event has to have behind it before its space can be set up. */
    public record Readiness(EventRules.Status status, boolean teamScored, boolean signupsClosed, boolean rosterConfirmed, boolean teamsFinal) {}

    /**
     * Why the space can't be set up yet, as sentences, or an empty list when it can: signups are over, the roster is confirmed and, for an event scored by
     * team, the teams are final. An event that has already started has been through all that.
     */
    public static List<String> notReady(Readiness r) {
        List<String> why = new ArrayList<>();
        if (r.status() == EventRules.Status.DONE || r.status() == EventRules.Status.CANCELLED) return List.of("This event is over.");
        boolean started = r.status() == EventRules.Status.RUNNING || r.status() == EventRules.Status.SCORING;
        if (started) return why;
        if (r.status() == EventRules.Status.DRAFT) {
            why.add("Open signups, take them, then close them.");
            return why;
        }
        if (!r.signupsClosed()) why.add("Close signups first.");
        else if (!r.rosterConfirmed()) why.add("Confirm the roster first.");
        if (r.teamScored() && !r.teamsFinal()) why.add("Finalise the teams first.");
        return why;
    }

    // ---------- the plan ----------

    public record TeamRef(long id, String name) {}

    /**
     * A player who holds a place and counts, as the space needs them: who they are and which team they are on. A player who hasn't joined the server can't
     * be given a role or a channel yet, but is still wanted: they get their role the moment they join.
     */
    public record PlayerRef(long userId, String name, Long teamId, boolean inServer) {
        public PlayerRef(long userId, String name, Long teamId) {
            this(userId, name, teamId, true);
        }
    }

    /** A channel JonnyBot made for the event and still finds on the server. {@code parent} is the key of the category it sits in (null for a category). */
    public record HeldChannel(long id, String name, String parent) {}

    public record HeldRole(long id, String name) {}

    /**
     * What the plan is made from. {@code heldRoles} and {@code heldChannels} are what JonnyBot made before, by key, and only what still exists on the server
     * (what someone deleted there counts as missing). {@code pickProblems} says why a chosen existing channel can't be used. {@code available} is the room left on
     * the server.
     */
    public record Facts(String eventName, String template, boolean teamScored, Setting setting, Long boardChannelId, Long reviewChannelId, List<TeamRef> teams,
                        List<PlayerRef> players, Set<Long> organiserIds, int excludedPlayers, Map<String, HeldRole> heldRoles, Map<String, HeldChannel> heldChannels,
                        Map<Kind, String> pickProblems, Room available, Readiness readiness) {
        public Facts {
            teams = List.copyOf(teams);
            players = List.copyOf(players);
            organiserIds = Set.copyOf(organiserIds);
            heldRoles = Map.copyOf(heldRoles);
            heldChannels = Map.copyOf(heldChannels);
            pickProblems = Map.copyOf(pickProblems);
        }
    }

    /** How many more roles and channels the server can take, and what JonnyBot lacks permission for. */
    public record Room(int roles, int channels, List<String> missingPermissions) {
        public Room {
            missingPermissions = List.copyOf(missingPermissions);
        }

        /** Plenty of room and every permission: for when the server isn't being asked. */
        public static final Room UNLIMITED = new Room(MAX_ROLES, MAX_CHANNELS, List.of());
    }

    public enum Action {
        /** Will be made. */
        CREATE,
        /** Was made before and is still there. */
        KEEP,
        /** A channel that already existed, chosen by an organiser; JonnyBot only uses it and never changes who can see it. */
        USE_EXISTING,
        /** Was made before but is no longer wanted. Only removed when the organiser says so. */
        REMOVE
    }

    public record RoleItem(String key, String name, Action action, Long id, int members, String note) {}

    /** {@code parent} is the key of the category a channel sits in (null for a category and for a chosen existing channel); {@code access} is who may see and write. */
    public record ChannelItem(String key, Kind kind, String name, Action action, Long id, String parent, String note, List<Grant> access) {
        public ChannelItem {
            access = List.copyOf(access);
        }
    }

    public record Plan(List<RoleItem> roles, List<ChannelItem> channels, List<String> blockers, List<String> warnings) {
        public Plan {
            roles = List.copyOf(roles);
            channels = List.copyOf(channels);
            blockers = List.copyOf(blockers);
            warnings = List.copyOf(warnings);
        }

        /** This plan with one more thing worth knowing. */
        public Plan withWarning(String warning) {
            List<String> all = new ArrayList<>(warnings);
            all.add(warning);
            return new Plan(roles, channels, blockers, all);
        }

        public long count(Action action) {
            return roles.stream().filter(r -> r.action() == action).count() + channels.stream().filter(c -> c.action() == action).count();
        }

        public long newRoles() {
            return roles.stream().filter(r -> r.action() == Action.CREATE).count();
        }

        public long newChannels() {
            return channels.stream().filter(c -> c.action() == Action.CREATE).count();
        }

        /** Whether everything wanted is there, so applying would change nothing (apart from anything to remove). */
        public boolean complete() {
            return newRoles() == 0 && newChannels() == 0;
        }

        /** The things that still have to be made, in a few words each, for the setup checklist. */
        public List<String> missing() {
            List<String> missing = new ArrayList<>();
            roles.stream().filter(r -> r.action() == Action.CREATE).forEach(r -> missing.add("the role " + r.name()));
            channels.stream().filter(c -> c.action() == Action.CREATE).forEach(c -> missing.add(c.kind() == Kind.CATEGORY ? "the category " + c.name() : "the channel " + c.name()));
            return missing;
        }
    }

    private static Map<String, String> varsOf(String event) {
        Map<String, String> vars = new java.util.HashMap<>();
        vars.put(EventNaming.EVENT, event);
        return vars;
    }

    private static Map<String, String> varsOf(String event, String key, String value) {
        Map<String, String> vars = varsOf(event);
        vars.put(key, value);
        return vars;
    }

    /**
     * Works out what setting up the space has to do. The roles come first (everyone taking part, the organisers, a role for each team that has a channel),
     * then the category and the channels: staff, announcements, general, the board and the proof channel where they apply, then a channel per team (or, in a
     * solo event that wants them, per player). New channels go in the first category with room and a new category is made when it is full. What was made
     * before is kept, and what is no longer wanted is listed for removal. Blockers are everything that stops it from being applied; warnings are worth
     * knowing and don't.
     */
    public static Plan plan(Facts f) {
        List<String> blockers = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Setting s = f.setting();
        if (!s.enabled()) blockers.add("Roles and channels are switched off for this event.");
        blockers.addAll(notReady(f.readiness()));

        // ----- roles -----
        boolean teamRoles = f.teamScored() && s.teamChannels();
        Map<String, String> roleNames = new LinkedHashMap<>();
        Map<String, Integer> members = new LinkedHashMap<>();
        String event = f.eventName();
        roleNames.put(PARTICIPANT_ROLE, EventNaming.label(s.pattern(RoleName.PARTICIPANT), varsOf(event), event + " Participant"));
        members.put(PARTICIPANT_ROLE, f.players().size());
        roleNames.put(ORGANISER_ROLE, EventNaming.label(s.pattern(RoleName.ORGANISER), varsOf(event), event + " Organiser"));
        members.put(ORGANISER_ROLE, f.organiserIds().size());
        if (teamRoles) {
            for (TeamRef t : f.teams()) {
                roleNames.put(teamKey(t.id()), EventNaming.label(s.pattern(RoleName.TEAM), varsOf(event, EventNaming.TEAM, t.name()), event + " " + t.name()));
                members.put(teamKey(t.id()), (int) f.players().stream().filter(p -> p.teamId() != null && p.teamId() == t.id()).count());
            }
        }
        List<String> uniqueRoleNames = EventNaming.unique(new ArrayList<>(roleNames.values()), " ");
        List<RoleItem> roles = new ArrayList<>();
        int at = 0;
        for (String key : roleNames.keySet()) {
            HeldRole held = f.heldRoles().get(key);
            roles.add(new RoleItem(key, held != null ? held.name() : uniqueRoleNames.get(at), held != null ? Action.KEEP : Action.CREATE, held == null ? null : held.id(), members.get(key), ""));
            at++;
        }
        f.heldRoles().forEach((key, held) -> {
            if (!roleNames.containsKey(key)) roles.add(new RoleItem(key, held.name(), Action.REMOVE, held.id(), 0, "No longer needed."));
        });

        // ----- channels -----
        List<Wanted> wanted = new ArrayList<>();
        Set<Long> picked = new LinkedHashSet<>();
        for (Kind kind : Kind.slots()) {
            Choice choice = choice(s, kind, f.template(), f.boardChannelId(), f.reviewChannelId());
            if (choice == null || choice.use() == Use.NONE) continue;
            if (choice.use() == Use.EXISTING) {
                String problem = f.pickProblems().get(kind);
                if (problem != null) blockers.add(problem);
                if (!picked.add(choice.channelId())) blockers.add("The same channel is chosen for two things. Choose a different one for the " + kind.label().toLowerCase(Locale.ROOT) + ".");
                wanted.add(new Wanted(kind.key(), kind, null, choice.channelId(), 0));
            } else {
                wanted.add(new Wanted(kind.key(), kind, EventNaming.channel(s.pattern(kind), varsOf(event), kind.key()), null, 0));
            }
        }
        if (f.teamScored() && s.teamChannels()) {
            for (TeamRef t : f.teams()) {
                wanted.add(new Wanted(teamKey(t.id()), Kind.TEAM, EventNaming.channel(s.pattern(Kind.TEAM), varsOf(event, EventNaming.TEAM, t.name()), t.name()), null, t.id()));
            }
        }
        if (!f.teamScored() && s.playerChannels()) {
            for (PlayerRef p : f.players()) {
                if (!p.inServer()) continue;
                wanted.add(new Wanted(playerKey(p.userId()), Kind.PLAYER, EventNaming.channel(s.pattern(Kind.PLAYER), varsOf(event, EventNaming.PLAYER, p.name()), p.name()), null, p.userId()));
            }
        } else if (f.teamScored() && s.playerChannels()) {
            warnings.add("Player channels are only made for solo events; this event is scored by team, so each team gets one channel.");
        }

        List<Wanted> newOnes = new ArrayList<>();
        for (Wanted w : wanted) {
            if (w.existing == null && !f.heldChannels().containsKey(w.key)) newOnes.add(w);
        }
        List<String> uniqueNames = EventNaming.unique(newOnes.stream().map(w -> w.name).toList(), "-");
        for (int i = 0; i < newOnes.size(); i++) newOnes.get(i).name = uniqueNames.get(i);

        // categories: those already made, then as many new ones as the new channels need
        Map<String, Integer> filled = new TreeMap<>();
        f.heldChannels().forEach((key, held) -> {
            if (held.parent() != null) filled.merge(held.parent(), 1, Integer::sum);
        });
        List<ChannelItem> channels = new ArrayList<>();
        String categoryPattern = s.pattern(Kind.CATEGORY);
        String categoryName = EventNaming.label(categoryPattern, varsOf(event), event);
        Map<String, ChannelItem> categories = new LinkedHashMap<>();
        Map<String, String> placed = new LinkedHashMap<>();
        for (Wanted w : newOnes) {
            String parent = null;
            for (int n = 1; parent == null; n++) {
                String key = categoryKeyOf(n);
                if (filled.getOrDefault(key, 0) < MAX_CHANNELS_PER_CATEGORY) {
                    parent = key;
                    filled.merge(key, 1, Integer::sum);
                }
            }
            placed.put(w.key, parent);
            if (!categories.containsKey(parent)) {
                HeldChannel held = f.heldChannels().get(parent);
                int n = parent.equals(CATEGORY_KEY) ? 1 : Integer.parseInt(parent.substring(CATEGORY_KEY.length() + 1));
                categories.put(parent, new ChannelItem(parent, Kind.CATEGORY, held != null ? held.name() : n == 1 ? categoryName : cut(categoryName, " (" + n + ")"),
                        held != null ? Action.KEEP : Action.CREATE, held == null ? null : held.id(), null, "", access(Kind.CATEGORY, 0)));
            }
        }
        // categories already made that hold channels are kept even when nothing new goes in them
        f.heldChannels().forEach((key, held) -> {
            if (isCategoryKey(key) && !categories.containsKey(key)) {
                categories.put(key, new ChannelItem(key, Kind.CATEGORY, held.name(), Action.KEEP, held.id(), null, "", access(Kind.CATEGORY, 0)));
            }
        });
        categories.values().stream().sorted(java.util.Comparator.comparing(c -> categoryNumber(c.key()))).forEach(channels::add);
        for (Wanted w : wanted) {
            HeldChannel held = f.heldChannels().get(w.key);
            if (w.existing != null) {
                channels.add(new ChannelItem(w.key, w.kind, "", Action.USE_EXISTING, w.existing, null, "Uses a channel that is already there; who can see it stays as it is.", List.of()));
            } else if (held != null) {
                channels.add(new ChannelItem(w.key, w.kind, held.name(), Action.KEEP, held.id(), held.parent(), "", access(w.kind, w.ref)));
            } else {
                channels.add(new ChannelItem(w.key, w.kind, w.name, Action.CREATE, null, placed.get(w.key), "", access(w.kind, w.ref)));
            }
        }
        Set<String> wantedKeys = new LinkedHashSet<>();
        wanted.forEach(w -> wantedKeys.add(w.key));
        f.heldChannels().forEach((key, held) -> {
            if (!isCategoryKey(key) && !wantedKeys.contains(key)) {
                channels.add(new ChannelItem(key, keyKind(key), held.name(), Action.REMOVE, held.id(), held.parent(), "No longer needed.", List.of()));
            }
        });

        // ----- limits and permissions -----
        Plan draft = new Plan(roles, channels, blockers, warnings);
        long newRoles = draft.newRoles();
        long newChannels = draft.newChannels();
        if (newRoles > f.available().roles()) blockers.add("This needs " + newRoles + " new roles but the server only has room for " + Math.max(0, f.available().roles()) + " more (Discord allows " + MAX_ROLES + ").");
        if (newChannels > f.available().channels()) blockers.add("This needs " + newChannels + " new channels and categories but the server only has room for " + Math.max(0, f.available().channels()) + " more (Discord allows " + MAX_CHANNELS + ").");
        if (!f.available().missingPermissions().isEmpty() && (newRoles > 0 || newChannels > 0)) {
            blockers.add("JonnyBot needs these permissions on the server: " + String.join(", ", f.available().missingPermissions()) + ".");
        }
        if (draft.newChannels() > 0 && channels.stream().filter(c -> c.action() == Action.CREATE && c.kind() == Kind.CATEGORY).count() > 1) {
            warnings.add("The channels don't fit in one category (Discord allows " + MAX_CHANNELS_PER_CATEGORY + "), so more categories are made.");
        }
        if (f.excludedPlayers() > 0) {
            warnings.add(f.excludedPlayers() == 1 ? "1 player on the roster isn't counted yet (waiting for a place or not verified), so they get no role." : f.excludedPlayers() + " players on the roster aren't counted yet (waiting for a place or not verified), so they get no role.");
        }
        if (f.players().isEmpty()) warnings.add("Nobody is on the roster yet, so no one would get the role.");
        if (f.teamScored() && s.teamChannels() && f.teams().isEmpty()) warnings.add("There are no teams, so there are no team channels.");
        return new Plan(roles, channels, blockers, warnings);
    }

    private static final class Wanted {
        final String key;
        final Kind kind;
        String name;
        final Long existing;
        final long ref;

        Wanted(String key, Kind kind, String name, Long existing, long ref) {
            this.key = key;
            this.kind = kind;
            this.name = name;
            this.existing = existing;
            this.ref = ref;
        }
    }

    private static String cut(String name, String suffix) {
        return (name.length() + suffix.length() > EventNaming.MAX_LENGTH ? name.substring(0, EventNaming.MAX_LENGTH - suffix.length()) : name) + suffix;
    }

    public static String teamKey(long teamId) {
        return "team:" + teamId;
    }

    public static String playerKey(long userId) {
        return "player:" + userId;
    }

    public static String categoryKeyOf(int number) {
        return number <= 1 ? CATEGORY_KEY : CATEGORY_KEY + ":" + number;
    }

    public static boolean isCategoryKey(String key) {
        return key.equals(CATEGORY_KEY) || key.startsWith(CATEGORY_KEY + ":");
    }

    private static int categoryNumber(String key) {
        return key.equals(CATEGORY_KEY) ? 1 : Integer.parseInt(key.substring(CATEGORY_KEY.length() + 1));
    }

    /** The kind of channel a key stands for. */
    public static Kind keyKind(String key) {
        if (isCategoryKey(key)) return Kind.CATEGORY;
        if (key.startsWith("team:")) return Kind.TEAM;
        if (key.startsWith("player:")) return Kind.PLAYER;
        Kind kind = Kind.byKey(key);
        return kind == null ? Kind.GENERAL : kind;
    }

    // ---------- who holds which role ----------

    /** Who should hold each role of the event (by role key), from the roster, the teams and the organisers. */
    public static Map<String, Set<Long>> wantedMembers(boolean teamScored, boolean teamChannels, List<PlayerRef> players, Set<Long> organisers) {
        Map<String, Set<Long>> wanted = new LinkedHashMap<>();
        Set<Long> everyone = new LinkedHashSet<>();
        players.forEach(p -> everyone.add(p.userId()));
        wanted.put(PARTICIPANT_ROLE, everyone);
        wanted.put(ORGANISER_ROLE, new LinkedHashSet<>(organisers));
        if (teamScored && teamChannels) {
            for (PlayerRef p : players) {
                if (p.teamId() != null) wanted.computeIfAbsent(teamKey(p.teamId()), k -> new LinkedHashSet<>()).add(p.userId());
            }
        }
        return wanted;
    }

    /** Who to give each role to and who to take it from. */
    public record Changes(Map<String, Set<Long>> add, Map<String, Set<Long>> remove) {
        public boolean isEmpty() {
            return add.values().stream().allMatch(Set::isEmpty) && remove.values().stream().allMatch(Set::isEmpty);
        }

        public int count() {
            return add.values().stream().mapToInt(Set::size).sum() + remove.values().stream().mapToInt(Set::size).sum();
        }
    }

    /**
     * What has to change so that {@code granted} (who JonnyBot gave each role to) becomes {@code wanted}. A role JonnyBot holds a record for but that is
     * no longer wanted is emptied. {@code roleKeys} are the roles that exist; nothing is added to a role that doesn't.
     */
    public static Changes changes(Map<String, Set<Long>> wanted, Map<String, Set<Long>> granted, Set<String> roleKeys) {
        Map<String, Set<Long>> add = new LinkedHashMap<>();
        Map<String, Set<Long>> remove = new LinkedHashMap<>();
        Set<String> keys = new LinkedHashSet<>(roleKeys);
        keys.addAll(granted.keySet());
        for (String key : keys) {
            Set<Long> want = roleKeys.contains(key) ? wanted.getOrDefault(key, Set.of()) : Set.of();
            Set<Long> have = granted.getOrDefault(key, Set.of());
            Set<Long> toAdd = new LinkedHashSet<>(want);
            toAdd.removeAll(have);
            Set<Long> toRemove = new LinkedHashSet<>(have);
            toRemove.removeAll(want);
            if (!toAdd.isEmpty()) add.put(key, toAdd);
            if (!toRemove.isEmpty()) remove.put(key, toRemove);
        }
        return new Changes(add, remove);
    }

    // ---------- what the website is shown ----------

    /** The settings as the website's Channels tab needs them: the choices with defaults filled in, and every pattern with its default. */
    public static DataObject settingJson(Setting s, String template, Long boardChannelId, Long reviewChannelId) {
        DataArray slots = DataArray.empty();
        for (Kind kind : Kind.slots()) {
            Choice choice = choice(s, kind, template, boardChannelId, reviewChannelId);
            slots.add(DataObject.empty().put("key", kind.key()).put("label", kind.label()).put("hint", kind.hint()).put("applies", choice != null).put("explicit", s.slots().containsKey(kind))
                    .put("use", choice == null ? "none" : choice.use().name().toLowerCase(Locale.ROOT)).put("channelId", choice == null || choice.channelId() == null ? null : Long.toString(choice.channelId()))
                    .put("pattern", s.pattern(kind)).put("defaultPattern", kind.defaultPattern()));
        }
        DataArray names = DataArray.empty();
        for (Kind kind : new Kind[]{Kind.CATEGORY, Kind.TEAM, Kind.PLAYER}) {
            names.add(DataObject.empty().put("key", kind.key()).put("label", kind.label()).put("hint", kind.hint()).put("pattern", s.pattern(kind)).put("defaultPattern", kind.defaultPattern())
                    .put("variables", variablesArray(kind.key())));
        }
        for (RoleName role : RoleName.values()) {
            names.add(DataObject.empty().put("key", role.key()).put("label", role.label()).put("hint", "").put("pattern", s.pattern(role)).put("defaultPattern", role.defaultPattern())
                    .put("variables", variablesArray(role.key())));
        }
        return DataObject.empty().put("enabled", s.enabled()).put("teamChannels", s.teamChannels()).put("playerChannels", s.playerChannels()).put("slots", slots).put("names", names);
    }

    private static DataArray variablesArray(String key) {
        DataArray array = DataArray.empty();
        variablesOf(key).stream().sorted().forEach(array::add);
        return array;
    }
}
