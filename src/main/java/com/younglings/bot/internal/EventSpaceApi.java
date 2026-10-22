package com.younglings.bot.internal;

import com.younglings.bot.events.EventBoards;
import com.younglings.bot.events.EventProblem;
import com.younglings.bot.events.EventRepository;
import com.younglings.bot.events.EventRepository.Event;
import com.younglings.bot.events.EventService;
import com.younglings.bot.events.EventSettings;
import com.younglings.bot.events.EventSpace;
import com.younglings.bot.events.EventSpace.Action;
import com.younglings.bot.events.EventSpace.ChannelItem;
import com.younglings.bot.events.EventSpace.Grant;
import com.younglings.bot.events.EventSpace.Plan;
import com.younglings.bot.events.EventSpace.RoleItem;
import com.younglings.bot.events.EventSpaceOps;
import com.younglings.bot.events.EventSpaces;
import com.younglings.bot.i18n.Text;
import com.younglings.bot.internal.TicketAdminApi.ApiError;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.function.Supplier;

/**
 * The dashboard's routes for an event's roles and channels, under {@code events/{id}/space}:
 * {@code GET} says where they stand (the choices, whether they are set up, and a plan of what setting up would do now), and
 * {@code PUT .../settings} saves the choices ({@code config.channels}, the whole object), {@code POST .../preview} shows the plan for choices without saving
 * them, {@code POST .../apply} starts setting them up on Discord (in the background; {@code GET} shows how far it got), {@code POST .../remove} starts taking
 * them down and {@code POST .../sync} puts who holds each role right at once. Every rule is {@link EventSpace}'s and the engine's. Reached through
 * {@link TicketAdminApi}, which has already checked the asker may use the dashboard in this server and audit-logs every change.
 */
@BService
public class EventSpaceApi implements AdminRouteExtension {
    private static final Logger log = LoggerFactory.getLogger(EventSpaceApi.class);

    private final EventService service;
    private final EventRepository repository;
    private final EventSpaces spaces;
    private final EventSpaceOps ops;

    public EventSpaceApi(EventService service, EventRepository repository, EventSpaces spaces, EventSpaceOps ops) {
        this.service = service;
        this.repository = repository;
        this.spaces = spaces;
        this.ops = ops;
    }

    @Override
    public DataObject route(Guild guild, Member actor, String method, String[] parts, Supplier<DataObject> body) {
        if (parts.length < 3 || !parts[0].equals("events") || !parts[2].equals("space")) return null;
        try {
            long id = idOf(parts[1]);
            if (parts.length == 3) {
                AdminRouteExtension.requireMethod(method, "GET");
                return view(guild, id);
            }
            if (parts.length == 4 && parts[3].equals("apply")) {
                AdminRouteExtension.requireMethod(method, "POST");
                DataObject b = body.get();
                ops.startSetup(guild, service.event(guild.getIdLong(), id), actor.getIdLong(), b.hasKey("removeUnneeded") && b.getBoolean("removeUnneeded", false));
                log.info("Dashboard: {} started setting up the roles and channels of event {}", actor.getId(), id);
                return view(guild, id);
            }
            if (parts.length == 4 && parts[3].equals("remove")) {
                AdminRouteExtension.requireMethod(method, "POST");
                DataObject b = body.get();
                if (!b.getBoolean("confirm", false)) throw new ApiError(400, Text.of(guild, "dashboard.say_that_you_are_sure_this"));
                ops.startRemoval(guild, service.event(guild.getIdLong(), id));
                log.info("Dashboard: {} started removing the roles and channels of event {}", actor.getId(), id);
                return view(guild, id);
            }
            if (parts.length == 4 && parts[3].equals("sync")) {
                AdminRouteExtension.requireMethod(method, "POST");
                int failed = ops.syncMembers(guild, service.event(guild.getIdLong(), id));
                log.info("Dashboard: {} put the role holders of event {} right ({} failed)", actor.getId(), id, failed);
                return view(guild, id).put("syncFailed", failed);
            }
            if (parts.length == 4 && parts[3].equals("preview")) {
                AdminRouteExtension.requireMethod(method, "POST");
                return preview(guild, id, body.get());
            }
            if (parts.length == 4 && parts[3].equals("settings")) {
                AdminRouteExtension.requireMethod(method, "PUT");
                service.setChannelSettings(guild.getIdLong(), id, body.get());
                log.info("Dashboard: {} changed the roles and channels of event {}", actor.getId(), id);
                return view(guild, id);
            }
            return null;
        } catch (EventProblem e) {
            throw new ApiError(e.status() == 500 ? 500 : e.status(), e.getMessage(), e.problems());
        }
    }

    private static long idOf(String raw) {
        if (raw == null || !raw.matches("\\d{1,18}")) throw new ApiError(400, Text.en("dashboard.that_isn_t_a_valid_event"));
        return Long.parseLong(raw);
    }

    /** What the plan would be with {@code channels} as the settings, without saving them (the page shows this while an organiser is still typing). */
    private DataObject preview(Guild guild, long id, DataObject channels) {
        Event event = service.event(guild.getIdLong(), id);
        DataObject config = DataObject.empty().put("channels", channels);
        java.util.List<String> problems = EventSpace.problems(config);
        if (!problems.isEmpty()) throw new ApiError(400, Text.of(guild, "dashboard.those_choices_can_t_be_used"), problems);
        return view(guild, event, EventSpace.parse(config));
    }

    /** The choices, the state and the plan for one event. */
    DataObject view(Guild guild, long id) {
        return view(guild, service.event(guild.getIdLong(), id), null);
    }

    private DataObject view(Guild guild, Event event, EventSpace.Setting instead) {
        long id = event.id();
        EventSpaces.Preview preview = spaces.preview(guild, event, instead);
        EventSettings settings = preview.gathered().settings();
        Plan plan = preview.plan();
        EventRepository.Setup setup = repository.setup(id);

        DataArray roles = DataArray.empty();
        plan.roles().forEach(r -> roles.add(roleJson(r)));
        DataArray channels = DataArray.empty();
        plan.channels().forEach(c -> channels.add(channelJson(guild, c)));
        DataArray blockers = DataArray.empty();
        plan.blockers().forEach(blockers::add);
        DataArray warnings = DataArray.empty();
        plan.warnings().forEach(warnings::add);
        DataArray staff = DataArray.empty();
        for (Role role : preview.gathered().staffRoles()) staff.add(DataObject.empty().put("id", role.getId()).put("name", role.getName()));

        DataObject state = DataObject.empty().put("enabled", settings.channels().enabled()).put("applied", setup.spaceApplied()).put("complete", setup.spaceComplete())
                .put("appliedAt", setup.spaceAppliedAt() == null ? null : setup.spaceAppliedAt().toString()).put("appliedBy", setup.spaceAppliedBy() == null ? null : nameOf(guild, setup.spaceAppliedBy()))
                .put("note", setup.spaceNote());
        DataObject summary = DataObject.empty().put("newRoles", plan.newRoles()).put("newChannels", plan.newChannels()).put("toRemove", plan.count(Action.REMOVE)).put("toRepair", plan.count(Action.REPAIR))
                .put("players", preview.gathered().facts().players().size()).put("excluded", preview.gathered().facts().excludedPlayers());
        EventSpaceOps.Job job = ops.job(id);
        DataObject jobJson = job == null ? null : DataObject.empty().put("kind", job.kind()).put("state", job.state()).put("done", job.done()).put("total", job.total()).put("message", job.message())
                .put("startedAt", job.startedAt().toString()).put("finishedAt", job.finishedAt() == null ? null : job.finishedAt().toString());
        DataObject welcome = DataObject.empty();
        com.younglings.bot.events.EventWelcome.of(DataObject.fromJson(event.config())).forEach(welcome::put);
        return DataObject.empty().put("job", jobJson).put("settings", EventSpace.settingJson(settings.channels(), event.template(), settings.boardChannelId(), settings.reviewChannelId(),
                EventBoards.displayOf(DataObject.fromJson(event.config())).shared()).put("welcome", welcome))
                .put("teamEvent", preview.gathered().facts().teamScored()).put("state", state).put("ready", plan.blockers().isEmpty()).put("complete", plan.complete())
                .put("blockers", blockers).put("warnings", warnings).put("roles", roles).put("channels", channels).put("staffRoles", staff).put("summary", summary);
    }

    private static DataObject roleJson(RoleItem r) {
        return DataObject.empty().put("key", r.key()).put("name", r.name()).put("action", action(r.action())).put("id", r.id() == null ? null : Long.toString(r.id()))
                .put("members", r.members()).put("note", r.note());
    }

    private static DataObject channelJson(Guild guild, ChannelItem c) {
        String name = c.name();
        if (c.action() == Action.USE_EXISTING && c.id() != null) {
            GuildChannel channel = guild.getGuildChannelById(c.id());
            name = channel == null ? "(not found)" : channel.getName();
        }
        DataArray access = DataArray.empty();
        for (Grant g : c.access()) {
            access.add(DataObject.empty().put("who", g.who().who().name().toLowerCase(Locale.ROOT)).put("ref", g.who().ref() == 0 ? null : Long.toString(g.who().ref()))
                    .put("level", g.level().name().toLowerCase(Locale.ROOT)));
        }
        return DataObject.empty().put("key", c.key()).put("kind", c.kind().key()).put("kindLabel", c.kind().label()).put("name", name).put("action", action(c.action()))
                .put("id", c.id() == null ? null : Long.toString(c.id())).put("parent", c.parent()).put("note", c.note()).put("access", access);
    }

    private static String action(Action action) {
        return action.name().toLowerCase(Locale.ROOT);
    }

    private static String nameOf(Guild guild, long userId) {
        Member member = guild.getMemberById(userId);
        return member == null ? null : member.getEffectiveName();
    }
}
