package com.younglings.bot.events;

import com.younglings.bot.events.EventRepository.Event;
import com.younglings.bot.events.EventRules.Status;
import com.younglings.bot.events.EventSpace.Action;
import com.younglings.bot.events.EventSpace.ChannelItem;
import com.younglings.bot.events.EventSpace.Kind;
import com.younglings.bot.events.EventSpace.Overwrite;
import com.younglings.bot.events.EventSpace.Plan;
import com.younglings.bot.events.EventSpace.Principal;
import com.younglings.bot.events.EventSpace.RoleItem;
import io.github.freya022.botcommands.api.core.annotations.BEventListener;
import io.github.freya022.botcommands.api.core.events.InjectedJDAEvent;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.UserSnowflake;
import net.dv8tion.jda.api.entities.channel.concrete.Category;
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel;
import net.dv8tion.jda.api.entities.channel.attribute.IPermissionContainer;
import net.dv8tion.jda.api.events.guild.member.GuildMemberJoinEvent;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.requests.ErrorResponse;
import net.dv8tion.jda.api.requests.restaction.ChannelAction;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Makes an event's roles and channels on Discord and keeps them right. Setting up creates what {@link EventSpace#plan} says is missing (the roles, then the
 * categories and channels with who can see what), recording each thing as soon as it exists, so a setup that stops halfway carries on where it was when it
 * is run again. Taking it down removes only what JonnyBot made. Who holds each role follows the roster, the teams and the organisers, and is put right
 * whenever one of those changes, whenever a player joins the server, and every quarter of an hour.
 * <p>
 * Setting up and removing run in the background (Discord limits how fast channels can be made), one at a time per event; {@link #job} says how far one got.
 */
@BService
public class EventSpaceOps {
    private static final Logger log = LoggerFactory.getLogger(EventSpaceOps.class);
    private static final long DEBOUNCE_SECONDS = 3;
    private static final long SWEEP_MINUTES = 15;
    private static final int NOTE_LIMIT = 500;

    /** How far a background job got: {@code state} is running, done or failed, and {@code message} says what it is doing or why it stopped. */
    public static final class Job {
        private final String kind;
        private final Instant startedAt = Instant.now();
        private volatile String state = "running";
        private volatile int done;
        private volatile int total;
        private volatile String message = "Starting…";
        private volatile Instant finishedAt;

        Job(String kind) {
            this.kind = kind;
        }

        public String kind() {
            return kind;
        }

        public String state() {
            return state;
        }

        public int done() {
            return done;
        }

        public int total() {
            return total;
        }

        public String message() {
            return message;
        }

        public Instant startedAt() {
            return startedAt;
        }

        public Instant finishedAt() {
            return finishedAt;
        }

        public boolean running() {
            return state.equals("running");
        }

        void step(String what) {
            message = what;
            done++;
        }

        void finish(String text) {
            message = text;
            state = "done";
            finishedAt = Instant.now();
        }

        void fail(String text) {
            message = text;
            state = "failed";
            finishedAt = Instant.now();
        }
    }

    private final EventRepository events;
    private final EventService service;
    private final EventSpaceRepository space;
    private final EventSpaces spaces;
    private final EventChanges changes;
    private final ScheduledExecutorService executor = Executors.newScheduledThreadPool(3, (ThreadFactory) runnable -> {
        Thread thread = new Thread(runnable, "event-space");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<Long, Job> jobs = new ConcurrentHashMap<>();
    private final Map<Long, ReentrantLock> locks = new ConcurrentHashMap<>();
    private final Map<Long, ScheduledFuture<?>> pending = new ConcurrentHashMap<>();
    private volatile JDA jda;

    public EventSpaceOps(EventRepository events, EventService service, EventSpaceRepository space, EventSpaces spaces, EventChanges changes) {
        this.events = events;
        this.service = service;
        this.space = space;
        this.spaces = spaces;
        this.changes = changes;
        changes.subscribe(this::onChange);
    }

    @BEventListener
    public void onJdaReady(InjectedJDAEvent event) {
        this.jda = event.getJda();
        executor.scheduleWithFixedDelay(this::sweep, 1, SWEEP_MINUTES, TimeUnit.MINUTES);
        log.info("Event roles and channels are ready: they are kept in step with each event's roster every {} minutes and when it changes.", SWEEP_MINUTES);
    }

    @BEventListener
    public void onMemberJoin(GuildMemberJoinEvent event) {
        try {
            for (long eventId : space.eventsWithSpace(event.getGuild().getIdLong())) schedule(event.getGuild().getIdLong(), eventId);
        } catch (RuntimeException e) {
            log.warn("Could not look for roles to give {}", event.getUser().getId(), e);
        }
    }

    /** The latest background job of the event, running or finished, or null. */
    public Job job(long eventId) {
        return jobs.get(eventId);
    }

    private ReentrantLock lockOf(long eventId) {
        return locks.computeIfAbsent(eventId, id -> new ReentrantLock());
    }

    // ---------- setting up ----------

    /**
     * Starts setting up the event's roles and channels in the background. Refused when something stops it (see {@link Plan#blockers()}) or a job is already
     * running for the event. {@code removeUnneeded} also removes what JonnyBot made before that the event no longer wants (a deleted team's channel).
     */
    public Job startSetup(Guild guild, Event event, long actorId, boolean removeUnneeded) {
        Plan plan = spaces.preview(guild, event).plan();
        if (!plan.blockers().isEmpty()) throw new EventProblem(409, "The roles and channels can't be set up yet.", plan.blockers());
        return start(event.id(), "setup", job -> runSetup(guild, event.guildId(), event.id(), actorId, removeUnneeded, job));
    }

    /** Starts removing everything JonnyBot made for the event, in the background. */
    public Job startRemoval(Guild guild, Event event) {
        if (!space.hasSpace(event.id()) && !events.setup(event.id()).spaceApplied()) throw new EventProblem(409, "There is nothing to remove: no roles or channels were made for this event.");
        return start(event.id(), "removal", job -> runRemoval(guild, event.guildId(), event.id(), job));
    }

    private Job start(long eventId, String kind, java.util.function.Consumer<Job> work) {
        Job job = new Job(kind);
        synchronized (jobs) {
            Job running = jobs.get(eventId);
            if (running != null && running.running()) throw new EventProblem(409, "This event's roles and channels are already being " + (running.kind().equals("removal") ? "removed" : "set up") + ".");
            jobs.put(eventId, job);
        }
        executor.execute(() -> {
            ReentrantLock lock = lockOf(eventId);
            lock.lock();
            try {
                work.accept(job);
            } catch (RuntimeException e) {
                log.error("The roles and channels job for event {} failed", eventId, e);
                job.fail(sentence(e));
            } finally {
                lock.unlock();
            }
        });
        return job;
    }

    private static String sentence(RuntimeException e) {
        if (e instanceof EventProblem problem) return problem.getMessage();
        if (e instanceof ErrorResponseException discord) return "Discord refused: " + discord.getMeaning() + ".";
        return e.getMessage() == null || e.getMessage().isBlank() ? "Something went wrong: " + e.getClass().getSimpleName() + "." : e.getMessage();
    }

    private void runSetup(Guild guild, long guildId, long eventId, long actorId, boolean removeUnneeded, Job job) {
        Event event = service.event(guildId, eventId);
        EventSpaces.Gathered gathered = spaces.gather(guild, event);
        Plan plan = EventSpace.plan(gathered.facts());
        if (!plan.blockers().isEmpty()) {
            job.fail("It can't be set up yet: " + String.join(" ", plan.blockers()));
            return;
        }
        // from here the event counts as set up (or part way), so it can't be deleted while its roles and channels exist
        events.markSpace(eventId, actorId, false, "Setting up…");
        job.total = (int) (plan.newRoles() + plan.newChannels() + (removeUnneeded ? plan.count(Action.REMOVE) : 0)) + 1;

        Map<String, Long> roleIds = new LinkedHashMap<>();
        for (RoleItem r : plan.roles()) {
            if (r.action() == Action.KEEP) roleIds.put(r.key(), r.id());
        }
        for (RoleItem r : plan.roles()) {
            if (r.action() != Action.CREATE) continue;
            job.message = "Making the role " + r.name() + "…";
            Role role = guild.createRole().setName(r.name()).setPermissions(0L).setMentionable(false).setHoisted(false).reason("Roles for the event " + event.name()).complete();
            space.saveRole(eventId, r.key(), role.getIdLong(), role.getName());
            roleIds.put(r.key(), role.getIdLong());
            job.step("Made the role " + r.name() + ".");
        }

        List<Long> staff = gathered.staffRoles().stream().map(Role::getIdLong).toList();
        long everyone = guild.getPublicRole().getIdLong();
        long bot = guild.getSelfMember().getIdLong();
        java.util.function.Function<Principal, List<Long>> idsOf = who -> switch (who.who()) {
            case EVERYONE -> List.of(everyone);
            case STAFF -> staff;
            case ORGANISERS -> roleIds.containsKey(EventSpace.ORGANISER_ROLE) ? List.of(roleIds.get(EventSpace.ORGANISER_ROLE)) : List.of();
            case PARTICIPANTS -> roleIds.containsKey(EventSpace.PARTICIPANT_ROLE) ? List.of(roleIds.get(EventSpace.PARTICIPANT_ROLE)) : List.of();
            case TEAM -> roleIds.containsKey(EventSpace.teamKey(who.ref())) ? List.of(roleIds.get(EventSpace.teamKey(who.ref()))) : List.of();
            case PLAYER -> List.of(who.ref());
            case BOT -> List.of(bot);
        };

        Map<String, Category> categories = new HashMap<>();
        for (ChannelItem c : plan.channels()) {
            if (c.kind() == Kind.CATEGORY && c.action() == Action.KEEP && c.id() != null) {
                Category category = guild.getCategoryById(c.id());
                if (category != null) categories.put(c.key(), category);
            }
        }
        for (ChannelItem c : plan.channels()) {
            if (c.kind() != Kind.CATEGORY || c.action() != Action.CREATE) continue;
            job.message = "Making the category " + c.name() + "…";
            ChannelAction<Category> action = guild.createCategory(c.name());
            for (Overwrite o : EventSpace.overwrites(c.access(), idsOf)) action = o.isRole() ? action.addRolePermissionOverride(o.id(), o.allow(), o.deny()) : action.addMemberPermissionOverride(o.id(), o.allow(), o.deny());
            Category made = action.reason("Channels for the event " + event.name()).complete();
            space.saveChannel(eventId, c.key(), made.getIdLong(), made.getName(), true, null);
            categories.put(c.key(), made);
            job.step("Made the category " + c.name() + ".");
        }
        Map<String, String> teamNames = new HashMap<>();
        events.teams(eventId).forEach(t -> teamNames.put(EventSpace.teamKey(t.id()), t.name()));
        for (ChannelItem c : plan.channels()) {
            if (c.kind() == Kind.CATEGORY) continue;
            if (c.action() == Action.USE_EXISTING && c.id() != null) {
                GuildChannel chosen = guild.getGuildChannelById(c.id());
                space.saveChannel(eventId, c.key(), c.id(), chosen == null ? "" : chosen.getName(), false, null);
                useAsEngineChannel(guildId, eventId, c.kind(), c.id());
                continue;
            }
            if (c.action() != Action.CREATE) continue;
            Category parent = categories.get(c.parent());
            if (parent == null) throw new EventProblem(500, "The category for #" + c.name() + " wasn't made.");
            job.message = "Making the channel #" + c.name() + "…";
            ChannelAction<net.dv8tion.jda.api.entities.channel.concrete.TextChannel> action = guild.createTextChannel(c.name(), parent).setTopic(topicOf(c, event, teamNames));
            for (Overwrite o : EventSpace.overwrites(c.access(), idsOf)) action = o.isRole() ? action.addRolePermissionOverride(o.id(), o.allow(), o.deny()) : action.addMemberPermissionOverride(o.id(), o.allow(), o.deny());
            var made = action.reason("Channels for the event " + event.name()).complete();
            space.saveChannel(eventId, c.key(), made.getIdLong(), made.getName(), true, c.parent());
            useAsEngineChannel(guildId, eventId, c.kind(), made.getIdLong());
            job.step("Made the channel #" + c.name() + ".");
        }

        if (removeUnneeded) {
            for (RoleItem r : plan.roles()) {
                if (r.action() != Action.REMOVE) continue;
                deleteRole(guild, eventId, r.key(), r.id());
                job.step("Removed the role " + r.name() + ".");
            }
            for (ChannelItem c : plan.channels()) {
                if (c.action() != Action.REMOVE) continue;
                deleteChannel(guild, eventId, c.key(), c.id());
                job.step("Removed #" + c.name() + ".");
            }
        }

        job.message = "Giving out the roles…";
        int failed = syncMembers(guild, service.event(guildId, eventId));
        job.step("Gave out the roles.");
        Plan after = EventSpace.plan(spaces.gather(guild, service.event(guildId, eventId)).facts());
        events.markSpace(eventId, actorId, after.complete(), noteOf(after));
        changes.publish(EventChanges.Kind.SETUP, guildId, eventId);
        job.finish(after.complete() ? "The roles and channels are set up." + (failed > 0 ? " " + failed + " roles couldn't be given out yet." : "") : "Set up, but something is still missing: " + noteOf(after));
    }

    /** The topic a channel starts with, so people know what it is for. */
    private static String topicOf(ChannelItem c, Event event, Map<String, String> teamNames) {
        String name = event.name();
        String topic = switch (c.kind()) {
            case STAFF -> "Organisers and staff of " + name + ".";
            case ANNOUNCEMENTS -> "News from the organisers of " + name + ".";
            case GENERAL -> "Chat for everyone taking part in " + name + ".";
            case BOARD -> "The live board of " + name + ".";
            case REVIEW -> "Proof for staff to approve in " + name + ".";
            case TEAM -> teamNames.getOrDefault(c.key(), "A team") + "'s private channel for " + name + ".";
            case PLAYER -> "A private channel for " + name + ".";
            default -> "";
        };
        return topic.length() > 1000 ? topic.substring(0, 1000) : topic;
    }

    /** The event's board and proof channels are the ones the rest of the engine posts to, so a channel set up for those becomes the event's setting. */
    private void useAsEngineChannel(long guildId, long eventId, Kind kind, long channelId) {
        String key = kind == Kind.BOARD ? "boardChannelId" : kind == Kind.REVIEW ? "reviewChannelId" : null;
        if (key == null) return;
        Event event = service.event(guildId, eventId);
        DataObject config = DataObject.fromJson(event.config());
        String id = Long.toString(channelId);
        if (config.hasKey(key) && !config.isNull(key) && id.equals(config.getString(key))) return;
        config.put(key, id);
        events.setConfig(guildId, eventId, config.toString());
    }

    private static String noteOf(Plan plan) {
        String note = String.join(", ", plan.missing());
        return note.length() > NOTE_LIMIT ? note.substring(0, NOTE_LIMIT - 1) + "…" : note;
    }

    private void deleteRole(Guild guild, long eventId, String key, Long id) {
        Role role = id == null ? null : guild.getRoleById(id);
        try {
            if (role != null) role.delete().reason("Removing the roles of an event").complete();
        } catch (ErrorResponseException e) {
            if (e.getErrorResponse() != ErrorResponse.UNKNOWN_ROLE) throw e;
        }
        space.deleteRole(eventId, key);
    }

    private void deleteChannel(Guild guild, long eventId, String key, Long id) {
        GuildChannel channel = id == null ? null : guild.getGuildChannelById(id);
        try {
            if (channel != null) channel.delete().reason("Removing the channels of an event").complete();
        } catch (ErrorResponseException e) {
            if (e.getErrorResponse() != ErrorResponse.UNKNOWN_CHANNEL) throw e;
        }
        space.deleteChannel(eventId, key);
    }

    // ---------- taking it down ----------

    private void runRemoval(Guild guild, long guildId, long eventId, Job job) {
        List<EventSpaceRepository.ChannelRow> channels = space.channels(eventId);
        List<EventSpaceRepository.RoleRow> roles = space.roles(eventId);
        job.total = (int) (channels.stream().filter(EventSpaceRepository.ChannelRow::owned).count() + roles.size()) + 1;
        Event event = service.event(guildId, eventId);
        DataObject config = DataObject.fromJson(event.config());
        // children before the categories that hold them
        List<EventSpaceRepository.ChannelRow> ordered = new ArrayList<>(channels.stream().filter(c -> !EventSpace.isCategoryKey(c.key())).toList());
        ordered.addAll(channels.stream().filter(c -> EventSpace.isCategoryKey(c.key())).toList());
        for (EventSpaceRepository.ChannelRow c : ordered) {
            if (!c.owned()) {
                space.deleteChannel(eventId, c.key());
                continue;
            }
            job.message = "Removing #" + c.name() + "…";
            deleteChannel(guild, eventId, c.key(), c.channelId());
            for (String key : new String[]{"boardChannelId", "reviewChannelId"}) {
                if (config.hasKey(key) && !config.isNull(key) && Long.toString(c.channelId()).equals(config.getString(key))) config.remove(key);
            }
            job.step("Removed #" + c.name() + ".");
        }
        for (EventSpaceRepository.RoleRow r : roles) {
            job.message = "Removing the role " + r.name() + "…";
            deleteRole(guild, eventId, r.key(), r.roleId());
            job.step("Removed the role " + r.name() + ".");
        }
        space.clear(eventId);
        events.clearSpace(eventId);
        events.setConfig(guildId, eventId, config.toString());
        changes.publish(EventChanges.Kind.SETUP, guildId, eventId);
        job.step("Done.");
        job.finish("Everything JonnyBot made for this event has been removed.");
    }

    // ---------- who holds the roles ----------

    /**
     * Gives and takes the event's roles so they match who is on the roster, in which team, and who the organisers are, and takes the access of a player
     * who left from their own channel. Only roles JonnyBot made, and only people it gave them to, are touched. Returns how many changes failed (people who
     * aren't in the server yet don't count: they get their role when they join).
     */
    public int syncMembers(Guild guild, Event event) {
        ReentrantLock lock = lockOf(event.id());
        boolean mine = !lock.isHeldByCurrentThread();
        if (mine) lock.lock();
        try {
            return sync(guild, event);
        } finally {
            if (mine) lock.unlock();
        }
    }

    private int sync(Guild guild, Event event) {
        List<EventSpaceRepository.RoleRow> rows = space.roles(event.id());
        if (rows.isEmpty()) return 0;
        Map<String, Role> roles = new LinkedHashMap<>();
        for (EventSpaceRepository.RoleRow row : rows) {
            Role role = guild.getRoleById(row.roleId());
            if (role != null) roles.put(row.key(), role);
        }
        EventSpaces.Gathered gathered = spaces.gather(guild, event);
        EventSettings settings = gathered.settings();
        boolean teamScored = event.scoringMode() == EventRules.ScoringMode.TEAM;
        Map<String, Set<Long>> wanted = EventSpace.wantedMembers(teamScored, settings.channels().teamChannels(), gathered.facts().players(), settings.organisers());
        Map<String, Set<Long>> granted = space.members(event.id());
        EventSpace.Changes changes = EventSpace.changes(wanted, granted, roles.keySet());
        int failed = 0;
        for (Map.Entry<String, Set<Long>> e : changes.add().entrySet()) {
            Role role = roles.get(e.getKey());
            for (long userId : e.getValue()) {
                Member member = guild.getMemberById(userId);
                if (member != null && member.getRoles().contains(role)) {
                    space.addMember(event.id(), e.getKey(), userId);
                    continue;
                }
                try {
                    guild.addRoleToMember(UserSnowflake.fromId(userId), role).reason("Taking part in " + event.name()).complete();
                    space.addMember(event.id(), e.getKey(), userId);
                } catch (ErrorResponseException ex) {
                    if (ex.getErrorResponse() == ErrorResponse.UNKNOWN_MEMBER || ex.getErrorResponse() == ErrorResponse.UNKNOWN_USER) continue; // not in the server yet
                    failed++;
                    log.warn("Could not give {} the role {} for event {}: {}", userId, role.getName(), event.id(), ex.getMeaning());
                }
            }
        }
        for (Map.Entry<String, Set<Long>> e : changes.remove().entrySet()) {
            Role role = roles.get(e.getKey());
            for (long userId : e.getValue()) {
                try {
                    if (role != null) guild.removeRoleFromMember(UserSnowflake.fromId(userId), role).reason("No longer taking part in " + event.name()).complete();
                    space.removeMember(event.id(), e.getKey(), userId);
                } catch (ErrorResponseException ex) {
                    if (ex.getErrorResponse() == ErrorResponse.UNKNOWN_MEMBER || ex.getErrorResponse() == ErrorResponse.UNKNOWN_USER) {
                        space.removeMember(event.id(), e.getKey(), userId);
                        continue;
                    }
                    failed++;
                    log.warn("Could not take the role {} from {} for event {}: {}", role == null ? e.getKey() : role.getName(), userId, event.id(), ex.getMeaning());
                }
            }
        }
        failed += revokeLeftPlayersChannels(guild, event, wanted.getOrDefault(EventSpace.PARTICIPANT_ROLE, Set.of()));
        return failed;
    }

    /** A player who is no longer in a solo event loses the access to the channel made for them. */
    private int revokeLeftPlayersChannels(Guild guild, Event event, Set<Long> players) {
        int failed = 0;
        for (EventSpaceRepository.ChannelRow row : space.channels(event.id())) {
            if (!row.owned() || !row.key().startsWith("player:")) continue;
            long userId;
            try {
                userId = Long.parseLong(row.key().substring("player:".length()));
            } catch (NumberFormatException e) {
                continue;
            }
            if (players.contains(userId)) continue;
            GuildChannel channel = guild.getGuildChannelById(row.channelId());
            if (!(channel instanceof IPermissionContainer container)) continue;
            var override = container.getPermissionOverrides().stream().filter(o -> o.isMemberOverride() && o.getIdLong() == userId).findFirst().orElse(null);
            if (override == null) continue;
            try {
                override.delete().reason("No longer taking part in " + event.name()).complete();
            } catch (ErrorResponseException e) {
                failed++;
                log.warn("Could not take {}'s access to their channel in event {}: {}", userId, event.id(), e.getMeaning());
            }
        }
        return failed;
    }

    // ---------- keeping it right ----------

    private void onChange(EventChanges.Change change) {
        switch (change.kind()) {
            case ROSTER, TEAMS, STAGE, SETUP -> schedule(change.guildId(), change.eventId());
            default -> { }
        }
    }

    private void schedule(long guildId, long eventId) {
        if (jda == null) return;
        ScheduledFuture<?> old = pending.put(eventId, executor.schedule(() -> {
            pending.remove(eventId);
            reconcile(guildId, eventId);
        }, DEBOUNCE_SECONDS, TimeUnit.SECONDS));
        if (old != null) old.cancel(false);
    }

    private void sweep() {
        JDA client = jda;
        if (client == null) return;
        for (Guild guild : client.getGuilds()) {
            try {
                for (long eventId : space.eventsWithSpace(guild.getIdLong())) reconcile(guild.getIdLong(), eventId);
            } catch (RuntimeException e) {
                log.warn("Could not keep the roles of {}'s events in step", guild.getId(), e);
            }
        }
    }

    /** Puts one event's role holders right and records whether everything wanted is still there. Does nothing for an event with no roles and channels, or one that is over. */
    void reconcile(long guildId, long eventId) {
        JDA client = jda;
        if (client == null) return;
        try {
            Guild guild = client.getGuildById(guildId);
            Event event = events.event(guildId, eventId).orElse(null);
            if (guild == null || event == null || event.status() == Status.DONE || event.status() == Status.CANCELLED || !space.hasSpace(eventId)) return;
            ReentrantLock lock = lockOf(eventId);
            if (!lock.tryLock()) return; // a setup or removal is running; it ends with the same work
            try {
                syncMembers(guild, event);
                EventRepository.Setup setup = events.setup(eventId);
                if (setup.spaceApplied() && !jobRunning(eventId)) {
                    Plan plan = EventSpace.plan(spaces.gather(guild, event).facts());
                    String note = noteOf(plan);
                    if (plan.complete() != setup.spaceComplete() || !note.equals(setup.spaceNote())) events.markSpace(eventId, null, plan.complete(), note);
                }
            } finally {
                lock.unlock();
            }
        } catch (RuntimeException e) {
            log.warn("Could not keep the roles and channels of event {} right", eventId, e);
        }
    }

    private boolean jobRunning(long eventId) {
        Job job = jobs.get(eventId);
        return job != null && job.running();
    }
}
